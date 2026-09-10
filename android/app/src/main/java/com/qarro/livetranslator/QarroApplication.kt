package com.qarro.livetranslator

import android.app.Application

/**
 * One-time preference migrations that run before MainActivity.
 *
 * v0.3 stored Advanced Server / Multi-voice as the last active mode. When a user
 * installs the phone-only builds over v0.3, Android keeps those SharedPreferences,
 * which made the app try ws://127.0.0.1:8765 even though no local server exists.
 */
class QarroApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("qarro_live_translator", MODE_PRIVATE)
        val migration = prefs.getInt("phone_only_migration", 0)
        if (migration < 1) {
            val endpoint = prefs.getString("endpoint", "").orEmpty()
            val oldConnection = prefs.getString("connection_mode", "direct")
            val oldMode = prefs.getString("mode", "live")
            val looksLikeOldLocalServer = endpoint.contains("127.0.0.1:8765") ||
                endpoint.contains("10.0.2.2:8765") ||
                endpoint.contains("192.168.1.25:8765")

            // Existing installs should land on the only no-PC path by default.
            // Advanced Server remains available when the user explicitly selects it later.
            if (oldConnection == "server" || oldMode == "multivoice" || looksLikeOldLocalServer) {
                prefs.edit()
                    .putString("connection_mode", "direct")
                    .putString("mode", "live")
                    .putInt("phone_only_migration", 1)
                    .apply()
            } else {
                prefs.edit().putInt("phone_only_migration", 1).apply()
            }
        }
    }
}
