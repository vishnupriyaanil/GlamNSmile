package com.q8ind.glamnsmile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.q8ind.glamnsmile.databinding.ActivityLeadDetailsBinding

class LeadDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLeadDetailsBinding
    private val leadRepository = LeadRepository()
    private lateinit var analysisMode: AnalysisMode
    private lateinit var analysisProvider: AnalysisProvider
    private lateinit var imagePaths: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityLeadDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        analysisMode = AnalysisMode.fromId(intent.getStringExtra(EXTRA_ANALYSIS_MODE))
        analysisProvider = AnalysisProvider.fromId(intent.getStringExtra(EXTRA_ANALYSIS_PROVIDER))
        imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS)?.filter(String::isNotBlank).orEmpty()

        binding.detailsToolbar.setNavigationOnClickListener { finish() }
        binding.modeSummaryText.text = getString(
            R.string.lead_mode_summary,
            analysisMode.displayLabel,
            imagePaths.size,
            analysisProvider.displayName,
        )
        binding.saveButton.setOnClickListener { saveLeadAndContinue() }
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

    private fun saveLeadAndContinue() {
        val name = binding.nameInput.text?.toString().orEmpty().trim()
        val phoneNumber = binding.phoneInput.text?.toString().orEmpty().trim()
        val email = binding.emailInput.text?.toString().orEmpty().trim()

        val validationMessage = validate(name, phoneNumber, email)
        if (validationMessage != null) {
            binding.errorText.isVisible = true
            binding.errorText.text = validationMessage
            return
        }

        binding.errorText.isVisible = false
        binding.statusText.isVisible = true
        binding.statusText.text = getString(R.string.saving_details)
        binding.progressIndicator.isVisible = true
        binding.saveButton.isEnabled = false

        leadRepository.upsertLead(
            name = name,
            phoneNumber = phoneNumber,
            email = email,
            analysisMode = analysisMode,
            analysisProvider = analysisProvider,
            imageCount = imagePaths.size,
        ) { result ->
            runOnUiThread {
                binding.progressIndicator.isVisible = false
                binding.saveButton.isEnabled = true
                result.fold(
                    onSuccess = {
                        startActivity(AnalysisResultActivity.newIntent(this, analysisMode, analysisProvider, imagePaths))
                        finish()
                    },
                    onFailure = { error ->
                        binding.statusText.isVisible = false
                        binding.errorText.isVisible = true
                        binding.errorText.text = error.localizedMessage ?: getString(R.string.details_save_failed)
                    },
                )
            }
        }
    }

    private fun validate(name: String, phoneNumber: String, email: String): String? {
        if (name.isBlank()) {
            return getString(R.string.details_name_required)
        }
        val normalizedPhone = leadRepository.normalizePhone(phoneNumber)
        if (phoneNumber.isBlank()) {
            return getString(R.string.details_phone_required)
        }
        if (normalizedPhone.length < 7) {
            return getString(R.string.details_phone_invalid)
        }
        if (email.isBlank()) {
            return getString(R.string.details_email_required)
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            return getString(R.string.details_email_invalid)
        }
        return null
    }

    companion object {
        private const val EXTRA_ANALYSIS_MODE = "lead_details_mode"
        private const val EXTRA_ANALYSIS_PROVIDER = "lead_details_provider"
        private const val EXTRA_IMAGE_PATHS = "lead_details_image_paths"

        fun newIntent(
            context: Context,
            mode: AnalysisMode,
            provider: AnalysisProvider,
            imagePaths: List<String>,
        ): Intent {
            return Intent(context, LeadDetailsActivity::class.java)
                .putExtra(EXTRA_ANALYSIS_MODE, mode.id)
                .putExtra(EXTRA_ANALYSIS_PROVIDER, provider.id)
                .putStringArrayListExtra(EXTRA_IMAGE_PATHS, ArrayList(imagePaths))
        }
    }
}
