package com.example.whiteboard

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import androidx.lifecycle.ViewModel

/**
 * One finished (or in-progress) stroke. Pure data + a Path: it holds no reference to any
 * View or Context, so it is safe to keep inside a ViewModel across configuration changes.
 */
class CustomPath(
    val color: Int,
    val strokeWidth: Float,
    val isEraser: Boolean
) {
    val path = Path()

    var lastX = 0f
        private set
    var lastY = 0f
        private set
    private var hasSegments = false

    fun begin(x: Float, y: Float) {
        path.moveTo(x, y)
        lastX = x
        lastY = y
    }

    /** Smooths the line by curving towards the midpoint between consecutive touch points. */
    fun extend(x: Float, y: Float) {
        path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
        lastX = x
        lastY = y
        hasSegments = true
    }

    fun finish() {
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
    /** Undo stack (oldest first). */
    val history = ArrayList<CustomPath>()

    /** Redo stack (most recently undone last). */
    val redoStack = ArrayList<CustomPath>()

    var penColor: Int = Color.BLACK
    var brushSizeDp: Float = SIZE_MEDIUM
    var isEraser: Boolean = false

    /** Size of the drawing surface the stored paths were created for. */
    var surfaceWidth = 0
    var surfaceHeight = 0

    fun transform(matrix: Matrix) {
        history.forEach { it.path.transform(matrix) }
        redoStack.forEach { it.path.transform(matrix) }
    }

    companion object {
        const val SIZE_THIN = 3f
        const val SIZE_MEDIUM = 8f
        const val SIZE_THICK = 18f
    }
}

class DrawingViewModel : ViewModel() {
    val state = DrawingState()
}
