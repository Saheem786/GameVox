package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.engine.PronunciationGlossary
import com.example.engine.SpeechEmotion
import com.example.service.CloudTtsProvider
import com.example.service.FreeVoiceBackend
import com.example.service.InstalledTtsEngineInfo
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    CHECKING("Checking Offline Pack..."),
    DOWNLOADING("Downloading Offline Hindi Model (~30 MB)..."),
    READY("On-Device ML Kit Offline Pack Ready"),
    OFFLINE_FALLBACK("Limited Local Phrasebook (Offline Pack Missing)")
}

enum class TranslationMode(
    val code: String,
    val displayName: String,
    val shortBadge: String,
    val costAndLimitNote: String
) {
    ON_DEVICE_MLKIT(
        code = "on_device_mlkit",
        displayName = "On-Device Offline (ML Kit)",
        shortBadge = "Offline • Private",
        costAndLimitNote = "Downloads ~30 MB Hindi pack once. Zero cloud cost, never sends screen text to any server. Translates line-by-line without scene memory."
    ),
    CONTEXT_AWARE_AI(
        code = "context_aware_ai",
        displayName = "Context-Aware AI (Optional Cloud)",
        shortBadge = "Online • Cloud Quota",
        costAndLimitNote = "Optional paid/quota-limited Gemini cloud mode (requires valid Firebase config & internet). Sends cropped subtitle text + last 4 dialogue lines when consented."
    );

    companion object {
        fun fromCode(code: String?): TranslationMode {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ON_DEVICE_MLKIT
        }
    }
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
    val hasActiveMediaProjection: Boolean = false,
    val isProtectedScreenDetected: Boolean = false,
    val captureStatusMessage: String = "Ready — tap Start to capture subtitles",
    val isCapturingOcr: Boolean = false,
    val showTargetBoxOverlay: Boolean = true,
    val isSubtitleVisible: Boolean = true,
    val isVoiceMuted: Boolean = false,
    val targetBoxScreenRect: Rect? = null,
    val targetLanguage: TargetLanguage = TargetLanguage.HINDI,
    val translationMode: TranslationMode = TranslationMode.ON_DEVICE_MLKIT,
    val aiPrivacyConsentAccepted: Boolean = false,
    val useHighQualityNetworkVoice: Boolean = false,
    val ssmlEnabledOnDevice: Boolean = false,
    val cloudTtsProvider: CloudTtsProvider = CloudTtsProvider.ANDROID_SSML_NETWORK,
    val freeVoiceBackend: FreeVoiceBackend = FreeVoiceBackend.ANDROID_USER_SELECTED,
    val selectedTtsEnginePackage: String = "",
    val activeTtsEnginePackage: String = "",
    val activeTtsEngineLabel: String = "System Default TTS",
    val isSupertonicOrSherpaActive: Boolean = false,
    val installedTtsEngines: List<InstalledTtsEngineInfo> = emptyList(),
    val supportsDistinctGenderVoices: Boolean = false,
    val genderVoiceCapabilityNote: String = "Checking installed TTS engine voices...",
    val kokoroModelPath: String = "",
    val kokoroUseGpu: Boolean = true,
    val kokoroStatusMessage: String = "Optional Kokoro-82M (.litertlm) not configured",
    val userGoogleCloudApiKey: String = "",
    val userElevenLabsApiKey: String = "",
    val userOpenAiApiKey: String = "",
    val activeApiKeyStatusNote: String = "100% Free Neural / On-Device TTS (No API key or billing)",
    val activeVoiceName: String = "User-Selected Android TTS (hi-IN)",
    val availableVoicesCount: Int = 0,
    val voiceDownloadMessage: String = "Checking user-selected Android TTS engine...",
    val mlKitModelStatusText: String = "Offline Hindi pack: checking...",
    val firebaseAiStatusText: String = "Not checked",
    val translationSourceLabel: String = "Idle",
    val captureLatencyMs: Long = 0L,
    val ocrLatencyMs: Long = 0L,
    val translationLatencyMs: Long = 0L,
    val totalPipelineLatencyMs: Long = 0L,
    val ttsInitLatencyMs: Long = 0L,
    val ttsFirstAudioLatencyMs: Long = 0L,
    val ttsSpeechDurationMs: Long = 0L,
    val isRunningTtsBenchmark: Boolean = false,
    val ttsBenchmarkSummary: String = "Not run yet — tap Run TTS Benchmark on an Android device",
    val roiDimensionsText: String = "—",
    val lastTtsEvent: String = "IDLE",
    val lastTtsUtteranceId: String = "",
    val lastTtsError: String = "",
    val lastPipelineError: String = "",
    val lastDetectedEmotion: SpeechEmotion = SpeechEmotion.NEUTRAL,
    val lastEmotionReason: String = "Normal sentence → Neutral (18yo natural baseline)",
    val lastSsmlPreview: String = "",
    val ttsPitch: Float = 1.0f,
    val ttsSpeechRate: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val invertColorsEnabled: Boolean = true,
    val captureIntervalMs: Long = 900L,
    val lastExtractedEnglish: String = "Drag the green subtitle box to the bottom of the screen...",
    val lastTranslatedHindi: String = "सबटायटल बॉक्स को गेम टेक्स्ट पर रखें...",
    val lastSpokenText: String = "",
    val scanFrameCount: Int = 0,
    val duplicateSkippedCount: Int = 0,
    val queuedSubtitlesCount: Int = 0,
    val skippedRapidCount: Int = 0,
    val pronunciationGlossary: Map<String, String> = PronunciationGlossary.DEFAULT_ENTRIES,
    val ttsStatus: TtsEngineStatus = TtsEngineStatus.INITIALIZING,
    val modelStatus: ModelDownloadStatus = ModelDownloadStatus.CHECKING,
    val showSettingsBottomSheet: Boolean = false,
    val originalFramePreview: Bitmap? = null,
    val invertedFramePreview: Bitmap? = null,
    val history: List<SubtitleTranslationEntry> = emptyList()
) {
    fun savedKeyFor(provider: CloudTtsProvider): String = ""
}

