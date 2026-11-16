package com.example.service

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.CharacterVoiceAssignment
import com.example.data.SpeakerGender
import com.example.data.TargetLanguage
import com.example.data.TranslatorStateRepository
import com.example.data.TtsEngineStatus
import com.example.data.TtsSettingsDataStore
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.engine.EmotionAnalysisResult
import com.example.engine.EmotionSpeechEngine
import com.example.engine.SpeechEmotion
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
import kotlinx.coroutines.withContext

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
    val activeEmotion: SpeechEmotion = SpeechEmotion.NEUTRAL,
    val activePitch: Float = 1.0f,
    val activeSpeechRate: Float = 1.0f,
    val activeVolume: Float = 1.0f,
    val ssmlPreview: String = "",
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
        val HINDI_LOCALE: Locale = Locale.forLanguageTag("hi-IN")
        private val INDIAN_ENGLISH_LOCALE: Locale = Locale.forLanguageTag("en-IN")

        @Volatile
        private var INSTANCE: ForegroundTtsManager? = null

        fun getInstance(context: Context): ForegroundTtsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ForegroundTtsManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun peekSherpaBackend(): SherpaSupertonicBackend? = INSTANCE?.sherpaEngine
    }

    private val appContext: Context = context.applicationContext
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val sherpaEngine = SherpaSupertonicBackend(appContext)
    val exoPlayerManager = HindiExoPlayerManager.getInstance(appContext)
    val neuralVoiceClient = NeuralVoiceClient().apply {
        attachExoPlayerManager(exoPlayerManager)
    }
    private val kokoroEngine = KokoroMediaPipeTtsEngine.getInstance(appContext)

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
    private var thirtySecondRepeatDelayJob: Job? = null
    private var isReceiverRegistered: Boolean = false

    private val _playbackEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var activeEnginePackageName: String = ""

    @Volatile
    private var initStartedElapsedMs: Long = 0L

    private val utteranceSubmitElapsedMs = ConcurrentHashMap<String, Long>()
    private val utteranceStartElapsedMs = ConcurrentHashMap<String, Long>()

    @Volatile
    private var benchmarkUtteranceId: String? = null

    @Volatile
    var isSpeakingUtterance: Boolean = false
        private set

    @Volatile
    private var activeUtteranceId: String? = null

    @Volatile
    var lastResolvedRouteDecision: com.example.engine.VoiceRouteDecision? = null
        private set

    @Volatile
    var lastSpokenSpeakerId: Int = -1
        private set

    /**
     * Callback invoked immediately when an utterance completes (`onDone`), used to drain the
     * short subtitle dubbing queue seamlessly.
     */
    var onUtteranceCompletedImmediately: ((completedUtteranceId: String) -> Unit)? = null

    /**
     * Callback invoked 30 seconds (`delay(30000)`) after [UtteranceProgressListener.onDone]
     * finishes speaking a full sentence/paragraph, allowing the same on-screen text to be spoken again.
     */
    var onThirtySecondPostSpeechDelayCompleted: ((completedUtteranceId: String) -> Unit)? = null

    private val _visualizerState = MutableStateFlow(TtsVisualizerState())
    val visualizerState: StateFlow<TtsVisualizerState> = _visualizerState.asStateFlow()

    private data class PendingNetworkUtterance(
        val text: String,
        val sourceEnglishText: String,
        val voiceProfile: VoiceProfile?,
        val usedSsml: Boolean
    )

    // Maps utteranceId -> spoken text & profile so SSML or network synthesis failures can auto-fallback to plain/local voice
    private val activeNetworkUtterances = ConcurrentHashMap<String, PendingNetworkUtterance>()

    @Volatile
    private var defaultEngineVoice: Voice? = null

    /**
     * Available Female TTS Voices fetched from `TextToSpeech.getVoices()` (`engine.voices`),
     * prioritized by High-Quality Network voices (e.g. `hi-in-x-hif-network`),
     * used when a Female character (e.g. "Jenny", 18yo Girl) is detected in scanned text.
     */
    val femaleVoices = CopyOnWriteArrayList<Voice>()

    /**
     * Available Male TTS Voices fetched from `TextToSpeech.getVoices()` (`engine.voices`),
     * prioritized by High-Quality Network voices (e.g. `hi-in-x-him-network`),
     * used when a Male character (e.g. "Anon", 18yo Boy) is detected in scanned text.
     */
    val maleVoices = CopyOnWriteArrayList<Voice>()

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
        managerScope.launch {
            TranslatorStateRepository.loadCloudTtsFromDataStore(appContext)
            val discovery = kokoroEngine.discoverModelFile(TranslatorStateRepository.state.value.kokoroModelPath)
            TranslatorStateRepository.updateKokoroStatusMessage(discovery.statusSummary)
            refreshInstalledEnginesList()
            initializeAsync(forceRebind = true)
        }
    }

    fun addVoiceDataDownloadListener(listener: VoiceDataDownloadListener) {
        downloadListeners.add(listener)
    }

    fun removeVoiceDataDownloadListener(listener: VoiceDataDownloadListener) {
        downloadListeners.remove(listener)
    }

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
            Log.w(TAG, "Unable to register ACTION_TTS_DATA_INSTALLED receiver", e)
        }
    }

    fun unregisterTtsDataBroadcastReceiver() {
        if (!isReceiverRegistered) return
        try {
            appContext.unregisterReceiver(ttsDataInstalledReceiver)
        } catch (_: Exception) {
        } finally {
            isReceiverRegistered = false
        }
    }

    /**
     * Queries installed Android TTS engines (`TextToSpeech.engines` + PackageManager)
     * and updates UI state without forcing Google TTS.
     */
    fun refreshInstalledEnginesList(): List<InstalledTtsEngineInfo> {
        val discovered = FreeTtsEngineCatalog.discoverInstalledEngines(appContext, tts)
        val currentState = TranslatorStateRepository.state.value
        val activePkg = activeEnginePackageName.ifBlank {
            try {
                tts?.defaultEngine.orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
        val activeInfo = discovered.firstOrNull { it.packageName == activePkg }
        val activeLabel = activeInfo?.label
            ?: activePkg.ifBlank { "System Default TTS" }
        val isSupertonic = FreeTtsEngineCatalog.isSherpaOrSupertonicEngine(activePkg, activeLabel)
        val genderReport = FreeTtsEngineCatalog.buildGenderSupportReport(
            engineLabel = activeLabel,
            targetLanguage = currentState.targetLanguage,
            femaleVoicesCount = femaleVoices.size,
            maleVoicesCount = maleVoices.size,
            totalLanguageVoicesCount = currentState.availableVoicesCount
        )
        TranslatorStateRepository.updateTtsEngineCatalogAndCapability(
            installedEngines = discovered,
            activeEnginePackage = activePkg,
            activeEngineLabel = activeLabel,
            isSupertonicOrSherpaActive = isSupertonic,
            supportsDistinctGenderVoices = genderReport.supportsDistinctGenderVoices,
            genderVoiceCapabilityNote = genderReport.statusNote
        )
        return discovered
    }

    /**
     * Switches to the user-selected Android TTS engine package (`""` = Android System Default)
     * and rebinds [TextToSpeech] immediately.
     */
    fun selectAndroidTtsEngine(enginePackage: String) {
        val cleaned = enginePackage.trim()
        TranslatorStateRepository.updateSelectedTtsEnginePackage(appContext, cleaned)
        stopSpeaking()
        initializeAsync(forceRebind = true)
    }

    fun openSystemTtsSettings(context: Context = appContext) {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open Android System TTS settings", e)
        }
    }

    /**
     * Dedicated [TextToSpeech.OnInitListener] that binds to the user-selected Android TTS engine
     * (e.g. sherpa-onnx Supertonic 3 Hindi or Android System Default), measures real initialization
     * latency, inspects voices & gender support honestly, and prepares Hindi synthesis.
     */
    private fun createTtsInitListener(
        requestedPackage: String?,
        allowDefaultFallback: Boolean
    ): TextToSpeech.OnInitListener {
        return TextToSpeech.OnInitListener { status ->
            isInitializing = false
            val elapsedInitMs = if (initStartedElapsedMs > 0L) {
                (SystemClock.elapsedRealtime() - initStartedElapsedMs).coerceAtLeast(0L)
            } else {
                0L
            }
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@OnInitListener
                activeEnginePackageName = requestedPackage
                    ?: try {
                        engine.defaultEngine.orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                TranslatorStateRepository.updateTtsLatencyBenchmarks(initLatencyMs = elapsedInitMs)
                attachUtteranceProgressListener(engine)
                configureHighQualityVoice(
                    engine = engine,
                    speakSampleOnUpgrade = false,
                    notifyInstalledListeners = false
                )
                refreshInstalledEnginesList()
                flushPendingSubtitleIfReady()
            } else if (allowDefaultFallback && !requestedPackage.isNullOrBlank()) {
                Log.w(
                    TAG,
                    "Selected TTS engine '$requestedPackage' init returned $status, falling back to Android system default TTS"
                )
                initializeSystemDefaultFallback()
            } else {
                isTtsReady = false
                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.ERROR)
                TranslatorStateRepository.updateVoiceTelemetry(
                    activeVoiceName = "TTS Unavailable",
                    availableVoicesCount = 0,
                    voiceDownloadMessage = "TTS Engine failed to initialize (status $status)"
                )
                refreshInstalledEnginesList()
            }
        }
    }

    /**
     * Starts asynchronous initialization of [TextToSpeech] using the user-selected Android TTS
     * engine (e.g. sherpa-onnx Supertonic 3) or Android's active system default engine.
     * Never forces `com.google.android.tts`.
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
        initStartedElapsedMs = SystemClock.elapsedRealtime()
        TranslatorStateRepository.setTtsStatus(TtsEngineStatus.INITIALIZING)

        val discovered = FreeTtsEngineCatalog.discoverInstalledEngines(appContext, tts)
        val systemDefault = try {
            tts?.defaultEngine
        } catch (_: Exception) {
            null
        }
        val selectedPkg = TranslatorStateRepository.state.value.selectedTtsEnginePackage
        val targetPackage = FreeTtsEngineCatalog.resolveEnginePackageForBinding(
            userSelectedPackage = selectedPkg,
            installedEngines = discovered,
            systemDefaultPackage = systemDefault,
            preferInstalledSupertonicWhenSystemDefaultUnset = true
        )

        try {
            tts?.shutdown()
            tts = if (!targetPackage.isNullOrBlank()) {
                TextToSpeech(
                    appContext,
                    createTtsInitListener(requestedPackage = targetPackage, allowDefaultFallback = true),
                    targetPackage
                )
            } else {
                TextToSpeech(
                    appContext,
                    createTtsInitListener(requestedPackage = null, allowDefaultFallback = false)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception binding to TTS engine '$targetPackage', falling back to system default", e)
            if (!targetPackage.isNullOrBlank()) {
                initializeSystemDefaultFallback()
            } else {
                isInitializing = false
                isTtsReady = false
                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.ERROR)
            }
        }
    }

    private fun initializeSystemDefaultFallback() {
        isInitializing = true
        initStartedElapsedMs = SystemClock.elapsedRealtime()
        try {
            tts?.shutdown()
            tts = TextToSpeech(
                appContext,
                createTtsInitListener(requestedPackage = null, allowDefaultFallback = false)
            )
        } catch (e: Exception) {
            isInitializing = false
            isTtsReady = false
            TranslatorStateRepository.setTtsStatus(TtsEngineStatus.ERROR)
            Log.w(TAG, "Fallback TextToSpeech initialization failed", e)
        }
    }

    /**
     * Returns true if the TTS engine is currently speaking an utterance (`onStart` .. `onDone`),
     * preventing background scan loops from interrupting ongoing speech mid-sentence.
     */
    fun isSpeakingNow(): Boolean {
        val engineSpeaking = try {
            tts?.isSpeaking == true
        } catch (_: Exception) {
            false
        }
        return isSpeakingUtterance || engineSpeaking
    }

    /**
     * Cancels any active 30-second post-speech wait timer when genuinely new text arrives
     * or when anti-repeat memory is manually reset.
     */
    fun cancelThirtySecondRepeatTimer() {
        thirtySecondRepeatDelayJob?.cancel()
        thirtySecondRepeatDelayJob = null
    }

    /**
     * Attaches an [UtteranceProgressListener] that:
     * 1. Synchronizes real-time TTS playback events (`onStart`, `onRangeStart`, `onAudioAvailable`,
     *    `onDone`, `onStop`, `onError`) with [visualizerState] for the Jetpack Compose Canvas Audio Visualizer.
     * 2. In [onDone], starts a 30-second coroutine (`delay(30000)`) ONLY after the complete sentence/paragraph
     *    has finished speaking, then invokes [onThirtySecondPostSpeechDelayCompleted] so if the same text
     *    remains on screen after 30 seconds, it will be spoken again.
     */
    private fun attachUtteranceProgressListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == null || utteranceId == "warmup_async_voice_download") return
                val nowElapsed = SystemClock.elapsedRealtime()
                utteranceStartElapsedMs[utteranceId] = nowElapsed
                val submittedAt = utteranceSubmitElapsedMs.remove(utteranceId)
                if (submittedAt != null) {
                    val firstAudioMs = (nowElapsed - submittedAt).coerceAtLeast(0L)
                    TranslatorStateRepository.updateTtsLatencyBenchmarks(
                        firstAudioLatencyMs = firstAudioMs,
                        summary = if (benchmarkUtteranceId == utteranceId) {
                            "First audio: ${firstAudioMs}ms — speaking..."
                        } else {
                            null
                        }
                    )
                }
                isSpeakingUtterance = true
                activeUtteranceId = utteranceId
                thirtySecondRepeatDelayJob?.cancel()
                TranslatorStateRepository.updateTtsLifecycleEvent(
                    event = "onStart",
                    utteranceId = utteranceId,
                    error = ""
                )
                _visualizerState.update { current ->
                    current.copy(
                        isSpeaking = true,
                        utteranceId = utteranceId
                    )
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId == null || utteranceId == "warmup_async_voice_download") return
                isSpeakingUtterance = true
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
                if (utteranceId == null || utteranceId == "warmup_async_voice_download") {
                    return
                }
                val nowElapsed = SystemClock.elapsedRealtime()
                val startedAt = utteranceStartElapsedMs.remove(utteranceId)
                utteranceSubmitElapsedMs.remove(utteranceId)
                if (startedAt != null) {
                    val durationMs = (nowElapsed - startedAt).coerceAtLeast(0L)
                    val s = TranslatorStateRepository.state.value
                    val isBenchmark = benchmarkUtteranceId == utteranceId
                    if (isBenchmark) {
                        benchmarkUtteranceId = null
                    }
                    TranslatorStateRepository.updateTtsLatencyBenchmarks(
                        speechDurationMs = durationMs,
                        isRunningBenchmark = if (isBenchmark) false else null,
                        summary = if (isBenchmark) {
                            "Init: ${s.ttsInitLatencyMs}ms • First audio: ${s.ttsFirstAudioLatencyMs}ms • Playback: ${durationMs}ms (${s.activeTtsEngineLabel})"
                        } else {
                            null
                        }
                    )
                } else if (benchmarkUtteranceId == utteranceId) {
                    benchmarkUtteranceId = null
                    TranslatorStateRepository.updateTtsLatencyBenchmarks(isRunningBenchmark = false)
                }

                activeNetworkUtterances.remove(utteranceId)
                isSpeakingUtterance = false
                if (activeUtteranceId == utteranceId) {
                    activeUtteranceId = null
                }
                TranslatorStateRepository.updateTtsLifecycleEvent(
                    event = "onDone",
                    utteranceId = utteranceId,
                    error = ""
                )
                if (_visualizerState.value.utteranceId == utteranceId) {
                    stopVisualizerSync()
                }
                onUtteranceCompletedImmediately?.invoke(utteranceId)
                // 30-Second Wait Logic: Start Kotlin Coroutine delay(30000) ONLY after TTS finishes speaking full text
                thirtySecondRepeatDelayJob?.cancel()
                thirtySecondRepeatDelayJob = managerScope.launch {
                    delay(30000L)
                    onThirtySecondPostSpeechDelayCompleted?.invoke(utteranceId)
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId != null) {
                    utteranceSubmitElapsedMs.remove(utteranceId)
                    utteranceStartElapsedMs.remove(utteranceId)
                    if (benchmarkUtteranceId == utteranceId) {
                        benchmarkUtteranceId = null
                        TranslatorStateRepository.updateTtsLatencyBenchmarks(
                            isRunningBenchmark = false,
                            summary = "Benchmark interrupted"
                        )
                    }
                    activeNetworkUtterances.remove(utteranceId)
                    if (activeUtteranceId == utteranceId) {
                        isSpeakingUtterance = false
                        activeUtteranceId = null
                    }
                    TranslatorStateRepository.updateTtsLifecycleEvent(
                        event = if (interrupted) "onStop (Interrupted)" else "onStop",
                        utteranceId = utteranceId,
                        error = ""
                    )
                    if (_visualizerState.value.utteranceId == utteranceId) {
                        stopVisualizerSync()
                    }
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

    internal fun mapTtsErrorCode(errorCode: Int): String {
        return when (errorCode) {
            TextToSpeech.ERROR_SYNTHESIS -> "ERROR_SYNTHESIS"
            TextToSpeech.ERROR_SERVICE -> "ERROR_SERVICE"
            TextToSpeech.ERROR_OUTPUT -> "ERROR_OUTPUT"
            TextToSpeech.ERROR_NETWORK -> "ERROR_NETWORK"
            TextToSpeech.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            TextToSpeech.ERROR_INVALID_REQUEST -> "ERROR_INVALID_REQUEST"
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "ERROR_NOT_INSTALLED_YET"
            else -> "ERROR_GENERIC($errorCode)"
        }
    }

    internal fun handleUtteranceFailure(utteranceId: String?, errorCode: Int) {
        if (utteranceId == null) return
        val errorLabel = mapTtsErrorCode(errorCode)
        val failedNetworkUtterance = activeNetworkUtterances.remove(utteranceId)
        Log.w(TAG, "TTS utterance error ($errorLabel) for $utteranceId")
        TranslatorStateRepository.updateTtsLifecycleEvent(
            event = "onError($errorLabel)",
            utteranceId = utteranceId,
            error = errorLabel
        )
        if (activeUtteranceId == utteranceId) {
            isSpeakingUtterance = false
            activeUtteranceId = null
        }
        if (_visualizerState.value.utteranceId == utteranceId) {
            stopVisualizerSync()
        }

        // If HD network synthesis failed on this device, auto-fallback cleanly to plain local voice
        if (failedNetworkUtterance != null) {
            managerScope.launch {
                speakInternal(
                    text = failedNetworkUtterance.text,
                    sourceEnglishText = failedNetworkUtterance.sourceEnglishText,
                    allowNetworkSynthesis = false,
                    allowSsml = false,
                    voiceProfile = failedNetworkUtterance.voiceProfile
                )
            }
            return
        }

        // Still apply the 30-second cooldown on failure so the same line does not loop endlessly
        thirtySecondRepeatDelayJob?.cancel()
        thirtySecondRepeatDelayJob = managerScope.launch {
            delay(30000L)
            onThirtySecondPostSpeechDelayCompleted?.invoke(utteranceId)
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
        // Never reconfigure language or voice on the live TTS engine while an utterance is actively speaking,
        // as calling setLanguage/setVoice mid-utterance cuts off ongoing speech on Android.
        if (isSpeakingNow()) {
            return
        }

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

        // Always apply natural pitch and normal speechRate (1.0f) during TTS initialization
        engine.setPitch(state.ttsPitch.coerceIn(0.5f, 1.6f))
        engine.setSpeechRate(state.ttsSpeechRate.coerceIn(0.6f, 1.4f))

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

        val chosenVoice = (if (state.useHighQualityNetworkVoice) {
            voiceSelection.bestInstalledNetworkVoice ?: voiceSelection.bestDownloadableVoice
        } else {
            null
        }) ?: voiceSelection.bestInstalledVoice ?: voiceSelection.bestDownloadableVoice

        if (chosenVoice != null) {
            defaultEngineVoice = chosenVoice
            try {
                if (engine.voice?.name != chosenVoice.name) {
                    engine.setVoice(chosenVoice)
                }
            } catch (_: Exception) {
            }
        } else {
            defaultEngineVoice = try {
                engine.defaultVoice ?: engine.voice
            } catch (_: Exception) {
                null
            }
        }

        // Populate Female (`hi-in-x-hif-network` / Female) & Male (`hi-in-x-him-network` / Male) TTS voice lists
        populateGenderVoiceCatalog(engine, targetLang, preferNetworkVoice = state.useHighQualityNetworkVoice)

        isTtsReady = true

        val isHindiMissingData = hindiAvailability == TextToSpeech.LANG_MISSING_DATA ||
            hindiAvailability == TextToSpeech.LANG_NOT_SUPPORTED
        val boundPkg = activeEnginePackageName.ifBlank {
            try {
                engine.defaultEngine.orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
        val discovered = FreeTtsEngineCatalog.discoverInstalledEngines(appContext, engine)
        val boundLabel = discovered.firstOrNull { it.packageName == boundPkg }?.label
            ?: boundPkg.ifBlank { "System TTS" }
        val isSupertonic = FreeTtsEngineCatalog.isSherpaOrSupertonicEngine(boundPkg, boundLabel)
        val genderReport = FreeTtsEngineCatalog.buildGenderSupportReport(
            engineLabel = boundLabel,
            targetLanguage = targetLang,
            femaleVoicesCount = femaleVoices.size,
            maleVoicesCount = maleVoices.size,
            totalLanguageVoicesCount = voiceSelection.totalMatchingVoices
        )
        TranslatorStateRepository.updateTtsEngineCatalogAndCapability(
            installedEngines = discovered,
            activeEnginePackage = boundPkg,
            activeEngineLabel = boundLabel,
            isSupertonicOrSherpaActive = isSupertonic,
            supportsDistinctGenderVoices = genderReport.supportsDistinctGenderVoices,
            genderVoiceCapabilityNote = genderReport.statusNote
        )

        when {
            // Case 1: Installed local neural engine (e.g. sherpa-onnx Supertonic 3) or matching voice pack is ready
            voiceSelection.bestInstalledNetworkVoice != null || voiceSelection.bestInstalledVoice != null -> {
                val activeVoice = voiceSelection.bestInstalledNetworkVoice
                    ?: voiceSelection.bestInstalledVoice!!
                val prefix = when {
                    isSupertonic -> "Supertonic 3 / sherpa-onnx"
                    voiceSelection.bestInstalledNetworkVoice != null || activeVoice.quality >= Voice.QUALITY_HIGH -> "HD Neural"
                    else -> "Installed"
                }
                val label = "$prefix (${activeVoice.name})"

                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.READY)
                TranslatorStateRepository.updateVoiceTelemetry(
                    activeVoiceName = label,
                    availableVoicesCount = voiceSelection.totalMatchingVoices,
                    voiceDownloadMessage = "$boundLabel Ready • ${voiceSelection.installedMatchingVoices}/${voiceSelection.totalMatchingVoices} ${targetLang.displayName} voices"
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

            // Case 2: Engine supports target locale directly (common for sherpa-onnx Supertonic 3 / custom Android TTS engines)
            isPrimarySupported -> {
                val activeName = if (isSupertonic) {
                    "Supertonic 3 / sherpa-onnx (${targetLang.ttsLocaleTag})"
                } else {
                    "$boundLabel (${targetLang.ttsLocaleTag})"
                }
                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.READY)
                TranslatorStateRepository.updateVoiceTelemetry(
                    activeVoiceName = activeName,
                    availableVoicesCount = maxOf(1, voiceSelection.totalMatchingVoices),
                    voiceDownloadMessage = "$boundLabel Active • ${targetLang.displayName} Ready"
                )
                downloadListeners.forEach { listener ->
                    listener.onHighQualityNetworkVoiceAvailable(
                        language = targetLang,
                        voiceName = activeName,
                        quality = Voice.QUALITY_HIGH
                    )
                }
            }

            // Case 3: Voice packs exist in catalog with KEY_FEATURE_NOT_INSTALLED, or LANG_MISSING_DATA
            targetAvailability == TextToSpeech.LANG_MISSING_DATA ||
                isHindiMissingData ||
                voiceSelection.uninstalledVoiceNames.isNotEmpty() -> {

                val missingNames = voiceSelection.uninstalledVoiceNames
                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.MISSING_HINDI_DATA)
                TranslatorStateRepository.updateVoiceTelemetry(
                    activeVoiceName = "Missing (${targetLang.ttsLocaleTag})",
                    availableVoicesCount = voiceSelection.totalMatchingVoices,
                    voiceDownloadMessage = "${targetLang.displayName} voice data missing on $boundLabel — Install voice data or switch TTS engine"
                )

                val reason = if (missingNames.isNotEmpty()) {
                    "${targetLang.displayName} voice pack (${missingNames.first()}) on $boundLabel is not installed yet."
                } else {
                    "${targetLang.displayName} (${targetLang.ttsLocaleTag}) voice data is missing on $boundLabel."
                }

                downloadListeners.forEach { listener ->
                    listener.onVoiceDataMissing(
                        language = targetLang,
                        missingVoiceNames = missingNames,
                        reason = reason
                    )
                }
            }

            // Case 4: Locale not installed at all — notify listener
            else -> {
                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.MISSING_HINDI_DATA)
                TranslatorStateRepository.updateVoiceTelemetry(
                    activeVoiceName = "Missing (${targetLang.ttsLocaleTag})",
                    availableVoicesCount = 0,
                    voiceDownloadMessage = "${targetLang.displayName} (${targetLang.ttsLocaleTag}) voice missing on $boundLabel"
                )
                downloadListeners.forEach { listener ->
                    listener.onVoiceDataMissing(
                        language = targetLang,
                        missingVoiceNames = emptyList(),
                        reason = "${targetLang.displayName} (${targetLang.ttsLocaleTag}) voice data is not installed on $boundLabel."
                    )
                }
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
        if (isSpeakingNow()) return
        managerScope.launch(Dispatchers.IO) {
            try {
                if (isSpeakingNow()) return@launch
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
            val activePkg = TranslatorStateRepository.state.value.activeTtsEnginePackage
                .ifBlank { TranslatorStateRepository.state.value.selectedTtsEnginePackage }
            try {
                val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (activePkg.isNotBlank()) {
                        setPackage(activePkg)
                    }
                }
                context.startActivity(installIntent)
            } catch (_: ActivityNotFoundException) {
                try {
                    val genericInstallIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(genericInstallIntent)
                } catch (_: ActivityNotFoundException) {
                    openSystemTtsSettings(context)
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
     * Speaks [text] non-blockingly using the shared [com.example.engine.CharacterVoiceRouter]
     * decision resolved BEFORE synthesis. Never silently replaces a resolved character SID
     * with the default SID or overwrites an explicit [voiceProfile] / [routeDecision] with stale global state.
     */
    fun speakNonBlocking(
        text: String,
        voiceProfile: com.example.data.VoiceProfile? = null,
        sourceEnglishText: String = "",
        isBenchmarkRun: Boolean = false,
        characterAssignment: CharacterVoiceAssignment? = null,
        routeDecision: com.example.engine.VoiceRouteDecision? = null
    ): Boolean {
        if (text.isBlank()) return false

        val state = TranslatorStateRepository.state.value
        val currentEpoch = _playbackEpoch.incrementAndGet()

        // Resolve the immutable VoiceRouteDecision BEFORE synthesis
        val effectiveRoute: com.example.engine.VoiceRouteDecision = when {
            routeDecision != null -> routeDecision.copy(sessionEpoch = currentEpoch)
            characterAssignment != null -> com.example.engine.CharacterVoiceRouter.routeCharacterPreview(
                context = appContext,
                assignment = characterAssignment,
                hindiDialogue = text,
                sessionEpoch = currentEpoch
            )
            voiceProfile != null && voiceProfile.id.startsWith("matrix_preview_") -> {
                com.example.engine.CharacterVoiceRouter.routeBaseSpeakerAudition(
                    context = appContext,
                    sid = voiceProfile.engineVoiceIndex,
                    speakerTitle = voiceProfile.name,
                    genderLabel = if (voiceProfile.engineVoiceIndex in 0..4) "Female" else "Male",
                    hindiDialogue = text,
                    sessionEpoch = currentEpoch
                )
            }
            voiceProfile != null -> {
                val sidFromProfile = voiceProfile.engineVoiceIndex.coerceIn(0, 9)
                val matchedChar = VoiceProfileRepository.characterAssignments.value.firstOrNull {
                    it.speakerId == sidFromProfile
                } ?: CharacterVoiceAssignment(
                    characterName = voiceProfile.id,
                    displayName = voiceProfile.name,
                    speakerId = sidFromProfile,
                    speed = voiceProfile.speechRate,
                    pitch = voiceProfile.pitch,
                    voicePresentation = if (voiceProfile.gender == SpeakerGender.FEMALE_18) "female" else "male",
                    referenceVoiceId = if (voiceProfile.gender == SpeakerGender.FEMALE_18) {
                        CharacterVoiceAssignment.DEFAULT_FEMALE_REF_ID
                    } else {
                        CharacterVoiceAssignment.DEFAULT_MALE_REF_ID
                    }
                )
                com.example.engine.CharacterVoiceRouter.routeCharacterPreview(
                    context = appContext,
                    assignment = matchedChar.copy(
                        speakerId = sidFromProfile,
                        speed = voiceProfile.speechRate,
                        pitch = voiceProfile.pitch
                    ),
                    hindiDialogue = text,
                    sessionEpoch = currentEpoch
                )
            }
            sourceEnglishText.isNotBlank() -> com.example.engine.CharacterVoiceRouter.routeLiveSubtitle(
                context = appContext,
                rawOcrSubtitle = sourceEnglishText,
                translatedHindi = text,
                sessionEpoch = currentEpoch
            )
            else -> {
                val activeChar = VoiceProfileRepository.activeCharacterAssignment.value
                    ?: CharacterVoiceAssignment.DEFAULT_PRESETS.first()
                com.example.engine.CharacterVoiceRouter.routeCharacterPreview(
                    context = appContext,
                    assignment = activeChar,
                    hindiDialogue = text,
                    sessionEpoch = currentEpoch
                )
            }
        }

        lastResolvedRouteDecision = effectiveRoute
        lastSpokenSpeakerId = effectiveRoute.selectedSpeakerId

        val activeAssignment = effectiveRoute.characterAssignment
        val effectiveVoiceProfile = voiceProfile ?: effectiveRoute.voiceProfile
        val targetGender = if (activeAssignment.isFemale) {
            SpeakerGender.FEMALE_18
        } else {
            effectiveVoiceProfile.gender
        }

        val emotionResult = EmotionSpeechEngine.analyzeEmotionAndBuildSsml(
            spokenText = effectiveRoute.hindiDialogue.ifBlank { text },
            sourceEnglishText = sourceEnglishText,
            gender = targetGender,
            profilePitchOverride = if (effectiveVoiceProfile.isCustomCloned) effectiveVoiceProfile.pitch else activeAssignment.pitch,
            profileRateOverride = if (effectiveVoiceProfile.isCustomCloned) effectiveVoiceProfile.speechRate else activeAssignment.speed,
            masterVolume = state.ttsVolume
        )

        TranslatorStateRepository.updateEmotionTelemetry(
            emotion = emotionResult.emotion,
            reason = emotionResult.analysisReason,
            ssmlPreview = emotionResult.ssmlMarkup
        )

        val canonicalSelection = com.example.engine.CharacterVoiceRouter.selectCanonicalVoiceBackend(
            context = appContext,
            assignment = activeAssignment,
            state = state
        )

        // 0. User-Visible Mode: Pause Dubbing (subtitles remain active, audio playback paused)
        if (canonicalSelection.backend == com.example.engine.CanonicalVoiceBackend.PAUSE_DUBBING) {
            TranslatorStateRepository.updateVoiceEngineDiagnostics(
                backendType = "PAUSE_DUBBING",
                modelLoaded = false,
                modelPath = "",
                speakerCount = sherpaEngine.numSpeakers,
                selectedSid = effectiveRoute.selectedSpeakerId,
                modelVersion = "Paused by user",
                jniLoadResult = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                configuredSteps = sherpaEngine.configuredNumSteps,
                lastError = ""
            )
            TranslatorStateRepository.updateDiagnosisMetrics(
                activeVoiceName = "Pause Dubbing (Subtitles Only)"
            )
            return false
        }

        // 0b. Canonical Backend: Local Neural Server (ResembleAI Chatterbox Multilingual V3 Hindi)
        if (canonicalSelection.backend == com.example.engine.CanonicalVoiceBackend.CHATTERBOX_LOCAL_SERVER) {
            val epoch = currentEpoch
            val utteranceId = effectiveRoute.utteranceId
            val fallbackDispatched = java.util.concurrent.atomic.AtomicBoolean(false)

            val spokenWithOverrides = if (activeAssignment.pronunciationOverrides.isNotEmpty()) {
                com.example.engine.PronunciationGlossary.applyForSpeech(
                    emotionResult.cleanSpokenText,
                    activeAssignment.pronunciationOverrides
                )
            } else {
                emotionResult.cleanSpokenText
            }

            val effectiveExaggeration = if (emotionResult.emotion == SpeechEmotion.NEUTRAL) {
                activeAssignment.exaggeration
            } else {
                emotionResult.chatterboxExaggeration
            }
            val effectiveCfgWeight = if (emotionResult.emotion == SpeechEmotion.NEUTRAL) {
                activeAssignment.cfgWeight
            } else {
                emotionResult.chatterboxCfgWeight
            }

            val synthReq = NeuralSynthesizeRequest(
                text = spokenWithOverrides,
                characterId = activeAssignment.characterName,
                referenceVoiceId = activeAssignment.referenceVoiceId,
                languageId = state.targetLanguage.code,
                emotion = emotionResult.emotion.code,
                exaggeration = effectiveExaggeration,
                cfgWeight = effectiveCfgWeight,
                requestId = utteranceId,
                epoch = epoch
            )

            isSpeakingUtterance = true
            activeUtteranceId = utteranceId
            startRealtimeVisualizerSync(
                utteranceId = utteranceId,
                text = spokenWithOverrides,
                voiceProfile = effectiveVoiceProfile,
                emotionResult = emotionResult,
                overrideVoiceName = "Synthesizing (${activeAssignment.displayName})..."
            )

            managerScope.launch(Dispatchers.Default) {
                val success = neuralVoiceClient.synthesizeAndPlay(
                    rawBaseUrl = state.chatterboxServerUrl,
                    authToken = state.chatterboxAuthToken,
                    request = synthReq,
                    onSynthesisTelemetry = { outcome ->
                        com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
                            routeDecision = effectiveRoute,
                            actualSidPassedToNative = effectiveRoute.selectedSpeakerId,
                            actualBackend = "CHATTERBOX_LOCAL_SERVER",
                            jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                            modelLoaded = true,
                            fallbackReason = "",
                            audioDurationMs = outcome.parsedWav.durationMs,
                            outputPcmRms = outcome.parsedWav.waveformRms
                        )
                        TranslatorStateRepository.updateChatterboxServerStatus(
                            reachable = true,
                            modelLoaded = true,
                            device = outcome.deviceUsed,
                            statusText = "Connected (${outcome.deviceUsed.uppercase(Locale.ROOT)}) • Infer ${outcome.serverInferenceLatencyMs}ms • RMS ${String.format(Locale.US, "%.4f", outcome.parsedWav.waveformRms)}",
                            errorCategory = "NONE",
                            warningMessage = ""
                        )
                        TranslatorStateRepository.updateVoiceEngineDiagnostics(
                            backendType = "CHATTERBOX_LOCAL_SERVER",
                            modelLoaded = true,
                            modelPath = "${state.chatterboxServerUrl} (${outcome.voiceId}.wav)",
                            speakerCount = VoiceProfileRepository.getUniqueReferenceVoiceSummary().first,
                            selectedSid = effectiveRoute.selectedSpeakerId,
                            modelVersion = "ResembleAI/Chatterbox-Multilingual-hi (${outcome.deviceUsed})",
                            jniLoadResult = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                            configuredSteps = sherpaEngine.configuredNumSteps,
                            firstAudioLatencyMs = outcome.serverRequestLatencyMs,
                            lastError = ""
                        )
                        TranslatorStateRepository.updateDiagnosisMetrics(
                            serverRequestLatencyMs = outcome.serverRequestLatencyMs,
                            serverInferenceLatencyMs = outcome.serverInferenceLatencyMs,
                            conditioningLatencyMs = outcome.conditioningLatencyMs,
                            conditioningCached = outcome.conditioningCached,
                            ttsFirstAudioLatencyMs = outcome.serverRequestLatencyMs,
                            lastSynthesizedVoiceSha256 = outcome.voiceSha256,
                            lastSynthesizedRms = outcome.parsedWav.waveformRms,
                            activeVoiceName = "Chatterbox V3 (${activeAssignment.displayName} • ${outcome.voiceId})"
                        )
                    },
                    onFirstAudioStarted = { firstPlayableMs ->
                        TranslatorStateRepository.updateDiagnosisMetrics(
                            ttsFirstAudioLatencyMs = firstPlayableMs,
                            ttsAudioTrackWriteLatencyMs = firstPlayableMs
                        )
                    },
                    onCompleted = {
                        if (_playbackEpoch.get() == epoch) {
                            isSpeakingUtterance = false
                            if (activeUtteranceId == utteranceId) activeUtteranceId = null
                            stopVisualizerSync()
                            TranslatorStateRepository.updateTtsLifecycleEvent(
                                event = "onDone(ChatterboxServer)",
                                utteranceId = utteranceId,
                                error = ""
                            )
                            onUtteranceCompletedImmediately?.invoke(utteranceId)
                            thirtySecondRepeatDelayJob?.cancel()
                            thirtySecondRepeatDelayJob = managerScope.launch {
                                delay(30000L)
                                onThirtySecondPostSpeechDelayCompleted?.invoke(utteranceId)
                            }
                        }
                    },
                    onError = { err ->
                        if (fallbackDispatched.compareAndSet(false, true)) {
                            val errCat = neuralVoiceClient.lastErrorCategory
                            val explicitWarn = "[${errCat.code}] ${errCat.displayTitle}: $err"
                            Log.w(TAG, "Chatterbox local server error ($explicitWarn)")
                            TranslatorStateRepository.updateChatterboxServerStatus(
                                reachable = errCat == NeuralServerErrorCategory.MODEL_UNAVAILABLE || errCat == NeuralServerErrorCategory.INFERENCE_FAILED,
                                modelLoaded = errCat == NeuralServerErrorCategory.INFERENCE_FAILED,
                                device = "error_${errCat.code.lowercase(Locale.ROOT)}",
                                statusText = explicitWarn,
                                errorCategory = errCat.code,
                                warningMessage = "WARNING: Human Voice server error — $explicitWarn"
                            )
                            if (_playbackEpoch.get() == epoch) {
                                isSpeakingUtterance = false
                                if (activeUtteranceId == utteranceId) activeUtteranceId = null
                                stopVisualizerSync()
                                if (!isBenchmarkRun) {
                                    // Honest fallback: never claim Chatterbox is active when falling back
                                    if (SupertonicModelManager.isModelReady(appContext)) {
                                        TranslatorStateRepository.updateVoiceEngineDiagnostics(
                                            backendType = "SHERPA_FALLBACK (${errCat.code})",
                                            modelLoaded = true,
                                            modelPath = SupertonicModelManager.getModelDirectory(appContext).absolutePath,
                                            speakerCount = sherpaEngine.numSpeakers,
                                            selectedSid = effectiveRoute.selectedSpeakerId,
                                            modelVersion = SupertonicModelManager.modelInfo.version,
                                            jniLoadResult = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                                            configuredSteps = sherpaEngine.configuredNumSteps,
                                            lastError = explicitWarn
                                        )
                                        managerScope.launch(Dispatchers.Default) {
                                            sherpaEngine.synthesizeAndPlay(
                                                text = spokenWithOverrides,
                                                sid = effectiveRoute.selectedSpeakerId,
                                                speed = activeAssignment.speed,
                                                pitch = activeAssignment.pitch,
                                                epoch = epoch,
                                                onFirstAudioStarted = { latencyMs ->
                                                    TranslatorStateRepository.updateDiagnosisMetrics(
                                                        ttsFirstAudioLatencyMs = latencyMs,
                                                        activeVoiceName = "SHERPA_FALLBACK: Supertonic Sid #${effectiveRoute.selectedSpeakerId} (${errCat.code})"
                                                    )
                                                },
                                                onCompleted = {
                                                    com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
                                                        routeDecision = effectiveRoute,
                                                        actualSidPassedToNative = sherpaEngine.lastValidatedNativeSid,
                                                        actualBackend = "SHERPA_DIRECT",
                                                        jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                                                        modelLoaded = sherpaEngine.isModelRuntimeInitialized,
                                                        fallbackReason = explicitWarn,
                                                        audioDurationMs = sherpaEngine.lastAudioDurationMs,
                                                        outputPcmRms = sherpaEngine.lastWaveformRms
                                                    )
                                                    onUtteranceCompletedImmediately?.invoke(utteranceId)
                                                },
                                                onError = { sherpaErr ->
                                                    val sysBackend = resolveSystemFallbackBackendCode(state)
                                                    com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
                                                        routeDecision = effectiveRoute,
                                                        actualSidPassedToNative = effectiveRoute.selectedSpeakerId,
                                                        actualBackend = sysBackend,
                                                        jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                                                        modelLoaded = false,
                                                        fallbackReason = "$explicitWarn; $sherpaErr",
                                                        audioDurationMs = 0L,
                                                        outputPcmRms = null
                                                    )
                                                    managerScope.launch(Dispatchers.Main) {
                                                        TranslatorStateRepository.updateDiagnosisMetrics(
                                                            lastSynthesizedRms = Float.NaN,
                                                            activeVoiceName = "$sysBackend: ${state.activeTtsEngineLabel} ($sherpaErr)"
                                                        )
                                                        speakOnAndroidTts(text, sourceEnglishText, effectiveVoiceProfile, false)
                                                    }
                                                }
                                            )
                                        }
                                    } else {
                                        val sysBackend = resolveSystemFallbackBackendCode(state)
                                        val singleVoiceWarn = if (sysBackend == "SYSTEM_FALLBACK_SINGLE_VOICE") {
                                            "WARNING: $explicitWarn • Active system TTS (${state.activeTtsEngineLabel}) only provides 1 robotic voice! Fix PC server connection or download Supertonic 3 for distinct Male/Female voices."
                                        } else {
                                            "WARNING: $explicitWarn • Using $sysBackend (${state.activeTtsEngineLabel})"
                                        }
                                        TranslatorStateRepository.updateVoiceEngineWarning(singleVoiceWarn, errCat.code)
                                        com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
                                            routeDecision = effectiveRoute,
                                            actualSidPassedToNative = effectiveRoute.selectedSpeakerId,
                                            actualBackend = sysBackend,
                                            jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                                            modelLoaded = false,
                                            fallbackReason = explicitWarn,
                                            audioDurationMs = 0L,
                                            outputPcmRms = null
                                        )
                                        TranslatorStateRepository.updateVoiceEngineDiagnostics(
                                            backendType = "$sysBackend (${errCat.code})",
                                            modelLoaded = false,
                                            modelPath = state.chatterboxServerUrl,
                                            speakerCount = sherpaEngine.numSpeakers,
                                            selectedSid = effectiveRoute.selectedSpeakerId,
                                            modelVersion = SupertonicModelManager.modelInfo.version,
                                            jniLoadResult = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                                            configuredSteps = sherpaEngine.configuredNumSteps,
                                            lastError = explicitWarn
                                        )
                                        TranslatorStateRepository.updateDiagnosisMetrics(
                                            lastSynthesizedRms = Float.NaN,
                                            activeVoiceName = "$sysBackend: ${state.activeTtsEngineLabel} (${errCat.code})"
                                        )
                                        managerScope.launch(Dispatchers.Main) {
                                            if (_playbackEpoch.get() == epoch) {
                                                speakOnAndroidTts(text, sourceEnglishText, effectiveVoiceProfile, false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
                if (!success && _playbackEpoch.get() == epoch && fallbackDispatched.compareAndSet(false, true)) {
                    isSpeakingUtterance = false
                    if (activeUtteranceId == utteranceId) activeUtteranceId = null
                    stopVisualizerSync()
                }
            }
            return true
        }

        // 1. Canonical Backend: Direct in-process Sherpa-ONNX Supertonic 3 Neural TTS Path
        if (canonicalSelection.backend == com.example.engine.CanonicalVoiceBackend.SHERPA_DIRECT) {
            val epoch = currentEpoch
            val sid = effectiveRoute.selectedSpeakerId
            val speed = activeAssignment.speed
            val pitch = activeAssignment.pitch
            val utteranceId = effectiveRoute.utteranceId
            val fallbackDispatched = java.util.concurrent.atomic.AtomicBoolean(false)

            val spokenWithOverrides = if (activeAssignment.pronunciationOverrides.isNotEmpty()) {
                com.example.engine.PronunciationGlossary.applyForSpeech(
                    emotionResult.cleanSpokenText,
                    activeAssignment.pronunciationOverrides
                )
            } else {
                emotionResult.cleanSpokenText
            }

            val triggerSingleSystemFallback: (String) -> Unit = { err ->
                if (fallbackDispatched.compareAndSet(false, true)) {
                    val sysBackend = resolveSystemFallbackBackendCode(state)
                    val warnMsg = if (sysBackend == "SYSTEM_FALLBACK_SINGLE_VOICE") {
                        "WARNING: SYSTEM_FALLBACK_SINGLE_VOICE active — Supertonic 3 neural model failed ($err). ${activeAssignment.displayName} (SID #$sid) and all characters will sound identical on system TTS (${state.activeTtsEngineLabel}). Download & verify Supertonic 3 in Voice Manager."
                    } else {
                        "WARNING: Supertonic 3 unavailable ($err) • Using $sysBackend (${state.activeTtsEngineLabel})"
                    }
                    TranslatorStateRepository.updateVoiceEngineWarning(warnMsg, "SUPERTONIC_FALLBACK")
                    Log.w(TAG, "Sherpa Supertonic single fallback for $utteranceId (SID #$sid): $err")
                    com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
                        routeDecision = effectiveRoute,
                        actualSidPassedToNative = sid,
                        actualBackend = sysBackend,
                        jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                        modelLoaded = false,
                        fallbackReason = err,
                        audioDurationMs = 0L,
                        outputPcmRms = null
                    )
                    TranslatorStateRepository.updateVoiceEngineDiagnostics(
                        backendType = sysBackend,
                        modelLoaded = false,
                        modelPath = SupertonicModelManager.getModelDirectory(appContext).absolutePath,
                        speakerCount = sherpaEngine.numSpeakers,
                        selectedSid = sid,
                        modelVersion = SupertonicModelManager.modelInfo.version,
                        jniLoadResult = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                        configuredSteps = sherpaEngine.configuredNumSteps,
                        lastError = err
                    )
                    TranslatorStateRepository.updateDiagnosisMetrics(
                        lastSynthesizedRms = Float.NaN,
                        activeVoiceName = "$sysBackend: ${state.activeTtsEngineLabel} ($err)"
                    )
                    if (_playbackEpoch.get() == epoch) {
                        isSpeakingUtterance = false
                        if (activeUtteranceId == utteranceId) activeUtteranceId = null
                        stopVisualizerSync()
                        if (!isBenchmarkRun) {
                            managerScope.launch(Dispatchers.Main) {
                                if (_playbackEpoch.get() == epoch) {
                                    speakOnAndroidTts(
                                        text = text,
                                        sourceEnglishText = sourceEnglishText,
                                        voiceProfile = effectiveVoiceProfile,
                                        isBenchmarkRun = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            isSpeakingUtterance = true
            activeUtteranceId = utteranceId
            startRealtimeVisualizerSync(
                utteranceId = utteranceId,
                text = spokenWithOverrides,
                voiceProfile = effectiveVoiceProfile,
                emotionResult = emotionResult,
                overrideVoiceName = "Supertonic 3 (Sid #$sid • ${activeAssignment.displayName})"
            )

            managerScope.launch(Dispatchers.Default) {
                val success = sherpaEngine.synthesizeAndPlay(
                    text = spokenWithOverrides,
                    sid = sid,
                    speed = speed,
                    pitch = pitch,
                    epoch = epoch,
                    onFirstAudioStarted = { latencyMs ->
                        TranslatorStateRepository.clearVoiceEngineWarning()
                        TranslatorStateRepository.updateDiagnosisMetrics(
                            ttsFirstAudioLatencyMs = latencyMs,
                            activeVoiceName = "Supertonic 3 (Sid #$sid • ${activeAssignment.displayName})"
                        )
                    },
                    onCompleted = {
                        val actualNativeSid = sherpaEngine.lastValidatedNativeSid.takeIf { it >= 0 } ?: sid
                        TranslatorStateRepository.clearVoiceEngineWarning()
                        com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
                            routeDecision = effectiveRoute,
                            actualSidPassedToNative = actualNativeSid,
                            actualBackend = "SHERPA_DIRECT",
                            jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                            modelLoaded = sherpaEngine.isModelRuntimeInitialized,
                            fallbackReason = "",
                            audioDurationMs = sherpaEngine.lastAudioDurationMs,
                            outputPcmRms = sherpaEngine.lastWaveformRms
                        )
                        TranslatorStateRepository.updateVoiceEngineDiagnostics(
                            backendType = "SHERPA_DIRECT",
                            modelLoaded = true,
                            modelPath = SupertonicModelManager.getModelDirectory(appContext).absolutePath,
                            speakerCount = sherpaEngine.numSpeakers,
                            selectedSid = actualNativeSid,
                            modelVersion = "${SupertonicModelManager.modelInfo.version} (${SupertonicModelManager.modelInfo.license})",
                            jniLoadResult = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                            configuredSteps = sherpaEngine.configuredNumSteps,
                            firstAudioLatencyMs = sherpaEngine.lastFirstChunkLatencyMs,
                            lastError = ""
                        )
                        if (_playbackEpoch.get() == epoch) {
                            isSpeakingUtterance = false
                            if (activeUtteranceId == utteranceId) activeUtteranceId = null
                            stopVisualizerSync()
                            TranslatorStateRepository.updateDiagnosisMetrics(
                                ttsSynthesisRtf = sherpaEngine.lastSynthesisRtf,
                                lastSynthesizedRms = sherpaEngine.lastWaveformRms,
                                activeVoiceName = "Supertonic 3 (Sid #$actualNativeSid • ${activeAssignment.displayName})"
                            )
                            TranslatorStateRepository.updateTtsLifecycleEvent(
                                event = "onDone(SherpaSupertonic)",
                                utteranceId = utteranceId,
                                error = ""
                            )
                            onUtteranceCompletedImmediately?.invoke(utteranceId)
                            thirtySecondRepeatDelayJob?.cancel()
                            thirtySecondRepeatDelayJob = managerScope.launch {
                                delay(30000L)
                                onThirtySecondPostSpeechDelayCompleted?.invoke(utteranceId)
                            }
                        }
                    },
                    onError = { err ->
                        triggerSingleSystemFallback(err)
                    }
                )

                if (!success && _playbackEpoch.get() == epoch) {
                    triggerSingleSystemFallback(sherpaEngine.initializationError ?: "Sherpa synthesis returned false")
                }
            }
            return true
        }

        // 2. Canonical Backend: Optional Google MediaPipe Kokoro-82M on-device model path
        if (canonicalSelection.backend == com.example.engine.CanonicalVoiceBackend.MEDIAPIPE_KOKORO) {
            val discovery = kokoroEngine.discoverModelFile(state.kokoroModelPath)
            if (discovery.resolvedFile != null) {
                val epoch = currentEpoch
                val utteranceId = effectiveRoute.utteranceId
                isSpeakingUtterance = true
                activeUtteranceId = utteranceId
                startRealtimeVisualizerSync(
                    utteranceId = utteranceId,
                    text = emotionResult.cleanSpokenText,
                    voiceProfile = effectiveVoiceProfile,
                    emotionResult = emotionResult,
                    overrideVoiceName = "Kokoro-82M MediaPipe"
                )
                managerScope.launch {
                    val outcome = kokoroEngine.synthesizeAndPlay(
                        text = emotionResult.cleanSpokenText,
                        targetLanguage = state.targetLanguage,
                        gender = targetGender,
                        speakingRate = emotionResult.effectiveSpeechRate,
                        volume = emotionResult.effectiveVolume,
                        configuredModelPath = state.kokoroModelPath,
                        useGpuDelegate = state.kokoroUseGpu,
                        isBenchmarkRun = isBenchmarkRun
                    )
                    if (_playbackEpoch.get() != epoch) return@launch
                    when (outcome) {
                        is KokoroSpeakOutcome.Success -> {
                            isSpeakingUtterance = false
                            if (activeUtteranceId == utteranceId) activeUtteranceId = null
                            stopVisualizerSync()
                            TranslatorStateRepository.updateTtsLifecycleEvent(
                                event = "onDone(${outcome.activeDelegate})",
                                utteranceId = utteranceId,
                                error = ""
                            )
                            onUtteranceCompletedImmediately?.invoke(utteranceId)
                            thirtySecondRepeatDelayJob?.cancel()
                            thirtySecondRepeatDelayJob = managerScope.launch {
                                delay(30000L)
                                onThirtySecondPostSpeechDelayCompleted?.invoke(utteranceId)
                            }
                        }
                        is KokoroSpeakOutcome.FallbackToAndroidTts -> {
                            isSpeakingUtterance = false
                            if (activeUtteranceId == utteranceId) activeUtteranceId = null
                            stopVisualizerSync()
                            if (!isBenchmarkRun) {
                                speakOnAndroidTts(
                                    text = text,
                                    sourceEnglishText = sourceEnglishText,
                                    voiceProfile = effectiveVoiceProfile,
                                    isBenchmarkRun = false
                                )
                            }
                        }
                    }
                }
                return true
            } else {
                TranslatorStateRepository.updateKokoroStatusMessage(discovery.statusSummary)
            }
        }

        // 3. Android System / Preferred Engine Fallback
        val sysBackend = resolveSystemFallbackBackendCode(state)
        if (sysBackend == "SYSTEM_FALLBACK_SINGLE_VOICE") {
            val warnMsg = "WARNING: SYSTEM_FALLBACK_SINGLE_VOICE active — ${canonicalSelection.selectionReason}. ${activeAssignment.displayName} (SID #${effectiveRoute.selectedSpeakerId}) and all characters will sound identical on system TTS (${state.activeTtsEngineLabel}). Open Voice Manager to download & verify Supertonic 3 (~145 MB)."
            TranslatorStateRepository.updateVoiceEngineWarning(warnMsg, "SYSTEM_FALLBACK_SINGLE_VOICE")
        }
        com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
            routeDecision = effectiveRoute,
            actualSidPassedToNative = effectiveRoute.selectedSpeakerId,
            actualBackend = sysBackend,
            jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
            modelLoaded = false,
            fallbackReason = canonicalSelection.selectionReason,
            audioDurationMs = 0L,
            outputPcmRms = null
        )
        return speakOnAndroidTts(
            text = text,
            sourceEnglishText = sourceEnglishText,
            voiceProfile = effectiveVoiceProfile,
            isBenchmarkRun = isBenchmarkRun
        )
    }

    fun resolveSystemFallbackBackendCode(
        state: com.example.data.TranslatorAppState = TranslatorStateRepository.state.value
    ): String {
        return if (state.supportsDistinctGenderVoices && femaleVoices.isNotEmpty() && maleVoices.isNotEmpty()) {
            "SYSTEM_FALLBACK"
        } else {
            "SYSTEM_FALLBACK_SINGLE_VOICE"
        }
    }

    /**
     * Runs a real synthesis & playback latency benchmark on the currently active free voice backend
     * (user-selected Android TTS / Supertonic 3 OR optional imported Kokoro-82M MediaPipe model).
     * Never returns simulated or hardcoded latency numbers.
     */
    fun runVoiceBenchmark(sampleText: String? = null): Boolean {
        val state = TranslatorStateRepository.state.value
        val text = sampleText?.trim().orEmpty().ifBlank { state.targetLanguage.sampleGreeting }

        if (state.freeVoiceBackend == FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT) {
            if (!sherpaEngine.isReady && !sherpaEngine.initialize()) {
                TranslatorStateRepository.updateTtsLatencyBenchmarks(
                    isRunningBenchmark = false,
                    summary = "Cannot benchmark Sherpa-ONNX: Supertonic model not installed or native init failed (SYSTEM_FALLBACK active)."
                )
                return false
            }
            TranslatorStateRepository.updateTtsLatencyBenchmarks(
                isRunningBenchmark = true,
                summary = "Benchmarking Sherpa-ONNX Supertonic 3 (5 vs 8 ODE steps on CPU)..."
            )
            managerScope.launch(Dispatchers.Default) {
                val results = sherpaEngine.benchmarkSteps(text, listOf(5, 8))
                val summaryText = if (results.isNotEmpty()) {
                    results.joinToString(" • ") { res ->
                        "${res.numSteps} steps: 1stChunk=${res.firstChunkLatencyMs}ms, total=${res.generationTimeMs}ms, RMS=${String.format(Locale.US, "%.4f", res.waveformRms)} (RTF: ${String.format(Locale.US, "%.2fx", res.realTimeFactor)})"
                    }
                } else {
                    "Sherpa benchmark failed: native model produced no valid non-silent audio samples"
                }
                TranslatorStateRepository.updateTtsLatencyBenchmarks(
                    isRunningBenchmark = false,
                    summary = "Supertonic Hindi: $summaryText"
                )
                val firstResult = results.firstOrNull()
                if (firstResult != null) {
                    TranslatorStateRepository.updateDiagnosisMetrics(
                        ttsFirstAudioLatencyMs = firstResult.firstChunkLatencyMs,
                        ttsSynthesisRtf = firstResult.realTimeFactor
                    )
                }
            }
            return true
        }

        if (state.freeVoiceBackend == FreeVoiceBackend.MEDIAPIPE_KOKORO) {
            val discovery = kokoroEngine.discoverModelFile(state.kokoroModelPath)
            if (discovery.resolvedFile == null) {
                TranslatorStateRepository.updateKokoroStatusMessage(discovery.statusSummary)
                TranslatorStateRepository.updateTtsLatencyBenchmarks(
                    isRunningBenchmark = false,
                    summary = "Cannot benchmark Kokoro-82M: no .litertlm model file loaded."
                )
                return false
            }
            TranslatorStateRepository.updateTtsLatencyBenchmarks(
                isRunningBenchmark = true,
                summary = "Running real Kokoro-82M benchmark (${discovery.resolvedFile.name})..."
            )
            return speakNonBlocking(
                text = text,
                voiceProfile = null,
                sourceEnglishText = "",
                isBenchmarkRun = true
            )
        }

        val engine = tts
        if (engine == null || !isTtsReady) {
            TranslatorStateRepository.updateTtsLatencyBenchmarks(
                isRunningBenchmark = false,
                summary = "Cannot run benchmark: Android TTS engine '${state.activeTtsEngineLabel}' is not ready yet."
            )
            return false
        }
        TranslatorStateRepository.updateTtsLatencyBenchmarks(
            isRunningBenchmark = true,
            summary = "Running real TTS benchmark on ${state.activeTtsEngineLabel}..."
        )
        return speakNonBlocking(
            text = text,
            voiceProfile = null,
            sourceEnglishText = "",
            isBenchmarkRun = true
        )
    }

    private fun speakOnAndroidTts(
        text: String,
        sourceEnglishText: String,
        voiceProfile: com.example.data.VoiceProfile?,
        isBenchmarkRun: Boolean = false
    ): Boolean {
        val engine = tts
        if (engine == null || !isTtsReady) {
            pendingSubtitleToSpeak = text
            if (isBenchmarkRun) {
                TranslatorStateRepository.updateTtsLatencyBenchmarks(
                    isRunningBenchmark = false,
                    summary = "TTS engine unavailable for benchmark."
                )
            }
            if (engine == null && !isInitializing) {
                initializeAsync()
            }
            return false
        }
        val state = TranslatorStateRepository.state.value
        // Built-in Android TextToSpeech ALWAYS receives clean plain text + pitch/rate/volume (never raw SSML XML)
        return speakInternal(
            text = text,
            sourceEnglishText = sourceEnglishText,
            allowNetworkSynthesis = state.useHighQualityNetworkVoice,
            allowSsml = false,
            voiceProfile = voiceProfile,
            isBenchmarkRun = isBenchmarkRun
        )
    }

    /**
     * Converts multi-sentence or multi-line game text into a single uninterrupted utterance string
     * by replacing internal single sentence-splitting punctuation (`।`, `.`, `:`, `;`, newlines)
     * with soft comma pauses while preserving ellipsis (`...`) and exclamation (`!`) for Emotion Analysis.
     */
    private fun prepareSingleContinuousUtterance(rawText: String): String {
        val collapsed = rawText
            .replace(Regex("[\\r\\n]+"), ", ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (collapsed.isEmpty()) return ""

        // Preserve ellipsis "..." temporarily so internal-sentence regex doesn't collapse it
        val ellipsisToken = " __ELLIPSIS__ "
        val protectedEllipsis = collapsed
            .replace("...", ellipsisToken)
            .replace("…", ellipsisToken)

        val trailingPunct = protectedEllipsis.lastOrNull()?.takeIf { it == '।' || it == '.' || it == '!' || it == '?' }
        val body = if (trailingPunct != null) protectedEllipsis.dropLast(1).trim() else protectedEllipsis

        val smoothBody = body
            .replace(Regex("[।.]+\\s+"), ", ")
            .replace(Regex("[:;]+\\s*"), ", ")
            .replace(Regex(",(\\s*,)+"), ",")
            .replace(ellipsisToken, "... ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd(',')
            .trim()

        return if (smoothBody.isEmpty()) {
            collapsed
        } else if (trailingPunct != null) {
            "$smoothBody$trailingPunct"
        } else {
            smoothBody
        }
    }

    private fun speakInternal(
        text: String,
        sourceEnglishText: String = "",
        allowNetworkSynthesis: Boolean,
        allowSsml: Boolean = TranslatorStateRepository.state.value.ssmlEnabledOnDevice,
        voiceProfile: com.example.data.VoiceProfile? = null,
        isBenchmarkRun: Boolean = false
    ): Boolean {
        val engine = tts ?: return false
        val state = TranslatorStateRepository.state.value
        val fullContinuousText = prepareSingleContinuousUtterance(text)
        if (fullContinuousText.isEmpty()) return false

        return try {
            // Cancel any pending 30-second repeat timer because a genuinely new utterance is starting now
            thirtySecondRepeatDelayJob?.cancel()
            thirtySecondRepeatDelayJob = null

            val effectiveProfile = voiceProfile
            val targetGender = effectiveProfile?.gender
                ?: VoiceProfileRepository.lastParsedDialogue.value?.detectedGender
                ?: SpeakerGender.MALE_18

            // Analyze emotion (! -> Excited/Angry, ... -> Sad/Hesitant, whisper -> Whisper) and prosody
            val emotionResult = EmotionSpeechEngine.analyzeEmotionAndBuildSsml(
                spokenText = fullContinuousText,
                sourceEnglishText = sourceEnglishText,
                gender = targetGender,
                profilePitchOverride = if (effectiveProfile?.isCustomCloned == true) effectiveProfile.pitch else null,
                profileRateOverride = if (effectiveProfile?.isCustomCloned == true) effectiveProfile.speechRate else null,
                masterVolume = state.ttsVolume
            )

            TranslatorStateRepository.updateEmotionTelemetry(
                emotion = emotionResult.emotion,
                reason = emotionResult.analysisReason,
                ssmlPreview = emotionResult.ssmlMarkup
            )

            // Switch voice ONLY when the active installed engine exposes distinct Female & Male voices
            val appliedVoice = if (state.supportsDistinctGenderVoices) {
                selectGenderBasedTtsVoice(
                    engine = engine,
                    targetLanguage = state.targetLanguage,
                    gender = targetGender,
                    voiceIndex = effectiveProfile?.engineVoiceIndex ?: 0,
                    preferNetworkVoice = allowNetworkSynthesis
                )
            } else {
                defaultEngineVoice
            }

            // Apply Gender + Emotion Pitch, SpeechRate, and Volume
            engine.setPitch(emotionResult.effectivePitch)
            engine.setSpeechRate(emotionResult.effectiveSpeechRate)

            val hasDistinctGenderVoices = state.supportsDistinctGenderVoices &&
                femaleVoices.isNotEmpty() &&
                maleVoices.isNotEmpty()
            if (!hasDistinctGenderVoices) {
                val singleVoiceName = appliedVoice?.name ?: state.activeTtsEngineLabel
                val singleLabel = "SYSTEM_FALLBACK_SINGLE_VOICE ($singleVoiceName • pitch/rate only)"
                TranslatorStateRepository.setActiveVoiceName(singleLabel)
                TranslatorStateRepository.updateDiagnosisMetrics(
                    lastSynthesizedRms = Float.NaN,
                    activeVoiceName = singleLabel
                )
                if (state.voiceEngineWarningMessage.isBlank()) {
                    TranslatorStateRepository.updateVoiceEngineWarning(
                        "WARNING: ${state.activeTtsEngineLabel} cannot provide separate Male & Female voices (only 1 voice available). Switch to Human Voice (Chatterbox) or Fast Offline (Supertonic 3)."
                    )
                }
            } else if (appliedVoice != null) {
                val engineTag = if (state.isSupertonicOrSherpaActive) {
                    "Supertonic 3"
                } else if (isNetworkHighQualityVoice(appliedVoice)) {
                    "SYSTEM_FALLBACK (HD Network)"
                } else {
                    "SYSTEM_FALLBACK (Installed)"
                }
                val label = "$engineTag (${appliedVoice.name})"
                TranslatorStateRepository.setActiveVoiceName(label)
                TranslatorStateRepository.updateDiagnosisMetrics(
                    lastSynthesizedRms = Float.NaN,
                    activeVoiceName = label
                )
            } else {
                val singleLabel = "SYSTEM_FALLBACK_SINGLE_VOICE (${state.activeTtsEngineLabel})"
                TranslatorStateRepository.setActiveVoiceName(singleLabel)
                TranslatorStateRepository.updateDiagnosisMetrics(
                    lastSynthesizedRms = Float.NaN,
                    activeVoiceName = singleLabel
                )
            }

            val utteranceId = if (isBenchmarkRun) {
                "gamevox_bench_${UUID.randomUUID()}"
            } else {
                "gamevox_${UUID.randomUUID()}"
            }
            val useNetworkBundle = !state.isSupertonicOrSherpaActive &&
                allowNetworkSynthesis &&
                (appliedVoice == null || isNetworkHighQualityVoice(appliedVoice))

            // Built-in Android TextToSpeech MUST receive clean plain text (never raw SSML XML)
            val utterancePayload = emotionResult.cleanSpokenText

            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, emotionResult.effectiveVolume)
                if (useNetworkBundle) {
                    putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "true")
                    putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_TIMEOUT_MS, "4000")
                }
            }

            if (useNetworkBundle) {
                activeNetworkUtterances[utteranceId] = PendingNetworkUtterance(
                    text = fullContinuousText,
                    sourceEnglishText = sourceEnglishText,
                    voiceProfile = voiceProfile,
                    usedSsml = false
                )
            }

            isSpeakingUtterance = true
            activeUtteranceId = utteranceId
            if (isBenchmarkRun) {
                benchmarkUtteranceId = utteranceId
            }
            utteranceSubmitElapsedMs[utteranceId] = SystemClock.elapsedRealtime()

            // Queue Flush: Immediately stop any previous audio (QUEUE_FLUSH) and speak clean modulated text
            val result = engine.speak(utterancePayload, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                utteranceSubmitElapsedMs.remove(utteranceId)
                if (benchmarkUtteranceId == utteranceId) {
                    benchmarkUtteranceId = null
                    TranslatorStateRepository.updateTtsLatencyBenchmarks(
                        isRunningBenchmark = false,
                        summary = "Benchmark speak() failed with status $result"
                    )
                }
                activeNetworkUtterances.remove(utteranceId)
                isSpeakingUtterance = false
                activeUtteranceId = null
                TranslatorStateRepository.updateTtsLifecycleEvent(
                    event = "speak_failed($result)",
                    utteranceId = utteranceId,
                    error = "TextToSpeech.speak returned $result"
                )
                if (useNetworkBundle) {
                    return speakInternal(
                        text = text,
                        sourceEnglishText = sourceEnglishText,
                        allowNetworkSynthesis = false,
                        allowSsml = false,
                        voiceProfile = voiceProfile,
                        isBenchmarkRun = isBenchmarkRun
                    )
                }
            } else {
                startRealtimeVisualizerSync(
                    utteranceId = utteranceId,
                    text = emotionResult.cleanSpokenText,
                    voiceProfile = voiceProfile,
                    emotionResult = emotionResult
                )
            }
            result == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            isSpeakingUtterance = false
            activeUtteranceId = null
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
        voiceProfile: VoiceProfile?,
        emotionResult: EmotionAnalysisResult? = null,
        overrideVoiceName: String? = null
    ) {
        val state = TranslatorStateRepository.state.value
        val activePitch = emotionResult?.effectivePitch ?: voiceProfile?.pitch ?: state.ttsPitch
        val activeRate = emotionResult?.effectiveSpeechRate ?: voiceProfile?.speechRate ?: state.ttsSpeechRate
        val activeVolume = emotionResult?.effectiveVolume ?: state.ttsVolume
        val voiceName = overrideVoiceName ?: voiceProfile?.name ?: state.activeVoiceName
        val roleLabel = voiceProfile?.assignedRole?.displayLabel?.substringBefore(" (")
            ?: VoiceProfileRepository.lastDetectedSpeaker.value

        _visualizerState.value = TtsVisualizerState(
            isSpeaking = true,
            utteranceId = utteranceId,
            spokenText = text,
            activeVoiceName = voiceName,
            activeSpeakerRoleLabel = roleLabel,
            activeEmotion = emotionResult?.emotion ?: SpeechEmotion.NEUTRAL,
            activePitch = activePitch,
            activeSpeechRate = activeRate,
            activeVolume = activeVolume,
            ssmlPreview = emotionResult?.ssmlMarkup.orEmpty(),
            currentRangeStart = 0,
            currentRangeEnd = minOf(text.length, 4),
            progressFraction = 0.02f,
            rmsAmplitude = 0.45f * activeVolume,
            phaseRadians = 0f,
            frequencyBands = List(24) { 0.18f }
        )

        visualizerSyncJob?.cancel()
        visualizerSyncJob = managerScope.launch {
            val cleanText = text.trim()
            // Estimate natural speaking duration in ms (~85ms per char scaled by speechRate, min 2800ms, max 30000ms)
            val estimatedDurationMs = ((cleanText.length.coerceAtLeast(16) * 85f) / activeRate.coerceAtLeast(0.55f))
                .toLong()
                .coerceIn(2800L, 30000L)

            val startMs = System.currentTimeMillis()
            var frameIndex = 0
            var consecutiveNotSpeakingFrames = 0

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

                if (engineSpeaking) {
                    consecutiveNotSpeakingFrames = 0
                } else {
                    consecutiveNotSpeakingFrames++
                }

                // Wait for UtteranceProgressListener.onDone() to stop the visualizer; only exit here if
                // elapsed time exceeds estimatedDurationMs + 2500ms AND engine has stopped speaking for > 1.5s
                if (!engineSpeaking && elapsedMs > estimatedDurationMs + 2500L && consecutiveNotSpeakingFrames > 45) {
                    break
                }

                val timeProgress = (elapsedMs.toFloat() / estimatedDurationMs.toFloat()).coerceIn(0f, 0.98f)
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

            // Fallback cleanup ONLY if UtteranceProgressListener.onDone() did not fire on a stub/emulator engine
            if (activeUtteranceId == utteranceId) {
                isSpeakingUtterance = false
                activeUtteranceId = null
                if (thirtySecondRepeatDelayJob?.isActive != true) {
                    thirtySecondRepeatDelayJob = managerScope.launch {
                        delay(30000L)
                        onThirtySecondPostSpeechDelayCompleted?.invoke(utteranceId)
                    }
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

    private fun isNetworkHighQualityVoice(voice: Voice): Boolean {
        val features = voice.features.orEmpty()
        return voice.isNetworkConnectionRequired ||
            features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS) ||
            voice.name.contains("network", ignoreCase = true) ||
            voice.name.contains("wavenet", ignoreCase = true) ||
            voice.name.contains("neural", ignoreCase = true) ||
            voice.quality >= Voice.QUALITY_HIGH
    }

    /**
     * Fetches all available voices from [TextToSpeech.getVoices] (`engine.voices`) and populates
     * [femaleVoices] (e.g. `hi-in-x-hif-network`) and [maleVoices] (e.g. `hi-in-x-him-network`),
     * prioritizing High-Quality Network Voices over robotic local voices.
     */
    private fun populateGenderVoiceCatalog(
        engine: TextToSpeech,
        targetLanguage: TargetLanguage,
        preferNetworkVoice: Boolean = TranslatorStateRepository.state.value.useHighQualityNetworkVoice
    ) {
        try {
            femaleVoices.clear()
            maleVoices.clear()
            val allVoices = engine.voices ?: return
            val candidateVoices = allVoices.filter { voice ->
                val features = voice.features.orEmpty()
                val isNotInstalled = features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) ||
                    features.contains("notInstalled")
                val isNetVoice = isNetworkHighQualityVoice(voice)
                val langMatches = voice.locale != null &&
                    (voice.locale.language.equals(targetLanguage.code, ignoreCase = true) ||
                        voice.locale.language.equals("hi", ignoreCase = true))

                // Include all installed voices AND online -network voices when High-Quality Network Voice is enabled
                langMatches && (!isNotInstalled || (preferNetworkVoice && isNetVoice))
            }.sortedWith(
                compareByDescending<Voice> {
                    it.locale.language.equals(targetLanguage.code, ignoreCase = true)
                }.thenByDescending { voice ->
                    val features = voice.features.orEmpty()
                    val isInstalled = !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) &&
                        !features.contains("notInstalled")
                    val isNet = isNetworkHighQualityVoice(voice)
                    // Priority: 1) Installed HD Network voice, 2) Cloud Network voice, 3) Installed Local voice
                    when {
                        preferNetworkVoice && isNet && isInstalled -> 4
                        preferNetworkVoice && isNet -> 3
                        isInstalled -> 2
                        else -> 1
                    }
                }.thenByDescending { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.name }
            )

            if (candidateVoices.isEmpty()) return

            val detectedFemales = mutableListOf<Voice>()
            val detectedMales = mutableListOf<Voice>()

            val uncategorized = mutableListOf<Voice>()
            for (voice in candidateVoices) {
                val lowerName = voice.name.lowercase(Locale.ROOT)
                val lowerFeatures = voice.features.orEmpty().joinToString(" ").lowercase(Locale.ROOT)
                val combined = "$lowerName $lowerFeatures"

                val isExplicitFemale = combined.contains("female") ||
                    lowerName.contains("-f-") ||
                    lowerName.contains("_f_") ||
                    lowerName.endsWith("-f") ||
                    // Google TTS hi-IN female voice IDs (hif, hia, hic, hie, cfn)
                    lowerName.contains("hi-in-x-hif") ||
                    lowerName.contains("hi-in-x-hia") ||
                    lowerName.contains("hi-in-x-hic") ||
                    lowerName.contains("hi-in-x-hie") ||
                    lowerName.contains("hi-in-x-cfn") ||
                    lowerName.contains("wavenet-a") ||
                    lowerName.contains("wavenet-c") ||
                    lowerName.contains("standard-a") ||
                    lowerName.contains("standard-c") ||
                    lowerName.contains("en-in-x-ena") ||
                    lowerName.contains("en-in-x-enc") ||
                    lowerName.contains("en-in-x-ene")

                val isExplicitMale = (combined.contains("male") && !combined.contains("female")) ||
                    lowerName.contains("-m-") ||
                    lowerName.contains("_m_") ||
                    lowerName.endsWith("-m") ||
                    // Google TTS hi-IN male voice IDs (him, hid, hib)
                    lowerName.contains("hi-in-x-him") ||
                    lowerName.contains("hi-in-x-hid") ||
                    lowerName.contains("hi-in-x-hib") ||
                    lowerName.contains("wavenet-b") ||
                    lowerName.contains("wavenet-d") ||
                    lowerName.contains("standard-b") ||
                    lowerName.contains("standard-d") ||
                    lowerName.contains("en-in-x-enb") ||
                    lowerName.contains("en-in-x-end")

                when {
                    isExplicitFemale -> detectedFemales.add(voice)
                    isExplicitMale -> detectedMales.add(voice)
                    else -> uncategorized.add(voice)
                }
            }

            // If the engine exposes 2+ distinct voices that lack standard '-f-'/'-m-' substrings,
            // partition uncategorized voices so femaleVoices and maleVoices receive separate voice instances
            if (candidateVoices.size >= 2 && (detectedFemales.isEmpty() || detectedMales.isEmpty())) {
                for (voice in uncategorized) {
                    if (detectedFemales.size <= detectedMales.size) {
                        if (detectedFemales.none { it.name == voice.name } && detectedMales.none { it.name == voice.name }) {
                            detectedFemales.add(voice)
                        }
                    } else {
                        if (detectedMales.none { it.name == voice.name } && detectedFemales.none { it.name == voice.name }) {
                            detectedMales.add(voice)
                        }
                    }
                }
            }

            // Rank `hi-in-x-hif-network` / `-network` female voices at the front of femaleVoices
            val rankedFemales = detectedFemales.sortedWith(
                compareByDescending<Voice> {
                    it.name.contains("hif", ignoreCase = true) && it.name.contains("network", ignoreCase = true)
                }.thenByDescending {
                    preferNetworkVoice && isNetworkHighQualityVoice(it)
                }.thenByDescending { it.quality }
            )

            // Rank `hi-in-x-him-network` / `-network` male voices at the front of maleVoices
            val rankedMales = detectedMales.sortedWith(
                compareByDescending<Voice> {
                    it.name.contains("him", ignoreCase = true) && it.name.contains("network", ignoreCase = true)
                }.thenByDescending {
                    preferNetworkVoice && isNetworkHighQualityVoice(it)
                }.thenByDescending { it.quality }
            )

            femaleVoices.clear()
            femaleVoices.addAll(rankedFemales)
            maleVoices.clear()
            maleVoices.addAll(rankedMales)
        } catch (_: Exception) {
        }
    }

    /**
     * Automatically selects a Female TTS Voice (e.g. `hi-in-x-hif-network` from [femaleVoices]) or
     * Male TTS Voice (e.g. `hi-in-x-him-network` from [maleVoices]) based on the detected character's
     * [gender] ("Jenny" -> [SpeakerGender.FEMALE_18], "Anon" -> [SpeakerGender.MALE_18]) and calls
     * `engine.setVoice(chosenVoice)`.
     *
     * If a voice for that specific gender is not available on the device, switches cleanly to
     * [defaultEngineVoice] (and applies natural 18yo pitch & normal speechRate).
     */
    private fun selectGenderBasedTtsVoice(
        engine: TextToSpeech,
        targetLanguage: TargetLanguage,
        gender: SpeakerGender,
        voiceIndex: Int,
        preferNetworkVoice: Boolean
    ): Voice? {
        return try {
            if (femaleVoices.isEmpty() && maleVoices.isEmpty()) {
                populateGenderVoiceCatalog(engine, targetLanguage, preferNetworkVoice)
            }

            val genderPool: List<Voice> = when (gender) {
                SpeakerGender.FEMALE_18 -> femaleVoices
                SpeakerGender.MALE_18 -> maleVoices
                SpeakerGender.UNKNOWN -> emptyList()
            }

            val fallbackDefault: Voice? = defaultEngineVoice ?: try {
                engine.defaultVoice ?: engine.voice
            } catch (_: Exception) {
                null
            }

            val chosenVoice: Voice? = if (genderPool.isNotEmpty()) {
                val langFiltered = genderPool.filter {
                    it.locale?.language.equals(targetLanguage.code, ignoreCase = true)
                }.ifEmpty { genderPool }

                val orderedPool = if (preferNetworkVoice) {
                    langFiltered.sortedByDescending { isNetworkHighQualityVoice(it) }
                } else {
                    langFiltered.sortedBy { isNetworkHighQualityVoice(it) }
                }

                // Index 0 always picks the top-ranked network voice (e.g. hi-in-x-hif-network or hi-in-x-him-network)
                orderedPool[voiceIndex.coerceAtLeast(0) % orderedPool.size]
            } else {
                // Specific gender voice not found on device -> gracefully switch to default voice
                fallbackDefault
            }

            if (chosenVoice != null && engine.voice?.name != chosenVoice.name) {
                engine.setVoice(chosenVoice)
            }
            chosenVoice ?: fallbackDefault
        } catch (_: Exception) {
            null
        }
    }

    private fun flushPendingSubtitleIfReady() {
        val pending = pendingSubtitleToSpeak
        if (!pending.isNullOrBlank() && isTtsReady) {
            pendingSubtitleToSpeak = null
            speakNonBlocking(pending)
        }
    }

    fun testLocalChatterboxServerConnection(
        onResult: (NeuralServerHealthReport) -> Unit = {}
    ) {
        val state = TranslatorStateRepository.state.value
        TranslatorStateRepository.updateChatterboxServerStatus(
            reachable = false,
            modelLoaded = false,
            device = "checking",
            statusText = "Testing connection to ${state.chatterboxServerUrl}...",
            isTesting = true
        )
        managerScope.launch {
            val report = neuralVoiceClient.checkServerHealth(
                rawBaseUrl = state.chatterboxServerUrl,
                authToken = state.chatterboxAuthToken
            )
            val errCat = report.errorCategory
            val statusSummary = when {
                report.reachable && report.modelLoaded ->
                    "READY on ${report.device.uppercase(Locale.ROOT)} • ${report.uniqueReferenceVoicesSha256Count} unique reference voices (${report.roundTripLatencyMs}ms RTT)"
                report.reachable ->
                    "[${errCat.code}] ${errCat.displayTitle}: Server reachable (${report.roundTripLatencyMs}ms), but model not loaded (${report.errorMessage.ifBlank { report.status }})"
                else ->
                    "[${errCat.code}] ${errCat.displayTitle}: ${report.errorMessage} — start run_server_windows.bat on your Windows PC"
            }
            val warnMsg = if (report.reachable && report.modelLoaded) {
                ""
            } else {
                "WARNING [${errCat.code}]: ${errCat.displayTitle} — ${report.errorMessage.ifBlank { statusSummary }}"
            }
            TranslatorStateRepository.updateChatterboxServerStatus(
                reachable = report.reachable,
                modelLoaded = report.modelLoaded,
                device = report.device,
                statusText = statusSummary,
                modelId = report.modelId,
                engineName = report.engineName,
                sampleRate = report.sampleRate,
                referenceVoiceFilesCount = report.referenceVoiceFilesCount,
                uniqueVoicesSha256Count = report.uniqueReferenceVoicesSha256Count,
                conditionalsCachedCount = report.conditionalsCachedCount,
                pingRttMs = report.roundTripLatencyMs,
                isTesting = false,
                errorCategory = errCat.code,
                warningMessage = warnMsg
            )
            onResult(report)
        }
    }

    /**
     * Downloads, verifies SHA-256 hashes, initializes [sherpaEngine], and runs native Hindi inference
     * readiness checks for Male (`Anon`, SID #5) and Female (`Jenny`, SID #1) directly inside Voice Manager.
     *
     * Does NOT mark the model ready unless native inference succeeds. When ready, switches the active
     * dubbing backend to [FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT] and clears single-voice fallback warnings.
     */
    fun downloadVerifyAndInitializeSupertonic(
        requireWifi: Boolean = false,
        onProgress: (Float) -> Unit = {},
        onComplete: (Boolean, SupertonicModelState) -> Unit = { _, _ -> }
    ) {
        managerScope.launch(Dispatchers.IO) {
            val ok = SupertonicModelManager.downloadModel(
                context = appContext,
                requireWifi = requireWifi,
                sherpaBackend = sherpaEngine,
                onProgress = onProgress
            )
            val finalState = SupertonicModelManager.modelState.value
            if (ok && finalState is SupertonicModelState.Ready && sherpaEngine.isModelRuntimeInitialized) {
                TranslatorStateRepository.clearVoiceEngineWarning()
                if (TranslatorStateRepository.state.value.freeVoiceBackend != FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER) {
                    TranslatorStateRepository.setFreeVoiceBackend(appContext, FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT)
                }
            }
            withContext(Dispatchers.Main) {
                onComplete(ok, finalState)
            }
        }
    }

    /**
     * Runs SHA-256 integrity verification, native `OfflineTts` initialization, and Hindi native inference
     * readiness verification on preexisting files in `filesDir/sherpa_models/supertonic_3_hi/`.
     */
    fun runSupertonicReadinessCheck(
        onComplete: (Boolean, SupertonicModelState) -> Unit = { _, _ -> }
    ) {
        managerScope.launch(Dispatchers.IO) {
            val ok = SupertonicModelManager.verifyAndInitializeReadiness(
                context = appContext,
                sherpaBackend = sherpaEngine
            )
            val finalState = SupertonicModelManager.modelState.value
            if (ok && finalState is SupertonicModelState.Ready && sherpaEngine.isModelRuntimeInitialized) {
                TranslatorStateRepository.clearVoiceEngineWarning()
                if (TranslatorStateRepository.state.value.freeVoiceBackend != FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER) {
                    TranslatorStateRepository.setFreeVoiceBackend(appContext, FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT)
                }
            }
            withContext(Dispatchers.Main) {
                onComplete(ok, finalState)
            }
        }
    }

    /**
     * Synthesizes a Hindi test line for a specific [CharacterVoiceAssignment] and optional [explicitEmotion]
     * routed through the shared [com.example.engine.CharacterVoiceRouter.routeCharacterPreview],
     * exporting the resulting WAV file if [exportWavFile] is provided and reporting `(success, engineUsedSummary, waveformRms)`.
     */
    fun synthesizeCharacterPreview(
        assignment: com.example.data.CharacterVoiceAssignment,
        hindiText: String,
        explicitEmotion: SpeechEmotion? = null,
        exportWavFile: File? = null,
        onResult: (Boolean, String, Float) -> Unit = { _, _, _ -> }
    ) {
        val state = TranslatorStateRepository.state.value
        val epoch = _playbackEpoch.incrementAndGet()
        val routeDecision = com.example.engine.CharacterVoiceRouter.routeCharacterPreview(
            context = appContext,
            assignment = assignment,
            hindiDialogue = hindiText,
            sessionEpoch = epoch
        )
        val cleanText = routeDecision.hindiDialogue.trim()
        if (cleanText.isBlank()) {
            onResult(false, "Empty text", 0f)
            return
        }

        lastResolvedRouteDecision = routeDecision
        lastSpokenSpeakerId = routeDecision.selectedSpeakerId

        val resolvedAssignment = routeDecision.characterAssignment
        val emotionToUse = explicitEmotion ?: SpeechEmotion.fromCode(resolvedAssignment.defaultEmotion)
        val effectiveExag = explicitEmotion?.chatterboxExaggeration ?: resolvedAssignment.exaggeration
        val effectiveCfg = explicitEmotion?.chatterboxCfgWeight ?: resolvedAssignment.cfgWeight
        val spokenWithOverrides = routeDecision.hindiDialogue

        val canonicalSelection = com.example.engine.CharacterVoiceRouter.selectCanonicalVoiceBackend(
            context = appContext,
            assignment = resolvedAssignment,
            state = state
        )

        if (canonicalSelection.backend == com.example.engine.CanonicalVoiceBackend.PAUSE_DUBBING) {
            onResult(false, "PAUSE_DUBBING (Audio Paused)", Float.NaN)
            return
        }

        if (canonicalSelection.backend == com.example.engine.CanonicalVoiceBackend.CHATTERBOX_LOCAL_SERVER) {
            managerScope.launch(Dispatchers.Default) {
                var reportedRms = Float.NaN
                var reportedSummary = ""
                val ok = neuralVoiceClient.synthesizeAndPlay(
                    rawBaseUrl = state.chatterboxServerUrl,
                    authToken = state.chatterboxAuthToken,
                    request = NeuralSynthesizeRequest(
                        text = spokenWithOverrides,
                        characterId = resolvedAssignment.characterName,
                        referenceVoiceId = resolvedAssignment.referenceVoiceId,
                        languageId = "hi",
                        emotion = emotionToUse.code,
                        exaggeration = effectiveExag,
                        cfgWeight = effectiveCfg,
                        requestId = routeDecision.utteranceId,
                        epoch = epoch
                    ),
                    exportWavFile = exportWavFile,
                    onSynthesisTelemetry = { outcome ->
                        reportedRms = outcome.parsedWav.waveformRms
                        reportedSummary = "CHATTERBOX_LOCAL_SERVER (${outcome.voiceId}.wav • SHA ${outcome.voiceSha256.take(8)} • ${outcome.serverInferenceLatencyMs}ms • RMS ${String.format(Locale.US, "%.4f", reportedRms)})"
                        com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
                            routeDecision = routeDecision,
                            actualSidPassedToNative = routeDecision.selectedSpeakerId,
                            actualBackend = "CHATTERBOX_LOCAL_SERVER",
                            jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                            modelLoaded = true,
                            fallbackReason = "",
                            audioDurationMs = outcome.parsedWav.durationMs,
                            outputPcmRms = reportedRms
                        )
                        TranslatorStateRepository.updateDiagnosisMetrics(
                            serverRequestLatencyMs = outcome.serverRequestLatencyMs,
                            serverInferenceLatencyMs = outcome.serverInferenceLatencyMs,
                            conditioningLatencyMs = outcome.conditioningLatencyMs,
                            conditioningCached = outcome.conditioningCached,
                            ttsFirstAudioLatencyMs = outcome.serverRequestLatencyMs,
                            lastSynthesizedVoiceSha256 = outcome.voiceSha256,
                            lastSynthesizedRms = reportedRms,
                            activeVoiceName = "Chatterbox V3 (${resolvedAssignment.displayName} • ${outcome.voiceId})"
                        )
                    },
                    onError = { err ->
                        reportedSummary = "Local Server Unavailable ($err)"
                    }
                )
                if (ok && !reportedRms.isNaN()) {
                    withContext(Dispatchers.Main) {
                        onResult(true, reportedSummary, reportedRms)
                    }
                    return@launch
                }

                // Honest fallback to Supertonic or Android TTS if local server is offline during preview
                if (SupertonicModelManager.isModelReady(appContext) && (sherpaEngine.isReady || sherpaEngine.initialize())) {
                    val wavTarget = exportWavFile ?: File(appContext.cacheDir, "ab_preview_${resolvedAssignment.characterName}_sid${routeDecision.selectedSpeakerId}.wav")
                    val valResult = sherpaEngine.synthesizeAndValidateToWav(
                        text = spokenWithOverrides,
                        sid = routeDecision.selectedSpeakerId,
                        speed = resolvedAssignment.speed,
                        numSteps = sherpaEngine.configuredNumSteps,
                        outputWavFile = wavTarget
                    )
                    sherpaEngine.synthesizeAndPlay(
                        text = spokenWithOverrides,
                        sid = routeDecision.selectedSpeakerId,
                        speed = resolvedAssignment.speed,
                        pitch = resolvedAssignment.pitch,
                        epoch = epoch
                    )
                    com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
                        routeDecision = routeDecision,
                        actualSidPassedToNative = valResult.sid,
                        actualBackend = "SHERPA_DIRECT",
                        jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                        modelLoaded = true,
                        fallbackReason = "Chatterbox server offline",
                        audioDurationMs = valResult.audioDurationMs,
                        outputPcmRms = valResult.waveformRms
                    )
                    withContext(Dispatchers.Main) {
                        onResult(
                            valResult.waveformRms > 0.001f,
                            "SHERPA_FALLBACK (Sid #${valResult.sid} • ${valResult.totalSynthesisTimeMs}ms • RMS ${String.format(Locale.US, "%.4f", valResult.waveformRms)})",
                            valResult.waveformRms
                        )
                    }
                } else {
                    val sysBackend = resolveSystemFallbackBackendCode(state)
                    com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
                        routeDecision = routeDecision,
                        actualSidPassedToNative = routeDecision.selectedSpeakerId,
                        actualBackend = sysBackend,
                        jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                        modelLoaded = false,
                        fallbackReason = "Local Chatterbox server offline & Supertonic model not installed",
                        audioDurationMs = 0L,
                        outputPcmRms = null
                    )
                    withContext(Dispatchers.Main) {
                        val fallbackOk = speakOnAndroidTts(spokenWithOverrides, cleanText, routeDecision.voiceProfile, false)
                        onResult(
                            fallbackOk,
                            "$sysBackend (${state.activeTtsEngineLabel} — Local Chatterbox server offline • RMS UNKNOWN)",
                            Float.NaN
                        )
                    }
                }
            }
            return
        }

        // Canonical Direct Supertonic or System Fallback path
        managerScope.launch(Dispatchers.Default) {
            if (canonicalSelection.backend == com.example.engine.CanonicalVoiceBackend.SHERPA_DIRECT &&
                SupertonicModelManager.isModelReady(appContext) &&
                (sherpaEngine.isReady || sherpaEngine.initialize())
            ) {
                val wavTarget = exportWavFile ?: File(appContext.cacheDir, "ab_preview_${resolvedAssignment.characterName}_sid${routeDecision.selectedSpeakerId}.wav")
                val valResult = sherpaEngine.synthesizeAndValidateToWav(
                    text = spokenWithOverrides,
                    sid = routeDecision.selectedSpeakerId,
                    speed = resolvedAssignment.speed,
                    numSteps = sherpaEngine.configuredNumSteps,
                    outputWavFile = wavTarget
                )
                sherpaEngine.synthesizeAndPlay(
                    text = spokenWithOverrides,
                    sid = routeDecision.selectedSpeakerId,
                    speed = resolvedAssignment.speed,
                    pitch = resolvedAssignment.pitch,
                    epoch = epoch
                )
                com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
                    routeDecision = routeDecision,
                    actualSidPassedToNative = valResult.sid,
                    actualBackend = "SHERPA_DIRECT",
                    jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                    modelLoaded = true,
                    fallbackReason = "",
                    audioDurationMs = valResult.audioDurationMs,
                    outputPcmRms = valResult.waveformRms
                )
                withContext(Dispatchers.Main) {
                    onResult(
                        valResult.waveformRms > 0.001f,
                        "SHERPA_DIRECT (Sid #${valResult.sid} • ${valResult.totalSynthesisTimeMs}ms • RMS ${String.format(Locale.US, "%.4f", valResult.waveformRms)})",
                        valResult.waveformRms
                    )
                }
            } else {
                val sysBackend = resolveSystemFallbackBackendCode(state)
                com.example.engine.CharacterVoiceRouter.recordSpokenUtteranceAudit(
                    routeDecision = routeDecision,
                    actualSidPassedToNative = routeDecision.selectedSpeakerId,
                    actualBackend = sysBackend,
                    jniStatus = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary,
                    modelLoaded = false,
                    fallbackReason = canonicalSelection.selectionReason,
                    audioDurationMs = 0L,
                    outputPcmRms = null
                )
                withContext(Dispatchers.Main) {
                    val fallbackOk = speakOnAndroidTts(spokenWithOverrides, cleanText, routeDecision.voiceProfile, false)
                    onResult(
                        fallbackOk,
                        "$sysBackend (${state.activeTtsEngineLabel} • RMS UNKNOWN)",
                        Float.NaN
                    )
                }
            }
        }
    }

    fun stopSpeaking() {
        val newEpoch = _playbackEpoch.incrementAndGet()
        pendingSubtitleToSpeak = null
        activeNetworkUtterances.clear()
        utteranceSubmitElapsedMs.clear()
        utteranceStartElapsedMs.clear()
        if (benchmarkUtteranceId != null) {
            benchmarkUtteranceId = null
            TranslatorStateRepository.updateTtsLatencyBenchmarks(
                isRunningBenchmark = false,
                summary = "Benchmark stopped"
            )
        }
        thirtySecondRepeatDelayJob?.cancel()
        thirtySecondRepeatDelayJob = null
        isSpeakingUtterance = false
        activeUtteranceId = null
        CloudTtsService.stopPlayback()
        kokoroEngine.stopPlayback()
        sherpaEngine.stop()
        exoPlayerManager.stopAndClearQueue()
        neuralVoiceClient.stop()
        val state = TranslatorStateRepository.state.value
        if (state.freeVoiceBackend == FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER) {
            managerScope.launch(Dispatchers.IO) {
                neuralVoiceClient.cancelStaleSynthesis(
                    rawBaseUrl = state.chatterboxServerUrl,
                    authToken = state.chatterboxAuthToken,
                    minValidEpoch = newEpoch
                )
            }
        }
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        stopVisualizerSync()
        TranslatorStateRepository.updateTtsLifecycleEvent(
            event = "STOPPED",
            utteranceId = "",
            error = ""
        )
    }

    fun releaseAudioResources() {
        stopSpeaking()
        exoPlayerManager.release()
    }
}
