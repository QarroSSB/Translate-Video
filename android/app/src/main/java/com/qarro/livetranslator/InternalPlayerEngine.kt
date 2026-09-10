package com.qarro.livetranslator

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

/** Media3 player whose decoded PCM is mirrored to [InternalPcmBridge]. */
@UnstableApi
class InternalPlayerEngine(
    context: Context,
    private val bridge: InternalPcmBridge,
    private val onStatus: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val tapProcessor = PcmTapAudioProcessor(bridge)

    private var loadedStream: ResolvedYouTubeStream? = null
    private var variantIndex = 0
    private var fallbackInProgress = false

    private val renderersFactory = object : DefaultRenderersFactory(appContext) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessors(arrayOf(tapProcessor))
                .build()
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> onStatus("Буферизация видео…")
                        Player.STATE_READY -> {
                            fallbackInProgress = false
                            val variant = currentVariant()
                            val suffix = variant?.label?.let { " • $it" }.orEmpty()
                            onStatus("Видео готово$suffix • внутренний аудиотракт активен")
                        }
                        Player.STATE_ENDED -> onStatus("Видео завершено")
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val stream = loadedStream
                    val nextIndex = variantIndex + 1
                    if (!fallbackInProgress && stream != null && nextIndex < stream.variants.size) {
                        fallbackInProgress = true
                        val failed = currentVariant()?.label ?: "поток"
                        variantIndex = nextIndex
                        val next = currentVariant()
                        onStatus(
                            "Поток $failed не открылся (${error.errorCodeName}). " +
                                "Пробую ${next?.label ?: "резервный вариант"}…",
                        )
                        loadCurrentVariant(autoplay = true)
                        return
                    }

                    onStatus("Ошибка плеера: ${describeError(error)}")
                }
            })
        }

    fun attach(playerView: PlayerView) {
        playerView.player = player
        playerView.useController = true
        playerView.keepScreenOn = true
    }

    fun load(stream: ResolvedYouTubeStream) {
        loadedStream = stream
        variantIndex = 0
        fallbackInProgress = false
        loadCurrentVariant(autoplay = false)
    }

    private fun loadCurrentVariant(autoplay: Boolean) {
        val variant = currentVariant() ?: run {
            onStatus("Не найден совместимый источник видео")
            return
        }

        player.stop()
        player.clearMediaItems()
        player.setMediaSource(buildMediaSource(variant))
        player.prepare()
        player.playWhenReady = autoplay
        onStatus("Открываю ${variant.label}…")
    }

    private fun currentVariant(): YouTubePlaybackVariant? {
        return loadedStream?.variants?.getOrNull(variantIndex)
    }

    private fun buildMediaSource(variant: YouTubePlaybackVariant): MediaSource {
        val httpDataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(QarroDownloader.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com",
                    "Accept" to "*/*",
                ),
            )

        return when (variant.mode) {
            YouTubePlaybackMode.COMBINED -> {
                ProgressiveMediaSource.Factory(httpDataSource)
                    .createMediaSource(mediaItem(variant.videoUrl, variant.videoMimeType, "combined"))
            }

            YouTubePlaybackMode.SEPARATE -> {
                val videoSource = ProgressiveMediaSource.Factory(httpDataSource)
                    .createMediaSource(mediaItem(variant.videoUrl, variant.videoMimeType, "video"))
                val audioUrl = requireNotNull(variant.audioUrl)
                val audioSource = ProgressiveMediaSource.Factory(httpDataSource)
                    .createMediaSource(mediaItem(audioUrl, variant.audioMimeType, "audio"))
                MergingMediaSource(true, videoSource, audioSource)
            }

            YouTubePlaybackMode.HLS -> {
                HlsMediaSource.Factory(httpDataSource)
                    .createMediaSource(mediaItem(variant.videoUrl, null, "hls"))
            }

            YouTubePlaybackMode.DASH -> {
                DashMediaSource.Factory(httpDataSource)
                    .createMediaSource(mediaItem(variant.videoUrl, null, "dash"))
            }
        }
    }

    private fun mediaItem(url: String, mimeType: String?, id: String): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(url)
            .setMediaId(id)
        if (!mimeType.isNullOrBlank()) builder.setMimeType(mimeType)
        return builder.build()
    }

    private fun describeError(error: PlaybackException): String {
        var cause: Throwable = error
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        val detail = cause.message?.take(180).orEmpty()
        return if (detail.isBlank()) error.errorCodeName else "${error.errorCodeName} • $detail"
    }

    fun setOriginalVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    fun release() {
        player.release()
    }
}
