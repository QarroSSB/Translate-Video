package com.qarro.livetranslator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PcmAudioPlayer(
    context: Context,
    volumePercent: Int,
    private val duckOriginal: Boolean,
) {
    private val sampleRate = 24_000
    private val minBuffer = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private val running = AtomicBoolean(true)
    private val queue = LinkedBlockingQueue<ByteArray>(96)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(focusAttributes)
        .setOnAudioFocusChangeListener { }
        .build()

    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes((minBuffer * 4).coerceAtLeast(24_000))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .apply {
            setVolume(volumePercent.coerceIn(0, 100) / 100f)
            play()
        }

    init {
        if (duckOriginal) runCatching { audioManager.requestAudioFocus(focusRequest) }
    }

    private val thread = Thread({
        while (running.get() || queue.isNotEmpty()) {
            val bytes = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
            var offset = 0
            while (offset < bytes.size && running.get()) {
                val written = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
        }
    }, "QarroTranslatedAudio").apply { start() }

    fun write(bytes: ByteArray) {
        if (bytes.isEmpty() || !running.get()) return
        if (!queue.offer(bytes)) {
            queue.poll() // Prefer current audio over an ever-growing stale queue.
            queue.offer(bytes)
        }
    }

    fun release() {
        if (!running.compareAndSet(true, false)) return
        thread.interrupt()
        runCatching { thread.join(500) }
        queue.clear()
        if (duckOriginal) runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        runCatching { track.stop() }
        track.release()
    }
}
