package com.qarro.livetranslator

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WEB poToken provider modeled after NewPipe's Android implementation.
 *
 * The normal WEB client needs two tokens minted from the same BotGuard session:
 * 1) visitorData -> streaming URL token (must be generated first)
 * 2) videoId -> player request token
 *
 * WEB_EMBEDDED intentionally returns null here. v0.5.7 proved on-device that the tested video
 * receives ERROR/"This video is unavailable" from that client, so the main extractor should use
 * the standard WEB flow instead.
 */
class NewPipeWebPoTokenProvider(context: Context) : PoTokenProvider, Closeable {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var progressListener: ((String) -> Unit)? = null

    private var session: WebPoTokenGeneratorSession? = null
    private var visitorData: String? = null
    private var streamingToken: String? = null

    fun setProgressListener(listener: ((String) -> Unit)?) {
        progressListener = listener
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        return try {
            obtainWebToken(videoId, forceRecreate = false)
        } catch (first: Throwable) {
            emit("PO Token WEB: первый mint не удался • перезапускаю BotGuard…")
            try {
                obtainWebToken(videoId, forceRecreate = true)
            } catch (second: Throwable) {
                emit("PO Token WEB: ошибка • ${shortError(second)}")
                throw IllegalStateException(
                    "WEB PO-token failed after retry: ${shortError(second)}",
                    second,
                )
            }
        }
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    private fun obtainWebToken(videoId: String, forceRecreate: Boolean): PoTokenResult = synchronized(lock) {
        var generator = session
        val recreate = forceRecreate || generator == null || generator.isExpired()

        if (recreate) {
            generator?.close()
            generator = null
            visitorData = null
            streamingToken = null

            emit("PO Token WEB: получаю visitorData…")
            val requestInfo = InnertubeClientRequestInfo.ofWebClient()
            requestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
            val newVisitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                requestInfo,
                NewPipe.getPreferredLocalization(),
                NewPipe.getPreferredContentCountry(),
                YoutubeParsingHelper.getYouTubeHeaders(),
                YoutubeParsingHelper.YOUTUBEI_V1_URL,
                null,
                false,
            )

            emit("PO Token WEB: запускаю BotGuard в локальном WebView…")
            generator = WebPoTokenGeneratorSession(appContext)
            generator.awaitReady()

            // NewPipe requires this token to be minted before any player token.
            emit("PO Token WEB: создаю streaming token…")
            val newStreamingToken = generator.generate(newVisitorData)

            session = generator
            visitorData = newVisitorData
            streamingToken = newStreamingToken
        }

        val activeGenerator = generator ?: throw IllegalStateException("WEB PO-token generator unavailable")
        val activeVisitorData = visitorData ?: throw IllegalStateException("WEB PO-token visitorData unavailable")
        val activeStreamingToken = streamingToken ?: throw IllegalStateException("WEB PO-token streaming token unavailable")

        emit("PO Token WEB: создаю player token для videoId…")
        val playerToken = activeGenerator.generate(videoId)
        emit("PO Token WEB: два токена готовы ✓")
        PoTokenResult(activeVisitorData, playerToken, activeStreamingToken)
    }

    fun reset() = synchronized(lock) {
        session?.close()
        session = null
        visitorData = null
        streamingToken = null
    }

    private fun emit(message: String) {
        runCatching { progressListener?.invoke(message) }
    }

    private fun shortError(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName)
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .take(260)

    override fun close() {
        reset()
        progressListener = null
    }
}

private class WebPoTokenGeneratorSession(context: Context) : Closeable {
    companion object {
        private const val JS = "QarroWebPoToken"
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
            throw IllegalStateException("BotGuard/WebView timeout")
        }
        error?.let { throw IllegalStateException(it.message ?: "WebView error", it) }
        if (!ready) throw IllegalStateException("WebView finished without integrity token")
    }

    fun isExpired(): Boolean = !ready || System.currentTimeMillis() >= expiresAt

    fun generate(identifier: String): String {
        awaitReady()
        if (isExpired()) throw IllegalStateException("integrity token expired")

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
            throw IllegalStateException("PO-token mint timeout")
        }
        pending.remove(identifier, actual)
        actual.error?.let { throw IllegalStateException(it.message ?: "mint error", it) }
        return actual.token ?: throw IllegalStateException("empty mint result")
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
            if (text.isBlank()) throw IllegalStateException("BotGuard returned empty response")
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
        decodeYouTubeBase64(value)
            .map { (it + 97).toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)

    private fun newUint8Array(bytes: ByteArray): String =
        "new Uint8Array([" + bytes.joinToString(",") { (it.toInt() and 0xff).toString() } + "])"

    private fun u8ToBase64(value: String): String {
        val bytes = value.split(',')
            .filter { it.isNotBlank() }
            .map { it.trim().toInt().toByte() }
            .toByteArray()
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
        else if (!main.post(block)) throw IllegalStateException("WebView main-thread dispatch failed")
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.values.forEach {
            it.error = IllegalStateException("PO-token session closed")
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
                    view.clearCache(true)
                    view.onPause()
                    view.removeAllViews()
                    view.destroy()
                }
            }
            webView = null
        }
    }
}
