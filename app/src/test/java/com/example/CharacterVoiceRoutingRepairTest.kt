package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CharacterVoiceAssignment
import com.example.data.SpeakerGender
import com.example.data.VoiceProfileRepository
import com.example.engine.CharacterVoiceRouter
import com.example.engine.PendingDubbingItem
import com.example.engine.SpeakerHeuristicEngine
import com.example.engine.SubtitleDubbingQueue
import com.example.service.ForegroundTtsManager
import com.example.service.SherpaSupertonicBackend
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
        VoiceProfileRepository.initialize(context)
        VoiceProfileRepository.restoreAllCharacterDefaults(context)
        CharacterVoiceRouter.resetContinuationContext()
        CharacterVoiceRouter.clearAuditLogs()
    }

    @Test
    fun testAnonAndJennyLiveGamePipelineProduceDifferentActualNativeSpeakerIds() {
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

        assertEquals("Jenny", jennyDecision.rawCharacterName)
        assertEquals("jenny", jennyDecision.normalizedCharacterName)
        assertEquals("Please don't leave me!", jennyDecision.strippedEnglishDialogue)
        assertFalse(jennyDecision.hindiDialogue.contains("Jenny", ignoreCase = true))

        // 2. Verify Anon (Male Lead, SID 5) and Jenny (Female Companion, SID 1) resolve to distinct SIDs
        assertEquals(5, anonDecision.selectedSpeakerId)
        assertEquals(1, jennyDecision.selectedSpeakerId)
        assertNotEquals(anonDecision.selectedSpeakerId, jennyDecision.selectedSpeakerId)
        assertFalse(anonDecision.characterAssignment.isFemale)
        assertTrue(jennyDecision.characterAssignment.isFemale)
    }

    @Test
    fun testPreviewVsLiveVoiceComparisonForAnonAndJennyExportsPairedWavs() {
        val exportDir = File("build/outputs/voice_comparison_samples").apply { mkdirs() }
        val hindiLine = "मैं तुम्हारी रक्षा करूँगा, कृपया मुझे मत छोड़ो!"

        val report = CharacterVoiceRouter.runFullAnonAndJennyComparison(
            context = context,
            hindiSentence = hindiLine,
            outputDir = exportDir
        )

        assertTrue("Expected Preview vs Live comparison to pass: ${report.summaryReport}", report.overallPassed)
        assertTrue(report.maleAndFemaleSidsDistinct)
        assertTrue(report.maleAndFemaleAudioDistinct)

        // Verify Anon Preview vs Live parity
        assertEquals(5, report.anonComparison.previewRequestedSid)
        assertEquals(5, report.anonComparison.previewActualSid)
        assertEquals(5, report.anonComparison.liveRequestedSid)
        assertEquals(5, report.anonComparison.liveActualSid)
        assertEquals(report.anonComparison.previewEngine, report.anonComparison.liveEngine)
        assertTrue(report.anonComparison.previewAudioRms >= SherpaSupertonicBackend.MIN_NON_SILENT_RMS)
        assertTrue(report.anonComparison.liveAudioRms >= SherpaSupertonicBackend.MIN_NON_SILENT_RMS)

        // Verify Jenny Preview vs Live parity
        assertEquals(1, report.jennyComparison.previewRequestedSid)
        assertEquals(1, report.jennyComparison.previewActualSid)
        assertEquals(1, report.jennyComparison.liveRequestedSid)
        assertEquals(1, report.jennyComparison.liveActualSid)
        assertEquals(report.jennyComparison.previewEngine, report.jennyComparison.liveEngine)
        assertTrue(report.jennyComparison.previewAudioRms >= SherpaSupertonicBackend.MIN_NON_SILENT_RMS)
        assertTrue(report.jennyComparison.liveAudioRms >= SherpaSupertonicBackend.MIN_NON_SILENT_RMS)

        // Verify exported WAV files exist and have valid RIFF headers
        val wavFiles = listOf(
            File(report.anonComparison.previewWavPath),
            File(report.anonComparison.liveWavPath),
            File(report.jennyComparison.previewWavPath),
            File(report.jennyComparison.liveWavPath)
        )
        for (wav in wavFiles) {
            assertTrue("Expected exported WAV file at ${wav.absolutePath}", wav.exists())
            assertTrue("Expected non-empty WAV > 1000 bytes", wav.length() > 1000L)
            val header = String(wav.readBytes().take(4).toByteArray(), Charsets.US_ASCII)
            assertEquals("RIFF", header)
        }

        // Verify audit logs recorded all utterances
        val logs = CharacterVoiceRouter.auditLogs.value
        assertTrue("Expected at least 4 audit logs, found ${logs.size}", logs.size >= 4)
    }

    @Test
    fun testManualCharacterVoiceOverrideSurvivesAppRestart() {
        // Assign custom speaker IDs for Anon (SID 8) and Jenny (SID 3)
        VoiceProfileRepository.setUserOverrideForCharacter(
            context = context,
            characterName = "Anon",
            sid = 8,
            referenceVoiceId = CharacterVoiceAssignment.DEFAULT_MALE_REF_ID,
            voicePresentation = "male"
        )
        VoiceProfileRepository.setUserOverrideForCharacter(
            context = context,
            characterName = "Jenny",
            sid = 3,
            referenceVoiceId = CharacterVoiceAssignment.DEFAULT_FEMALE_REF_ID,
            voicePresentation = "female"
        )

        // Simulate app restart by reloading from SharedPreferences
        VoiceProfileRepository.initialize(context)

        val anonLive = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Anon]: Hold the line!",
            translatedHindi = "मोर्चा संभाले रखो!"
        )
        val jennyLive = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Jenny]: Watch out behind you!",
            translatedHindi = "अपने पीछे देखो!"
        )

        assertEquals(8, anonLive.selectedSpeakerId)
        assertEquals("USER_OVERRIDE", anonLive.resolutionRule)
        assertEquals(3, jennyLive.selectedSpeakerId)
        assertEquals("USER_OVERRIDE", jennyLive.resolutionRule)

        // Restore defaults and verify
        VoiceProfileRepository.restoreAllCharacterDefaults(context)
        assertEquals(5, VoiceProfileRepository.getAssignmentForCharacter("Anon").speakerId)
        assertEquals(1, VoiceProfileRepository.getAssignmentForCharacter("Jenny").speakerId)
    }

    @Test
    fun testMissingSpeakerLabelFollowsContinuationContextWithoutResettingToSid0() {
        val t0 = 1_000_000L
        val taggedJenny = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Jenny]: Please don't leave me!",
            translatedHindi = "कृपया मुझे मत छोड़ो!",
            nowMs = t0
        )
        assertEquals("jenny", taggedJenny.normalizedCharacterName)
        assertEquals(1, taggedJenny.selectedSpeakerId)

        // Unlabeled continuation subtitle 3 seconds later in the same scene must continue Jenny (SID 1)
        val untaggedContinuation = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "We need to escape before the gate closes!",
            translatedHindi = "गेट बंद होने से पहले हमें निकलना होगा!",
            nowMs = t0 + 3_000L
        )
        assertEquals("jenny", untaggedContinuation.normalizedCharacterName)
        assertEquals(1, untaggedContinuation.selectedSpeakerId)
        assertEquals("CONTINUATION_CONTEXT", untaggedContinuation.resolutionRule)
    }

    @Test
    fun testOcrCharacterTypoCorrectionAndNoNameSuffixGenderGuessing() {
        // 1. OCR typo [An0n] and [Jeny] should resolve to canonical Anon (SID 5) and Jenny (SID 1)
        val anonTypo = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[An0n]: I will protect you.",
            translatedHindi = "मैं तुम्हारी रक्षा करूँगा।"
        )
        val jennyTypo = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Jeny]: Please don't leave me!",
            translatedHindi = "कृपया मुझे मत छोड़ो!"
        )

        assertEquals("anon", anonTypo.normalizedCharacterName)
        assertEquals(5, anonTypo.selectedSpeakerId)
        assertEquals("OCR_ALIAS_CORRECTION", anonTypo.resolutionRule)

        assertEquals("jenny", jennyTypo.normalizedCharacterName)
        assertEquals(1, jennyTypo.selectedSpeakerId)
        assertEquals("OCR_ALIAS_CORRECTION", jennyTypo.resolutionRule)

        // 2. Unknown character names ending in -a or -y (e.g. "Kenta", "Broly") must NOT be guessed as female from suffix alone
        assertEquals(SpeakerGender.MALE_18, SpeakerHeuristicEngine.detectCharacterGender("Kenta"))
        assertEquals(SpeakerGender.MALE_18, SpeakerHeuristicEngine.detectCharacterGender("Broly"))
    }

    @Test
    fun testTenVoiceAuditionMatrixRoutesExactSidWithoutActiveCharacterShadowing() {
        val ttsManager = ForegroundTtsManager.getInstance(context)
        // First set active character to Anon (SID 5)
        CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Anon]: Ready for battle.",
            translatedHindi = "लड़ाई के लिए तैयार।"
        )
        assertEquals(5, VoiceProfileRepository.activeCharacterAssignment.value?.speakerId)

        // Now audition all 10 base speakers (SID 0..9) and verify each audition resolves to its exact SID
        for (sid in 0..9) {
            val route = CharacterVoiceRouter.routeBaseSpeakerAudition(
                context = context,
                sid = sid,
                speakerTitle = "Speaker #$sid",
                genderLabel = if (sid in 0..4) "Female" else "Male",
                hindiDialogue = "नमस्ते गेमर!"
            )
            ttsManager.speakNonBlocking(
                text = "नमस्ते गेमर!",
                voiceProfile = route.voiceProfile,
                characterAssignment = route.characterAssignment,
                routeDecision = route
            )
            assertEquals("Audition for SID $sid was shadowed!", sid, ttsManager.lastSpokenSpeakerId)
            assertEquals(sid, ttsManager.lastResolvedRouteDecision?.selectedSpeakerId)
        }
    }

    @Test
    fun testSubtitleDubbingQueuePreservesPerItemVoiceRouteDecisionDuringRapidSubtitles() {
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

        // Enqueue both while TTS is busy speaking a prior line
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
}
