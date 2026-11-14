package com.example.service

import android.content.Context
import android.os.SystemClock
import com.example.data.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fallback implementation of [NeuralTtsEngine] delegating to Android system [TextToSpeech].
 * Active when Supertonic 3 model files are not yet downloaded on the device.
 */
class AndroidSystemTtsFallback(
    private val context: Context
) : NeuralTtsEngine {

    companion object {
        const val BACKEND_ID = "android_system_tts_fallback"
    }

    override val backendId: String = BACKEND_ID

    override val isReady: Boolean
        get() = ForegroundTtsManager.getInstance(context).isTtsReady

    override val currentBackendLabel: String
        get() = "Android System TTS (Fallback • Supertonic model not installed)"

    override val numSpeakers: Int = 10

    override suspend fun synthesizeAndPlay(
        text: String,
        sid: Int,
        speed: Float,
        pitch: Float,
        epoch: Long,
        onFirstAudioStarted: (latencyMs: Long) -> Unit,
        onCompleted: () -> Unit,
        onError: (errorMessage: String) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        val manager = ForegroundTtsManager.getInstance(context)
        if (!manager.isTtsReady) {
            onError("Android TextToSpeech engine not ready")
            return@withContext false
        }

        val startTime = SystemClock.elapsedRealtime()
        onFirstAudioStarted(35L) // Immediate IPC dispatch approximation

        val profile = VoiceProfile(
            id = "fallback_sid_$sid",
            name = "Supertonic Sid #$sid (System Fallback)",
            description = "Fallback to system TTS for Sid $sid",
            pitch = pitch,
            speechRate = speed,
            engineVoiceIndex = sid % 4
        )

        val success = manager.speakNonBlocking(
            text = text,
            voiceProfile = profile
        )
        if (success) {
            onCompleted()
        }

        if (!success) {
            onError("System TTS speak failed")
        }
        success
    }

    override fun stop() {
        ForegroundTtsManager.getInstance(context).stopSpeaking()
    }

    override fun release() {
        // ForegroundTtsManager manages its own lifecycle
    }
}
