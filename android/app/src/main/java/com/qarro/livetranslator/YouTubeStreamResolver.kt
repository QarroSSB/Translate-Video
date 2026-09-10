package com.qarro.livetranslator

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ResolvedYouTubeStream(
    val title: String,
    val streamUrl: String,
    val resolution: String,
    val durationSeconds: Long,
)

/** Resolves a normal YouTube watch/share URL to a directly playable progressive stream. */
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

        val progressive = info.videoStreams
            .asSequence()
            .filter { it.isUrl && !it.isVideoOnly }
            .maxWithOrNull(compareBy({ it.height }, { it.bitrate }))

        if (progressive != null) {
            return ResolvedYouTubeStream(
                title = info.name,
                streamUrl = progressive.content,
                resolution = progressive.resolution.ifBlank { "progressive" },
                durationSeconds = info.duration,
            )
        }

        val hls = info.hlsUrl
        if (!hls.isNullOrBlank()) {
            return ResolvedYouTubeStream(
                title = info.name,
                streamUrl = hls,
                resolution = "HLS",
                durationSeconds = info.duration,
            )
        }

        throw IllegalStateException("Не найден поток YouTube с видео и звуком")
    }

    override fun close() {
        executor.shutdownNow()
    }
}
