package com.qarro.livetranslator

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Minimal blocking downloader used by NewPipeExtractor on a background thread. */
class QarroDownloader : Downloader() {
    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        @Volatile
        var lastDiagnostic: String = "network: no request yet"
            private set
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(null)
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)
            // Our lightweight downloader does not install NewPipe's Brotli interceptor.
            // Ask for an uncompressed response so the extractor always receives readable text.
            .header("Accept-Encoding", "identity")

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }
        if (builder.build().header("Accept-Encoding").isNullOrBlank()) {
            builder.header("Accept-Encoding", "identity")
        }

        val host = runCatching { URI(request.url()).host ?: "unknown-host" }.getOrDefault("unknown-host")
        lastDiagnostic = "${request.httpMethod()} $host • waiting"

        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string()
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';')
            lastDiagnostic =
                "${request.httpMethod()} $host • HTTP ${response.code}" +
                    if (contentType.isBlank()) "" else " • $contentType"

            if (response.code == 429) {
                throw IOException("YouTube/Google вернул HTTP 429 (слишком много запросов / anti-bot)")
            }

            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                text,
                response.request.url.toString(),
            )
        }
    }
}
