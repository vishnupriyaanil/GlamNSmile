package com.q8ind.glamnsmile

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class GeminiStreamingClient(
    private val apiKey: String,
) {

    fun streamImageAnalysis(
        imageFiles: List<File>,
        prompt: String,
        onChunk: (String) -> Unit,
    ) {
        require(apiKey.isNotBlank()) { "Gemini API key is missing." }
        require(imageFiles.isNotEmpty()) { "At least one image is required." }

        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))

        imageFiles.forEach { imageFile ->
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", imageFile.mimeType())
                        .put(
                            "data",
                            Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP),
                        ),
                ),
            )
        }

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("parts", parts)
                    },
                ),
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.5)
                    .put("topP", 0.9)
                    .put("maxOutputTokens", 4096),
            )
        }

        val connection = (URL(STREAM_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException(parseErrorMessage(errorBody, responseCode))
            }

            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data:")) {
                        return@forEach
                    }

                    val payloadLine = line.removePrefix("data:").trim()
                    if (payloadLine.isBlank() || payloadLine == "[DONE]") {
                        return@forEach
                    }

                    val text = extractText(payloadLine)
                    if (text.isNotBlank()) {
                        onChunk(text)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractText(jsonLine: String): String {
        val root = JSONObject(jsonLine)
        val candidates = root.optJSONArray("candidates") ?: return ""
        val result = StringBuilder()

        for (candidateIndex in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(candidateIndex) ?: continue
            val content = candidate.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue

            for (partIndex in 0 until parts.length()) {
                val part = parts.optJSONObject(partIndex) ?: continue
                val text = part.optString("text")
                if (text.isNotBlank()) {
                    result.append(text)
                }
            }
        }

        return result.toString()
    }

    private fun parseErrorMessage(body: String, responseCode: Int): String {
        if (body.isBlank()) {
            return "Gemini request failed with HTTP $responseCode."
        }

        return try {
            val message = JSONObject(body)
                .optJSONObject("error")
                ?.optString("message")
                .orEmpty()
            if (message.isBlank()) {
                "Gemini request failed with HTTP $responseCode."
            } else {
                message
            }
        } catch (_: Exception) {
            "Gemini request failed with HTTP $responseCode."
        }
    }

    private fun File.mimeType(): String {
        return when (extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    companion object {
        private const val STREAM_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse"
    }
}
