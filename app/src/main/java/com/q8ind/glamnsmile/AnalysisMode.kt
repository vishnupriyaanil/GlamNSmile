package com.q8ind.glamnsmile

enum class AnalysisMode(
    val id: String,
    val requiredImages: Int,
    val captureTitleRes: Int,
    val captureSubtitleRes: Int,
    val resultTitleRes: Int,
    val resultSubtitleRes: Int,
    val resultSectionTitleRes: Int,
    val estimatedMinSeconds: Int,
    val estimatedMaxSeconds: Int,
    val displayLabel: String,
) {
    FACIAL(
        id = "facial",
        requiredImages = 1,
        captureTitleRes = R.string.image_capture_title,
        captureSubtitleRes = R.string.image_capture_subtitle,
        resultTitleRes = R.string.facial_result_title,
        resultSubtitleRes = R.string.facial_result_subtitle,
        resultSectionTitleRes = R.string.facial_output_title,
        estimatedMinSeconds = 20,
        estimatedMaxSeconds = 45,
        displayLabel = "Face",
    ),
    DENTAL(
        id = "dental",
        requiredImages = 3,
        captureTitleRes = R.string.dental_capture_title,
        captureSubtitleRes = R.string.dental_capture_subtitle,
        resultTitleRes = R.string.dental_result_title,
        resultSubtitleRes = R.string.dental_result_subtitle,
        resultSectionTitleRes = R.string.dental_output_title,
        estimatedMinSeconds = 12,
        estimatedMaxSeconds = 30,
        displayLabel = "Dental",
    );

    companion object {
        fun fromId(id: String?): AnalysisMode {
            return entries.firstOrNull { it.id == id } ?: FACIAL
        }
    }
}
