package com.example.radioshuffle

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log

object EqualizerHelper {
    private const val TAG = "EqualizerHelper"
    private const val PREFS_NAME = "radio_shuffler_equalizer"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_PRESET = "eq_preset"
    private const val KEY_BASS_BOOST = "eq_bass_boost"
    private const val KEY_BAND_PREFIX = "eq_band_"

    private var activeEqualizer: Equalizer? = null
    private var activeBassBoost: BassBoost? = null
    private var currentSessionId: Int = 0

    fun isSystemEqualizerAvailable(context: Context): Boolean {
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        val resolveInfo = context.packageManager.queryIntentActivities(intent, 0)
        return resolveInfo.isNotEmpty()
    }

    fun openDeviceSystemEqualizer(context: Context, audioSessionId: Int = 0): Boolean {
        return try {
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                if (audioSessionId != 0) {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                }
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Device system equalizer could not be launched: ${e.message}")
            false
        }
    }

    fun attachSession(context: Context, audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == currentSessionId) return
        release()
        currentSessionId = audioSessionId

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val eq = Equalizer(0, audioSessionId)
            val isEnabled = prefs.getBoolean(KEY_ENABLED, true)
            eq.enabled = isEnabled

            val savedPreset = prefs.getInt(KEY_PRESET, -1).toShort()
            if (savedPreset >= 0 && savedPreset < eq.numberOfPresets) {
                eq.usePreset(savedPreset)
            } else {
                for (band in 0 until eq.numberOfBands) {
                    val key = "$KEY_BAND_PREFIX$band"
                    if (prefs.contains(key)) {
                        val level = prefs.getInt(key, 0).toShort()
                        eq.setBandLevel(band.toShort(), level)
                    }
                }
            }
            activeEqualizer = eq

            val bb = BassBoost(0, audioSessionId)
            val bassStrength = prefs.getInt(KEY_BASS_BOOST, 0).toShort()
            if (bb.strengthSupported) {
                bb.setStrength(bassStrength)
                bb.enabled = isEnabled && bassStrength > 0
            }
            activeBassBoost = bb
        } catch (e: Exception) {
            Log.d(TAG, "Failed to initialize native in-app audio effects: ${e.message}")
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        activeEqualizer?.enabled = enabled
        activeBassBoost?.let {
            if (it.strengthSupported) {
                it.enabled = enabled && it.roundedStrength > 0
            }
        }
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun getPresets(): List<String> {
        val eq = activeEqualizer ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until eq.numberOfPresets) {
            list.add(eq.getPresetName(i.toShort()))
        }
        return list
    }

    fun usePreset(context: Context, presetIndex: Short) {
        val eq = activeEqualizer ?: return
        if (presetIndex in 0 until eq.numberOfPresets) {
            eq.usePreset(presetIndex)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_PRESET, presetIndex.toInt()).apply()
        }
    }

    fun setBandLevel(context: Context, band: Short, level: Short) {
        val eq = activeEqualizer ?: return
        eq.setBandLevel(band, level)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PRESET, -1) // Custom preset
            .putInt("$KEY_BAND_PREFIX$band", level.toInt())
            .apply()
    }

    fun setBassBoost(context: Context, strength: Short) {
        val bb = activeBassBoost ?: return
        if (bb.strengthSupported) {
            bb.setStrength(strength)
            bb.enabled = isEnabled(context) && strength > 0
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BASS_BOOST, strength.toInt()).apply()
        }
    }

    fun release() {
        try {
            activeEqualizer?.release()
        } catch (_: Exception) {}
        try {
            activeBassBoost?.release()
        } catch (_: Exception) {}
        activeEqualizer = null
        activeBassBoost = null
        currentSessionId = 0
    }
}
