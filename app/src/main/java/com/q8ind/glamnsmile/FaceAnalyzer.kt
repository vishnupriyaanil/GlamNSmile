package com.q8ind.glamnsmile

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class FaceAnalyzer(
    private val detector: FaceDetector,
    private val callbackExecutor: Executor,
    private val onStateChanged: (FaceAnalysisUiState) -> Unit,
) : ImageAnalysis.Analyzer {

    private val isProcessing = AtomicBoolean(false)
    private val appearanceEstimator = FaceAppearanceEstimator()

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(image)
            .addOnSuccessListener(callbackExecutor) { faces ->
                onStateChanged(faces.toUiState(imageProxy))
            }
            .addOnFailureListener(callbackExecutor) { error ->
                val message = error.localizedMessage ?: "Unknown ML Kit error"
                onStateChanged(FaceAnalysisUiState.analyzerError(message))
            }
            .addOnCompleteListener(callbackExecutor) {
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    private fun List<Face>.toUiState(imageProxy: ImageProxy): FaceAnalysisUiState {
        if (isEmpty()) {
            return FaceAnalysisUiState(
                statusText = "Searching for a face...",
                faceCount = 0,
            )
        }

        val dominantFace = maxByOrNull { face ->
            face.boundingBox.width() * face.boundingBox.height()
        } ?: first()
        val appearanceEstimate = appearanceEstimator.estimate(imageProxy, dominantFace)

        val baseStatus = when {
            dominantFace.smilingProbability != null && dominantFace.smilingProbability!! >= 0.7f ->
                "Smile likely"

            size > 1 -> "$size faces detected"
            else -> "Face locked"
        }
        val statusText = if (appearanceEstimate == null && size == 1) {
            "$baseStatus. Move closer for wrinkle and skin tone estimates."
        } else {
            baseStatus
        }

        return FaceAnalysisUiState(
            statusText = statusText,
            faceCount = size,
            trackingId = dominantFace.trackingId,
            smileProbability = dominantFace.smilingProbability,
            leftEyeOpenProbability = dominantFace.leftEyeOpenProbability,
            rightEyeOpenProbability = dominantFace.rightEyeOpenProbability,
            yaw = dominantFace.headEulerAngleY,
            pitch = dominantFace.headEulerAngleX,
            roll = dominantFace.headEulerAngleZ,
            wrinkleScore = appearanceEstimate?.wrinkleScore,
            wrinkleLabel = appearanceEstimate?.wrinkleLabel,
            estimatedAgeBand = appearanceEstimate?.ageBandLabel,
            skinToneLabel = appearanceEstimate?.skinToneLabel,
            skinToneHex = appearanceEstimate?.skinToneHex,
        )
    }
}
