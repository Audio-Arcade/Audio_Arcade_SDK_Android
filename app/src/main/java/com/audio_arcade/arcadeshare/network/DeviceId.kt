package com.audio_arcade.arcadeshare.network

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.util.UUID

internal object DeviceId {

    private const val PREFS_NAME = "arcadeshare_device"
    private const val KEY_UUID   = "device_uuid"

    /**
     * Retourne un identifiant stable et unique pour cet appareil.
     * Même valeur à chaque appel, même après redémarrage.
     */
    @SuppressLint("HardwareIds")
    fun get(context: Context): String {
        // 1. Essayer ANDROID_ID (stable, pas de permission)
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            // "9774d56d682e549c" est l'ANDROID_ID buggé sur certains appareils
            return androidId
        }

        // 2. Fallback : UUID persisté en SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved  = prefs.getString(KEY_UUID, null)
        if (!saved.isNullOrBlank()) return saved

        val generated = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_UUID, generated).apply()
        return generated
    }
}