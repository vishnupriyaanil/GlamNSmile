package com.q8ind.glamnsmile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.q8ind.glamnsmile.databinding.ActivityMainBinding
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var faceDetector: FaceDetector? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        updatePermissionUi(granted)
        if (granted) {
            startCamera()
        } else {
            renderState(FaceAnalysisUiState.permissionRequired())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()
        ensureRoiOverlayFullScreen()

        cameraExecutor = Executors.newSingleThreadExecutor()
        faceDetector = FaceDetection.getClient(createFaceDetectorOptions())
        binding.roiOverlay.setPreset(RoiOverlayView.Preset.FACE)

        updateCameraSwitchLabel()
        renderState(FaceAnalysisUiState.initial(currentLensLabel()))

        binding.switchCameraButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            updateCameraSwitchLabel()
            if (hasCameraPermission()) {
                renderState(FaceAnalysisUiState.initial(currentLensLabel()))
                startCamera()
            }
        }

        binding.permissionButton.setOnClickListener {
            requestCameraPermission()
        }

        val hasPermission = hasCameraPermission()
        updatePermissionUi(hasPermission)
        if (hasPermission) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        ensureRoiOverlayFullScreen()
        if (hasCameraPermission()) {
            updatePermissionUi(true)
            startCamera()
        }
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        faceDetector?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun ensureRoiOverlayFullScreen() {
        binding.roiOverlay.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            topToBottom = ConstraintLayout.LayoutParams.UNSET
            bottomToTop = ConstraintLayout.LayoutParams.UNSET
            startToEnd = ConstraintLayout.LayoutParams.UNSET
            endToStart = ConstraintLayout.LayoutParams.UNSET
        }
        binding.roiOverlay.elevation = resources.displayMetrics.density * 32f
        binding.roiOverlay.post {
            binding.roiOverlay.bringToFront()
            binding.permissionOverlay.bringToFront()
            binding.roiOverlay.invalidate()
        }
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun applyWindowInsets() {
        val bottomSafeSpacing = resources.getDimensionPixelSize(R.dimen.bottom_safe_spacing)
        val statusCardTopMargin =
            (binding.statusCard.layoutParams as ConstraintLayout.LayoutParams).topMargin
        val metricsCardBottomMargin =
            (binding.metricsCard.layoutParams as ConstraintLayout.LayoutParams).bottomMargin
        val permissionOverlayLeftPadding = binding.permissionOverlay.paddingLeft
        val permissionOverlayTopPadding = binding.permissionOverlay.paddingTop
        val permissionOverlayRightPadding = binding.permissionOverlay.paddingRight
        val permissionOverlayBottomPadding = binding.permissionOverlay.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.statusCard.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = statusCardTopMargin + systemBars.top
            }

            binding.metricsCard.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = metricsCardBottomMargin + systemBars.bottom + bottomSafeSpacing
            }

            binding.permissionOverlay.updatePadding(
                left = permissionOverlayLeftPadding + systemBars.left,
                top = permissionOverlayTopPadding + systemBars.top,
                right = permissionOverlayRightPadding + systemBars.right,
                bottom = permissionOverlayBottomPadding + systemBars.bottom + bottomSafeSpacing,
            )

            insets
        }

        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val existingProvider = cameraProvider
        if (existingProvider != null) {
            bindCameraUseCases(existingProvider)
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                cameraProvider = providerFuture.get()
                bindCameraUseCases(cameraProvider ?: return@addListener)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        val detector = faceDetector ?: return
        val preview = Preview.Builder()
            .build()
            .also { it.surfaceProvider = binding.previewView.surfaceProvider }

        val analyzer = FaceAnalyzer(detector, cameraExecutor, ::renderState)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(cameraExecutor, analyzer) }

        imageAnalysis?.clearAnalyzer()
        imageAnalysis = analysis

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
            renderState(FaceAnalysisUiState.initial(currentLensLabel()))
        } catch (error: Exception) {
            val message = error.localizedMessage ?: "Unknown camera binding error"
            renderState(FaceAnalysisUiState.cameraError(message))
        }
    }

    private fun renderState(state: FaceAnalysisUiState) {
        runOnUiThread {
            binding.statusText.text = state.statusText
            binding.faceCountValue.text = state.faceCount.toString()
            binding.trackingValue.text = state.trackingId?.toString() ?: getString(R.string.value_not_available)
            binding.smileValue.text = state.smileProbability.asPercent()
            binding.leftEyeValue.text = state.leftEyeOpenProbability.asPercent()
            binding.rightEyeValue.text = state.rightEyeOpenProbability.asPercent()
            binding.yawValue.text = state.yaw.asDegrees()
            binding.pitchValue.text = state.pitch.asDegrees()
            binding.rollValue.text = state.roll.asDegrees()
            binding.wrinkleValue.text = wrinkleSummary(
                score = state.wrinkleScore,
                label = state.wrinkleLabel,
            )
            binding.ageValue.text = state.estimatedAgeBand ?: getString(R.string.value_not_available)
            binding.skinToneValue.text = skinToneSummary(
                label = state.skinToneLabel,
                hex = state.skinToneHex,
            )
            binding.errorText.isVisible = state.errorMessage != null
            binding.errorText.text = state.errorMessage
        }
    }

    private fun updatePermissionUi(hasPermission: Boolean) {
        binding.permissionOverlay.isVisible = !hasPermission
        binding.previewView.alpha = if (hasPermission) 1f else 0.25f
        binding.switchCameraButton.isEnabled = hasPermission
    }

    private fun updateCameraSwitchLabel() {
        binding.switchCameraButton.text = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            getString(R.string.switch_to_back_camera)
        } else {
            getString(R.string.switch_to_front_camera)
        }
    }

    private fun currentLensLabel(): String {
        return if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            getString(R.string.front_camera_label)
        } else {
            getString(R.string.back_camera_label)
        }
    }

    private fun createFaceDetectorOptions(): FaceDetectorOptions {
        return FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .enableTracking()
            .setMinFaceSize(0.15f)
            .build()
    }

    private fun Float?.asPercent(): String {
        if (this == null) return getString(R.string.value_not_available)
        return String.format(Locale.US, "%.0f%%", this * 100f)
    }

    private fun Float?.asDegrees(): String {
        if (this == null) return getString(R.string.value_not_available)
        return String.format(Locale.US, "%.1f deg", this)
    }

    private fun wrinkleSummary(score: Float?, label: String?): String {
        if (score == null || label == null) return getString(R.string.value_not_available)
        return String.format(Locale.US, "%s (%d/100)", label, score.roundToInt())
    }

    private fun skinToneSummary(label: String?, hex: String?): String {
        if (label == null || hex == null) return getString(R.string.value_not_available)
        return "$label $hex"
    }
}
