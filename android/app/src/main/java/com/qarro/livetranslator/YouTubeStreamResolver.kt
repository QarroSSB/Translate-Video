package com.qarro.livetranslator

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
 * Resolves a normal YouTube watch/share URL to one or more playable variants.
 * Modern YouTube commonly exposes video and audio as separate adaptive streams,
 * so we keep those instead of requiring a legacy video+audio progressive URL.
 */
class YouTubeStreamResolver : Closeable {
    companion object {
        private val initialized = AtomicBoolean(false)

        @Synchronized
        private fun ensureInitialized() {
            if (initialized.compareAndSet(false, true)) {
                NewPipe.init(QarroDownloader())
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun resolve(url: String, callback: (Result<ResolvedYouTubeStream>) -> Unit) {
        executor.execute {
            callback(runCatching { resolveBlocking(url) })
        }
    }

    private fun resolveBlocking(url: String): ResolvedYouTubeStream {
        ensureInitialized()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val variants = mutableListOf<YouTubePlaybackVariant>()

        // Modern YouTube path: adaptive video + adaptive audio. Prefer MP4/M4A and <=1080p
        // for broad Android hardware-decoder compatibility and predictable bandwidth.
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

        // Legacy progressive stream remains a useful fallback on videos where YouTube still
        // exposes one URL containing both video and audio.
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

        // Manifest fallbacks are especially useful for live streams and some regional variants.
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

    override fun close() {
        executor.shutdownNow()
    }
}
