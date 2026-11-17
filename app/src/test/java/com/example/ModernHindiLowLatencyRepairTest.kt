package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.data.CharacterVoiceAssignment
import com.example.data.DubbingDeliveryMode
import com.example.data.TargetLanguage
import com.example.data.TranslationMode
import com.example.data.TranslationStyle
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceProfileRepository
import com.example.engine.CharacterVoiceRouter
import com.example.engine.DubbingEnqueueOutcome
import com.example.engine.HindiTranslationEngine
import com.example.engine.ImageProcessor
import com.example.engine.OcrTranslationPipeline
import com.example.engine.PendingDubbingItem
import com.example.engine.SubtitleDubbingQueue
import com.example.service.SherpaSupertonicBackend
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModernHindiLowLatencyRepairTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppContextHolder.set(context)
        TranslatorStateRepository.loadPreferences(context)
        VoiceProfileRepository.initialize(context)
        VoiceProfileRepository.setGameNamespace(context, "default_game")
        VoiceProfileRepository.restoreAllCharacterDefaults(context)
        TranslatorStateRepository.updateTargetLanguage(context, TargetLanguage.HINDI)
        TranslatorStateRepository.updateTranslationMode(
            context = context,
            mode = TranslationMode.ON_DEVICE_MLKIT,
            aiPrivacyConsentAccepted = false
        )
        TranslatorStateRepository.updateTranslationStyle(context, TranslationStyle.MODERN_HINDI)
        TranslatorStateRepository.updateDubbingDeliveryMode(context, DubbingDeliveryMode.LIVE_MODE)
        HindiTranslationEngine.mlKitTranslatorOverride = null
        HindiTranslationEngine.mlKitPreDownloadOverride = null
        HindiTranslationEngine.clearCacheForTesting()
        CharacterVoiceRouter.resetContinuationContext()
        CharacterVoiceRouter.clearAuditLogs()
        TranslatorStateRepository.clearAlignmentLogs()
    }

    @After
    fun tearDown() {
        HindiTranslationEngine.mlKitTranslatorOverride = null
        HindiTranslationEngine.mlKitPreDownloadOverride = null
        HindiTranslationEngine.clearCacheForTesting()
        TranslatorStateRepository.setCapturingOcr(false)
    }

    @Test
    fun `modern hindi conversational dialogue avoids formal textbook translations across all styles`() = runBlocking {
        val sampleLines = listOf(
            "Yeah, well... Maybe she likes doing it?",
            "It's the breakfast that's waiting for you downstairs, dummy.",
            "Hey, you're the one who started it!",
            "I got your back."
        )

        val modernOutputs = sampleLines.map { line ->
            HindiTranslationEngine.translateSuspend(
                englishText = line,
                targetLanguage = TargetLanguage.HINDI,
                mode = TranslationMode.ON_DEVICE_MLKIT,
                translationStyle = TranslationStyle.MODERN_HINDI
            )
        }
        val hinglishOutputs = sampleLines.map { line ->
            HindiTranslationEngine.translateSuspend(
                englishText = line,
                targetLanguage = TargetLanguage.HINDI,
                mode = TranslationMode.ON_DEVICE_MLKIT,
                translationStyle = TranslationStyle.CASUAL_HINGLISH
            )
        }
        val formalOutputs = sampleLines.map { line ->
            HindiTranslationEngine.translateSuspend(
                englishText = line,
                targetLanguage = TargetLanguage.HINDI,
                mode = TranslationMode.ON_DEVICE_MLKIT,
                translationStyle = TranslationStyle.FORMAL_HINDI
            )
        }

        assertEquals("हाँ, हो सकता है उसे ऐसा करना पसंद हो।", modernOutputs[0])
        assertEquals("अरे बुद्धू, नीचे नाश्ता तुम्हारा इंतज़ार कर रहा है!", modernOutputs[1])
        assertEquals("अरे, शुरू तो तुमने किया था!", modernOutputs[2])
        assertEquals("टेंशन मत लो, मैं हूँ ना!", modernOutputs[3])

        // Ensure style presets genuinely diverge and remain style-isolated
        assertNotEquals(modernOutputs[1], formalOutputs[1])
        assertNotEquals(modernOutputs[3], hinglishOutputs[3])

        // Ensure stiff formal vocabulary is converted cleanly in MODERN_HINDI
        val convertedFromTextbook = HindiTranslationEngine.applyTranslationStyle(
            hindiText = "कृपया प्रतीक्षा करें, मैं आपके पिता से सहायता माँगूँगा।",
            originalEnglishLower = "please wait, i will ask your dad for help.",
            style = TranslationStyle.MODERN_HINDI
        )
        assertFalse(convertedFromTextbook.contains("प्रतीक्षा"))
        assertFalse(convertedFromTextbook.contains("सहायता"))
        assertTrue(convertedFromTextbook.contains("वेट") || convertedFromTextbook.contains("प्लीज़"))
        assertTrue(convertedFromTextbook.contains("मदद"))
    }

    @Test
    fun `translation cache isolates styles and never blocks offline pipeline waiting 30s on mlkit download`() = runBlocking {
        val preDownloadFlags = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        HindiTranslationEngine.mlKitPreDownloadOverride = { _, downloadIfMissing ->
            preDownloadFlags.add(downloadIfMissing)
            false // Simulate offline pack not downloaded yet
        }

        val startMs = System.currentTimeMillis()
        val result = HindiTranslationEngine.translateSuspendDetailed(
            englishText = "Unseen dynamic combat line from enemy scout",
            targetLanguage = TargetLanguage.HINDI,
            mode = TranslationMode.ON_DEVICE_MLKIT,
            translationStyle = TranslationStyle.MODERN_HINDI,
            speakerName = "Anon"
        )
        val elapsedMs = System.currentTimeMillis() - startMs

        // Live dubbing path must check readiness non-blockingly first (downloadIfMissing = false) without stalling
        assertEquals(false, preDownloadFlags.firstOrNull())
        assertTrue("Translation offline fallback check took too long (${elapsedMs}ms)", elapsedMs < 2000L)
        assertTrue(result.translatedText.isNotBlank())
    }

    @Test
    fun `subtitle sequence and session epoch ordering drops stale overwrites`() {
        TranslatorStateRepository.resetSubtitleSequenceTracking(sessionEpoch = 1L)

        TranslatorStateRepository.recordTranslationResult(
            english = "First line",
            hindi = "पहली लाइन",
            wasSpoken = true,
            skippedReason = "Spoken",
            processingTimeMs = 12L,
            timestamp = "12:00:01",
            sequenceId = 5L,
            eventId = "ep1-seq5",
            sessionEpoch = 1L
        )
        assertEquals("First line", TranslatorStateRepository.state.value.lastExtractedEnglish)
        assertEquals("पहली लाइन", TranslatorStateRepository.state.value.lastTranslatedHindi)
        assertEquals(5L, TranslatorStateRepository.state.value.latestSubtitleSequenceId)

        // Older out-of-order callback (seq 3 < 5 in same epoch) must NOT overwrite visible Hindi caption
        TranslatorStateRepository.recordTranslationResult(
            english = "Stale old line",
            hindi = "पुरानी अटक गई लाइन",
            wasSpoken = false,
            skippedReason = "Stale",
            processingTimeMs = 80L,
            timestamp = "12:00:00",
            sequenceId = 3L,
            eventId = "ep1-seq3",
            sessionEpoch = 1L
        )
        assertEquals("First line", TranslatorStateRepository.state.value.lastExtractedEnglish)
        assertEquals("पहली लाइन", TranslatorStateRepository.state.value.lastTranslatedHindi)
        assertEquals(5L, TranslatorStateRepository.state.value.latestSubtitleSequenceId)

        // Older session epoch (epoch 0 < 1) must be ignored even with higher seq
        TranslatorStateRepository.recordTranslationResult(
            english = "Old session ghost",
            hindi = "पुराने सेशन की लाइन",
            wasSpoken = false,
            skippedReason = "Old epoch",
            processingTimeMs = 90L,
            timestamp = "12:00:02",
            sequenceId = 99L,
            eventId = "ep0-seq99",
            sessionEpoch = 0L
        )
        assertEquals("पहली लाइन", TranslatorStateRepository.state.value.lastTranslatedHindi)

        // Newer seq in same epoch updates cleanly
        TranslatorStateRepository.recordTranslationResult(
            english = "New dialogue",
            hindi = "नई लाइन",
            wasSpoken = true,
            skippedReason = "Spoken",
            processingTimeMs = 9L,
            timestamp = "12:00:03",
            sequenceId = 6L,
            eventId = "ep1-seq6",
            sessionEpoch = 1L
        )
        assertEquals("New dialogue", TranslatorStateRepository.state.value.lastExtractedEnglish)
        assertEquals("नई लाइन", TranslatorStateRepository.state.value.lastTranslatedHindi)
        assertEquals(6L, TranslatorStateRepository.state.value.latestSubtitleSequenceId)
    }

    @Test
    fun `live mode queue preempts obsolete lines while story mode keeps ordered queue`() {
        val queue = SubtitleDubbingQueue(maxCapacity = 2)
        val item1 = PendingDubbingItem(
            sourceEnglish = "[Anon]: Watch out!",
            spokenDialogue = "Watch out!",
            translatedText = "संभल के!",
            voiceProfile = null,
            speakerLabel = "Anon",
            sequenceId = 1L,
            eventId = "ep1-seq1"
        )
        val item2 = PendingDubbingItem(
            sourceEnglish = "[Jenny]: Run now!",
            spokenDialogue = "Run now!",
            translatedText = "अभी भागो!",
            voiceProfile = null,
            speakerLabel = "Jenny",
            sequenceId = 2L,
            eventId = "ep1-seq2"
        )
        val item3 = PendingDubbingItem(
            sourceEnglish = "[Anon]: We made it!",
            spokenDialogue = "We made it!",
            translatedText = "हम बच गए!",
            voiceProfile = null,
            speakerLabel = "Anon",
            sequenceId = 3L,
            eventId = "ep1-seq3"
        )

        // LIVE_MODE: while speaking, item2 queues; when item3 arrives, obsolete item2 is dropped so queue size stays 1
        val out2 = queue.enqueue(
            item = item2,
            isCurrentlySpeaking = true,
            activeSpokenEnglishNormalized = "watch out",
            activeSpokenTranslated = "संभल के",
            activeSpokenSpeakerContextKey = item1.buildSpeakerContextKey(),
            deliveryMode = DubbingDeliveryMode.LIVE_MODE
        )
        assertTrue(out2 is DubbingEnqueueOutcome.Queued)
        assertEquals(1, queue.size())

        val out3 = queue.enqueue(
            item = item3,
            isCurrentlySpeaking = true,
            activeSpokenEnglishNormalized = "watch out",
            activeSpokenTranslated = "संभल के",
            activeSpokenSpeakerContextKey = item1.buildSpeakerContextKey(),
            deliveryMode = DubbingDeliveryMode.LIVE_MODE
        )
        assertTrue(out3 is DubbingEnqueueOutcome.QueuedAndSkippedStale)
        assertEquals(1, queue.size())
        assertEquals("We made it!", queue.pollNext()?.spokenDialogue)

        // Out-of-order lower sequenceId must be rejected if higher sequence is already pending
        queue.enqueue(item3, isCurrentlySpeaking = true, activeSpokenEnglishNormalized = "", activeSpokenTranslated = "", deliveryMode = DubbingDeliveryMode.STORY_MODE)
        val outStaleOrder = queue.enqueue(item2, isCurrentlySpeaking = true, activeSpokenEnglishNormalized = "", activeSpokenTranslated = "", deliveryMode = DubbingDeliveryMode.STORY_MODE)
        assertEquals(DubbingEnqueueOutcome.SkippedDuplicate, outStaleOrder)

        // STORY_MODE preserves FIFO up to capacity
        queue.clear()
        queue.enqueue(item1, isCurrentlySpeaking = true, activeSpokenEnglishNormalized = "", activeSpokenTranslated = "", deliveryMode = DubbingDeliveryMode.STORY_MODE)
        queue.enqueue(item2, isCurrentlySpeaking = true, activeSpokenEnglishNormalized = "", activeSpokenTranslated = "", deliveryMode = DubbingDeliveryMode.STORY_MODE)
        assertEquals(2, queue.size())
        assertEquals("Watch out!", queue.pollNext()?.spokenDialogue)
        assertEquals("Run now!", queue.pollNext()?.spokenDialogue)
    }

    @Test
    fun `overlay exclusion masking prevents self-OCR contamination inside target region`() {
        val processor = ImageProcessor()
        val width = 400
        val height = 200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        // Draw fake overlay text inside top-left rect that overlaps the cropped ROI
        val brightPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        val overlayRect = Rect(10, 10, 160, 80)
        canvas.drawRect(overlayRect, brightPaint)

        // Draw real subtitle text pixels outside the overlay rect
        val subtitleRect = Rect(180, 110, 320, 150)
        canvas.drawRect(subtitleRect, brightPaint)

        val isolated = processor.createBrightYellowWhiteIsolationBitmap(
            source = bitmap,
            excludeOverlayRects = listOf(overlayRect)
        )

        // Excluded overlay area must be solid white background (never dark text glyphs)
        assertEquals(Color.WHITE, isolated.getPixel(50, 40))
        // Non-excluded subtitle pixels must remain preserved as black glyph ink for OCR
        assertEquals(Color.BLACK, isolated.getPixel(220, 130))

        bitmap.recycle()
        isolated.recycle()
    }

    @Test
    fun `pipeline preserves Anon vs Jenny voice routing, speaker anti-repeat, and stage telemetry`() = runBlocking {
        val pipeline = OcrTranslationPipeline.getInstance(context)
        pipeline.resetAntiRepeatMemory()
        TranslatorStateRepository.setCapturingOcr(true)
        TranslatorStateRepository.updateDubbingDeliveryMode(context, DubbingDeliveryMode.LIVE_MODE)

        val tStart = System.currentTimeMillis()
        val (eng1, hi1) = pipeline.processExtractedRawText(
            rawEnglishText = "[Anon]: I got your back.",
            enforceActiveSession = true,
            frameTimestampMs = tStart
        )
        // Identical dialogue spoken immediately by Jenny must NOT be suppressed by Anon's anti-repeat key
        val (eng2, hi2) = pipeline.processExtractedRawText(
            rawEnglishText = "[Jenny]: I got your back.",
            enforceActiveSession = true,
            frameTimestampMs = System.currentTimeMillis()
        )
        val totalPipelineTestMs = System.currentTimeMillis() - tStart

        assertEquals("I got your back.", eng1)
        assertEquals("I got your back.", eng2)
        assertEquals("टेंशन मत लो, मैं हूँ ना!", hi1)
        assertEquals("टेंशन मत लो, मैं हूँ ना!", hi2)

        val logs = TranslatorStateRepository.state.value.alignmentLogs
        assertTrue("Expected at least 2 alignment log entries, got ${logs.size}", logs.size >= 2)
        // Newest is first
        assertTrue("Expected Jenny speaker label, got ${logs[0].speakerLabel}", logs[0].speakerLabel.startsWith("Jenny"))
        assertEquals(1, logs[0].speakerId)
        assertTrue("Expected Anon speaker label, got ${logs[1].speakerLabel}", logs[1].speakerLabel.startsWith("Anon"))
        assertEquals(5, logs[1].speakerId)

        val stage = TranslatorStateRepository.state.value.stageTimestamps
        assertTrue(stage.sequenceId >= 2L)
        assertTrue(stage.subtitleEventId.isNotBlank())
        println(
            "LOW_LATENCY_PIPELINE_TEST_TIMING: twoTurnsTotalMs=$totalPipelineTestMs, " +
                "stageSeq=${stage.sequenceId}, translateMs=${stage.translationMs}"
        )
    }

    @Test
    fun `sherpa backend reports honest generation mode label and reuses short-utterance PCM cache`() = runBlocking {
        var generateInvocations = 0
        val fakeTts = object : OfflineTts(OfflineTtsConfig(), 123456L) {
            override fun sampleRate(): Int = 44100
            override fun numSpeakers(): Int = 10
            override fun generateWithConfig(text: String, config: GenerationConfig): GeneratedAudio {
                val samples = FloatArray(4410) { idx -> (0.2 * sin(idx * 0.05)).toFloat() }
                return GeneratedAudio(samples, 44100)
            }

            override fun generateWithConfigAndCallback(
                text: String,
                config: GenerationConfig,
                callback: (samples: FloatArray) -> Int
            ): GeneratedAudio {
                generateInvocations++
                val chunk1 = FloatArray(2205) { idx -> (0.2 * sin(idx * 0.05)).toFloat() }
                val chunk2 = FloatArray(2205) { idx -> (0.2 * sin((idx + 2205) * 0.05)).toFloat() }
                callback(chunk1)
                callback(chunk2)
                return GeneratedAudio(chunk1 + chunk2, 44100)
            }
        }

        val backend = SherpaSupertonicBackend(context) { fakeTts }
        backend.clearSynthesisCacheForTesting()
        assertTrue(backend.initialize())

        // First synthesis invokes native callback in 2 chunks -> CALLBACK_STREAMED_TO_AUDIOTRACK
        val firstOk = backend.synthesizeAndPlay(
            text = "अरे बुद्धू, नीचे नाश्ता तुम्हारा इंतज़ार कर रहा है!",
            sid = 1,
            speed = 1.0f,
            pitch = 1.0f,
            epoch = 1L,
            onFirstAudioStarted = {},
            onCompleted = {},
            onError = {}
        )
        assertTrue(firstOk)
        assertEquals(1, generateInvocations)
        assertFalse(backend.lastSynthesisCacheHit)
        assertTrue(backend.lastGenerationModeLabel.startsWith("CALLBACK_STREAMED_TO_AUDIOTRACK (2 chunks)"))

        // Exact repeat for same SID hits PCM synthesis cache without re-running generation
        val secondOk = backend.synthesizeAndPlay(
            text = "अरे बुद्धू, नीचे नाश्ता तुम्हारा इंतज़ार कर रहा है!",
            sid = 1,
            speed = 1.0f,
            pitch = 1.0f,
            epoch = 2L,
            onFirstAudioStarted = {},
            onCompleted = {},
            onError = {}
        )
        assertTrue(secondOk)
        assertEquals(1, generateInvocations)
        assertTrue(backend.lastSynthesisCacheHit)
        assertTrue(backend.lastGenerationModeLabel.startsWith("SPEECH_CACHE_HIT"))

        // Same line spoken by Anon (SID 5) must NOT reuse Jenny's SID 1 voice PCM cache!
        val thirdOk = backend.synthesizeAndPlay(
            text = "अरे बुद्धू, नीचे नाश्ता तुम्हारा इंतज़ार कर रहा है!",
            sid = 5,
            speed = 1.0f,
            pitch = 1.0f,
            epoch = 3L,
            onFirstAudioStarted = {},
            onCompleted = {},
            onError = {}
        )
        assertTrue(thirdOk)
        assertEquals(2, generateInvocations)
        assertFalse(backend.lastSynthesisCacheHit)
        backend.release()
    }
}
