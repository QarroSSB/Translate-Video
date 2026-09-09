package com.qarro.livetranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.qarro.livetranslator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("qarro_live_translator", MODE_PRIVATE) }
    private val serverProbe = ServerProbe()

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val endpoint = binding.endpoint.text.toString().trim().ifEmpty {
                "ws://10.0.2.2:8765/ws/translate"
            }
            val target = binding.targetLanguage.text.toString().trim().ifEmpty { "ru" }
            val mode = if (binding.modeMultiVoice.isChecked) "multivoice" else "live"
            val chunkMs = (binding.chunkSeek.progress + 3) * 1000
            val showOverlay = binding.showOverlay.isChecked
            val translationVolume = binding.volumeSeek.progress.coerceIn(0, 100)
            val duckOriginal = binding.duckOriginal.isChecked

            prefs.edit()
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
                putExtra(PlaybackCaptureService.EXTRA_ENDPOINT, endpoint)
                putExtra(PlaybackCaptureService.EXTRA_TARGET_LANGUAGE, target)
                putExtra(PlaybackCaptureService.EXTRA_MODE, mode)
                putExtra(PlaybackCaptureService.EXTRA_CHUNK_MS, chunkMs)
                putExtra(PlaybackCaptureService.EXTRA_SHOW_OVERLAY, showOverlay)
                putExtra(PlaybackCaptureService.EXTRA_TRANSLATION_VOLUME, translationVolume)
                putExtra(PlaybackCaptureService.EXTRA_DUCK_ORIGINAL, duckOriginal)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            binding.status.text = "Статус: запуск захвата…"
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

        binding.endpoint.setText(prefs.getString("endpoint", "ws://10.0.2.2:8765/ws/translate"))
        binding.targetLanguage.setText(prefs.getString("target", "ru"))
        binding.showOverlay.isChecked = prefs.getBoolean("overlay", true)
        binding.duckOriginal.isChecked = prefs.getBoolean("duck_original", false)
        binding.volumeSeek.progress = prefs.getInt("translation_volume", 100).coerceIn(0, 100)
        updateVolumeLabel()

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

        binding.modeGroup.setOnCheckedChangeListener { _, _ -> updateModeUi() }
        binding.modeGroup.check(if (binding.modeMultiVoice.isChecked) binding.modeMultiVoice.id else binding.modeLive.id)

        binding.testServer.setOnClickListener { testServer() }
        binding.overlayPermission.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                binding.status.text = "Статус: разрешение на оверлей уже выдано"
            }
        }

        binding.start.setOnClickListener { ensurePermissionsAndStart() }
        binding.stop.setOnClickListener {
            stopService(Intent(this, PlaybackCaptureService::class.java))
            binding.status.text = "Статус: остановлено"
        }
    }

    override fun onDestroy() {
        serverProbe.close()
        super.onDestroy()
    }

    private fun updateModeUi() {
        val enabled = binding.modeMultiVoice.isChecked
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

    private fun ensurePermissionsAndStart() {
        if (binding.showOverlay.isChecked && !Settings.canDrawOverlays(this)) {
            binding.status.text = "Статус: сначала разреши субтитры поверх приложений"
            return
        }
        if (!isValidEndpoint(binding.endpoint.text.toString().trim())) return

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
