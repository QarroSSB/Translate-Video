package com.qarro.livetranslator

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Pass-through Media3 processor that mirrors decoded PCM into [InternalPcmBridge]. */
@UnstableApi
class PcmTapAudioProcessor(
    private val bridge: InternalPcmBridge,
) : BaseAudioProcessor() {
    private var currentFormat = AudioProcessor.AudioFormat.NOT_SET

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        currentFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val copy = inputBuffer.asReadOnlyBuffer()
        val bytes = ByteArray(remaining)
        copy.get(bytes)
        bridge.offer(bytes, currentFormat.sampleRate, currentFormat.channelCount)

        replaceOutputBuffer(remaining)
            .put(inputBuffer)
            .flip()
    }

    override fun onReset() {
        currentFormat = AudioProcessor.AudioFormat.NOT_SET
    }
}
