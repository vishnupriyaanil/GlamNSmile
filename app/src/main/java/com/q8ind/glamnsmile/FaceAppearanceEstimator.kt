package com.q8ind.glamnsmile

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.face.Face
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FaceAppearanceEstimate(
    val wrinkleScore: Float,
    val wrinkleLabel: String,
    val ageBandLabel: String,
    val skinToneLabel: String,
    val skinToneHex: String,
)

class FaceAppearanceEstimator {

    fun estimate(imageProxy: ImageProxy, face: Face): FaceAppearanceEstimate? {
        if (abs(face.headEulerAngleY) > 18f || abs(face.headEulerAngleX) > 15f || abs(face.headEulerAngleZ) > 12f) {
            return null
        }

        val sampler = YuvSampler(imageProxy)
        val faceRect = face.toClampedRect(
            width = sampler.uprightWidth,
            height = sampler.uprightHeight,
        ) ?: return null

        if (faceRect.width() < 140 || faceRect.height() < 140) {
            return null
        }

        val foreheadRegion = faceRect.subRect(0.30f, 0.16f, 0.70f, 0.31f)
        val leftCheekRegion = faceRect.subRect(0.18f, 0.48f, 0.36f, 0.72f)
        val rightCheekRegion = faceRect.subRect(0.64f, 0.48f, 0.82f, 0.72f)
        val centerSkinRegion = faceRect.subRect(0.33f, 0.42f, 0.67f, 0.70f)

        val centerSkinStats = sampler.sampleColorStats(
            rect = centerSkinRegion,
            columns = 14,
            rows = 10,
            ellipticalMask = true,
        ) ?: return null

        val leftCheekColorStats = sampler.sampleColorStats(
            rect = leftCheekRegion,
            columns = 10,
            rows = 8,
            ellipticalMask = true,
        ) ?: return null

        val rightCheekColorStats = sampler.sampleColorStats(
            rect = rightCheekRegion,
            columns = 10,
            rows = 8,
            ellipticalMask = true,
        ) ?: return null

        val skinStats = averageColorStats(
            centerSkinStats,
            leftCheekColorStats,
            rightCheekColorStats,
        )

        if (skinStats.meanLuma !in 65f..225f) {
            return null
        }

        val foreheadTexture = sampler.sampleTextureStats(
            rect = foreheadRegion,
            columns = 12,
            rows = 6,
            ellipticalMask = true,
        ) ?: return null

        val leftCheekTexture = sampler.sampleTextureStats(
            rect = leftCheekRegion,
            columns = 10,
            rows = 8,
            ellipticalMask = true,
        ) ?: return null

        val rightCheekTexture = sampler.sampleTextureStats(
            rect = rightCheekRegion,
            columns = 10,
            rows = 8,
            ellipticalMask = true,
        ) ?: return null

        val cheekTexture = averageTextureStats(leftCheekTexture, rightCheekTexture)

        val textureIndex = (foreheadTexture.gradientRatio * 0.42f) +
            (cheekTexture.gradientRatio * 0.28f) +
            (foreheadTexture.contrastRatio * 0.20f) +
            (cheekTexture.contrastRatio * 0.10f)

        val exposureBias = when {
            skinStats.meanLuma < 90f -> -8f
            skinStats.meanLuma < 110f -> -4f
            skinStats.meanLuma > 205f -> -5f
            else -> 0f
        }

        val wrinkleScore = ((((textureIndex - 0.052f) / 0.11f) * 100f) + exposureBias)
            .coerceIn(0f, 100f)
        val wrinkleLabel = wrinkleLabelFor(wrinkleScore)
        val ageBandLabel = ageBandFor(wrinkleScore)
        val skinToneLabel = skinToneLabelFor(
            meanLuma = skinStats.meanLuma,
            meanRed = skinStats.meanRed,
            meanGreen = skinStats.meanGreen,
            meanBlue = skinStats.meanBlue,
        )
        val skinToneHex = String.format(
            Locale.US,
            "#%02X%02X%02X",
            skinStats.meanRed.roundToInt().coerceIn(0, 255),
            skinStats.meanGreen.roundToInt().coerceIn(0, 255),
            skinStats.meanBlue.roundToInt().coerceIn(0, 255),
        )

        return FaceAppearanceEstimate(
            wrinkleScore = wrinkleScore,
            wrinkleLabel = wrinkleLabel,
            ageBandLabel = ageBandLabel,
            skinToneLabel = skinToneLabel,
            skinToneHex = skinToneHex,
        )
    }

    private fun wrinkleLabelFor(score: Float): String {
        return when {
            score < 16f -> "Minimal"
            score < 32f -> "Light"
            score < 50f -> "Moderate"
            score < 70f -> "Visible"
            else -> "High"
        }
    }

    private fun ageBandFor(wrinkleScore: Float): String {
        return when {
            wrinkleScore < 18f -> "Under 25"
            wrinkleScore < 34f -> "25-34"
            wrinkleScore < 50f -> "35-44"
            wrinkleScore < 68f -> "45-59"
            else -> "60+"
        }
    }

