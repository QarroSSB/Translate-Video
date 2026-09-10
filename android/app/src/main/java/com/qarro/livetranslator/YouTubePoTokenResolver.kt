package com.qarro.livetranslator

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper
import java.io.Closeable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device WEB_EMBEDDED fallback for YouTube's anonymous anti-bot gate.
 * The BotGuard/WebView flow follows the public NewPipe approach (GPL-3.0-or-later).
 */
class YouTubePoTokenResolver(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var session: WebPoTokenSession? = null
    private var cachedVisitorData: String? = null
    private var cachedToken: String? = null

    fun resolve(url: String, onProgress: (String) -> Unit = {}): ResolvedYouTubeStream {
        val videoId = YouTubeUrlParser.extractVideoId(url)
            ?: throw IllegalArgumentException("PO Token: не удалось определить videoId")

        onProgress("PO Token: получаю visitorData…")
        val visitorData = obtainEmbeddedVisitorData(videoId)
        onProgress("PO Token: запускаю BotGuard в локальном WebView…")
        val tokenResult = obtainEmbeddedToken(visitorData)
        onProgress("PO Token: токен получен ✓ • запрашиваю WEB_EMBEDDED…")

        val cpn = YoutubeParsingHelper.generateContentPlaybackNonce()
        val signatureTimestamp = YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        val response = YoutubeStreamHelper.getWebEmbeddedPlayerResponse(
            NewPipe.getPreferredLocalization(),
            NewPipe.getPreferredContentCountry(),
            videoId,
            cpn,
            tokenResult,
            signatureTimestamp,
        )
        checkPlayability(response)
        val result = buildResolvedStream(response, videoId, cpn, tokenResult.streamingDataPoToken)
        onProgress("PO Token: WEB_EMBEDDED поток найден ✓")
        return result
    }

    private fun obtainEmbeddedVisitorData(videoId: String): String {
        val requestInfo = InnertubeClientRequestInfo.ofWebEmbeddedPlayerClient()
        val embedUrl = "https://www.youtube.com/watch?v=$videoId"
        val headers = HashMap(
            YoutubeParsingHelper.getClientHeaders(
                requestInfo.clientInfo.clientId,
                requestInfo.clientInfo.clientVersion,
            ),
        )
        headers.putAll(YoutubeParsingHelper.getOriginReferrerHeaders("https://www.youtube.com"))
        return YoutubeParsingHelper.getVisitorDataFromInnertube(
            requestInfo,
            NewPipe.getPreferredLocalization(),
            NewPipe.getPreferredContentCountry(),
            headers,
            YoutubeParsingHelper.YOUTUBEI_V1_URL,
            embedUrl,
            false,
        )
    }

    private fun obtainEmbeddedToken(visitorData: String): PoTokenResult = synchronized(lock) {
        var current = session
        if (current == null || current.isExpired() || cachedVisitorData != visitorData) {
            current?.close()
            current = WebPoTokenSession(appContext)
            current.awaitReady()
            session = current
            cachedVisitorData = visitorData
            cachedToken = current.generate(visitorData)
        }
        val token = cachedToken ?: throw IllegalStateException("PO Token: пустой embedded token")
        // WEB_EMBEDDED v0.26.5 uses one visitorData-minted token for player + stream URLs.
        PoTokenResult(visitorData, token, token)
    }

    private fun checkPlayability(response: JsonObject) {
        val playability = response.getObject("playabilityStatus")
        val status = playability.getString("status", "")
        if (status.isBlank() || status.equals("OK", ignoreCase = true)) return
        val reason = playability.getString("reason", "")
        throw IllegalStateException(
            "PO Token WEB_EMBEDDED: $status${if (reason.isBlank()) "" else " • $reason"}",
        )
    }

    private data class Candidate(
        val url: String,
        val mimeType: String,
        val height: Int,
        val bitrate: Int,
    )

    private fun buildResolvedStream(
        response: JsonObject,
        videoId: String,
        cpn: String,
        streamingToken: String?,
    ): ResolvedYouTubeStream {
        val data = response.getObject("streamingData")
        val progressive = parseCandidates(data.getArray("formats"), videoId, cpn, streamingToken)
        val adaptive = parseCandidates(data.getArray("adaptiveFormats"), videoId, cpn, streamingToken)
        val variants = mutableListOf<YouTubePlaybackVariant>()

        val video = chooseVideo(adaptive.filter { it.mimeType.startsWith("video/") })
        val audio = chooseAudio(adaptive.filter { it.mimeType.startsWith("audio/") })
        if (video != null && audio != null) {
            variants += YouTubePlaybackVariant(
                mode = YouTubePlaybackMode.SEPARATE,
                videoUrl = video.url,
                audioUrl = audio.url,
                videoMimeType = video.mimeType,
                audioMimeType = audio.mimeType,
                label = "PO Token • WEB_EMBEDDED • ${video.height.takeIf { it > 0 }?.let { "${it}p" } ?: "adaptive"}",
            )
        }

        chooseVideo(progressive.filter { it.mimeType.startsWith("video/") })?.let { combined ->
            variants += YouTubePlaybackVariant(
                mode = YouTubePlaybackMode.COMBINED,
                videoUrl = combined.url,
                videoMimeType = combined.mimeType,
                label = "PO Token • WEB_EMBEDDED • ${combined.height.takeIf { it > 0 }?.let { "${it}p" } ?: "progressive"}",
            )
        }

        data.getString("hlsManifestUrl", "").takeIf { it.isNotBlank() }?.let { hls ->
            variants += YouTubePlaybackVariant(
                mode = YouTubePlaybackMode.HLS,
                videoUrl = appendQueryIfMissing(hls, "pot", streamingToken),
                label = "PO Token • WEB_EMBEDDED • HLS",
            )
        }
        data.getString("dashManifestUrl", "").takeIf { it.isNotBlank() }?.let { dash ->
            variants += YouTubePlaybackVariant(
                mode = YouTubePlaybackMode.DASH,
                videoUrl = appendQueryIfMissing(dash, "pot", streamingToken),
                label = "PO Token • WEB_EMBEDDED • DASH",
            )
        }

        if (variants.isEmpty()) {
            throw IllegalStateException(
                "PO Token получен, но WEB_EMBEDDED не вернул playable URL " +
                    "(formats=${progressive.size}, adaptive=${adaptive.size})",
            )
        }

        val details = response.getObject("videoDetails")
        return ResolvedYouTubeStream(
            title = details.getString("title", "YouTube video"),
            variants = variants.distinctBy { listOf(it.mode, it.videoUrl, it.audioUrl).joinToString("|") },
            durationSeconds = details.getString("lengthSeconds", "0").toLongOrNull() ?: 0L,
        )
    }

    private fun parseCandidates(
        formats: JsonArray,
        videoId: String,
        cpn: String,
        token: String?,
    ): List<Candidate> {
        val out = ArrayList<Candidate>(formats.size)
        for (i in 0 until formats.size) {
            val format = formats.getObject(i)
            val mime = format.getString("mimeType", "").substringBefore(';')
            if (mime.isBlank()) continue
            val url = runCatching { resolveFormatUrl(format, videoId, cpn, token) }.getOrNull() ?: continue
            out += Candidate(
                url = url,
                mimeType = mime,
                height = format.getInt("height", 0),
                bitrate = format.getInt("bitrate", 0),
            )
        }
        return out
    }

    private fun resolveFormatUrl(
        format: JsonObject,
        videoId: String,
        cpn: String,
        token: String?,
    ): String? {
        var url = format.getString("url", "")
        if (url.isBlank()) {
            val cipher = format.getString("cipher", format.getString("signatureCipher", ""))
            if (cipher.isBlank()) return null
            val values = parseQuery(cipher)
            url = values["url"].orEmpty()
            if (url.isBlank()) return null
            val s = values["s"]
            if (!s.isNullOrBlank()) {
                val signature = YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, s)
                url = appendQuery(url, values["sp"].orEmpty().ifBlank { "signature" }, signature)
            }
        }
        url = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        url = appendQueryIfMissing(url, "cpn", cpn)
        url = appendQueryIfMissing(url, "pot", token)
        return url
    }

    private fun chooseVideo(items: List<Candidate>): Candidate? {
        val sane = items.filter { it.height in 144..1080 }.ifEmpty { items }
        return sane.maxWithOrNull(
            compareBy<Candidate>({ if (it.mimeType == "video/mp4") 1 else 0 }, { it.height }, { it.bitrate }),
        )
    }

    private fun chooseAudio(items: List<Candidate>): Candidate? = items.maxWithOrNull(
        compareBy<Candidate>({ if (it.mimeType == "audio/mp4") 1 else 0 }, { it.bitrate }),
    )

    private fun appendQueryIfMissing(url: String, key: String, value: String?): String {
        if (value.isNullOrBlank()) return url
        val uri = Uri.parse(url)
        if (!uri.getQueryParameter(key).isNullOrBlank()) return url
        return uri.buildUpon().appendQueryParameter(key, value).build().toString()
    }

    private fun appendQuery(url: String, key: String, value: String): String =
        Uri.parse(url).buildUpon().appendQueryParameter(key, value).build().toString()

    private fun parseQuery(query: String): Map<String, String> = buildMap {
        query.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            put(
                URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8.name()),
                URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8.name()),
            )
        }
    }

    override fun close() = synchronized(lock) {
        session?.close()
        session = null
        cachedVisitorData = null
        cachedToken = null
    }
}

