package com.muse.niimbridge.render

import android.graphics.Typeface

enum class LabelFont(val base: Typeface, val displayName: String) {
    MONOSPACE(Typeface.MONOSPACE, "Monospace"),
    SANS_SERIF(Typeface.SANS_SERIF, "Sans Serif"),
    SERIF(Typeface.SERIF, "Serif"),
    CONDENSED(Typeface.create("sans-serif-condensed", Typeface.NORMAL), "Condensed"),
}

enum class TextAlignment { LEFT, CENTER, RIGHT }

data class LabelStyle(
    val font: LabelFont = LabelFont.MONOSPACE,
    val alignment: TextAlignment = TextAlignment.LEFT,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)
