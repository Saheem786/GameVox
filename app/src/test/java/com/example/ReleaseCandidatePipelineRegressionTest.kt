package com.example

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.example.data.ModelDownloadStatus
import com.example.data.TargetLanguage
import com.example.data.TranslationMode
import com.example.data.TranslatorStateRepository
import com.example.engine.EmotionSpeechEngine
import com.example.engine.HindiTranslationEngine
import com.example.engine.OcrTranslationPipeline
import com.example.service.FloatingOverlayController
import com.example.service.ForegroundTtsManager
import com.example.service.ScreenTranslatorService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReleaseCandidatePipelineRegressionTest {

    private lateinit var context: Context
    private lateinit var pipeline: OcrTranslationPipeline

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppContextHolder.set(context)
        TranslatorStateRepository.loadPreferences(context)
        TranslatorStateRepository.clearHistory()
        TranslatorStateRepository.updateTranslationMode(
            context = context,
            mode = TranslationMode.ON_DEVICE_MLKIT,
            aiPrivacyConsentAccepted = false
        )
        TranslatorStateRepository.setVoiceMuted(context, false)
        TranslatorStateRepository.setCapturingOcr(false)
        HindiTranslationEngine.mlKitTranslatorOverride = null
        HindiTranslationEngine.clearCacheForTesting()
        pipeline = OcrTranslationPipeline.getInstance(context)
        pipeline.resetAntiRepeatMemory()
    }

    @After
    fun tearDown() {
        HindiTranslationEngine.mlKitTranslatorOverride = null
        HindiTranslationEngine.clearCacheForTesting()
        pipeline.resetAntiRepeatMemory()
        TranslatorStateRepository.setCapturingOcr(false)
    }

    @Test
    fun `1_repeated_dialogue_is_suppressed_and_counted`() = runBlocking {
        TranslatorStateRepository.setCapturingOcr(true)
        // Mute audio playback side effect only? No: keep unmuted or check duplicate tracking via second identical call
        val first = pipeline.processExtractedRawText(
            rawEnglishText = "Jenny: Are you kidding me?",
            enforceActiveSession = true
        )
        assertEquals("Are you kidding me?", first.first)
        assertEquals("मज़ाक कर रहे हो क्या?", first.second)

        val beforeDuplicates = TranslatorStateRepository.state.value.duplicateSkippedCount

        // Process exact same subtitle immediately again
        val second = pipeline.processExtractedRawText(
            rawEnglishText = "Jenny: Are you kidding me?",
            enforceActiveSession = true
        )
        assertEquals("Are you kidding me?", second.first)
        assertTrue(
            "Repeating same dialogue must increment duplicateSkippedCount",
            TranslatorStateRepository.state.value.duplicateSkippedCount > beforeDuplicates
        )
    }

    @Test
    fun `2_rapidly_changing_subtitles_are_translated_into_modern_conversational_hindi`() = runBlocking {
        val lines = listOf(
            "Let's get moving!",
            "I won't let you down.",
            "Watch your back!",
            "Hold your position! Reinforcements arriving in thirty seconds."
        )
        TranslatorStateRepository.setCapturingOcr(true)

        val outputs = lines.map { line ->
            pipeline.processExtractedRawText(rawEnglishText = line, enforceActiveSession = true)
        }

        assertEquals("चलो, जल्दी निकलते हैं!", outputs[0].second)
        assertEquals("भरोसा रखो, तुम्हें निराश नहीं करूँगा।", outputs[1].second)
        assertEquals("पीछे ध्यान रखना!", outputs[2].second)
        assertEquals("अपनी पोज़िशन होल्ड करो! 30 सेकंड में बैकअप आ रहा है।", outputs[3].second)

        // Ensure zero stiff textbook Hindi words appear anywhere
        outputs.forEach { (_, hindi) ->
            assertFalse(hindi.contains("प्रतीक्षा"))
            assertFalse(hindi.contains("सहायता"))
            assertFalse(hindi.contains("अंतिम संस्कार"))
        }
    }

    @Test
    fun `3_empty_ocr_and_hud_clutter_produce_no_translation_or_speech`() = runBlocking {
        TranslatorStateRepository.setCapturingOcr(true)
        val initialHistorySize = TranslatorStateRepository.state.value.history.size

        val emptyResult = pipeline.processExtractedRawText("   ", enforceActiveSession = true)
        val hudClutterResult = pipeline.processExtractedRawText("HP 95/100\nFPS 60\nLEVEL 12", enforceActiveSession = true)

        assertEquals("" to "", emptyResult)
        assertEquals("" to "", hudClutterResult)
        assertEquals(initialHistorySize, TranslatorStateRepository.state.value.history.size)
    }

    @Test
    fun `4_offline_mlkit_installed_and_error_fallback_make_zero_network_requests_and_report_honest_status`() = runBlocking {
        // Case A: Offline ML Kit model IS installed — produces textbook Hindi, post-processed cleanly with zero network
        HindiTranslationEngine.mlKitTranslatorOverride = { _, _ ->
            "कृपया प्रतीक्षा करें, सहायता आ रही है।"
        }
        val mlKitResult = HindiTranslationEngine.translateSuspendDetailed(
            englishText = "Please wait in the eastern courtyard, backup arrives soon.",
            targetLanguage = TargetLanguage.HINDI,
            mode = TranslationMode.ON_DEVICE_MLKIT
        )
        assertFalse(HindiTranslationEngine.didAttemptNetworkTranslationOnLastCall)
        assertEquals("Offline ML Kit", mlKitResult.sourceLabel)
        assertFalse(mlKitResult.isOfflineFallback)
        assertTrue(mlKitResult.translatedText.contains("प्लीज़"))
        assertTrue(mlKitResult.translatedText.contains("वेट करो"))
        assertTrue(mlKitResult.translatedText.contains("मदद आ रही है"))
        assertEquals(ModelDownloadStatus.READY, TranslatorStateRepository.state.value.modelStatus)

        // Case B: Offline ML Kit model throws an error — MUST fall back to limited local phrasebook, zero network!
        HindiTranslationEngine.clearCacheForTesting()
        HindiTranslationEngine.mlKitTranslatorOverride = { _, _ ->
            throw IllegalStateException("Offline model file corrupted or missing")
        }
        val fallbackResult = HindiTranslationEngine.translateSuspendDetailed(
            englishText = "Fortify the western citadel gate before sunset.",
            targetLanguage = TargetLanguage.HINDI,
            mode = TranslationMode.ON_DEVICE_MLKIT
        )
        assertFalse(
            "ML Kit translation error must NEVER trigger network lookup in offline mode",
            HindiTranslationEngine.didAttemptNetworkTranslationOnLastCall
        )
        assertEquals("Offline Phrasebook (Limited)", fallbackResult.sourceLabel)
        assertTrue(fallbackResult.isOfflineFallback)
        assertEquals(ModelDownloadStatus.OFFLINE_FALLBACK, TranslatorStateRepository.state.value.modelStatus)
        assertTrue(
            TranslatorStateRepository.state.value.modelStatus.label.contains(
                "Limited Local Phrasebook",
                ignoreCase = true
            )
        )
    }

    @Test
    fun `5_tts_error_callback_maps_error_code_updates_telemetry_and_recovers`() {
        val ttsManager = ForegroundTtsManager.getInstance(context)
        assertEquals("ERROR_SYNTHESIS", ttsManager.mapTtsErrorCode(TextToSpeech.ERROR_SYNTHESIS))
        assertEquals("ERROR_NETWORK", ttsManager.mapTtsErrorCode(TextToSpeech.ERROR_NETWORK))
        assertEquals("ERROR_NOT_INSTALLED_YET", ttsManager.mapTtsErrorCode(TextToSpeech.ERROR_NOT_INSTALLED_YET))

        ttsManager.handleUtteranceFailure("utt_err_42", TextToSpeech.ERROR_SYNTHESIS)

        val state = TranslatorStateRepository.state.value
        assertEquals("onError(ERROR_SYNTHESIS)", state.lastTtsEvent)
        assertEquals("utt_err_42", state.lastTtsUtteranceId)
        assertEquals("ERROR_SYNTHESIS", state.lastTtsError)
        assertFalse(ttsManager.isSpeakingNow())

        // Also ensure built-in Android TTS strips raw SSML XML tags so device never speaks markup aloud
        val emotionAnalysis = EmotionSpeechEngine.analyzeEmotionAndBuildSsml(
            spokenText = "चलो, जल्दी निकलते हैं!",
            sourceEnglishText = "Let's get moving!"
        )
        assertFalse(emotionAnalysis.cleanSpokenText.contains("<speak>"))
        assertFalse(emotionAnalysis.cleanSpokenText.contains("<prosody"))
        assertEquals("चलो, जल्दी निकलते हैं!", emotionAnalysis.cleanSpokenText)
    }

    @Test
    fun `6_pause_and_stop_cancellation_prevents_stale_subtitle_translation_or_speech`() = runBlocking {
        TranslatorStateRepository.setCapturingOcr(true)
        val epochBefore = pipeline.currentEpoch()

        // Simulate user tapping Pause / Stop
        TranslatorStateRepository.setCapturingOcr(false)
        pipeline.stopAndClearQueue()

        assertTrue(pipeline.currentEpoch() > epochBefore)
        assertEquals(0, TranslatorStateRepository.state.value.queuedSubtitlesCount)
        assertEquals("STOPPED", TranslatorStateRepository.state.value.lastTtsEvent)

        // Late frame arriving after pause/stop must abort before translation & speech
        val lateFrameText = pipeline.processExtractedRawText(
            rawEnglishText = "Don't mess with me!",
            enforceActiveSession = true
        )
        assertEquals("" to "", lateFrameText)
    }

    @Test
    fun `7_service_rotation_and_destroy_clean_up_resources_without_crash`() {
        val controller = Robolectric.buildService(ScreenTranslatorService::class.java).create()
        val service = controller.get()

        // Toggling caption visibility in Settings sends ACTION_UPDATE_SUBTITLE_VISIBILITY (never toggles OCR scanning)
        TranslatorStateRepository.setCapturingOcr(false)
        val captionIntent = Intent(context, ScreenTranslatorService::class.java).apply {
            action = ScreenTranslatorService.ACTION_UPDATE_SUBTITLE_VISIBILITY
        }
        service.onStartCommand(captionIntent, 0, 1)
        assertFalse(
            "Updating caption overlay visibility must not alter scanning active state",
            TranslatorStateRepository.state.value.isCapturingOcr
        )

        // Simulate device rotation to landscape (e.g. launching/playing a landscape game)
        val landscapeConfig = Configuration(context.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        service.onConfigurationChanged(landscapeConfig)

        // Ensure FloatingOverlayController survives orientation change & destroyOverlay cleanly
        val overlay = FloatingOverlayController(context)
        overlay.onScreenOrientationChanged()
        overlay.destroyOverlay()

        controller.destroy()
        val postDestroyState = TranslatorStateRepository.state.value
        assertFalse(postDestroyState.isServiceRunning)
        assertFalse(postDestroyState.hasActiveMediaProjection)
        assertFalse(postDestroyState.isCapturingOcr)
    }
}