    private fun skinToneLabelFor(
        meanLuma: Float,
        meanRed: Float,
        meanGreen: Float,
        meanBlue: Float,
    ): String {
        val depthTone = when {
            meanLuma >= 185f -> "Light"
            meanLuma >= 155f -> "Light-medium"
            meanLuma >= 125f -> "Medium"
            meanLuma >= 95f -> "Medium-deep"
            else -> "Deep"
        }

        val undertone = when {
            meanRed > meanGreen + 10f && meanRed > meanBlue + 18f -> "warm"
            meanBlue > meanRed + 8f && meanBlue > meanGreen + 4f -> "cool"
            else -> "neutral"
        }

        return "$depthTone $undertone"
    }

    private fun averageColorStats(vararg values: ColorStats): ColorStats {
        val size = values.size.toFloat()
        return ColorStats(
            meanRed = values.sumOf { it.meanRed.toDouble() }.toFloat() / size,
            meanGreen = values.sumOf { it.meanGreen.toDouble() }.toFloat() / size,
            meanBlue = values.sumOf { it.meanBlue.toDouble() }.toFloat() / size,
            meanLuma = values.sumOf { it.meanLuma.toDouble() }.toFloat() / size,
        )
    }

    private fun averageTextureStats(vararg values: TextureStats): TextureStats {
        val size = values.size.toFloat()
        return TextureStats(
            contrast = values.sumOf { it.contrast.toDouble() }.toFloat() / size,
            gradient = values.sumOf { it.gradient.toDouble() }.toFloat() / size,
            meanLuma = values.sumOf { it.meanLuma.toDouble() }.toFloat() / size,
            contrastRatio = values.sumOf { it.contrastRatio.toDouble() }.toFloat() / size,
            gradientRatio = values.sumOf { it.gradientRatio.toDouble() }.toFloat() / size,
        )
    }

    private fun Face.toClampedRect(width: Int, height: Int): Rect? {
        val left = boundingBox.left.coerceIn(0, width - 1)
        val top = boundingBox.top.coerceIn(0, height - 1)
        val right = boundingBox.right.coerceIn(left + 1, width)
        val bottom = boundingBox.bottom.coerceIn(top + 1, height)

        if (right - left < 1 || bottom - top < 1) {
            return null
        }

        return Rect(left, top, right, bottom)
    }

    private fun Rect.subRect(
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float,
    ): Rect {
        val width = width()
        val height = height()
        val left = left + (width * leftRatio).roundToInt()
        val top = top + (height * topRatio).roundToInt()
        val right = left + max(8, (width * (rightRatio - leftRatio)).roundToInt())
        val bottom = top + max(8, (height * (bottomRatio - topRatio)).roundToInt())
        return Rect(
            left.coerceAtLeast(this.left),
            top.coerceAtLeast(this.top),
            right.coerceAtMost(this.right),
            bottom.coerceAtMost(this.bottom),
        )
    }

    private data class ColorStats(
        val meanRed: Float,
        val meanGreen: Float,
        val meanBlue: Float,
        val meanLuma: Float,
    )

    private data class TextureStats(
        val contrast: Float,
        val gradient: Float,
        val meanLuma: Float,
        val contrastRatio: Float,
        val gradientRatio: Float,
    )

    private data class RgbSample(
        val red: Int,
        val green: Int,
        val blue: Int,
        val luma: Int,
    )

