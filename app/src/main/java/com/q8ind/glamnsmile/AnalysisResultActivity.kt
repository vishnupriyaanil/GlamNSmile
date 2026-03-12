package com.q8ind.glamnsmile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.q8ind.glamnsmile.databinding.ActivityAnalysisResultBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class AnalysisResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalysisResultBinding
    private var analysisJob: Job? = null
    private var timingJob: Job? = null
    private val responseBuffer = StringBuilder()
    private val preparedImageFiles = mutableListOf<File>()
    private var generatedOutputFile: File? = null
    private var analysisStartedAtMs: Long = 0L
    private lateinit var analysisMode: AnalysisMode
    private lateinit var analysisProvider: AnalysisProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAnalysisResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.resultToolbar.setNavigationOnClickListener { finish() }

        analysisMode = AnalysisMode.fromId(intent.getStringExtra(EXTRA_ANALYSIS_MODE))
        analysisProvider = AnalysisProvider.fromId(intent.getStringExtra(EXTRA_ANALYSIS_PROVIDER))
        binding.generatedImageTitleText.text = getString(R.string.generated_image_title)
        binding.generatedImageCard.isVisible = false

        val imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS)
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(EXTRA_IMAGE_PATH)?.let(::listOf)

        if (imagePaths.isNullOrEmpty()) {
            renderError("Missing image for Gemini analysis.")
            return
        }

        val existingImageFiles = imagePaths
            .map(::File)
            .filter(File::exists)

        if (existingImageFiles.isEmpty()) {
            renderError("Captured image file was not found.")
            return
        }

        val normalizedImageFiles = existingImageFiles.map(::prepareImageForDisplayAndAnalysis)
        preparedImageFiles.clear()
        preparedImageFiles.addAll(normalizedImageFiles)
        configureResultUi(preparedImageFiles.size)
        val previewFile = preparedImageFiles.first()
        binding.previewImage.setImageBitmap(BitmapFactory.decodeFile(previewFile.absolutePath))
        binding.previewImage.setOnClickListener {
            startActivity(
                ImagePreviewActivity.newIntent(
                    this,
                    previewFile.absolutePath,
                    getString(R.string.captured_reference_title),
                ),
            )
        }
        binding.generatedImageView.setOnClickListener {
            val generatedFile = generatedOutputFile
            if (generatedFile != null && generatedFile.exists()) {
                startActivity(
                    ImagePreviewActivity.newIntent(
                        this,
                        generatedFile.absolutePath,
                        getString(R.string.generated_image_title),
                    ),
                )
            }
        }
        startAnalysis(preparedImageFiles)
    }

    override fun onDestroy() {
        analysisJob?.cancel()
        timingJob?.cancel()
        preparedImageFiles.forEach { preparedFile ->
            if (preparedFile.exists()) {
                preparedFile.delete()
            }
        }
        generatedOutputFile?.takeIf(File::exists)?.delete()
        super.onDestroy()
    }

    private fun applyWindowInsets() {
        val bottomSafeSpacing = resources.getDimensionPixelSize(R.dimen.bottom_safe_spacing)
        val leftPadding = binding.contentContainer.paddingLeft
        val topPadding = binding.contentContainer.paddingTop
        val rightPadding = binding.contentContainer.paddingRight
        val bottomPadding = binding.contentContainer.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.contentContainer.updatePadding(
                left = leftPadding + systemBars.left,
                top = topPadding + systemBars.top,
                right = rightPadding + systemBars.right,
                bottom = bottomPadding + systemBars.bottom + bottomSafeSpacing,
            )

            insets
        }

        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun configureResultUi(imageCount: Int) {
        binding.resultToolbar.title = getString(
            if (analysisMode == AnalysisMode.FACIAL) {
                R.string.analysis_result_title_face
            } else {
                R.string.analysis_result_title_dental
            },
            analysisProvider.displayName,
        )
        binding.resultToolbar.subtitle = getString(
            if (analysisMode == AnalysisMode.FACIAL) {
                R.string.analysis_result_subtitle_face
            } else {
                R.string.analysis_result_subtitle_dental
            },
            analysisProvider.displayName,
            imageCount,
        )
        binding.sectionTitleText.text = getString(
            if (analysisMode == AnalysisMode.FACIAL) {
                R.string.analysis_section_title_notes
            } else {
                R.string.analysis_section_title_response
            },
            analysisProvider.displayName,
        )
    }

    private fun startAnalysis(imageFiles: List<File>) {
        val apiKey = apiKeyForSelectedProvider()
        if (apiKey.isBlank() || apiKey == "<SECRET>") {
            renderError(missingKeyMessage())
            return
        }

        generatedOutputFile?.takeIf(File::exists)?.delete()
        generatedOutputFile = null
        binding.generatedImageCard.isVisible = false
        binding.progressIndicator.isVisible = true
        binding.placeholderText.isVisible = true
        binding.timingText.isVisible = true
        responseBuffer.setLength(0)
        binding.resultText.text = ""
        startTimingFeedback()

        if (analysisMode == AnalysisMode.FACIAL) {
            binding.statusText.text = getString(
                R.string.analysis_generating_image_provider,
                analysisProvider.displayName,
            )
            binding.placeholderText.text = getString(R.string.analysis_image_waiting_placeholder)
            startFacialImageGeneration(apiKey, imageFiles.first())
        } else {
            binding.statusText.text = getString(
                R.string.analysis_connecting_provider,
                analysisProvider.displayName,
            )
            binding.placeholderText.text = getString(R.string.analysis_waiting_placeholder)
            startStreamingTextAnalysis(apiKey, imageFiles)
        }
    }

    private fun apiKeyForSelectedProvider(): String {
        return when (analysisProvider) {
            AnalysisProvider.GEMINI -> BuildConfig.GEMINI_API_KEY.trim()
            AnalysisProvider.OPENAI -> BuildConfig.OPENAI_API_KEY.trim()
        }
    }

    private fun missingKeyMessage(): String {
        return when (analysisProvider) {
            AnalysisProvider.GEMINI -> "Gemini API key is missing. Add GEMINI_API_KEY to local.properties."
            AnalysisProvider.OPENAI -> getString(R.string.analysis_openai_key_missing)
        }
    }

    private fun startFacialImageGeneration(apiKey: String, sourceImage: File) {
        analysisJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = when (analysisProvider) {
                    AnalysisProvider.GEMINI -> {
                        val geminiResult = NanoBananaImageClient(apiKey).generateFaceInfographic(
                            referenceImage = sourceImage,
                            prompt = analysisMode.prompt,
                            outputDirectory = cacheDir,
                        )
                        OpenAiImageClient.Result(
                            imageFile = geminiResult.imageFile,
                            textResponse = geminiResult.textResponse,
                        )
                    }

                    AnalysisProvider.OPENAI -> OpenAiImageClient(apiKey).generateFaceInfographic(
                        referenceImage = sourceImage,
                        prompt = analysisMode.prompt,
                        outputDirectory = cacheDir,
                    )
                }

                generatedOutputFile?.takeIf(File::exists)?.delete()
                generatedOutputFile = result.imageFile

                runOnUiThread {
                    binding.generatedImageCard.isVisible = true
                    binding.generatedImageView.setImageBitmap(
                        BitmapFactory.decodeFile(result.imageFile.absolutePath),
                    )
                    binding.progressIndicator.isVisible = false
                    binding.statusText.text = getString(R.string.analysis_complete)
                    completeTimingFeedback()
                    if (result.textResponse.isBlank()) {
                        binding.placeholderText.isVisible = true
                        binding.placeholderText.text = getString(R.string.analysis_image_no_text_returned)
                        binding.resultText.text = ""
                    } else {
                        binding.placeholderText.isVisible = false
                        binding.resultText.text = GeminiResponseFormatter.format(result.textResponse)
                    }
                    binding.pageScroll.post {
                        binding.pageScroll.smoothScrollTo(0, binding.generatedImageCard.top)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runOnUiThread {
                    renderError(error.localizedMessage ?: getString(R.string.analysis_no_image_returned))
                }
            }
        }
    }

    private fun startStreamingTextAnalysis(apiKey: String, imageFiles: List<File>) {
        analysisJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val handleChunk: (String) -> Unit = { chunk ->
                    if (chunk.isNotBlank()) {
                        runOnUiThread {
                            if (responseBuffer.isEmpty()) {
                                binding.placeholderText.isVisible = false
                            }
                            responseBuffer.append(chunk)
                            binding.resultText.text = GeminiResponseFormatter.format(responseBuffer.toString())
                            binding.statusText.text = getString(
                                R.string.analysis_streaming_provider,
                                analysisProvider.displayName,
                            )
                            binding.progressIndicator.isVisible = true
                            binding.pageScroll.post {
                                binding.pageScroll.fullScroll(View.FOCUS_DOWN)
                            }
                        }
                    }
                }

                when (analysisProvider) {
                    AnalysisProvider.GEMINI -> GeminiStreamingClient(apiKey).streamImageAnalysis(
                        imageFiles = imageFiles,
                        prompt = analysisMode.prompt,
                        onChunk = handleChunk,
                    )

                    AnalysisProvider.OPENAI -> OpenAiStreamingClient(apiKey).streamImageAnalysis(
                        imageFiles = imageFiles,
                        prompt = analysisMode.prompt,
                        onChunk = handleChunk,
                    )
                }

                runOnUiThread {
                    binding.progressIndicator.isVisible = false
                    completeTimingFeedback()
                    if (responseBuffer.isEmpty()) {
                        binding.placeholderText.isVisible = true
                        binding.placeholderText.text = getString(R.string.analysis_no_text_returned)
                        binding.statusText.text = getString(R.string.analysis_complete_no_text)
                    } else {
                        binding.statusText.text = getString(R.string.analysis_complete)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runOnUiThread {
                    renderError(error.localizedMessage ?: "Gemini analysis failed.")
                }
            }
        }
    }

    private fun renderError(message: String) {
        binding.progressIndicator.isVisible = false
        binding.generatedImageCard.isVisible = false
        binding.statusText.text = getString(R.string.analysis_failed)
        binding.placeholderText.isVisible = true
        binding.placeholderText.text = message
        failTimingFeedback()
    }

    private fun startTimingFeedback() {
        timingJob?.cancel()
        analysisStartedAtMs = System.currentTimeMillis()
        updateTimingInProgress(0L)
        timingJob = lifecycleScope.launch {
            while (true) {
                delay(1000)
                val elapsedSeconds = (System.currentTimeMillis() - analysisStartedAtMs) / 1000
                updateTimingInProgress(elapsedSeconds)
            }
        }
    }

    private fun updateTimingInProgress(elapsedSeconds: Long) {
        binding.timingText.text = getString(
            R.string.analysis_timing_in_progress,
            formatElapsedDuration(elapsedSeconds),
            analysisMode.estimatedMinSeconds,
            analysisMode.estimatedMaxSeconds,
        )
    }

    private fun completeTimingFeedback() {
        timingJob?.cancel()
        if (analysisStartedAtMs == 0L) {
            binding.timingText.text = ""
            return
        }
        val elapsedSeconds = (System.currentTimeMillis() - analysisStartedAtMs) / 1000
        binding.timingText.text = getString(
            R.string.analysis_timing_complete,
            formatElapsedDuration(elapsedSeconds),
        )
    }

    private fun failTimingFeedback() {
        timingJob?.cancel()
        if (analysisStartedAtMs == 0L) {
            binding.timingText.text = ""
            return
        }
        val elapsedSeconds = (System.currentTimeMillis() - analysisStartedAtMs) / 1000
        binding.timingText.text = getString(
            R.string.analysis_timing_failed,
            formatElapsedDuration(elapsedSeconds),
        )
    }

    private fun formatElapsedDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun prepareImageForDisplayAndAnalysis(sourceFile: File): File {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(sourceFile.absolutePath, options)

        options.inSampleSize = calculateInSampleSize(
            width = options.outWidth,
            height = options.outHeight,
            maxDimension = 1600,
        )
        options.inJustDecodeBounds = false

        val decodedBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, options)
            ?: return sourceFile
        val orientation = ExifInterface(sourceFile.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val transformedBitmap = decodedBitmap.transformForExifOrientation(orientation)
        val outputFile = File(cacheDir, "prepared-${sourceFile.name}")

        FileOutputStream(outputFile).use { outputStream ->
            transformedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)
        }

        if (transformedBitmap !== decodedBitmap) {
            decodedBitmap.recycle()
        }
        transformedBitmap.recycle()
        return outputFile
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        var currentWidth = width
        var currentHeight = height

        while (currentWidth > maxDimension || currentHeight > maxDimension) {
            currentWidth /= 2
            currentHeight /= 2
            sampleSize *= 2
        }

        return sampleSize.coerceAtLeast(1)
    }

    private fun Bitmap.transformForExifOrientation(orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }
            }
        }

        if (matrix.isIdentity) {
            return this
        }

        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    companion object {
        private const val EXTRA_ANALYSIS_MODE = "analysis_result_mode"
        private const val EXTRA_ANALYSIS_PROVIDER = "analysis_result_provider"
        private const val EXTRA_IMAGE_PATH = "analysis_result_image_path"
        private const val EXTRA_IMAGE_PATHS = "analysis_result_image_paths"

        fun newIntent(
            context: Context,
            mode: AnalysisMode,
            provider: AnalysisProvider,
            imagePaths: List<String>,
        ): Intent {
            return Intent(context, AnalysisResultActivity::class.java)
                .putExtra(EXTRA_ANALYSIS_MODE, mode.id)
                .putExtra(EXTRA_ANALYSIS_PROVIDER, provider.id)
                .putStringArrayListExtra(EXTRA_IMAGE_PATHS, ArrayList(imagePaths))
        }
    }
}
