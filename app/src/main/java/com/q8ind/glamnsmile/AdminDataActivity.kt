package com.q8ind.glamnsmile

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.q8ind.glamnsmile.databinding.ActivityAdminDataBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminDataActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminDataBinding
    private val leadRepository = LeadRepository()
    private val dateFormatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAdminDataBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.adminToolbar.setNavigationOnClickListener { finish() }
        loadLeads()
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

    private fun loadLeads() {
        binding.progressIndicator.isVisible = true
        binding.statusText.isVisible = true
        binding.statusText.text = getString(R.string.admin_loading)
        binding.emptyText.isVisible = false
        binding.scrollView.isVisible = false
        binding.listContainer.removeAllViews()

        leadRepository.loadLeads { result ->
            runOnUiThread {
                binding.progressIndicator.isVisible = false
                result.fold(
                    onSuccess = { leads ->
                        if (leads.isEmpty()) {
                            binding.statusText.isVisible = false
                            binding.emptyText.isVisible = true
                        } else {
                            binding.statusText.isVisible = true
                            binding.statusText.text = getString(R.string.admin_title)
                            binding.scrollView.isVisible = true
                            leads.forEach(::addLeadCard)
                        }
                    },
                    onFailure = { error ->
                        binding.statusText.isVisible = true
                        binding.statusText.text =
                            error.localizedMessage ?: getString(R.string.admin_load_failed)
                        binding.emptyText.isVisible = true
                        binding.emptyText.text = getString(R.string.admin_load_failed)
                    },
                )
            }
        }
    }

    private fun addLeadCard(lead: AnalysisLead) {
        val itemView = LayoutInflater.from(this).inflate(
            R.layout.item_admin_lead,
            binding.listContainer,
            false,
        )
        itemView.findViewById<TextView>(R.id.nameText).text = lead.name.ifBlank { lead.phoneNumber }
        itemView.findViewById<TextView>(R.id.phoneText).text = lead.phoneNumber
        itemView.findViewById<TextView>(R.id.emailText).text = lead.email
        itemView.findViewById<TextView>(R.id.faceCountText).text = getString(
            R.string.admin_face_count_format,
            lead.faceCount,
        )
        itemView.findViewById<TextView>(R.id.dentalCountText).text = getString(
            R.string.admin_dental_count_format,
            lead.dentalCount,
        )
        itemView.findViewById<TextView>(R.id.lastFaceText).text = getString(
            R.string.admin_last_face_format,
            formatTimestamp(lead.lastFaceAt),
        )
        itemView.findViewById<TextView>(R.id.lastDentalText).text = getString(
            R.string.admin_last_dental_format,
            formatTimestamp(lead.lastDentalAt),
        )
        itemView.findViewById<TextView>(R.id.updatedText).text = getString(
            R.string.admin_updated_format,
            formatTimestamp(lead.updatedAt),
        )
        itemView.setOnClickListener {
            startActivity(AdminHistoryActivity.newIntent(this, lead))
        }
        binding.listContainer.addView(itemView)
    }

    private fun formatTimestamp(value: Long): String {
        return if (value > 0L) {
            dateFormatter.format(Date(value))
        } else {
            getString(R.string.value_not_available)
        }
    }
}
