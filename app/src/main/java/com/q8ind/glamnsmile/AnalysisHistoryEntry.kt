package com.q8ind.glamnsmile

data class AnalysisHistoryEntry(
    val id: String,
    val analysisModeId: String,
    val analysisModeLabel: String,
    val providerId: String,
    val providerLabel: String,
    val imageCount: Int,
    val createdAt: Long,
)
