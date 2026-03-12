package com.q8ind.glamnsmile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.q8ind.glamnsmile.databinding.ActivityAdminHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminHistoryBinding
    private val leadRepository = LeadRepository()
    private val dateFormatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)

    private lateinit var normalizedPhone: String
    private lateinit var displayName: String
    private lateinit var phoneNumber: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAdminHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        normalizedPhone = intent.getStringExtra(EXTRA_NORMALIZED_PHONE).orEmpty()
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
        phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty()

        binding.historyToolbar.setNavigationOnClickListener { finish() }
        binding.historyToolbar.title = getString(R.string.admin_history_title)
        binding.historyToolbar.subtitle = getString(
            R.string.admin_history_subtitle,
            displayName.ifBlank { phoneNumber.ifBlank { normalizedPhone } },
            phoneNumber.ifBlank { normalizedPhone },
        )

        loadHistory()
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

    private fun loadHistory() {
        binding.progressIndicator.isVisible = true
        binding.statusText.isVisible = true
        binding.statusText.text = getString(R.string.admin_history_loading)
        binding.emptyText.isVisible = false
        binding.scrollView.isVisible = false
        binding.listContainer.removeAllViews()

        leadRepository.loadHistory(normalizedPhone) { result ->
            runOnUiThread {
                binding.progressIndicator.isVisible = false
                result.fold(
                    onSuccess = { entries ->
                        if (entries.isEmpty()) {
                            binding.statusText.isVisible = false
                            binding.emptyText.isVisible = true
                            binding.emptyText.text = getString(R.string.admin_history_empty)
                        } else {
                            binding.statusText.isVisible = false
                            binding.scrollView.isVisible = true
                            entries.forEach(::addHistoryCard)
                        }
                    },
                    onFailure = { error ->
                        binding.statusText.isVisible = true
                        binding.statusText.text =
                            error.localizedMessage ?: getString(R.string.admin_history_load_failed)
                        binding.emptyText.isVisible = true
                        binding.emptyText.text = getString(R.string.admin_history_load_failed)
                    },
                )
            }
        }
    }

    private fun addHistoryCard(entry: AnalysisHistoryEntry) {
        val itemView = LayoutInflater.from(this).inflate(
            R.layout.item_admin_history_entry,
            binding.listContainer,
            false,
        )
        itemView.findViewById<TextView>(R.id.modeText).text = getString(
            R.string.admin_history_mode_format,
            entry.analysisModeLabel,
        )
        itemView.findViewById<TextView>(R.id.providerText).text = getString(
            R.string.admin_history_provider_format,
            entry.providerLabel,
        )
        itemView.findViewById<TextView>(R.id.imagesText).text = getString(
            R.string.admin_history_images_format,
            entry.imageCount,
        )
        itemView.findViewById<TextView>(R.id.timeText).text = getString(
            R.string.admin_history_time_format,
            formatTimestamp(entry.createdAt),
        )
        binding.listContainer.addView(itemView)
    }

    private fun formatTimestamp(value: Long): String {
        return if (value > 0L) {
            dateFormatter.format(Date(value))
        } else {
            getString(R.string.value_not_available)
        }
    }

    companion object {
        private const val EXTRA_NORMALIZED_PHONE = "admin_history_normalized_phone"
        private const val EXTRA_DISPLAY_NAME = "admin_history_display_name"
        private const val EXTRA_PHONE_NUMBER = "admin_history_phone_number"

        fun newIntent(context: Context, lead: AnalysisLead): Intent {
            return Intent(context, AdminHistoryActivity::class.java)
                .putExtra(EXTRA_NORMALIZED_PHONE, lead.normalizedPhone)
                .putExtra(EXTRA_DISPLAY_NAME, lead.name)
                .putExtra(EXTRA_PHONE_NUMBER, lead.phoneNumber)
        }
    }
}

