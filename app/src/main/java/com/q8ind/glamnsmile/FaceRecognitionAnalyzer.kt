package com.q8ind.glamnsmile

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class FaceRecognitionAnalyzer(
    private val detector: FaceDetector,
    private val callbackExecutor: Executor,
    private val onStateChanged: (FaceCaptureUiState) -> Unit,
) : ImageAnalysis.Analyzer {

    private val isProcessing = AtomicBoolean(false)

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
                onStateChanged(FaceCaptureUiState.analyzerError(message))
            }
            .addOnCompleteListener(callbackExecutor) {
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    private fun List<Face>.toUiState(imageProxy: ImageProxy): FaceCaptureUiState {
        if (isEmpty()) {
            return FaceCaptureUiState(
                statusText = "Searching for a face...",
                guidanceText = "Center one face in the frame to enable Analyse.",
                readinessLabel = "No face",
            )
        }

        if (size > 1) {
            return FaceCaptureUiState(
                statusText = "$size faces detected",
                guidanceText = "Keep only one face in view to enable Analyse.",
                faceCount = size,
                readinessLabel = "One face only",
            )
        }

        val face = first()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val uprightWidth = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
        val uprightHeight = if (rotation % 180 == 0) imageProxy.height else imageProxy.width
        val frameArea = (uprightWidth * uprightHeight).coerceAtLeast(1)
        val faceArea = face.boundingBox.width().coerceAtLeast(1) * face.boundingBox.height().coerceAtLeast(1)
        val faceAreaRatio = faceArea.toFloat() / frameArea.toFloat()
        val centerXOffset = abs((face.boundingBox.exactCenterX() / uprightWidth) - 0.5f)
        val centerYOffset = abs((face.boundingBox.exactCenterY() / uprightHeight) - 0.5f)
        val centered = centerXOffset < 0.18f && centerYOffset < 0.22f
        val frontal = abs(face.headEulerAngleY) < 12f &&
            abs(face.headEulerAngleX) < 10f &&
            abs(face.headEulerAngleZ) < 10f
        val largeEnough = faceAreaRatio >= 0.14f
        val ready = centered && frontal && largeEnough

        val (guidanceText, readinessLabel) = when {
            !largeEnough -> "Move closer until your face fills more of the frame." to "Move closer"
            !centered -> "Center your face in the frame before capturing." to "Center face"
            !frontal -> "Look straight at the camera and keep your head level." to "Look forward"
            else -> "Face recognized. Tap Analyse to capture the image." to "Ready"
        }

        return FaceCaptureUiState(
            statusText = if (ready) "Face ready for Gemini analysis." else "Face detected",
            guidanceText = guidanceText,
            faceCount = 1,
            readinessLabel = readinessLabel,
            canAnalyze = ready,
        )
    }
}
