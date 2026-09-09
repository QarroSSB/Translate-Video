package com.qarro.livetranslator

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class PlaybackCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_TARGET_LANGUAGE = "target_language"
        const val EXTRA_MODE = "mode"
        const val EXTRA_CHUNK_MS = "chunk_ms"
        const val EXTRA_SHOW_OVERLAY = "show_overlay"
        const val EXTRA_TRANSLATION_VOLUME = "translation_volume"
        const val EXTRA_DUCK_ORIGINAL = "duck_original"
        private const val CHANNEL_ID = "qarro_live_translation"
        private const val NOTIFICATION_ID = 71
        private const val ACTION_STOP = "com.qarro.livetranslator.STOP"
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var socket: TranslationSocket? = null
    private var player: PcmAudioPlayer? = null
    private var overlay: SubtitleOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val foregroundType = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Подготовка перевода…"),
            foregroundType,
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val endpoint = intent?.getStringExtra(EXTRA_ENDPOINT) ?: "ws://10.0.2.2:8765/ws/translate"
        val target = intent?.getStringExtra(EXTRA_TARGET_LANGUAGE) ?: "ru"
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: "live"
        val chunkMs = intent?.getIntExtra(EXTRA_CHUNK_MS, 6000)?.coerceIn(3000, 12000) ?: 6000
        val showOverlay = intent?.getBooleanExtra(EXTRA_SHOW_OVERLAY, true) ?: true
        val translationVolume = intent?.getIntExtra(EXTRA_TRANSLATION_VOLUME, 100)?.coerceIn(0, 100) ?: 100
        val duckOriginal = intent?.getBooleanExtra(EXTRA_DUCK_ORIGINAL, false) ?: false

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startTranslation(
            resultCode, resultData, endpoint, target, mode, chunkMs, showOverlay,
            translationVolume, duckOriginal
        )
        return START_NOT_STICKY
    }

    private fun startTranslation(
        resultCode: Int,
        resultData: Intent,
        endpoint: String,
        target: String,
        mode: String,
        chunkMs: Int,
        showOverlay: Boolean,
        translationVolume: Int,
        duckOriginal: Boolean,
    ) {
        if (!running.compareAndSet(false, true)) return

        if (showOverlay && Settings.canDrawOverlays(this)) {
            overlay = SubtitleOverlay(this).also { it.show() }
        }
        player = PcmAudioPlayer(this, translationVolume, duckOriginal)
        socket = TranslationSocket(
            endpoint = endpoint,
            targetLanguage = target,
            mode = mode,
            chunkMs = chunkMs,
            onAudio = { bytes -> player?.write(bytes) },
            onTargetTranscript = { delta -> overlay?.append(delta) },
            onSpeakerLine = { speaker, emotion, translation ->
                overlay?.showSpeakerLine(speaker, emotion, translation)
            },
            onStatus = { state ->
                if (state == "connected") overlay?.setStatus("Перевод подключён…")
                updateNotification("Перевод: $state")
            },
            onMetric = { metric ->
                updateNotification("Перевод • $metric")
            },
            onError = { message ->
                overlay?.setStatus("Ошибка: $message")
                updateNotification("Ошибка: ${message.take(50)}")
            },
        ).also { it.connect() }

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, resultData).also { mediaProjection ->
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, null)
        }

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val sampleRate = 48_000
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes((minBuffer * 4).coerceAtLeast(48_000))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        executor.execute {
            val local = recorder ?: return@execute
            val buffer = ShortArray(4_800) // 100 ms @ 48 kHz
            var blocks = 0
            var audibleBlocks = 0
            try {
                local.startRecording()
                updateNotification("Перевод активен • $mode")
                while (running.get()) {
                    val count = local.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        blocks++
                        var peak = 0
                        for (i in 0 until count) peak = maxOf(peak, abs(buffer[i].toInt()))
                        if (peak > 40) audibleBlocks++

                        val downsampled = PcmResampler.downsample48kTo24k(buffer, count)
                        socket?.sendPcm24k(PcmResampler.shortsToLittleEndianBytes(downsampled))

                        if (blocks == 50 && audibleBlocks == 0) {
                            overlay?.setStatus("Входной звук не обнаружен. Возможно, YouTube запрещает захват аудио.")
                            updateNotification("Не обнаружен звук приложения")
                        }
                    }
                }
            } catch (t: Throwable) {
                overlay?.setStatus("Ошибка захвата: ${t.message}")
                updateNotification("Ошибка захвата аудио")
            } finally {
                runCatching { local.stop() }
            }
        }
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        socket?.close()
        socket = null
        player?.release()
        player = null
        overlay?.hide()
        overlay = null
        projection?.stop()
        projection = null
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.channel_description) }
            )
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Qarro Live Translator")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(
            android.R.drawable.ic_media_pause,
            "Остановить",
            PendingIntent.getService(
                this,
                1,
                Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
