package com.q8ind.glamnsmile

data class FaceCaptureUiState(
    val statusText: String = "Center a single face to prepare the image capture.",
    val guidanceText: String = "Keep one face in frame and look straight at the camera.",
    val faceCount: Int = 0,
    val readinessLabel: String = "Waiting",
    val canAnalyze: Boolean = false,
    val errorMessage: String? = null,
) {
    companion object {
        fun initial(lensFacingLabel: String) = FaceCaptureUiState(
            statusText = "$lensFacingLabel ready for image capture.",
            guidanceText = "Center one face and keep it still to enable Analyse.",
        )

        fun permissionRequired() = FaceCaptureUiState(
            statusText = "Camera permission is required before image analysis can start.",
            guidanceText = "Grant camera access to capture a face for Gemini analysis.",
            readinessLabel = "Permission needed",
        )

        fun cameraError(message: String) = FaceCaptureUiState(
            statusText = "Unable to start the camera preview.",
            guidanceText = "Try reopening the screen or switching cameras.",
            readinessLabel = "Camera error",
            errorMessage = message,
        )

        fun analyzerError(message: String) = FaceCaptureUiState(
            statusText = "Face detection failed for the latest frame.",
            guidanceText = "Hold the device steady and try again.",
            readinessLabel = "Detection error",
            errorMessage = message,
        )

        fun capturing() = FaceCaptureUiState(
            statusText = "Capturing image...",
            guidanceText = "Hold still while the still image is saved.",
            readinessLabel = "Capturing",
        )
    }
}
