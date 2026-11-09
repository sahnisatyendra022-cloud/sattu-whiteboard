package com.example.whiteboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Region
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
    private var regionGesture: Path? = null
    private val selectedStrokes = ArrayList<CustomPath>()
    private var movingSelection = false
    private var moveStartX = 0f
    private var moveStartY = 0f
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    var tool: DrawingTool = DrawingTool.PEN
        private set
    var shapeType: ShapeType = ShapeType.RECTANGLE
        private set
    var regionType: RegionType = RegionType.RECTANGLE
        private set

    private val density = resources.displayMetrics.density
    private val touchTolerance = 2f * density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isDither = true
    }
    private val clearXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(33, 150, 243)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
    }

    /** Called whenever undo/redo availability may have changed. */
    var onHistoryChanged: (() -> Unit)? = null

    val canUndo: Boolean get() = state.undoStack.isNotEmpty()
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
        selectedStrokes.clear()
        regionGesture = null
        if (width > 0 && height > 0) fitStrokesToSize(width, height)
        rebuildCache()
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun undo() {
        if (state.undoStack.isEmpty()) return
        when (val action = state.undoStack.removeAt(state.undoStack.lastIndex)) {
            is CanvasAction.StrokeAdded -> state.history.remove(action.stroke)
            is CanvasAction.StrokesMoved -> transformStrokes(action.strokes, -action.dx, -action.dy)
        }
        state.redoStack.add(action)
        selectedStrokes.clear()
        rebuildCache()
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun redo() {
        if (state.redoStack.isEmpty()) return
        val action = state.redoStack.removeAt(state.redoStack.lastIndex)
        when (action) {
            is CanvasAction.StrokeAdded -> state.history.add(action.stroke)
            is CanvasAction.StrokesMoved -> transformStrokes(action.strokes, action.dx, action.dy)
        }
        state.undoStack.add(action)
        selectedStrokes.clear()
        rebuildCache()
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun clearCanvas() {
        if (state.history.isEmpty() && state.redoStack.isEmpty()) return
        state.history.clear()
        state.undoStack.clear()
        state.redoStack.clear()
        selectedStrokes.clear()
        rebuildCache()
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun setTool(newTool: DrawingTool) {
        tool = newTool
        currentStroke = null
        regionGesture = null
        if (newTool != DrawingTool.SELECT) selectedStrokes.clear()
        invalidate()
    }

    fun setShapeType(type: ShapeType) {
        shapeType = type
        tool = DrawingTool.SHAPE
        invalidate()
    }

    fun setRegionType(type: RegionType) {
        regionType = type
        invalidate()
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

            stroke != null && stroke.isEraser -> {
                // CLEAR only works inside an off-screen layer, otherwise it would punch
                // through to the window background instead of just removing ink.
                val saved = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                drawStroke(canvas, stroke)
                canvas.restoreToCount(saved)
            }
            else -> {
                canvas.drawBitmap(bmp, 0f, 0f, null)
                stroke?.let { drawStroke(canvas, it) }
            }
        }
        selectedStrokes.forEach { selected ->
            canvas.drawRect(strokeBounds(selected), regionPaint)
        }
        regionGesture?.let { canvas.drawPath(it, regionPaint) }
    }

    private fun drawStroke(canvas: Canvas, stroke: CustomPath) {
        strokePaint.strokeWidth = stroke.strokeWidth
        strokePaint.style = if (stroke.isFilled) Paint.Style.FILL else Paint.Style.STROKE
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
                when (tool) {
                    DrawingTool.PEN, DrawingTool.SHAPE -> startStroke(event.x, event.y)
                    DrawingTool.AREA_ERASER -> startRegion(event.x, event.y)
                    DrawingTool.SELECT -> {
                        if (selectedStrokes.isNotEmpty() && selectedStrokes.any {
                                strokeBounds(it).contains(event.x, event.y)
                            }
                        ) {
                            movingSelection = true
                            moveStartX = event.x
                            moveStartY = event.y
                            lastMoveX = event.x
                            lastMoveY = event.y
                        } else {
                            startRegion(event.x, event.y)
                        }
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (movingSelection) {
                    moveSelected(event.x, event.y)
                } else if (regionGesture != null) {
                    updateRegion(event.x, event.y)
                } else if (currentStroke != null) {
                    // Historical points give much smoother curves on fast finger/stylus movement.
                    for (i in 0 until event.historySize) {
                        addPoint(event.getHistoricalX(i), event.getHistoricalY(i))
                    }
                    addPoint(event.x, event.y)
                }
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                when {
                    movingSelection -> finishSelectionMove()
                    regionGesture != null -> finishRegion(event.x, event.y)
                    currentStroke != null -> {
                        addPoint(event.x, event.y)
                        finishStroke()
                    }
                }
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (currentStroke != null) finishStroke()
                regionGesture = null
                if (movingSelection) finishSelectionMove()
                invalidate()
            }
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
        val shape = if (tool == DrawingTool.SHAPE) shapeType else ShapeType.FREEHAND
        currentStroke = CustomPath(
            state.penColor,
            widthPx,
            eraser,
            shape,
            isFilled = tool == DrawingTool.SHAPE && state.fillShapes && shape != ShapeType.LINE
        ).also {
            it.begin(x, y)
            if (shape != ShapeType.FREEHAND) it.updateShape(x, y)
        }
        invalidate()
    }

    private fun startRegion(x: Float, y: Float) {
        selectedStrokes.clear()
        regionGesture = Path().apply {
            if (regionType == RegionType.LASSO) moveTo(x, y)
            else updateRegionPath(this, x, y, x, y)
        }
        lastMoveX = x
        lastMoveY = y
        invalidate()
    }

    private fun updateRegion(x: Float, y: Float) {
        val path = regionGesture ?: return
        if (regionType == RegionType.LASSO) {
            path.lineTo(x, y)
        } else {
            updateRegionPath(path, lastMoveX, lastMoveY, x, y)
        }
    }

    private fun updateRegionPath(path: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        val left = minOf(x1, x2)
        val right = maxOf(x1, x2)
        val top = minOf(y1, y2)
        val bottom = maxOf(y1, y2)
        path.reset()
        if (regionType == RegionType.OVAL) path.addOval(left, top, right, bottom, Path.Direction.CW)
        else path.addRect(left, top, right, bottom, Path.Direction.CW)
    }

    private fun finishRegion(x: Float, y: Float) {
        updateRegion(x, y)
        val region = regionGesture
        regionGesture = null
        if (region != null) {
            if (regionType == RegionType.LASSO) region.close()
            if (tool == DrawingTool.AREA_ERASER) {
                val bounds = RectF()
                region.computeBounds(bounds, true)
                if (bounds.width() > 1f && bounds.height() > 1f) {
                    val eraser = CustomPath(Color.TRANSPARENT, 1f, true, isFilled = true)
                    eraser.path.set(region)
                    state.history.add(eraser)
                    state.undoStack.add(CanvasAction.StrokeAdded(eraser))
                    state.redoStack.clear()
                }
                selectedStrokes.clear()
            } else {
                selectedStrokes.addAll(state.history.filter { pathIntersectsRegion(it, region) })
            }
        }
        rebuildCache()
        invalidate()
        onHistoryChanged?.invoke()
    }

    private fun moveSelected(x: Float, y: Float) {
        val dx = x - lastMoveX
        val dy = y - lastMoveY
        if (dx == 0f && dy == 0f) return
        val matrix = Matrix().apply { setTranslate(dx, dy) }
        selectedStrokes.forEach { it.path.transform(matrix) }
        lastMoveX = x
        lastMoveY = y
        rebuildCache()
    }

    private fun finishSelectionMove() {
        movingSelection = false
        val dx = lastMoveX - moveStartX
        val dy = lastMoveY - moveStartY
        if (dx != 0f || dy != 0f) {
            state.undoStack.add(CanvasAction.StrokesMoved(selectedStrokes.toList(), dx, dy))
            state.redoStack.clear()
            onHistoryChanged?.invoke()
        }
    }

    private fun transformStrokes(strokes: List<CustomPath>, dx: Float, dy: Float) {
        val matrix = Matrix().apply { setTranslate(dx, dy) }
        strokes.forEach { it.path.transform(matrix) }
    }

    private fun strokeBounds(stroke: CustomPath): RectF =
        RectF().also {
            stroke.path.computeBounds(it, true)
            val padding = stroke.strokeWidth / 2f
            it.inset(-padding, -padding)
        }

    private fun pathIntersectsRegion(stroke: CustomPath, selection: Path): Boolean {
        val selectionBounds = RectF()
        selection.computeBounds(selectionBounds, true)
        if (!strokeBounds(stroke).intersect(selectionBounds)) return false

        val clipBounds = Region(
            selectionBounds.left.toInt(),
            selectionBounds.top.toInt(),
            selectionBounds.right.toInt() + 1,
            selectionBounds.bottom.toInt() + 1
        )
        val selectionRegion = Region()
        selectionRegion.setPath(selection, clipBounds)

        val outline = Path()
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = if (stroke.isFilled) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = stroke.strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        outlinePaint.getFillPath(stroke.path, outline)
        val strokeRegion = Region()
        strokeRegion.setPath(outline, clipBounds)
        return strokeRegion.op(selectionRegion, Region.Op.INTERSECT) && !strokeRegion.isEmpty
    }

    private fun addPoint(x: Float, y: Float) {
        val stroke = currentStroke ?: return
        if (stroke.shape != ShapeType.FREEHAND) {
            stroke.updateShape(x, y)
            return
        }
        if (abs(x - stroke.lastX) >= touchTolerance || abs(y - stroke.lastY) >= touchTolerance) {
            stroke.extend(x, y)
        }
    }

    private fun finishStroke() {
        val stroke = currentStroke ?: return
        currentStroke = null
        stroke.finish()
        state.history.add(stroke)
        state.undoStack.add(CanvasAction.StrokeAdded(stroke))
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
