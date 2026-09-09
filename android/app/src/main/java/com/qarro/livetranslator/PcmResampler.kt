package com.qarro.livetranslator

object PcmResampler {
    /**
     * Fast MVP downsampler for mono PCM16 48 kHz -> 24 kHz.
     * A production version should use a proper low-pass resampler.
     */
    fun downsample48kTo24k(input: ShortArray, length: Int): ShortArray {
        val outputLength = length / 2
        val output = ShortArray(outputLength)
        var src = 0
        var dst = 0
        while (dst < outputLength) {
            val a = input[src].toInt()
            val b = input[(src + 1).coerceAtMost(length - 1)].toInt()
            output[dst] = ((a + b) / 2).toShort()
            src += 2
            dst += 1
        }
        return output
    }

    fun shortsToLittleEndianBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var j = 0
        for (sample in samples) {
            val v = sample.toInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v ushr 8) and 0xFF).toByte()
        }
        return out
    }
}
