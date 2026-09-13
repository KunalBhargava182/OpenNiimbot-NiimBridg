package com.muse.niimbridge.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Renders a [StickerData] onto the fixed 320x96 print canvas, then rotates it to
 * the orientation the rotation spinner selects. Default 90 is the only rotation
 * confirmed against hardware; 0/180 will fail ImageEncoder's width<=96px assertion
 * on transmit -- that is deliberate, the spinner exists to confirm this empirically.
 *
 * One layout only, matching what the official app currently prints. Sticker type,
 * font sizing, and line count are out of scope -- tune the constants below, do not
 * invent alternative layouts.
 */
object LabelRenderer {

    const val CANVAS_WIDTH_PX = 320
    const val CANVAS_HEIGHT_PX = 96
    const val DEFAULT_ROTATION_DEGREES = 90

    private const val TEXT_SIZE_PX = 22f
    private const val LINE_SPACING_PX = 28f
    private const val MARGIN_LEFT_PX = 6f
    private const val MARGIN_RIGHT_PX = 6f
    private const val MARGIN_TOP_PX = 24f

    /**
     * Colour is deliberately not configurable: the printer is a 1-bit thermal head
     * (PROTOCOL.md section 5) that either burns a dot or doesn't. ImageEncoder
     * thresholds on luminance, so anything but black text would either print
     * identically to black or silently vanish -- there is no physical "colour".
     */
    fun render(data: StickerData, style: LabelStyle = LabelStyle(), rotationDegrees: Int = DEFAULT_ROTATION_DEGREES): Bitmap {
        val canvasBitmap = Bitmap.createBitmap(CANVAS_WIDTH_PX, CANVAS_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.WHITE)

        val typefaceStyle = when {
            style.bold && style.italic -> Typeface.BOLD_ITALIC
            style.bold -> Typeface.BOLD
            style.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val paint = Paint().apply {
            isAntiAlias = false
            isDither = false
            color = Color.BLACK
            textSize = TEXT_SIZE_PX
            typeface = Typeface.create(style.font.base, typefaceStyle)
            isUnderlineText = style.underline
            textAlign = when (style.alignment) {
                TextAlignment.LEFT -> Paint.Align.LEFT
                TextAlignment.CENTER -> Paint.Align.CENTER
                TextAlignment.RIGHT -> Paint.Align.RIGHT
            }
        }

        val x = when (style.alignment) {
            TextAlignment.LEFT -> MARGIN_LEFT_PX
            TextAlignment.CENTER -> CANVAS_WIDTH_PX / 2f
            TextAlignment.RIGHT -> CANVAS_WIDTH_PX - MARGIN_RIGHT_PX
        }

        val lines = listOf("${data.studyId}/", data.emirId, data.studyName)
        var baseline = MARGIN_TOP_PX
        for (line in lines) {
            canvas.drawText(line, x, baseline, paint)
            baseline += LINE_SPACING_PX
        }

        return rotate(canvasBitmap, rotationDegrees)
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return src
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, false)
    }
}
