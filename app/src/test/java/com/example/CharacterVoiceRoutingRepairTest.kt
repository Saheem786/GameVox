package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CharacterVoiceAssignment
import com.example.data.SpeakerGender
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceProfileRepository
import com.example.engine.CanonicalVoiceBackend
import com.example.engine.CharacterVoiceRouter
import com.example.engine.DubbingEnqueueOutcome
import com.example.engine.OcrTranslationPipeline
import com.example.engine.PendingDubbingItem
import com.example.engine.SpeakerHeuristicEngine
import com.example.engine.SubtitleDubbingQueue
import com.example.service.ForegroundTtsManager
import com.example.service.FreeVoiceBackend
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterVoiceRoutingRepairTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TranslatorStateRepository.loadPreferences(context)
        VoiceProfileRepository.initialize(context)
        VoiceProfileRepository.setGameNamespace(context, "default_game")
        VoiceProfileRepository.restoreAllCharacterDefaults(context)
        VoiceProfileRepository.setUnlabeledSubtitleManualOverride(context, null, "default_game")
        CharacterVoiceRouter.resetContinuationContext()
        CharacterVoiceRouter.clearAuditLogs()
    }

    @Test
    fun `1_two_alternating_speaker_tags_resolve_to_distinct_saved_voices`() {
        val anonDecision = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Anon]: I will protect you.",
            translatedHindi = "मैं तुम्हारी रक्षा करूँगा।"
        )
        val jennyDecision = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Jenny]: Please don't leave me!",
            translatedHindi = "कृपया मुझे मत छोड़ो!"
        )

        // 1. Verify speaker labels were parsed BEFORE stripping and stripped from spoken dialogue
        assertEquals("Anon", anonDecision.rawCharacterName)
        assertEquals("anon", anonDecision.normalizedCharacterName)
        assertEquals("I will protect you.", anonDecision.strippedEnglishDialogue)
        assertFalse(anonDecision.hindiDialogue.contains("Anon", ignoreCase = true))
        assertTrue(anonDecision.speakerConfidence >= 0.90f)

        assertEquals("Jenny", jennyDecision.rawCharacterName)
        assertEquals("jenny", jennyDecision.normalizedCharacterName)
        assertEquals("Please don't leave me!", jennyDecision.strippedEnglishDialogue)
        assertFalse(jennyDecision.hindiDialogue.contains("Jenny", ignoreCase = true))
        assertTrue(jennyDecision.speakerConfidence >= 0.90f)

        // 2. Verify Anon (Male Lead, SID 5) and Jenny (Female Companion, SID 1) resolve to distinct SIDs
        assertEquals(5, anonDecision.selectedSpeakerId)
        assertEquals(1, jennyDecision.selectedSpeakerId)
        assertNotEquals(anonDecision.selectedSpeakerId, jennyDecision.selectedSpeakerId)
        assertFalse(anonDecision.characterAssignment.isFemale)
        assertTrue(jennyDecision.characterAssignment.isFemale)
    }

    @Test
    fun `2_two_unlabeled_lines_route_to_explicit_unknown_speaker_never_reusing_previous_male_speaker`() {
        // First, a tagged male line from Anon (SID 5)
        val taggedAnon = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Anon]: Get down behind cover!",
            translatedHindi = "कवर के पीछे झुक जाओ!"
        )
        assertEquals("anon", taggedAnon.normalizedCharacterName)
        assertEquals(5, taggedAnon.selectedSpeakerId)

        // Next, two consecutive unlabeled subtitles (e.g. rapid alternating dialogue without speaker tags)
        val unlabeled1 = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "We need to escape before the gate closes!",
            translatedHindi = "गेट बंद होने से पहले हमें निकलना होगा!"
        )
        val unlabeled2 = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "Hurry up, grab the keycard!",
            translatedHindi = "जल्दी करो, कीकार्ड उठाओ!"
        )

        // Neither unlabeled line may forcibly reuse Anon (SID 5)
        assertEquals(CharacterVoiceAssignment.UNKNOWN_SPEAKER_NAME, unlabeled1.normalizedCharacterName)
        assertEquals("UNLABELED_UNKNOWN_SPEAKER", unlabeled1.resolutionRule)
        assertEquals(0.0f, unlabeled1.speakerConfidence, 0.001f)
        assertNotEquals("Unlabeled line must not reuse previous male speaker Anon (SID 5)", 5, unlabeled1.selectedSpeakerId)

        assertEquals(CharacterVoiceAssignment.UNKNOWN_SPEAKER_NAME, unlabeled2.normalizedCharacterName)
        assertEquals("UNLABELED_UNKNOWN_SPEAKER", unlabeled2.resolutionRule)
        assertEquals(0.0f, unlabeled2.speakerConfidence, 0.001f)

        // Verify real-time visible debug log recorded raw OCR, speaker candidate, confidence, SID, and backend
        val debugLogs = CharacterVoiceRouter.liveRoutingDebugLogs.value
        assertTrue("Expected at least 3 live routing debug log entries", debugLogs.size >= 3)
        val latestDebug = debugLogs.first()
        assertEquals("(none)", latestDebug.speakerLabelCandidate)
        assertEquals("unknown", latestDebug.normalizedName)
        assertEquals(0.0f, latestDebug.confidence, 0.001f)
    }

    @Test
    fun `3_same_dialogue_spoken_by_different_speakers_anon_and_jenny_are_both_voiced`() = runBlocking {
        val queue = SubtitleDubbingQueue(maxCapacity = 2)
        val sameEnglish = "Hold the line!"
        val sameHindi = "मोर्चा संभाले रखो!"

        val anonRoute = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Anon]: $sameEnglish",
            translatedHindi = sameHindi
        )
        val jennyRoute = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Jenny]: $sameEnglish",
            translatedHindi = sameHindi
        )

        val itemAnon = PendingDubbingItem(
            sourceEnglish = anonRoute.strippedEnglishDialogue,
            spokenDialogue = anonRoute.strippedEnglishDialogue,
            translatedText = anonRoute.hindiDialogue,
            voiceProfile = anonRoute.voiceProfile,
            speakerLabel = "Anon",
            characterAssignment = anonRoute.characterAssignment,
            routeDecision = anonRoute
        )
        val itemJenny = PendingDubbingItem(
            sourceEnglish = jennyRoute.strippedEnglishDialogue,
            spokenDialogue = jennyRoute.strippedEnglishDialogue,
            translatedText = jennyRoute.hindiDialogue,
            voiceProfile = jennyRoute.voiceProfile,
            speakerLabel = "Jenny",
            characterAssignment = jennyRoute.characterAssignment,
            routeDecision = jennyRoute
        )

        // 1. Anon speaks immediately
        val out1 = queue.enqueue(
            item = itemAnon,
            isCurrentlySpeaking = false,
            activeSpokenEnglishNormalized = "",
            activeSpokenTranslated = "",
            activeSpokenSpeakerContextKey = ""
        )
        assertTrue("Anon should speak immediately", out1 is DubbingEnqueueOutcome.SpeakImmediately)

        // 2. While Anon is speaking (or right after), Jenny says the EXACT same text -> MUST NOT be skipped as duplicate!
        val out2 = queue.enqueue(
            item = itemJenny,
            isCurrentlySpeaking = true,
            activeSpokenEnglishNormalized = SubtitleDubbingQueue.normalize(sameEnglish),
            activeSpokenTranslated = sameHindi,
            activeSpokenSpeakerContextKey = itemAnon.buildSpeakerContextKey()
        )
        assertTrue("Jenny saying the same text as Anon must be queued, not skipped: $out2", out2 is DubbingEnqueueOutcome.Queued)

        // 3. If Jenny repeats the exact same line while Jenny is already queued -> skipped as duplicate
        val out3 = queue.enqueue(
            item = itemJenny,
            isCurrentlySpeaking = true,
            activeSpokenEnglishNormalized = SubtitleDubbingQueue.normalize(sameEnglish),
            activeSpokenTranslated = sameHindi,
            activeSpokenSpeakerContextKey = itemAnon.buildSpeakerContextKey()
        )
        assertEquals(DubbingEnqueueOutcome.SkippedDuplicate, out3)

        // 4. Also verify OcrTranslationPipeline end-to-end does not suppress Jenny when Anon just spoke the same line
        val pipeline = OcrTranslationPipeline.getInstance(context)
        pipeline.resetAntiRepeatMemory()
        TranslatorStateRepository.setCapturingOcr(true)
        val beforeDuplicates = TranslatorStateRepository.state.value.duplicateSkippedCount

        val resAnon = pipeline.processExtractedRawText("[Anon]: Watch your back!", enforceActiveSession = true)
        val resJenny = pipeline.processExtractedRawText("[Jenny]: Watch your back!", enforceActiveSession = true)

        assertEquals("Watch your back!", resAnon.first)
        assertEquals("Watch your back!", resJenny.first)
        assertEquals(
            "Different speakers saying identical text must not increment duplicateSkippedCount",
            beforeDuplicates,
            TranslatorStateRepository.state.value.duplicateSkippedCount
        )
        TranslatorStateRepository.setCapturingOcr(false)
    }

    @Test
    fun `4_changing_characters_during_active_tts_preserves_distinct_per_item_route_decisions`() {
        val queue = SubtitleDubbingQueue(maxCapacity = 2)
        val anonRoute = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Anon]: I will protect you.",
            translatedHindi = "मैं तुम्हारी रक्षा करूँगा।"
        )
        val jennyRoute = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Jenny]: Please don't leave me!",
            translatedHindi = "कृपया मुझे मत छोड़ो!"
        )

        val itemAnon = PendingDubbingItem(
            sourceEnglish = anonRoute.strippedEnglishDialogue,
            spokenDialogue = anonRoute.strippedEnglishDialogue,
            translatedText = anonRoute.hindiDialogue,
            voiceProfile = anonRoute.voiceProfile,
            speakerLabel = "Anon",
            characterAssignment = anonRoute.characterAssignment,
            routeDecision = anonRoute
        )
        val itemJenny = PendingDubbingItem(
            sourceEnglish = jennyRoute.strippedEnglishDialogue,
            spokenDialogue = jennyRoute.strippedEnglishDialogue,
            translatedText = jennyRoute.hindiDialogue,
            voiceProfile = jennyRoute.voiceProfile,
            speakerLabel = "Jenny",
            characterAssignment = jennyRoute.characterAssignment,
            routeDecision = jennyRoute
        )

        queue.enqueue(itemAnon, isCurrentlySpeaking = true, activeSpokenEnglishNormalized = "prior line", activeSpokenTranslated = "पूर्व पंक्ति")
        queue.enqueue(itemJenny, isCurrentlySpeaking = true, activeSpokenEnglishNormalized = "prior line", activeSpokenTranslated = "पूर्व पंक्ति")

        val polledFirst = queue.pollNext()
        val polledSecond = queue.pollNext()

        assertNotNull(polledFirst)
        assertNotNull(polledSecond)
        assertEquals(5, polledFirst?.routeDecision?.selectedSpeakerId)
        assertEquals("anon", polledFirst?.routeDecision?.normalizedCharacterName)
        assertEquals(1, polledSecond?.routeDecision?.selectedSpeakerId)
        assertEquals("jenny", polledSecond?.routeDecision?.normalizedCharacterName)
    }

    @Test
    fun `5_preview_and_live_dubbing_share_canonical_backend_selection_without_mismatch`() {
        // Default profiles use "follow_global" so preview and live dubbing never diverge
        for (preset in CharacterVoiceAssignment.DEFAULT_PRESETS) {
            assertEquals("follow_global", preset.voiceEngine)
        }

        // Case A: Global mode is ANDROID_USER_SELECTED (and Supertonic model is not installed in test env)
        TranslatorStateRepository.updateFreeVoiceBackend(context, FreeVoiceBackend.ANDROID_USER_SELECTED)
        val anon = VoiceProfileRepository.getAssignmentForCharacter("Anon", context)
        val previewRouteA = CharacterVoiceRouter.routeCharacterPreview(context, anon, "नमस्ते")
        val liveRouteA = CharacterVoiceRouter.routeLiveSubtitle(context, "[Anon]: Hello", "नमस्ते")

        assertEquals(
            "Preview and Live must select the exact same canonical backend",
            previewRouteA.canonicalBackend,
            liveRouteA.canonicalBackend
        )
        assertEquals( previewRouteA.requestedEngine, liveRouteA.requestedEngine)
        assertNotEquals(
            "When global backend is ANDROID_USER_SELECTED, preview must NOT secretly choose CHATTERBOX_LOCAL_SERVER",
            CanonicalVoiceBackend.CHATTERBOX_LOCAL_SERVER,
            previewRouteA.canonicalBackend
        )

        // Case B: Global mode is LOCAL_CHATTERBOX_SERVER
        TranslatorStateRepository.updateFreeVoiceBackend(context, FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER)
        val previewRouteB = CharacterVoiceRouter.routeCharacterPreview(context, anon, "नमस्ते")
        val liveRouteB = CharacterVoiceRouter.routeLiveSubtitle(context, "[Anon]: Hello", "नमस्ते")

        assertEquals(CanonicalVoiceBackend.CHATTERBOX_LOCAL_SERVER, previewRouteB.canonicalBackend)
        assertEquals(CanonicalVoiceBackend.CHATTERBOX_LOCAL_SERVER, liveRouteB.canonicalBackend)
        assertEquals(previewRouteB.requestedEngine, liveRouteB.requestedEngine)
    }

    @Test
    fun `6_server_unavailable_and_single_voice_system_fallback_report_honest_backend_and_unknown_rms`() {
        val ttsManager = ForegroundTtsManager.getInstance(context)
        val state = TranslatorStateRepository.state.value

        // When Android system engine does not expose distinct male & female voices, backend code must be SYSTEM_FALLBACK_SINGLE_VOICE
        val fallbackCode = ttsManager.resolveSystemFallbackBackendCode(
            state.copy(supportsDistinctGenderVoices = false)
        )
        assertEquals("SYSTEM_FALLBACK_SINGLE_VOICE", fallbackCode)

        // Record a fallback utterance audit where PCM was not measured -> outputPcmRms must be null ("UNKNOWN"), never 0.05f
        val route = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Anon]: System fallback test.",
            translatedHindi = "सिस्टम फ़ॉलबैक परीक्षण।"
        )
        val audit = CharacterVoiceRouter.recordSpokenUtteranceAudit(
            routeDecision = route,
            actualSidPassedToNative = route.selectedSpeakerId,
            actualBackend = fallbackCode,
            jniStatus = "NOT_LOADED",
            modelLoaded = false,
            fallbackReason = "Local Chatterbox server unreachable",
            audioDurationMs = 0L,
            outputPcmRms = null
        )
        assertEquals("SYSTEM_FALLBACK_SINGLE_VOICE", audit.actualBackend)
        assertNull("Unmeasured PCM RMS must be null, never fabricated 0.05f", audit.outputPcmRms)
        assertEquals("UNKNOWN", audit.outputPcmRmsDisplay)
    }

    @Test
    fun `7_model_missing_reports_NOT_TESTED_NO_MODEL_and_never_generates_synthetic_sine_wave_wavs`() = runBlocking {
        val exportDir = File(context.cacheDir, "audit_no_model_test_${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        TranslatorStateRepository.updateFreeVoiceBackend(context, FreeVoiceBackend.ANDROID_USER_SELECTED)

        val report = CharacterVoiceRouter.runFullAnonAndJennyComparison(
            context = context,
            hindiSentence = "मैं तुम्हारी रक्षा करूँगा, कृपया मुझे मत छोड़ो!",
            outputDir = exportDir,
            gameNamespace = "default_game"
        )

        // Must report NOT_TESTED_NO_MODEL and overallPassed = false when neither neural server nor Supertonic model is active
        assertFalse("Synthetic sine-wave WAVs must never count as PASS when neural model is missing", report.overallPassed)
        assertEquals("NOT_TESTED_NO_MODEL", report.verificationStatus)
        assertEquals("NOT VERIFIED", report.physicalDeviceVerificationStatus)
        assertEquals("NOT_TESTED_NO_MODEL", report.anonComparison.verificationStatus)
        assertEquals("NOT_TESTED_NO_MODEL", report.jennyComparison.verificationStatus)
        assertFalse(report.anonComparison.passed)
        assertFalse(report.jennyComparison.passed)
        assertNull(report.anonComparison.liveAudioRms)
        assertEquals("UNKNOWN", report.anonComparison.liveAudioRmsDisplay)

        // Ensure zero dummy WAV files were created in exportDir
        val generatedWavs = exportDir.listFiles()?.filter { it.extension == "wav" }.orEmpty()
        assertTrue("No synthetic sine-wave WAV files may be written on NOT_TESTED_NO_MODEL", generatedWavs.isEmpty())

        // Still verifies routing SID parity even when acoustic synthesis is NOT_TESTED_NO_MODEL
        assertTrue(report.maleAndFemaleSidsDistinct)
        assertEquals(5, report.anonComparison.previewRequestedSid)
        assertEquals(5, report.anonComparison.liveRequestedSid)
        assertEquals(1, report.jennyComparison.previewRequestedSid)
        assertEquals(1, report.jennyComparison.liveRequestedSid)
    }

    @Test
    fun `8_persistent_manual_overrides_per_game_arjun_isolation_and_consented_wav_import`() {
        // 8a. Verify 'arjun' is NOT hardcoded to 'aarav' by default (Audit Item 7)
        val (normArjunDefault, wasAliasCorrectedDefault) = CharacterVoiceRouter.normalizeAndCorrectSpeakerName(
            rawName = "Arjun",
            gameNamespace = "default_game"
        )
        assertEquals("arjun", normArjunDefault)
        assertFalse("Arjun must not be hardcoded to alias Aarav unless user sets an override", wasAliasCorrectedDefault)

        // When user explicitly registers an alias for a specific game, it applies only to that game
        CharacterVoiceRouter.registerOcrCharacterAlias("arjun", "aarav", gameNamespace = "custom_rpg")
        val (normArjunCustomGame, wasCorrectedCustom) = CharacterVoiceRouter.normalizeAndCorrectSpeakerName(
            rawName = "Arjun",
            gameNamespace = "custom_rpg"
        )
        assertEquals("aarav", normArjunCustomGame)
        assertTrue(wasCorrectedCustom)

        // 8b. Verify manual override for unlabeled subtitles persists and routes unlabeled lines when set
        VoiceProfileRepository.setUnlabeledSubtitleManualOverride(context, "jenny", "default_game")
        val overriddenUnlabeled = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "Unlabeled line with manual override active.",
            translatedHindi = "मैनुअल ओवरराइड सक्रिय होने पर बिना लेबल वाली लाइन।"
        )
        assertEquals("jenny", overriddenUnlabeled.normalizedCharacterName)
        assertEquals(1, overriddenUnlabeled.selectedSpeakerId)
        assertEquals("UNLABELED_MANUAL_OVERRIDE", overriddenUnlabeled.resolutionRule)
        VoiceProfileRepository.setUnlabeledSubtitleManualOverride(context, null, "default_game")

        // 8c. Verify manual character SID override survives re-initialization
        VoiceProfileRepository.setUserOverrideForCharacter(
            context = context,
            characterName = "Anon",
            sid = 8,
            referenceVoiceId = CharacterVoiceAssignment.DEFAULT_MALE_REF_ID,
            voicePresentation = "male"
        )
        VoiceProfileRepository.initialize(context)
        val anonLive = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Anon]: Hold the line!",
            translatedHindi = "मोर्चा संभाले रखो!"
        )
        assertEquals(8, anonLive.selectedSpeakerId)
        assertEquals("USER_OVERRIDE", anonLive.resolutionRule)

        // 8d. Verify honest reference voice count (2 bundled WAVs across 24 profiles) + consented WAV import increases unique count
        VoiceProfileRepository.restoreAllCharacterDefaults(context)
        val (initialUniqueCount, totalProfiles, summaryText) = VoiceProfileRepository.getUniqueReferenceVoiceSummary()
        assertEquals(2, initialUniqueCount)
        assertTrue(totalProfiles >= 24)
        assertTrue(summaryText.contains("24 saved character profiles"))

        val tempWav = File(context.cacheDir, "consented_kaal_sample.wav")
        writeValidTestPcmWav(tempWav)

        // Rejects import when speakerConsentConfirmed == false
        val rejected = VoiceProfileRepository.importConsentedReferenceVoiceFile(
            context = context,
            characterName = "kaal",
            sourceWavFile = tempWav,
            speakerConsentConfirmed = false
        )
        assertTrue(rejected.isFailure)

        // Accepts import when speakerConsentConfirmed == true and increases unique SHA-256 reference voices from 2 to 3
        val accepted = VoiceProfileRepository.importConsentedReferenceVoiceFile(
            context = context,
            characterName = "kaal",
            sourceWavFile = tempWav,
            speakerConsentConfirmed = true
        )
        assertTrue(accepted.isSuccess)
        val (newUniqueCount, _, _) = VoiceProfileRepository.getUniqueReferenceVoiceSummary()
        assertEquals(3, newUniqueCount)
    }

    private fun writeValidTestPcmWav(target: File) {
        val sampleRate = 16000
        val numSamples = 1600
        val pcmBytes = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            pcmBytes.putShort(((i % 40) * 200).toShort())
        }
        val raw = pcmBytes.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + raw.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(raw.size)
        }.array()
        FileOutputStream(target).use {
            it.write(header)
            it.write(raw)
        }
    }
}
