package com.q8ind.glamnsmile

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.hypot
import kotlin.math.min

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val transformMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val tempRect = RectF()

    private var minScale = 1f
    private var maxScale = 6f

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    init {
        super.setScaleType(ScaleType.MATRIX)
    }

    override fun setScaleType(scaleType: ScaleType) {
        // Keep MATRIX scaling for zoom/pan.
        super.setScaleType(ScaleType.MATRIX)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetToFit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetToFit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) {
            return false
        }

        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    return true
                }

                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex == -1) {
                    return true
                }

                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val dx = x - lastTouchX
                val dy = y - lastTouchY

                if (!isDragging) {
                    isDragging = hypot(dx.toDouble(), dy.toDouble()) >= TOUCH_SLOP_PX
                }

                if (isDragging) {
                    transformMatrix.postTranslate(dx, dy)
                    fixTranslation()
                    imageMatrix = transformMatrix
                    lastTouchX = x
                    lastTouchY = y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(newPointerIndex)
                    lastTouchX = event.getX(newPointerIndex)
                    lastTouchY = event.getY(newPointerIndex)
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isDragging = false
            }
        }

        return true
    }

    private fun resetToFit() {
        val drawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return
        }

        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        if (drawableWidth <= 0f || drawableHeight <= 0f) {
            return
        }

        val scale = min(viewWidth / drawableWidth, viewHeight / drawableHeight)
        val dx = (viewWidth - drawableWidth * scale) / 2f
        val dy = (viewHeight - drawableHeight * scale) / 2f

        transformMatrix.reset()
        transformMatrix.postScale(scale, scale)
        transformMatrix.postTranslate(dx, dy)
        imageMatrix = transformMatrix

        minScale = scale
        maxScale = scale * MAX_SCALE_MULTIPLIER
    }

    private fun getCurrentScale(): Float {
        transformMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun zoomTo(targetScale: Float, focusX: Float, focusY: Float) {
        val currentScale = getCurrentScale()
        val clamped = targetScale.coerceIn(minScale, maxScale)
        val factor = clamped / currentScale
        transformMatrix.postScale(factor, factor, focusX, focusY)
        fixTranslation()
        imageMatrix = transformMatrix
    }

    private fun fixTranslation() {
        val drawable = drawable ?: return
        tempRect.set(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        transformMatrix.mapRect(tempRect)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        var deltaX = 0f
        var deltaY = 0f

        if (tempRect.width() <= viewWidth) {
            deltaX = (viewWidth - tempRect.width()) / 2f - tempRect.left
        } else if (tempRect.left > 0f) {
            deltaX = -tempRect.left
        } else if (tempRect.right < viewWidth) {
            deltaX = viewWidth - tempRect.right
        }

        if (tempRect.height() <= viewHeight) {
            deltaY = (viewHeight - tempRect.height()) / 2f - tempRect.top
        } else if (tempRect.top > 0f) {
            deltaY = -tempRect.top
        } else if (tempRect.bottom < viewHeight) {
            deltaY = viewHeight - tempRect.bottom
        }

        if (deltaX != 0f || deltaY != 0f) {
            transformMatrix.postTranslate(deltaX, deltaY)
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val currentScale = getCurrentScale()
            val targetScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = targetScale / currentScale
            transformMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            fixTranslation()
            imageMatrix = transformMatrix
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val currentScale = getCurrentScale()
            val target = if (currentScale < minScale * DOUBLE_TAP_ZOOM_FACTOR) {
                minScale * DOUBLE_TAP_ZOOM_FACTOR
            } else {
                minScale
            }
            zoomTo(target, e.x, e.y)
            return true
        }
    }

    companion object {
        private const val MAX_SCALE_MULTIPLIER = 6f
        private const val DOUBLE_TAP_ZOOM_FACTOR = 2.5f
        private const val TOUCH_SLOP_PX = 6.0
    }
}

