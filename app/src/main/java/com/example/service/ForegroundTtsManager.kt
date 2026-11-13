package com.example.service

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.TargetLanguage
import com.example.data.TranslatorStateRepository
import com.example.data.TtsEngineStatus
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real-time TTS playback telemetry and 24-band spectrum state consumed by the Jetpack Compose
 * Canvas Audio Visualizer while [TextToSpeech] is speaking.
 */
data class TtsVisualizerState(
    val isSpeaking: Boolean = false,
    val utteranceId: String = "",
    val spokenText: String = "",
    val activeVoiceName: String = "Vikram • Character A (Hero)",
    val activeSpeakerRoleLabel: String = "Character A",
    val activePitch: Float = 0.96f,
    val activeSpeechRate: Float = 0.96f,
    val currentRangeStart: Int = 0,
    val currentRangeEnd: Int = 0,
    val progressFraction: Float = 0f,
    val rmsAmplitude: Float = 0f,
    val phaseRadians: Float = 0f,
    val frequencyBands: List<Float> = List(24) { 0.08f }
)

/**
 * Dedicated TextToSpeech Initialization & Voice Pack Verification Service:
 *
 * 1. **High-Quality Network Voice Pack Inspection**:
 *    - During [TextToSpeech.OnInitListener], inspects `engine.voices` (`Set<Voice>`) for
 *      high-quality neural/network voice packs (`Voice.QUALITY_VERY_HIGH` / `Voice.QUALITY_HIGH`,
 *      `KEY_FEATURE_NETWORK_SYNTHESIS`, `isNetworkConnectionRequired`, `-network`, `wavenet`).
 *    - Checks whether each voice pack is installed (`!features.contains(KEY_FEATURE_NOT_INSTALLED)`)
 *      and verifies `engine.isLanguageAvailable(Locale("hi", "IN"))`.
 *
 * 2. **Listener for Missing Hindi Voice Data ([VoiceDataDownloadListener])**:
 *    - Notifies registered listeners via [VoiceDataDownloadListener.onVoiceDataMissing] whenever
 *      Hindi (`hi-IN`) or the active target language voice data/high-quality pack is missing,
 *      triggers background neural voice warm-up synthesis + system voice data installation
 *      (`ACTION_CHECK_TTS_DATA` / `ACTION_INSTALL_TTS_DATA`), and invokes
 *      [VoiceDataDownloadListener.onVoiceDataInstalled] as soon as `ACTION_TTS_DATA_INSTALLED`
 *      or the async voice pack watcher detects completion.
 */
class ForegroundTtsManager private constructor(context: Context) {

    /**
     * Listener notified during TTS initialization and background voice pack monitoring
     * when high-quality network voice packs are verified, missing, downloading, or newly installed.
     */
    interface VoiceDataDownloadListener {
        fun onHighQualityNetworkVoiceAvailable(
            language: TargetLanguage,
            voiceName: String,
            quality: Int
        )

        fun onVoiceDataMissing(
            language: TargetLanguage,
            missingVoiceNames: List<String>,
            reason: String
        )

        fun onVoiceDataDownloadStarted(language: TargetLanguage)

        fun onVoiceDataInstalled(
            language: TargetLanguage,
            installedVoiceName: String
        )
    }

