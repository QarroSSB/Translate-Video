package com.qarro.livetranslator

import android.net.Uri

object YouTubeUrlParser {
    private val idRegex = Regex("^[A-Za-z0-9_-]{11}$")
    private val urlRegex = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)

    fun findFirstUrl(text: String): String? = urlRegex.find(text)
        ?.value
        ?.trimEnd('.', ',', ')', ']', '}', '>', '"', '\'')

    fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (idRegex.matches(trimmed)) return trimmed

        val candidateUrl = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            findFirstUrl(trimmed) ?: return null
        }

        val uri = runCatching { Uri.parse(candidateUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty().removePrefix("www.").removePrefix("m.")
        val segments = uri.pathSegments

        val candidate = when {
            host == "youtu.be" -> segments.firstOrNull()
            host == "youtube.com" || host.endsWith(".youtube.com") -> when (segments.firstOrNull()) {
                "watch" -> uri.getQueryParameter("v")
                "shorts", "live", "embed" -> segments.getOrNull(1)
                else -> uri.getQueryParameter("v")
            }
            else -> null
        }
        return candidate?.takeIf(idRegex::matches)
    }

    fun extractSharedYouTubeUrl(text: String): String? {
        return urlRegex.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '}', '>', '"', '\'') }
            .firstOrNull { extractVideoId(it) != null }
    }
}
