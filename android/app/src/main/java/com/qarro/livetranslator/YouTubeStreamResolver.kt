package com.qarro.livetranslator

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class YouTubePlaybackMode {
    COMBINED,
    SEPARATE,
    HLS,
    DASH,
}

data class YouTubePlaybackVariant(
    val mode: YouTubePlaybackMode,
    val videoUrl: String,
    val audioUrl: String? = null,
    val videoMimeType: String? = null,
    val audioMimeType: String? = null,
    val label: String,
)

data class ResolvedYouTubeStream(
    val title: String,
    val variants: List<YouTubePlaybackVariant>,
    val durationSeconds: Long,
) {
    val resolution: String
        get() = variants.firstOrNull()?.label ?: "авто"
}

/**
 * Resolves a YouTube URL on-device.
 * Fast path: NewPipe. Fallbacks: yt-dlp guest web_embedded, then authenticated yt-dlp cookies.
 */
class YouTubeStreamResolver(context: Context? = null) : Closeable {
    companion object {
        private val initialized = AtomicBoolean(false)

        @Synchronized
        private fun ensureInitialized() {
            if (initialized.compareAndSet(false, true)) {
                NewPipe.init(QarroDownloader())
            }
        }
    }

    private val appContext = context?.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    fun resolve(
        url: String,
        onProgress: (String) -> Unit = {},
        callback: (Result<ResolvedYouTubeStream>) -> Unit,
    ) {
        executor.execute {
            onProgress("NewPipe: получаю метаданные YouTube…")
            val primary = runCatching { resolveBlocking(url) }
            if (primary.isSuccess) {
                onProgress("NewPipe: поток найден ✓")
                callback(primary)
                return@execute
            }

            val primaryError = primary.exceptionOrNull()
            val context = appContext
            if (context == null) {
                callback(Result.failure(wrapPrimaryFailure(primaryError)))
                return@execute
            }

            onProgress("NewPipe не получил поток • переключаюсь на yt-dlp…")
            val fallback = runCatching { resolveWithYtDlp(context, url, onProgress) }
            if (fallback.isSuccess) {
                onProgress("yt-dlp: поток найден ✓")
                callback(fallback)
            } else {
                val fallbackError = fallback.exceptionOrNull()
                val p = primaryError?.message?.take(180) ?: primaryError?.javaClass?.simpleName ?: "unknown"
                val f = fallbackError?.message?.take(280) ?: fallbackError?.javaClass?.simpleName ?: "unknown"
                callback(
                    Result.failure(
                        IllegalStateException(
                            "Не удалось открыть YouTube. NewPipe: $p • yt-dlp: $f",
                            fallbackError,
                        ),
                    ),
                )
            }
        }
    }

    internal fun resolveBlockingForDiagnostics(url: String): ResolvedYouTubeStream = resolveBlocking(url)

    private fun wrapPrimaryFailure(error: Throwable?): Throwable {
        val detail = error?.message?.take(220).orEmpty()
        val base = if (detail.isBlank()) {
            error?.javaClass?.simpleName ?: "unknown extractor error"
        } else {
            "${error?.javaClass?.simpleName}: $detail"
        }
        return IllegalStateException("$base • ${QarroDownloader.lastDiagnostic}", error)
    }

