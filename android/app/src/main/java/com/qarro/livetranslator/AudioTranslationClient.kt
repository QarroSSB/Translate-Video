package com.qarro.livetranslator

interface AudioTranslationClient {
    fun connect()
    fun sendPcm24k(bytes: ByteArray): Boolean
    fun close()
}
