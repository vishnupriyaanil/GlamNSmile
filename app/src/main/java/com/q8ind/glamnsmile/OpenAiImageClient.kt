package com.q8ind.glamnsmile

import android.util.Base64
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class OpenAiImageClient(
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
        require(apiKey.isNotBlank()) { "OpenAI API key is missing." }
        require(referenceImage.exists()) { "Reference image file was not found." }

        val boundary = "----GlamNSmile${System.currentTimeMillis()}"
        val connection = (URL(IMAGE_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            DataOutputStream(connection.outputStream).use { output ->
                output.writeFormField(boundary, "model", IMAGE_MODEL)
                output.writeFormField(boundary, "prompt", prompt)
                output.writeFormField(boundary, "size", "1536x1024")
                output.writeFormField(boundary, "quality", "high")
                output.writeFormField(boundary, "output_format", "jpeg")
                output.writeFormField(boundary, "input_fidelity", "high")
                output.writeFileField(boundary, "image[]", referenceImage)
                output.writeBytes("--$boundary--\r\n")
                output.flush()
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

    private fun parseResult(body: String, outputDirectory: File): Result {
        val root = JSONObject(body)
        val data = root.optJSONArray("data")
            ?: throw IllegalStateException("OpenAI did not return image data.")
        val firstItem = data.optJSONObject(0)
            ?: throw IllegalStateException("OpenAI did not return an image result.")
        val encodedImage = firstItem.optString("b64_json")
        if (encodedImage.isBlank()) {
            throw IllegalStateException("OpenAI did not return a generated image.")
        }

        val outputFile = File(outputDirectory, "openai-face-${System.currentTimeMillis()}.jpg")
        outputFile.writeBytes(Base64.decode(encodedImage, Base64.DEFAULT))
        return Result(
            imageFile = outputFile,
            textResponse = firstItem.optString("revised_prompt").trim(),
        )
    }

    private fun parseErrorMessage(body: String, responseCode: Int): String {
        if (body.isBlank()) {
            return "OpenAI image request failed with HTTP $responseCode."
        }

        return try {
            val message = JSONObject(body)
                .optJSONObject("error")
                ?.optString("message")
                .orEmpty()
            if (message.isBlank()) {
                "OpenAI image request failed with HTTP $responseCode."
            } else {
                message
            }
        } catch (_: Exception) {
            "OpenAI image request failed with HTTP $responseCode."
        }
    }

    private fun DataOutputStream.writeFormField(boundary: String, name: String, value: String) {
        writeBytes("--$boundary\r\n")
        writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        writeBytes(value)
        writeBytes("\r\n")
    }

    private fun DataOutputStream.writeFileField(boundary: String, name: String, file: File) {
        writeBytes("--$boundary\r\n")
        writeBytes(
            "Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n",
        )
        writeBytes("Content-Type: ${file.mimeType()}\r\n\r\n")
        write(file.readBytes())
        writeBytes("\r\n")
    }

    private fun File.mimeType(): String {
        return when (extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    companion object {
        private const val IMAGE_ENDPOINT = "https://api.openai.com/v1/images/edits"
        private const val IMAGE_MODEL = "gpt-image-1"
    }
}
