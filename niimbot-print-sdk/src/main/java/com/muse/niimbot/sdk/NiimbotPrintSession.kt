package com.muse.niimbot.sdk

import android.content.Context
import com.muse.niimbot.DeviceInfo
import com.muse.niimbot.NiimbotException
import com.muse.niimbot.NiimbotPrinter
import com.muse.niimbot.NiimbotPrinterConnector
import com.muse.niimbot.PrintResult
import com.muse.niimbot.PrinterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Headless print session for building a fully custom screen: the same
 * [NiimbotPrinterConnector] (device selection with reachability fallback), the same
 * [NiimbotPrinter] (from niimbot-driver), and the same [NiimbotLabelRenderer] the
 * packaged `NiimbotPrintSdk.createIntent(...)` screen uses -- bundled behind one small
 * coroutine API, with no bundled UI at all. You build the screen; this hands you the
 * validated primitives.
 *
 * Public since 1.1. This is a parallel path alongside the packaged screen, not a
 * replacement for it -- if the locked-down packaged flow (fixed template, 1-or-6 copies)
 * already fits, `NiimbotPrintSdk.createIntent(...)` is simpler and still fully supported.
 * Reach for this class only when you need your own layout, branding, or multi-step flow
 * around the print step.
 *
 * Not tied to any Android lifecycle owner by design, so it works from a plain Fragment,
 * a ViewModel, or anything else -- call [disconnect] yourself from whatever "this screen
 * is going away for good" callback your host uses (a ViewModel's `onCleared()`, a
 * Fragment's `onDestroyView()`), not from `onPause()`: a rotation shouldn't drop a live
 * print.
 *
 * A session cannot be reused after [disconnect] -- construct a new [NiimbotPrintSession]
 * if you need to reconnect later, the same way you wouldn't call methods on an already
 * cleared ViewModel.
 */
class NiimbotPrintSession(context: Context) {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connector = NiimbotPrinterConnector(context.applicationContext)

    private var printer: NiimbotPrinter? = null
    private var stateJob: Job? = null

    private val _state = MutableStateFlow<PrinterState>(PrinterState.Disconnected)

    /**
     * Live connection/print state -- [PrinterState.Disconnected] / [PrinterState.Connecting]
     * / [PrinterState.Ready] / [PrinterState.Printing] / [PrinterState.Failed]. Drive your
     * custom screen's UI off this the same way the packaged screen does internally.
     */
    val state: StateFlow<PrinterState> = _state.asStateFlow()

    /**
     * Connects using [NiimbotPrinterConnector]'s last-used-then-fallback logic: tries the
     * previously-remembered printer first, then every other paired NIIMBOT-looking device,
     * skipping any that don't respond instead of failing on the first unreachable one.
     *
     * Throws [NiimbotException] when no paired candidate could be reached, Bluetooth is
     * off/unavailable, or nothing NIIMBOT-looking is paired at all -- [state] is set to
     * [PrinterState.Failed] before the throw either way.
     */
    suspend fun connect(): DeviceInfo {
        printer?.disconnect()
        val transport = connector.connect(sessionScope)
        val p = NiimbotPrinter(transport, sessionScope)
        printer = p
        stateJob?.cancel()
        stateJob = sessionScope.launch { p.state.collect { _state.value = it } }
        return p.connect()
    }

    /**
     * Reads the roll's remaining label count via RFID. Call this before [printLabel] for
     * a batch so you can block/warn on a roll that can't finish it, rather than
     * discovering that mid-print. Throws [NiimbotException] if not connected.
     */
    suspend fun labelsRemaining(): Int {
        val p = printer ?: throw NiimbotException("Not connected -- call connect() first")
        val rfid = p.readRfid() ?: throw NiimbotException("Printer reports no readable RFID roll")
        return rfid.remaining
    }

    /**
     * Renders the fixed Study ID / EMIR ID / study-name template via [NiimbotLabelRenderer]
     * and prints [copies] identical copies as **one page-counter-confirmed transaction**
     * (see [NiimbotPrinter.printBitmap] for the full tradeoff against calling this
     * repeatedly with `copies = 1`). [PrintResult.complete] is `true` only when the
     * printer's own page counter confirmed every copy -- check it, don't assume success
     * just because this call didn't throw.
     *
     * Throws [NiimbotException] if not connected.
     */
    suspend fun printLabel(studyId: String, emirId: String, copies: Int = 1): PrintResult {
        val p = printer ?: throw NiimbotException("Not connected -- call connect() first")
        val bitmap = NiimbotLabelRenderer.render(studyId, emirId)
        return p.printBitmap(
            bitmap,
            density = FixedPrintSettings.DENSITY,
            labelType = FixedPrintSettings.LABEL_TYPE,
            copies = copies,
        )
    }

    /**
     * Releases the connection and ends this session for good -- see the class-level doc
     * for why this isn't reusable afterward. Safe to call even if [connect] was never
     * called or already failed.
     */
    fun disconnect() {
        stateJob?.cancel()
        printer?.disconnect()
        printer = null
        _state.value = PrinterState.Disconnected
        sessionScope.cancel()
    }
}
