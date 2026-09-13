package com.muse.niimbot

/**
 * Snapshot of the printer's identity, read via `GetInfo` (0x40) on every connect.
 *
 * Public since 1.1 — part of the headless API (see [NiimbotPrinter.readDeviceInfo]),
 * not just an implementation detail of the packaged NiimbotPrintSdk screen.
 */
data class DeviceInfo(
    val modelId: Int,
    val modelName: String,
    val serial: String,
    val softVersion: String,
    val hardVersion: String,
    val battery: Int,
) {
    /** `true` when [modelId] matches the D110_M this driver was validated against (2320). */
    val isD110M: Boolean get() = modelId == Opcodes.MODEL_ID_D110_M
}

/**
 * Roll/RFID info read via `GetRfid` (0x1A). See `docs/PROTOCOL.md` section 8.
 *
 * Public since 1.1 — part of the headless API (see [NiimbotPrinter.readRfid]).
 */
data class RfidInfo(
    val uuid: String,
    val barcode: String,
    val serial: String,
    val totalLabels: Int,
    val usedLabels: Int,
    val type: Int,
) {
    /** `totalLabels - usedLabels`, floored at 0. Check this before a batch print. */
    val remaining: Int get() = (totalLabels - usedLabels).coerceAtLeast(0)
}

/** 0xB3 payload. See PROTOCOL.md section 6. */
data class PrintStatus(
    val pagesPrinted: Int,
    val progressPage: Int,
    val progressFeed: Int,
    val telemetry: Int,
    val busy: Boolean,
    val raw: ByteArray,
) {
    override fun equals(other: Any?) = other is PrintStatus && other.raw.contentEquals(raw)
    override fun hashCode() = raw.contentHashCode()
}

/**
 * Outcome of a [NiimbotPrinter.printBitmap] call.
 *
 * Public since 1.1 — part of the headless API.
 *
 * [complete] is the only thing that means "the printer confirmed this" — it reflects the
 * printer's own page counter (`0xB3` `pagesPrinted`), never the progress bytes, per
 * `docs/PROTOCOL.md` section 6: a poll timeout or a short count is always a failure, never
 * an optimistic success. Check it explicitly; don't assume a call that didn't throw means
 * every requested copy was printed.
 */
data class PrintResult(val pagesRequested: Int, val pagesConfirmed: Int, val elapsedMs: Long) {
    val complete: Boolean get() = pagesConfirmed >= pagesRequested
}

/**
 * Live connection/print state, exposed via [NiimbotPrinter.state].
 *
 * Public since 1.1 — part of the headless API. Drive custom UI off this the same way the
 * packaged NiimbotPrintSdk screen does.
 */
sealed class PrinterState {
    object Disconnected : PrinterState()
    object Connecting : PrinterState()
    data class Ready(val info: DeviceInfo) : PrinterState()
    data class Printing(val page: Int, val total: Int, val percent: Int) : PrinterState()
    data class Failed(val reason: String) : PrinterState()
}

/**
 * Thrown for every driver-level failure: connect failures, printer-reported errors (`0xDB`),
 * transceive timeouts, and malformed responses. Always carries a human-readable [message];
 * [cause] is populated when the failure wraps a lower-level exception (e.g. a socket error).
 *
 * Public since 1.1 — part of the headless API. Catch this (not a bare `Exception`) when
 * building custom error handling around [NiimbotPrinter].
 */
class NiimbotException(message: String, cause: Throwable? = null) : Exception(message, cause)
