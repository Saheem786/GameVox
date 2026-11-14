package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ModelDownloadStatus
import com.example.data.TargetLanguage
import com.example.data.TranslationMode
import com.example.data.TranslatorStateRepository
import com.example.engine.HindiTranslationEngine
import com.example.engine.OcrTranslationPipeline
import com.example.engine.PronunciationGlossary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class TranslationAndGlossaryTest {

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
        HindiTranslationEngine.mlKitTranslatorOverride = null
        HindiTranslationEngine.mlKitPreDownloadOverride = null
        HindiTranslationEngine.clearCacheForTesting()
    }

    @After
    fun tearDown() {
        HindiTranslationEngine.mlKitTranslatorOverride = null
        HindiTranslationEngine.mlKitPreDownloadOverride = null
        HindiTranslationEngine.clearCacheForTesting()
        TranslatorStateRepository.setCapturingOcr(false)
    }

    @Test
    fun `translates everyday game dialogue into natural modern Hindi`() {
        assertEquals(
            "मज़ाक कर रहे हो क्या?",
            HindiTranslationEngine.translate("Are you kidding me?", TargetLanguage.HINDI)
        )
        assertEquals(
            "चलो, जल्दी निकलते हैं!",
            HindiTranslationEngine.translate("Let's get moving!", TargetLanguage.HINDI)
        )
        assertEquals(
            "भरोसा रखो, तुम्हें निराश नहीं करूँगा।",
            HindiTranslationEngine.translate("I won't let you down.", TargetLanguage.HINDI)
        )
    }

    @Test
    fun `strips speaker prefix before translating and normalizes formal Hindi`() {
        val casual = HindiTranslationEngine.convertFormalHindiToModernCasual(
            hindiText = "क्या आप मेरे साथ मज़ाक कर रहे हैं? कृपया प्रतीक्षा करें।",
            originalEnglishLower = "are you kidding me? please wait."
        )
        assertTrue(casual.contains("मज़ाक कर रहे हो क्या"))
        assertTrue(casual.contains("प्लीज़"))
        assertTrue(casual.contains("वेट करो"))
    }

    @Test
    fun `glossary applies whole-word pronunciation replacements only for TTS without substring corruption`() {
        val glossary = linkedMapOf(
            "Anon" to "एनॉन",
            "HP" to "हेल्थ"
        )
        val visualCaption = "Anon, check your HP right now!"
        val spokenText = PronunciationGlossary.applyForSpeech(visualCaption, glossary)

        assertEquals("एनॉन, check your हेल्थ right now!", spokenText)
        assertFalse(PronunciationGlossary.applyForSpeech("Anonymous player", glossary).contains("एनॉन"))
    }

    @Test
    fun `glossary serializes and parses cleanly via JSON`() {
        val original = linkedMapOf("Valor" to "वैलर", "Kael" to "केल")
        val json = PronunciationGlossary.toJsonString(original)
        val parsed = PronunciationGlossary.fromJsonString(json)

        assertEquals(original, parsed)
    }

    @Test
    fun `preDownloadHindiOfflineModel deduplicates concurrent requests and marks offline model ready`() = runBlocking {
        var downloadInvocations = 0
        HindiTranslationEngine.mlKitPreDownloadOverride = { lang, downloadIfMissing ->
            assertEquals(TargetLanguage.HINDI, lang)
            assertTrue(downloadIfMissing)
            downloadInvocations++
            delay(40L)
            true
        }

        val results = listOf(
            async { HindiTranslationEngine.preDownloadHindiOfflineModel() },
            async { HindiTranslationEngine.preDownloadHindiOfflineModel() },
            async { HindiTranslationEngine.ensureOfflineModelDownloaded(TargetLanguage.HINDI, downloadIfMissing = true) }
        ).awaitAll()

        assertTrue(results.all { it })
        assertEquals("Concurrent pre-download calls must coalesce cleanly", 1, downloadInvocations)
        assertTrue(HindiTranslationEngine.isOfflineModelReady(TargetLanguage.HINDI))
        assertEquals(ModelDownloadStatus.READY, TranslatorStateRepository.state.value.modelStatus)
        assertTrue(
            TranslatorStateRepository.state.value.mlKitModelStatusText.contains("ready", ignoreCase = true)
        )
    }

    @Test
    fun `ocr extracted english dialogue translates via offline ML Kit into natural Hindi`() = runBlocking {
        HindiTranslationEngine.mlKitPreDownloadOverride = { _, _ -> true }
        assertTrue(HindiTranslationEngine.preDownloadHindiOfflineModel())

        HindiTranslationEngine.mlKitTranslatorOverride = { englishText, lang ->
            assertEquals(TargetLanguage.HINDI, lang)
            // Simulate raw ML Kit Translation textbook Hindi for OCR text
            if (englishText.contains("eastern bridge", ignoreCase = true)) {
                "कृपया प्रतीक्षा करें, मेरे पिता के मित्र पूर्वी पुल पर हैं।"
            } else {
                null
            }
        }

        val pipeline = OcrTranslationPipeline.getInstance(context)
        pipeline.resetAntiRepeatMemory()
        TranslatorStateRepository.setCapturingOcr(true)

        val (extractedEng, translatedHindi) = pipeline.processExtractedRawText(
            rawEnglishText = "Jenny: Please hold near the eastern bridge!",
            enforceActiveSession = true
        )

        assertEquals("Please hold near the eastern bridge!", extractedEng)
        assertFalse(HindiTranslationEngine.didAttemptNetworkTranslationOnLastCall)
        assertEquals("Offline ML Kit", TranslatorStateRepository.state.value.translationSourceLabel)
        assertTrue(translatedHindi.contains("प्लीज़"))
        assertTrue(translatedHindi.contains("वेट करो"))
        assertTrue(translatedHindi.contains("पापा"))
        assertTrue(translatedHindi.contains("दोस्त"))
        assertFalse(translatedHindi.contains("प्रतीक्षा"))
        assertFalse(translatedHindi.contains("पिता"))
    }
}

