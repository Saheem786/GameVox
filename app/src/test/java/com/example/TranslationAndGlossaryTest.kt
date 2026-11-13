package com.example

import com.example.data.TargetLanguage
import com.example.engine.HindiTranslationEngine
import com.example.engine.PronunciationGlossary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationAndGlossaryTest {

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
}
