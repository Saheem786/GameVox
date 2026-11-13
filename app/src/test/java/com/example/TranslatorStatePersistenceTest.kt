package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TranslationMode
import com.example.data.TranslatorStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslatorStatePersistenceTest {

    @Test
    fun `persists translation mode ai consent volume and custom glossary across reloads`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        TranslatorStateRepository.updateTranslationMode(
            context = context,
            mode = TranslationMode.CONTEXT_AWARE_AI,
            aiPrivacyConsentAccepted = true
        )
        TranslatorStateRepository.updateTtsVolume(context, 0.72f)
        assertTrue(TranslatorStateRepository.upsertGlossaryEntry(context, "Kael", "केल"))
        TranslatorStateRepository.removeGlossaryEntry(context, "GG")

        // Reset in-memory values before reloading from SharedPreferences
        TranslatorStateRepository.updateTranslationMode(
            context = context,
            mode = TranslationMode.CONTEXT_AWARE_AI,
            aiPrivacyConsentAccepted = true
        )
        TranslatorStateRepository.loadPreferences(context)

        val loaded = TranslatorStateRepository.state.value
        assertEquals(TranslationMode.CONTEXT_AWARE_AI, loaded.translationMode)
        assertTrue(loaded.aiPrivacyConsentAccepted)
        assertEquals(0.72f, loaded.ttsVolume, 0.01f)
        assertEquals("केल", loaded.pronunciationGlossary["Kael"])
        assertFalse(loaded.pronunciationGlossary.containsKey("GG"))
    }
}
