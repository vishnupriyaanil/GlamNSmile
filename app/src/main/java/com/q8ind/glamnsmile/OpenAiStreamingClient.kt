package com.q8ind.glamnsmile

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class OpenAiStreamingClient(
    private val apiKey: String,
) {

    fun streamImageAnalysis(
        imageFiles: List<File>,
        prompt: String,
        onChunk: (String) -> Unit,
    ) {
        require(apiKey.isNotBlank()) { "OpenAI API key is missing." }
        require(imageFiles.isNotEmpty()) { "At least one image is required." }

        val content = JSONArray()
            .put(
                JSONObject()
                    .put("type", "input_text")
                    .put("text", prompt),
            )

        imageFiles.forEach { imageFile ->
            content.put(
                JSONObject()
                    .put("type", "input_image")
                    .put("image_url", imageFile.toDataUrl())
                    .put("detail", "high"),
            )
        }

        val payload = JSONObject().apply {
            put("model", TEXT_MODEL)
            put("stream", true)
            put("max_output_tokens", 4096)
            put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content),
                ),
            )
        }

        val connection = (URL(RESPONSES_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
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
                val dataBuffer = StringBuilder()
                lines.forEach { line ->
                    when {
                        line.startsWith("data:") -> {
                            if (dataBuffer.isNotEmpty()) {
                                dataBuffer.append('\n')
                            }
                            dataBuffer.append(line.removePrefix("data:").trim())
                        }

                        line.isBlank() -> {
                            processEventData(dataBuffer.toString(), onChunk)
                            dataBuffer.setLength(0)
                        }
                    }
                }
                processEventData(dataBuffer.toString(), onChunk)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun processEventData(
        eventPayload: String,
        onChunk: (String) -> Unit,
    ) {
        if (eventPayload.isBlank() || eventPayload == "[DONE]") {
            return
        }

        val event = JSONObject(eventPayload)
        when (event.optString("type")) {
            "response.output_text.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotBlank()) {
                    onChunk(delta)
                }
            }

            "response.failed",
            "error",
            -> throw IllegalStateException(extractStreamErrorMessage(event))
        }
    }

    private fun extractStreamErrorMessage(event: JSONObject): String {
        val directMessage = event.optString("message")
        if (directMessage.isNotBlank()) {
            return directMessage
        }

        val errorObject = event.optJSONObject("error")
        val nestedMessage = errorObject?.optString("message").orEmpty()
        if (nestedMessage.isNotBlank()) {
            return nestedMessage
        }

        return event.optJSONObject("response")
            ?.optJSONObject("error")
            ?.optString("message")
            .orEmpty()
            .ifBlank { "OpenAI streaming request failed." }
    }

    private fun parseErrorMessage(body: String, responseCode: Int): String {
        if (body.isBlank()) {
            return "OpenAI request failed with HTTP $responseCode."
        }

        return try {
            val message = JSONObject(body)
                .optJSONObject("error")
                ?.optString("message")
                .orEmpty()
            if (message.isBlank()) {
                "OpenAI request failed with HTTP $responseCode."
            } else {
                message
            }
        } catch (_: Exception) {
            "OpenAI request failed with HTTP $responseCode."
        }
    }

    private fun File.toDataUrl(): String {
        val encoded = Base64.encodeToString(readBytes(), Base64.NO_WRAP)
        return "data:${mimeType()};base64,$encoded"
    }

    private fun File.mimeType(): String {
        return when (extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    companion object {
        private const val RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses"
        private const val TEXT_MODEL = "gpt-4.1"
    }
}
