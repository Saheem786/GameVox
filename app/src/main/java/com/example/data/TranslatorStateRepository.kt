package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Target languages supported by ML Kit / Google Translator (BCP-47 language codes)
 * along with their corresponding Android TextToSpeech locales and native script labels.
 */
enum class TargetLanguage(
    val code: String,
    val ttsLocaleTag: String,
    val displayName: String,
    val nativeName: String,
    val glyphBadge: String,
    val sampleGreeting: String
) {
    HINDI(
        code = "hi",
        ttsLocaleTag = "hi-IN",
        displayName = "Hindi",
        nativeName = "हिन्दी",
        glyphBadge = "अ/A",
        sampleGreeting = "नमस्ते गेमर! अब हाई-क्वालिटी नेटवर्क वॉइस और ड्रैगेबल स्कैन बॉक्स तैयार है।"
    ),
    MARATHI(
        code = "mr",
        ttsLocaleTag = "mr-IN",
        displayName = "Marathi",
        nativeName = "मराठी",
        glyphBadge = "म/A",
        sampleGreeting = "नमस्कार गेमर! मराठी सबटायटल अनुवादक सज्ज आहे."
    ),
    BENGALI(
        code = "bn",
        ttsLocaleTag = "bn-IN",
        displayName = "Bengali",
        nativeName = "বাংলা",
        glyphBadge = "অ/A",
        sampleGreeting = "নমস্কার গেমার! বাংলা সাবটাইটেল অনুবাদক প্রস্তুত।"
    ),
    TAMIL(
        code = "ta",
        ttsLocaleTag = "ta-IN",
        displayName = "Tamil",
        nativeName = "தமிழ்",
        glyphBadge = "அ/A",
        sampleGreeting = "வணக்கம் கேமர்! தமிழ் வசன மொழிபெயர்ப்பாளர் தயாராக உள்ளது."
    ),
    TELUGU(
        code = "te",
        ttsLocaleTag = "te-IN",
        displayName = "Telugu",
        nativeName = "తెలుగు",
        glyphBadge = "అ/A",
        sampleGreeting = "నమస్కారం గేమర్! తెలుగు సబ్‌టైటిల్ అనువాదకం సిద్ధంగా ఉంది."
    ),
    GUJARATI(
        code = "gu",
        ttsLocaleTag = "gu-IN",
        displayName = "Gujarati",
        nativeName = "ગુજરાતી",
        glyphBadge = "અ/A",
        sampleGreeting = "નમસ્તે ગેમર! ગુજરાતી સબટાઈટલ અનુવાદક તૈયાર છે."
    ),
    KANNADA(
        code = "kn",
        ttsLocaleTag = "kn-IN",
        displayName = "Kannada",
        nativeName = "ಕನ್ನಡ",
        glyphBadge = "ಅ/A",
        sampleGreeting = "ನಮಸ್ಕಾರ ಗೇಮರ್! ಕನ್ನಡ ಉಪಶೀರ್ಷಿಕೆ ಅನುವಾದಕ ಸಿದ್ಧವಾಗಿದೆ."
    ),
    URDU(
        code = "ur",
        ttsLocaleTag = "ur-IN",
        displayName = "Urdu",
        nativeName = "اردو",
        glyphBadge = "ا/A",
        sampleGreeting = "السلام علیکم گیمر! اردو سب ٹائٹل مترجم تیار ہے۔"
    ),
    SPANISH(
        code = "es",
        ttsLocaleTag = "es-ES",
        displayName = "Spanish",
        nativeName = "Español",
        glyphBadge = "Ñ/A",
        sampleGreeting = "¡Hola jugador! El traductor de subtítulos en español está listo."
    ),
    JAPANESE(
        code = "ja",
        ttsLocaleTag = "ja-JP",
        displayName = "Japanese",
        nativeName = "日本語",
        glyphBadge = "あ/A",
        sampleGreeting = "こんにちはゲーマー！日本語字幕翻訳機の準備ができました。"
    );

    val locale: Locale
        get() = Locale.forLanguageTag(ttsLocaleTag)

    companion object {
        fun fromCode(code: String?): TargetLanguage {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: HINDI
        }
    }
}

enum class TtsEngineStatus(val label: String) {
    INITIALIZING("Initializing High-Quality TTS..."),
    DOWNLOADING_VOICE_DATA("Downloading Voice Pack Async..."),
    READY("HD Network Voice Ready"),
    FALLBACK_VOICE_READY("TTS Ready (Standard Voice)"),
    MISSING_HINDI_DATA("Voice Data Missing — Tap to Download"),
    ERROR("TTS Engine Unavailable")
}

enum class ModelDownloadStatus(val label: String) {
    CHECKING("Checking Translator..."),
    DOWNLOADING("Connecting Translator..."),
    READY("Google Neural + Offline Translator Ready"),
    OFFLINE_FALLBACK("Ready (Offline Dictionary Mode)")
}

data class SubtitleTranslationEntry(
    val id: Long,
    val timestamp: String,
    val extractedEnglish: String,
    val translatedHindi: String,
    val targetLanguage: TargetLanguage = TargetLanguage.HINDI,
    val wasSpoken: Boolean,
    val skippedReason: String? = null,
    val processingTimeMs: Long = 0L
)