    private class YuvSampler(
        imageProxy: ImageProxy,
    ) {
        private val width = imageProxy.width
        private val height = imageProxy.height
        private val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        private val yPlane = imageProxy.planes[0]
        private val uPlane = imageProxy.planes[1]
        private val vPlane = imageProxy.planes[2]

        val uprightWidth: Int = if (rotationDegrees % 180 == 0) width else height
        val uprightHeight: Int = if (rotationDegrees % 180 == 0) height else width

        fun sampleColorStats(
            rect: Rect,
            columns: Int,
            rows: Int,
            ellipticalMask: Boolean,
        ): ColorStats? {
            var sumRed = 0f
            var sumGreen = 0f
            var sumBlue = 0f
            var sumLuma = 0f
            var count = 0

            val centerX = rect.exactCenterX()
            val centerY = rect.exactCenterY()
            val radiusX = rect.width() / 2f
            val radiusY = rect.height() / 2f

            for (row in 0 until rows) {
                val sampleY = sampleCoordinate(rect.top, rect.height(), row, rows)
                for (column in 0 until columns) {
                    val sampleX = sampleCoordinate(rect.left, rect.width(), column, columns)
                    if (ellipticalMask) {
                        val ellipseX = (sampleX - centerX) / radiusX
                        val ellipseY = (sampleY - centerY) / radiusY
                        if ((ellipseX * ellipseX) + (ellipseY * ellipseY) > 1f) {
                            continue
                        }
                    }

                    val sample = sampleRgb(sampleX, sampleY)
                    if (sample.luma < 20 || sample.luma > 245) {
                        continue
                    }

                    sumRed += sample.red
                    sumGreen += sample.green
                    sumBlue += sample.blue
                    sumLuma += sample.luma
                    count += 1
                }
            }

            if (count < 12) {
                return null
            }

            return ColorStats(
                meanRed = sumRed / count,
                meanGreen = sumGreen / count,
                meanBlue = sumBlue / count,
                meanLuma = sumLuma / count,
            )
        }

        fun sampleTextureStats(
            rect: Rect,
            columns: Int,
            rows: Int,
            ellipticalMask: Boolean,
        ): TextureStats? {
            if (rect.width() < 12 || rect.height() < 12) {
                return null
            }

            val grid = Array(rows) { FloatArray(columns) }
            val valid = Array(rows) { BooleanArray(columns) }
            var sum = 0f
            var sampleCount = 0
            val centerX = rect.exactCenterX()
            val centerY = rect.exactCenterY()
            val radiusX = rect.width() / 2f
            val radiusY = rect.height() / 2f

            for (row in 0 until rows) {
                val sampleY = sampleCoordinate(rect.top, rect.height(), row, rows)
                for (column in 0 until columns) {
                    val sampleX = sampleCoordinate(rect.left, rect.width(), column, columns)
                    if (ellipticalMask) {
                        val ellipseX = (sampleX - centerX) / radiusX
                        val ellipseY = (sampleY - centerY) / radiusY
                        if ((ellipseX * ellipseX) + (ellipseY * ellipseY) > 1f) {
                            continue
                        }
                    }

                    val luma = sampleRgb(sampleX, sampleY).luma.toFloat()
                    if (luma < 20f || luma > 245f) {
                        continue
                    }
                    grid[row][column] = luma
                    valid[row][column] = true
                    sum += luma
                    sampleCount += 1
                }
            }

            if (sampleCount < 20) {
                return null
            }

            val mean = sum / sampleCount
            var varianceSum = 0f
            var gradientSum = 0f
            var edgeCount = 0

            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    if (!valid[row][column]) {
                        continue
                    }

                    val current = grid[row][column]
                    val diff = current - mean
                    varianceSum += diff * diff

                    if (column + 1 < columns && valid[row][column + 1]) {
                        gradientSum += abs(current - grid[row][column + 1])
                        edgeCount += 1
                    }

                    if (row + 1 < rows && valid[row + 1][column]) {
                        gradientSum += abs(current - grid[row + 1][column])
                        edgeCount += 1
                    }
                }
            }

            return TextureStats(
                contrast = sqrt(varianceSum / sampleCount),
                gradient = gradientSum / edgeCount.coerceAtLeast(1),
                meanLuma = mean,
                contrastRatio = sqrt(varianceSum / sampleCount) / mean.coerceAtLeast(1f),
                gradientRatio = (gradientSum / edgeCount.coerceAtLeast(1)) / mean.coerceAtLeast(1f),
            )
        }

        private fun sampleRgb(uprightX: Int, uprightY: Int): RgbSample {
            val (rawX, rawY) = mapToRawCoordinates(
                uprightX = uprightX.coerceIn(0, uprightWidth - 1),
                uprightY = uprightY.coerceIn(0, uprightHeight - 1),
            )

            val y = planeValue(yPlane, rawX, rawY)
            val uvX = rawX / 2
            val uvY = rawY / 2
            val u = planeValue(uPlane, uvX, uvY)
            val v = planeValue(vPlane, uvX, uvY)

            val adjustedY = (y - 16).coerceAtLeast(0)
            val adjustedU = u - 128
            val adjustedV = v - 128

            val red = (1.164f * adjustedY + 1.596f * adjustedV).roundToInt().coerceIn(0, 255)
            val green = (1.164f * adjustedY - 0.813f * adjustedV - 0.391f * adjustedU)
                .roundToInt()
                .coerceIn(0, 255)
            val blue = (1.164f * adjustedY + 2.018f * adjustedU).roundToInt().coerceIn(0, 255)

            return RgbSample(
                red = red,
                green = green,
                blue = blue,
                luma = y,
            )
        }

        private fun mapToRawCoordinates(uprightX: Int, uprightY: Int): Pair<Int, Int> {
            return when (rotationDegrees) {
                0 -> Pair(uprightX, uprightY)
                90 -> Pair(uprightY, height - 1 - uprightX)
                180 -> Pair(width - 1 - uprightX, height - 1 - uprightY)
                270 -> Pair(width - 1 - uprightY, uprightX)
                else -> Pair(uprightX, uprightY)
            }
        }

        private fun planeValue(
            plane: ImageProxy.PlaneProxy,
            x: Int,
            y: Int,
        ): Int {
            val index = (y * plane.rowStride) + (x * plane.pixelStride)
            return plane.buffer.get(index).toInt() and 0xFF
        }

        private fun sampleCoordinate(start: Int, size: Int, index: Int, count: Int): Int {
            val position = ((index + 0.5f) * size / count).roundToInt()
            return (start + position).coerceIn(start, start + size - 1)
        }
    }
}
