package com.muse.niimbot.sdk

import android.content.Context
import android.content.Intent

/**
 * Public entry point. This is the entire integration surface -- everything else
 * in this module is internal.
 *
 * Usage:
 * ```
 * val intent = NiimbotPrintSdk.createIntent(context, studyId, emirId)
 * startActivityForResult(intent, REQUEST_PRINT_STICKER) // or registerForActivityResult
 *
 * // onActivityResult(requestCode, resultCode, data):
 * if (requestCode == REQUEST_PRINT_STICKER) {
 *     val message = data?.getStringExtra(NiimbotPrintSdk.EXTRA_RESULT_MESSAGE)
 *     when (resultCode) {
 *         Activity.RESULT_OK -> // printed successfully
 *         Activity.RESULT_CANCELED -> // user backed out, or the print failed -- see [message]
 *     }
 * }
 * ```
 *
 * Study ID and EMIR ID are expected to come from your backend and are shown
 * read-only in the sticker preview -- there is no text entry in this screen.
 * Sticker type, font, alignment, style, rotation, and density are fixed and not
 * configurable; the only choice presented to the user is 1 or 6 copies.
 */
object NiimbotPrintSdk {

    const val EXTRA_STUDY_ID = "com.muse.niimbot.sdk.EXTRA_STUDY_ID"
    const val EXTRA_EMIR_ID = "com.muse.niimbot.sdk.EXTRA_EMIR_ID"

    /** Present on the result Intent for both outcomes: a human-readable summary or error. */
    const val EXTRA_RESULT_MESSAGE = "com.muse.niimbot.sdk.EXTRA_RESULT_MESSAGE"

    /**
     * Builds the Intent to launch the print-sticker screen.
     *
     * @param studyId the Study ID from your backend, shown read-only.
     * @param emirId the EMIR ID from your backend, shown read-only.
     */
    fun createIntent(context: Context, studyId: String, emirId: String): Intent =
        Intent(context, NiimbotPrintActivity::class.java).apply {
            putExtra(EXTRA_STUDY_ID, studyId)
            putExtra(EXTRA_EMIR_ID, emirId)
        }
}
