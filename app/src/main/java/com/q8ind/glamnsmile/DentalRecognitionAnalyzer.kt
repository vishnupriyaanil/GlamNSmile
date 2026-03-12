package com.q8ind.glamnsmile

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

class DentalRecognitionAnalyzer(
    private val onStateChanged: (FaceCaptureUiState) -> Unit,
) : ImageAnalysis.Analyzer {

    private val isProcessing = AtomicBoolean(false)

    override fun analyze(imageProxy: ImageProxy) {
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        try {
            onStateChanged(imageProxy.toDentalUiState())
        } catch (error: Exception) {
            val message = error.localizedMessage ?: "Unknown dental detection error"
            onStateChanged(FaceCaptureUiState.analyzerError(message, AnalysisMode.DENTAL))
        } finally {
            isProcessing.set(false)
            imageProxy.close()
        }
    }

    private fun ImageProxy.toDentalUiState(): FaceCaptureUiState {
        val sample = sampleLuma()
        val metrics = sample.measureTeethSignal()
        val ready = metrics.signal >= READY_SIGNAL_THRESHOLD &&
            metrics.visibilityScore >= 0.35f &&
            metrics.edgeScore >= 0.30f &&
            metrics.centerScore >= 0.45f &&
            metrics.sizeOk

        val (statusText, guidanceText, readinessLabel) = when {
            metrics.brightRatio < 0.012f ->
                Triple(
                    "Searching for teeth...",
                    "Open your mouth slightly until the teeth are clearly visible.",
                    "Show teeth",
                )

            !metrics.sizeOk && metrics.brightRatio < 0.02f ->
                Triple(
                    "Dental area is too small.",
                    "Move closer so the teeth occupy more of the frame.",
                    "Move closer",
                )

            !metrics.sizeOk ->
                Triple(
                    "Dental area is too large.",
                    "Move a little farther back so the full teeth area stays in frame.",
                    "Reframe",
                )

            metrics.centerScore < 0.45f ->
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

    private fun SampledLuma.measureTeethSignal(): DentalMetrics {
        val left = (size * 0.22f).toInt()
        val right = (size * 0.78f).toInt().coerceAtLeast(left + 2)
        val top = (size * 0.36f).toInt()
        val bottom = (size * 0.82f).toInt().coerceAtLeast(top + 2)
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
        val brightThreshold = maxOf(150, (mean + 22.0).roundToInt())
        val veryBrightThreshold = maxOf(185, (mean + 40.0).roundToInt())

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
        val sizeOk = brightRatio in 0.015f..0.26f
        val signal = (
            (visibilityScore * 0.28f) +
                (highlightScore * 0.12f) +
                (contrastScore * 0.20f) +
                (edgeScore * 0.25f) +
                (centerScore * 0.15f)
            ) * 100f

        return DentalMetrics(
            signal = signal.roundToInt().coerceIn(0, 100),
            brightRatio = brightRatio,
            visibilityScore = visibilityScore,
            contrastScore = contrastScore,
            edgeScore = edgeScore,
            centerScore = centerScore,
            sizeOk = sizeOk,
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
        val brightRatio: Float = 0f,
        val visibilityScore: Float = 0f,
        val contrastScore: Float = 0f,
        val edgeScore: Float = 0f,
        val centerScore: Float = 0f,
        val sizeOk: Boolean = false,
    )

    companion object {
        private const val SAMPLE_SIZE = 64
        private const val EDGE_THRESHOLD = 24
        private const val READY_SIGNAL_THRESHOLD = 58
    }
}
