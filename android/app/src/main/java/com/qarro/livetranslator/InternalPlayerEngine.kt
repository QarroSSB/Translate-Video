package com.qarro.livetranslator

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
                        Player.STATE_READY -> onStatus("Видео готово • внутренний аудиотракт активен")
                        Player.STATE_ENDED -> onStatus("Видео завершено")
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    onStatus("Ошибка плеера: ${error.message ?: error.errorCodeName}")
                }
            })
        }

    fun attach(playerView: PlayerView) {
        playerView.player = player
        playerView.useController = true
        playerView.keepScreenOn = true
    }

    fun load(stream: ResolvedYouTubeStream) {
        val item = MediaItem.Builder()
            .setUri(stream.streamUrl)
            .setMediaId(stream.title)
            .build()

        if (stream.resolution == "HLS" || stream.streamUrl.contains(".m3u8", ignoreCase = true)) {
            player.setMediaItem(item)
        } else {
            val dataSource = DefaultHttpDataSource.Factory()
                .setUserAgent(QarroDownloader.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
            player.setMediaSource(ProgressiveMediaSource.Factory(dataSource).createMediaSource(item))
        }
        player.prepare()
        player.playWhenReady = false
    }

    fun setOriginalVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    fun release() {
        player.release()
    }
}
