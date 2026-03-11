package com.q8ind.glamnsmile

enum class AnalysisMode(
    val id: String,
    val requiredImages: Int,
    val captureTitleRes: Int,
    val captureSubtitleRes: Int,
    val resultTitleRes: Int,
    val resultSubtitleRes: Int,
    val resultSectionTitleRes: Int,
    val prompt: String,
) {
    FACIAL(
        id = "facial",
        requiredImages = 1,
        captureTitleRes = R.string.image_capture_title,
        captureSubtitleRes = R.string.image_capture_subtitle,
        resultTitleRes = R.string.gemini_result_title,
        resultSubtitleRes = R.string.gemini_result_subtitle,
        resultSectionTitleRes = R.string.analysis_output_title,
        prompt = "Conduct analysis of the attached image and recommend facial aesthetics treatment. " +
            "Describe visible facial features, skin texture, facial balance, and likely aesthetic concerns from the image only. " +
            "Then suggest suitable facial aesthetics treatments in a professional, non-diagnostic tone. " +
            "Use the sections Summary, Observations, Suggested Treatments, and Important Note.",
    ),
    DENTAL(
        id = "dental",
        requiredImages = 3,
        captureTitleRes = R.string.dental_capture_title,
        captureSubtitleRes = R.string.dental_capture_subtitle,
        resultTitleRes = R.string.dental_result_title,
        resultSubtitleRes = R.string.dental_result_subtitle,
        resultSectionTitleRes = R.string.dental_output_title,
        prompt = "Detected my teeth analyse and give me the dental problems and treatments. " +
            "Review all attached images together. Summarize visible dental issues, possible concerns, and likely treatment options. " +
            "Do not claim certainty when visibility is limited.",
    );

    companion object {
        fun fromId(id: String?): AnalysisMode {
            return entries.firstOrNull { it.id == id } ?: FACIAL
        }
    }
}
