package com.example.whiteboard

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import androidx.lifecycle.ViewModel

enum class ShapeType {
    FREEHAND, RECTANGLE, ROUNDED_RECTANGLE, OVAL, TRIANGLE, DIAMOND, PENTAGON, HEXAGON, STAR, ARROW, LINE
}
enum class RegionType { RECTANGLE, OVAL, LASSO }
enum class DrawingTool { PEN, AREA_ERASER, SELECT, SHAPE }

sealed class CanvasAction {
    data class StrokeAdded(val stroke: CustomPath) : CanvasAction()
    data class StrokesMoved(val strokes: List<CustomPath>, val dx: Float, val dy: Float) : CanvasAction()

    fun affectedStrokes(): List<CustomPath> = when (this) {
        is StrokeAdded -> listOf(stroke)
        is StrokesMoved -> strokes
    }
}

/**
 * One finished (or in-progress) stroke. Pure data + a Path: it holds no reference to any
 * View or Context, so it is safe to keep inside a ViewModel across configuration changes.
 */
class CustomPath(
    val color: Int,
    val strokeWidth: Float,
    val isEraser: Boolean,
    val shape: ShapeType = ShapeType.FREEHAND,
    val isFilled: Boolean = false
) {
    val path = Path()

    private var startX = 0f
    private var startY = 0f
    var lastX = 0f
        private set
    var lastY = 0f
        private set
    private var hasSegments = false

    fun begin(x: Float, y: Float) {
        startX = x
        startY = y
        path.moveTo(x, y)
        lastX = x
        lastY = y
    }

    fun updateShape(x: Float, y: Float) {
        val left = minOf(startX, x)
        val right = maxOf(startX, x)
        val top = minOf(startY, y)
        val bottom = maxOf(startY, y)
        path.reset()
        when (shape) {
            ShapeType.RECTANGLE -> path.addRect(left, top, right, bottom, Path.Direction.CW)
            ShapeType.ROUNDED_RECTANGLE -> path.addRoundRect(
                left, top, right, bottom,
                minOf(right - left, bottom - top) * 0.15f,
                minOf(right - left, bottom - top) * 0.15f,
                Path.Direction.CW
            )
            ShapeType.OVAL -> path.addOval(left, top, right, bottom, Path.Direction.CW)
            ShapeType.TRIANGLE -> {
                addPolygon(listOf(((left + right) / 2f) to top, right to bottom, left to bottom))
            }
            ShapeType.DIAMOND -> addPolygon(
                listOf(
                    ((left + right) / 2f) to top,
                    right to ((top + bottom) / 2f),
                    ((left + right) / 2f) to bottom,
                    left to ((top + bottom) / 2f)
                )
            )
            ShapeType.PENTAGON -> addRegularPolygon(left, top, right, bottom, 5)
            ShapeType.HEXAGON -> addRegularPolygon(left, top, right, bottom, 6)
            ShapeType.STAR -> addRegularPolygon(left, top, right, bottom, 10, true)
            ShapeType.ARROW -> {
                val centerY = (top + bottom) / 2f
                val headStart = left + (right - left) * 0.58f
                val headTop = top + (bottom - top) * 0.2f
                val tailTop = top + (bottom - top) * 0.35f
                val tailBottom = bottom - (bottom - top) * 0.35f
                addPolygon(
                    listOf(
                        left to tailTop,
                        headStart to tailTop,
                        headStart to headTop,
                        right to centerY,
                        headStart to (bottom - (bottom - top) * 0.2f),
                        headStart to tailBottom,
                        left to tailBottom
                    )
                )
            }
            ShapeType.LINE -> {
                path.moveTo(startX, startY)
                path.lineTo(x, y)
            }
            ShapeType.FREEHAND -> Unit
        }
        lastX = x
        lastY = y
    }

    private fun addRegularPolygon(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        vertices: Int,
        star: Boolean = false
    ) {
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val radiusX = (right - left) / 2f
        val radiusY = (bottom - top) / 2f
        val points = (0 until vertices).map { index ->
            val angle = -Math.PI / 2.0 + index * 2.0 * Math.PI / vertices
            val radiusScale = if (star && index % 2 == 1) 0.42 else 1.0
            (centerX + (kotlin.math.cos(angle) * radiusX * radiusScale).toFloat()) to
                (centerY + (kotlin.math.sin(angle) * radiusY * radiusScale).toFloat())
        }
        addPolygon(points)
    }

    private fun addPolygon(points: List<Pair<Float, Float>>) {
        points.forEachIndexed { index, (pointX, pointY) ->
            if (index == 0) path.moveTo(pointX, pointY) else path.lineTo(pointX, pointY)
        }
        path.close()
    }

    /** Smooths the line by curving towards the midpoint between consecutive touch points. */
    fun extend(x: Float, y: Float) {
        if (shape != ShapeType.FREEHAND) {
            updateShape(x, y)
            return
        }
        path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
        lastX = x
        lastY = y
        hasSegments = true
    }

    fun finish() {
        if (shape != ShapeType.FREEHAND) return
        if (hasSegments) {
            path.lineTo(lastX, lastY)
        } else {
            // A single tap: a tiny segment + round cap renders as a dot.
            path.lineTo(lastX + 0.1f, lastY)
        }
    }
}

/** Everything that must survive rotation / configuration changes. */
class DrawingState {
    /** Visible drawing operations in paint order. */
    val history = ArrayList<CustomPath>()

    /** Undo/redo stacks (most recently performed/undone last). */
    val undoStack = ArrayList<CanvasAction>()
    val redoStack = ArrayList<CanvasAction>()

    var penColor: Int = Color.BLACK
    var brushSizeDp: Float = SIZE_MEDIUM
    var isEraser: Boolean = false
    var fillShapes: Boolean = false

    /** Size of the drawing surface the stored paths were created for. */
    var surfaceWidth = 0
    var surfaceHeight = 0

    fun transform(matrix: Matrix) {
        val strokes = LinkedHashSet<CustomPath>()
        strokes.addAll(history)
        (undoStack + redoStack).forEach { strokes.addAll(it.affectedStrokes()) }
        strokes.forEach { it.path.transform(matrix) }
    }

    companion object {
        const val SIZE_THIN = 3f
        const val SIZE_MEDIUM = 8f
        const val SIZE_THICK = 18f
    }
}

class DrawingViewModel : ViewModel() {
    val slides = arrayListOf(DrawingState())
    var currentSlide = 0
        private set

    val state: DrawingState
        get() = slides[currentSlide]

    fun addSlide(): DrawingState {
        val slide = DrawingState()
        slides.add(slide)
        currentSlide = slides.lastIndex
        return slide
    }

    fun changeSlide(index: Int): DrawingState {
        currentSlide = index.coerceIn(0, slides.lastIndex)
        return state
    }
}