private class WebPoTokenSession(context: Context) : Closeable {
    companion object {
        private const val JS = "QarroPoToken"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
    }

    private data class Pending(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var token: String? = null,
        @Volatile var error: Throwable? = null,
    )

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val readyLatch = CountDownLatch(1)
    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile private var ready = false
    @Volatile private var error: Throwable? = null
    @Volatile private var expiresAt = 0L
    @Volatile private var webView: WebView? = null
    @Volatile private var closed = false

    init {
        val html = app.assets.open("po_token.html").bufferedReader().use { it.readText() }
        onMain {
            try {
                val view = WebView(app)
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.userAgentString = USER_AGENT
                view.settings.blockNetworkLoads = true
                view.addJavascriptInterface(this, JS)
                view.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        if (m.message().contains("Uncaught", ignoreCase = true)) {
                            fail(IllegalStateException("WebView JS: ${m.message()}"))
                        }
                        return true
                    }
                }
                val injected = html.replaceFirst(
                    "</script>",
                    "\n$JS.downloadAndRunBotguard()</script>",
                )
                view.loadDataWithBaseURL("https://www.youtube.com", injected, "text/html", "utf-8", null)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun awaitReady() {
        if (!readyLatch.await(35, TimeUnit.SECONDS)) {
            throw IllegalStateException("PO Token: BotGuard/WebView timeout")
        }
        error?.let { throw IllegalStateException("PO Token: ${it.message ?: "WebView error"}", it) }
        if (!ready) throw IllegalStateException("PO Token: WebView завершился без integrity token")
    }

    fun isExpired(): Boolean = !ready || System.currentTimeMillis() >= expiresAt

    fun generate(identifier: String): String {
        awaitReady()
        if (isExpired()) throw IllegalStateException("PO Token: integrity token истёк")
        val item = Pending()
        val actual = pending.putIfAbsent(identifier, item) ?: item
        if (actual === item) {
            val identifierJson = JSONObject.quote(identifier)
            val u8 = newUint8Array(identifier.toByteArray(Charsets.UTF_8))
            onMain {
                webView?.evaluateJavascript(
                    """try {
                        identifier = $identifierJson
                        u8Identifier = $u8
                        poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS.onObtainPoTokenResult(identifier, poTokenU8String)
                    } catch (e) {
                        $JS.onObtainPoTokenError(identifier, e + "\\n" + e.stack)
                    }""".trimIndent(),
                    null,
                )
            }
        }
        if (!actual.latch.await(20, TimeUnit.SECONDS)) {
            pending.remove(identifier, actual)
            throw IllegalStateException("PO Token: mint timeout")
        }
        pending.remove(identifier, actual)
        actual.error?.let { throw IllegalStateException("PO Token: ${it.message}", it) }
        return actual.token ?: throw IllegalStateException("PO Token: пустой mint result")
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        runCatching {
            val raw = request(
                "https://www.youtube.com/api/jnn/v1/Create",
                "[ \"$REQUEST_KEY\" ]",
            )
            val challenge = parseChallenge(raw)
            onMain {
                webView?.evaluateJavascript(
                    """try {
                        data = $challenge
                        runBotGuard(data).then(function (result) {
                            this.webPoSignalOutput = result.webPoSignalOutput
                            $JS.onRunBotguardResult(result.botguardResponse)
                        }, function (e) {
                            $JS.onJsInitializationError(e + "\\n" + e.stack)
                        })
                    } catch (e) {
                        $JS.onJsInitializationError(e + "\\n" + e.stack)
                    }""".trimIndent(),
                    null,
                )
            }
        }.onFailure(::fail)
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        runCatching {
            val raw = request(
                "https://www.youtube.com/api/jnn/v1/GenerateIT",
                "[ \"$REQUEST_KEY\", ${JSONObject.quote(botguardResponse)} ]",
            )
            val data = JSONArray(raw)
            val integrityJs = newUint8Array(decodeYouTubeBase64(data.getString(0)))
            val safeSeconds = (data.getLong(1) - 600L).coerceAtLeast(60L)
            expiresAt = System.currentTimeMillis() + safeSeconds * 1000L
            onMain {
                webView?.evaluateJavascript("this.integrityToken = $integrityJs") {
                    ready = true
                    readyLatch.countDown()
                }
            }
        }.onFailure(::fail)
    }

    @JavascriptInterface
    fun onJsInitializationError(message: String) = fail(IllegalStateException("BotGuard JS: $message"))

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, bytes: String) {
        pending[identifier]?.let { item ->
            runCatching { u8ToBase64(bytes) }
                .onSuccess { item.token = it }
                .onFailure { item.error = it }
            item.latch.countDown()
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, message: String) {
        pending[identifier]?.let { item ->
            item.error = IllegalStateException("BotGuard mint: $message")
            item.latch.countDown()
        }
    }

    private fun request(url: String, data: String): String {
        val body = data.toRequestBody("application/json+protobuf".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json+protobuf")
            .header("x-goog-api-key", GOOGLE_API_KEY)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .build()
        http.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("BotGuard HTTP ${response.code}")
            if (text.isBlank()) throw IllegalStateException("BotGuard вернул пустой ответ")
            return text
        }
    }

    private fun parseChallenge(raw: String): String {
        val scrambled = JSONArray(raw)
        val challenge = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
            JSONArray(descramble(scrambled.getString(1)))
        } else {
            scrambled.getJSONArray(0)
        }
        fun firstString(array: JSONArray?): String? {
            if (array == null) return null
            for (i in 0 until array.length()) if (array.opt(i) is String) return array.getString(i)
            return null
        }
        return JSONObject()
            .put("messageId", challenge.getString(0))
            .put(
                "interpreterJavascript",
                JSONObject()
                    .put("privateDoNotAccessOrElseSafeScriptWrappedValue", firstString(challenge.optJSONArray(1)))
                    .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", firstString(challenge.optJSONArray(2))),
            )
            .put("interpreterHash", challenge.getString(3))
            .put("program", challenge.getString(4))
            .put("globalName", challenge.getString(5))
            .put("clientExperimentsStateBlob", challenge.optString(7, ""))
            .toString()
    }

