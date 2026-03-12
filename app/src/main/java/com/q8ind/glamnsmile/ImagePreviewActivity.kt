package com.q8ind.glamnsmile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.q8ind.glamnsmile.databinding.ActivityImagePreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImagePreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.previewToolbar.setNavigationOnClickListener { finish() }
        binding.previewToolbar.title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH).orEmpty()
        val imageFile = imagePath.takeIf(String::isNotBlank)?.let(::File)
        if (imageFile == null || !imageFile.exists()) {
            finish()
            return
        }

        binding.loadingIndicator.isVisible = true
        lifecycleScope.launch(Dispatchers.IO) {
            val targetWidth = resources.displayMetrics.widthPixels * 2
            val targetHeight = resources.displayMetrics.heightPixels * 2
            val bitmap = decodeScaledBitmap(imageFile, targetWidth, targetHeight)

            withContext(Dispatchers.Main) {
                binding.loadingIndicator.isVisible = false
                if (bitmap == null) {
                    finish()
                } else {
                    binding.zoomImageView.setImageBitmap(bitmap)
                }
            }
        }
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

    private fun decodeScaledBitmap(imageFile: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(width, height, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(imageFile.absolutePath, options)
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    companion object {
        private const val EXTRA_IMAGE_PATH = "image_preview_path"
        private const val EXTRA_TITLE = "image_preview_title"

        fun newIntent(context: Context, imagePath: String, title: String): Intent {
            return Intent(context, ImagePreviewActivity::class.java)
                .putExtra(EXTRA_IMAGE_PATH, imagePath)
                .putExtra(EXTRA_TITLE, title)
        }
    }
}

