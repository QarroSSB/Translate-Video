package com.qarro.livetranslator

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class SubtitleOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var textView: TextView? = null
    private val current = StringBuilder()

    fun show() {
        if (!Settings.canDrawOverlays(context) || textView != null) return
        val view = TextView(context).apply {
            text = "Перевод запускается…"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB0000000.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(20, 12, 20, 12)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 120
        }
        windowManager.addView(view, params)
        textView = view
    }

    fun append(delta: String) {
        if (delta.isEmpty()) return
        current.append(delta)
        if (current.length > 280) current.delete(0, current.length - 280)
        textView?.post { textView?.text = current.toString().trim() }
    }

    fun showSpeakerLine(speaker: String, emotion: String, translation: String) {
        if (translation.isBlank()) return
        current.clear()
        current.append("$speaker · $emotion\n$translation")
        textView?.post { textView?.text = current.toString() }
    }

    fun setStatus(text: String) {
        textView?.post { textView?.text = text }
    }

    fun hide() {
        textView?.let { runCatching { windowManager.removeView(it) } }
        textView = null
        current.clear()
    }
}
