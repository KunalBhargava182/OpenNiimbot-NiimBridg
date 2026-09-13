package com.muse.niimbot

import kotlinx.coroutines.flow.Flow

/**
 * A raw byte-stream transport to a NIIMBOT printer. [NiimbotPrinter] frames and unframes
 * every byte through this interface; it never touches Bluetooth (or any other transport)
 * directly.
 *
 * Public since 1.1 — part of the headless API. The two implementations shipped in this
 * module are [SppTransport] (primary, verified) and `BleTransport` (unverified fallback).
 * Implement this yourself only if you need a transport neither of those covers.
 */
interface NiimbotTransport {
    val name: String
    val isConnected: Boolean
    /** Raw inbound bytes, unframed. Feed straight into PacketBuffer. */
    val inbound: Flow<ByteArray>
    suspend fun connect()
    suspend fun write(bytes: ByteArray)
    fun close()
}
