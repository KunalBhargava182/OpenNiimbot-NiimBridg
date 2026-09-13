package com.muse.niimbridge.ui.designer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Pixel-accurate, zoomable/pannable view of a 1-bit label bitmap with an optional
 * pixel-grid overlay once individual pixels are large enough to see on screen.
 * This is the primary debugging tool for confirming rotation/orientation before
 * committing ink to a label -- draws the bitmap unfiltered, no smoothing.
 */
class BitmapPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        const val MAX_SCALE = 40f
        const val GRID_VISIBLE_AT_SCALE = 8f
    }

    private var bitmap: Bitmap? = null
    private var scale = 1f
    private var minScale = 1f
    private var translateX = 0f
    private var translateY = 0f

    private val bitmapPaint = Paint().apply { isFilterBitmap = false; isDither = false }
    private val gridPaint = Paint().apply { color = Color.argb(70, 128, 128, 128); strokeWidth = 1f }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(minScale, MAX_SCALE)
                val factor = newScale / scale
                translateX = detector.focusX - (detector.focusX - translateX) * factor
                translateY = detector.focusY - (detector.focusY - translateY) * factor
                scale = newScale
                invalidate()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                translateX -= dx
                translateY -= dy
                invalidate()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetZoom()
                return true
            }
        },
    )

    fun setBitmap(bmp: Bitmap) {
        val previous = bitmap
        bitmap = bmp
        if (previous == null || previous.width != bmp.width || previous.height != bmp.height) {
            if (width > 0 && height > 0) resetZoom() else post { resetZoom() }
        } else {
            invalidate()
        }
    }

    private fun resetZoom() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val fit = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        minScale = fit
        scale = fit
        translateX = (width - bmp.width * scale) / 2f
        translateY = (height - bmp.height * scale) / 2f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return

        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        canvas.restore()

        if (scale >= GRID_VISIBLE_AT_SCALE) {
            val left = translateX
            val top = translateY
            val right = translateX + bmp.width * scale
            val bottom = translateY + bmp.height * scale
            for (x in 0..bmp.width) {
                val sx = translateX + x * scale
                canvas.drawLine(sx, top, sx, bottom, gridPaint)
            }
            for (y in 0..bmp.height) {
                val sy = translateY + y * scale
                canvas.drawLine(left, sy, right, sy, gridPaint)
            }
        }
    }
}
