package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.TargetLanguage
import com.example.data.TranslationMode
import com.example.data.TranslatorStateRepository
import com.example.engine.ContextAwareAiTranslator
import com.example.engine.HindiTranslationEngine
import com.example.engine.ImageProcessor
import com.example.engine.OcrTranslationPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class P0OfflinePrivacyAndPipelineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppContextHolder.set(context)
        TranslatorStateRepository.loadPreferences(context)
        TranslatorStateRepository.updateTranslationMode(
            context = context,
            mode = TranslationMode.ON_DEVICE_MLKIT,
            aiPrivacyConsentAccepted = false
        )
        TranslatorStateRepository.setServiceRunning(running = false, hasActiveProjection = false)
        TranslatorStateRepository.setCapturingOcr(false)
    }

    @Test
    fun `on-device ML Kit mode never attempts network translation on unknown subtitle`() = runBlocking {
        val unknownDialogue = "Mysterious vanguard, fortify the southern bridge immediately!"

        val result = HindiTranslationEngine.translateSuspendDetailed(
            englishText = unknownDialogue,
            targetLanguage = TargetLanguage.HINDI,
            mode = TranslationMode.ON_DEVICE_MLKIT
        )

        assertFalse(
            "ON_DEVICE_MLKIT must NEVER attempt network/Google API translation fallback",
            HindiTranslationEngine.didAttemptNetworkTranslationOnLastCall
        )
        assertTrue(result.translatedText.isNotBlank())
        assertEquals("Offline Phrasebook (Limited)", result.sourceLabel)
        assertTrue(result.isOfflineFallback)
    }

    @Test
    fun `synchronous translate defaults to strictly offline without network lookup`() {
        val output = HindiTranslationEngine.translate(
            englishText = "Unknown citadel patrol reports three dragon scouts.",
            targetLanguage = TargetLanguage.HINDI
        )

        assertFalse(HindiTranslationEngine.didAttemptNetworkTranslationOnLastCall)
        assertTrue(output.isNotBlank())
    }

    @Test
    fun `firebase AI availability check handles uninitialized project safely`() {
        val available = ContextAwareAiTranslator.isFirebaseAiAvailable(context)
        val statusText = TranslatorStateRepository.state.value.firebaseAiStatusText
        assertTrue(statusText.isNotBlank())
        if (!available) {
            assertTrue(
                statusText.contains("Not configured", ignoreCase = true) ||
                    statusText.contains("Unavailable", ignoreCase = true)
            )
        }
    }

    @Test
    fun `image processor upscales tiny subtitle ROI and isolates white or yellow glyphs`() {
        val processor = ImageProcessor()
        val tiny = Bitmap.createBitmap(200, 60, Bitmap.Config.ARGB_8888)
        tiny.eraseColor(Color.DKGRAY)

        val upscaled = processor.upscaleSmallRoiIfNeeded(tiny)
        assertTrue("Tiny 200x60 ROI should upscale for crisp OCR", upscaled.width >= 320)
        assertEquals(120, upscaled.height)

        val scene = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        scene.eraseColor(Color.rgb(30, 110, 45))
        scene.setPixel(4, 5, Color.WHITE)
        scene.setPixel(10, 5, Color.rgb(250, 215, 40))

        val isolated = processor.createBrightYellowWhiteIsolationBitmap(scene)
        assertEquals(Color.WHITE, isolated.getPixel(1, 1))
        assertEquals(Color.BLACK, isolated.getPixel(4, 5))
        assertEquals(Color.BLACK, isolated.getPixel(10, 5))

        if (!tiny.isRecycled) tiny.recycle()
        if (upscaled !== tiny && !upscaled.isRecycled) upscaled.recycle()
        if (!scene.isRecycled) scene.recycle()
        if (!isolated.isRecycled) isolated.recycle()
        processor.close()
    }

    @Test
    fun `pipeline stopAndClearQueue increments epoch and blocks inactive session subtitle`() = runBlocking {
        val pipeline = OcrTranslationPipeline.getInstance(context)
        val initialEpoch = pipeline.currentEpoch()

        pipeline.stopAndClearQueue()

        assertTrue(pipeline.currentEpoch() > initialEpoch)
        assertEquals(0, TranslatorStateRepository.state.value.queuedSubtitlesCount)

        TranslatorStateRepository.setCapturingOcr(false)
        val skippedResult = pipeline.processExtractedRawText(
            rawEnglishText = "Are you kidding me?",
            enforceActiveSession = true
        )
        assertEquals("" to "", skippedResult)
    }

    @Test
    fun `default repository telemetry disables broken on-device SSML and records diagnosis metrics`() {
        val state = TranslatorStateRepository.state.value
        assertFalse(
            "Built-in Android TTS must default SSML OFF to avoid speaking XML tags",
            state.ssmlEnabledOnDevice
        )
        assertEquals(900L, state.captureIntervalMs)

        // Enabling scan box visibility when service is not running must not falsely set isCapturingOcr = true
        TranslatorStateRepository.updateShowTargetBoxOverlay(context, true)
        assertFalse(TranslatorStateRepository.state.value.isCapturingOcr)

        TranslatorStateRepository.updateDiagnosisMetrics(
            captureLatencyMs = 42L,
            ocrLatencyMs = 85L,
            translationLatencyMs = 15L,
            totalPipelineLatencyMs = 142L,
            roiDimensionsText = "1920x240 (Pass 2)",
            translationSourceLabel = "Offline ML Kit",
            lastPipelineError = ""
        )
        TranslatorStateRepository.updateTtsLifecycleEvent(
            event = "DONE",
            utteranceId = "test-1",
            error = ""
        )

        val updated = TranslatorStateRepository.state.value
        assertEquals(42L, updated.captureLatencyMs)
        assertEquals(85L, updated.ocrLatencyMs)
        assertEquals(15L, updated.translationLatencyMs)
        assertEquals(142L, updated.totalPipelineLatencyMs)
        assertEquals("1920x240 (Pass 2)", updated.roiDimensionsText)
        assertEquals("Offline ML Kit", updated.translationSourceLabel)
        assertEquals("DONE", updated.lastTtsEvent)
        assertEquals("test-1", updated.lastTtsUtteranceId)
    }
}
