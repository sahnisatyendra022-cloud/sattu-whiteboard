package com.example.whiteboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Whiteboard canvas.
 *
 * - Finished strokes are rasterised into a transparent off-screen bitmap ([cacheBitmap]), so
 *   each frame only draws one bitmap plus the stroke currently being drawn.
 * - The eraser uses PorterDuff.CLEAR on that transparent bitmap, so erasing really removes ink
 *   (and exported PNGs are correct); the white background is painted separately.
 * - Stroke history lives in [DrawingState] (owned by a ViewModel), so rotation never loses work.
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var state = DrawingState()

    private var cacheBitmap: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private var currentStroke: CustomPath? = null

    private val density = resources.displayMetrics.density
    private val touchTolerance = 2f * density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isDither = true
    }
    private val clearXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    /** Called whenever undo/redo availability may have changed. */
    var onHistoryChanged: (() -> Unit)? = null

    val canUndo: Boolean get() = state.history.isNotEmpty()
    val canRedo: Boolean get() = state.redoStack.isNotEmpty()
    val isEmpty: Boolean get() = state.history.isEmpty()

    init {
        // Keeps the view in a GPU texture; onDraw only re-runs when we call invalidate().
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // region public API -------------------------------------------------------------------

    /** Binds this view to a (ViewModel-owned) state. Call once, right after inflation. */
    fun attachState(newState: DrawingState) {
        state = newState
        currentStroke = null
        if (width > 0 && height > 0) fitStrokesToSize(width, height)
        rebuildCache()
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun undo() {
        if (state.history.isEmpty()) return
        state.redoStack.add(state.history.removeAt(state.history.lastIndex))
        rebuildCache()
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun redo() {
        if (state.redoStack.isEmpty()) return
        val stroke = state.redoStack.removeAt(state.redoStack.lastIndex)
        state.history.add(stroke)
        cacheCanvas?.let { drawStroke(it, stroke) }
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun clearCanvas() {
        if (state.history.isEmpty() && state.redoStack.isEmpty()) return
        state.history.clear()
        state.redoStack.clear()
        rebuildCache()
        invalidate()
        onHistoryChanged?.invoke()
    }

    /** Returns a new opaque bitmap (white background + drawing) or null if not laid out yet. */
    fun createExportBitmap(): Bitmap? {
        val src = cacheBitmap ?: return null
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    // endregion

    // region lifecycle / sizing -----------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        currentStroke = null
        fitStrokesToSize(w, h)
        createCache(w, h)
        rebuildCache()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (cacheBitmap == null && width > 0 && height > 0) {
            createCache(width, height)
            rebuildCache()
        }
    }

    override fun onDetachedFromWindow() {
        // Drop the reference only; recycle() could crash if the RenderThread still uses it.
        cacheBitmap = null
        cacheCanvas = null
        currentStroke = null
        super.onDetachedFromWindow()
    }

    private fun createCache(w: Int, h: Int) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cacheBitmap = bmp
        cacheCanvas = Canvas(bmp)
    }

    /** After rotation the surface changes size; scale existing strokes uniformly to fit. */
    private fun fitStrokesToSize(w: Int, h: Int) {
        val sw = state.surfaceWidth
        val sh = state.surfaceHeight
        if (sw > 0 && sh > 0 && (sw != w || sh != h)) {
            val scale = min(w.toFloat() / sw, h.toFloat() / sh)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((w - sw * scale) / 2f, (h - sh * scale) / 2f)
            }
            state.transform(matrix)
        }
        state.surfaceWidth = w
        state.surfaceHeight = h
    }

    // endregion

    // region drawing ----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val bmp = cacheBitmap ?: return
        val stroke = currentStroke

        when {
            stroke == null -> canvas.drawBitmap(bmp, 0f, 0f, null)

            stroke.isEraser -> {
                // CLEAR only works inside an off-screen layer, otherwise it would punch
                // through to the window background instead of just removing ink.
                val saved = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                drawStroke(canvas, stroke)
                canvas.restoreToCount(saved)
            }

            else -> {
                canvas.drawBitmap(bmp, 0f, 0f, null)
                drawStroke(canvas, stroke)
            }
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: CustomPath) {
        strokePaint.strokeWidth = stroke.strokeWidth
        if (stroke.isEraser) {
            strokePaint.xfermode = clearXfermode
            strokePaint.color = Color.TRANSPARENT
        } else {
            strokePaint.xfermode = null
            strokePaint.color = stroke.color
        }
        canvas.drawPath(stroke.path, strokePaint)
    }

    private fun rebuildCache() {
        val bmp = cacheBitmap ?: return
        val canvas = cacheCanvas ?: return
        bmp.eraseColor(Color.TRANSPARENT)
        state.history.forEach { drawStroke(canvas, it) }
    }

    // endregion

    // region touch ------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startStroke(event.x, event.y)
            }

            MotionEvent.ACTION_MOVE -> {
                if (currentStroke == null) return true
                // Historical points give much smoother curves on fast finger/stylus movement.
                for (i in 0 until event.historySize) {
                    addPoint(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                addPoint(event.x, event.y)
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                addPoint(event.x, event.y)
                finishStroke()
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> finishStroke()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun startStroke(x: Float, y: Float) {
        val eraser = state.isEraser
        val widthPx = state.brushSizeDp * density * (if (eraser) ERASER_SCALE else 1f)
        currentStroke = CustomPath(state.penColor, widthPx, eraser).also { it.begin(x, y) }
        invalidate()
    }

    private fun addPoint(x: Float, y: Float) {
        val stroke = currentStroke ?: return
        if (abs(x - stroke.lastX) >= touchTolerance || abs(y - stroke.lastY) >= touchTolerance) {
            stroke.extend(x, y)
        }
    }

    private fun finishStroke() {
        val stroke = currentStroke ?: return
        currentStroke = null
        stroke.finish()
        state.history.add(stroke)
        state.redoStack.clear() // a new action invalidates the redo history
        cacheCanvas?.let { drawStroke(it, stroke) }
        invalidate()
        onHistoryChanged?.invoke()
    }

    // endregion

    private companion object {
        const val ERASER_SCALE = 2f
    }
}