data class TranslatorAppState(
    val isServiceRunning: Boolean = false,
    val isAccessibilityConnected: Boolean = false,
    val isOverlayDemoActive: Boolean = false,
    val isCapturingOcr: Boolean = false,
    val showTargetBoxOverlay: Boolean = true,
    val targetBoxScreenRect: Rect? = null,
    val targetLanguage: TargetLanguage = TargetLanguage.HINDI,
    val useHighQualityNetworkVoice: Boolean = true,
    val activeVoiceName: String = "Google Neural Network (hi-IN)",
    val availableVoicesCount: Int = 0,
    val voiceDownloadMessage: String = "HD Network Synthesis Active",
    val ttsPitch: Float = 0.95f,
    val ttsSpeechRate: Float = 0.95f,
    val invertColorsEnabled: Boolean = true,
    val captureIntervalMs: Long = 2000L,
    val lastExtractedEnglish: String = "Drag the cyan Scan Box over game text...",
    val lastTranslatedHindi: String = "स्कैन बॉक्स को गेम के टेक्स्ट पर ले जाएं...",
    val lastSpokenText: String = "",
    val scanFrameCount: Int = 0,
    val duplicateSkippedCount: Int = 0,
    val ttsStatus: TtsEngineStatus = TtsEngineStatus.INITIALIZING,
    val modelStatus: ModelDownloadStatus = ModelDownloadStatus.READY,
    val showSettingsBottomSheet: Boolean = false,
    val originalFramePreview: Bitmap? = null,
    val invertedFramePreview: Bitmap? = null,
    val history: List<SubtitleTranslationEntry> = emptyList()
)

object TranslatorStateRepository {
    private const val PREFS_NAME = "gamevox_hindi_prefs"
    private const val KEY_TARGET_LANG = "target_lang_code"
    private const val KEY_NETWORK_VOICE = "use_hq_network_voice"
    private const val KEY_SHOW_SCAN_BOX = "show_target_box_overlay"
    private const val KEY_PITCH = "tts_pitch_v2"
    private const val KEY_RATE = "tts_speech_rate_v2"
    private const val KEY_INVERT = "invert_colors_enabled"
    private const val KEY_INTERVAL = "capture_interval_ms"

    private val _state = MutableStateFlow(TranslatorAppState())
    val state: StateFlow<TranslatorAppState> = _state.asStateFlow()

    @Volatile
    private var lastDialogChangeTimestamp: Long = 0L

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val langCode = prefs.getString(KEY_TARGET_LANG, TargetLanguage.HINDI.code)
        val targetLang = TargetLanguage.fromCode(langCode)
        val useHqVoice = prefs.getBoolean(KEY_NETWORK_VOICE, true)
        val showScanBox = prefs.getBoolean(KEY_SHOW_SCAN_BOX, true)
        val pitch = prefs.getFloat(KEY_PITCH, 0.95f).coerceIn(0.4f, 1.8f)
        val rate = prefs.getFloat(KEY_RATE, 0.95f).coerceIn(0.4f, 1.8f)
        val invert = prefs.getBoolean(KEY_INVERT, true)
        val interval = prefs.getLong(KEY_INTERVAL, 2000L).coerceIn(1000L, 5000L)
        _state.update {
            it.copy(
                targetLanguage = targetLang,
                useHighQualityNetworkVoice = useHqVoice,
                showTargetBoxOverlay = showScanBox,
                ttsPitch = pitch,
                ttsSpeechRate = rate,
                invertColorsEnabled = invert,
                captureIntervalMs = interval
            )
        }
    }

    fun updateTargetLanguage(context: Context, language: TargetLanguage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TARGET_LANG, language.code)
            .apply()
        _state.update {
            it.copy(
                targetLanguage = language,
                lastSpokenText = ""
            )
        }
    }

    fun updateUseHighQualityNetworkVoice(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NETWORK_VOICE, enabled)
            .apply()
        _state.update { it.copy(useHighQualityNetworkVoice = enabled) }
    }

    fun setActiveVoiceName(voiceName: String) {
        _state.update { it.copy(activeVoiceName = voiceName) }
    }

    fun updateVoiceTelemetry(
        activeVoiceName: String,
        availableVoicesCount: Int,
        voiceDownloadMessage: String
    ) {
        _state.update {
            it.copy(
                activeVoiceName = activeVoiceName,
                availableVoicesCount = availableVoicesCount,
                voiceDownloadMessage = voiceDownloadMessage
            )
        }
    }

    fun updateShowTargetBoxOverlay(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_SCAN_BOX, show)
            .apply()
        _state.update { it.copy(showTargetBoxOverlay = show) }
    }

    fun updateTargetBoxScreenRect(rect: Rect?) {
        _state.update { it.copy(targetBoxScreenRect = rect?.let { r -> Rect(r) }) }
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

    fun setAccessibilityConnected(connected: Boolean) {
        _state.update { it.copy(isAccessibilityConnected = connected) }
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
        val now = System.currentTimeMillis()
        if (show && _state.value.showSettingsBottomSheet) {
            return
        }
        if (show && (now - lastDialogChangeTimestamp) < 400L) {
            return
        }
        lastDialogChangeTimestamp = now
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
                id = System.nanoTime(),
                timestamp = timestamp,
                extractedEnglish = english,
                translatedHindi = hindi,
                targetLanguage = current.targetLanguage,
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
