package com.muse.niimbot.sdk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Renders the fixed three-line ICF sticker template: Study ID, EMIR ID, then the
 * study name -- centered, bold, monospace, rotated 90 degrees onto the 96x320
 * bitmap the printer expects. This is a locked-down subset of NiimBridge's
 * LabelRenderer: font, alignment, style, and rotation are not configurable here,
 * by design -- that is the whole point of this SDK versus the diagnostic app.
 */
internal object StickerRenderer {

    private const val CANVAS_WIDTH_PX = 320
    private const val CANVAS_HEIGHT_PX = 96
    private const val ROTATION_DEGREES = 90
    private const val STUDY_NAME = "TreBle Respire Study"

    private const val TEXT_SIZE_PX = 22f
    private const val LINE_SPACING_PX = 28f
    private const val MARGIN_TOP_PX = 24f

    fun render(studyId: String, emirId: String): Bitmap {
        val canvasBitmap = Bitmap.createBitmap(CANVAS_WIDTH_PX, CANVAS_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            isAntiAlias = false
            isDither = false
            color = Color.BLACK
            textSize = TEXT_SIZE_PX
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val lines = listOf("$studyId/", emirId, STUDY_NAME)
        val x = CANVAS_WIDTH_PX / 2f
        var baseline = MARGIN_TOP_PX
        for (line in lines) {
            canvas.drawText(line, x, baseline, paint)
            baseline += LINE_SPACING_PX
        }

        val matrix = Matrix().apply { postRotate(ROTATION_DEGREES.toFloat()) }
        return Bitmap.createBitmap(canvasBitmap, 0, 0, canvasBitmap.width, canvasBitmap.height, matrix, false)
    }
}
