package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioSampleProcessor
import com.example.data.SpeakerGender
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceCategory
import com.example.data.VoiceProfileRepository
import com.example.engine.CanonicalVoiceBackend
import com.example.engine.CharacterVoiceRouter
import com.example.engine.OcrTranslationPipeline
import com.example.service.ForegroundTtsManager
import com.example.service.FreeVoiceBackend
import com.example.service.NeuralServerErrorCategory
import com.example.ui.theme.MyApplicationTheme
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h915dp")
class AiVoiceCloningEndToEndTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private var httpServer: HttpServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("gamevox_multi_voice_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("screen_translator_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TranslatorStateRepository.loadPreferences(context)
        VoiceProfileRepository.initialize(context)
        VoiceProfileRepository.restoreAllCharacterDefaults(context)
        CharacterVoiceRouter.clearAuditLogs()
        CharacterVoiceRouter.resetContinuationContext()
    }

    @After
    fun tearDown() {
        httpServer?.stop(0)
        httpServer = null
        TranslatorStateRepository.updateChatterboxServerStatus(
            reachable = false,
            modelLoaded = false,
            device = "offline",
            statusText = "Not tested"
        )
        val customDir = File(context.filesDir, "custom_voices")
        if (customDir.exists()) customDir.deleteRecursively()
    }

    // -----------------------------------------------------------------
    // 1. AUDIO PREPROCESSING DSP: TRIM SILENCE, MONO RESAMPLE, SNR, F0
    // -----------------------------------------------------------------

    @Test
    fun `audio sample processor trims dead silence downmixes stereo 44kHz to 24kHz mono and checks quality`() {
        val sampleRate = 44100
        val leadSilence = ShortArray((sampleRate * 1.2).toInt())
        val speechA = generateSinePcm(durationSec = 3.2f, sampleRate = sampleRate, freqHz = 135.0, amplitude = 11000.0)
        val midPause = ShortArray((sampleRate * 1.3).toInt())
        val speechB = generateSinePcm(durationSec = 3.2f, sampleRate = sampleRate, freqHz = 142.0, amplitude = 11000.0)
        val trailSilence = ShortArray((sampleRate * 1.1).toInt())
        val monoCombined = leadSilence + speechA + midPause + speechB + trailSilence

        // Interleave into stereo PCM16 WAV
        val stereoSource = File(context.filesDir, "custom_voices/stereo_source.wav").apply {
            parentFile?.mkdirs()
        }
        writeStereoWav(stereoSource, monoCombined, sampleRate)

        val outNormalized = File(context.filesDir, "custom_voices/normalized_24k.wav")
        val res = AudioSampleProcessor.decodeAndNormalizeFile(stereoSource, outNormalized)
        assertTrue(res.isSuccess)
        val report = res.getOrThrow()

        assertEquals(44100, report.sourceSampleRateHz)
        assertEquals(2, report.sourceChannels)
        assertEquals(24000, report.processedSampleRateHz)
        assertTrue("Expected > 2000ms trimmed silence, got ${report.trimmedSilenceMs}", report.trimmedSilenceMs >= 2000L)
        assertTrue("Expected cleaned speech duration ~6.0-7.2s, got ${report.durationSeconds}", report.durationSeconds in 5.8f..7.5f)
        assertTrue("Expected clean SNR > 15dB, got ${report.estimatedSnrDb}", report.estimatedSnrDb > 15f)
        assertFalse(report.multiSpeakerSuspected)
        assertTrue(report.recommendedForCloning)
        assertEquals(64, report.sha256.length)
        assertTrue(report.qualityVerdict.contains("Ready") || report.qualityVerdict.contains("Reference"))
    }

    @Test
    fun `audio sample processor flags clipping optimal 35s duration and multi-speaker bimodal F0`() {
        val sampleRate = 24000
        // Multi-speaker alternating 110 Hz (male) and 235 Hz (female) segments
        val maleSeg1 = generateSinePcm(3.0f, sampleRate, 110.0, 12000.0)
        val femaleSeg1 = generateSinePcm(3.0f, sampleRate, 235.0, 12000.0)
        val maleSeg2 = generateSinePcm(3.0f, sampleRate, 112.0, 12000.0)
        val femaleSeg2 = generateSinePcm(3.0f, sampleRate, 238.0, 12000.0)
        val mixedOut = File(context.filesDir, "custom_voices/mixed_speakers.wav").apply { parentFile?.mkdirs() }
        val mixedReport = AudioSampleProcessor.processPcmToNormalizedWav(
            monoSamples = maleSeg1 + femaleSeg1 + maleSeg2 + femaleSeg2,
            sourceSampleRateHz = sampleRate,
            sourceChannels = 1,
            sourceFormat = "WAV",
            destWavFile = mixedOut
        )
        assertTrue(mixedReport.multiSpeakerSuspected)
        assertTrue(mixedReport.warnings.any { it.contains("multiple speakers", ignoreCase = true) })

        // Clipping + 35s optimal length
        val longSamples = generateSinePcm(35.0f, sampleRate, 190.0, 32760.0)
        val longOut = File(context.filesDir, "custom_voices/long_clipped.wav")
        val longReport = AudioSampleProcessor.processPcmToNormalizedWav(
            monoSamples = longSamples,
            sourceSampleRateHz = sampleRate,
            sourceChannels = 1,
            sourceFormat = "WAV",
            destWavFile = longOut
        )
        assertTrue(longReport.qualityVerdict.startsWith("Ready"))
        assertTrue(longReport.warnings.any { it.contains("Clipping", ignoreCase = true) })
    }

    // -----------------------------------------------------------------
    // 2. CLONED PROFILE PERSISTENCE & CANONICAL ROUTING CONSISTENCY
    // -----------------------------------------------------------------

    @Test
    fun `cloned voice metadata persists cleanly and forces Chatterbox canonical backend for both preview and live OCR`() {
        val refWav = File(context.filesDir, "custom_voices/alice_clone_ref.wav").apply { parentFile?.mkdirs() }
        val synthWav = File(context.filesDir, "custom_voices/alice_clone_synth.wav")
        writeMonoWav(refWav, generateSinePcm(8.0f, 24000, 225.0, 11000.0), 24000)
        writeMonoWav(synthWav, generateSinePcm(2.5f, 24000, 240.0, 12000.0), 24000)

        val saved = VoiceProfileRepository.addGeneratedOrImportedVoice(
            context = context,
            name = "Alice Neural Clone",
            description = "Chatterbox V3 Hindi",
            gender = SpeakerGender.FEMALE_18,
            engineVoiceIndex = 1,
            voiceCategory = VoiceCategory.CLONED,
            sourceModelInfo = "Chatterbox V3 Hindi Clone",
            sampleAudioPath = refWav.absolutePath,
            isNeuralCloned = true,
            referenceAudioSha256 = "abc123def456",
            generatedPreviewPath = synthWav.absolutePath,
            generatedPreviewText = "सावधान रहो!",
            cloneExaggeration = 0.65f,
            cloneCfgWeight = 0.45f,
            consentConfirmed = true,
            consentTimestampMs = 1731800000000L
        )
        val assignment = VoiceProfileRepository.assignVoiceToCharacter(context, saved, "Alice")
        assertEquals(FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER.code, assignment.voiceEngine)
        assertTrue(assignment.isUserOverride)

        // Force global default to offline Supertonic to verify per-character cloned voice still chooses Chatterbox
        TranslatorStateRepository.setFreeVoiceBackend(context, FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT)

        val previewBackend = CharacterVoiceRouter.selectCanonicalVoiceBackend(
            context = context,
            assignment = assignment,
            state = TranslatorStateRepository.state.value
        ).canonicalBackend
        val liveDecision = CharacterVoiceRouter.routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = "[Alice]: Look out ahead!",
            translatedHindi = "आगे ध्यान से देखो!"
        )
        assertEquals(CanonicalVoiceBackend.CHATTERBOX_LOCAL_SERVER, previewBackend)
        assertEquals(CanonicalVoiceBackend.CHATTERBOX_LOCAL_SERVER, liveDecision.canonicalBackend)
        assertEquals(saved.id, liveDecision.characterAssignment.assignedVoiceId)

        // Re-initialize from SharedPreferences & confirm all clone fields survived
        VoiceProfileRepository.initialize(context)
        val reloaded = VoiceProfileRepository.voices.value.find { it.id == saved.id }
        assertNotNull(reloaded)
        assertEquals(VoiceCategory.CLONED, reloaded?.voiceCategory)
        assertTrue(reloaded?.isNeuralCloned == true)
        assertEquals("abc123def456", reloaded?.referenceAudioSha256)
        assertEquals(synthWav.absolutePath, reloaded?.generatedPreviewPath)
        assertEquals("सावधान रहो!", reloaded?.generatedPreviewText)
        assertEquals(0.65f, reloaded?.cloneExaggeration ?: 0f, 0.001f)
        assertEquals(0.45f, reloaded?.cloneCfgWeight ?: 0f, 0.001f)
        assertTrue(reloaded?.consentConfirmed == true)
    }

    // -----------------------------------------------------------------
    // 3. END-TO-END LOOPBACK 2-SPEAKER CLONING + LIVE HINDI SYNTHESIS
    //    AND ZERO SILENT FALLBACK WHEN CHATTERBOX SERVER IS OFFLINE
    // -----------------------------------------------------------------

    @Test
    fun `two distinct speakers clone and synthesize distinct new Hindi lines and never silently fallback when offline`() = runBlocking {
        val uploadedVoices = CopyOnWriteArrayList<String>()
        val synthRequests = CopyOnWriteArrayList<JSONObject>()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            val payload = JSONObject().apply {
                put("status", "ok")
                put("model_loaded", true)
                put("model_name", "ResembleAI/Chatterbox-Multilingual-hi")
                put("device", "cpu")
                put("ready_for_inference", true)
                put("unique_reference_voices_sha256_count", uploadedVoices.toSet().size.coerceAtLeast(2))
            }.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.createContext("/v1/voices/upload") { exchange ->
            exchange.requestBody.readBytes()
            val query = exchange.requestURI.rawQuery.orEmpty()
            val idMatch = Regex("(?:^|&)voice_id=([^&]+)").find(query)
            val voiceId = idMatch?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "unknown"
            uploadedVoices.add(voiceId)
            val payload = JSONObject().apply {
                put("voice_id", voiceId)
                put("status", "uploaded")
                put("preprocessed_sample_rate_hz", 24000)
                put("duration_seconds", 8.0)
            }.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.createContext("/v1/synthesize") { exchange ->
            val reqJson = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            synthRequests.add(reqJson)
            val refId = reqJson.optString("reference_voice_id", "")
            val text = reqJson.optString("text", "")
            // Generate distinct non-silent 24kHz WAV waveforms keyed by reference voice + text hash
            val freqHz = if (refId.contains("speaker1", ignoreCase = true)) 130.0 else 245.0
            val phaseFreq = freqHz + (text.hashCode() and 0x1F).toDouble()
            val synthFile = File(context.cacheDir, "mock_synth_${System.nanoTime()}.wav")
            writeMonoWav(synthFile, generateSinePcm(1.2f, 24000, phaseFreq, 13000.0), 24000)
            val wavBytes = synthFile.readBytes()
            synthFile.delete()

            exchange.responseHeaders.add("Content-Type", "audio/wav")
            exchange.responseHeaders.add("X-GameVox-Conditioning-Ms", "180")
            exchange.responseHeaders.add("X-GameVox-Conditioning-Cached", "false")
            exchange.responseHeaders.add("X-GameVox-Inference-Latency-Ms", "320")
            exchange.responseHeaders.add("X-GameVox-Device", "cpu")
            exchange.sendResponseHeaders(200, wavBytes.size.toLong())
            exchange.responseBody.use { it.write(wavBytes) }
        }
        server.start()
        httpServer = server

        val baseUrl = "http://127.0.0.1:${server.address.port}"
        TranslatorStateRepository.updateChatterboxServerConfig(context, baseUrl, "")

        val ttsManager = ForegroundTtsManager.getInstance(context)
        val spk1Wav = File(context.filesDir, "custom_voices/speaker1_male.wav").apply { parentFile?.mkdirs() }
        val spk2Wav = File(context.filesDir, "custom_voices/speaker2_female.wav")
        writeMonoWav(spk1Wav, generateSinePcm(6.5f, 24000, 125.0, 11000.0), 24000)
        writeMonoWav(spk2Wav, generateSinePcm(6.5f, 24000, 235.0, 11000.0), 24000)

        // 0. Consent gating test
        val noConsent = ttsManager.generateNeuralClonePreview(
            voiceName = "NoConsent",
            referenceAudioFile = spk1Wav,
            previewHindiText = "नमस्ते!",
            speakerConsentConfirmed = false,
            rawBaseUrl = baseUrl
        )
        assertFalse(noConsent.success)
        assertTrue(noConsent.errorMessage.contains("permission", ignoreCase = true))

        // 1. Generate Clone Previews for Speaker 1 and Speaker 2
        val clone1 = ttsManager.generateNeuralClonePreview(
            voiceName = "Hero Male Clone",
            referenceAudioFile = spk1Wav,
            previewHindiText = "हम यहाँ से सुरक्षित निकलेंगे।",
            exaggeration = 0.55f,
            cfgWeight = 0.45f,
            speakerConsentConfirmed = true,
            rawBaseUrl = baseUrl
        )
        val clone2 = ttsManager.generateNeuralClonePreview(
            voiceName = "Heroine Female Clone",
            referenceAudioFile = spk2Wav,
            previewHindiText = "जल्दी चलो, दरवाज़ा खुल रहा है!",
            exaggeration = 0.65f,
            cfgWeight = 0.40f,
            speakerConsentConfirmed = true,
            rawBaseUrl = baseUrl
        )

        assertTrue("Speaker 1 clone failed: ${clone1.errorMessage}", clone1.success)
        assertTrue("Speaker 2 clone failed: ${clone2.errorMessage}", clone2.success)
        assertTrue(clone1.synthesizedRms > 0.01f)
        assertTrue(clone2.synthesizedRms > 0.01f)
        assertNotEquals(clone1.referenceSha256, clone1.outputWavSha256)
        assertNotEquals(clone2.referenceSha256, clone2.outputWavSha256)
        assertNotEquals("Both cloned voices must produce distinct WAV SHA-256 hashes", clone1.outputWavSha256, clone2.outputWavSha256)
        assertEquals(2, uploadedVoices.toSet().size)

        // 2. Save both clones to Voice Library & assign to Anon and Jenny
        val savedMale = VoiceProfileRepository.addGeneratedOrImportedVoice(
            context = context,
            name = "Speaker 1 Male Clone",
            description = "Chatterbox Clone",
            gender = SpeakerGender.MALE_18,
            voiceCategory = VoiceCategory.CLONED,
            sourceModelInfo = "Chatterbox V3 Hindi",
            sampleAudioPath = spk1Wav.absolutePath,
            isNeuralCloned = true,
            referenceAudioSha256 = clone1.referenceSha256,
            generatedPreviewPath = clone1.outputWavPath,
            consentConfirmed = true
        )
        val savedFemale = VoiceProfileRepository.addGeneratedOrImportedVoice(
            context = context,
            name = "Speaker 2 Female Clone",
            description = "Chatterbox Clone",
            gender = SpeakerGender.FEMALE_18,
            voiceCategory = VoiceCategory.CLONED,
            sourceModelInfo = "Chatterbox V3 Hindi",
            sampleAudioPath = spk2Wav.absolutePath,
            isNeuralCloned = true,
            referenceAudioSha256 = clone2.referenceSha256,
            generatedPreviewPath = clone2.outputWavPath,
            consentConfirmed = true
        )
        VoiceProfileRepository.assignVoiceToCharacter(context, savedMale, "Anon")
        VoiceProfileRepository.assignVoiceToCharacter(context, savedFemale, "Jenny")

        // 3. Regenerate arbitrary new Hindi line on saved VoiceProfile
        val regenLatch = CountDownLatch(1)
        var regenResultSha = ""
        ttsManager.regenerateVoiceProfilePreview(
            voiceId = savedMale.id,
            hindiText = "सावधान! सामने दुश्मन खड़ा है!",
            playAfterGeneration = false
        ) { res ->
            if (res.success) regenResultSha = res.outputWavSha256
            regenLatch.countDown()
        }
        assertTrue(regenLatch.await(5, TimeUnit.SECONDS))
        assertTrue(regenResultSha.isNotBlank())
        assertNotEquals(clone1.outputWavSha256, regenResultSha)

        // 4. Verify live OCR route decisions for Anon & Jenny resolve to their distinct cloned reference IDs
        val anonRoute = CharacterVoiceRouter.routeLiveSubtitle(context, "[Anon]: Cover me!", "मुझे कवर दो!")
        val jennyRoute = CharacterVoiceRouter.routeLiveSubtitle(context, "[Jenny]: Moving up!", "मैं आगे बढ़ रही हूँ!")
        assertEquals(CanonicalVoiceBackend.CHATTERBOX_LOCAL_SERVER, anonRoute.canonicalBackend)
        assertEquals(CanonicalVoiceBackend.CHATTERBOX_LOCAL_SERVER, jennyRoute.canonicalBackend)
        assertNotEquals(anonRoute.characterAssignment.referenceVoiceId, jennyRoute.characterAssignment.referenceVoiceId)

        // 5. Stop Chatterbox server and assert ZERO silent fallback to System TTS for explicit cloned voice
        server.stop(0)
        httpServer = null
        val offlineLatch = CountDownLatch(1)
        var offlineOk = true
        var offlineSummary = ""
        ttsManager.synthesizeCharacterPreview(
            assignment = anonRoute.characterAssignment,
            hindiText = "क्या तुम सुन सकते हो?"
        ) { ok, engineSummary, _ ->
            offlineOk = ok
            offlineSummary = engineSummary
            offlineLatch.countDown()
        }
        assertTrue(offlineLatch.await(5, TimeUnit.SECONDS))
        assertFalse("Explicit cloned voice must NOT succeed offline via System TTS", offlineOk)
        assertEquals("CHATTERBOX_UNAVAILABLE", offlineSummary)
        val lastAudit = CharacterVoiceRouter.auditLogs.value.firstOrNull()
        assertEquals("CHATTERBOX_UNAVAILABLE", lastAudit?.actualBackend)
    }

    // -----------------------------------------------------------------
    // 4. COMPOSE UI INTEGRATION: AI VOICE CLONE TAB & VOICE LIBRARY
    // -----------------------------------------------------------------

    @Test
    fun `ai voice clone tab shows complete non-duplicated controls and voice library renders cloned card actions`() {
        val refWav = File(context.filesDir, "custom_voices/ui_clone_ref.wav").apply { parentFile?.mkdirs() }
        val synthWav = File(context.filesDir, "custom_voices/ui_clone_synth.wav")
        writeMonoWav(refWav, generateSinePcm(6.0f, 24000, 180.0, 11000.0), 24000)
        writeMonoWav(synthWav, generateSinePcm(1.5f, 24000, 195.0, 12000.0), 24000)

        val clonedVoice = VoiceProfileRepository.addGeneratedOrImportedVoice(
            context = context,
            name = "UI Cloned Heroine",
            description = "Chatterbox V3 Hindi",
            gender = SpeakerGender.FEMALE_18,
            voiceCategory = VoiceCategory.CLONED,
            sourceModelInfo = "Chatterbox V3 Hindi Clone",
            sampleAudioPath = refWav.absolutePath,
            isNeuralCloned = true,
            referenceAudioSha256 = "deadbeef01",
            generatedPreviewPath = synthWav.absolutePath,
            generatedPreviewText = "चलो चलते हैं!",
            consentConfirmed = true
        )

        val pipeline = OcrTranslationPipeline.getInstance(context)
        composeRule.setContent {
            MyApplicationTheme {
                GameVoxApp(localPipeline = pipeline)
            }
        }

        // Voice Library -> Cloned filter -> check Preview AI, Play Original Ref, New Hindi Line
        composeRule.onNodeWithTag("nav_tab_voice_library").performClick()
        composeRule.onNodeWithTag("voice_filter_cloned").performClick()
        composeRule.onNodeWithTag("voice_library_lazy_list").performScrollToNode(hasTestTag("voice_card_${clonedVoice.id}"))
        composeRule.onNodeWithTag("voice_card_${clonedVoice.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("preview_voice_${clonedVoice.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("play_ref_${clonedVoice.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("regen_hindi_btn_${clonedVoice.id}").assertIsDisplayed()

        // Navigate to Voice Generate -> AI Voice Clone tab
        composeRule.onNodeWithTag("voice_library_add_voice_button").performClick()
        composeRule.onNodeWithTag("gen_tab_import_clone").performScrollTo().performClick()

        // Single voice name & preview Hindi input (no duplicates)
        assertEquals(1, composeRule.onAllNodesWithTag("generate_voice_name_input").fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithTag("generate_voice_preview_text_input").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("post_generation_actions_card").fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("generate_voice_preview_text_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Preview Text in Hindi", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ai_clone_server_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("record_audio_sample_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("import_audio_sample_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("voice_clone_consent_checkbox").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_clone_exaggeration_slider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_clone_cfg_slider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("clone_via_chatterbox_button").performScrollTo().assertIsDisplayed()

        // Verify clicking New Hindi Line opens RegenerateClonedHindiDialog cleanly
        composeRule.onNodeWithTag("nav_tab_voice_library").performClick()
        composeRule.onNodeWithTag("voice_filter_cloned").performClick()
        composeRule.onNodeWithTag("voice_library_lazy_list").performScrollToNode(hasTestTag("voice_card_${clonedVoice.id}"))
        composeRule.onNodeWithTag("regen_hindi_btn_${clonedVoice.id}").performClick()
        composeRule.onNodeWithTag("regenerate_hindi_input").assertIsDisplayed()
        composeRule.onNodeWithTag("regenerate_hindi_confirm_button").assertIsDisplayed()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // -----------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------

    private fun generateSinePcm(durationSec: Float, sampleRate: Int, freqHz: Double, amplitude: Double): ShortArray {
        val count = (durationSec * sampleRate).toInt()
        return ShortArray(count) { i ->
            (sin(2.0 * Math.PI * freqHz * i / sampleRate) * amplitude).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun writeMonoWav(file: File, pcm: ShortArray, sampleRate: Int) {
        val dataSize = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(1)
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in pcm) buf.putShort(s)
        FileOutputStream(file).use { it.write(buf.array()) }
    }

    private fun writeStereoWav(file: File, mono: ShortArray, sampleRate: Int) {
        val dataSize = mono.size * 4
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(2)
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 4)
        buf.putShort(4)
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in mono) {
            buf.putShort(s)
            buf.putShort(s)
        }
        FileOutputStream(file).use { it.write(buf.array()) }
    }
}