    companion object {
        private const val TAG = "ForegroundTtsManager"
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        val HINDI_LOCALE: Locale = Locale.forLanguageTag("hi-IN")
        private val INDIAN_ENGLISH_LOCALE: Locale = Locale.forLanguageTag("en-IN")

        @Volatile
        private var INSTANCE: ForegroundTtsManager? = null

        fun getInstance(context: Context): ForegroundTtsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ForegroundTtsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val appContext: Context = context.applicationContext
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val downloadListeners = CopyOnWriteArraySet<VoiceDataDownloadListener>()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    var isTtsReady: Boolean = false
        private set

    @Volatile
    private var isInitializing: Boolean = false

    @Volatile
    private var pendingSubtitleToSpeak: String? = null

    private var asyncDownloadWatcherJob: Job? = null
    private var visualizerSyncJob: Job? = null
    private var isReceiverRegistered: Boolean = false

    private val _visualizerState = MutableStateFlow(TtsVisualizerState())
    val visualizerState: StateFlow<TtsVisualizerState> = _visualizerState.asStateFlow()

    // Maps utteranceId -> spoken text so network synthesis failures can auto-fallback to local voice
    private val activeNetworkUtterances = ConcurrentHashMap<String, String>()

    /**
     * BroadcastReceiver listening for [TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED]
     * so when Android finishes downloading Hindi or other language voice packs,
     * the TTS engine automatically reloads and notifies [VoiceDataDownloadListener.onVoiceDataInstalled].
     */
    private val ttsDataInstalledReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED) {
                Log.i(TAG, "Received ACTION_TTS_DATA_INSTALLED broadcast — refreshing voice packs")
                managerScope.launch {
                    val engine = tts
                    if (engine != null) {
                        configureHighQualityVoice(
                            engine = engine,
                            speakSampleOnUpgrade = false,
                            notifyInstalledListeners = true
                        )
                    } else {
                        initializeAsync(forceRebind = true)
                    }
                    flushPendingSubtitleIfReady()
                }
            }
        }
    }

    init {
        registerTtsDataBroadcastReceiver()
        initializeAsync()
    }

    fun addVoiceDataDownloadListener(listener: VoiceDataDownloadListener) {
        downloadListeners.add(listener)
    }

    fun removeVoiceDataDownloadListener(listener: VoiceDataDownloadListener) {
        downloadListeners.remove(listener)
    }

    /**
     * Registers the [TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED] receiver with the application
     * context so the Foreground Service detects completed asynchronous language downloads immediately.
     */
    fun registerTtsDataBroadcastReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter(TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED)
            ContextCompat.registerReceiver(
                appContext,
                ttsDataInstalledReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register ACTION_TTS_DATA_INSTALLED receiver", e)
        }
    }

    /**
     * Dedicated [TextToSpeech.OnInitListener] that checks for high-quality network voice packs
     * (`Voice.QUALITY_HIGH` / `QUALITY_VERY_HIGH` & `KEY_FEATURE_NETWORK_SYNTHESIS`) and invokes
     * [VoiceDataDownloadListener] to download Hindi voice data if it is missing.
     */
    private fun createTtsInitListener(isGoogleEngineAttempt: Boolean): TextToSpeech.OnInitListener {
        return TextToSpeech.OnInitListener { status ->
            isInitializing = false
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@OnInitListener
                attachUtteranceProgressListener(engine)
                configureHighQualityVoice(
                    engine = engine,
                    speakSampleOnUpgrade = false,
                    notifyInstalledListeners = false
                )
                flushPendingSubtitleIfReady()
            } else if (isGoogleEngineAttempt) {
                Log.w(TAG, "Google TTS package init returned $status, falling back to system default TTS")
                initializeSystemDefaultFallback()
            } else {
                isTtsReady = false
                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.ERROR)
                TranslatorStateRepository.updateVoiceTelemetry(
                    activeVoiceName = "TTS Unavailable",
                    availableVoicesCount = 0,
                    voiceDownloadMessage = "TTS Engine failed to initialize (status $status)"
                )
            }
        }
    }

    /**
     * Starts asynchronous initialization of [TextToSpeech] inside the Foreground Service,
     * preferring Google Speech Services (`com.google.android.tts`) for neural network voices.
     */
    @Synchronized
    fun initializeAsync(forceRebind: Boolean = false) {
        if (!forceRebind && tts != null && isTtsReady) {
            refreshVoiceConfiguration()
            return
        }
        if (isInitializing && !forceRebind) return

        isInitializing = true
        isTtsReady = false
        TranslatorStateRepository.setTtsStatus(TtsEngineStatus.INITIALIZING)

        try {
            tts?.shutdown()
            tts = TextToSpeech(
                appContext,
                createTtsInitListener(isGoogleEngineAttempt = true),
                GOOGLE_TTS_PACKAGE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Exception binding to Google TTS package, falling back to default engine", e)
            initializeSystemDefaultFallback()
        }
    }

    private fun initializeSystemDefaultFallback() {
        isInitializing = true
        try {
            tts?.shutdown()
            tts = TextToSpeech(
                appContext,
                createTtsInitListener(isGoogleEngineAttempt = false)
            )
        } catch (e: Exception) {
            isInitializing = false
            isTtsReady = false
            TranslatorStateRepository.setTtsStatus(TtsEngineStatus.ERROR)
            Log.w(TAG, "Fallback TextToSpeech initialization failed", e)
        }
    }

    /**
     * Attaches an [UtteranceProgressListener] that:
     * 1. Synchronizes real-time TTS playback events (`onStart`, `onRangeStart`, `onAudioAvailable`,
     *    `onDone`, `onStop`, `onError`) with [visualizerState] for the Jetpack Compose Canvas Audio Visualizer.
     * 2. Automatically retries using local synthesis if a network voice call times out or errors.
     */
    private fun attachUtteranceProgressListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == null || utteranceId == "warmup_async_voice_download") return
                _visualizerState.update { current ->
                    current.copy(
                        isSpeaking = true,
                        utteranceId = utteranceId
                    )
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId == null || utteranceId == "warmup_async_voice_download") return
                _visualizerState.update { current ->
                    val len = current.spokenText.length.coerceAtLeast(1)
                    val safeStart = start.coerceIn(0, len)
                    val safeEnd = end.coerceIn(safeStart, len)
                    val fraction = (safeEnd.toFloat() / len.toFloat()).coerceIn(0f, 1f)
                    current.copy(
                        isSpeaking = true,
                        currentRangeStart = safeStart,
                        currentRangeEnd = safeEnd,
                        progressFraction = fraction
                    )
                }
            }

            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                if (utteranceId == null || utteranceId == "warmup_async_voice_download" || audio == null || audio.size < 4) return
                val (rms, bands) = computePcmSpectrumBands(audio, _visualizerState.value.activePitch)
                _visualizerState.update { current ->
                    current.copy(
                        isSpeaking = true,
                        rmsAmplitude = rms,
                        frequencyBands = bands
                    )
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null) {
                    activeNetworkUtterances.remove(utteranceId)
                    if (utteranceId == "warmup_async_voice_download") {
                        managerScope.launch {
                            refreshVoiceConfiguration()
                        }
                        return
                    }
                    if (_visualizerState.value.utteranceId == utteranceId) {
                        stopVisualizerSync()
                    }
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId != null && _visualizerState.value.utteranceId == utteranceId) {
                    stopVisualizerSync()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleUtteranceFailure(utteranceId, errorCode = -1)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleUtteranceFailure(utteranceId, errorCode = errorCode)
            }
        })
    }

    private fun handleUtteranceFailure(utteranceId: String?, errorCode: Int) {
        if (utteranceId == null) return
        val fallbackText = activeNetworkUtterances.remove(utteranceId)
        if (fallbackText != null) {
            Log.w(TAG, "Network synthesis error ($errorCode) for $utteranceId; retrying with local synthesis")
            managerScope.launch {
                speakInternal(
                    text = fallbackText,
                    allowNetworkSynthesis = false
                )
            }
        } else if (_visualizerState.value.utteranceId == utteranceId) {
            stopVisualizerSync()
        }
    }

    /**
     * Checks `engine.isLanguageAvailable` and inspects `engine.voices` for High-Quality Network
     * Voice packs (`hi-IN` or active [TargetLanguage]).
     * If Hindi/target voice data or high-quality voice packs are missing, notifies
     * [VoiceDataDownloadListener.onVoiceDataMissing] and starts asynchronous voice pack download.
     */
    fun configureHighQualityVoice(
        engine: TextToSpeech,
        speakSampleOnUpgrade: Boolean = false,
        notifyInstalledListeners: Boolean = false
    ) {
        val state = TranslatorStateRepository.state.value
        val targetLang = state.targetLanguage
        val primaryLocale = targetLang.locale
        val genericLocale = Locale.forLanguageTag(targetLang.code)

        try {
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            engine.setAudioAttributes(audioAttrs)
        } catch (_: Exception) {
        }

        engine.setPitch(state.ttsPitch)
        engine.setSpeechRate(state.ttsSpeechRate)

        val hindiAvailability = try {
            engine.isLanguageAvailable(HINDI_LOCALE)
        } catch (_: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }

        val targetAvailability = try {
            engine.isLanguageAvailable(primaryLocale)
        } catch (_: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }

        val setLangResult = engine.setLanguage(primaryLocale)
        val isPrimarySupported = setLangResult != TextToSpeech.LANG_MISSING_DATA &&
            setLangResult != TextToSpeech.LANG_NOT_SUPPORTED

        if (!isPrimarySupported) {
            val genericResult = engine.setLanguage(genericLocale)
            if (genericResult == TextToSpeech.LANG_MISSING_DATA ||
                genericResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setLanguage(INDIAN_ENGLISH_LOCALE)
                engine.setLanguage(primaryLocale)
            }
        }

        val voiceSelection = findBestVoiceForLanguage(
            engine = engine,
            targetLanguage = targetLang,
            preferNetworkVoice = state.useHighQualityNetworkVoice
        )

        val chosenVoice = voiceSelection.bestInstalledNetworkVoice
            ?: voiceSelection.bestInstalledVoice
            ?: voiceSelection.bestDownloadableVoice

        if (chosenVoice != null) {
            try {
                engine.voice = chosenVoice
            } catch (_: Exception) {
            }
        }

        isTtsReady = true

        val isHindiMissingData = hindiAvailability == TextToSpeech.LANG_MISSING_DATA ||
            hindiAvailability == TextToSpeech.LANG_NOT_SUPPORTED

        when {
            // Case 1: High-Quality Network Voice Pack (or installed voice pack) is ready
            voiceSelection.bestInstalledNetworkVoice != null || voiceSelection.bestInstalledVoice != null -> {
                val activeVoice = voiceSelection.bestInstalledNetworkVoice
                    ?: voiceSelection.bestInstalledVoice!!
                val isHighQualityNet = voiceSelection.bestInstalledNetworkVoice != null ||
                    activeVoice.quality >= Voice.QUALITY_HIGH
                val label = if (isHighQualityNet) {
                    "HD Neural (${activeVoice.name})"
                } else {
                    "Installed (${activeVoice.name})"
                }

                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.READY)
                TranslatorStateRepository.updateVoiceTelemetry(
                    activeVoiceName = label,
                    availableVoicesCount = voiceSelection.totalMatchingVoices,
                    voiceDownloadMessage = "HD Voice Ready • ${voiceSelection.installedMatchingVoices}/${voiceSelection.totalMatchingVoices} ${targetLang.displayName} packs installed"
                )

                downloadListeners.forEach { listener ->
                    listener.onHighQualityNetworkVoiceAvailable(
                        language = targetLang,
                        voiceName = activeVoice.name,
                        quality = activeVoice.quality
                    )
                    if (notifyInstalledListeners) {
                        listener.onVoiceDataInstalled(
                            language = targetLang,
                            installedVoiceName = activeVoice.name
                        )
                    }
                }

                if (speakSampleOnUpgrade) {
                    speakNonBlocking(targetLang.sampleGreeting)
                }
            }

            // Case 2: Voice packs exist in catalog with KEY_FEATURE_NOT_INSTALLED, or LANG_MISSING_DATA
            targetAvailability == TextToSpeech.LANG_MISSING_DATA ||
                isHindiMissingData ||
                voiceSelection.uninstalledVoiceNames.isNotEmpty() -> {

                val missingNames = voiceSelection.uninstalledVoiceNames
                val candidateName = voiceSelection.bestDownloadableVoice?.name
                    ?: "Google Network (${targetLang.ttsLocaleTag})"

                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.MISSING_HINDI_DATA)
                TranslatorStateRepository.updateVoiceTelemetry(
                    activeVoiceName = "Cloud Neural ($candidateName)",
                    availableVoicesCount = voiceSelection.totalMatchingVoices,
                    voiceDownloadMessage = "${targetLang.displayName} HD voice pack missing locally — Downloading in background..."
                )

                val reason = if (missingNames.isNotEmpty()) {
                    "High-quality ${targetLang.displayName} network voice pack (${missingNames.first()}) is not installed yet."
                } else {
                    "${targetLang.displayName} (${targetLang.ttsLocaleTag}) voice data is missing on this device."
                }

                downloadListeners.forEach { listener ->
                    listener.onVoiceDataMissing(
                        language = targetLang,
                        missingVoiceNames = missingNames,
                        reason = reason
                    )
                    listener.onVoiceDataDownloadStarted(targetLang)
                }

                // Trigger background neural voice pack warm-up & watcher automatically
                triggerSilentAsyncVoicePackWarmup(engine, targetLang)
                startAsyncLanguageDataDownloadWatcher(targetLang)
            }

            // Case 3: Engine supports the locale directly even if getVoices() returned an empty catalog
            isPrimarySupported -> {
                val activeName = if (state.useHighQualityNetworkVoice) {
                    "Google Network Synthesis (${targetLang.ttsLocaleTag})"
                } else {
                    "System Default (${targetLang.ttsLocaleTag})"
                }
                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.READY)
                TranslatorStateRepository.updateVoiceTelemetry(
                    activeVoiceName = activeName,
                    availableVoicesCount = 1,
                    voiceDownloadMessage = "${targetLang.displayName} Network TTS Active"
                )
                downloadListeners.forEach { listener ->
                    listener.onHighQualityNetworkVoiceAvailable(
                        language = targetLang,
                        voiceName = activeName,
                        quality = Voice.QUALITY_HIGH
                    )
                }
            }

            // Case 4: Locale not installed at all — notify listener to download Hindi voice data
            else -> {
                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.MISSING_HINDI_DATA)
                TranslatorStateRepository.updateVoiceTelemetry(
                    activeVoiceName = "Missing (${targetLang.ttsLocaleTag})",
                    availableVoicesCount = 0,
                    voiceDownloadMessage = "Hindi (${targetLang.ttsLocaleTag}) voice data missing — Tap to download"
                )
                downloadListeners.forEach { listener ->
                    listener.onVoiceDataMissing(
                        language = targetLang,
                        missingVoiceNames = emptyList(),
                        reason = "${targetLang.displayName} (${targetLang.ttsLocaleTag}) voice data is not installed."
                    )
                }
                triggerSilentAsyncVoicePackWarmup(engine, targetLang)
            }
        }
    }

    /**
     * Handles the result of [TextToSpeech.Engine.ACTION_CHECK_TTS_DATA] launched by an Activity,
     * checking `EXTRA_AVAILABLE_VOICES` and `EXTRA_UNAVAILABLE_VOICES` for `hi-IN` / `hi-IND`.
     * Returns `true` if Hindi voice data is confirmed installed, or `false` if missing (and notifies listeners).
     */
    fun handleCheckTtsDataResult(resultCode: Int, data: Intent?): Boolean {
        val targetLang = TranslatorStateRepository.state.value.targetLanguage
        if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS && data != null) {
            val available = data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES).orEmpty()
            val unavailable = data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES).orEmpty()

            val hasHindiAvailable = available.any {
                it.startsWith("hi", ignoreCase = true) ||
                    it.startsWith(targetLang.code, ignoreCase = true)
            }
            val isHindiUnavailable = unavailable.any {
                it.startsWith("hi", ignoreCase = true) ||
                    it.startsWith(targetLang.code, ignoreCase = true)
            }

            if (!hasHindiAvailable && isHindiUnavailable) {
                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.MISSING_HINDI_DATA)
                downloadListeners.forEach { listener ->
                    listener.onVoiceDataMissing(
                        language = targetLang,
                        missingVoiceNames = unavailable.filter { it.startsWith(targetLang.code, ignoreCase = true) },
                        reason = "TTS voice check reported ${targetLang.displayName} in EXTRA_UNAVAILABLE_VOICES."
                    )
                }
                return false
            }
            refreshVoiceConfiguration()
            return true
        } else {
            TranslatorStateRepository.setTtsStatus(TtsEngineStatus.MISSING_HINDI_DATA)
            downloadListeners.forEach { listener ->
                listener.onVoiceDataMissing(
                    language = targetLang,
                    missingVoiceNames = emptyList(),
                    reason = "TTS check returned $resultCode — Hindi voice data needs to be downloaded."
                )
            }
            return false
        }
    }

    private data class VoiceCatalogSelection(
        val bestInstalledNetworkVoice: Voice?,
        val bestInstalledVoice: Voice?,
        val bestDownloadableVoice: Voice?,
        val uninstalledVoiceNames: List<String>,
        val installedMatchingVoices: Int,
        val totalMatchingVoices: Int
    )

    /**
     * Inspects `engine.voices` to identify:
     * 1. [VoiceCatalogSelection.bestInstalledNetworkVoice]: Installed high-quality neural/network voice pack
     * 2. [VoiceCatalogSelection.bestInstalledVoice]: Best installed local voice pack
     * 3. [VoiceCatalogSelection.bestDownloadableVoice]: Best high-quality voice pack flagged `KEY_FEATURE_NOT_INSTALLED`
     */
    private fun findBestVoiceForLanguage(
        engine: TextToSpeech,
        targetLanguage: TargetLanguage,
        preferNetworkVoice: Boolean
    ): VoiceCatalogSelection {
        return try {
            val allVoices = engine.voices
                ?: return VoiceCatalogSelection(null, null, null, emptyList(), 0, 0)
            val langMatches = allVoices.filter { voice ->
                voice.locale != null &&
                    voice.locale.language.equals(targetLanguage.code, ignoreCase = true)
            }
            if (langMatches.isEmpty()) {
                return VoiceCatalogSelection(null, null, null, emptyList(), 0, 0)
            }

            val installed = langMatches.filter { voice ->
                val features = voice.features.orEmpty()
                !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) &&
                    !features.contains("notInstalled")
            }
            val downloadable = langMatches.filter { voice ->
                val features = voice.features.orEmpty()
                features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) ||
                    features.contains("notInstalled")
            }

            val isNetworkHighQuality: (Voice) -> Boolean = { voice ->
                val features = voice.features.orEmpty()
                voice.isNetworkConnectionRequired ||
                    features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS) ||
                    voice.name.contains("network", ignoreCase = true) ||
                    voice.name.contains("wavenet", ignoreCase = true) ||
                    voice.quality >= Voice.QUALITY_HIGH
            }

            val voiceComparator = compareBy<Voice> { voice ->
                val isNet = isNetworkHighQuality(voice)
                if (preferNetworkVoice) {
                    if (isNet) 2 else 1
                } else {
                    if (!isNet) 2 else 1
                }
            }
                .thenBy { it.quality }
                .thenBy { voice ->
                    if (voice.locale.country.equals(targetLanguage.locale.country, ignoreCase = true)) 1 else 0
                }
                .thenBy { -it.latency }

            val installedNetVoices = installed.filter(isNetworkHighQuality)

            VoiceCatalogSelection(
                bestInstalledNetworkVoice = if (preferNetworkVoice) {
                    installedNetVoices.maxWithOrNull(voiceComparator)
                } else {
                    null
                },
                bestInstalledVoice = installed.maxWithOrNull(voiceComparator),
                bestDownloadableVoice = downloadable.maxWithOrNull(voiceComparator),
                uninstalledVoiceNames = downloadable.map { it.name },
                installedMatchingVoices = installed.size,
                totalMatchingVoices = langMatches.size
            )
        } catch (e: Exception) {
            VoiceCatalogSelection(null, null, null, emptyList(), 0, 0)
        }
    }

    /**
     * Triggers an asynchronous silent `synthesizeToFile` call with `KEY_FEATURE_NETWORK_SYNTHESIS = "true"`
     * so Google Speech Services automatically fetches and caches the Hindi neural voice model in the background.
     */
    private fun triggerSilentAsyncVoicePackWarmup(engine: TextToSpeech, targetLang: TargetLanguage) {
        managerScope.launch(Dispatchers.IO) {
            try {
                val warmupFile = File(appContext.cacheDir, "tts_warmup_${targetLang.code}.wav")
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "true")
                    putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_TIMEOUT_MS, "5000")
                }
                engine.synthesizeToFile(
                    targetLang.nativeName,
                    params,
                    warmupFile,
                    "warmup_async_voice_download"
                )
            } catch (e: Exception) {
                Log.d(TAG, "Silent voice warm-up synthesis skipped: ${e.message}")
            }
        }
    }

    /**
     * Runs a non-blocking coroutine watcher that polls `engine.voices` and `isLanguageAvailable`
     * while a language pack is downloading in the background, automatically upgrading the active
     * voice and notifying [VoiceDataDownloadListener.onVoiceDataInstalled] as soon as the download completes.
     */
    fun startAsyncLanguageDataDownloadWatcher(
        targetLang: TargetLanguage = TranslatorStateRepository.state.value.targetLanguage,
        speakConfirmationWhenReady: Boolean = false
    ) {
        asyncDownloadWatcherJob?.cancel()
        asyncDownloadWatcherJob = managerScope.launch {
            var attempts = 0
            while (isActive && attempts < 20) {
                delay(2500L)
                attempts++
                val engine = tts ?: continue
                val selection = findBestVoiceForLanguage(
                    engine = engine,
                    targetLanguage = targetLang,
                    preferNetworkVoice = TranslatorStateRepository.state.value.useHighQualityNetworkVoice
                )
                if (selection.bestInstalledNetworkVoice != null || selection.bestInstalledVoice != null) {
                    configureHighQualityVoice(
                        engine = engine,
                        speakSampleOnUpgrade = speakConfirmationWhenReady,
                        notifyInstalledListeners = true
                    )
                    flushPendingSubtitleIfReady()
                    break
                }
            }
        }
    }

    /**
     * Initiates an asynchronous Hindi / target language voice data download:
     * 1. Sets status to `DOWNLOADING_VOICE_DATA`, notifies [VoiceDataDownloadListener.onVoiceDataDownloadStarted],
     *    and starts the background coroutine watcher + silent warm-up.
     * 2. Launches [TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA] (or TTS Settings fallback) when [openSystemInstaller]
     *    is true so the user or OS can complete the Hindi voice pack download.
     */
    fun requestAsyncLanguageDataDownload(
        context: Context,
        openSystemInstaller: Boolean = true
    ) {
        val targetLang = TranslatorStateRepository.state.value.targetLanguage
        TranslatorStateRepository.setTtsStatus(TtsEngineStatus.DOWNLOADING_VOICE_DATA)
        TranslatorStateRepository.updateVoiceTelemetry(
            activeVoiceName = TranslatorStateRepository.state.value.activeVoiceName,
            availableVoicesCount = TranslatorStateRepository.state.value.availableVoicesCount,
            voiceDownloadMessage = "Downloading ${targetLang.displayName} (${targetLang.ttsLocaleTag}) Voice Pack in background..."
        )

        downloadListeners.forEach { listener ->
            listener.onVoiceDataDownloadStarted(targetLang)
        }

        val engine = tts
        if (engine != null) {
            triggerSilentAsyncVoicePackWarmup(engine, targetLang)
        } else {
            initializeAsync()
        }

        startAsyncLanguageDataDownloadWatcher(
            targetLang = targetLang,
            speakConfirmationWhenReady = true
        )

        if (openSystemInstaller) {
            try {
                val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage(GOOGLE_TTS_PACKAGE)
                }
                context.startActivity(installIntent)
            } catch (_: ActivityNotFoundException) {
                try {
                    val genericInstallIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(genericInstallIntent)
                } catch (_: ActivityNotFoundException) {
                    try {
                        val settingsIntent = Intent("com.android.settings.TTS_SETTINGS").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(settingsIntent)
                    } catch (ex: Exception) {
                        Log.w(TAG, "Unable to open system TTS installer", ex)
                    }
                }
            }
        }
    }

    fun refreshVoiceConfiguration(speakSampleOnUpgrade: Boolean = false) {
        val engine = tts
        if (engine == null) {
            initializeAsync()
            return
        }
        configureHighQualityVoice(
            engine = engine,
            speakSampleOnUpgrade = speakSampleOnUpgrade,
            notifyInstalledListeners = false
        )
    }

    /**
     * Speaks [text] non-blockingly using the configured High-Quality Network Voice or a specific
     * character's [com.example.data.VoiceProfile] (applying its custom pitch, speechRate, and engine voice variant).
     */
    fun speakNonBlocking(
        text: String,
        voiceProfile: com.example.data.VoiceProfile? = null
    ): Boolean {
        if (text.isBlank()) return false
        val engine = tts
        if (engine == null || !isTtsReady) {
            pendingSubtitleToSpeak = text
            if (engine == null && !isInitializing) {
                initializeAsync()
            }
            return false
        }
        val preferNetwork = TranslatorStateRepository.state.value.useHighQualityNetworkVoice
        return speakInternal(
            text = text,
            allowNetworkSynthesis = preferNetwork,
            voiceProfile = voiceProfile
        )
    }

    private fun speakInternal(
        text: String,
        allowNetworkSynthesis: Boolean,
        voiceProfile: com.example.data.VoiceProfile? = null
    ): Boolean {
        val engine = tts ?: return false
        val state = TranslatorStateRepository.state.value
        return try {
            // If a specific character VoiceProfile is provided, select its distinct engine voice variant if available
            if (voiceProfile != null) {
                selectIndexedVoiceForProfile(engine, state.targetLanguage, voiceProfile.engineVoiceIndex, allowNetworkSynthesis)
                engine.setPitch(voiceProfile.pitch)
                engine.setSpeechRate(voiceProfile.speechRate)
            } else {
                engine.setPitch(state.ttsPitch)
                engine.setSpeechRate(state.ttsSpeechRate)
            }

            val utteranceId = "gamevox_${UUID.randomUUID()}"
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                if (allowNetworkSynthesis) {
                    putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "true")
                    putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_TIMEOUT_MS, "3500")
                    activeNetworkUtterances[utteranceId] = text
                } else {
                    putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
                }
            }

            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                activeNetworkUtterances.remove(utteranceId)
            } else {
                startRealtimeVisualizerSync(
                    utteranceId = utteranceId,
                    text = text,
                    voiceProfile = voiceProfile
                )
            }
            result == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            Log.w(TAG, "Error speaking text via ForegroundTtsManager", e)
            false
        }
    }

    /**
     * Runs a 30 FPS coroutine loop synchronized with the active TTS utterance (`utteranceId`),
     * combining `engine.isSpeaking`, `onRangeStart` word boundaries, `onAudioAvailable` PCM chunks,
     * and syllable/phoneme envelope modulation shaped by the character's `pitch` and `speechRate`.
     */
    private fun startRealtimeVisualizerSync(
        utteranceId: String,
        text: String,
        voiceProfile: VoiceProfile?
    ) {
        val state = TranslatorStateRepository.state.value
        val activePitch = voiceProfile?.pitch ?: state.ttsPitch
        val activeRate = voiceProfile?.speechRate ?: state.ttsSpeechRate
        val voiceName = voiceProfile?.name ?: state.activeVoiceName
        val roleLabel = voiceProfile?.assignedRole?.displayLabel?.substringBefore(" (")
            ?: VoiceProfileRepository.lastDetectedSpeaker.value

        _visualizerState.value = TtsVisualizerState(
            isSpeaking = true,
            utteranceId = utteranceId,
            spokenText = text,
            activeVoiceName = voiceName,
            activeSpeakerRoleLabel = roleLabel,
            activePitch = activePitch,
            activeSpeechRate = activeRate,
            currentRangeStart = 0,
            currentRangeEnd = minOf(text.length, 4),
            progressFraction = 0.02f,
            rmsAmplitude = 0.45f,
            phaseRadians = 0f,
            frequencyBands = List(24) { 0.18f }
        )

        visualizerSyncJob?.cancel()
        visualizerSyncJob = managerScope.launch {
            val cleanText = text.trim()
            val words = cleanText.split(Regex("\\s+")).filter { it.isNotEmpty() }
            // Estimate natural speaking duration in ms (~68ms per char scaled by speechRate, min 1800ms)
            val estimatedDurationMs = ((cleanText.length.coerceAtLeast(12) * 68f) / activeRate.coerceAtLeast(0.55f))
                .toLong()
                .coerceIn(1800L, 14000L)

            val startMs = System.currentTimeMillis()
            var frameIndex = 0

            while (isActive) {
                delay(33L) // ~30 FPS real-time Canvas sync
                frameIndex++
                val currentVis = _visualizerState.value
                if (!currentVis.isSpeaking || currentVis.utteranceId != utteranceId) {
                    break
                }

                val elapsedMs = System.currentTimeMillis() - startMs
                val engineSpeaking = try {
                    tts?.isSpeaking == true
                } catch (_: Exception) {
                    false
                }

                // Keep animating while engine.isSpeaking is true OR within the estimated utterance window
                if (!engineSpeaking && elapsedMs > estimatedDurationMs) {
                    break
                }

                val timeProgress = (elapsedMs.toFloat() / estimatedDurationMs.toFloat()).coerceIn(0f, 1f)
                val effectiveProgress = maxOf(currentVis.progressFraction, timeProgress)

                // Compute current word highlight range if onRangeStart hasn't overridden it
                val charCursor = (effectiveProgress * cleanText.length).toInt().coerceIn(0, cleanText.length)
                val wordStart = cleanText.lastIndexOf(' ', (charCursor - 1).coerceAtLeast(0)).let {
                    if (it < 0) 0 else it + 1
                }
                val wordEnd = cleanText.indexOf(' ', charCursor).let {
                    if (it < 0) cleanText.length else it
                }

                // Compute phoneme/syllable acoustic energy from current character & pitch/rate harmonics
                val currentChar = cleanText.getOrNull(charCursor.coerceAtMost((cleanText.length - 1).coerceAtLeast(0))) ?: 'a'
                val isPauseChar = currentChar == ' ' || currentChar == ',' || currentChar == '।' || currentChar == '.'
                val charSeed = currentChar.code

                val baseEnergy = if (isPauseChar) {
                    0.16f
                } else {
                    val syllablePulse = 0.52f + 0.36f * abs(sin(frameIndex * 0.34f * activeRate + (charSeed % 7)))
                    syllablePulse.coerceIn(0.22f, 0.96f)
                }

                val centerBand = ((activePitch - 0.5f) / 1.2f * 16f + 4f).coerceIn(3f, 20f)
                val newBands = List(24) { bandIdx ->
                    val distFromFormant = abs(bandIdx - centerBand) / 12f
                    val formantGain = (1.08f - distFromFormant * 0.65f).coerceIn(0.25f, 1.1f)
                    val harmonicWave = 0.5f + 0.5f * sin(
                        frameIndex * 0.28f * activeRate + bandIdx * 0.55f + (charSeed % 5) * 0.4f
                    )
                    val secondaryWave = 0.5f + 0.5f * cos(
                        frameIndex * 0.19f * activePitch - bandIdx * 0.37f
                    )
                    val rawBand = baseEnergy * formantGain * (0.58f * harmonicWave + 0.42f * secondaryWave)
                    if (isPauseChar) (rawBand * 0.38f).coerceIn(0.08f, 0.35f)
                    else rawBand.coerceIn(0.10f, 0.98f)
                }

                val phase = (currentVis.phaseRadians + 0.26f * activeRate) % (2f * Math.PI.toFloat())
                _visualizerState.update { stateNow ->
                    stateNow.copy(
                        currentRangeStart = if (stateNow.currentRangeEnd > wordStart) stateNow.currentRangeStart else wordStart,
                        currentRangeEnd = maxOf(stateNow.currentRangeEnd, wordEnd),
                        progressFraction = effectiveProgress,
                        rmsAmplitude = baseEnergy,
                        phaseRadians = phase,
                        frequencyBands = newBands
                    )
                }
            }

            stopVisualizerSync()
        }
    }

    private fun stopVisualizerSync() {
        visualizerSyncJob?.cancel()
        visualizerSyncJob = null
        _visualizerState.update { current ->
            current.copy(
                isSpeaking = false,
                progressFraction = if (current.spokenText.isNotEmpty()) 1f else 0f,
                rmsAmplitude = 0f,
                frequencyBands = List(24) { 0.08f }
            )
        }
    }

    private fun computePcmSpectrumBands(audio: ByteArray, pitch: Float): Pair<Float, List<Float>> {
        val sampleCount = (audio.size / 2).coerceAtLeast(1)
        val shortBuf = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sumSq = 0.0
        val bandCount = 24
        val samplesPerBand = (sampleCount / bandCount).coerceAtLeast(1)
        val bands = MutableList(bandCount) { 0.1f }

        for (b in 0 until bandCount) {
            var bandSum = 0.0
            val start = b * samplesPerBand
            val end = minOf(sampleCount, start + samplesPerBand)
            for (i in start until end) {
                val norm = shortBuf.get(i) / 32768.0
                sumSq += norm * norm
                bandSum += abs(norm)
            }
            val avg = (bandSum / (end - start).coerceAtLeast(1)).toFloat()
            bands[b] = (avg * 3.2f * (0.85f + 0.25f * pitch)).coerceIn(0.10f, 0.98f)
        }
        val rms = sqrt(sumSq / sampleCount).toFloat().coerceIn(0.08f, 0.98f)
        return rms to bands
    }

    private fun selectIndexedVoiceForProfile(
        engine: TextToSpeech,
        targetLanguage: TargetLanguage,
        voiceIndex: Int,
        preferNetworkVoice: Boolean
    ) {
        try {
            val allVoices = engine.voices ?: return
            val installedForLang = allVoices.filter { voice ->
                val features = voice.features.orEmpty()
                voice.locale != null &&
                    voice.locale.language.equals(targetLanguage.code, ignoreCase = true) &&
                    !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) &&
                    !features.contains("notInstalled")
            }.sortedWith(
                compareByDescending<Voice> { voice ->
                    val isNet = voice.isNetworkConnectionRequired ||
                        voice.name.contains("network", ignoreCase = true) ||
                        voice.name.contains("wavenet", ignoreCase = true)
                    if (preferNetworkVoice) (if (isNet) 2 else 1) else (if (!isNet) 2 else 1)
                }.thenByDescending { it.quality }.thenBy { it.name }
            )
            if (installedForLang.isNotEmpty()) {
                val chosen = installedForLang[voiceIndex.coerceAtLeast(0) % installedForLang.size]
                engine.voice = chosen
            }
        } catch (_: Exception) {
        }
    }

    private fun flushPendingSubtitleIfReady() {
        val pending = pendingSubtitleToSpeak
        if (!pending.isNullOrBlank() && isTtsReady) {
            pendingSubtitleToSpeak = null
            speakNonBlocking(pending)
        }
    }

    fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        stopVisualizerSync()
    }
}
