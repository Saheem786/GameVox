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
import android.util.Log
import com.example.data.ModelDownloadStatus
import com.example.data.TranslatorStateRepository
import com.example.data.TtsEngineStatus
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Process-wide Singleton coordinating:
 * 1. Bitmap ColorMatrix Inversion (white game subtitles on dark background -> black text on white)
 * 2. Google ML Kit Text Recognition (English OCR extraction) + AccessibilityService node text
 * 3. English -> Hindi Translation + Anti-Repeat check (lastSpokenText)
 * 4. Properly initialized Android TextToSpeech (hi-IN with fallback locales, USAGE_MEDIA audio stream,
 *    queued pending utterances while warming up, pitch = 0.8f, speechRate = 0.9f).
 */
class OcrTranslationPipeline private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "OcrTranslationPipeline"
        val HINDI_LOCALE: Locale = Locale.forLanguageTag("hi-IN")
        private val HINDI_GENERIC_LOCALE: Locale = Locale.forLanguageTag("hi")
        private val INDIAN_ENGLISH_LOCALE: Locale = Locale.forLanguageTag("en-IN")

        @Volatile
        private var INSTANCE: OcrTranslationPipeline? = null

        fun getInstance(context: Context): OcrTranslationPipeline {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OcrTranslationPipeline(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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
     * Initializes Android's native [TextToSpeech] asynchronously with USAGE_MEDIA audio stream
     * and Hindi Locale (hi-IN), falling back gracefully so speech ALWAYS works on any device.
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
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    configureTtsEngine(engine)

                    // If speech was requested while TTS was still initializing, speak it now!
                    val queued = pendingSpeechText
                    if (!queued.isNullOrBlank()) {
                        pendingSpeechText = null
                        speakHindiNonBlocking(queued, forceSpeakEvenIfRepeat = true)
                    }
                } else {
                    isTtsReady = false
                    TranslatorStateRepository.setTtsStatus(TtsEngineStatus.ERROR)
                    Log.w(TAG, "TextToSpeech initialization returned status: $status")
                }
            }
        } catch (e: Exception) {
            isTtsReady = false
            TranslatorStateRepository.setTtsStatus(TtsEngineStatus.ERROR)
            Log.w(TAG, "Unable to initialize TextToSpeech", e)
        }
    }

    private fun configureTtsEngine(engine: TextToSpeech) {
        val currentState = TranslatorStateRepository.state.value

        // Route TTS audio through USAGE_MEDIA (Music/Game stream) so it's always audible during gameplay
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

        val hiInResult = engine.setLanguage(HINDI_LOCALE)
        if (hiInResult != TextToSpeech.LANG_MISSING_DATA && hiInResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            isTtsReady = true
            TranslatorStateRepository.setTtsStatus(TtsEngineStatus.READY)
            Log.i(TAG, "Hindi TTS (hi-IN) ready with pitch=${currentState.ttsPitch}, rate=${currentState.ttsSpeechRate}")
            return
        }

        val hiResult = engine.setLanguage(HINDI_GENERIC_LOCALE)
        if (hiResult != TextToSpeech.LANG_MISSING_DATA && hiResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            isTtsReady = true
            TranslatorStateRepository.setTtsStatus(TtsEngineStatus.READY)
            return
        }

        // Fallback: even if offline Hindi voice pack isn't downloaded yet, try en-IN or default
        // and STILL mark isTtsReady = true so engine.speak() is never silently blocked!
        val enInResult = engine.setLanguage(INDIAN_ENGLISH_LOCALE)
        if (enInResult == TextToSpeech.LANG_MISSING_DATA || enInResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        // Attempt setting hi-IN one more time for network synthesis if Google TTS is active
        engine.setLanguage(HINDI_LOCALE)
        isTtsReady = true
        TranslatorStateRepository.setTtsStatus(TtsEngineStatus.FALLBACK_VOICE_READY)
        Log.w(TAG, "Hindi offline pack missing; TTS enabled in fallback/network mode so audio still plays.")
    }

    /**
     * Re-checks Hindi voice data availability and applies latest pitch & speechRate.
     */
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
     * from an active game window without requiring bitmap OCR.
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
     * Processes a captured screen [rawBitmap]:
     * 1. Applies ColorMatrix inversion (unless disabled in settings)
     * 2. Runs ML Kit OCR
     * 3. Translates English -> Hindi
     * 4. Checks Anti-Repeat [lastSpokenText] and speaks Hindi out loud via non-blocking TTS.
     */
    suspend fun processCapturedFrame(
        rawBitmap: Bitmap,
        excludeOverlayRect: Rect? = null,
        onDebugTextUpdate: ((english: String, hindi: String, statusNote: String) -> Unit)? = null
    ): Pair<String, String> = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val appState = TranslatorStateRepository.state.value

        // Step 1: Apply ColorMatrix Inversion (White text on dark -> Black text on white)
        val processedBitmap = if (appState.invertColorsEnabled) {
            BitmapInverter.invertBitmap(rawBitmap, excludeOverlayRect)
        } else {
            rawBitmap
        }

        // Save lightweight thumbnails for the live ColorMatrix Inversion Inspector in UI
        val origThumb = BitmapInverter.createPreviewThumbnail(rawBitmap)
        val invThumb = BitmapInverter.createPreviewThumbnail(processedBitmap)
        TranslatorStateRepository.updateFramePreviews(origThumb, invThumb)

        // Step 2: Run Google ML Kit Text Recognition on the inverted bitmap
        val extractedEnglish = try {
            val inputImage = InputImage.fromBitmap(processedBitmap, 0)
            val visionText = textRecognizer.process(inputImage).await()
            cleanExtractedSubtitle(visionText.text)
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit OCR processing skipped", e)
            ""
        }

        if (extractedEnglish.isBlank()) {
            withContext(Dispatchers.Main) {
                onDebugTextUpdate?.invoke(
                    "No subtitle detected in frame",
                    TranslatorStateRepository.state.value.lastTranslatedHindi,
                    "Scanning..."
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
        val translatedHindi = translateEnglishToHindi(extractedEnglish)
        val elapsedMs = System.currentTimeMillis() - startTime
        val timestamp = timeFormatter.format(Date())

        val normalizedHindi = translatedHindi.trim()
        val normalizedEnglish = extractedEnglish.trim().lowercase(Locale.ROOT)

        val isRepeat = (normalizedHindi.isNotEmpty() && normalizedHindi == lastSpokenText) ||
            (normalizedEnglish.isNotEmpty() && normalizedEnglish == lastExtractedNormalized && lastSpokenText.isNotEmpty())

        if (isRepeat) {
            TranslatorStateRepository.recordTranslationResult(
                english = extractedEnglish,
                hindi = translatedHindi,
                wasSpoken = false,
                skippedReason = "Anti-Repeat: Identical to lastSpokenText",
                processingTimeMs = elapsedMs,
                timestamp = timestamp
            )
            withContext(Dispatchers.Main) {
                onDebugTextUpdate?.invoke(
                    extractedEnglish,
                    translatedHindi,
                    "Skipped (Anti-Repeat)"
                )
            }
            return extractedEnglish to translatedHindi
        }

        lastSpokenText = normalizedHindi
        lastExtractedNormalized = normalizedEnglish

        val spokenSuccess = speakHindiNonBlocking(translatedHindi)
        val skipNote = if (spokenSuccess) null else "TTS Queued / Warming Up"

        TranslatorStateRepository.recordTranslationResult(
            english = extractedEnglish,
            hindi = translatedHindi,
            wasSpoken = spokenSuccess,
            skippedReason = skipNote,
            processingTimeMs = elapsedMs,
            timestamp = timestamp
        )

        withContext(Dispatchers.Main) {
            onDebugTextUpdate?.invoke(
                extractedEnglish,
                translatedHindi,
                if (spokenSuccess) "Speaking Hindi (hi-IN)" else (skipNote ?: "Translated")
            )
        }

        return extractedEnglish to translatedHindi
    }

    suspend fun translateEnglishToHindi(englishText: String): String = withContext(Dispatchers.Default) {
        HindiTranslationEngine.translate(englishText)
    }

    /**
     * Speaks [hindiText] using Android's native [TextToSpeech] on the Media stream.
     * If TTS is still warming up, queues [hindiText] so it speaks immediately once ready.
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
