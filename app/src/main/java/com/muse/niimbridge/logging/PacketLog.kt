package com.muse.niimbridge.logging

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ring buffer of every frame sent/received. This app exists to debug a
 * reverse-engineered protocol -- the log is the product, so nothing here is
 * sampled or truncated except by the oldest-first [CAPACITY] eviction.
 */
class PacketLog(private val capacity: Int = 4000) {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun append(direction: LogEntry.Direction, text: String) {
        val next = _entries.value + LogEntry(System.currentTimeMillis(), direction, text)
        _entries.value = if (next.size > capacity) next.takeLast(capacity) else next
    }

    /** Classifies a NiimbotPrinter log line ("TX ...", "RX ...", or anything else) into a direction. */
    fun appendPrinterLine(line: String) {
        val direction = when {
            line.startsWith("TX") -> LogEntry.Direction.TX
            line.startsWith("RX") -> LogEntry.Direction.RX
            else -> LogEntry.Direction.INFO
        }
        append(direction, line)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private fun renderText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return _entries.value.joinToString("\n") { e ->
            "${fmt.format(Date(e.timestampMs))} [${e.direction}] ${e.text}"
        }
    }

    /** Writes the current log to a timestamped file under the app cache dir, readable standalone. */
    fun exportToFile(context: Context): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val name = "niimbridge_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".log"
        val file = File(dir, name)
        file.writeText(renderText())
        return file
    }
}
