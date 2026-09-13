package com.muse.niimbridge.logging

data class LogEntry(
    val timestampMs: Long,
    val direction: Direction,
    val text: String,
) {
    enum class Direction { TX, RX, INFO }
}
