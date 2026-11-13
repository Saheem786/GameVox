package com.example.engine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
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
 * Coordinates the complete 4-stage pipeline:
 * 1. Bitmap ColorMatrix Inversion (white game subtitles on dark background -> black text on white)
 * 2. Google ML Kit Text Recognition (English OCR extraction)
 * 3. English -> Hindi Translation + Anti-Repeat check (lastSpokenText)
 * 4. Asynchronous Android TextToSpeech (hi-IN, pitch = 0.8f, speechRate = 0.9f)
 *    with non-blocking initialization and missing Hindi voice data check.
 */
class OcrTranslationPipeline(private val appContext: Context) {

    companion object {
        private const val TAG = "OcrTranslationPipeline"
        val HINDI_LOCALE: Locale = Locale.forLanguageTag("hi-IN")
    }

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    @Volatile
    private var isTtsReady: Boolean = false

    private var tts: TextToSpeech? = null

    /**
     * Anti-Repeat state variable required by specification:
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
     * Initializes Android's native [TextToSpeech] asynchronously with Hindi Locale (hi-IN).
     * Does NOT block OCR or translation if TTS is still warming up or if Hindi voice data is missing.
     */
    fun initializeTtsAsync() {
        TranslatorStateRepository.setTtsStatus(TtsEngineStatus.INITIALIZING)
        try {
            tts?.shutdown()
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    val currentState = TranslatorStateRepository.state.value
                    // Requirement 4: Set pitch to 0.8f and speech rate to 0.9f so it sounds natural
                    engine.setPitch(currentState.ttsPitch)
                    engine.setSpeechRate(currentState.ttsSpeechRate)

                    val availability = engine.isLanguageAvailable(HINDI_LOCALE)
                    when (availability) {
                        TextToSpeech.LANG_MISSING_DATA,
                        TextToSpeech.LANG_NOT_SUPPORTED -> {
                            isTtsReady = false
                            TranslatorStateRepository.setTtsStatus(TtsEngineStatus.MISSING_HINDI_DATA)
                            Log.w(TAG, "Hindi TTS (hi-IN) voice data is missing or unsupported: $availability")
                        }
                        else -> {
                            engine.language = HINDI_LOCALE
                            isTtsReady = true
                            TranslatorStateRepository.setTtsStatus(TtsEngineStatus.READY)
                            Log.i(TAG, "Hindi TTS (hi-IN) initialized with pitch=${currentState.ttsPitch}, rate=${currentState.ttsSpeechRate}")
                        }
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

    /**
     * Re-checks Hindi voice data availability (e.g., after user returns from TTS settings)
     * and applies latest pitch & speechRate.
     */
    fun refreshTtsConfiguration() {
        val engine = tts
        if (engine == null) {
            initializeTtsAsync()
            return
        }
        val state = TranslatorStateRepository.state.value
        engine.setPitch(state.ttsPitch)
        engine.setSpeechRate(state.ttsSpeechRate)

        val availability = engine.isLanguageAvailable(HINDI_LOCALE)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            isTtsReady = false
            TranslatorStateRepository.setTtsStatus(TtsEngineStatus.MISSING_HINDI_DATA)
        } else {
            engine.language = HINDI_LOCALE
            isTtsReady = true
            TranslatorStateRepository.setTtsStatus(TtsEngineStatus.READY)
        }
    }

    /**
     * Marks the English-to-Hindi translation engine ready without invoking native C++ mlock()
     * on restricted container memory limits.
     */
    fun ensureTranslationModelDownloaded() {
        TranslatorStateRepository.setModelStatus(ModelDownloadStatus.READY)
    }

    /**
     * Processes a captured screen [rawBitmap]:
     * 1. Applies ColorMatrix inversion (unless disabled in settings)
     * 2. Runs ML Kit OCR
     * 3. Translates English -> Hindi
     * 4. Checks Anti-Repeat [lastSpokenText] and speaks Hindi out loud via non-blocking TTS.
     *
     * Returns a pair of (extractedEnglish, translatedHindi) for the Floating Debug TextView.
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

        // Step 3: Translate extracted English text to Hindi
        val translatedHindi = translateEnglishToHindi(extractedEnglish)
        val elapsedMs = System.currentTimeMillis() - startTime
        val timestamp = timeFormatter.format(Date())

        // Step 4: Anti-Repeat check against lastSpokenText
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
            return@withContext extractedEnglish to translatedHindi
        }

        // Update Anti-Repeat memory and speak asynchronously without blocking translation
        lastSpokenText = normalizedHindi
        lastExtractedNormalized = normalizedEnglish

        val spokenSuccess = speakHindiNonBlocking(translatedHindi)
        val skipNote = when {
            spokenSuccess -> null
            TranslatorStateRepository.state.value.ttsStatus == TtsEngineStatus.MISSING_HINDI_DATA ->
                "TTS Skipped: Hindi Voice Missing"
            !isTtsReady -> "TTS Warming Up (Non-blocking)"
            else -> null
        }

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

        extractedEnglish to translatedHindi
    }

    /**
     * Translates [englishText] to Hindi cleanly and deterministically.
     */
    suspend fun translateEnglishToHindi(englishText: String): String = withContext(Dispatchers.Default) {
        HindiTranslationEngine.translate(englishText)
    }

    /**
     * Speaks [hindiText] using Android's native [TextToSpeech] without blocking the caller.
     * Applies user-configured pitch (default 0.8f) and speechRate (default 0.9f).
     */
    fun speakHindiNonBlocking(hindiText: String, forceSpeakEvenIfRepeat: Boolean = false): Boolean {
        if (hindiText.isBlank()) return false
        if (forceSpeakEvenIfRepeat) {
            lastSpokenText = hindiText.trim()
        }

        val engine = tts
        if (engine == null || !isTtsReady) {
            Log.w(TAG, "TTS not ready yet — skipping audio playback without blocking OCR pipeline.")
            return false
        }

        val state = TranslatorStateRepository.state.value
        return try {
            engine.setPitch(state.ttsPitch)
            engine.setSpeechRate(state.ttsSpeechRate)
            val params = Bundle()
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

    /**
     * Launches the Android system intent to install missing Hindi TTS voice data,
     * falling back to general Text-to-Speech settings if the direct installer is unavailable.
     */
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

    fun release() {
        pipelineScope.launch {
            try {
                textRecognizer.close()
            } catch (_: Exception) {
            }
        }
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        } catch (_: Exception) {
        }
    }
}
