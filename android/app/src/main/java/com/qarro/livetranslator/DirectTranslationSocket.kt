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

class DirectTranslationSocket(
    private val apiKey: String,
    private val targetLanguage: String,
    private val onAudio: (ByteArray) -> Unit,
    private val onTargetTranscript: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onMetric: (String) -> Unit,
    private val onError: (String) -> Unit,
) : AudioTranslationClient {
    companion object {
        private const val MAX_SOCKET_QUEUE_BYTES = 256L * 1024L
        private const val ENDPOINT =
            "wss://api.openai.com/v1/realtime/translations?model=gpt-realtime-translate"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private val opened = AtomicBoolean(false)
    private val droppedAudioFrames = AtomicInteger(0)

    override fun connect() {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            onError("OpenAI API key не задан")
            return
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $cleanKey")
            .header("OpenAI-Safety-Identifier", "qarro-live-translator-android")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                droppedAudioFrames.set(0)
                webSocket.send(
                    JSONObject()
                        .put("type", "session.update")
                        .put(
                            "session",
                            JSONObject().put(
                                "audio",
                                JSONObject().put(
                                    "output",
                                    JSONObject().put("language", targetLanguage)
                                )
                            )
                        )
                        .toString()
                )
                onStatus("connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrElse {
                    onError("Некорректный ответ OpenAI")
                    return
                }
                when (json.optString("type")) {
                    "session.created" -> onStatus("connected")
                    "session.updated" -> onStatus("ready")
                    "session.output_audio.delta" -> {
                        val encoded = json.optString("delta")
                        if (encoded.isNotEmpty()) {
                            runCatching { Base64.decode(encoded, Base64.DEFAULT) }
                                .onSuccess(onAudio)
                                .onFailure { onError("Не удалось декодировать русский звук") }
                        }
                    }
                    "session.output_transcript.delta" -> {
                        onTargetTranscript(json.optString("delta"))
                    }
                    "session.closed" -> {
                        opened.set(false)
                        onStatus("closed")
                    }
                    "error" -> {
                        val error = json.optJSONObject("error")
                        onError(error?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: "Ошибка OpenAI Realtime")
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                opened.set(false)
                onStatus("closed")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.set(false)
                val suffix = response?.code?.let { " (HTTP $it)" }.orEmpty()
                onError((t.message ?: "WebSocket failure") + suffix)
            }
        })
    }

    override fun sendPcm24k(bytes: ByteArray): Boolean {
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
                .put("type", "session.input_audio_buffer.append")
                .put("audio", encoded)
                .toString()
        )
    }

    override fun close() {
        if (opened.get()) {
            socket?.send(JSONObject().put("type", "session.close").toString())
        }
        socket?.close(1000, "stop")
        opened.set(false)
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
