package com.q8ind.glamnsmile

data class AnalysisLead(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val normalizedPhone: String,
    val email: String,
    val faceCount: Int,
    val dentalCount: Int,
    val lastFaceAt: Long,
    val lastDentalAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)
