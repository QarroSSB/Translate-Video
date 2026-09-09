package com.qarro.livetranslator

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.qarro.livetranslator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("qarro_live_translator", MODE_PRIVATE) }
    private val serverProbe = ServerProbe()
    private val apiKeyStore by lazy { ApiKeyStore(this) }
    private var videoWebView: WebView? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val direct = binding.connectionDirect.isChecked
            val apiKey = if (direct) runCatching { apiKeyStore.load().orEmpty() }.getOrDefault("") else ""
            if (direct && apiKey.isBlank()) {
                binding.status.text = "Статус: сначала сохрани OpenAI API key"
                return@registerForActivityResult
            }

            val endpoint = binding.endpoint.text.toString().trim().ifEmpty {
                "ws://127.0.0.1:8765/ws/translate"
            }
            val target = binding.targetLanguage.text.toString().trim().ifEmpty { "ru" }
            val mode = if (direct) "live" else if (binding.modeMultiVoice.isChecked) "multivoice" else "live"
            val chunkMs = (binding.chunkSeek.progress + 3) * 1000
            val showOverlay = binding.showOverlay.isChecked
            val translationVolume = binding.volumeSeek.progress.coerceIn(0, 100)
            val duckOriginal = binding.duckOriginal.isChecked
            val connectionMode = if (direct) "direct" else "server"

            prefs.edit()
                .putString("connection_mode", connectionMode)
                .putString("endpoint", endpoint)
                .putString("target", target)
                .putString("mode", mode)
                .putInt("chunk_ms", chunkMs)
                .putBoolean("overlay", showOverlay)
                .putInt("translation_volume", translationVolume)
                .putBoolean("duck_original", duckOriginal)
                .apply()

            val serviceIntent = Intent(this, PlaybackCaptureService::class.java).apply {
                putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(PlaybackCaptureService.EXTRA_CONNECTION_MODE, connectionMode)
                putExtra(PlaybackCaptureService.EXTRA_API_KEY, apiKey)
                putExtra(PlaybackCaptureService.EXTRA_ENDPOINT, endpoint)
                putExtra(PlaybackCaptureService.EXTRA_TARGET_LANGUAGE, target)
                putExtra(PlaybackCaptureService.EXTRA_MODE, mode)
                putExtra(PlaybackCaptureService.EXTRA_CHUNK_MS, chunkMs)
                putExtra(PlaybackCaptureService.EXTRA_SHOW_OVERLAY, showOverlay)
                putExtra(PlaybackCaptureService.EXTRA_TRANSLATION_VOLUME, translationVolume)
                putExtra(PlaybackCaptureService.EXTRA_DUCK_ORIGINAL, duckOriginal)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            binding.status.text = if (direct) {
                "Статус: Phone-Only перевод запускается… теперь включи видео"
            } else {
                "Статус: серверный перевод запускается…"
            }
        } else {
            binding.status.text = "Статус: разрешение на захват не выдано"
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioGranted) requestProjection() else binding.status.text = "Статус: нужен доступ RECORD_AUDIO"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.endpoint.setText(prefs.getString("endpoint", "ws://127.0.0.1:8765/ws/translate"))
        binding.targetLanguage.setText(prefs.getString("target", "ru"))
        binding.showOverlay.isChecked = prefs.getBoolean("overlay", true)
        binding.duckOriginal.isChecked = prefs.getBoolean("duck_original", false)
        binding.volumeSeek.progress = prefs.getInt("translation_volume", 100).coerceIn(0, 100)
        updateVolumeLabel()
        updateApiKeyStatus()

        val connectionMode = prefs.getString("connection_mode", "direct")
        binding.connectionDirect.isChecked = connectionMode != "server"
        binding.connectionServer.isChecked = connectionMode == "server"

        val savedMode = prefs.getString("mode", "live")
        binding.modeMultiVoice.isChecked = savedMode == "multivoice"
        binding.modeLive.isChecked = savedMode != "multivoice"
        val chunkMs = prefs.getInt("chunk_ms", 6000).coerceIn(3000, 12000)
        binding.chunkSeek.progress = chunkMs / 1000 - 3
        updateChunkLabel()

        binding.chunkSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateChunkLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateVolumeLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.connectionGroup.setOnCheckedChangeListener { _, _ -> updateConnectionUi() }
        binding.modeGroup.setOnCheckedChangeListener { _, _ -> updateModeUi() }

        binding.saveApiKey.setOnClickListener { saveApiKey() }
        binding.testServer.setOnClickListener { testServer() }
        binding.openVideo.setOnClickListener { openYouTubeVideo() }
        binding.overlayPermission.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                binding.status.text = "Статус: разрешение на оверлей уже выдано"
            }
        }

        binding.start.setOnClickListener { ensurePermissionsAndStart() }
        binding.stop.setOnClickListener {
            stopService(Intent(this, PlaybackCaptureService::class.java))
            binding.status.text = "Статус: остановлено"
        }

        updateConnectionUi()
        updateModeUi()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        serverProbe.close()
        runCatching {
            videoWebView?.stopLoading()
            videoWebView?.loadUrl("about:blank")
            videoWebView?.removeAllViews()
            videoWebView?.destroy()
            videoWebView = null
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        videoWebView?.let { return it }

        val webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
                allowFileAccess = false
                allowContentAccess = false
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        binding.status.text = "Статус: не удалось открыть встроенное видео — ${error?.description ?: "ошибка"}"
                    }
                }
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                getSystemService(AudioManager::class.java)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
            }
        }

        binding.videoContainer.removeAllViews()
        binding.videoContainer.addView(webView)
        videoWebView = webView
        return webView
    }

    private fun saveApiKey() {
        val key = binding.apiKey.text.toString().trim()
        if (key.isBlank()) {
            runCatching { apiKeyStore.clear() }
            binding.status.text = "Статус: сохранённый API key удалён"
        } else {
            runCatching { apiKeyStore.save(key) }
                .onSuccess {
                    binding.apiKey.text?.clear()
                    binding.status.text = "Статус: API key сохранён в Android Keystore"
                }
                .onFailure {
                    binding.status.text = "Статус: не удалось сохранить API key — ${it.message ?: "ошибка"}"
                }
        }
        updateApiKeyStatus()
    }

    private fun updateApiKeyStatus() {
        val hasKey = runCatching { apiKeyStore.hasKey() }.getOrDefault(false)
        binding.apiKeyStatus.text = if (hasKey) "API key: сохранён локально ✓" else "API key: не сохранён"
    }

    private fun updateConnectionUi() {
        val direct = binding.connectionDirect.isChecked
        binding.directSettings.visibility = if (direct) View.VISIBLE else View.GONE
        binding.serverSettings.visibility = if (direct) View.GONE else View.VISIBLE
        binding.modeMultiVoice.isEnabled = !direct
        if (direct && binding.modeMultiVoice.isChecked) binding.modeLive.isChecked = true
        binding.modeHint.text = if (direct) {
            "Phone-Only подключается напрямую к Realtime Translation. Multi-voice пока остаётся в Advanced Server."
        } else {
            "Advanced Server поддерживает Live и текущий Multi-voice с разными голосами и эмоциями."
        }
        updateModeUi()
    }

    private fun updateModeUi() {
        val enabled = binding.connectionServer.isChecked && binding.modeMultiVoice.isChecked
        binding.chunkSeek.isEnabled = enabled
        binding.chunkLabel.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateChunkLabel() {
        binding.chunkLabel.text = "Контекст Multi-voice: ${binding.chunkSeek.progress + 3} сек"
    }

    private fun updateVolumeLabel() {
        binding.volumeLabel.text = "Громкость русского голоса: ${binding.volumeSeek.progress}%"
    }

    private fun testServer() {
        if (binding.connectionDirect.isChecked) {
            binding.status.text = "Статус: Phone-Only не использует локальный сервер"
            return
        }
        val endpoint = binding.endpoint.text.toString().trim()
        if (!isValidEndpoint(endpoint)) return
        binding.status.text = "Статус: проверяю сервер…"
        binding.testServer.isEnabled = false
        serverProbe.check(endpoint) { result ->
            runOnUiThread {
                binding.testServer.isEnabled = true
                result.onSuccess { health ->
                    binding.status.text = if (health.apiKeyConfigured) {
                        "Статус: сервер v${health.version} готов • API key ✓ • ${health.modes}"
                    } else {
                        "Статус: сервер v${health.version} доступен, но OPENAI_API_KEY не настроен"
                    }
                }.onFailure { error ->
                    binding.status.text = "Статус: сервер недоступен — ${error.message ?: "ошибка"}"
                }
            }
        }
    }

    private fun openYouTubeVideo() {
        val raw = binding.videoUrl.text.toString().trim()
        val videoId = YouTubeUrlParser.extractVideoId(raw)
        if (videoId == null) {
            binding.status.text = "Статус: не удалось определить ID видео из ссылки YouTube"
            return
        }

        val webView = runCatching { ensureWebView() }.getOrElse { error ->
            binding.status.text = "Статус: WebView не запустился — ${error.message ?: "ошибка"}"
            return
        }
        val embed = "https://www.youtube.com/embed/$videoId?playsinline=1&rel=0&autoplay=0&enablejsapi=1"
        binding.videoContainer.visibility = View.VISIBLE
        webView.loadUrl(embed)
        binding.status.text = "Статус: видео загружается. Затем нажми «Начать перевод» и запусти Play."
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND || incoming.type != "text/plain") return
        val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val link = YouTubeUrlParser.extractSharedYouTubeUrl(sharedText) ?: sharedText.trim()
        if (YouTubeUrlParser.extractVideoId(link) != null) {
            binding.videoUrl.setText(link)
            binding.status.text = "Статус: ссылка получена из YouTube. Нажми «Открыть видео»."
        }
    }

    private fun ensurePermissionsAndStart() {
        if (binding.showOverlay.isChecked && !Settings.canDrawOverlays(this)) {
            binding.status.text = "Статус: сначала разреши субтитры поверх приложений или отключи их"
            return
        }
        if (binding.connectionDirect.isChecked) {
            val hasKey = runCatching { apiKeyStore.hasKey() }.getOrDefault(false)
            if (!hasKey) {
                binding.status.text = "Статус: сначала введи и сохрани OpenAI API key"
                return
            }
        } else if (!isValidEndpoint(binding.endpoint.text.toString().trim())) {
            return
        }

        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) requestProjection() else permissionsLauncher.launch(needed.toTypedArray())
    }

    private fun isValidEndpoint(endpoint: String): Boolean {
        if (!endpoint.startsWith("ws://") && !endpoint.startsWith("wss://")) {
            binding.status.text = "Статус: адрес сервера должен начинаться с ws:// или wss://"
            return false
        }
        return true
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
