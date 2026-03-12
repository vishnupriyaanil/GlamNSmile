package com.q8ind.glamnsmile

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class NanoBananaImageClient(
    private val apiKey: String,
) {

    data class Result(
        val imageFile: File,
        val textResponse: String,
    )

    fun generateFaceInfographic(
        referenceImage: File,
        prompt: String,
        outputDirectory: File,
    ): Result {
        require(apiKey.isNotBlank()) { "Gemini API key is missing." }
        require(referenceImage.exists()) { "Reference image file was not found." }

        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", referenceImage.mimeType())
                        .put(
                            "data",
                            Base64.encodeToString(referenceImage.readBytes(), Base64.NO_WRAP),
                        ),
                ),
            )
            .put(JSONObject().put("text", prompt))

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", parts),
                ),
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                    .put(
                        "imageConfig",
                        JSONObject()
                            .put("aspectRatio", "16:9")
                            .put("imageSize", "2K"),
                    ),
            )
        }

        val connection = (URL(IMAGE_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }

            if (responseCode !in 200..299) {
                throw IllegalStateException(parseErrorMessage(responseBody, responseCode))
            }

            return parseResult(responseBody, outputDirectory)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResult(
        body: String,
        outputDirectory: File,
    ): Result {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw IllegalStateException(extractPromptFeedback(root).ifBlank { "Gemini did not return a result." })
        }

        val textResponse = StringBuilder()
        var generatedImageFile: File? = null

        for (candidateIndex in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(candidateIndex) ?: continue
            val content = candidate.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue

            for (partIndex in 0 until parts.length()) {
                val part = parts.optJSONObject(partIndex) ?: continue
                val text = part.optString("text")
                if (text.isNotBlank()) {
                    if (textResponse.isNotEmpty()) {
                        textResponse.append("\n\n")
                    }
                    textResponse.append(text.trim())
                }

                val inlineData = part.optJSONObject("inlineData")
                    ?: part.optJSONObject("inline_data")
                    ?: continue
                val mimeType = inlineData.optString("mimeType")
                    .ifBlank { inlineData.optString("mime_type") }
                val encodedImage = inlineData.optString("data")
                if (encodedImage.isBlank() || generatedImageFile != null) {
                    continue
                }

                val extension = when {
                    mimeType.contains("png", ignoreCase = true) -> "png"
                    mimeType.contains("webp", ignoreCase = true) -> "webp"
                    else -> "jpg"
                }
                val outputFile = File(
                    outputDirectory,
                    "nano-banana-${System.currentTimeMillis()}.$extension",
                )
                outputFile.writeBytes(Base64.decode(encodedImage, Base64.DEFAULT))
                generatedImageFile = outputFile
            }
        }

        val finalImage = generatedImageFile
            ?: throw IllegalStateException(
                extractPromptFeedback(root).ifBlank { "Gemini did not return a generated image." },
            )

        return Result(
            imageFile = finalImage,
            textResponse = textResponse.toString().trim(),
        )
    }

    private fun extractPromptFeedback(root: JSONObject): String {
        val promptFeedback = root.optJSONObject("promptFeedback") ?: return ""
        return promptFeedback.optString("blockReasonMessage")
            .ifBlank { promptFeedback.optString("blockReason") }
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
        private const val IMAGE_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-image-preview:generateContent"
    }
}
