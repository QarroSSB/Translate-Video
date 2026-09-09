package com.qarro.livetranslator

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TranslationSocket(
    private val endpoint: String,
    private val targetLanguage: String,
    private val mode: String,
    private val chunkMs: Int,
    private val onAudio: (ByteArray) -> Unit,
    private val onTargetTranscript: (String) -> Unit,
    private val onSpeakerLine: (speaker: String, emotion: String, translation: String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onMetric: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        // ~4 seconds of 24 kHz mono PCM16 after base64/JSON overhead. Beyond this,
        // sending more audio would mostly create stale translation rather than useful latency.
        private const val MAX_SOCKET_QUEUE_BYTES = 256L * 1024L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private val opened = AtomicBoolean(false)
    private val droppedAudioFrames = AtomicInteger(0)

    fun connect() {
        val request = Request.Builder().url(endpoint).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                droppedAudioFrames.set(0)
                webSocket.send(
                    JSONObject()
                        .put("type", "start")
                        .put("target_language", targetLanguage)
                        .put("mode", mode)
                        .put("chunk_ms", chunkMs)
                        .toString()
                )
                onStatus("connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "translated_audio" -> {
                        val encoded = json.optString("audio")
                        if (encoded.isNotEmpty()) onAudio(Base64.decode(encoded, Base64.DEFAULT))
                    }
                    "target_transcript" -> onTargetTranscript(json.optString("delta"))
                    "speaker_line" -> onSpeakerLine(
                        json.optString("speaker", "Speaker"),
                        json.optString("emotion", "neutral"),
                        json.optString("translation", "")
                    )
                    "metrics" -> onMetric(
                        "обработка ${json.optLong("processing_ms")} мс • " +
                            "очередь ${json.optInt("queue_chunks")} • " +
                            "говорящих ${json.optInt("speaker_profiles")}"
                    )
                    "status" -> onStatus(json.optString("state"))
                    "error" -> onError(json.optString("message"))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                opened.set(false)
                onStatus("closed")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.set(false)
                onError(t.message ?: "WebSocket failure")
            }
        })
    }

    fun sendPcm24k(bytes: ByteArray): Boolean {
        if (!opened.get()) return false
        val ws = socket ?: return false
        if (ws.queueSize() > MAX_SOCKET_QUEUE_BYTES) {
            val dropped = droppedAudioFrames.incrementAndGet()
            if (dropped == 1 || dropped % 20 == 0) {
                onMetric("сеть не успевает • пропущено аудиоблоков: $dropped")
            }
            return false
        }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return ws.send(
            JSONObject()
                .put("type", "audio")
                .put("audio", encoded)
                .toString()
        )
    }

    fun close() {
        if (opened.get()) socket?.send(JSONObject().put("type", "stop").toString())
        socket?.close(1000, "stop")
        opened.set(false)
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
