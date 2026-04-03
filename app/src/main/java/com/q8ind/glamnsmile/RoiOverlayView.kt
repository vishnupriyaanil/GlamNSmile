package com.q8ind.glamnsmile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Preset {
        FACE,
        DENTAL,
    }

    private enum class Shape {
        OVAL,
        RECTANGLE,
    }

    private val roiRect = RectF()
    private val roiOverrideRect = RectF()
    private var isRoiOverrideEnabled: Boolean = false
    private val scrimPath = Path()
    private val borderPath = Path()

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x99000000.toInt()
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
        color = runCatching {
            ContextCompat.getColor(context, R.color.accent_teal)
        }.getOrDefault(0xFF4FD1C5.toInt())
    }

    private var preset: Preset = Preset.FACE
    private var shape: Shape = Shape.OVAL
    private var scrimEnabled: Boolean = false
    private var widthFraction = 0.72f
    private var aspectRatio = 0.75f // width / height
    private var maxHeightFraction = 0.86f
    private var verticalBias = 0.52f
    private var cornerRadiusPx = dpToPx(18f)

    init {
        applyPreset(preset)
    }

    fun setPreset(preset: Preset) {
        if (this.preset == preset) return
        this.preset = preset
        applyPreset(preset)
        updatePaths()
        invalidate()
    }

    fun setScrimEnabled(enabled: Boolean) {
        if (scrimEnabled == enabled) return
        scrimEnabled = enabled
        invalidate()
    }

    fun setRoiOverride(rect: RectF?) {
        val normalizedRect = rect?.takeIf { !it.isEmpty }
        if (normalizedRect == null) {
            if (!isRoiOverrideEnabled) return
            isRoiOverrideEnabled = false
        } else {
            roiOverrideRect.set(normalizedRect)
            if (!isRoiOverrideEnabled) {
                isRoiOverrideEnabled = true
            }
        }
        updatePaths()
        invalidate()
    }

    fun getRoiRect(): RectF {
        return RectF(roiRect)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePaths()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        if (scrimEnabled) {
            canvas.drawPath(scrimPath, scrimPaint)
        }
        canvas.drawPath(borderPath, borderPaint)
    }

    private fun applyPreset(preset: Preset) {
        when (preset) {
            Preset.FACE -> {
                shape = Shape.OVAL
                widthFraction = FACE_WIDTH_FRACTION
                aspectRatio = FACE_ASPECT_RATIO
                maxHeightFraction = FACE_MAX_HEIGHT_FRACTION
                verticalBias = FACE_VERTICAL_BIAS
            }

            Preset.DENTAL -> {
                shape = Shape.RECTANGLE
                widthFraction = DENTAL_WIDTH_FRACTION
                aspectRatio = DENTAL_ASPECT_RATIO
                maxHeightFraction = DENTAL_MAX_HEIGHT_FRACTION
                verticalBias = DENTAL_VERTICAL_BIAS
                cornerRadiusPx = dpToPx(DENTAL_CORNER_RADIUS_DP)
            }
        }
    }

    private fun updatePaths() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        if (isRoiOverrideEnabled) {
            roiRect.set(
                roiOverrideRect.left.coerceIn(0f, viewWidth),
                roiOverrideRect.top.coerceIn(0f, viewHeight),
                roiOverrideRect.right.coerceIn(0f, viewWidth),
                roiOverrideRect.bottom.coerceIn(0f, viewHeight),
            )
            if (roiRect.right <= roiRect.left || roiRect.bottom <= roiRect.top) {
                roiRect.set(0f, 0f, viewWidth, viewHeight)
            }
        } else if (preset == Preset.FACE) {
            roiRect.set(0f, 0f, viewWidth, viewHeight)
        } else {
            var roiWidth = viewWidth * widthFraction
            var roiHeight = roiWidth / max(aspectRatio, 0.01f)
            val maxRoiHeight = viewHeight * maxHeightFraction
            if (roiHeight > maxRoiHeight) {
                roiHeight = maxRoiHeight
                roiWidth = roiHeight * aspectRatio
            }

            val left = (viewWidth - roiWidth) / 2f
            val top = (viewHeight - roiHeight) * verticalBias
            roiRect.set(left, top, left + roiWidth, top + roiHeight)
        }

        scrimPath.reset()
        borderPath.reset()
        scrimPath.addRect(0f, 0f, viewWidth, viewHeight, Path.Direction.CW)
        when (shape) {
            Shape.OVAL -> {
                scrimPath.addOval(roiRect, Path.Direction.CW)
                borderPath.addOval(roiRect, Path.Direction.CW)
            }

            Shape.RECTANGLE -> {
                scrimPath.addRoundRect(roiRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
                borderPath.addRoundRect(roiRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
            }
        }
        scrimPath.fillType = Path.FillType.EVEN_ODD
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    companion object {
        // Fractions are relative to the container (view or bitmap) size.
        private const val FACE_WIDTH_FRACTION = 0.98f
        private const val FACE_ASPECT_RATIO = 0.72f // width / height (taller oval)
        private const val FACE_MAX_HEIGHT_FRACTION = 1.0f
        private const val FACE_VERTICAL_BIAS = 0.50f

        private const val DENTAL_WIDTH_FRACTION = 0.86f
        private const val DENTAL_ASPECT_RATIO = 2.25f // width / height
        private const val DENTAL_MAX_HEIGHT_FRACTION = 0.62f
        private const val DENTAL_VERTICAL_BIAS = 0.58f
        private const val DENTAL_CORNER_RADIUS_DP = 20f

        fun computeRoiRect(widthPx: Int, heightPx: Int, preset: Preset): RectF {
            val viewWidth = widthPx.toFloat().coerceAtLeast(0f)
            val viewHeight = heightPx.toFloat().coerceAtLeast(0f)
            if (viewWidth <= 0f || viewHeight <= 0f) return RectF()

            if (preset == Preset.FACE) {
                return RectF(0f, 0f, viewWidth, viewHeight)
            }

            val (widthFraction, aspectRatio, maxHeightFraction, verticalBias) = when (preset) {
                Preset.FACE -> RoiGeometry(
                    widthFraction = FACE_WIDTH_FRACTION,
                    aspectRatio = FACE_ASPECT_RATIO,
                    maxHeightFraction = FACE_MAX_HEIGHT_FRACTION,
                    verticalBias = FACE_VERTICAL_BIAS,
                )

                Preset.DENTAL -> RoiGeometry(
                    widthFraction = DENTAL_WIDTH_FRACTION,
                    aspectRatio = DENTAL_ASPECT_RATIO,
                    maxHeightFraction = DENTAL_MAX_HEIGHT_FRACTION,
                    verticalBias = DENTAL_VERTICAL_BIAS,
                )
            }

            var roiWidth = viewWidth * widthFraction
            var roiHeight = roiWidth / max(aspectRatio, 0.01f)
            val maxRoiHeight = viewHeight * maxHeightFraction
            if (roiHeight > maxRoiHeight) {
                roiHeight = maxRoiHeight
                roiWidth = roiHeight * aspectRatio
            }

            val left = (viewWidth - roiWidth) / 2f
            val top = (viewHeight - roiHeight) * verticalBias.coerceIn(0f, 1f)
            return RectF(left, top, left + roiWidth, top + roiHeight)
        }

        private data class RoiGeometry(
            val widthFraction: Float,
            val aspectRatio: Float,
            val maxHeightFraction: Float,
            val verticalBias: Float,
        )
    }
}
