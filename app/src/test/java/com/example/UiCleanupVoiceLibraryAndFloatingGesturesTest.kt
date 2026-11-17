package com.example

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.SpeakerGender
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceCategory
import com.example.data.VoiceProfileRepository
import com.example.engine.OcrTranslationPipeline
import com.example.service.FloatingOverlayController
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h915dp")
class UiCleanupVoiceLibraryAndFloatingGesturesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("gamevox_multi_voice_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("screen_translator_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TranslatorStateRepository.loadPreferences(context)
        VoiceProfileRepository.initialize(context)
    }

    @After
    fun teardown() {
        val customDir = File(context.filesDir, "custom_voices")
        if (customDir.exists()) customDir.deleteRecursively()
    }

    // -----------------------------------------------------------------
    // 1. FLOATING ICON SINGLE-TAP / DOUBLE-TAP & DRAG GESTURE ISOLATION
    // -----------------------------------------------------------------

    @Test
    fun `floating icon single tap toggles scanning ON and OFF while double tap toggles subtitle visibility independently`() {
        var scanningCalls = 0
        var subtitleStates = mutableListOf<Boolean>()
        var controller: FloatingOverlayController? = null

        controller = FloatingOverlayController(
            context = context,
            onStartStopDubbingToggled = {
                scanningCalls++
                val next = !TranslatorStateRepository.state.value.isCapturingOcr
                TranslatorStateRepository.setCapturingOcr(next)
                controller?.updateScanningState(next)
                controller?.showGestureFeedbackBadge(if (next) "Scanning ON" else "Scanning OFF")
            },
            onSubtitleVisibilityToggled = { visible ->
                subtitleStates.add(visible)
                TranslatorStateRepository.setSubtitleVisible(context, visible)
            }
        )

        ShadowSettings.setCanDrawOverlays(true)
        TranslatorStateRepository.setCapturingOcr(true)
        TranslatorStateRepository.setSubtitleVisible(context, true)
        controller.showFloatingOverlay()
        shadowOf(Looper.getMainLooper()).idle()

        // First single tap -> pauses scanning, keeps subtitle state untouched
        controller.triggerBubbleSingleTapForTest()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, scanningCalls)
        assertFalse(TranslatorStateRepository.state.value.isCapturingOcr)
        assertEquals("Scanning OFF", controller.getCurrentFeedbackBannerText())
        assertTrue(TranslatorStateRepository.state.value.isSubtitleVisible)

        // Second single tap -> resumes scanning
        controller.triggerBubbleSingleTapForTest()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, scanningCalls)
        assertTrue(TranslatorStateRepository.state.value.isCapturingOcr)
        assertEquals("Scanning ON", controller.getCurrentFeedbackBannerText())

        // First double tap -> hides subtitle captions WITHOUT toggling scanning OFF
        controller.triggerBubbleDoubleTapForTest()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(false), subtitleStates)
        assertFalse(TranslatorStateRepository.state.value.isSubtitleVisible)
        assertFalse(controller.isSubtitleViewAttachedAndVisible())
        assertFalse(controller.isSubtitleViewBlockingTouch())
        assertEquals("Hindi Subtitles Hidden", controller.getCurrentFeedbackBannerText())
        assertTrue(TranslatorStateRepository.state.value.isCapturingOcr)
        assertEquals(2, scanningCalls)

        // While subtitles are hidden, OCR & translation continue updating latest text silently
        controller.updateDebugReadout(
            englishText = "Watch out ahead!",
            hindiText = "आगे ध्यान से देखो!",
            statusNote = "Live",
            sequenceId = 42L,
            sessionEpoch = 1L
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.isSubtitleViewAttachedAndVisible())
        assertEquals("आगे ध्यान से देखो!", controller.getLastDisplayedSubtitleText())

        // Second double tap -> restores subtitle visibility and shows latest translated line
        controller.triggerBubbleDoubleTapForTest()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(false, true), subtitleStates)
        assertTrue(TranslatorStateRepository.state.value.isSubtitleVisible)
        assertEquals("Hindi Subtitles Shown", controller.getCurrentFeedbackBannerText())
        assertTrue(TranslatorStateRepository.state.value.isCapturingOcr)
        assertEquals(2, scanningCalls)

        controller.destroyOverlay()
    }

    @Test
    fun `dragging floating bubble beyond touch slop never triggers single tap or double tap`() {
        ShadowSettings.setCanDrawOverlays(true)
        var singleTaps = 0
        var doubleTaps = 0
        val controller = FloatingOverlayController(
            context = context,
            onStartStopDubbingToggled = { singleTaps++ },
            onSubtitleVisibilityToggled = { doubleTaps++ }
        )
        controller.showFloatingOverlay()

        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        val move = MotionEvent.obtain(downTime, downTime + 25L, MotionEvent.ACTION_MOVE, 100f + slop + 40f, 140f, 0)
        val up = MotionEvent.obtain(downTime, downTime + 50L, MotionEvent.ACTION_UP, 100f + slop + 40f, 140f, 0)

        controller.dispatchBubbleTouchEventForTest(down)
        controller.dispatchBubbleTouchEventForTest(move)
        controller.dispatchBubbleTouchEventForTest(up)
        down.recycle()
        move.recycle()
        up.recycle()

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))

        assertEquals(0, singleTaps)
        assertEquals(0, doubleTaps)
        controller.destroyOverlay()
    }

    @Test
    fun `double tapping floating bubble via real MotionEvents does not trigger single tap pause`() {
        ShadowSettings.setCanDrawOverlays(true)
        var singleTaps = 0
        var doubleTaps = 0
        val controller = FloatingOverlayController(
            context = context,
            onStartStopDubbingToggled = { singleTaps++ },
            onSubtitleVisibilityToggled = { doubleTaps++ }
        )
        controller.showFloatingOverlay()

        val t0 = SystemClock.uptimeMillis()
        val events = listOf(
            MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 50f, 50f, 0),
            MotionEvent.obtain(t0, t0 + 20L, MotionEvent.ACTION_UP, 50f, 50f, 0),
            MotionEvent.obtain(t0 + 70L, t0 + 70L, MotionEvent.ACTION_DOWN, 51f, 50f, 0),
            MotionEvent.obtain(t0 + 70L, t0 + 90L, MotionEvent.ACTION_UP, 51f, 50f, 0)
        )
        events.forEach { ev ->
            controller.dispatchBubbleTouchEventForTest(ev)
            ev.recycle()
        }

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))

        assertEquals(1, doubleTaps)
        assertEquals(0, singleTaps)
        controller.destroyOverlay()
    }

    // -----------------------------------------------------------------
    // 2. VOICE LIBRARY CRUD, RENAME ID STABILITY, SAFE DELETE & MIGRATION
    // -----------------------------------------------------------------

    @Test
    fun `voice library includes all 10 Supertonic male and female defaults plus custom lifecycle persistence`() {
        val initialVoices = VoiceProfileRepository.voices.value
        val defaultMales = initialVoices.filter { it.voiceCategory == VoiceCategory.DEFAULT_MALE }
        val defaultFemales = initialVoices.filter { it.voiceCategory == VoiceCategory.DEFAULT_FEMALE }
        assertEquals(5, defaultMales.size)
        assertEquals(5, defaultFemales.size)

        // Add imported & generated voices
        val imported = VoiceProfileRepository.addGeneratedOrImportedVoice(
            context = context,
            name = "Custom Alice Imported",
            description = "Warm sample",
            gender = SpeakerGender.FEMALE_18,
            engineVoiceIndex = 1,
            voiceCategory = VoiceCategory.IMPORTED,
            sourceModelInfo = "Imported Sample",
            languageCode = "hi",
            styleTag = "Conversational"
        )
        val generated = VoiceProfileRepository.addGeneratedOrImportedVoice(
            context = context,
            name = "Heroic Offline Anon",
            description = "Supertonic SID #5",
            gender = SpeakerGender.MALE_18,
            engineVoiceIndex = 5,
            voiceCategory = VoiceCategory.GENERATED_OFFLINE,
            sourceModelInfo = "Supertonic 3 Offline",
            languageCode = "hi",
            styleTag = "Heroic"
        )

        // Assign distinct voices to Anon, Jenny, Alice
        val aliceAssign = VoiceProfileRepository.assignVoiceToCharacter(context, imported, "Alice")
        val anonAssign = VoiceProfileRepository.assignVoiceToCharacter(context, generated, "Anon")
        assertEquals(imported.id, aliceAssign.assignedVoiceId)
        assertEquals(generated.id, anonAssign.assignedVoiceId)

        // Rename keeps voice ID identical!
        val originalImportedId = imported.id
        val renamed = VoiceProfileRepository.renameVoice(context, originalImportedId, "Alice Season 2")
        assertNotNull(renamed)
        assertEquals(originalImportedId, renamed?.id)
        assertEquals("Alice Season 2", renamed?.name)

        // Default system voice deletion is protected
        val protectedDelete = VoiceProfileRepository.deleteVoiceSafely(context, "preset_char_a_hero")
        assertTrue(protectedDelete.isFailure)

        // Deleting an assigned custom voice WITHOUT replacement fails with clear explanation
        val blockedDelete = VoiceProfileRepository.deleteVoiceSafely(context, originalImportedId, replacementVoiceId = null)
        assertTrue(blockedDelete.isFailure)
        assertTrue(blockedDelete.exceptionOrNull()?.message.orEmpty().contains("Alice"))

        // Deleting WITH replacement succeeds & updates Alice's assignment cleanly
        val safeDelete = VoiceProfileRepository.deleteVoiceSafely(
            context = context,
            voiceId = originalImportedId,
            replacementVoiceId = "preset_char_b_companion"
        )
        assertTrue(safeDelete.isSuccess)
        assertFalse(VoiceProfileRepository.voices.value.any { it.id == originalImportedId })

        // Re-initialize from disk & confirm persistence of renamed/assigned voices and reassignment
        VoiceProfileRepository.initialize(context)
        assertTrue(VoiceProfileRepository.voices.value.any { it.id == generated.id && it.name == "Heroic Offline Anon" })
        val persistedAlice = VoiceProfileRepository.getAssignmentForCharacter("Alice", context)
        assertEquals("preset_char_b_companion", persistedAlice.assignedVoiceId)
    }

    @Test
    fun `legacy saved custom voices migrate cleanly without losing existing ids`() {
        val legacyArr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "voice_cloned_legacy_99")
                put("name", "Legacy Clone")
                put("description", "Old format")
                put("pitch", 1.15)
                put("speechRate", 1.0)
                put("engineVoiceIndex", 2)
                put("assignedRole", "char_b")
                put("gender", "female_18")
                put("isCustomCloned", true)
            })
        }
        context.getSharedPreferences("gamevox_multi_voice_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("saved_voice_profiles_json_v1", legacyArr.toString())
            .commit()

        VoiceProfileRepository.initialize(context)
        val voices = VoiceProfileRepository.voices.value
        val migrated = voices.find { it.id == "voice_cloned_legacy_99" }
        assertNotNull(migrated)
        assertEquals(VoiceCategory.IMPORTED, migrated?.voiceCategory)
        assertFalse(migrated?.isSystemProtected ?: true)
        assertTrue(voices.size >= 11) // 10 Supertonic defaults + 1 legacy custom voice
    }

    // -----------------------------------------------------------------
    // 3. SAMPLE AUDIO INSPECTION & HONEST CLONING VERDICT
    // -----------------------------------------------------------------

    @Test
    fun `inspectAudioSample validates real WAV duration RMS clipping and cloning compatibility honestly`() {
        val wavFile = File(context.filesDir, "test_sample_6s.wav")
        writePcmSineWav(wavFile, durationSec = 6, sampleRate = 16000, freqHz = 220.0)

        val report = VoiceProfileRepository.inspectAudioSample(wavFile)
        assertTrue(report.isWavPcm)
        assertEquals(16000, report.sampleRateHz)
        assertTrue(report.durationSeconds in 5.8f..6.2f)
        assertTrue(report.rmsEnergy > 0.1f)
        assertTrue(report.recommendedForCloning)
        assertTrue(report.sha256.length == 64)
        // Offline Chatterbox -> honest cloningBackendCompatible == false
        assertFalse(report.cloningBackendCompatible)
        wavFile.delete()
    }

    // -----------------------------------------------------------------
    // 4. COMPOSE UI NAVIGATION, SEPARATION & ZERO DUPLICATE CONTROLS
    // -----------------------------------------------------------------

    @Test
    fun `compose ui cleanly separates Home, Voice Library, Voice Generate, and Settings with working + Add Voice CTA`() {
        val pipeline = OcrTranslationPipeline.getInstance(context)
        composeRule.setContent {
            MyApplicationTheme {
                GameVoxApp(localPipeline = pipeline)
            }
        }

        // Starts on Home / Live Dubbing
        composeRule.onNodeWithTag("home_live_dubbing_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle_overlay_demo_button").assertIsDisplayed()

        // Switch to Voice Library tab
        composeRule.onNodeWithTag("nav_tab_voice_library").performClick()
        composeRule.onNodeWithTag("voice_library_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_library_add_voice_button").assertIsDisplayed()
        // Verify Voice Generate inputs are NOT duplicated inside Voice Library
        assertEquals(0, composeRule.onAllNodesWithTag("generate_voice_name_input").fetchSemanticsNodes().size)

        // Tap "+ Add Voice" CTA -> navigates strictly to Voice Generate screen
        composeRule.onNodeWithTag("voice_library_add_voice_button").performClick()
        composeRule.onNodeWithTag("voice_generate_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("generate_voice_name_input").performTextInput("Alice Custom Heroine")
        composeRule.onNodeWithTag("gen_tab_offline").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("gen_tab_online").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("gen_tab_import_clone").performScrollTo().performClick()
        composeRule.onNodeWithTag("import_audio_sample_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("voice_clone_consent_checkbox").performScrollTo().assertIsDisplayed()

        // Switch to Settings tab
        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        // Verify no duplicate "Voice Manager" label remains
        assertEquals(0, composeRule.onAllNodesWithText("Voice Manager").fetchSemanticsNodes().size)
    }

    private fun writePcmSineWav(outFile: File, durationSec: Int, sampleRate: Int, freqHz: Double) {
        val numSamples = durationSec * sampleRate
        val dataSize = numSamples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        FileOutputStream(outFile).use { fos ->
            fos.write(header)
            val pcmBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                val sample = (sin(2.0 * Math.PI * freqHz * i / sampleRate) * 14000.0).toInt().toShort()
                pcmBuf.putShort(sample)
            }
            fos.write(pcmBuf.array())
        }
    }
}
