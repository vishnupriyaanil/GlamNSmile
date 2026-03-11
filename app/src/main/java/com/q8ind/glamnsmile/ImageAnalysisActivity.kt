package com.q8ind.glamnsmile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.q8ind.glamnsmile.databinding.ActivityImageAnalysisBinding
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ImageAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageAnalysisBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analysisMode: AnalysisMode

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var faceDetector: FaceDetector? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT
    private var latestState: FaceCaptureUiState = FaceCaptureUiState.initial("Front camera")
    private var isCaptureInProgress = false
    private val capturedImageFiles = mutableListOf<File>()

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        updatePermissionUi(granted)
        if (granted) {
            startCamera()
        } else {
            renderState(FaceCaptureUiState.permissionRequired())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityImageAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        analysisMode = AnalysisMode.fromId(intent.getStringExtra(EXTRA_ANALYSIS_MODE))
        cameraExecutor = Executors.newSingleThreadExecutor()
        faceDetector = FaceDetection.getClient(createFaceDetectorOptions())

        configureModeUi()

        binding.backButton.setOnClickListener { finish() }
        binding.switchCameraButton.setOnClickListener { toggleCamera() }
        binding.permissionButton.setOnClickListener { requestCameraPermission() }
        binding.analyzeButton.setOnClickListener { handlePrimaryAction() }
        binding.clearImagesButton.setOnClickListener { clearCapturedImages() }

        updateCameraSwitchLabel()
        renderState(FaceCaptureUiState.initial(currentLensLabel()))

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

    private fun configureModeUi() {
        binding.captureTitleText.text = getString(analysisMode.captureTitleRes)
        binding.captureSubtitleText.text = getString(analysisMode.captureSubtitleRes)
        binding.captureProgressRow.isVisible = analysisMode.requiredImages > 1
    }

    private fun toggleCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        updateCameraSwitchLabel()
        if (hasCameraPermission()) {
            renderState(FaceCaptureUiState.initial(currentLensLabel()))
            startCamera()
        }
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun applyWindowInsets() {
        val bottomSafeSpacing = resources.getDimensionPixelSize(R.dimen.bottom_safe_spacing)
        val statusCardTopMargin =
            (binding.statusCard.layoutParams as ConstraintLayout.LayoutParams).topMargin
        val actionCardBottomMargin =
            (binding.actionCard.layoutParams as ConstraintLayout.LayoutParams).bottomMargin
        val permissionOverlayLeftPadding = binding.permissionOverlay.paddingLeft
        val permissionOverlayTopPadding = binding.permissionOverlay.paddingTop
        val permissionOverlayRightPadding = binding.permissionOverlay.paddingRight
        val permissionOverlayBottomPadding = binding.permissionOverlay.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.statusCard.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = statusCardTopMargin + systemBars.top
            }

            binding.actionCard.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = actionCardBottomMargin + systemBars.bottom + bottomSafeSpacing
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
        val targetRotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
            .also { it.surfaceProvider = binding.previewView.surfaceProvider }

        val analyzer = FaceRecognitionAnalyzer(detector, cameraExecutor, ::onCaptureStateChanged)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(targetRotation)
            .build()
            .also { it.setAnalyzer(cameraExecutor, analyzer) }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(targetRotation)
            .build()

        imageAnalysis?.clearAnalyzer()
        imageAnalysis = analysis
        imageCapture = capture

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis, capture)
            if (!isCaptureInProgress) {
                renderState(FaceCaptureUiState.initial(currentLensLabel()))
            }
        } catch (error: Exception) {
            val message = error.localizedMessage ?: "Unknown camera binding error"
            renderState(FaceCaptureUiState.cameraError(message))
        }
    }

    private fun onCaptureStateChanged(state: FaceCaptureUiState) {
        latestState = state
        if (!isCaptureInProgress) {
            renderState(state)
        }
    }

    private fun handlePrimaryAction() {
        if (analysisMode.requiredImages > 1 && capturedImageFiles.size >= analysisMode.requiredImages) {
            launchAnalysisResult(capturedImageFiles.map(File::getAbsolutePath))
        } else {
            captureCurrentImage()
        }
    }

    private fun captureCurrentImage() {
        val capture = imageCapture ?: run {
            renderState(
                latestState.copy(
                    errorMessage = "Image capture is not ready yet.",
                ),
            )
            return
        }

        if (!shouldEnablePrimaryAction()) {
            return
        }

        isCaptureInProgress = true
        renderState(FaceCaptureUiState.capturing())
        capture.targetRotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0

        val outputFile = File(cacheDir, "face-capture-${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    isCaptureInProgress = false

                    if (analysisMode.requiredImages == 1) {
                        launchAnalysisResult(listOf(outputFile.absolutePath))
                    } else {
                        capturedImageFiles += outputFile
                        renderState(latestState.copy(errorMessage = null))
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isCaptureInProgress = false
                    renderState(
                        latestState.copy(
                            errorMessage = exception.localizedMessage ?: "Image capture failed.",
                        ),
                    )
                }
            },
        )
    }

    private fun launchAnalysisResult(imagePaths: List<String>) {
        startActivity(AnalysisResultActivity.newIntent(this, analysisMode, imagePaths))
    }

    private fun clearCapturedImages() {
        capturedImageFiles.forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        capturedImageFiles.clear()
        renderState(latestState.copy(errorMessage = null))
    }

    private fun renderState(state: FaceCaptureUiState) {
        runOnUiThread {
            binding.statusText.text = statusTextFor(state)
            binding.guidanceText.text = guidanceTextFor(state)
            binding.faceCountValue.text = state.faceCount.toString()
            binding.readinessValue.text = state.readinessLabel
            binding.captureCountValue.text = getString(
                R.string.capture_count_value,
                capturedImageFiles.size,
                analysisMode.requiredImages,
            )
            binding.clearImagesButton.isVisible =
                analysisMode.requiredImages > 1 && capturedImageFiles.isNotEmpty() && !isCaptureInProgress
            binding.errorText.isVisible = state.errorMessage != null
            binding.errorText.text = state.errorMessage
            binding.analyzeButton.isEnabled = shouldEnablePrimaryAction(state)
            binding.analyzeButton.text = primaryActionText()
            binding.switchCameraButton.isEnabled = hasCameraPermission() && !isCaptureInProgress
        }
    }

    private fun statusTextFor(state: FaceCaptureUiState): String {
        if (analysisMode == AnalysisMode.DENTAL) {
            return when {
                isCaptureInProgress -> getString(R.string.dental_capture_saving_status)
                capturedImageFiles.size >= analysisMode.requiredImages -> getString(R.string.dental_capture_ready_status)
                capturedImageFiles.isNotEmpty() -> getString(
                    R.string.dental_capture_progress_status,
                    capturedImageFiles.size,
                    analysisMode.requiredImages,
                )
                else -> state.statusText
            }
        }
        return state.statusText
    }

    private fun guidanceTextFor(state: FaceCaptureUiState): String {
        if (analysisMode == AnalysisMode.DENTAL) {
            return when {
                isCaptureInProgress -> getString(R.string.dental_capture_saving_guidance)
                capturedImageFiles.size >= analysisMode.requiredImages -> getString(R.string.dental_capture_ready_guidance)
                capturedImageFiles.isNotEmpty() -> getString(
                    R.string.dental_capture_progress_guidance,
                    capturedImageFiles.size + 1,
                    analysisMode.requiredImages,
                )
                else -> state.guidanceText
            }
        }
        return state.guidanceText
    }

    private fun primaryActionText(): String {
        return when {
            isCaptureInProgress -> getString(R.string.capturing_image)
            analysisMode.requiredImages > 1 && capturedImageFiles.size < analysisMode.requiredImages ->
                getString(
                    R.string.capture_image_step,
                    capturedImageFiles.size + 1,
                    analysisMode.requiredImages,
                )
            analysisMode == AnalysisMode.DENTAL -> getString(R.string.analyze_dental_images)
            else -> getString(R.string.analyze_image)
        }
    }

    private fun shouldEnablePrimaryAction(state: FaceCaptureUiState = latestState): Boolean {
        if (!hasCameraPermission() || isCaptureInProgress) {
            return false
        }

        return if (analysisMode.requiredImages > 1 && capturedImageFiles.size >= analysisMode.requiredImages) {
            true
        } else {
            state.canAnalyze
        }
    }

    private fun updatePermissionUi(hasPermission: Boolean) {
        binding.permissionOverlay.isVisible = !hasPermission
        binding.previewView.alpha = if (hasPermission) 1f else 0.25f
        binding.switchCameraButton.isEnabled = hasPermission && !isCaptureInProgress
        binding.analyzeButton.isEnabled = hasPermission && shouldEnablePrimaryAction()
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
            .enableTracking()
            .setMinFaceSize(0.15f)
            .build()
    }

    companion object {
        private const val EXTRA_ANALYSIS_MODE = "image_analysis_mode"

        fun newIntent(context: Context, mode: AnalysisMode): Intent {
            return Intent(context, ImageAnalysisActivity::class.java)
                .putExtra(EXTRA_ANALYSIS_MODE, mode.id)
        }
    }
}
