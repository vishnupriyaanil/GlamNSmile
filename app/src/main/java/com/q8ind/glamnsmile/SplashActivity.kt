package com.q8ind.glamnsmile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.q8ind.glamnsmile.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val promptRepository = PromptRepository()
    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        binding.retryButton.setOnClickListener { loadPrompts() }
        binding.continueButton.setOnClickListener { navigateToCamera() }
        loadPrompts()
    }

    private fun applyWindowInsets() {
        val basePaddingLeft = binding.root.paddingLeft
        val basePaddingTop = binding.root.paddingTop
        val basePaddingRight = binding.root.paddingRight
        val basePaddingBottom = binding.root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = basePaddingLeft + systemBars.left,
                top = basePaddingTop + systemBars.top,
                right = basePaddingRight + systemBars.right,
                bottom = basePaddingBottom + systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun loadPrompts() {
        val cached = PromptStore.getAllPrompts(this)

        binding.progressIndicator.isVisible = true
        binding.retryButton.isVisible = false
        binding.continueButton.isVisible = false
        binding.statusText.text = getString(R.string.splash_loading)

        promptRepository.fetchAllPrompts { result ->
            if (isFinishing || isDestroyed) return@fetchAllPrompts

            runOnUiThread {
                val remotePrompts = result.getOrElse { emptyMap() }
                val merged = cached.toMutableMap()
                remotePrompts.forEach { (id, prompt) ->
                    if (prompt.isNotBlank()) {
                        merged[id] = prompt
                    }
                }
                if (merged != cached) {
                    PromptStore.setPrompts(this, merged)
                }

                val missing = PromptStore.missingRequiredPromptIds(this)
                if (missing.isEmpty()) {
                    navigateToCamera()
                } else {
                    binding.progressIndicator.isVisible = false
                    binding.retryButton.isVisible = true
                    binding.continueButton.isVisible = true
                    val message = result.exceptionOrNull()?.localizedMessage
                        ?: "No prompt templates found in Firestore."
                    binding.statusText.text = getString(
                        R.string.splash_load_failed,
                        message,
                        missing.joinToString(", "),
                    )
                }
            }
        }
    }

    private fun navigateToCamera() {
        if (hasNavigated) return
        hasNavigated = true
        startActivity(Intent(this, ImageAnalysisActivity::class.java))
        finish()
    }
}
