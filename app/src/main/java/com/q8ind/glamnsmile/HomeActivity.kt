package com.q8ind.glamnsmile

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.q8ind.glamnsmile.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var selectedProvider: AnalysisProvider
    private var adminTapCount: Int = 0
    private var lastAdminTapMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()
        selectedProvider = loadSelectedProvider()
        initializeProviderSelection()

        binding.imageButton.setOnClickListener {
            startActivity(ImageAnalysisActivity.newIntent(this, AnalysisMode.FACIAL, selectedProvider))
        }

        binding.dentalButton.setOnClickListener {
            startActivity(ImageAnalysisActivity.newIntent(this, AnalysisMode.DENTAL, selectedProvider))
        }

        binding.homeToolbar.setOnClickListener {
            handleAdminTap()
        }
    }

    private fun handleAdminTap() {
        val now = System.currentTimeMillis()
        adminTapCount = if (now - lastAdminTapMs > ADMIN_TAP_WINDOW_MS) {
            1
        } else {
            adminTapCount + 1
        }
        lastAdminTapMs = now

        if (adminTapCount >= ADMIN_TAP_THRESHOLD) {
            adminTapCount = 0
            lastAdminTapMs = 0L
            showAdminDialog()
        }
    }

    private fun showAdminDialog() {
        val options = arrayOf(getString(R.string.view_stored_data))
        val adapter = ArrayAdapter(this, R.layout.item_admin_dialog_option, options)

        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_GlamNSmile_AdminDialog)
            .setTitle(R.string.admin_options_title)
            .setAdapter(adapter) { _, which ->
                if (which == 0) {
                    startActivity(Intent(this, AdminDataActivity::class.java))
                }
            }
            .show()
    }

    private fun initializeProviderSelection() {
        val checkedButtonId = if (selectedProvider == AnalysisProvider.OPENAI) {
            R.id.providerOpenAiButton
        } else {
            R.id.providerGeminiButton
        }
        binding.providerToggleGroup.check(checkedButtonId)
        binding.providerToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            selectedProvider = if (checkedId == R.id.providerOpenAiButton) {
                AnalysisProvider.OPENAI
            } else {
                AnalysisProvider.GEMINI
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_PROVIDER_ID, selectedProvider.id)
                .apply()
        }
    }

    private fun loadSelectedProvider(): AnalysisProvider {
        val storedId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_PROVIDER_ID, AnalysisProvider.GEMINI.id)
        return AnalysisProvider.fromId(storedId)
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

    companion object {
        private const val PREFS_NAME = "analysis_preferences"
        private const val KEY_PROVIDER_ID = "selected_provider_id"
        private const val ADMIN_TAP_THRESHOLD = 5
        private const val ADMIN_TAP_WINDOW_MS = 2000L
    }
}
