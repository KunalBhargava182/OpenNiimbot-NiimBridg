package com.muse.niimbot

object Opcodes {
    // --- requests -------------------------------------------------------
    const val PRINT_START      = 0x01
    const val SET_RTC          = 0x07
    const val SET_PAGE_SIZE    = 0x13
    const val GET_RFID         = 0x1A
    const val SET_DENSITY      = 0x21
    const val SET_LABEL_TYPE   = 0x23
    const val GET_INFO         = 0x40
    const val ROW_INDEXED      = 0x83
    const val ROW_EMPTY        = 0x84
    const val ROW_BITMAP       = 0x85
    const val PRINT_STATUS     = 0xA3
    const val GET_CAPABILITIES = 0xAF
    const val HEARTBEAT        = 0xDC
    const val PAGE_END         = 0xE3
    const val PRINT_END        = 0xF3

    // --- responses ------------------------------------------------------
    const val R_PRINT_START   = 0x02
    const val R_SET_RTC       = 0x08
    const val R_SET_PAGE_SIZE = 0x14
    const val R_GET_RFID      = 0x1B
    const val R_SET_DENSITY   = 0x31
    const val R_SET_LABEL     = 0x33
    const val R_PRINT_STATUS  = 0xB3
    const val R_CAPABILITIES  = 0xBF
    const val R_HEARTBEAT     = 0xD9
    const val R_HEARTBEAT_ALT = 0xDE
    const val R_PAGE_END      = 0xE4
    const val R_PRINT_END     = 0xF4

    /** Unsolicited line-progress notification: lineIdx(u16 BE), 0x01. Ignore. */
    const val U_LINE_PROGRESS = 0xD3
    /** Printer-side error. Fatal. */
    const val R_ERROR         = 0xDB
    /** Command not supported. Non-fatal. */
    const val R_UNSUPPORTED   = 0x00

    // GetInfo keys. Response opcode = GET_INFO + key.
    object Info {
        const val DENSITY       = 1
        const val PRINT_SPEED   = 2
        const val LABEL_TYPE    = 3
        const val LANGUAGE      = 6
        const val AUTO_SHUTDOWN = 7
        const val DEVICE_TYPE   = 8
        const val SOFT_VERSION  = 9
        const val BATTERY       = 10
        const val DEVICE_SERIAL = 11
        const val HARD_VERSION  = 12
    }

    const val MODEL_ID_D110_M = 2320

    private val NAMES = mapOf(
        PRINT_START to "PrintStart", SET_RTC to "SetRtc", SET_PAGE_SIZE to "SetPageSize",
        GET_RFID to "GetRfid", SET_DENSITY to "SetDensity", SET_LABEL_TYPE to "SetLabelType",
        GET_INFO to "GetInfo", ROW_INDEXED to "RowIndexed", ROW_EMPTY to "RowEmpty",
        ROW_BITMAP to "RowBitmap", PRINT_STATUS to "PrintStatus",
        GET_CAPABILITIES to "GetCapabilities", HEARTBEAT to "Heartbeat",
        PAGE_END to "PageEnd", PRINT_END to "PrintEnd",
        R_PRINT_START to "ackPrintStart", R_SET_RTC to "ackSetRtc",
        R_SET_PAGE_SIZE to "ackSetPageSize", R_GET_RFID to "rfid",
        R_SET_DENSITY to "ackDensity", R_SET_LABEL to "ackLabelType",
        R_PRINT_STATUS to "status", R_CAPABILITIES to "capabilities",
        R_HEARTBEAT to "heartbeat", R_HEARTBEAT_ALT to "heartbeatAlt",
        R_PAGE_END to "ackPageEnd", R_PRINT_END to "ackPrintEnd",
        U_LINE_PROGRESS to "lineProgress", R_ERROR to "ERROR", R_UNSUPPORTED to "UNSUPPORTED",
    )

    fun name(type: Int): String = NAMES[type] ?: "info/0x%02X".format(type)
}
