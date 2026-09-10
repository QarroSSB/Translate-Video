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
 * Resolves a YouTube URL entirely on the phone.
 * Order: NewPipe -> on-device BotGuard/PO-token WEB_EMBEDDED -> yt-dlp -> cookies.txt.
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
    private val poTokenResolver = appContext?.let { YouTubePoTokenResolver(it) }

    fun resolve(
        url: String,
        onProgress: (String) -> Unit = {},
        callback: (Result<ResolvedYouTubeStream>) -> Unit,
    ) {
        executor.execute {
            ensureInitialized()
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

            onProgress("NewPipe получил anti-bot • пробую PO Token через WebView…")
            val poResult = runCatching {
                poTokenResolver?.resolve(url, onProgress)
                    ?: throw IllegalStateException("PO Token resolver недоступен")
            }
            if (poResult.isSuccess) {
                callback(poResult)
                return@execute
            }
            val poError = poResult.exceptionOrNull()

            onProgress("PO Token не получил поток • переключаюсь на yt-dlp…")
            val fallback = runCatching { resolveWithYtDlp(context, url, onProgress) }
            if (fallback.isSuccess) {
                onProgress("yt-dlp: поток найден ✓")
                callback(fallback)
                return@execute
            }

            val fallbackError = fallback.exceptionOrNull()
            callback(
                Result.failure(
                    IllegalStateException(
                        "Не удалось открыть YouTube. NewPipe: ${shortError(primaryError)} • " +
                            "PO Token: ${shortError(poError)} • yt-dlp: ${shortError(fallbackError)}",
                        fallbackError,
                    ),
                ),
            )
        }
    }

    internal fun resolveBlockingForDiagnostics(url: String): ResolvedYouTubeStream {
        ensureInitialized()
        return resolveBlocking(url)
    }

    private fun wrapPrimaryFailure(error: Throwable?): Throwable =
        IllegalStateException("${shortError(error)} • ${QarroDownloader.lastDiagnostic}", error)

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
            runCatching { youtubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE) }
                .onSuccess { prefs.edit().putLong("ytdlp_last_update_ms", now).apply() }
        }

        val failures = mutableListOf<String>()
        onProgress("yt-dlp: пробую гостевой web_embedded…")
        val guestRequest = baseYtDlpRequest(url).apply {
            addOption("--extractor-args", "youtube:player_client=web_embedded;player_skip=webpage,configs")
            addOption("-f", "best[ext=mp4]/best")
        }
        runCatching { youtubeDL.getInfo(guestRequest) }
            .onSuccess { return streamFromYtDlpInfo(it, "yt-dlp guest") }
            .onFailure { failures += "guest: ${shortError(it)}" }

        val cookiePath = YouTubeCookieStore(context).filePathOrNull()
        if (cookiePath != null) {
            onProgress("yt-dlp: использую импортированную YouTube-сессию…")
            val authenticated = baseYtDlpRequest(url).apply {
                addOption("--cookies", cookiePath)
                addOption("--extractor-args", "youtube:player_client=web_safari")
                addOption("-f", "best[protocol^=m3u8]/best[ext=mp4]/best")
            }
            runCatching { youtubeDL.getInfo(authenticated) }
                .onSuccess { return streamFromYtDlpInfo(it, "yt-dlp cookies") }
                .onFailure { failures += "cookies: ${shortError(it)}" }

            onProgress("yt-dlp: пробую авторизованный auto-режим…")
            val authenticatedAuto = baseYtDlpRequest(url).apply {
                addOption("--cookies", cookiePath)
                addOption("-f", "best[ext=mp4]/best")
            }
            runCatching { youtubeDL.getInfo(authenticatedAuto) }
                .onSuccess { return streamFromYtDlpInfo(it, "yt-dlp cookies auto") }
                .onFailure { failures += "cookies-auto: ${shortError(it)}" }
        } else {
            onProgress("yt-dlp: пробую обычный гостевой режим…")
            val normalGuest = baseYtDlpRequest(url).apply {
                addOption("-f", "best[ext=mp4]/best")
            }
            runCatching { youtubeDL.getInfo(normalGuest) }
                .onSuccess { return streamFromYtDlpInfo(it, "yt-dlp guest auto") }
                .onFailure { failures += "guest-auto: ${shortError(it)}" }
        }

        val joined = failures.joinToString(" • ").take(620)
        val botGate = joined.contains("confirm you're not a bot", ignoreCase = true) ||
            joined.contains("LOGIN_REQUIRED", ignoreCase = true) ||
            joined.contains("sign in", ignoreCase = true)
        if (botGate && cookiePath == null) {
            throw IllegalStateException(
                "YouTube всё ещё требует Sign in после PO-token fallback. cookies.txt остаётся резервом. $joined",
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
            info.videoStreams.filter {
                it.isUrl && !it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
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
        info.hlsUrl.takeIf { it.isNotBlank() }?.let {
            variants += YouTubePlaybackVariant(YouTubePlaybackMode.HLS, it, label = "HLS")
        }
        info.dashMpdUrl.takeIf { it.isNotBlank() }?.let {
            variants += YouTubePlaybackVariant(YouTubePlaybackMode.DASH, it, label = "DASH")
        }
        if (variants.isEmpty()) {
            throw IllegalStateException(
                "YouTube не отдал совместимый поток " +
                    "(video=${info.videoStreams.size}, videoOnly=${info.videoOnlyStreams.size}, audio=${info.audioStreams.size})",
            )
        }
        return ResolvedYouTubeStream(
            title = info.name,
            variants = variants.distinctBy { listOf(it.mode.name, it.videoUrl, it.audioUrl.orEmpty()).joinToString("|") },
            durationSeconds = info.duration,
        )
    }

    private fun chooseVideo(streams: List<VideoStream>): VideoStream? {
        val playable = streams.filter {
            it.isUrl && it.content.isNotBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        if (playable.isEmpty()) return null
        val preferred = playable.filter { it.format == MediaFormat.MPEG_4 }.ifEmpty { playable }
        val sane = preferred.filter { effectiveHeight(it) in 144..1080 }.ifEmpty { preferred }
        return sane.maxWithOrNull(compareBy<VideoStream>({ effectiveHeight(it) }, { it.bitrate }))
    }

    private fun chooseAudio(streams: List<AudioStream>): AudioStream? {
        val playable = streams.filter {
            it.isUrl && it.content.isNotBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        if (playable.isEmpty()) return null
        val preferred = playable.filter { it.format == MediaFormat.M4A }.ifEmpty { playable }
        return preferred.maxWithOrNull(compareBy<AudioStream>({ it.averageBitrate }, { it.bitrate }))
    }

    private fun effectiveHeight(stream: VideoStream): Int {
        if (stream.height > 0) return stream.height
        return Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
            .find(stream.resolution)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun displayResolution(stream: VideoStream): String = stream.resolution.ifBlank {
        effectiveHeight(stream).takeIf { it > 0 }?.let { "${it}p" } ?: "video"
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

    private fun shortError(error: Throwable?): String {
        if (error == null) return "unknown"
        return (error.message ?: error.javaClass.simpleName)
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .take(280)
    }

    override fun close() {
        poTokenResolver?.close()
        executor.shutdownNow()
    }
}
