package com.q8ind.glamnsmile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.util.Rational
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.OutputTransform
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.ViewCompat.setTooltipText
import androidx.lifecycle.observe
import androidx.drawerlayout.widget.DrawerLayout
import com.q8ind.glamnsmile.databinding.ActivityImageAnalysisBinding
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

class ImageAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageAnalysisBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analysisMode: AnalysisMode
    private lateinit var analysisProvider: AnalysisProvider

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var faceDetector: FaceDetector? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT
    private var latestState: FaceCaptureUiState = FaceCaptureUiState(statusText = "", guidanceText = "")
    private var isCaptureInProgress = false
    private val capturedImageFiles = mutableListOf<File>()
    private val capturedRoiSpecs = mutableListOf<String>()
    private val previewOutputTransformRef = AtomicReference<OutputTransform?>(null)
    private var adminTapCount = 0
    private var adminUnlocked: Boolean = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        updatePermissionUi(granted)
        if (granted) {
            startCamera()
        } else {
            renderState(FaceCaptureUiState.permissionRequired(analysisMode))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityImageAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()
        binding.previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePreviewOutputTransform()
        }
        binding.previewView.post { updatePreviewOutputTransform() }
        binding.previewView.previewStreamState.observe(this) { streamState ->
            if (streamState == PreviewView.StreamState.STREAMING) {
                updatePreviewOutputTransform()
            }
        }

        analysisMode = intent.getStringExtra(EXTRA_ANALYSIS_MODE)
            ?.let { AnalysisMode.fromId(it) }
            ?: loadSelectedMode()
        analysisProvider = intent.getStringExtra(EXTRA_ANALYSIS_PROVIDER)
            ?.let { AnalysisProvider.fromId(it) }
            ?: loadSelectedProvider()
        persistSelectedMode(analysisMode)
        persistSelectedProvider(analysisProvider)
        PromptStore.hydrate(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        ensureDetectorForMode()

        configureModeUi()
        ensureRoiOverlayFullScreen()

        setupDrawerNavigation()
        binding.switchCameraButton.setOnClickListener { toggleCamera() }
        binding.permissionButton.setOnClickListener { requestCameraPermission() }
        binding.analyzeButton.setOnClickListener { handlePrimaryAction() }
        binding.clearImagesButton.setOnClickListener { clearCapturedImages() }

        updateCameraSwitchLabel()
        renderState(FaceCaptureUiState.initial(currentLensLabel(), analysisMode))

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
        updatePreviewOutputTransform()
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
        binding.roiOverlay.post {
            binding.permissionOverlay.bringToFront()
            binding.roiOverlay.invalidate()
        }
    }

    private fun updatePreviewOutputTransform() {
        previewOutputTransformRef.set(binding.previewView.outputTransform)
    }

    private fun setupDrawerNavigation() {
        binding.captureToolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        setupDrawerHeader()
        refreshAdminMenuVisibility()
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                if (drawerView == binding.navigationView) {
                    adminTapCount = 0
                }
            }
        })

        binding.navigationView.setNavigationItemSelectedListener { item ->
            val handled = when (item.itemId) {
                R.id.nav_mode_face -> {
                    applyAnalysisMode(AnalysisMode.FACIAL)
                    true
                }

                R.id.nav_mode_dental -> {
                    applyAnalysisMode(AnalysisMode.DENTAL)
                    true
                }

                R.id.nav_provider_gemini -> {
                    applyAnalysisProvider(AnalysisProvider.GEMINI)
                    true
                }

                R.id.nav_provider_openai -> {
                    applyAnalysisProvider(AnalysisProvider.OPENAI)
                    true
                }

                R.id.nav_admin_history -> {
                    startActivity(Intent(this, AdminDataActivity::class.java))
                    true
                }

                R.id.nav_admin_prompts -> {
                    startActivity(Intent(this, PromptTemplatesActivity::class.java))
                    true
                }

                else -> false
            }

            if (handled) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                refreshDrawerSelection()
            }
            handled
        }

        refreshDrawerSelection()

        onBackPressedDispatcher.addCallback(this) {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                finish()
            }
        }
    }

    private fun refreshDrawerSelection() {
        fun icon(drawableId: Int) = ContextCompat.getDrawable(this, drawableId)

        binding.navigationView.menu.findItem(R.id.nav_mode_face)?.icon =
            icon(if (analysisMode == AnalysisMode.FACIAL) R.drawable.ic_radio_checked else R.drawable.ic_radio_unchecked)
        binding.navigationView.menu.findItem(R.id.nav_mode_dental)?.icon =
            icon(if (analysisMode == AnalysisMode.DENTAL) R.drawable.ic_radio_checked else R.drawable.ic_radio_unchecked)
        binding.navigationView.menu.findItem(R.id.nav_provider_gemini)?.icon =
            icon(if (analysisProvider == AnalysisProvider.GEMINI) R.drawable.ic_radio_checked else R.drawable.ic_radio_unchecked)
        binding.navigationView.menu.findItem(R.id.nav_provider_openai)?.icon =
            icon(if (analysisProvider == AnalysisProvider.OPENAI) R.drawable.ic_radio_checked else R.drawable.ic_radio_unchecked)
    }

    private fun setupDrawerHeader() {
        if (binding.navigationView.headerCount <= 0) return

        val headerView = binding.navigationView.getHeaderView(0)
        val container = headerView.findViewById<View>(R.id.drawerHeaderContainer)
        container.setOnClickListener { handleDrawerHeaderTap() }
    }

    private fun handleDrawerHeaderTap() {
        adminTapCount += 1

        if (adminTapCount >= ADMIN_TAP_COUNT_REQUIRED) {
            adminTapCount = 0
            adminUnlocked = !adminUnlocked
            refreshAdminMenuVisibility()
            Toast.makeText(
                this,
                getString(if (adminUnlocked) R.string.admin_unlocked_toast else R.string.admin_hidden_toast),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun refreshAdminMenuVisibility() {
        binding.navigationView.menu.findItem(R.id.nav_section_admin)?.isVisible = adminUnlocked
        binding.navigationView.menu.findItem(R.id.nav_admin_history)?.isVisible = adminUnlocked
        binding.navigationView.menu.findItem(R.id.nav_admin_prompts)?.isVisible = adminUnlocked
    }

    private fun applyAnalysisMode(mode: AnalysisMode) {
        if (analysisMode == mode || isCaptureInProgress) {
            return
        }

        analysisMode = mode
        persistSelectedMode(mode)
        ensureDetectorForMode()
        binding.roiOverlay.setRoiOverride(null)
        clearCapturedImages()
        latestState = FaceCaptureUiState.initial(currentLensLabel(), analysisMode)
        configureModeUi()
        renderState(latestState)

        if (hasCameraPermission()) {
            startCamera()
        } else {
            updatePermissionUi(false)
            renderState(FaceCaptureUiState.permissionRequired(analysisMode))
        }
    }

    private fun applyAnalysisProvider(provider: AnalysisProvider) {
        if (analysisProvider == provider) {
            return
        }

        analysisProvider = provider
        persistSelectedProvider(provider)
        configureModeUi()
    }

    private fun configureModeUi() {
        binding.captureToolbar.title = getString(analysisMode.captureTitleRes)
        binding.captureToolbar.subtitle = analysisProvider.displayName
        binding.captureSubtitleText.text = getString(analysisMode.captureSubtitleRes)
        binding.captureCountValue.isVisible = analysisMode.requiredImages > 1
        binding.roiOverlay.setPreset(
            if (analysisMode == AnalysisMode.DENTAL) {
                RoiOverlayView.Preset.DENTAL
            } else {
                RoiOverlayView.Preset.FACE
            },
        )
        binding.detectionMetricLabel.setText(
            if (analysisMode == AnalysisMode.DENTAL) {
                R.string.dental_capture_detection_label
            } else {
                R.string.image_capture_face_count
            },
        )
    }

    private fun ensureDetectorForMode() {
        if (faceDetector == null) {
            faceDetector = FaceDetection.getClient(createFaceDetectorOptions())
        }
    }

    private fun loadSelectedProvider(): AnalysisProvider {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val storedId = prefs.getString(KEY_PROVIDER_ID, null)
        val migrated = prefs.getBoolean(KEY_PROVIDER_DEFAULT_MIGRATED, false)

        if (!migrated) {
            val shouldSwitchToOpenAi = storedId.isNullOrBlank() || storedId == AnalysisProvider.GEMINI.id
            if (shouldSwitchToOpenAi) {
                prefs.edit()
                    .putString(KEY_PROVIDER_ID, AnalysisProvider.OPENAI.id)
                    .putBoolean(KEY_PROVIDER_DEFAULT_MIGRATED, true)
                    .apply()
                return AnalysisProvider.OPENAI
            }

            prefs.edit()
                .putBoolean(KEY_PROVIDER_DEFAULT_MIGRATED, true)
                .apply()
        }

        val effectiveId = storedId ?: AnalysisProvider.OPENAI.id
        return AnalysisProvider.fromId(effectiveId)
    }

    private fun loadSelectedMode(): AnalysisMode {
        val storedId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_MODE_ID, AnalysisMode.FACIAL.id)
        return AnalysisMode.fromId(storedId)
    }

    private fun persistSelectedProvider(provider: AnalysisProvider) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_PROVIDER_ID, provider.id)
            .apply()
    }

    private fun persistSelectedMode(mode: AnalysisMode) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE_ID, mode.id)
            .apply()
    }

    private fun toggleCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        updateCameraSwitchLabel()
        if (hasCameraPermission()) {
            renderState(FaceCaptureUiState.initial(currentLensLabel(), analysisMode))
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
        val navigationViewLeftPadding = binding.navigationView.paddingLeft
        val navigationViewTopPadding = binding.navigationView.paddingTop
        val navigationViewRightPadding = binding.navigationView.paddingRight
        val navigationViewBottomPadding = binding.navigationView.paddingBottom
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

            binding.navigationView.updatePadding(
                left = navigationViewLeftPadding + systemBars.left,
                top = navigationViewTopPadding + systemBars.top,
                right = navigationViewRightPadding,
                bottom = navigationViewBottomPadding + systemBars.bottom,
            )

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
        if (binding.previewView.width == 0 || binding.previewView.height == 0) {
            binding.previewView.post { startCamera() }
            return
        }

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
        previewOutputTransformRef.set(null)
        val targetRotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
            .also { it.surfaceProvider = binding.previewView.surfaceProvider }

        val analyzer: ImageAnalysis.Analyzer = if (analysisMode == AnalysisMode.DENTAL) {
            val detector = faceDetector ?: run {
                renderState(FaceCaptureUiState.cameraError("Face detector is not ready.", analysisMode))
                return
            }
            DentalRecognitionAnalyzer(
                detector = detector,
                callbackExecutor = cameraExecutor,
                previewOutputTransformProvider = previewOutputTransformRef::get,
                onRoiRectUpdated = { rect ->
                    runOnUiThread { binding.roiOverlay.setRoiOverride(rect) }
                },
                onStateChanged = ::onCaptureStateChanged,
            )
        } else {
            val detector = faceDetector ?: run {
                renderState(FaceCaptureUiState.cameraError("Face detector is not ready.", analysisMode))
                return
            }
            FaceRecognitionAnalyzer(
                detector = detector,
                callbackExecutor = cameraExecutor,
                previewOutputTransformProvider = previewOutputTransformRef::get,
                onRoiRectUpdated = { rect ->
                    runOnUiThread { binding.roiOverlay.setRoiOverride(rect) }
                },
                onStateChanged = ::onCaptureStateChanged,
            )
        }
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

        val viewPort = ViewPort.Builder(
            Rational(binding.previewView.width, binding.previewView.height),
            targetRotation,
        )
            .setScaleType(ViewPort.FILL_CENTER)
            .build()

        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(analysis)
            .addUseCase(capture)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, useCaseGroup)
            binding.previewView.post { updatePreviewOutputTransform() }
            if (!isCaptureInProgress) {
                renderState(FaceCaptureUiState.initial(currentLensLabel(), analysisMode))
            }
        } catch (error: Exception) {
            val message = error.localizedMessage ?: "Unknown camera binding error"
            renderState(FaceCaptureUiState.cameraError(message, analysisMode))
        }
    }

    private fun onCaptureStateChanged(state: FaceCaptureUiState) {
        latestState = state
        if (!isCaptureInProgress) {
            renderState(state)
        }
    }

    private fun handlePrimaryAction() {
        if (capturedImageFiles.size >= analysisMode.requiredImages && capturedImageFiles.isNotEmpty()) {
            launchLeadDetails(
                imagePaths = capturedImageFiles.map(File::getAbsolutePath),
                roiSpecs = capturedRoiSpecs.toList(),
            )
        } else {
            captureCurrentImage()
        }
    }

    private fun snapshotNormalizedRoiSpec(): String? {
        val overlayWidth = binding.roiOverlay.width.toFloat()
        val overlayHeight = binding.roiOverlay.height.toFloat()
        if (overlayWidth <= 0f || overlayHeight <= 0f) {
            return null
        }

        val roiRect = binding.roiOverlay.getRoiRect()
        if (roiRect.isEmpty) {
            return null
        }

        val left = (roiRect.left / overlayWidth).coerceIn(0f, 1f)
        val top = (roiRect.top / overlayHeight).coerceIn(0f, 1f)
        val right = (roiRect.right / overlayWidth).coerceIn(0f, 1f)
        val bottom = (roiRect.bottom / overlayHeight).coerceIn(0f, 1f)

        val normalized = RectF(left, top, right, bottom)
        if (normalized.isEmpty || normalized.right <= normalized.left || normalized.bottom <= normalized.top) {
            return null
        }

        return String.format(
            Locale.US,
            "%.6f,%.6f,%.6f,%.6f",
            normalized.left,
            normalized.top,
            normalized.right,
            normalized.bottom,
        )
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
        renderState(FaceCaptureUiState.capturing(analysisMode))
        capture.targetRotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
        val roiSpec = snapshotNormalizedRoiSpec().orEmpty()

        val outputFile = File(cacheDir, "${analysisMode.id}-capture-${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    isCaptureInProgress = false
                    capturedImageFiles += outputFile
                    capturedRoiSpecs += roiSpec

                    if (capturedImageFiles.size >= analysisMode.requiredImages) {
                        renderState(latestState.copy(errorMessage = null))
                        launchLeadDetails(
                            imagePaths = capturedImageFiles.map(File::getAbsolutePath),
                            roiSpecs = capturedRoiSpecs.toList(),
                        )
                    } else {
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

    private fun launchLeadDetails(imagePaths: List<String>, roiSpecs: List<String>) {
        startActivity(LeadDetailsActivity.newIntent(this, analysisMode, analysisProvider, imagePaths, roiSpecs))
    }

    private fun clearCapturedImages() {
        capturedImageFiles.forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        capturedImageFiles.clear()
        capturedRoiSpecs.clear()
        renderState(latestState.copy(errorMessage = null))
    }

    private fun renderState(state: FaceCaptureUiState) {
        runOnUiThread {
            binding.statusText.text = statusTextFor(state)
            binding.guidanceText.text = guidanceTextFor(state)
            binding.faceCountValue.text = if (analysisMode == AnalysisMode.DENTAL) {
                getString(R.string.capture_signal_value, state.faceCount)
            } else {
                state.faceCount.toString()
            }
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
            val primaryActionLabel = primaryActionText()
            binding.analyzeButton.contentDescription = primaryActionLabel
            setTooltipText(binding.analyzeButton, primaryActionLabel)
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
            analysisMode.requiredImages == 1 && capturedImageFiles.isNotEmpty() ->
                getString(R.string.continue_to_details)
            analysisMode.requiredImages > 1 && capturedImageFiles.size < analysisMode.requiredImages ->
                getString(
                    R.string.capture_image_step,
                    capturedImageFiles.size + 1,
                    analysisMode.requiredImages,
                )
            analysisMode.requiredImages > 1 && capturedImageFiles.size >= analysisMode.requiredImages ->
                getString(R.string.continue_to_details)
            analysisMode == AnalysisMode.DENTAL -> getString(R.string.analyze_dental_images)
            else -> getString(R.string.capture_face_image)
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
        val label = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            getString(R.string.switch_to_back_camera)
        } else {
            getString(R.string.switch_to_front_camera)
        }
        binding.switchCameraButton.contentDescription = label
        setTooltipText(binding.switchCameraButton, label)
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
        private const val PREFS_NAME = "analysis_preferences"
        private const val KEY_PROVIDER_ID = "selected_provider_id"
        private const val KEY_PROVIDER_DEFAULT_MIGRATED = "provider_default_migrated_openai"
        private const val KEY_MODE_ID = "selected_mode_id"
        private const val ADMIN_TAP_COUNT_REQUIRED = 5
        private const val EXTRA_ANALYSIS_MODE = "image_analysis_mode"
        private const val EXTRA_ANALYSIS_PROVIDER = "image_analysis_provider"

        fun newIntent(context: Context, mode: AnalysisMode, provider: AnalysisProvider): Intent {
            return Intent(context, ImageAnalysisActivity::class.java)
                .putExtra(EXTRA_ANALYSIS_MODE, mode.id)
                .putExtra(EXTRA_ANALYSIS_PROVIDER, provider.id)
        }
    }
}
