package com.qarro.livetranslator

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Explicit third-party fallback used only when the user taps the proxy button.
 * The list contains public instances from the official Invidious instances list.
 */
class InvidiousProxyResolver : Closeable {
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .build()

    private val instances = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://yt.chocolatemoo53.com",
        "https://invidious.tiekoetter.com",
    )

    fun resolve(
        videoId: String,
        onProgress: (String) -> Unit = {},
        callback: (Result<ResolvedYouTubeStream>) -> Unit,
    ) {
        executor.execute {
            val failures = mutableListOf<String>()
            for ((index, base) in instances.withIndex()) {
                onProgress("Proxy ${index + 1}/${instances.size}: ${base.removePrefix("https://")}…")
                val result = runCatching { resolveFrom(base, videoId) }
                if (result.isSuccess) {
                    onProgress("Proxy: поток найден ✓")
                    callback(result)
                    return@execute
                }
                failures += "${base.removePrefix("https://")}: ${shortError(result.exceptionOrNull())}"
            }
            callback(
                Result.failure(
                    IllegalStateException(
                        "Публичные proxy тоже не получили видео. ${failures.joinToString(" • ").take(520)}",
                    ),
                ),
            )
        }
    }

    private fun resolveFrom(base: String, videoId: String): ResolvedYouTubeStream {
        val apiUrl = "$base/api/v1/videos/$videoId?local=true"
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "QarroLiveTranslator/0.5.6")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                throw IllegalStateException("HTTP ${response.code}${if (reason.isNotBlank()) ": $reason" else ""}")
            }
            if (body.isBlank()) throw IllegalStateException("пустой ответ API")
            return parseVideo(base, JSONObject(body))
        }
    }

    private fun parseVideo(base: String, root: JSONObject): ResolvedYouTubeStream {
        val title = root.optString("title").ifBlank { "YouTube video" }
        val duration = root.optLong("lengthSeconds", 0L)
        val variants = mutableListOf<YouTubePlaybackVariant>()

        val formatStreams = root.optJSONArray("formatStreams")
        if (formatStreams != null) {
            val combined = (0 until formatStreams.length())
                .mapNotNull { i -> formatStreams.optJSONObject(i) }
                .filter { item ->
                    val url = item.optString("url")
                    val type = item.optString("type")
                    url.isNotBlank() && (type.contains("video/mp4", true) || item.optString("container").equals("mp4", true))
                }
                .maxByOrNull { item -> qualityHeight(item.optString("qualityLabel").ifBlank { item.optString("quality") }) }

            if (combined != null) {
                variants += YouTubePlaybackVariant(
                    mode = YouTubePlaybackMode.COMBINED,
                    videoUrl = absoluteUrl(base, combined.optString("url")),
                    videoMimeType = "video/mp4",
                    label = "proxy • ${combined.optString("qualityLabel").ifBlank { combined.optString("quality").ifBlank { "progressive" } }}",
                )
            }
        }

        val adaptive = root.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            val items = (0 until adaptive.length()).mapNotNull { i -> adaptive.optJSONObject(i) }
            val video = items
                .filter { item ->
                    val type = item.optString("type")
                    item.optString("url").isNotBlank() && type.contains("video/", true)
                }
                .filter { item -> qualityHeight(item.optString("qualityLabel")) <= 1080 || qualityHeight(item.optString("qualityLabel")) == 0 }
                .maxByOrNull { item -> qualityHeight(item.optString("qualityLabel")) }
            val audio = items
                .filter { item ->
                    val type = item.optString("type")
                    item.optString("url").isNotBlank() && type.contains("audio/", true)
                }
                .maxByOrNull { item -> item.optLong("bitrate", 0L) }

            if (video != null && audio != null) {
                variants += YouTubePlaybackVariant(
                    mode = YouTubePlaybackMode.SEPARATE,
                    videoUrl = absoluteUrl(base, video.optString("url")),
                    audioUrl = absoluteUrl(base, audio.optString("url")),
                    videoMimeType = mimeBase(video.optString("type")),
                    audioMimeType = mimeBase(audio.optString("type")),
                    label = "proxy • ${video.optString("qualityLabel").ifBlank { "adaptive" }}",
                )
            }
        }

        root.optString("hlsUrl").takeIf { it.isNotBlank() }?.let { hls ->
            variants += YouTubePlaybackVariant(
                mode = YouTubePlaybackMode.HLS,
                videoUrl = absoluteUrl(base, hls),
                label = "proxy • HLS",
            )
        }

        root.optString("dashUrl").takeIf { it.isNotBlank() }?.let { dash ->
            variants += YouTubePlaybackVariant(
                mode = YouTubePlaybackMode.DASH,
                videoUrl = absoluteUrl(base, dash),
                label = "proxy • DASH",
            )
        }

        if (variants.isEmpty()) {
            val reason = root.optString("reason").ifBlank {
                root.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
            }
            throw IllegalStateException(reason.ifBlank { "API не вернул playable streams" })
        }

        return ResolvedYouTubeStream(
            title = title,
            variants = variants.distinctBy { listOf(it.mode.name, it.videoUrl, it.audioUrl.orEmpty()).joinToString("|") },
            durationSeconds = duration,
        )
    }

    private fun absoluteUrl(base: String, url: String): String {
        if (url.startsWith("https://") || url.startsWith("http://")) return url
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    private fun qualityHeight(text: String): Int = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 0

    private fun mimeBase(type: String): String? = type.substringBefore(';').trim().takeIf { it.contains('/') }

    private fun shortError(error: Throwable?): String = (error?.message ?: error?.javaClass?.simpleName ?: "unknown")
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .take(150)

    override fun close() {
        executor.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
