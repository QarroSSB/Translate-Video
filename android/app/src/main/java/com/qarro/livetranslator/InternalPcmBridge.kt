package com.qarro.livetranslator

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

/**
 * Converts decoded player PCM to 24 kHz mono PCM16 and forwards it to the translation client.
 * Nothing is captured from Android: the source is the PCM already decoded by ExoPlayer.
 */
class InternalPcmBridge(
    private val onFirstAudio: () -> Unit = {},
    private val onMetric: (String) -> Unit = {},
) : Closeable {
    private data class Packet(val bytes: ByteArray, val sampleRate: Int, val channels: Int)

    companion object {
        private const val TARGET_RATE = 24_000
        private const val TARGET_CHUNK_BYTES = 4_800 // 100 ms, mono PCM16 @ 24 kHz
    }

    private val queue = ArrayBlockingQueue<Packet>(24)
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(true)
    private val firstAudioReported = AtomicBoolean(false)
    @Volatile private var client: AudioTranslationClient? = null
    private var dropped = 0

    init {
        executor.execute { consumeLoop() }
    }

    fun attachClient(newClient: AudioTranslationClient?) {
        client = newClient
    }

    fun offer(bytes: ByteArray, sampleRate: Int, channels: Int) {
        if (!running.get() || bytes.isEmpty() || sampleRate <= 0 || channels <= 0) return
        if (firstAudioReported.compareAndSet(false, true)) onFirstAudio()
        if (!queue.offer(Packet(bytes, sampleRate, channels))) {
            queue.poll()
            queue.offer(Packet(bytes, sampleRate, channels))
            dropped++
            if (dropped == 1 || dropped % 25 == 0) {
                onMetric("внутренний PCM: отброшено блоков $dropped")
            }
        }
    }

    private fun consumeLoop() {
        val accumulator = ByteArrayOutputStream(TARGET_CHUNK_BYTES * 3)
        while (running.get()) {
            val packet = try {
                queue.take()
            } catch (_: InterruptedException) {
                break
            }
            val converted = convertTo24kMono(packet)
            accumulator.write(converted)
            val all = accumulator.toByteArray()
            var offset = 0
            while (all.size - offset >= TARGET_CHUNK_BYTES) {
                val chunk = all.copyOfRange(offset, offset + TARGET_CHUNK_BYTES)
                client?.sendPcm24k(chunk)
                offset += TARGET_CHUNK_BYTES
            }
            accumulator.reset()
            if (offset < all.size) accumulator.write(all, offset, all.size - offset)
        }
    }

    private fun convertTo24kMono(packet: Packet): ByteArray {
        val frameBytes = packet.channels * 2
        val frames = packet.bytes.size / frameBytes
        if (frames <= 0) return ByteArray(0)

        val mono = ShortArray(frames)
        var bytePos = 0
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until packet.channels) {
                val lo = packet.bytes[bytePos].toInt() and 0xff
                val hi = packet.bytes[bytePos + 1].toInt()
                sum += ((hi shl 8) or lo).toShort().toInt()
                bytePos += 2
            }
            mono[frame] = (sum / packet.channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val outFrames = ((frames.toLong() * TARGET_RATE) / packet.sampleRate).toInt().coerceAtLeast(1)
        val out = ByteArray(outFrames * 2)
        val step = packet.sampleRate.toDouble() / TARGET_RATE.toDouble()
        for (i in 0 until outFrames) {
            val src = floor(i * step).toInt().coerceIn(0, frames - 1)
            val sample = mono[src].toInt()
            out[i * 2] = (sample and 0xff).toByte()
            out[i * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
        }
        return out
    }

    override fun close() {
        running.set(false)
        queue.clear()
        executor.shutdownNow()
        client = null
    }
}
