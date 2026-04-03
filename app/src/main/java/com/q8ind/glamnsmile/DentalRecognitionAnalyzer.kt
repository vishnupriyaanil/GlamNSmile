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
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class DentalRecognitionAnalyzer(
    private val detector: FaceDetector,
    private val callbackExecutor: Executor,
    private val previewOutputTransformProvider: () -> OutputTransform?,
    private val onRoiRectUpdated: (RectF?) -> Unit,
    private val onAutoLightingUpdated: (AutoLightingMetrics?) -> Unit,
    private val onStateChanged: (FaceCaptureUiState) -> Unit,
) : ImageAnalysis.Analyzer {

    private val isProcessing = AtomicBoolean(false)
    private val imageTransformFactory = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = true
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        var processingScheduled = false
        try {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                onStateChanged(FaceCaptureUiState.analyzerError("No camera frame available.", AnalysisMode.DENTAL))
                onRoiRectUpdated(null)
                return
            }

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(image)
                .addOnSuccessListener(callbackExecutor) { faces ->
                    val dominantFace = faces.dominantFace()
                    val mouthRect = dominantFace?.let { computeMouthRect(it) }
                    onStateChanged(faces.toDentalUiState(imageProxy, mouthRect))
                    onRoiRectUpdated(mapMouthRectToPreview(imageProxy, mouthRect))
                }
                .addOnFailureListener(callbackExecutor) { error ->
                    val message = error.localizedMessage ?: "Unknown ML Kit error"
                    onStateChanged(FaceCaptureUiState.analyzerError(message, AnalysisMode.DENTAL))
                    onRoiRectUpdated(null)
                }
                .addOnCompleteListener(callbackExecutor) {
                    isProcessing.set(false)
                    imageProxy.close()
                }
            processingScheduled = true
            return
        } catch (error: Exception) {
            val message = error.localizedMessage ?: "Unknown dental detection error"
            onStateChanged(FaceCaptureUiState.analyzerError(message, AnalysisMode.DENTAL))
            onRoiRectUpdated(null)
        } finally {
            if (!processingScheduled) {
                isProcessing.set(false)
                imageProxy.close()
            }
        }
    }

    private fun List<Face>.dominantFace(): Face? {
        return maxByOrNull { face -> face.boundingBox.width() * face.boundingBox.height() }
    }

    private fun computeMouthRect(face: Face): RectF {
        val faceRect = RectF(face.boundingBox)
        val mouthWidth = faceRect.width() * 0.78f
        val mouthHeight = faceRect.height() * 0.34f
        val centerX = faceRect.centerX()
        val centerY = faceRect.top + faceRect.height() * 0.72f
        val left = centerX - mouthWidth / 2f
        val top = centerY - mouthHeight / 2f
        val mouthRect = RectF(left, top, left + mouthWidth, top + mouthHeight)
        mouthRect.inset(-mouthWidth * 0.08f, -mouthHeight * 0.25f)

        val minTop = faceRect.top + faceRect.height() * 0.38f
        if (mouthRect.top < minTop) {
            mouthRect.top = minTop
        }
        mouthRect.left = mouthRect.left.coerceAtLeast(faceRect.left)
        mouthRect.right = mouthRect.right.coerceAtMost(faceRect.right)
        mouthRect.top = mouthRect.top.coerceAtLeast(faceRect.top)
        mouthRect.bottom = mouthRect.bottom.coerceAtMost(faceRect.bottom)
        return mouthRect
    }

    private fun mapMouthRectToPreview(imageProxy: ImageProxy, mouthRectUpright: RectF?): RectF? {
        val rect = mouthRectUpright?.takeIf { !it.isEmpty } ?: return null
        val previewTransform = previewOutputTransformProvider() ?: return null
        val sourceTransform = imageTransformFactory.getOutputTransform(imageProxy)
        val coordinateTransform = CoordinateTransform(sourceTransform, previewTransform)
        val mapped = RectF(rect)
        coordinateTransform.mapRect(mapped)
        if (mapped.isEmpty) return null

        val dx = mapped.width() * 0.12f
        val dy = mapped.height() * 0.22f
        mapped.inset(-dx, -dy)

        mapped.left = mapped.left.coerceAtLeast(0f)
        mapped.top = mapped.top.coerceAtLeast(0f)
        mapped.right = mapped.right.coerceAtLeast(mapped.left + 1f)
        mapped.bottom = mapped.bottom.coerceAtLeast(mapped.top + 1f)

        val maxDim = 10000f
        mapped.left = mapped.left.coerceAtMost(maxDim)
        mapped.top = mapped.top.coerceAtMost(maxDim)
        mapped.right = mapped.right.coerceAtMost(maxDim)
        mapped.bottom = mapped.bottom.coerceAtMost(maxDim)

        return mapped
    }

    private fun List<Face>.toDentalUiState(imageProxy: ImageProxy, mouthRectUpright: RectF?): FaceCaptureUiState {
        if (isEmpty() || mouthRectUpright == null || mouthRectUpright.isEmpty) {
            onAutoLightingUpdated(null)
            return FaceCaptureUiState(
                statusText = "Searching for teeth...",
                guidanceText = "Keep your face in frame and show your teeth.",
                readinessLabel = "No face",
            )
        }

        if (size > 1) {
            onAutoLightingUpdated(null)
            return FaceCaptureUiState(
                statusText = "$size faces detected",
                guidanceText = "Keep only one face in view to enable capture.",
                faceCount = size,
                readinessLabel = "One face only",
            )
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val uprightWidth = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
        val uprightHeight = if (rotation % 180 == 0) imageProxy.height else imageProxy.width
        val frameArea = (uprightWidth * uprightHeight).coerceAtLeast(1)
        val mouthArea = max(mouthRectUpright.width(), 1f) * max(mouthRectUpright.height(), 1f)
        val mouthAreaRatio = mouthArea / frameArea.toFloat()
        val targetCenterY = 0.58f
        val centerXOffset = abs((mouthRectUpright.centerX() / uprightWidth.toFloat()) - 0.5f)
        val centerYOffset = abs((mouthRectUpright.centerY() / uprightHeight.toFloat()) - targetCenterY)
        val centered = centerXOffset < 0.20f && centerYOffset < 0.22f
        val largeEnough = mouthAreaRatio >= 0.035f

        val sample = imageProxy.sampleLuma()
        val metrics = sample.measureTeethSignal(mouthRectUpright, uprightWidth, uprightHeight)
        val teethVisible = metrics.veryBrightRatio >= TEETH_VISIBLE_MIN_RATIO
        onAutoLightingUpdated(
            AutoLightingMetrics(
                meanLuma = metrics.meanLuma,
                signal = metrics.signal,
            ),
        )
        val ready = centered && largeEnough &&
            metrics.signal >= READY_SIGNAL_THRESHOLD &&
            metrics.visibilityScore >= 0.30f &&
            metrics.edgeScore >= 0.30f &&
            metrics.contrastScore >= 0.24f &&
            teethVisible

        val (statusText, guidanceText, readinessLabel) = when {
            !largeEnough ->
                Triple(
                    "Move closer for dental capture.",
                    "Move closer so the teeth occupy more of the frame.",
                    "Move closer",
                )

            !centered ->
                Triple(
                    "Center teeth in the frame.",
                    "Center the mouth in the frame before capturing.",
                    "Center teeth",
                )

            !teethVisible ->
                Triple(
                    "Searching for teeth...",
                    "Open your mouth slightly until the teeth are clearly visible.",
                    "Show teeth",
                )

            metrics.centerScore < 0.35f ->
                Triple(
                    "Teeth detected off-center.",
                    "Center the mouth in the frame before capturing.",
                    "Center teeth",
                )

            metrics.edgeScore < 0.30f || metrics.contrastScore < 0.25f ->
                Triple(
                    "Teeth visibility is still weak.",
                    "Improve the lighting and hold still so the tooth edges stay sharp.",
                    "Improve view",
                )

            ready ->
                Triple(
                    "Dental view ready for capture.",
                    "Teeth detected. Tap the capture button when this view looks clear.",
                    "Ready",
                )

            else ->
                Triple(
                    "Dental area detected.",
                    "Keep the teeth centered and clearly visible to enable capture.",
                    "Adjust",
                )
        }

        return FaceCaptureUiState(
            statusText = statusText,
            guidanceText = guidanceText,
            faceCount = metrics.signal,
            readinessLabel = readinessLabel,
            canAnalyze = ready,
        )
    }

    private fun ImageProxy.sampleLuma(sampleSize: Int = SAMPLE_SIZE): SampledLuma {
        val plane = planes.firstOrNull() ?: error("No image planes available.")
        val buffer = plane.buffer.toByteArray()
        val width = width
        val height = height
        val rotation = imageInfo.rotationDegrees
        val uprightWidth = if (rotation % 180 == 0) width else height
        val uprightHeight = if (rotation % 180 == 0) height else width
        val values = IntArray(sampleSize * sampleSize)

        for (y in 0 until sampleSize) {
            val uprightY = ((y + 0.5f) / sampleSize * uprightHeight)
                .toInt()
                .coerceIn(0, uprightHeight - 1)
            for (x in 0 until sampleSize) {
                val uprightX = ((x + 0.5f) / sampleSize * uprightWidth)
                    .toInt()
                    .coerceIn(0, uprightWidth - 1)
                val (sourceX, sourceY) = mapUprightToSource(
                    uprightX = uprightX,
                    uprightY = uprightY,
                    sourceWidth = width,
                    sourceHeight = height,
                    rotation = rotation,
                )
                val safeX = sourceX.coerceIn(0, width - 1)
                val safeY = sourceY.coerceIn(0, height - 1)
                val index = safeY * plane.rowStride + safeX * plane.pixelStride
                values[y * sampleSize + x] = buffer[index].toInt() and 0xFF
            }
        }

        return SampledLuma(sampleSize, values)
    }

    private fun mapUprightToSource(
        uprightX: Int,
        uprightY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotation: Int,
    ): Pair<Int, Int> {
        return when (rotation) {
            90 -> uprightY to (sourceHeight - 1 - uprightX)
            180 -> (sourceWidth - 1 - uprightX) to (sourceHeight - 1 - uprightY)
            270 -> (sourceWidth - 1 - uprightY) to uprightX
            else -> uprightX to uprightY
        }
    }

    private fun SampledLuma.measureTeethSignal(uprightRoi: RectF, uprightWidth: Int, uprightHeight: Int): DentalMetrics {
        if (uprightWidth <= 0 || uprightHeight <= 0) {
            return DentalMetrics()
        }

        val safeLeft = uprightRoi.left.coerceIn(0f, uprightWidth.toFloat())
        val safeTop = uprightRoi.top.coerceIn(0f, uprightHeight.toFloat())
        val safeRight = uprightRoi.right.coerceIn(0f, uprightWidth.toFloat())
        val safeBottom = uprightRoi.bottom.coerceIn(0f, uprightHeight.toFloat())

        val left = ((safeLeft / uprightWidth.toFloat()) * size).toInt().coerceIn(0, size - 2)
        val right = ((safeRight / uprightWidth.toFloat()) * size).toInt().coerceIn(left + 2, size)
        val top = ((safeTop / uprightHeight.toFloat()) * size).toInt().coerceIn(0, size - 2)
        val bottom = ((safeBottom / uprightHeight.toFloat()) * size).toInt().coerceIn(top + 2, size)
        val pixelCount = (right - left) * (bottom - top)
        if (pixelCount <= 0) {
            return DentalMetrics()
        }

        var sum = 0.0
        for (y in top until bottom) {
            for (x in left until right) {
                sum += valueAt(x, y)
            }
        }
        val mean = sum / pixelCount
        val brightThreshold = maxOf(155, (mean + 22.0).roundToInt())
        val veryBrightThreshold = maxOf(190, (mean + 40.0).roundToInt())

        var varianceSum = 0.0
        var brightCount = 0
        var veryBrightCount = 0
        var edgeCount = 0
        var weightedX = 0.0
        var weightedY = 0.0
        var weightSum = 0.0

        for (y in top until bottom) {
            for (x in left until right) {
                val value = valueAt(x, y)
                val delta = value - mean
                varianceSum += delta * delta
                if (value >= brightThreshold) {
                    brightCount += 1
                    val weight = (value - brightThreshold + 1).toDouble()
                    weightedX += ((x - left).toDouble() / (right - left).toDouble()) * weight
                    weightedY += ((y - top).toDouble() / (bottom - top).toDouble()) * weight
                    weightSum += weight
                }
                if (value >= veryBrightThreshold) {
                    veryBrightCount += 1
                }
                if (x < right - 1 && abs(value - valueAt(x + 1, y)) >= EDGE_THRESHOLD) {
                    edgeCount += 1
                }
                if (y < bottom - 1 && abs(value - valueAt(x, y + 1)) >= EDGE_THRESHOLD) {
                    edgeCount += 1
                }
            }
        }

        val stdDev = sqrt(varianceSum / pixelCount) / 255.0
        val brightRatio = brightCount.toFloat() / pixelCount.toFloat()
        val veryBrightRatio = veryBrightCount.toFloat() / pixelCount.toFloat()
        val maxEdgeCount = ((right - left - 1) * (bottom - top)) + ((bottom - top - 1) * (right - left))
        val edgeRatio = if (maxEdgeCount > 0) edgeCount.toFloat() / maxEdgeCount.toFloat() else 0f
        val centroidX = if (weightSum > 0.0) (weightedX / weightSum).toFloat() else 0.5f
        val centroidY = if (weightSum > 0.0) (weightedY / weightSum).toFloat() else 0.5f
        val centerDistance = hypot(centroidX - 0.5f, centroidY - 0.55f)
        val centerScore = (1f - (centerDistance / 0.48f)).coerceIn(0f, 1f)
        val visibilityScore = normalize(brightRatio, 0.015f, 0.11f)
        val highlightScore = normalize(veryBrightRatio, 0.005f, 0.05f)
        val contrastScore = normalize(stdDev.toFloat(), 0.10f, 0.22f)
        val edgeScore = normalize(edgeRatio, 0.06f, 0.22f)
        val signal = (
            (visibilityScore * 0.28f) +
                (highlightScore * 0.12f) +
                (contrastScore * 0.20f) +
                (edgeScore * 0.25f) +
                (centerScore * 0.15f)
            ) * 100f

        return DentalMetrics(
            signal = signal.roundToInt().coerceIn(0, 100),
            meanLuma = mean.toFloat(),
            brightRatio = brightRatio,
            veryBrightRatio = veryBrightRatio,
            visibilityScore = visibilityScore,
            contrastScore = contrastScore,
            edgeScore = edgeScore,
            centerScore = centerScore,
        )
    }

    private fun normalize(value: Float, min: Float, max: Float): Float {
        if (max <= min) {
            return 0f
        }
        return ((value - min) / (max - min)).coerceIn(0f, 1f)
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val duplicate = duplicate()
        duplicate.rewind()
        return ByteArray(duplicate.remaining()).also(duplicate::get)
    }

    private data class SampledLuma(
        val size: Int,
        val values: IntArray,
    ) {
        fun valueAt(x: Int, y: Int): Int = values[y * size + x]
    }

    private data class DentalMetrics(
        val signal: Int = 0,
        val meanLuma: Float = 0f,
        val brightRatio: Float = 0f,
        val veryBrightRatio: Float = 0f,
        val visibilityScore: Float = 0f,
        val contrastScore: Float = 0f,
        val edgeScore: Float = 0f,
        val centerScore: Float = 0f,
    )

    companion object {
        private const val SAMPLE_SIZE = 64
        private const val EDGE_THRESHOLD = 24
        private const val READY_SIGNAL_THRESHOLD = 58
        private const val TEETH_VISIBLE_MIN_RATIO = 0.004f
    }

    data class AutoLightingMetrics(
        val meanLuma: Float,
        val signal: Int,
    )
}
