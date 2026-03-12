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
    val prompt: String,
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
        prompt = """
            A hyper-realistic, high-resolution portrait infographic (16:9).

            Use the attached reference photo. The person must be the exact same person with identical facial features and all distinguishing characteristics (identity locked).
            Pose and framing: head-and-shoulders, facing directly toward the camera, neutral expression, eyes open, no tilt/rotation.
            Styling: light gray studio background, professional soft studio lighting, and a plain black or white tank top. Do not change the person's face, skin, or features.

            Do NOT retouch or beautify: no smoothing, sharpening, makeup, fillers, face slimming, eye enlargement, skin tone change, age change, or texture removal. Preserve real skin texture exactly as in the original.

            Overlay a subtle semi-transparent facial analysis grid: thin, soft white contour lines that gently glow, following facial contours without hiding skin details. Minimal, elegant, cosmetic-tech advertisement style.

            Detect and label every noticeable and prominent skin concern that actually exists and is visible in the original photo, choosing only from:
            acne; pores and blemishes; melasma; dullness; dark spots and uneven skin tone; texture issues; premature aging; fine lines and wrinkles; total skin brightening; sun damage / tan; under-eye concerns (dark circles or puffiness); skin dullness and lack of radiance; redness and inflammation; dehydration and skin barrier damage; loss of firmness and elasticity; congested skin; blackheads or scarring; unwanted facial hair in women.
            Do not invent issues. If a concern is not clearly visible, omit it.

            For each visible concern, add:
            - A small label with a thin callout line pointing to the relevant facial area
            - A short title + severity score (0-100%)
            - 1 short recommended treatment, e.g. "Dark spots: Q-Switched / Pico Laser" or "Blackheads: HydraFacial"
            Severity colors: 0-33% green (mild), 34-66% orange (moderate), 67-100% red (significant).

            Layout rules:
            - Labels for concerns on the left side of the face must be left-aligned.
            - Labels for concerns on the right side of the face must be right-aligned.
            - Keep labels readable and uncluttered; avoid covering key facial features.

            Typography: clean modern sans-serif, small technical-style text.
            Overall: futuristic AI-guided skincare analysis, minimalistic, premium editorial lighting.
        """.trimIndent(),
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
