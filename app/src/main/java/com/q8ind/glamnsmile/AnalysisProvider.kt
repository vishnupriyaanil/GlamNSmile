package com.q8ind.glamnsmile

enum class AnalysisProvider(
    val id: String,
    val displayName: String,
) {
    GEMINI(
        id = "gemini",
        displayName = "Gemini",
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
    );

    companion object {
        fun fromId(id: String?): AnalysisProvider {
            return entries.firstOrNull { it.id == id } ?: GEMINI
        }
    }
}
