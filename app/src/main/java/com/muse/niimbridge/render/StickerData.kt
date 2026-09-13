package com.muse.niimbridge.render

enum class StickerType {
    ICF, ZERO_BILLING, PCR_LAB, SPUTUM_CULTURE, PUAT, BACKUP
}

data class StickerData(
    val stickerType: StickerType,
    val studyId: String,
    val emirId: String,
    val studyName: String,
)
