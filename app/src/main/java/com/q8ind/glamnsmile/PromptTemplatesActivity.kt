package com.q8ind.glamnsmile

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.q8ind.glamnsmile.databinding.ActivityPromptTemplatesBinding

class PromptTemplatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPromptTemplatesBinding
    private val promptRepository = PromptRepository()

    private val slots = listOf(
        PromptSlot(provider = AnalysisProvider.OPENAI, mode = AnalysisMode.FACIAL),
        PromptSlot(provider = AnalysisProvider.OPENAI, mode = AnalysisMode.DENTAL),
        PromptSlot(provider = AnalysisProvider.GEMINI, mode = AnalysisMode.FACIAL),
        PromptSlot(provider = AnalysisProvider.GEMINI, mode = AnalysisMode.DENTAL),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityPromptTemplatesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.promptsToolbar.setNavigationOnClickListener { finish() }
        binding.retryButton.setOnClickListener { refreshFromFirestore() }

        PromptStore.hydrate(this)
        renderPrompts(PromptStore.getAllPrompts(this))
        refreshFromFirestore()
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

    private fun refreshFromFirestore() {
        binding.progressIndicator.isVisible = true
        binding.statusText.isVisible = true
        binding.retryButton.isVisible = false
        binding.statusText.text = getString(R.string.prompts_loading)

        promptRepository.fetchAllPrompts { result ->
            if (isFinishing || isDestroyed) return@fetchAllPrompts

            runOnUiThread {
                binding.progressIndicator.isVisible = false
                result.fold(
                    onSuccess = { remotePrompts ->
                        if (remotePrompts.isNotEmpty()) {
                            val merged = PromptStore.getAllPrompts(this).toMutableMap()
                            remotePrompts.forEach { (id, prompt) ->
                                if (prompt.isNotBlank()) {
                                    merged[id] = prompt
                                }
                            }
                            PromptStore.setPrompts(this, merged)
                        }
                        renderPrompts(PromptStore.getAllPrompts(this))
                        binding.statusText.text = getString(R.string.prompts_loaded)
                    },
                    onFailure = { error ->
                        renderPrompts(PromptStore.getAllPrompts(this))
                        binding.retryButton.isVisible = true
                        binding.statusText.text =
                            error.localizedMessage ?: getString(R.string.prompts_load_failed)
                    },
                )
            }
        }
    }

    private fun renderPrompts(prompts: Map<String, String>) {
        binding.listContainer.removeAllViews()
        slots.forEach { slot ->
            addPromptCard(
                slot = slot,
                promptText = prompts[slot.documentId].orEmpty(),
            )
        }
    }

    private fun addPromptCard(slot: PromptSlot, promptText: String) {
        val itemView = LayoutInflater.from(this).inflate(
            R.layout.item_prompt_template,
            binding.listContainer,
            false,
        )
        itemView.findViewById<TextView>(R.id.promptTitleText).text = slot.displayTitle
        itemView.findViewById<TextView>(R.id.promptIdText).text = slot.documentId
        val bodyTextView = itemView.findViewById<TextView>(R.id.promptBodyText)
        if (promptText.isBlank()) {
            bodyTextView.text = getString(R.string.prompts_missing_body)
            bodyTextView.setTextColor(getColor(R.color.sand_300))
        } else {
            bodyTextView.text = promptText
            bodyTextView.setTextColor(getColor(R.color.mist_50_dim))
        }
        itemView.findViewById<com.google.android.material.button.MaterialButton>(R.id.editButton)
            .setOnClickListener {
                showEditDialog(slot, promptText)
            }
        binding.listContainer.addView(itemView)
    }

    private fun showEditDialog(slot: PromptSlot, existingPrompt: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_prompt, null, false)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.promptInputLayout)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.promptEditText)
        inputLayout.hint = slot.displayTitle
        editText.setText(existingPrompt)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.prompts_edit_title, slot.provider.displayName, slot.mode.displayLabel))
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.prompts_save, null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val value = editText.text?.toString().orEmpty().trim()
                if (value.isBlank()) {
                    inputLayout.error = getString(R.string.prompts_error_empty)
                    return@setOnClickListener
                }
                inputLayout.error = null
                saveButton.isEnabled = false
                binding.progressIndicator.isVisible = true
                binding.statusText.isVisible = true
                binding.statusText.text = getString(R.string.prompts_saving)

                promptRepository.upsertPrompt(slot.provider, slot.mode, value) { result ->
                    if (isFinishing || isDestroyed) return@upsertPrompt

                    runOnUiThread {
                        saveButton.isEnabled = true
                        binding.progressIndicator.isVisible = false
                        result.fold(
                            onSuccess = {
                                val updated = PromptStore.getAllPrompts(this).toMutableMap()
                                updated[slot.documentId] = value
                                PromptStore.setPrompts(this, updated)
                                renderPrompts(updated)
                                binding.statusText.text = getString(R.string.prompts_saved)
                                Toast.makeText(
                                    this,
                                    getString(R.string.prompts_saved_toast),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                dialog.dismiss()
                            },
                            onFailure = { error ->
                                val message = error.localizedMessage ?: getString(R.string.prompts_save_failed)
                                binding.statusText.text = message
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            },
                        )
                    }
                }
            }
        }

        dialog.show()
    }

    private data class PromptSlot(
        val provider: AnalysisProvider,
        val mode: AnalysisMode,
    ) {
        val documentId: String = PromptStore.documentId(provider, mode)
        val displayTitle: String = "${provider.displayName} • ${if (mode == AnalysisMode.FACIAL) "Face analysis" else "Dental analysis"}"
    }
}

