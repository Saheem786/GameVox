package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.service.ForegroundTtsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide Singleton coordinating:
 * 1. [ImageProcessor] Target Subtitle Region Cropping + Frame-Diff Optimization + Grayscale/Inversion + ML Kit OCR
 * 2. [SubtitleTextFilter] to ignore HUD clutter, health bars, timers, and unrelated game UI text
 * 3. Multi-Speaker Character Detection ([VoiceProfileRepository.resolveSpeakerAndVoiceForSubtitle])
 * 4. Dual-Mode Translation via [HindiTranslationEngine.translateSuspend] (Free On-Device ML Kit OR Context-Aware AI)
 * 5. [SubtitleDubbingQueue] (short bounded queue with intelligent skipping when subtitles change rapidly)
 * 6. [PronunciationGlossary] applied right before Hindi TTS playback via [ForegroundTtsManager].
 */
class OcrTranslationPipeline private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scanMutex = Mutex()
    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dubbingQueue = SubtitleDubbingQueue(maxCapacity = 2)

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
        ForegroundTtsManager.getInstance(appContext).also { manager ->
            manager.onUtteranceCompletedImmediately = {
                drainNextQueuedSubtitleIfAny()
            }
            manager.onThirtySecondPostSpeechDelayCompleted = {
                // Only clear anti-repeat memory if the queue is empty and same line stayed idle for 30s
                if (dubbingQueue.size() == 0) {
                    lastSpokenText = ""
                    lastExtractedNormalized = ""
                }
            }
        }
    }

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

    fun isTtsSpeakingNow(): Boolean = ttsManager.isSpeakingNow()

    fun initializeTtsAsync() {
        ttsManager.registerTtsDataBroadcastReceiver()
        ttsManager.initializeAsync()
    }

    fun refreshTtsConfiguration() {
        ttsManager.refreshVoiceConfiguration()
    }

    fun ensureTranslationModelDownloaded(downloadIfMissing: Boolean = true) {
        pipelineScope.launch(Dispatchers.IO) {
            val targetLang = TranslatorStateRepository.state.value.targetLanguage
            HindiTranslationEngine.ensureOfflineModelDownloaded(
                targetLanguage = targetLang,
                downloadIfMissing = downloadIfMissing
            )
        }
    }

    suspend fun processExtractedRawText(
        rawEnglishText: String,
        onDebugTextUpdate: ((english: String, hindi: String, statusNote: String) -> Unit)? = null
    ): Pair<String, String> = withContext(Dispatchers.Default) {
        scanMutex.withLock {
            val startTime = System.currentTimeMillis()
            val filtered = SubtitleTextFilter.filterDialogueText(rawEnglishText)
            val extractedEnglish = cleanExtractedSubtitle(filtered)
            if (extractedEnglish.isBlank()) {
                return@withLock "" to ""
            }
            handleExtractedSubtitle(filtered, extractedEnglish, startTime, onDebugTextUpdate)
        }
    }

    suspend fun processCapturedFrame(
        rawBitmap: Bitmap,
        excludeOverlayRect: Rect? = null,
        targetRegionScreenRect: Rect? = null,
        rawScreenWidth: Int = 0,
        rawScreenHeight: Int = 0,
        skipIfRegionUnchanged: Boolean = true,
        onDebugTextUpdate: ((english: String, hindi: String, statusNote: String) -> Unit)? = null
    ): Pair<String, String> = withContext(Dispatchers.Default) {
        scanMutex.withLock {
            val startTime = System.currentTimeMillis()
            val appState = TranslatorStateRepository.state.value

            val extractionResult = imageProcessor.extractTextFromOverlayBitmap(
                source = rawBitmap,
                enableColorInversion = appState.invertColorsEnabled,
                excludeOverlayRect = excludeOverlayRect,
                targetRegionScreenRect = targetRegionScreenRect,
                rawScreenWidth = rawScreenWidth,
                rawScreenHeight = rawScreenHeight,
                skipIfRegionUnchanged = skipIfRegionUnchanged
            )

            if (extractionResult.skippedUnchangedFrame) {
                return@withLock "" to ""
            }

            val origThumb = BitmapInverter.createPreviewThumbnail(extractionResult.croppedOriginalBitmap)
            val invThumb = BitmapInverter.createPreviewThumbnail(extractionResult.processedBitmap)
            TranslatorStateRepository.updateFramePreviews(origThumb, invThumb)

            val extractedEnglish = cleanExtractedSubtitle(extractionResult.extractedText)
            if (extractedEnglish.isBlank()) {
                return@withLock "" to ""
            }

            handleExtractedSubtitle(extractionResult.extractedText, extractedEnglish, startTime, onDebugTextUpdate)
        }
    }

    private suspend fun handleExtractedSubtitle(
        rawScanText: String,
        extractedEnglish: String,
        startTime: Long,
        onDebugTextUpdate: ((english: String, hindi: String, statusNote: String) -> Unit)?
    ): Pair<String, String> {
        val state = TranslatorStateRepository.state.value
        val targetLang = state.targetLanguage

        val parsedDialogue = VoiceProfileRepository.resolveSpeakerAndVoiceForSubtitle(rawScanText)
        val assignedVoice = parsedDialogue.assignedVoice
        val dialogueToTranslate = parsedDialogue.spokenDialogue.trim()
        if (dialogueToTranslate.isBlank()) {
            return "" to ""
        }

        // Skip re-translating if extracted dialogue matches the active or most recently queued line
        val candidateNorm = SubtitleDubbingQueue.normalize(dialogueToTranslate)
        if (SubtitleDubbingQueue.areSimilar(candidateNorm, lastExtractedNormalized) && !isTtsSpeakingNow()) {
            TranslatorStateRepository.recordTranslationResult(
                english = dialogueToTranslate,
                hindi = lastSpokenText.ifBlank { state.lastTranslatedHindi },
                wasSpoken = false,
                skippedReason = "Anti-Repeat: Identical to lastSpokenText",
                processingTimeMs = System.currentTimeMillis() - startTime,
                timestamp = timeFormatter.format(Date())
            )
            return dialogueToTranslate to lastSpokenText
        }

        val translatedText = translateEnglishToHindi(
            englishText = dialogueToTranslate,
            speakerName = parsedDialogue.speakerName
        )
        if (translatedText.isBlank()) {
            return "" to ""
        }
        val elapsedMs = System.currentTimeMillis() - startTime
        val timestamp = timeFormatter.format(Date())

        val speakerTag = parsedDialogue.speakerName ?: assignedVoice.assignedRole.displayLabel.substringBefore(" (")
        val queueItem = PendingDubbingItem(
            sourceEnglish = dialogueToTranslate,
            spokenDialogue = dialogueToTranslate,
            translatedText = translatedText,
            voiceProfile = assignedVoice,
            speakerLabel = speakerTag
        )

        val outcome = dubbingQueue.enqueue(
            item = queueItem,
            isCurrentlySpeaking = isTtsSpeakingNow(),
            activeSpokenEnglishNormalized = lastExtractedNormalized,
            activeSpokenTranslated = lastSpokenText
        )

        val isVoiceMuted = TranslatorStateRepository.state.value.isVoiceMuted
        when (outcome) {
            is DubbingEnqueueOutcome.SkippedDuplicate -> {
                TranslatorStateRepository.recordTranslationResult(
                    english = dialogueToTranslate,
                    hindi = translatedText,
                    wasSpoken = false,
                    skippedReason = "Anti-Repeat: Identical to lastSpokenText",
                    processingTimeMs = elapsedMs,
                    timestamp = timestamp
                )
                withContext(Dispatchers.Main) {
                    onDebugTextUpdate?.invoke(dialogueToTranslate, translatedText, "Duplicate skipped")
                }
            }

            is DubbingEnqueueOutcome.Queued -> {
                TranslatorStateRepository.updateQueueTelemetry(queuedCount = outcome.queueSize)
                TranslatorStateRepository.recordTranslationResult(
                    english = dialogueToTranslate,
                    hindi = translatedText,
                    wasSpoken = false,
                    skippedReason = "Queued next (${outcome.queueSize})",
                    processingTimeMs = elapsedMs,
                    timestamp = timestamp
                )
                withContext(Dispatchers.Main) {
                    onDebugTextUpdate?.invoke(dialogueToTranslate, translatedText, "Queued (${outcome.queueSize})")
                }
            }

            is DubbingEnqueueOutcome.QueuedAndSkippedStale -> {
                TranslatorStateRepository.updateQueueTelemetry(
                    queuedCount = outcome.queueSize,
                    skippedRapidIncrement = 1
                )
                TranslatorStateRepository.recordTranslationResult(
                    english = dialogueToTranslate,
                    hindi = translatedText,
                    wasSpoken = false,
                    skippedReason = "Skipped stale line; queued latest (${outcome.queueSize})",
                    processingTimeMs = elapsedMs,
                    timestamp = timestamp
                )
                withContext(Dispatchers.Main) {
                    onDebugTextUpdate?.invoke(
                        dialogueToTranslate,
                        translatedText,
                        "Skipped old rapid line • Queued (${outcome.queueSize})"
                    )
                }
            }

            is DubbingEnqueueOutcome.SpeakImmediately -> {
                TranslatorStateRepository.updateQueueTelemetry(queuedCount = 0)
                lastSpokenText = translatedText.trim()
                lastExtractedNormalized = SubtitleDubbingQueue.normalize(dialogueToTranslate)

                val spokenSuccess = if (isVoiceMuted) {
                    false
                } else {
                    withContext(Dispatchers.Main) {
                        speakHindiNonBlocking(
                            hindiText = translatedText,
                            forceSpeakEvenIfRepeat = false,
                            voiceProfile = assignedVoice,
                            sourceEnglishText = dialogueToTranslate
                        )
                    }
                }
                val genderBadge = parsedDialogue.detectedGender.displayLabel
                val note = when {
                    isVoiceMuted -> "Voice Muted • $speakerTag ($genderBadge)"
                    spokenSuccess -> "Speaking ${targetLang.displayName} ($speakerTag)"
                    else -> "TTS Initializing"
                }

                TranslatorStateRepository.recordTranslationResult(
                    english = dialogueToTranslate,
                    hindi = translatedText,
                    wasSpoken = spokenSuccess,
                    skippedReason = note,
                    processingTimeMs = elapsedMs,
                    timestamp = timestamp
                )

                withContext(Dispatchers.Main) {
                    onDebugTextUpdate?.invoke(dialogueToTranslate, translatedText, note)
                }
            }
        }

        return dialogueToTranslate to translatedText
    }

    private fun drainNextQueuedSubtitleIfAny() {
        pipelineScope.launch(Dispatchers.Main) {
            val next = dubbingQueue.pollNext() ?: run {
                TranslatorStateRepository.updateQueueTelemetry(queuedCount = 0)
                return@launch
            }
            TranslatorStateRepository.updateQueueTelemetry(queuedCount = dubbingQueue.size())
            if (TranslatorStateRepository.state.value.isVoiceMuted) return@launch

            lastSpokenText = next.translatedText.trim()
            lastExtractedNormalized = SubtitleDubbingQueue.normalize(next.spokenDialogue)

            val spokenSuccess = speakHindiNonBlocking(
                hindiText = next.translatedText,
                forceSpeakEvenIfRepeat = true,
                voiceProfile = next.voiceProfile,
                sourceEnglishText = next.sourceEnglish
            )

            TranslatorStateRepository.recordTranslationResult(
                english = next.spokenDialogue,
                hindi = next.translatedText,
                wasSpoken = spokenSuccess,
                skippedReason = if (spokenSuccess) "Spoken from queue (${next.speakerLabel})" else "Queue TTS failed",
                processingTimeMs = 0L,
                timestamp = timeFormatter.format(Date())
            )
        }
    }

    suspend fun translateEnglishToHindi(
        englishText: String,
        speakerName: String? = null
    ): String = withContext(Dispatchers.IO) {
        val state = TranslatorStateRepository.state.value
        HindiTranslationEngine.translateSuspend(
            englishText = englishText,
            targetLanguage = state.targetLanguage,
            mode = state.translationMode,
            speakerName = speakerName
        )
    }

    /**
     * Applies configured [PronunciationGlossary] rules right before speaking via [ForegroundTtsManager]
     * so English names and Hinglish terms sound natural without altering displayed Hindi captions.
     */
    fun speakHindiNonBlocking(
        hindiText: String,
        forceSpeakEvenIfRepeat: Boolean = false,
        voiceProfile: VoiceProfile? = null,
        sourceEnglishText: String = ""
    ): Boolean {
        if (hindiText.isBlank()) return false
        val state = TranslatorStateRepository.state.value
        if (!forceSpeakEvenIfRepeat && state.isVoiceMuted) {
            return false
        }
        lastSpokenText = hindiText.trim()
        val speechReadyText = if (state.targetLanguage.code == "hi") {
            PronunciationGlossary.applyForSpeech(hindiText, state.pronunciationGlossary)
        } else {
            hindiText
        }
        val activeProfile = voiceProfile ?: VoiceProfileRepository.voices.value.find {
            it.id == VoiceProfileRepository.selectedVoiceId.value
        }
        return ttsManager.speakNonBlocking(
            text = speechReadyText,
            voiceProfile = activeProfile,
            sourceEnglishText = sourceEnglishText
        )
    }

    fun previewVoiceProfile(voiceProfile: VoiceProfile) {
        val lang = TranslatorStateRepository.state.value.targetLanguage
        val previewLine = when (lang.code) {
            "hi" -> "हे गेमर! यह ${voiceProfile.name} की आवाज़ है। गेम के डायलॉग इसी आवाज़ में बोले जाएंगे।"
            else -> lang.sampleGreeting
        }
        ttsManager.speakNonBlocking(previewLine, voiceProfile = voiceProfile)
    }

    fun resetAntiRepeatMemory() {
        dubbingQueue.clear()
        imageProcessor.resetFrameDiffCache()
        HindiTranslationEngine.clearSceneContext()
        ttsManager.stopSpeaking()
        ttsManager.cancelThirtySecondRepeatTimer()
        lastSpokenText = ""
        lastExtractedNormalized = ""
        TranslatorStateRepository.updateQueueTelemetry(queuedCount = 0)
        TranslatorStateRepository.clearLastSpokenMemory()
    }

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
