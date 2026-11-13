package com.example.engine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.example.data.ModelDownloadStatus
import com.example.data.TranslatorStateRepository
import com.example.data.TtsEngineStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-wide Singleton coordinating:
 * 1. [ImageProcessor] Target Bounding Box Cropping + Grayscale + ColorMatrix Inversion + ML Kit OCR
 * 2. AccessibilityService bounding-box node & screenshot overlay extraction
 * 3. Google Translate Neural API + Offline English -> Hindi Translation + Anti-Repeat check (lastSpokenText)
 * 4. High-Quality Network Voice Android TextToSpeech (`com.google.android.tts`, `KEY_FEATURE_NETWORK_SYNTHESIS`,
 *    highest-quality neural `Voice` selection, `USAGE_MEDIA` audio stream).
 */
class OcrTranslationPipeline private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "OcrTranslationPipeline"
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        val HINDI_LOCALE: Locale = Locale.forLanguageTag("hi-IN")
        private val INDIAN_ENGLISH_LOCALE: Locale = Locale.forLanguageTag("en-IN")

        @Volatile
        private var INSTANCE: OcrTranslationPipeline? = null

        fun getInstance(context: Context): OcrTranslationPipeline {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OcrTranslationPipeline(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    val imageProcessor: ImageProcessor by lazy {
        ImageProcessor()
    }

    @Volatile
    private var isTtsReady: Boolean = false

    @Volatile
    private var pendingSpeechText: String? = null

    private var tts: TextToSpeech? = null

    /**
     * Anti-Repeat state variable:
     * If the newly translated Hindi text is identical to [lastSpokenText], skip speaking it again.
     */
    @Volatile
    var lastSpokenText: String = ""
        private set

    @Volatile
    private var lastExtractedNormalized: String = ""

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        initializeTtsAsync()
        ensureTranslationModelDownloaded()
    }

    /**
     * Initializes Android's [TextToSpeech] preferring Google Speech Services (`com.google.android.tts`)
     * so high-quality neural network Hindi voices (`hi-in-x-hie-network`, `hi-in-x-hid-network`, etc.)
     * are available, falling back to the system default engine if Google TTS is not installed.
     */
    @Synchronized
    fun initializeTtsAsync() {
        if (tts != null && isTtsReady) {
            refreshTtsConfiguration()
            return
        }
        TranslatorStateRepository.setTtsStatus(TtsEngineStatus.INITIALIZING)
        try {
            tts?.shutdown()
            tts = TextToSpeech(
                appContext,
                { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val engine = tts ?: return@TextToSpeech
                        configureTtsEngine(engine)
                        flushPendingSpeechIfAny()
                    } else {
                        // Fallback to default TTS engine constructor if explicit package binding failed
                        initializeDefaultTtsFallback()
                    }
                },
                GOOGLE_TTS_PACKAGE
            )
        } catch (e: Exception) {
            initializeDefaultTtsFallback()
        }
    }

    private fun initializeDefaultTtsFallback() {
        try {
            tts?.shutdown()
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    configureTtsEngine(engine)
                    flushPendingSpeechIfAny()
                } else {
                    isTtsReady = false
                    TranslatorStateRepository.setTtsStatus(TtsEngineStatus.ERROR)
                    Log.w(TAG, "TextToSpeech initialization returned status: $status")
                }
            }
        } catch (e: Exception) {
            isTtsReady = false
            TranslatorStateRepository.setTtsStatus(TtsEngineStatus.ERROR)
            Log.w(TAG, "Unable to initialize fallback TextToSpeech", e)
        }
    }

    private fun flushPendingSpeechIfAny() {
        val queued = pendingSpeechText
        if (!queued.isNullOrBlank()) {
            pendingSpeechText = null
            speakHindiNonBlocking(queued, forceSpeakEvenIfRepeat = true)
        }
    }

    /**
     * Configures [engine] to use `USAGE_MEDIA` audio attributes and selects the highest-quality
     * neural/network [Voice] available for the target language so speech sounds natural rather than robotic.
     */
    private fun configureTtsEngine(engine: TextToSpeech) {
        val currentState = TranslatorStateRepository.state.value

        try {
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            engine.setAudioAttributes(audioAttrs)
        } catch (_: Exception) {
        }

        engine.setPitch(currentState.ttsPitch)
        engine.setSpeechRate(currentState.ttsSpeechRate)

        val targetLang = currentState.targetLanguage
        val primaryLocale = targetLang.locale
        val genericLocale = Locale.forLanguageTag(targetLang.code)

        val primaryResult = engine.setLanguage(primaryLocale)
        val langAvailable = primaryResult != TextToSpeech.LANG_MISSING_DATA &&
            primaryResult != TextToSpeech.LANG_NOT_SUPPORTED

        if (!langAvailable) {
            val genericResult = engine.setLanguage(genericLocale)
            if (genericResult == TextToSpeech.LANG_MISSING_DATA || genericResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                val enInResult = engine.setLanguage(INDIAN_ENGLISH_LOCALE)
                if (enInResult == TextToSpeech.LANG_MISSING_DATA || enInResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.getDefault())
                }
                engine.setLanguage(primaryLocale)
            }
        }

        // Select the best High-Quality Network Voice from engine.voices
        val selectedVoice = selectBestVoiceForLanguage(
            engine = engine,
            langCode = targetLang.code,
            preferNetworkVoice = currentState.useHighQualityNetworkVoice
        )

        if (selectedVoice != null) {
            try {
                engine.voice = selectedVoice
                val qualityBadge = if (selectedVoice.isNetworkConnectionRequired ||
                    selectedVoice.name.contains("network", ignoreCase = true) ||
                    selectedVoice.quality >= Voice.QUALITY_HIGH
                ) {
                    "HD Neural (${selectedVoice.name})"
                } else {
                    "Local (${selectedVoice.name})"
                }
                TranslatorStateRepository.setActiveVoiceName(qualityBadge)
                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.READY)
            } catch (_: Exception) {
                TranslatorStateRepository.setTtsStatus(TtsEngineStatus.READY)
            }
        } else {
            TranslatorStateRepository.setActiveVoiceName(
                if (currentState.useHighQualityNetworkVoice) {
                    "Google Network Synthesis (${targetLang.ttsLocaleTag})"
                } else {
                    "Standard (${targetLang.ttsLocaleTag})"
                }
            )
            TranslatorStateRepository.setTtsStatus(
                if (langAvailable) TtsEngineStatus.READY else TtsEngineStatus.FALLBACK_VOICE_READY
            )
        }

        isTtsReady = true
    }

    /**
     * Searches `engine.voices` for the most natural, high-quality voice matching [langCode].
     * Prioritizes neural/network voices (`isNetworkConnectionRequired`, `-network`, `wavenet`,
     * `QUALITY_VERY_HIGH`, `QUALITY_HIGH`) when [preferNetworkVoice] is true.
     */
    private fun selectBestVoiceForLanguage(
        engine: TextToSpeech,
        langCode: String,
        preferNetworkVoice: Boolean
    ): Voice? {
        return try {
            val availableVoices = engine.voices ?: return null
            val matchingVoices = availableVoices.filter { voice ->
                voice.locale != null &&
                    voice.locale.language.equals(langCode, ignoreCase = true) &&
                    !voice.features.orEmpty().contains("notInstalled")
            }
            if (matchingVoices.isEmpty()) return null

            matchingVoices.maxWithOrNull(
                compareBy<Voice> { voice ->
                    val isNetwork = voice.isNetworkConnectionRequired ||
                        voice.name.contains("network", ignoreCase = true) ||
                        voice.name.contains("wavenet", ignoreCase = true)
                    if (preferNetworkVoice) {
                        if (isNetwork) 2 else 1
                    } else {
                        if (!isNetwork) 2 else 1
                    }
                }
                    .thenBy { it.quality }
                    .thenBy { -it.latency }
            )
        } catch (e: Exception) {
            null
        }
    }

    fun refreshTtsConfiguration() {
        val engine = tts
        if (engine == null) {
            initializeTtsAsync()
            return
        }
        configureTtsEngine(engine)
    }

    fun ensureTranslationModelDownloaded() {
        TranslatorStateRepository.setModelStatus(ModelDownloadStatus.READY)
    }

    /**
     * Processes text extracted directly by [com.example.service.GameVoxAccessibilityService]
     * from inside the user's Draggable & Resizable Target Bounding Box.
     */
    suspend fun processExtractedRawText(
        rawEnglishText: String,
        onDebugTextUpdate: ((english: String, hindi: String, statusNote: String) -> Unit)? = null
    ): Pair<String, String> = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val extractedEnglish = cleanExtractedSubtitle(rawEnglishText)
        if (extractedEnglish.isBlank()) {
            return@withContext "" to ""
        }
        handleExtractedSubtitle(extractedEnglish, startTime, onDebugTextUpdate)
    }

    /**
     * Processes a captured screen [rawBitmap] using [ImageProcessor]:
     * 1. Crops [rawBitmap] strictly to [targetRegionScreenRect] (the user's Draggable Overlay Box)
     * 2. Applies Grayscale + Color-Inversion
     * 3. Extracts text from the cropped box via Google ML Kit Text Recognition
     * 4. Translates English -> selected TargetLanguage via Google Translate API + Offline fallback
     * 5. Checks Anti-Repeat [lastSpokenText] and speaks via High-Quality Network TTS.
     */
    suspend fun processCapturedFrame(
        rawBitmap: Bitmap,
        excludeOverlayRect: Rect? = null,
        targetRegionScreenRect: Rect? = null,
        rawScreenWidth: Int = 0,
        rawScreenHeight: Int = 0,
        onDebugTextUpdate: ((english: String, hindi: String, statusNote: String) -> Unit)? = null
    ): Pair<String, String> = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val appState = TranslatorStateRepository.state.value

        val extractionResult = imageProcessor.extractTextFromOverlayBitmap(
            source = rawBitmap,
            enableColorInversion = appState.invertColorsEnabled,
            excludeOverlayRect = excludeOverlayRect,
            targetRegionScreenRect = targetRegionScreenRect,
            rawScreenWidth = rawScreenWidth,
            rawScreenHeight = rawScreenHeight
        )

        val origThumb = BitmapInverter.createPreviewThumbnail(extractionResult.croppedOriginalBitmap)
        val invThumb = BitmapInverter.createPreviewThumbnail(extractionResult.processedBitmap)
        TranslatorStateRepository.updateFramePreviews(origThumb, invThumb)

        val extractedEnglish = cleanExtractedSubtitle(extractionResult.extractedText)
        if (extractedEnglish.isBlank()) {
            withContext(Dispatchers.Main) {
                onDebugTextUpdate?.invoke(
                    "No text inside Scan Box",
                    TranslatorStateRepository.state.value.lastTranslatedHindi,
                    "Drag Box over Game Text"
                )
            }
            return@withContext "" to ""
        }

        handleExtractedSubtitle(extractedEnglish, startTime, onDebugTextUpdate)
    }

    private suspend fun handleExtractedSubtitle(
        extractedEnglish: String,
        startTime: Long,
        onDebugTextUpdate: ((english: String, hindi: String, statusNote: String) -> Unit)?
    ): Pair<String, String> {
        val targetLang = TranslatorStateRepository.state.value.targetLanguage
        val translatedText = translateEnglishToHindi(extractedEnglish)
        val elapsedMs = System.currentTimeMillis() - startTime
        val timestamp = timeFormatter.format(Date())

        val normalizedTranslated = translatedText.trim()
        val normalizedEnglish = extractedEnglish.trim().lowercase(Locale.ROOT)

        val isRepeat = (normalizedTranslated.isNotEmpty() && normalizedTranslated == lastSpokenText) ||
            (normalizedEnglish.isNotEmpty() && normalizedEnglish == lastExtractedNormalized && lastSpokenText.isNotEmpty())

        if (isRepeat) {
            TranslatorStateRepository.recordTranslationResult(
                english = extractedEnglish,
                hindi = translatedText,
                wasSpoken = false,
                skippedReason = "Anti-Repeat: Identical to lastSpokenText",
                processingTimeMs = elapsedMs,
                timestamp = timestamp
            )
            withContext(Dispatchers.Main) {
                onDebugTextUpdate?.invoke(
                    extractedEnglish,
                    translatedText,
                    "Skipped (Anti-Repeat)"
                )
            }
            return extractedEnglish to translatedText
        }

        lastSpokenText = normalizedTranslated
        lastExtractedNormalized = normalizedEnglish

        val spokenSuccess = speakHindiNonBlocking(translatedText)
        val skipNote = if (spokenSuccess) null else "TTS Queued / Warming Up"

        TranslatorStateRepository.recordTranslationResult(
            english = extractedEnglish,
            hindi = translatedText,
            wasSpoken = spokenSuccess,
            skippedReason = skipNote,
            processingTimeMs = elapsedMs,
            timestamp = timestamp
        )

        withContext(Dispatchers.Main) {
            onDebugTextUpdate?.invoke(
                extractedEnglish,
                translatedText,
                if (spokenSuccess) "Speaking ${targetLang.displayName} (HD)" else (skipNote ?: "Translated")
            )
        }

        return extractedEnglish to translatedText
    }

    suspend fun translateEnglishToHindi(englishText: String): String = withContext(Dispatchers.IO) {
        val targetLang = TranslatorStateRepository.state.value.targetLanguage
        HindiTranslationEngine.translate(englishText, targetLang)
    }

    /**
     * Speaks [hindiText] using Android's [TextToSpeech] with `KEY_FEATURE_NETWORK_SYNTHESIS = "true"`
     * enabled so Google TTS synthesizes natural, non-robotic neural speech.
     */
    fun speakHindiNonBlocking(hindiText: String, forceSpeakEvenIfRepeat: Boolean = false): Boolean {
        if (hindiText.isBlank()) return false
        if (forceSpeakEvenIfRepeat) {
            lastSpokenText = hindiText.trim()
        }

        val engine = tts
        if (engine == null || !isTtsReady) {
            pendingSpeechText = hindiText
            if (engine == null) {
                initializeTtsAsync()
            }
            return false
        }

        val state = TranslatorStateRepository.state.value
        return try {
            engine.setPitch(state.ttsPitch)
            engine.setSpeechRate(state.ttsSpeechRate)
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                if (state.useHighQualityNetworkVoice) {
                    putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "true")
                }
            }
            val utteranceId = "gamevox_${UUID.randomUUID()}"
            val result = engine.speak(hindiText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            result == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            Log.w(TAG, "Error speaking Hindi text", e)
            false
        }
    }

    fun resetAntiRepeatMemory() {
        lastSpokenText = ""
        lastExtractedNormalized = ""
        TranslatorStateRepository.clearLastSpokenMemory()
    }

    fun promptInstallHindiTtsData(context: Context) {
        try {
            val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: ActivityNotFoundException) {
            try {
                val settingsIntent = Intent("com.android.settings.TTS_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
            } catch (ex: Exception) {
                Log.w(TAG, "Unable to launch TTS installation or settings intent", ex)
            }
        }
    }

    private fun cleanExtractedSubtitle(rawText: String): String {
        return rawText
            .lines()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
