package com.example.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TtsEngineStatus(val label: String) {
    INITIALIZING("Initializing Hindi TTS..."),
    READY("Hindi Voice (hi-IN) Ready"),
    MISSING_HINDI_DATA("Hindi Voice Data Missing"),
    ERROR("TTS Engine Unavailable")
}

enum class ModelDownloadStatus(val label: String) {
    CHECKING("Checking Hindi Model..."),
    DOWNLOADING("Downloading EN → HI Model..."),
    READY("ML Kit EN → HI Model Ready"),
    OFFLINE_FALLBACK("Ready (Fast Local + ML Kit)")
}

data class SubtitleTranslationEntry(
    val id: Long,
    val timestamp: String,
    val extractedEnglish: String,
    val translatedHindi: String,
    val wasSpoken: Boolean,
    val skippedReason: String? = null,
    val processingTimeMs: Long = 0L
)

data class TranslatorAppState(
    val isServiceRunning: Boolean = false,
    val isOverlayDemoActive: Boolean = false,
    val isCapturingOcr: Boolean = false,
    val ttsPitch: Float = 0.8f,
    val ttsSpeechRate: Float = 0.9f,
    val invertColorsEnabled: Boolean = true,
    val captureIntervalMs: Long = 2000L,
    val lastExtractedEnglish: String = "Waiting for game subtitles...",
    val lastTranslatedHindi: String = "गेम सबटाइटल की प्रतीक्षा कर रहा है...",
    val lastSpokenText: String = "",
    val scanFrameCount: Int = 0,
    val duplicateSkippedCount: Int = 0,
    val ttsStatus: TtsEngineStatus = TtsEngineStatus.INITIALIZING,
    val modelStatus: ModelDownloadStatus = ModelDownloadStatus.CHECKING,
    val showSettingsBottomSheet: Boolean = false,
    val originalFramePreview: Bitmap? = null,
    val invertedFramePreview: Bitmap? = null,
    val history: List<SubtitleTranslationEntry> = emptyList()
)

object TranslatorStateRepository {
    private const val PREFS_NAME = "gamevox_hindi_prefs"
    private const val KEY_PITCH = "tts_pitch"
    private const val KEY_RATE = "tts_speech_rate"
    private const val KEY_INVERT = "invert_colors_enabled"
    private const val KEY_INTERVAL = "capture_interval_ms"

    private val _state = MutableStateFlow(TranslatorAppState())
    val state: StateFlow<TranslatorAppState> = _state.asStateFlow()

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pitch = prefs.getFloat(KEY_PITCH, 0.8f).coerceIn(0.4f, 1.8f)
        val rate = prefs.getFloat(KEY_RATE, 0.9f).coerceIn(0.4f, 1.8f)
        val invert = prefs.getBoolean(KEY_INVERT, true)
        val interval = prefs.getLong(KEY_INTERVAL, 2000L).coerceIn(1000L, 5000L)
        _state.update {
            it.copy(
                ttsPitch = pitch,
                ttsSpeechRate = rate,
                invertColorsEnabled = invert,
                captureIntervalMs = interval
            )
        }
    }

    fun updateTtsPitch(context: Context, pitch: Float) {
        val clamped = pitch.coerceIn(0.4f, 1.8f)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PITCH, clamped)
            .apply()
        _state.update { it.copy(ttsPitch = clamped) }
    }

    fun updateTtsSpeechRate(context: Context, rate: Float) {
        val clamped = rate.coerceIn(0.4f, 1.8f)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_RATE, clamped)
            .apply()
        _state.update { it.copy(ttsSpeechRate = clamped) }
    }

    fun updateInvertColors(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INVERT, enabled)
            .apply()
        _state.update { it.copy(invertColorsEnabled = enabled) }
    }

    fun updateCaptureInterval(context: Context, intervalMs: Long) {
        val clamped = intervalMs.coerceIn(1000L, 5000L)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_INTERVAL, clamped)
            .apply()
        _state.update { it.copy(captureIntervalMs = clamped) }
    }

    fun setServiceRunning(running: Boolean, isOverlayDemo: Boolean = false) {
        _state.update {
            it.copy(
                isServiceRunning = running,
                isOverlayDemoActive = isOverlayDemo,
                isCapturingOcr = if (!running) false else it.isCapturingOcr
            )
        }
    }

    fun setCapturingOcr(capturing: Boolean) {
        _state.update { it.copy(isCapturingOcr = capturing) }
    }

    fun toggleCapturingOcr(): Boolean {
        var newState = false
        _state.update {
            newState = !it.isCapturingOcr
            it.copy(isCapturingOcr = newState)
        }
        return newState
    }

    fun setTtsStatus(status: TtsEngineStatus) {
        _state.update { it.copy(ttsStatus = status) }
    }

    fun setModelStatus(status: ModelDownloadStatus) {
        _state.update { it.copy(modelStatus = status) }
    }

    fun setShowSettingsBottomSheet(show: Boolean) {
        _state.update { it.copy(showSettingsBottomSheet = show) }
    }

    fun updateFramePreviews(original: Bitmap?, inverted: Bitmap?) {
        _state.update {
            it.copy(
                originalFramePreview = original,
                invertedFramePreview = inverted,
                scanFrameCount = it.scanFrameCount + 1
            )
        }
    }

    fun recordTranslationResult(
        english: String,
        hindi: String,
        wasSpoken: Boolean,
        skippedReason: String? = null,
        processingTimeMs: Long = 0L,
        timestamp: String
    ) {
        _state.update { current ->
            val newEntry = SubtitleTranslationEntry(
                id = System.currentTimeMillis(),
                timestamp = timestamp,
                extractedEnglish = english,
                translatedHindi = hindi,
                wasSpoken = wasSpoken,
                skippedReason = skippedReason,
                processingTimeMs = processingTimeMs
            )
            val updatedHistory = (listOf(newEntry) + current.history).take(25)
            current.copy(
                lastExtractedEnglish = english,
                lastTranslatedHindi = hindi,
                lastSpokenText = if (wasSpoken) hindi else current.lastSpokenText,
                duplicateSkippedCount = if (skippedReason == "Anti-Repeat: Identical to lastSpokenText") {
                    current.duplicateSkippedCount + 1
                } else {
                    current.duplicateSkippedCount
                },
                history = updatedHistory
            )
        }
    }

    fun clearLastSpokenMemory() {
        _state.update { it.copy(lastSpokenText = "") }
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList(), duplicateSkippedCount = 0) }
    }
}
