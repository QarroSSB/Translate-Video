package com.qarro.livetranslator

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Stores an imported Netscape-format cookies.txt in the app's private storage.
 * The file is never uploaded anywhere by Qarro Translator; it is only passed locally to yt-dlp.
 */
class YouTubeCookieStore(private val context: Context) {
    private val cookieFile = File(context.filesDir, "youtube-cookies.txt")

    fun hasCookies(): Boolean = cookieFile.isFile && cookieFile.length() > 0

    fun filePathOrNull(): String? = cookieFile.takeIf { hasCookies() }?.absolutePath

    fun importFrom(uri: Uri): Result<Int> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            reader.readText()
        } ?: error("Не удалось прочитать выбранный файл")

        require(text.length <= 4_000_000) { "Файл cookies слишком большой" }

        val normalized = text.replace("\r\n", "\n")
        val cookieLines = normalized.lineSequence()
            .filter { line -> line.isNotBlank() && !line.startsWith("#") }
            .toList()

        require(cookieLines.isNotEmpty()) { "В файле нет cookies" }
        require(cookieLines.any { line ->
            val lower = line.lowercase()
            lower.contains("youtube.com") || lower.contains("google.com")
        }) {
            "Это не похоже на cookies.txt для YouTube"
        }

        // filesDir is private to this application (Android app sandbox).
        cookieFile.writeText(normalized)
        cookieLines.size
    }

    fun clear() {
        if (cookieFile.exists()) cookieFile.delete()
    }
}
