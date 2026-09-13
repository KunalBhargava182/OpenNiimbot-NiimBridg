package com.muse.niimbot.sdk

import android.graphics.Bitmap

/**
 * Renders the exact same 96x320 label bitmap the packaged NiimbotPrintSdk screen prints --
 * same font (Monospace Bold), same size (22px), same line spacing (28px), same rotation
 * (90 degrees), same centering, same white-background/black-text, no anti-aliasing (the
 * printer is 1-bit; anti-aliased edges just become mud). This is a thin public wrapper
 * around the same internal renderer the packaged screen itself calls -- there is exactly
 * one implementation, so a custom screen's output can never drift from the packaged
 * screen's, even as either evolves.
 *
 * Public since 1.1 -- part of the headless API. Use this from a custom screen instead of
 * re-deriving these constants yourself; a previous integrator's first attempt at a custom
 * renderer used a slightly different text size and vertical-centering formula, and the
 * mismatch from the packaged screen's output was immediately visible on the physical label.
 *
 * Font, alignment, style, and rotation are intentionally not parameters here -- this
 * renders the one hardware-validated template, nothing else. If you need a different
 * layout, font, or alignment, you're building your own renderer, not using this one; see
 * NiimBridge's (the standalone diagnostic app's) `LabelRenderer` for a fully configurable
 * example, or match this class's constants by hand.
 */
object NiimbotLabelRenderer {

    /** Study ID / EMIR ID / study-name label, matching the packaged screen exactly. */
    fun render(studyId: String, emirId: String): Bitmap = StickerRenderer.render(studyId, emirId)
}
