package com.q8ind.glamnsmile

import android.content.Context
import org.json.JSONObject

object PromptStore {

    private const val PREFS_NAME = "analysis_prompts_cache"
    private const val KEY_PROMPTS_JSON = "prompts_json_v1"

    @Volatile
    private var hydrated: Boolean = false

    @Volatile
    private var promptsById: Map<String, String> = emptyMap()

    fun hydrate(context: Context) {
        if (hydrated) return
        synchronized(this) {
            if (hydrated) return
            promptsById = readFromPrefs(context)
            hydrated = true
        }
    }

    fun setPrompts(context: Context, prompts: Map<String, String>) {
        synchronized(this) {
            promptsById = prompts.toMap()
            hydrated = true
            writeToPrefs(context, promptsById)
        }
    }

    fun getAllPrompts(context: Context): Map<String, String> {
        hydrate(context)
        return promptsById.toMap()
    }

    fun getPrompt(context: Context, provider: AnalysisProvider, mode: AnalysisMode): String? {
        hydrate(context)
        return promptsById[documentId(provider, mode)]
    }

    fun hasAllRequiredPrompts(context: Context): Boolean {
        return missingRequiredPromptIds(context).isEmpty()
    }

    fun missingRequiredPromptIds(context: Context): List<String> {
        hydrate(context)
        return requiredDocumentIds().filter { id ->
            promptsById[id].orEmpty().trim().isBlank()
        }
    }

    fun requiredDocumentIds(): List<String> {
        return listOf(
            documentId(AnalysisProvider.OPENAI, AnalysisMode.FACIAL),
            documentId(AnalysisProvider.OPENAI, AnalysisMode.DENTAL),
            documentId(AnalysisProvider.GEMINI, AnalysisMode.FACIAL),
            documentId(AnalysisProvider.GEMINI, AnalysisMode.DENTAL),
        )
    }

    fun documentId(provider: AnalysisProvider, mode: AnalysisMode): String {
        return "${provider.id}_${mode.id}"
    }

    private fun writeToPrefs(context: Context, prompts: Map<String, String>) {
        val json = JSONObject()
        prompts.forEach { (id, prompt) ->
            json.put(id, prompt)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROMPTS_JSON, json.toString())
            .apply()
    }

    private fun readFromPrefs(context: Context): Map<String, String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROMPTS_JSON, null)
            ?.trim()
            .orEmpty()
        if (raw.isBlank()) return emptyMap()

        return runCatching {
            val json = JSONObject(raw)
            val keys = json.keys()
            val map = LinkedHashMap<String, String>()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.optString(key).orEmpty()
            }
            map
        }.getOrDefault(emptyMap())
    }
}
