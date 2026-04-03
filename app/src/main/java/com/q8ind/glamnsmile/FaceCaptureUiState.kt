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
        fun initial(lensFacingLabel: String, mode: AnalysisMode = AnalysisMode.FACIAL) =
            if (mode == AnalysisMode.DENTAL) {
                FaceCaptureUiState(
                    statusText = "$lensFacingLabel ready for dental capture.",
                    guidanceText = "Center the mouth in the frame and keep the teeth clearly visible.",
                )
            } else {
                FaceCaptureUiState(
                    statusText = "$lensFacingLabel ready for image capture.",
                    guidanceText = "Center one face and keep it still to enable Analyse.",
                )
            }

        fun permissionRequired(mode: AnalysisMode = AnalysisMode.FACIAL) =
            if (mode == AnalysisMode.DENTAL) {
                FaceCaptureUiState(
                    statusText = "Camera permission is required before dental capture can start.",
                    guidanceText = "Grant camera access to capture teeth images for analysis.",
                    readinessLabel = "Permission needed",
                )
            } else {
                FaceCaptureUiState(
                    statusText = "Camera permission is required before image analysis can start.",
                    guidanceText = "Grant camera access to capture a face for analysis.",
                    readinessLabel = "Permission needed",
                )
            }

        fun cameraError(message: String, mode: AnalysisMode = AnalysisMode.FACIAL) =
            if (mode == AnalysisMode.DENTAL) {
                FaceCaptureUiState(
                    statusText = "Unable to start the dental capture preview.",
                    guidanceText = "Try reopening the screen or switching cameras.",
                    readinessLabel = "Camera error",
                    errorMessage = message,
                )
            } else {
                FaceCaptureUiState(
                    statusText = "Unable to start the camera preview.",
                    guidanceText = "Try reopening the screen or switching cameras.",
                    readinessLabel = "Camera error",
                    errorMessage = message,
                )
            }

        fun analyzerError(message: String, mode: AnalysisMode = AnalysisMode.FACIAL) =
            if (mode == AnalysisMode.DENTAL) {
                FaceCaptureUiState(
                    statusText = "Dental detection failed for the latest frame.",
                    guidanceText = "Hold the device steady and keep the teeth visible.",
                    readinessLabel = "Detection error",
                    errorMessage = message,
                )
            } else {
                FaceCaptureUiState(
                    statusText = "Face detection failed for the latest frame.",
                    guidanceText = "Hold the device steady and try again.",
                    readinessLabel = "Detection error",
                    errorMessage = message,
                )
            }

        fun capturing(mode: AnalysisMode = AnalysisMode.FACIAL) =
            if (mode == AnalysisMode.DENTAL) {
                FaceCaptureUiState(
                    statusText = "Capturing dental image...",
                    guidanceText = "Hold still while the still image is saved.",
                    readinessLabel = "Capturing",
                )
            } else {
                FaceCaptureUiState(
                    statusText = "Capturing image...",
                    guidanceText = "Hold still while the still image is saved.",
                    readinessLabel = "Capturing",
                )
            }
    }
}
