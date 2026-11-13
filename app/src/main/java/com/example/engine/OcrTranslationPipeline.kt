package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.data.ModelDownloadStatus
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.service.ForegroundTtsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-wide Singleton coordinating:
 * 1. [ImageProcessor] Target Bounding Box Cropping + Grayscale + ColorMatrix Inversion + ML Kit OCR
 * 2. AccessibilityService bounding-box node & screenshot overlay extraction
 * 3. Multi-Speaker Character Detection ([VoiceProfileRepository.resolveSpeakerAndVoiceForSubtitle])
 * 4. Google Translate Neural API + Offline English -> Hindi Translation + Anti-Repeat check (lastSpokenText)
 * 5. [ForegroundTtsManager] for Multi-Voice & High-Quality Network Voice TextToSpeech.
 */
class OcrTranslationPipeline private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    companion object {
        val HINDI_LOCALE: Locale = Locale.forLanguageTag("hi-IN")

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

    val ttsManager: ForegroundTtsManager by lazy {
        ForegroundTtsManager.getInstance(appContext)
    }

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
        VoiceProfileRepository.initialize(appContext)
        initializeTtsAsync()
        ensureTranslationModelDownloaded()
    }

    /**
     * Initializes [ForegroundTtsManager] asynchronously without blocking OCR or translation.
     */
    fun initializeTtsAsync() {
        ttsManager.registerTtsDataBroadcastReceiver()
        ttsManager.initializeAsync()
    }

    fun refreshTtsConfiguration() {
        ttsManager.refreshVoiceConfiguration()
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
        handleExtractedSubtitle(rawEnglishText, extractedEnglish, startTime, onDebugTextUpdate)
    }

    /**
     * Processes a captured screen [rawBitmap] using [ImageProcessor]:
     * 1. Crops [rawBitmap] strictly to [targetRegionScreenRect] (the user's Draggable Overlay Box)
     * 2. Applies Grayscale + Color-Inversion
     * 3. Extracts text from the cropped box via Google ML Kit Text Recognition
     * 4. Runs [SpeakerHeuristicEngine] to detect character name tags (`"Character A:"`, etc.) and map to [VoiceProfile]
     * 5. Translates clean dialogue -> selected TargetLanguage via Google Translate API + Offline fallback
     * 6. Checks Anti-Repeat [lastSpokenText] and speaks via the assigned character's [VoiceProfile].
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
            return@withContext "" to ""
        }

        handleExtractedSubtitle(extractionResult.extractedText, extractedEnglish, startTime, onDebugTextUpdate)
    }

    private suspend fun handleExtractedSubtitle(
        rawScanText: String,
        extractedEnglish: String,
        startTime: Long,
        onDebugTextUpdate: ((english: String, hindi: String, statusNote: String) -> Unit)?
    ): Pair<String, String> {
        val targetLang = TranslatorStateRepository.state.value.targetLanguage

        // Analyze scan result via SpeakerHeuristicEngine to identify character name tags (e.g., "Character A:")
        // and map them to the corresponding VoiceProfile selected in the Voice Manager
        val parsedDialogue = VoiceProfileRepository.resolveSpeakerAndVoiceForSubtitle(rawScanText)
        val assignedVoice = parsedDialogue.assignedVoice
        val dialogueToTranslate = parsedDialogue.spokenDialogue.ifBlank { extractedEnglish }

        val translatedText = translateEnglishToHindi(dialogueToTranslate)
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

        val spokenSuccess = speakHindiNonBlocking(
            hindiText = translatedText,
            forceSpeakEvenIfRepeat = false,
            voiceProfile = assignedVoice
        )
        val speakerTag = parsedDialogue.speakerName ?: assignedVoice.assignedRole.displayLabel.substringBefore(" (")
        val skipNote = if (spokenSuccess) {
            "$speakerTag → ${assignedVoice.name}"
        } else {
            "TTS Queued / Downloading Voice Async"
        }

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
                if (spokenSuccess) "Speaking ${targetLang.displayName} ($speakerTag → ${assignedVoice.name})" else skipNote
            )
        }

        return extractedEnglish to translatedText
    }

    suspend fun translateEnglishToHindi(englishText: String): String = withContext(Dispatchers.IO) {
        val targetLang = TranslatorStateRepository.state.value.targetLanguage
        HindiTranslationEngine.translate(englishText, targetLang)
    }

    /**
     * Speaks [hindiText] using [ForegroundTtsManager] with the specified character's [voiceProfile]
     * (or the default selected voice if null).
     */
    fun speakHindiNonBlocking(
        hindiText: String,
        forceSpeakEvenIfRepeat: Boolean = false,
        voiceProfile: VoiceProfile? = null
    ): Boolean {
        if (hindiText.isBlank()) return false
        if (forceSpeakEvenIfRepeat) {
            lastSpokenText = hindiText.trim()
        }
        val activeProfile = voiceProfile ?: VoiceProfileRepository.voices.value.find {
            it.id == VoiceProfileRepository.selectedVoiceId.value
        }
        return ttsManager.speakNonBlocking(hindiText, voiceProfile = activeProfile)
    }

    /**
     * Plays a preview of [voiceProfile] in the active target language so the user can hear
     * its cloned pitch, speech rate, and timbre from the Voice List UI.
     */
    fun previewVoiceProfile(voiceProfile: VoiceProfile) {
        val lang = TranslatorStateRepository.state.value.targetLanguage
        val previewLine = when (lang.code) {
            "hi" -> "नमस्ते! यह ${voiceProfile.name} की आवाज़ है। गेम के डायलॉग इसी आवाज़ में बोले जाएंगे।"
            else -> lang.sampleGreeting
        }
        ttsManager.speakNonBlocking(previewLine, voiceProfile = voiceProfile)
    }

    fun resetAntiRepeatMemory() {
        lastSpokenText = ""
        lastExtractedNormalized = ""
        TranslatorStateRepository.clearLastSpokenMemory()
    }

    /**
     * Triggers an asynchronous language data download and warm-up via [ForegroundTtsManager].
     */
    fun promptInstallHindiTtsData(context: Context, openSystemInstaller: Boolean = true) {
        ttsManager.requestAsyncLanguageDataDownload(
            context = context,
            openSystemInstaller = openSystemInstaller
        )
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
