package com.example.radioshuffle

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SoundFeedback {
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Immediate crisp auditory beep played as soon as a shuffle is initiated
     * (via Bluetooth headset, volume long-press, lock screen controls, or in-app action).
     * Provides instant confirmation for visually impaired and hands-free users.
     */
    fun playShuffleTriggered() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 130)
            scope.launch {
                delay(220)
                tone.release()
            }
        } catch (_: Exception) {
            // Protect against device-specific audio stream initialization failures
        }
    }

    /**
     * Pleasant confirmation chime played when the new station connects and starts streaming.
     */
    fun playShuffleConnected() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 75)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 160)
            scope.launch {
                delay(250)
                tone.release()
            }
        } catch (_: Exception) {
        }
    }
}