    private fun descramble(value: String): String =
        decodeYouTubeBase64(value).map { (it + 97).toByte() }.toByteArray().toString(Charsets.UTF_8)

    private fun newUint8Array(bytes: ByteArray): String =
        "new Uint8Array([" + bytes.joinToString(",") { (it.toInt() and 0xff).toString() } + "])"

    private fun u8ToBase64(value: String): String {
        val bytes = value.split(',').filter { it.isNotBlank() }.map { it.trim().toInt().toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun decodeYouTubeBase64(value: String): ByteArray {
        var normalized = value.replace('-', '+').replace('_', '/').replace('.', '=')
        normalized += "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.decode(normalized, Base64.DEFAULT)
    }

    private fun fail(t: Throwable) {
        if (readyLatch.count > 0L) {
            error = t
            readyLatch.countDown()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else if (!main.post(block)) throw IllegalStateException("PO Token: WebView main-thread dispatch failed")
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.values.forEach {
            it.error = IllegalStateException("PO Token: session closed")
            it.latch.countDown()
        }
        pending.clear()
        onMain {
            webView?.let { view ->
                runCatching {
                    view.stopLoading()
                    view.removeJavascriptInterface(JS)
                    view.loadUrl("about:blank")
                    view.clearHistory()
                    view.onPause()
                    view.removeAllViews()
                    view.destroy()
                }
            }
            webView = null
        }
    }
}