    private fun resolveWithYtDlp(
        context: Context,
        url: String,
        onProgress: (String) -> Unit,
    ): ResolvedYouTubeStream {
        val youtubeDL = YoutubeDL.getInstance()
        onProgress("yt-dlp: инициализирую движок на телефоне…")
        youtubeDL.init(context)

        val prefs = context.getSharedPreferences("qarro_live_translator", Context.MODE_PRIVATE)
        val lastUpdate = prefs.getLong("ytdlp_last_update_ms", 0L)
        val now = System.currentTimeMillis()
        if (now - lastUpdate > 24L * 60L * 60L * 1000L) {
            onProgress("yt-dlp: проверяю актуальную версию…")
            runCatching {
                youtubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            }.onSuccess {
                prefs.edit().putLong("ytdlp_last_update_ms", now).apply()
            }
        }

        val failures = mutableListOf<String>()

        // First try a client that currently does not require a GVS PO token for embeddable videos.
        // Skipping the normal webpage also avoids the anonymous-watch bot gate on some IPs.
        onProgress("yt-dlp: пробую гостевой web_embedded…")
        val guestRequest = baseYtDlpRequest(url).apply {
            addOption("--extractor-args", "youtube:player_client=web_embedded;player_skip=webpage,configs")
            addOption("-f", "best[ext=mp4]/best")
        }
        runCatching { youtubeDL.getInfo(guestRequest) }
            .onSuccess { return streamFromYtDlpInfo(it, "yt-dlp guest") }
            .onFailure { failures += "guest: ${shortError(it)}" }

        val cookieStore = YouTubeCookieStore(context)
        val cookiePath = cookieStore.filePathOrNull()
        if (cookiePath != null) {
            // With cookies prefer web_safari/HLS. HLS currently avoids the GVS PO-token requirement
            // for this client more reliably than a raw progressive googlevideo URL.
            onProgress("yt-dlp: использую импортированную YouTube-сессию…")
            val authenticated = baseYtDlpRequest(url).apply {
                addOption("--cookies", cookiePath)
                addOption("--extractor-args", "youtube:player_client=web_safari")
                addOption("-f", "best[protocol^=m3u8]/best[ext=mp4]/best")
            }
            runCatching { youtubeDL.getInfo(authenticated) }
                .onSuccess { return streamFromYtDlpInfo(it, "yt-dlp cookies") }
                .onFailure { failures += "cookies: ${shortError(it)}" }

            // Final authenticated fallback: let current yt-dlp choose the clients/formats itself.
            onProgress("yt-dlp: пробую авторизованный auto-режим…")
            val authenticatedAuto = baseYtDlpRequest(url).apply {
                addOption("--cookies", cookiePath)
                addOption("-f", "best[ext=mp4]/best")
            }
            runCatching { youtubeDL.getInfo(authenticatedAuto) }
                .onSuccess { return streamFromYtDlpInfo(it, "yt-dlp cookies auto") }
                .onFailure { failures += "cookies-auto: ${shortError(it)}" }
        } else {
            // Keep the normal anonymous attempt as a last guest fallback.
            onProgress("yt-dlp: пробую обычный гостевой режим…")
            val normalGuest = baseYtDlpRequest(url).apply {
                addOption("-f", "best[ext=mp4]/best")
            }
            runCatching { youtubeDL.getInfo(normalGuest) }
                .onSuccess { return streamFromYtDlpInfo(it, "yt-dlp guest auto") }
                .onFailure { failures += "guest-auto: ${shortError(it)}" }
        }

        val joined = failures.joinToString(" • ").take(520)
        val botGate = joined.contains("confirm you're not a bot", ignoreCase = true) ||
            joined.contains("LOGIN_REQUIRED", ignoreCase = true) ||
            joined.contains("sign in", ignoreCase = true)

        if (botGate && cookiePath == null) {
            throw IllegalStateException(
                "YouTube запросил подтверждение «не бот». Нажми «Импортировать cookies.txt» " +
                    "или попробуй другую сеть. $joined",
            )
        }
        throw IllegalStateException(joined.ifBlank { "yt-dlp не вернул playable URL" })
    }

    private fun baseYtDlpRequest(url: String): YoutubeDLRequest = YoutubeDLRequest(url).apply {
        addOption("--no-playlist")
        addOption("--no-warnings")
    }

    private fun streamFromYtDlpInfo(info: VideoInfo, sourceLabel: String): ResolvedYouTubeStream {
        val direct = info.url?.takeIf { it.isNotBlank() }
        if (direct != null) {
            val hls = direct.contains(".m3u8", ignoreCase = true) ||
                direct.contains("/manifest/hls", ignoreCase = true) ||
                direct.contains("hls_playlist", ignoreCase = true)
            return ResolvedYouTubeStream(
                title = info.title ?: info.fulltitle ?: "YouTube video",
                variants = listOf(
                    YouTubePlaybackVariant(
                        mode = if (hls) YouTubePlaybackMode.HLS else YouTubePlaybackMode.COMBINED,
                        videoUrl = direct,
                        videoMimeType = if (hls) null else mimeForVideoExt(info.ext),
                        label = "$sourceLabel • ${info.resolution ?: info.format ?: if (hls) "HLS" else "single stream"}",
                    ),
                ),
                durationSeconds = info.duration.toLong(),
            )
        }

        val requested = info.requestedFormats.orEmpty()
        val video = requested.firstOrNull { format ->
            !format.url.isNullOrBlank() && !format.vcodec.isNullOrBlank() && format.vcodec != "none" &&
                (format.acodec.isNullOrBlank() || format.acodec == "none")
        }
        val audio = requested.firstOrNull { format ->
            !format.url.isNullOrBlank() && !format.acodec.isNullOrBlank() && format.acodec != "none" &&
                (format.vcodec.isNullOrBlank() || format.vcodec == "none")
        }
        if (video?.url != null && audio?.url != null) {
            return ResolvedYouTubeStream(
                title = info.title ?: info.fulltitle ?: "YouTube video",
                variants = listOf(
                    YouTubePlaybackVariant(
                        mode = YouTubePlaybackMode.SEPARATE,
                        videoUrl = video.url!!,
                        audioUrl = audio.url!!,
                        videoMimeType = mimeForVideoExt(video.ext),
                        audioMimeType = mimeForAudioExt(audio.ext),
                        label = "$sourceLabel • ${video.height.takeIf { it > 0 }?.let { "${it}p" } ?: "adaptive"}",
                    ),
                ),
                durationSeconds = info.duration.toLong(),
            )
        }

        throw IllegalStateException("$sourceLabel вернул метаданные, но без playable URL")
    }

