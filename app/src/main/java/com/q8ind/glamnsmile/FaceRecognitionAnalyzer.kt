package com.q8ind.glamnsmile

import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max


class FaceRecognitionAnalyzer(
    private val detector: FaceDetector,
    private val callbackExecutor: Executor,
    private val previewOutputTransformProvider: () -> OutputTransform?,
    private val onRoiRectUpdated: (RectF?) -> Unit,
    private val onStateChanged: (FaceCaptureUiState) -> Unit,
) : ImageAnalysis.Analyzer {

    private val isProcessing = AtomicBoolean(false)
    private val imageTransformFactory = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = true
    }

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
                onRoiRectUpdated(mapDominantFaceRectToPreview(imageProxy, faces))
            }
            .addOnFailureListener(callbackExecutor) { error ->
                val message = error.localizedMessage ?: "Unknown ML Kit error"
                onStateChanged(FaceCaptureUiState.analyzerError(message))
                onRoiRectUpdated(null)
            }
            .addOnCompleteListener(callbackExecutor) {
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    private fun mapDominantFaceRectToPreview(imageProxy: ImageProxy, faces: List<Face>): RectF? {
        if (faces.isEmpty()) return null
        val previewTransform = previewOutputTransformProvider() ?: return null
        val dominantFace = faces.maxByOrNull { face ->
            face.boundingBox.width() * face.boundingBox.height()
        } ?: return null

        val sourceTransform = imageTransformFactory.getOutputTransform(imageProxy)
        val coordinateTransform = CoordinateTransform(sourceTransform, previewTransform)

        val faceRect = RectF(dominantFace.boundingBox)
        coordinateTransform.mapRect(faceRect)

        if (faceRect.isEmpty) return null

        // Expand slightly to include forehead/chin and stabilize framing.
        val dx = faceRect.width() * 0.18f
        val dy = faceRect.height() * 0.30f
        faceRect.inset(-dx, -dy)

        // Normalize to a portrait-ish oval aspect ratio (width / height).
        val targetAspect = 0.72f
        val currentAspect = faceRect.width() / max(faceRect.height(), 1f)
        if (currentAspect > targetAspect) {
            val desiredHeight = faceRect.width() / targetAspect
            val extra = desiredHeight - faceRect.height()
            faceRect.top -= extra / 2f
            faceRect.bottom += extra / 2f
        } else {
            val desiredWidth = faceRect.height() * targetAspect
            val extra = desiredWidth - faceRect.width()
            faceRect.left -= extra / 2f
            faceRect.right += extra / 2f
        }

        // Clamp to the preview output bounds (same coordinate space as RoiOverlayView).
        faceRect.left = faceRect.left.coerceAtLeast(0f)
        faceRect.top = faceRect.top.coerceAtLeast(0f)
        faceRect.right = faceRect.right.coerceAtLeast(faceRect.left + 1f)
        faceRect.bottom = faceRect.bottom.coerceAtLeast(faceRect.top + 1f)

        // The preview transform already maps into the view coordinate space. Keep rect sane.
        val maxDim = 10000f
        faceRect.left = faceRect.left.coerceAtMost(maxDim)
        faceRect.top = faceRect.top.coerceAtMost(maxDim)
        faceRect.right = faceRect.right.coerceAtMost(maxDim)
        faceRect.bottom = faceRect.bottom.coerceAtMost(maxDim)

        return faceRect
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
            statusText = if (ready) "Face ready for analysis." else "Face detected",
            guidanceText = guidanceText,
            faceCount = 1,
            readinessLabel = readinessLabel,
            canAnalyze = ready,
        )
    }
}