object TranslatorStateRepository {
    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PREFS_NAME = "gamevox_hindi_prefs"
    private const val KEY_TARGET_LANG = "target_lang_code"
    private const val KEY_NETWORK_VOICE = "use_hq_network_voice"
    private const val KEY_SHOW_SCAN_BOX = "show_target_box_overlay"
    private const val KEY_SHOW_SUBTITLE_BOX = "show_subtitle_captions_box_v1"
    private const val KEY_VOICE_MUTED = "is_voice_muted_v1"
    private const val KEY_PITCH = "tts_pitch_v3"
    private const val KEY_RATE = "tts_speech_rate_v3"
    private const val KEY_VOLUME = "tts_volume_v1"
    private const val KEY_INVERT = "invert_colors_enabled"
    private const val KEY_INTERVAL = "capture_interval_ms"
    private const val KEY_SSML_ENABLED = "ssml_enabled_cloud_or_opt_in_v2"
    private const val KEY_CLOUD_TTS_PROVIDER = "cloud_tts_provider_v1"
    private const val KEY_SELECTED_ANDROID_TTS_ENGINE = "selected_android_tts_engine_v1"
    private const val KEY_FREE_VOICE_BACKEND = "free_voice_backend_v1"
    private const val KEY_KOKORO_MODEL_PATH = "kokoro_model_path_v1"
    private const val KEY_KOKORO_USE_GPU = "kokoro_use_gpu_v1"
    private const val KEY_TRANSLATION_MODE = "translation_mode_v1"
    private const val KEY_AI_PRIVACY_CONSENT = "ai_privacy_consent_v1"
    private const val KEY_GLOSSARY_JSON = "pronunciation_glossary_v1"

    private val _state = MutableStateFlow(TranslatorAppState())
    val state: StateFlow<TranslatorAppState> = _state.asStateFlow()