    private fun shortError(error: Throwable): String {
        return (error.message ?: error.javaClass.simpleName)
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .take(220)
    }

    private fun resolveBlocking(url: String): ResolvedYouTubeStream {
        ensureInitialized()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val variants = mutableListOf<YouTubePlaybackVariant>()

        val adaptiveVideo = chooseVideo(info.videoOnlyStreams)
        val adaptiveAudio = chooseAudio(info.audioStreams)
        if (adaptiveVideo != null && adaptiveAudio != null) {
            variants += YouTubePlaybackVariant(
                mode = YouTubePlaybackMode.SEPARATE,
                videoUrl = adaptiveVideo.content,
                audioUrl = adaptiveAudio.content,
                videoMimeType = adaptiveVideo.format?.mimeType,
                audioMimeType = adaptiveAudio.format?.mimeType,
                label = "${displayResolution(adaptiveVideo)} + adaptive audio",
            )
        }

        val combined = chooseVideo(
            info.videoStreams.filter { stream ->
                stream.isUrl &&
                    !stream.isVideoOnly &&
                    stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
            },
        )
        if (combined != null) {
            variants += YouTubePlaybackVariant(
                mode = YouTubePlaybackMode.COMBINED,
                videoUrl = combined.content,
                videoMimeType = combined.format?.mimeType,
                label = "${displayResolution(combined)} progressive",
            )
        }

        info.hlsUrl.takeIf { it.isNotBlank() }?.let { hls ->
            variants += YouTubePlaybackVariant(
                mode = YouTubePlaybackMode.HLS,
                videoUrl = hls,
                label = "HLS",
            )
        }
        info.dashMpdUrl.takeIf { it.isNotBlank() }?.let { dash ->
            variants += YouTubePlaybackVariant(
                mode = YouTubePlaybackMode.DASH,
                videoUrl = dash,
                label = "DASH",
            )
        }

        if (variants.isEmpty()) {
            val diagnostics =
                "video=${info.videoStreams.size}, videoOnly=${info.videoOnlyStreams.size}, " +
                    "audio=${info.audioStreams.size}, hls=${info.hlsUrl.isNotBlank()}, " +
                    "dash=${info.dashMpdUrl.isNotBlank()}"
            throw IllegalStateException("YouTube не отдал совместимый поток ($diagnostics)")
        }

        return ResolvedYouTubeStream(
            title = info.name,
            variants = variants.distinctBy { variant ->
                listOf(variant.mode.name, variant.videoUrl, variant.audioUrl.orEmpty()).joinToString("|")
            },
            durationSeconds = info.duration,
        )
    }

    private fun chooseVideo(streams: List<VideoStream>): VideoStream? {
        val playable = streams.filter { stream ->
            stream.isUrl &&
                stream.content.isNotBlank() &&
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        if (playable.isEmpty()) return null

        val mp4 = playable.filter { it.format == MediaFormat.MPEG_4 }
        val formatPreferred = mp4.ifEmpty { playable }
        val saneResolution = formatPreferred.filter { stream ->
            val height = effectiveHeight(stream)
            height in 144..1080
        }
        val candidates = saneResolution.ifEmpty { formatPreferred }

        return candidates.maxWithOrNull(
            compareBy<VideoStream>({ effectiveHeight(it) }, { it.bitrate }),
        )
    }

    private fun chooseAudio(streams: List<AudioStream>): AudioStream? {
        val playable = streams.filter { stream ->
            stream.isUrl &&
                stream.content.isNotBlank() &&
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        if (playable.isEmpty()) return null

        val m4a = playable.filter { it.format == MediaFormat.M4A }
        val candidates = m4a.ifEmpty { playable }
        return candidates.maxWithOrNull(
            compareBy<AudioStream>({ it.averageBitrate }, { it.bitrate }),
        )
    }

    private fun effectiveHeight(stream: VideoStream): Int {
        if (stream.height > 0) return stream.height
        return Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
            .find(stream.resolution)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun displayResolution(stream: VideoStream): String {
        return stream.resolution.ifBlank {
            effectiveHeight(stream).takeIf { it > 0 }?.let { "${it}p" } ?: "video"
        }
    }

    private fun mimeForVideoExt(ext: String?): String? = when (ext?.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        else -> null
    }

    private fun mimeForAudioExt(ext: String?): String? = when (ext?.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "webm", "weba" -> "audio/webm"
        else -> null
    }

    override fun close() {
        executor.shutdownNow()
    }
}
