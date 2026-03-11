package com.q8ind.glamnsmile

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

object GeminiResponseFormatter {

    fun format(raw: String): Spanned {
        val builder = SpannableStringBuilder()
        val normalized = raw.replace("\r\n", "\n")
        val lines = normalized.split('\n')

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) {
                appendBlankLine(builder)
                return@forEachIndexed
            }

            val headingText = headingTextOrNull(line)
            when {
                headingText != null -> appendStyledLine(
                    builder = builder,
                    text = headingText,
                    isHeading = true,
                    addTrailingBlankLine = true,
                )

                line == "---" || line == "***" -> {
                    if (builder.isNotEmpty() && !builder.endsWith("\n\n")) {
                        builder.append("\n")
                    }
                }

                line.startsWith("- ") || (line.startsWith("* ") && !line.startsWith("**")) -> {
                    appendStyledLine(
                        builder = builder,
                        text = "\u2022 ${line.drop(2).trim()}",
                        isBullet = true,
                        addTrailingBlankLine = false,
                    )
                }

                else -> appendStyledLine(
                    builder = builder,
                    text = line,
                    addTrailingBlankLine = index < lines.lastIndex && lines[index + 1].trim().isEmpty(),
                )
            }
        }

        return builder
    }

    private fun headingTextOrNull(line: String): String? {
        return when {
            line.startsWith("### ") -> line.removePrefix("### ").trim()
            line.startsWith("## ") -> line.removePrefix("## ").trim()
            line.startsWith("# ") -> line.removePrefix("# ").trim()
            line.startsWith("**") && line.endsWith("**") && line.length > 4 ->
                line.removePrefix("**").removeSuffix("**").trim()
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun appendStyledLine(
        builder: SpannableStringBuilder,
        text: String,
        isHeading: Boolean = false,
        isBullet: Boolean = false,
        addTrailingBlankLine: Boolean = false,
    ) {
        if (builder.isNotEmpty() && !builder.endsWith("\n")) {
            builder.append("\n")
        }

        val lineStart = builder.length
        appendInlineBold(builder, text)
        val lineEnd = builder.length

        if (isHeading) {
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                lineStart,
                lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.setSpan(
                RelativeSizeSpan(1.18f),
                lineStart,
                lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        if (isBullet) {
            builder.setSpan(
                LeadingMarginSpan.Standard(28, 0),
                lineStart,
                lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        builder.append("\n")
        if (addTrailingBlankLine && !builder.endsWith("\n\n")) {
            builder.append("\n")
        }
    }

    private fun appendInlineBold(
        builder: SpannableStringBuilder,
        text: String,
    ) {
        val regex = Regex("\\*\\*(.+?)\\*\\*")
        var cursor = 0

        regex.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                builder.append(text.substring(cursor, match.range.first))
            }
            val boldStart = builder.length
            builder.append(match.groupValues[1])
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                boldStart,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            cursor = match.range.last + 1
        }

        if (cursor < text.length) {
            builder.append(text.substring(cursor))
        }
    }

    private fun appendBlankLine(builder: SpannableStringBuilder) {
        if (builder.isEmpty()) {
            return
        }

        if (!builder.endsWith("\n\n")) {
            builder.append("\n")
        }
    }

    private fun CharSequence.endsWith(suffix: String): Boolean {
        if (length < suffix.length) {
            return false
        }
        return substring(length - suffix.length, length) == suffix
    }
}
