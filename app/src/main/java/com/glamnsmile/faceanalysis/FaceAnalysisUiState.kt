package com.glamnsmile.faceanalysis

data class FaceAnalysisUiState(
    val statusText: String = "Point the camera at a face to begin analysis.",
    val faceCount: Int = 0,
    val trackingId: Int? = null,
    val smileProbability: Float? = null,
    val leftEyeOpenProbability: Float? = null,
    val rightEyeOpenProbability: Float? = null,
    val yaw: Float? = null,
    val pitch: Float? = null,
    val roll: Float? = null,
    val wrinkleScore: Float? = null,
    val wrinkleLabel: String? = null,
    val estimatedAgeBand: String? = null,
    val skinToneLabel: String? = null,
    val skinToneHex: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun initial(lensFacingLabel: String) = FaceAnalysisUiState(
            statusText = "$lensFacingLabel ready. Point the camera at a face.",
        )

        fun permissionRequired() = FaceAnalysisUiState(
            statusText = "Camera permission is required before face analysis can start.",
        )

        fun cameraError(message: String) = FaceAnalysisUiState(
            statusText = "Unable to start the camera preview.",
            errorMessage = message,
        )

        fun analyzerError(message: String) = FaceAnalysisUiState(
            statusText = "Face detection failed for the latest frame.",
            errorMessage = message,
        )
    }
}