    @Volatile
    private var lastDialogChangeTimestamp: Long = 0L

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val langCode = prefs.getString(KEY_TARGET_LANG, TargetLanguage.HINDI.code)
        val targetLang = TargetLanguage.fromCode(langCode)
        val translationMode = TranslationMode.fromCode(
            prefs.getString(KEY_TRANSLATION_MODE, TranslationMode.ON_DEVICE_MLKIT.code)
        )
        val aiConsent = prefs.getBoolean(KEY_AI_PRIVACY_CONSENT, false)
        val useHqVoice = prefs.getBoolean(KEY_NETWORK_VOICE, false)
        val ssmlEnabled = prefs.getBoolean(KEY_SSML_ENABLED, false)
        val cloudProvider = CloudTtsProvider.ANDROID_SSML_NETWORK
        val selectedAndroidEnginePkg = prefs.getString(KEY_SELECTED_ANDROID_TTS_ENGINE, "").orEmpty().trim()
        val freeVoiceBackend = FreeVoiceBackend.fromCode(
            prefs.getString(KEY_FREE_VOICE_BACKEND, FreeVoiceBackend.ANDROID_USER_SELECTED.code)
        )
        val kokoroModelPath = prefs.getString(KEY_KOKORO_MODEL_PATH, "").orEmpty().trim()
        val kokoroUseGpu = prefs.getBoolean(KEY_KOKORO_USE_GPU, true)
        val showScanBox = prefs.getBoolean(KEY_SHOW_SCAN_BOX, true)
        val showSubtitleBox = prefs.getBoolean(KEY_SHOW_SUBTITLE_BOX, true)
        val voiceMuted = prefs.getBoolean(KEY_VOICE_MUTED, false)
        val pitch = prefs.getFloat(KEY_PITCH, 1.0f).coerceIn(0.4f, 1.8f)
        val rate = prefs.getFloat(KEY_RATE, 1.0f).coerceIn(0.4f, 1.8f)
        val volume = prefs.getFloat(KEY_VOLUME, 1.0f).coerceIn(0.1f, 1.0f)
        val invert = prefs.getBoolean(KEY_INVERT, true)
        val interval = prefs.getLong(KEY_INTERVAL, 900L).coerceIn(600L, 5000L)
        val glossary = PronunciationGlossary.fromJsonString(prefs.getString(KEY_GLOSSARY_JSON, null))
        _state.update {
            it.copy(
                targetLanguage = targetLang,
                translationMode = translationMode,
                aiPrivacyConsentAccepted = aiConsent,
                useHighQualityNetworkVoice = useHqVoice,
                ssmlEnabledOnDevice = ssmlEnabled,
                cloudTtsProvider = cloudProvider,
                selectedTtsEnginePackage = selectedAndroidEnginePkg,
                freeVoiceBackend = freeVoiceBackend,
                kokoroModelPath = kokoroModelPath,
                kokoroUseGpu = kokoroUseGpu,
                showTargetBoxOverlay = showScanBox,
                isSubtitleVisible = showSubtitleBox,
                isVoiceMuted = voiceMuted,
                ttsPitch = pitch,
                ttsSpeechRate = rate,
                ttsVolume = volume,
                invertColorsEnabled = invert,
                captureIntervalMs = interval,
                pronunciationGlossary = glossary
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

    fun updateSsmlEnabledOnDevice(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SSML_ENABLED, enabled)
            .apply()
        _state.update { it.copy(ssmlEnabledOnDevice = enabled) }
    }

    fun updateCloudTtsProvider(context: Context, provider: CloudTtsProvider) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLOUD_TTS_PROVIDER, CloudTtsProvider.ANDROID_SSML_NETWORK.code)
            .apply()
        _state.update {
            it.copy(
                cloudTtsProvider = CloudTtsProvider.ANDROID_SSML_NETWORK,
                activeApiKeyStatusNote = "100% Free Neural / On-Device TTS (No API key or billing)"
            )
        }
        dataStoreScope.launch {
            TtsSettingsDataStore.saveProvider(context, CloudTtsProvider.ANDROID_SSML_NETWORK)
        }
    }

    suspend fun loadCloudTtsFromDataStore(context: Context) {
        val stored = TtsSettingsDataStore.readPreferences(context)
        _state.update { current ->
            current.copy(
                cloudTtsProvider = CloudTtsProvider.ANDROID_SSML_NETWORK,
                selectedTtsEnginePackage = stored.selectedTtsEnginePackage.ifBlank { current.selectedTtsEnginePackage },
                freeVoiceBackend = stored.freeVoiceBackend,
                kokoroModelPath = stored.kokoroModelPath.ifBlank { current.kokoroModelPath },
                kokoroUseGpu = stored.kokoroUseGpu,
                userGoogleCloudApiKey = "",
                userElevenLabsApiKey = "",
                userOpenAiApiKey = ""
            )
        }
    }

    fun updateSelectedTtsEnginePackage(context: Context, enginePackage: String) {
        val cleaned = enginePackage.trim()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_ANDROID_TTS_ENGINE, cleaned)
            .apply()
        _state.update { it.copy(selectedTtsEnginePackage = cleaned) }
        dataStoreScope.launch {
            TtsSettingsDataStore.saveSelectedAndroidTtsEngine(context, cleaned)
        }
    }

    fun updateFreeVoiceBackend(context: Context, backend: FreeVoiceBackend) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FREE_VOICE_BACKEND, backend.code)
            .apply()
        _state.update { it.copy(freeVoiceBackend = backend) }
        dataStoreScope.launch {
            TtsSettingsDataStore.saveFreeVoiceBackend(context, backend)
        }
    }

    fun updateKokoroConfig(
        context: Context,
        modelPath: String,
        useGpu: Boolean = _state.value.kokoroUseGpu,
        statusMessage: String? = null
    ) {
        val cleaned = modelPath.trim()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_KOKORO_MODEL_PATH, cleaned)
            .putBoolean(KEY_KOKORO_USE_GPU, useGpu)
            .apply()
        _state.update {
            it.copy(
                kokoroModelPath = cleaned,
                kokoroUseGpu = useGpu,
                kokoroStatusMessage = statusMessage ?: it.kokoroStatusMessage
            )
        }
        dataStoreScope.launch {
            TtsSettingsDataStore.saveKokoroConfig(context, cleaned, useGpu)
        }
    }

    fun updateKokoroStatusMessage(message: String) {
        _state.update { it.copy(kokoroStatusMessage = message) }
    }

    fun updateTtsEngineCatalogAndCapability(
        installedEngines: List<InstalledTtsEngineInfo>,
        activeEnginePackage: String,
        activeEngineLabel: String,
        isSupertonicOrSherpaActive: Boolean,
        supportsDistinctGenderVoices: Boolean,
        genderVoiceCapabilityNote: String
    ) {
        _state.update {
            it.copy(
                installedTtsEngines = installedEngines,
                activeTtsEnginePackage = activeEnginePackage,
                activeTtsEngineLabel = activeEngineLabel,
                isSupertonicOrSherpaActive = isSupertonicOrSherpaActive,
                supportsDistinctGenderVoices = supportsDistinctGenderVoices,
                genderVoiceCapabilityNote = genderVoiceCapabilityNote
            )
        }
    }

    fun updateTtsLatencyBenchmarks(
        initLatencyMs: Long? = null,
        firstAudioLatencyMs: Long? = null,
        speechDurationMs: Long? = null,
        isRunningBenchmark: Boolean? = null,
        summary: String? = null
    ) {
        _state.update { current ->
            current.copy(
                ttsInitLatencyMs = initLatencyMs ?: current.ttsInitLatencyMs,
                ttsFirstAudioLatencyMs = firstAudioLatencyMs ?: current.ttsFirstAudioLatencyMs,
                ttsSpeechDurationMs = speechDurationMs ?: current.ttsSpeechDurationMs,
                isRunningTtsBenchmark = isRunningBenchmark ?: current.isRunningTtsBenchmark,
                ttsBenchmarkSummary = summary ?: current.ttsBenchmarkSummary
            )
        }
    }

    fun saveCloudTtsConfig(
        context: Context,
        provider: CloudTtsProvider,
        apiKey: String
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLOUD_TTS_PROVIDER, CloudTtsProvider.ANDROID_SSML_NETWORK.code)
            .apply()

        _state.update { current ->
            current.copy(
                cloudTtsProvider = CloudTtsProvider.ANDROID_SSML_NETWORK,
                userGoogleCloudApiKey = "",
                userElevenLabsApiKey = "",
                userOpenAiApiKey = "",
                activeApiKeyStatusNote = "Paid cloud TTS APIs are disabled — Free Android TTS active."
            )
        }

        dataStoreScope.launch {
            TtsSettingsDataStore.saveProviderAndApiKey(
                context = context,
                provider = CloudTtsProvider.ANDROID_SSML_NETWORK,
                apiKey = ""
            )
        }
    }

    fun setCloudTtsStatusMessage(message: String) {
        _state.update {
            it.copy(
                activeApiKeyStatusNote = message,
                voiceDownloadMessage = message
            )
        }
    }

    fun updateEmotionTelemetry(
        emotion: SpeechEmotion,
        reason: String,
        ssmlPreview: String
    ) {
        _state.update {
            it.copy(
                lastDetectedEmotion = emotion,
                lastEmotionReason = reason,
                lastSsmlPreview = ssmlPreview
            )
        }
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

    /**
     * Toggles ONLY the Green Scan Box visibility (`showTargetBoxOverlay`) on Single Tap (`onTap`).
     * Does NOT affect [isSubtitleVisible] (Translated Captions Box)!
     */
    fun toggleScanBoxVisible(context: Context): Boolean {
        val newVisible = !_state.value.showTargetBoxOverlay
        updateShowTargetBoxOverlay(context, newVisible)
        return newVisible
    }

    fun updateShowTargetBoxOverlay(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_SCAN_BOX, show)
            .apply()
        _state.update {
            it.copy(
                showTargetBoxOverlay = show,
                isCapturingOcr = if (!show) false else (it.isServiceRunning && it.hasActiveMediaProjection)
            )
        }
    }

    /**
     * Toggles ONLY the Voice (TTS Mute/Unmute) state on Long Press (`onLongPress`).
     * Voice is ON (`isVoiceMuted = false`) by default.
     */
    fun toggleVoiceMuted(context: Context): Boolean {
        val newMuted = !_state.value.isVoiceMuted
        setVoiceMuted(context, newMuted)
        return newMuted
    }

    fun setVoiceMuted(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VOICE_MUTED, muted)
            .apply()
        _state.update { it.copy(isVoiceMuted = muted) }
    }

    /**
     * Toggles ONLY the Translated Captions Box visibility (`isSubtitleVisible`) on Double Tap (`onDoubleTap`).
     * Does NOT affect [showTargetBoxOverlay] (Green Scan Box) or [isVoiceMuted]!
     */
    fun toggleSubtitleVisible(context: Context): Boolean {
        val newVisible = !_state.value.isSubtitleVisible
        setSubtitleVisible(context, newVisible)
        return newVisible
    }

    fun setSubtitleVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_SUBTITLE_BOX, visible)
            .apply()
        _state.update {
            it.copy(
                isSubtitleVisible = visible
            )
        }
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

    fun updateTtsVolume(context: Context, volume: Float) {
        val clamped = volume.coerceIn(0.1f, 1.0f)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_VOLUME, clamped)
            .apply()
        _state.update { it.copy(ttsVolume = clamped) }
    }

    fun updateTranslationMode(
        context: Context,
        mode: TranslationMode,
        aiPrivacyConsentAccepted: Boolean = _state.value.aiPrivacyConsentAccepted
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRANSLATION_MODE, mode.code)
            .putBoolean(KEY_AI_PRIVACY_CONSENT, aiPrivacyConsentAccepted)
            .apply()
        _state.update {
            it.copy(
                translationMode = mode,
                aiPrivacyConsentAccepted = aiPrivacyConsentAccepted
            )
        }
    }

    fun upsertGlossaryEntry(context: Context, term: String, pronunciation: String): Boolean {
        val sanitized = PronunciationGlossary.sanitizeEntry(term, pronunciation) ?: return false
        val current = _state.value.pronunciationGlossary.toMutableMap()
        val existingKey = PronunciationGlossary.normalizedKeyMatch(current, sanitized.first)
        if (existingKey != null && existingKey != sanitized.first) {
            current.remove(existingKey)
        }
        current[sanitized.first] = sanitized.second
        saveGlossary(context, current)
        return true
    }

    fun removeGlossaryEntry(context: Context, term: String) {
        val updated = _state.value.pronunciationGlossary.toMutableMap()
        updated.remove(term)
        saveGlossary(context, updated)
    }

    fun resetGlossaryToDefaults(context: Context) {
        saveGlossary(context, PronunciationGlossary.DEFAULT_ENTRIES.toMap())
    }

    private fun saveGlossary(context: Context, glossary: Map<String, String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GLOSSARY_JSON, PronunciationGlossary.toJsonString(glossary))
            .apply()
        _state.update { it.copy(pronunciationGlossary = glossary) }
    }

    fun updateInvertColors(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INVERT, enabled)
            .apply()
        _state.update { it.copy(invertColorsEnabled = enabled) }
    }

    fun updateCaptureInterval(context: Context, intervalMs: Long) {
        val clamped = intervalMs.coerceIn(700L, 5000L)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_INTERVAL, clamped)
            .apply()
        _state.update { it.copy(captureIntervalMs = clamped) }
    }

    fun setServiceRunning(
        running: Boolean,
        isOverlayDemo: Boolean = false,
        hasActiveProjection: Boolean = if (!running) false else _state.value.hasActiveMediaProjection
    ) {
        _state.update {
            it.copy(
                isServiceRunning = running,
                isOverlayDemoActive = isOverlayDemo,
                hasActiveMediaProjection = hasActiveProjection,
                isCapturingOcr = if (!running) false else it.isCapturingOcr,
                isProtectedScreenDetected = if (!running) false else it.isProtectedScreenDetected
            )
        }
    }

    fun updateCaptureTelemetry(
        hasActiveProjection: Boolean = _state.value.hasActiveMediaProjection,
        isProtectedScreen: Boolean = _state.value.isProtectedScreenDetected,
        statusMessage: String = _state.value.captureStatusMessage
    ) {
        _state.update {
            it.copy(
                hasActiveMediaProjection = hasActiveProjection,
                isProtectedScreenDetected = isProtectedScreen,
                captureStatusMessage = statusMessage
            )
        }
    }

    fun updateQueueTelemetry(queuedCount: Int, skippedRapidIncrement: Int = 0) {
        _state.update {
            it.copy(
                queuedSubtitlesCount = queuedCount.coerceAtLeast(0),
                skippedRapidCount = it.skippedRapidCount + skippedRapidIncrement.coerceAtLeast(0)
            )
        }
    }

    fun setMlKitModelStatusText(text: String) {
        _state.update { it.copy(mlKitModelStatusText = text) }
    }

    fun setFirebaseAiStatusText(text: String) {
        _state.update { it.copy(firebaseAiStatusText = text) }
    }

    fun updateTtsLifecycleEvent(
        event: String,
        utteranceId: String = _state.value.lastTtsUtteranceId,
        error: String = ""
    ) {
        _state.update {
            it.copy(
                lastTtsEvent = event,
                lastTtsUtteranceId = utteranceId,
                lastTtsError = error
            )
        }
    }

    fun updateDiagnosisMetrics(
        captureLatencyMs: Long? = null,
        ocrLatencyMs: Long? = null,
        translationLatencyMs: Long? = null,
        totalPipelineLatencyMs: Long? = null,
        roiDimensionsText: String? = null,
        translationSourceLabel: String? = null,
        lastPipelineError: String? = null
    ) {
        _state.update { current ->
            current.copy(
                captureLatencyMs = captureLatencyMs ?: current.captureLatencyMs,
                ocrLatencyMs = ocrLatencyMs ?: current.ocrLatencyMs,
                translationLatencyMs = translationLatencyMs ?: current.translationLatencyMs,
                totalPipelineLatencyMs = totalPipelineLatencyMs ?: current.totalPipelineLatencyMs,
                roiDimensionsText = roiDimensionsText ?: current.roiDimensionsText,
                translationSourceLabel = translationSourceLabel ?: current.translationSourceLabel,
                lastPipelineError = lastPipelineError ?: current.lastPipelineError
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
