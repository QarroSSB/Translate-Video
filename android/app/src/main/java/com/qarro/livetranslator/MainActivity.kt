package com.qarro.livetranslator

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.qarro.livetranslator.databinding.ActivityMainBinding

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("qarro_live_translator", MODE_PRIVATE) }
    private val apiKeyStore by lazy { ApiKeyStore(this) }
    private val serverProbe = ServerProbe()
    private val resolver = YouTubeStreamResolver()

    private lateinit var pcmBridge: InternalPcmBridge
    private lateinit var playerEngine: InternalPlayerEngine
    private var translationClient: AudioTranslationClient? = null
    private var translationPlayer: PcmAudioPlayer? = null
    private var subtitleBuffer = StringBuilder()
    private var translationRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pcmBridge = InternalPcmBridge(
            onFirstAudio = {
                runOnUiThread {
                    binding.audioTapStatus.text = "Внутренний PCM: обнаружен ✓ • системный захват не используется"
                }
            },
            onMetric = { metric -> runOnUiThread { binding.audioTapStatus.text = metric } },
        )
        playerEngine = InternalPlayerEngine(this, pcmBridge) { message ->
            runOnUiThread { binding.status.text = "Статус: $message" }
        }
        playerEngine.attach(binding.playerView)

        migrateOldCaptureSettings()
        restoreUi()
        bindUi()
        handleIncomingIntent(intent)
    }

    private fun migrateOldCaptureSettings() {
        val uiVersion = prefs.getInt("internal_player_ui_version", 0)
        if (uiVersion < 5) {
            prefs.edit()
                .putInt("internal_player_ui_version", 5)
                .putString("connection_mode", "direct")
                .putString("mode", "live")
                .apply()
        }
    }

    private fun restoreUi() {
        binding.endpoint.setText(prefs.getString("endpoint", "ws://127.0.0.1:8765/ws/translate"))
        binding.targetLanguage.setText(prefs.getString("target", "ru"))
        binding.duckOriginal.isChecked = prefs.getBoolean("duck_original", true)
        binding.showSubtitles.isChecked = prefs.getBoolean("show_subtitles", true)
        binding.volumeSeek.progress = prefs.getInt("translation_volume", 100).coerceIn(0, 100)
        updateVolumeLabel()
        updateApiKeyStatus()

        val connectionMode = prefs.getString("connection_mode", "direct")
        binding.connectionDirect.isChecked = connectionMode != "server"
        binding.connectionServer.isChecked = connectionMode == "server"

        val mode = prefs.getString("mode", "live")
        binding.modeMultiVoice.isChecked = mode == "multivoice"
        binding.modeLive.isChecked = mode != "multivoice"

        val chunkMs = prefs.getInt("chunk_ms", 6000).coerceIn(3000, 12000)
        binding.chunkSeek.progress = chunkMs / 1000 - 3
        updateChunkLabel()
        updateConnectionUi()
        updateModeUi()
    }

    private fun bindUi() {
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
        binding.duckOriginal.setOnCheckedChangeListener { _, checked ->
            if (translationRunning) playerEngine.setOriginalVolume(if (checked) 0.22f else 1f)
        }
        binding.showSubtitles.setOnCheckedChangeListener { _, checked ->
            binding.internalSubtitle.visibility = if (checked && subtitleBuffer.isNotEmpty()) View.VISIBLE else View.GONE
        }

        binding.saveApiKey.setOnClickListener { saveApiKey() }
        binding.testServer.setOnClickListener { testServer() }
        binding.openVideo.setOnClickListener { openYouTubeVideo() }
        binding.start.setOnClickListener { startTranslation() }
        binding.stop.setOnClickListener { stopTranslation(showStatus = true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun openYouTubeVideo() {
        val raw = binding.videoUrl.text.toString().trim()
        val videoId = YouTubeUrlParser.extractVideoId(raw)
        if (videoId == null) {
            binding.status.text = "Статус: не удалось определить ID видео из ссылки YouTube"
            return
        }

        val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
        binding.openVideo.isEnabled = false
        binding.status.text = "Статус: получаю прямой поток YouTube на телефоне…"
        binding.audioTapStatus.text = "Внутренний PCM: ждёт запуска видео"

        resolver.resolve(canonicalUrl) { result ->
            runOnUiThread {
                binding.openVideo.isEnabled = true
                result.onSuccess { stream ->
                    binding.videoContainer.visibility = View.VISIBLE
                    subtitleBuffer.clear()
                    binding.internalSubtitle.text = ""
                    binding.internalSubtitle.visibility = View.GONE
                    playerEngine.load(stream)
                    binding.status.text =
                        "Статус: ${stream.title} • ${stream.resolution} • нажми Play во встроенном плеере"
                }.onFailure { error ->
                    binding.status.text =
                        "Статус: не удалось получить поток YouTube — ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    private fun startTranslation() {
        stopTranslation(showStatus = false)

        val direct = binding.connectionDirect.isChecked
        val target = binding.targetLanguage.text.toString().trim().ifEmpty { "ru" }
        val chunkMs = (binding.chunkSeek.progress + 3) * 1000
        val mode = if (direct) "live" else if (binding.modeMultiVoice.isChecked) "multivoice" else "live"
        val volume = binding.volumeSeek.progress.coerceIn(0, 100)
        val showSubtitles = binding.showSubtitles.isChecked

        val onAudio: (ByteArray) -> Unit = { bytes -> translationPlayer?.write(bytes) }
        val onTranscript: (String) -> Unit = { delta ->
            if (showSubtitles) runOnUiThread { appendSubtitle(delta) }
        }
        val onStatus: (String) -> Unit = { state ->
            runOnUiThread { binding.status.text = "Статус: перевод $state" }
        }
        val onMetric: (String) -> Unit = { metric ->
            runOnUiThread { binding.audioTapStatus.text = metric }
        }
        val onError: (String) -> Unit = { message ->
            runOnUiThread { binding.status.text = "Статус: ошибка перевода — $message" }
        }

        val client: AudioTranslationClient = if (direct) {
            val apiKey = runCatching { apiKeyStore.load().orEmpty() }.getOrDefault("")
            if (apiKey.isBlank()) {
                binding.status.text = "Статус: сначала сохрани OpenAI API key"
                return
            }
            DirectTranslationSocket(
                apiKey = apiKey,
                targetLanguage = target,
                onAudio = onAudio,
                onTargetTranscript = onTranscript,
                onStatus = onStatus,
                onMetric = onMetric,
                onError = onError,
            )
        } else {
            val endpoint = binding.endpoint.text.toString().trim()
            if (!isValidEndpoint(endpoint)) return
            TranslationSocket(
                endpoint = endpoint,
                targetLanguage = target,
                mode = mode,
                chunkMs = chunkMs,
                onAudio = onAudio,
                onTargetTranscript = onTranscript,
                onSpeakerLine = { speaker, emotion, translation ->
                    if (showSubtitles) runOnUiThread {
                        showSpeakerSubtitle(speaker, emotion, translation)
                    }
                },
                onStatus = onStatus,
                onMetric = onMetric,
                onError = onError,
            )
        }

        prefs.edit()
            .putString("connection_mode", if (direct) "direct" else "server")
            .putString("target", target)
            .putString("mode", mode)
            .putInt("chunk_ms", chunkMs)
            .putInt("translation_volume", volume)
            .putBoolean("duck_original", binding.duckOriginal.isChecked)
            .putBoolean("show_subtitles", showSubtitles)
            .apply()

        translationPlayer = PcmAudioPlayer(this, volume, false)
        translationClient = client
        pcmBridge.attachClient(client)
        translationRunning = true
        playerEngine.setOriginalVolume(if (binding.duckOriginal.isChecked) 0.22f else 1f)
        client.connect()
        binding.status.text = "Статус: перевод подключается • запусти видео, захват Android не требуется"
    }

    private fun stopTranslation(showStatus: Boolean) {
        pcmBridge.attachClient(null)
        translationClient?.close()
        translationClient = null
        translationPlayer?.release()
        translationPlayer = null
        translationRunning = false
        if (::playerEngine.isInitialized) playerEngine.setOriginalVolume(1f)
        if (showStatus && ::binding.isInitialized) binding.status.text = "Статус: перевод остановлен"
    }

    private fun appendSubtitle(delta: String) {
        if (delta.isBlank()) return
        subtitleBuffer.append(delta)
        if (subtitleBuffer.length > 420) {
            subtitleBuffer.delete(0, subtitleBuffer.length - 320)
        }
        binding.internalSubtitle.text = subtitleBuffer.toString().trim()
        binding.internalSubtitle.visibility = View.VISIBLE
    }

    private fun showSpeakerSubtitle(speaker: String, emotion: String, translation: String) {
        val prefix = listOf(speaker, emotion).filter { it.isNotBlank() }.joinToString(" • ")
        binding.internalSubtitle.text = if (prefix.isBlank()) translation else "$prefix\n$translation"
        binding.internalSubtitle.visibility = View.VISIBLE
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
            "Phone-Only получает PCM прямо из Media3. Никакого захвата экрана/звука Android."
        } else {
            "Advanced Server получает тот же внутренний PCM; Multi-voice доступен через серверный backend."
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
                        "Статус: сервер доступен, но OPENAI_API_KEY не настроен"
                    }
                }.onFailure { error ->
                    binding.status.text = "Статус: сервер недоступен — ${error.message ?: "ошибка"}"
                }
            }
        }
    }

    private fun isValidEndpoint(endpoint: String): Boolean {
        if (!endpoint.startsWith("ws://") && !endpoint.startsWith("wss://")) {
            binding.status.text = "Статус: адрес сервера должен начинаться с ws:// или wss://"
            return false
        }
        return true
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND || incoming.type != "text/plain") return
        val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val link = YouTubeUrlParser.extractSharedYouTubeUrl(sharedText) ?: sharedText.trim()
        if (YouTubeUrlParser.extractVideoId(link) != null) {
            binding.videoUrl.setText(link)
            binding.status.text = "Статус: ссылка получена из YouTube. Нажми «Получить и открыть видео»."
        }
    }

    override fun onDestroy() {
        stopTranslation(showStatus = false)
        playerEngine.release()
        pcmBridge.close()
        resolver.close()
        serverProbe.close()
        super.onDestroy()
    }
}
