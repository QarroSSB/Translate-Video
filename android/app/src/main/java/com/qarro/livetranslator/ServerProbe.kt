package com.qarro.livetranslator

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class ServerProbe {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class Health(
        val version: String,
        val apiKeyConfigured: Boolean,
        val modes: String,
    )

    fun check(webSocketEndpoint: String, callback: (Result<Health>) -> Unit) {
        val url = runCatching { healthUrl(webSocketEndpoint) }
            .getOrElse {
                callback(Result.failure(it))
                return
            }
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("HTTP ${it.code}")))
                        return
                    }
                    runCatching {
                        val json = JSONObject(it.body?.string().orEmpty())
                        Health(
                            version = json.optString("version", "?"),
                            apiKeyConfigured = json.optBoolean("api_key_configured", false),
                            modes = json.optJSONArray("modes")?.let { array ->
                                (0 until array.length()).joinToString(", ") { i -> array.optString(i) }
                            }.orEmpty(),
                        )
                    }.also(callback)
                }
            }
        })
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        fun healthUrl(webSocketEndpoint: String): String {
            val uri = URI(webSocketEndpoint.trim())
            val scheme = when (uri.scheme?.lowercase()) {
                "ws" -> "http"
                "wss" -> "https"
                else -> throw IllegalArgumentException("Адрес должен начинаться с ws:// или wss://")
            }
            require(!uri.host.isNullOrBlank()) { "В адресе сервера не найден host" }
            return URI(scheme, null, uri.host, uri.port, "/health", null, null).toString()
        }
    }
}
