package com.qarro.livetranslator

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
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
        private const val MAX_RECONNECT_ATTEMPTS = 6
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var socket: WebSocket? = null
    private val opened = AtomicBoolean(false)
    private val manuallyClosed = AtomicBoolean(false)
    private val terminalFailure = AtomicBoolean(false)
    private val reconnectScheduled = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val droppedAudioFrames = AtomicInteger(0)

    override fun connect() {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            onError("OpenAI API key не задан")
            return
        }
        manuallyClosed.set(false)
        terminalFailure.set(false)
        openSocket(cleanKey)
    }

    private fun openSocket(cleanKey: String) {
        if (manuallyClosed.get() || terminalFailure.get()) return
        reconnectScheduled.set(false)
        opened.set(false)
        onStatus(if (reconnectAttempts.get() == 0) "connecting" else "reconnecting")

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $cleanKey")
            .header("OpenAI-Safety-Identifier", "qarro-live-translator-android")
            .build()

        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== socket || manuallyClosed.get()) {
                    webSocket.close(1000, "stale")
                    return
                }
                opened.set(true)
                reconnectAttempts.set(0)
                droppedAudioFrames.set(0)

                val configured = webSocket.send(
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
                if (!configured) {
                    opened.set(false)
                    scheduleReconnect("не удалось настроить translation session")
                    return
                }
                onStatus("connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket) return
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
                        if (!manuallyClosed.get() && !terminalFailure.get()) {
                            scheduleReconnect("translation session закрыта сервером")
                        }
                    }
                    "error" -> handleApiError(webSocket, json)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== socket) return
                opened.set(false)
                onStatus("closing")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== socket) return
                opened.set(false)
                if (manuallyClosed.get()) {
                    onStatus("closed")
                    return
                }
                if (!terminalFailure.get()) {
                    val detail = reason.takeIf { it.isNotBlank() } ?: "код $code"
                    scheduleReconnect("соединение закрыто ($detail)")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
                opened.set(false)
                if (manuallyClosed.get()) return

                val httpCode = response?.code
                if (httpCode != null && httpCode in listOf(401, 403, 429)) {
                    terminalFailure.set(true)
                    onError(httpFailureMessage(httpCode))
                    onStatus("blocked")
                    return
                }

                val raw = t.message.orEmpty()
                val normalized = raw.lowercase(Locale.ROOT)
                val transientNetworkError = normalized.contains("broken pipe") ||
                    normalized.contains("connection reset") ||
                    normalized.contains("socket closed") ||
                    normalized.contains("timeout") ||
                    normalized.contains("eof")

                if (transientNetworkError || response == null) {
                    scheduleReconnect("сеть оборвалась")
                } else {
                    val suffix = httpCode?.let { " (HTTP $it)" }.orEmpty()
                    onError((raw.ifBlank { "WebSocket failure" }) + suffix)
                }
            }
        })
        socket = newSocket
    }

    private fun handleApiError(webSocket: WebSocket, json: JSONObject) {
        val error = json.optJSONObject("error")
        val message = error?.optString("message").orEmpty()
        val code = error?.optString("code").orEmpty()
        val type = error?.optString("type").orEmpty()
        val combined = "$code $type $message".lowercase(Locale.ROOT)

        val billingOrAuth = combined.contains("quota") ||
            combined.contains("billing") ||
            combined.contains("credit") ||
            combined.contains("insufficient") ||
            combined.contains("unauthorized") ||
            combined.contains("invalid_api_key") ||
            combined.contains("permission") ||
            combined.contains("tier")

        if (billingOrAuth) {
            terminalFailure.set(true)
            opened.set(false)
            onError(
                message.ifBlank {
                    "OpenAI API отклонил запрос: проверь API billing, кредиты и доступ к gpt-realtime-translate"
                }
            )
            onStatus("blocked")
            webSocket.close(1000, "terminal api error")
        } else {
            onError(message.ifBlank { "Ошибка OpenAI Realtime${code.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}" })
        }
    }

    private fun httpFailureMessage(code: Int): String = when (code) {
        401 -> "OpenAI API: ключ отклонён (HTTP 401). Проверь, что сохранён действующий API key."
        403 -> "OpenAI API: нет доступа к Realtime Translation (HTTP 403). Проверь API project/billing."
        429 -> "OpenAI API: нет доступного лимита/кредита либо превышен rate limit (HTTP 429). Для gpt-realtime-translate Free API tier не поддерживается."
        else -> "OpenAI API: HTTP $code"
    }

    private fun scheduleReconnect(reason: String) {
        if (manuallyClosed.get() || terminalFailure.get()) return
        if (!reconnectScheduled.compareAndSet(false, true)) return

        opened.set(false)
        val attempt = reconnectAttempts.incrementAndGet()
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            reconnectScheduled.set(false)
            terminalFailure.set(true)
            onStatus("offline")
            onError("Соединение с OpenAI несколько раз оборвалось. Проверь интернет и API billing.")
            return
        }

        val delaySeconds = when (attempt) {
            1 -> 1L
            2 -> 2L
            3 -> 4L
            else -> 8L
        }
        onStatus("reconnect ${delaySeconds}s")
        onMetric("$reason • переподключение #$attempt через ${delaySeconds}с")
        scheduler.schedule({
            if (!manuallyClosed.get() && !terminalFailure.get()) {
                openSocket(apiKey.trim())
            }
        }, delaySeconds, TimeUnit.SECONDS)
    }

    override fun sendPcm24k(bytes: ByteArray): Boolean {
        if (!opened.get() || manuallyClosed.get() || terminalFailure.get()) return false
        val ws = socket ?: return false
        if (ws.queueSize() > MAX_SOCKET_QUEUE_BYTES) {
            val dropped = droppedAudioFrames.incrementAndGet()
            if (dropped == 1 || dropped % 20 == 0) {
                onMetric("сеть не успевает • пропущено аудиоблоков: $dropped")
            }
            return false
        }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val sent = ws.send(
            JSONObject()
                .put("type", "session.input_audio_buffer.append")
                .put("audio", encoded)
                .toString()
        )
        if (!sent) {
            opened.set(false)
            scheduleReconnect("WebSocket перестал принимать аудио")
        }
        return sent
    }

    override fun close() {
        if (!manuallyClosed.compareAndSet(false, true)) return
        terminalFailure.set(true)
        opened.set(false)
        reconnectScheduled.set(false)
        socket?.close(1000, "stop")
        socket = null
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
