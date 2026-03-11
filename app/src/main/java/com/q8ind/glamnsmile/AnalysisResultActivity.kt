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
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class AnalysisResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalysisResultBinding
    private var analysisJob: Job? = null
    private val responseBuffer = StringBuilder()
    private val preparedImageFiles = mutableListOf<File>()
    private lateinit var analysisMode: AnalysisMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAnalysisResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.backButton.setOnClickListener { finish() }

        analysisMode = AnalysisMode.fromId(intent.getStringExtra(EXTRA_ANALYSIS_MODE))
        binding.headerTitleText.text = getString(analysisMode.resultTitleRes)
        binding.headerSubtitleText.text = getString(
            analysisMode.resultSubtitleRes,
            intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS)?.size ?: 1,
        )
        binding.sectionTitleText.text = getString(analysisMode.resultSectionTitleRes)

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
        binding.headerSubtitleText.text = getString(analysisMode.resultSubtitleRes, preparedImageFiles.size)
        binding.previewImage.setImageBitmap(BitmapFactory.decodeFile(preparedImageFiles.first().absolutePath))
        startStreamingAnalysis(preparedImageFiles)
    }

    override fun onDestroy() {
        analysisJob?.cancel()
        preparedImageFiles.forEach { preparedFile ->
            if (preparedFile.exists()) {
                preparedFile.delete()
            }
        }
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

    private fun startStreamingAnalysis(imageFiles: List<File>) {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isBlank() || apiKey == "<SECRET>") {
            renderError("Gemini API key is missing. Add GEMINI_API_KEY to local.properties.")
            return
        }

        binding.statusText.text = getString(R.string.analysis_connecting)
        binding.progressIndicator.isVisible = true
        binding.placeholderText.isVisible = true
        binding.placeholderText.text = getString(R.string.analysis_waiting_placeholder)
        responseBuffer.setLength(0)
        binding.resultText.text = ""

        analysisJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                GeminiStreamingClient(apiKey).streamImageAnalysis(
                    imageFiles = imageFiles,
                    prompt = analysisMode.prompt,
                ) { chunk ->
                    if (chunk.isBlank()) {
                        return@streamImageAnalysis
                    }

                    runOnUiThread {
                        if (responseBuffer.isEmpty()) {
                            binding.placeholderText.isVisible = false
                        }
                        responseBuffer.append(chunk)
                        binding.resultText.text = GeminiResponseFormatter.format(responseBuffer.toString())
                        binding.statusText.text = getString(R.string.analysis_streaming)
                        binding.progressIndicator.isVisible = true
                        binding.pageScroll.post {
                            binding.pageScroll.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }

                runOnUiThread {
                    binding.progressIndicator.isVisible = false
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
        binding.statusText.text = getString(R.string.analysis_failed)
        binding.placeholderText.isVisible = true
        binding.placeholderText.text = message
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
        private const val EXTRA_IMAGE_PATH = "analysis_result_image_path"
        private const val EXTRA_IMAGE_PATHS = "analysis_result_image_paths"

        fun newIntent(context: Context, mode: AnalysisMode, imagePaths: List<String>): Intent {
            return Intent(context, AnalysisResultActivity::class.java)
                .putExtra(EXTRA_ANALYSIS_MODE, mode.id)
                .putStringArrayListExtra(EXTRA_IMAGE_PATHS, ArrayList(imagePaths))
        }
    }
}
