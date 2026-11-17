package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.data.CharacterVoiceAssignment
import com.example.data.TranslationStyle
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceCategory
import com.example.data.VoiceProfileRepository
import com.example.engine.CharacterVoiceRouter
import com.example.engine.HindiTranslationEngine
import com.example.engine.OcrTranslationPipeline
import com.example.ui.theme.MyApplicationTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h915dp")
class GameVox20UpgradeRegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("gamevox_multi_voice_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("gamevox_hindi_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("screen_translator_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        CharacterVoiceRouter.clearPerGameOcrAliases()
        TranslatorStateRepository.loadPreferences(context)
        VoiceProfileRepository.initialize(context)
    }

    @Test
    fun `version 2 metadata matches upgrade requirements and preserves applicationId`() {
        assertEquals("com.aistudio.gamevoxhindi.vxkrmp", BuildConfig.APPLICATION_ID)
        assertEquals(2, BuildConfig.VERSION_CODE)
        assertEquals("2.0.0", BuildConfig.VERSION_NAME)
    }

    @Test
    fun `idempotent v1 to v2 migration preserves custom voices, character assignments, and legacy prefs`() {
        // Simulate clean v1 install (clear v2 schema markers created by setup())
        context.getSharedPreferences("gamevox_hindi_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("gamevox_multi_voice_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        // Seed v1 state before initializing v2
        val legacyPrefs = context.getSharedPreferences("screen_translator_prefs", Context.MODE_PRIVATE)
        legacyPrefs.edit()
            .putFloat("tts_speech_rate_v3", 1.18f)
            .putString("translation_style_v1", TranslationStyle.MODERN_HINDI.code)
            .commit()

        val v1VoiceArr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "voice_custom_v1_elena")
                put("name", "Elena Saved V1")
                put("description", "User custom v1 voice")
                put("pitch", 1.14)
                put("speechRate", 1.02)
                put("engineVoiceIndex", 3)
                put("assignedRole", "char_b")
                put("gender", "female_18")
                put("isCustomCloned", true)
                put("voiceCategory", VoiceCategory.IMPORTED.code)
                put("isSystemProtected", false)
            })
        }
        val v1CharAssignments = CharacterVoiceAssignment.serializeList(
            listOf(
                CharacterVoiceAssignment(
                    characterName = "alice",
                    displayName = "Alice",
                    speakerId = 2,
                    voicePresentation = "female",
                    referenceVoiceId = "jenny_female_ref",
                    isUserOverride = true
                )
            )
        )
        val multiPrefs = context.getSharedPreferences("gamevox_multi_voice_prefs", Context.MODE_PRIVATE)
        multiPrefs.edit()
            .putString("saved_voice_profiles_json_v1", v1VoiceArr.toString())
            .putString("selected_primary_voice_id_v1", "voice_custom_v1_elena")
            .putString("character_voice_assignments_json_v2", v1CharAssignments)
            .commit()

        // First migration run
        TranslatorStateRepository.loadPreferences(context)
        VoiceProfileRepository.initialize(context)

        assertEquals(TranslatorStateRepository.CURRENT_APP_SCHEMA_VERSION, TranslatorStateRepository.getSchemaVersion(context))
        assertEquals(VoiceProfileRepository.CURRENT_REPO_SCHEMA_VERSION, VoiceProfileRepository.getRepoSchemaVersion(context))
        assertEquals(1.18f, TranslatorStateRepository.state.value.ttsSpeechRate, 0.01f)
        assertTrue(VoiceProfileRepository.voices.value.any { it.id == "voice_custom_v1_elena" && it.name == "Elena Saved V1" })
        assertEquals("voice_custom_v1_elena", VoiceProfileRepository.selectedVoiceId.value)
        assertEquals(2, VoiceProfileRepository.findExistingAssignment("alice", "default_game")?.speakerId)

        // Second migration run (idempotent)
        TranslatorStateRepository.loadPreferences(context)
        VoiceProfileRepository.initialize(context)
        val countMatching = VoiceProfileRepository.voices.value.count { it.id == "voice_custom_v1_elena" }
        assertEquals(1, countMatching)
    }

    @Test
    fun `hiding system protected default voice hides from main library and restores cleanly`() {
        val protectedId = "preset_f1_priya"
        val res = VoiceProfileRepository.setVoiceHidden(context, protectedId, true)
        assertTrue(res.isSuccess)
        assertTrue(VoiceProfileRepository.voices.value.first { it.id == protectedId }.isHidden)

        val restore = VoiceProfileRepository.setVoiceHidden(context, protectedId, false)
        assertTrue(restore.isSuccess)
        assertFalse(VoiceProfileRepository.voices.value.first { it.id == protectedId }.isHidden)
    }

    @Test
    fun `persisted OCR character typo alias survives repository re-initialization`() {
        val savedOk = VoiceProfileRepository.setOcrCharacterCorrection(
            context = context,
            misreadOcrName = "Al1ce",
            canonicalCharacterName = "Alice",
            gameNamespace = "cyber_rpg"
        )
        assertTrue(savedOk)

        // Clear in-memory map and re-initialize from SharedPreferences
        CharacterVoiceRouter.clearPerGameOcrAliases()
        VoiceProfileRepository.initialize(context)
        VoiceProfileRepository.setGameNamespace(context, "cyber_rpg")

        val route = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "Al1ce: I got your back.",
            translatedHindi = "टेंशन मत लो, मैं हूँ ना!",
            gameNamespace = "cyber_rpg"
        )
        assertEquals("alice", route.normalizedCharacterName)
        assertEquals("cyber_rpg", route.gameNamespace)
    }

    @Test
    fun `modern hindi phrasebook translates all natural gamer expressions cleanly`() {
        assertEquals(
            "टेंशन मत लो, मैं हूँ ना!",
            HindiTranslationEngine.translateOfflinePhrasebook("I got your back.", translationStyle = TranslationStyle.MODERN_HINDI)
        )
        assertEquals(
            "अरे यार, ये क्या कर रहे हो?",
            HindiTranslationEngine.translateOfflinePhrasebook("What the hell are you doing?", translationStyle = TranslationStyle.MODERN_HINDI)
        )
        assertEquals(
            "चलो यार, जल्दी चलते हैं!",
            HindiTranslationEngine.translateOfflinePhrasebook("Come on! Let's go!", translationStyle = TranslationStyle.MODERN_HINDI)
        )
    }

    @Test
    fun `anon jenny alice and unlabeled subtitles route distinctly without fake fallback`() {
        val anonRoute = CharacterVoiceRouter.routeLiveSubtitle(context, "Anon: We move now.", "अभी चलते हैं।")
        val jennyRoute = CharacterVoiceRouter.routeLiveSubtitle(context, "Jenny: Watch out!", "संभल के!")
        val unknownRoute = CharacterVoiceRouter.routeLiveSubtitle(context, "Watch the ridge ahead.", "आगे ध्यान दो।")

        assertEquals("anon", anonRoute.normalizedCharacterName)
        assertEquals(5, anonRoute.selectedSpeakerId)
        assertEquals("jenny", jennyRoute.normalizedCharacterName)
        assertEquals(1, jennyRoute.selectedSpeakerId)
        assertNotEquals(anonRoute.selectedSpeakerId, jennyRoute.selectedSpeakerId)
        assertEquals(CharacterVoiceAssignment.UNKNOWN_SPEAKER_NAME, unknownRoute.normalizedCharacterName)
    }

    @Test
    fun `compose ui navigates across all 5 tabs including Characters, OCR fix, and Voice Generate disclosures`() {
        val pipeline = OcrTranslationPipeline.getInstance(context)
        composeRule.setContent {
            MyApplicationTheme {
                GameVoxApp(localPipeline = pipeline)
            }
        }

        composeRule.onNodeWithTag("nav_tab_home").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_tab_voice_library").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_tab_voice_generate").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_tab_characters").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_tab_settings").assertIsDisplayed()

        // Characters tab CRUD & OCR correction UI
        composeRule.onNodeWithTag("nav_tab_characters").performClick()
        composeRule.onNodeWithTag("characters_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("characters_add_button").performClick()
        composeRule.onNodeWithTag("character_dialog_name_input").performTextInput("Elena")
        composeRule.onNodeWithTag("character_dialog_save_button").performClick()
        composeRule.onNodeWithTag("characters_lazy_list").performScrollToNode(hasTestTag("character_card_elena"))
        composeRule.onNodeWithTag("character_card_elena").assertIsDisplayed()

        // OCR Fix dialog
        composeRule.onNodeWithTag("characters_ocr_fix_button").performClick()
        composeRule.onNodeWithTag("ocr_fix_typo_input").performTextInput("E1ena")
        composeRule.onNodeWithTag("ocr_fix_canonical_input").performTextReplacement("Elena")
        composeRule.onNodeWithTag("ocr_fix_save_button").performClick()
        assertEquals("elena", VoiceProfileRepository.getOcrCharacterCorrections()["e1ena"])

        // Voice Generate capability disclosure & acoustic sliders
        composeRule.onNodeWithTag("nav_tab_voice_generate").performClick()
        composeRule.onNodeWithTag("voice_generate_capability_disclosure").assertIsDisplayed()
        composeRule.onNodeWithTag("gen_offline_pitch_slider").performScrollTo().assertIsDisplayed()

        // Voice Library uses Hide Voice for protected defaults instead of blocked Delete
        composeRule.onNodeWithTag("nav_tab_voice_library").performClick()
        composeRule.onNodeWithTag("voice_library_lazy_list").performScrollToNode(hasTestTag("voice_card_preset_f1_priya"))
        composeRule.onNodeWithTag("voice_menu_preset_f1_priya").performClick()
        composeRule.onNodeWithTag("menu_hide_preset_f1_priya").performClick()
        assertEquals(0, composeRule.onAllNodesWithTag("voice_card_preset_f1_priya").fetchSemanticsNodes().size)

        // Ensure zero "Voice Manager" obsolete branding
        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        assertEquals(0, composeRule.onAllNodesWithText("Voice Manager").fetchSemanticsNodes().size)
    }
}
