package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CharacterVoiceAssignment
import com.example.data.VoiceProfileRepository
import com.example.engine.EmotionSpeechEngine
import com.example.engine.HindiTranslationEngine
import com.example.engine.SpeakerHeuristicEngine
import com.example.engine.SpeechEmotion
import com.example.service.FreeVoiceBackend
import com.example.service.NeuralSynthesisOutcome
import com.example.service.NeuralSynthesizeRequest
import com.example.service.NeuralVoiceClient
import com.example.ui.FIVE_LINE_CONSISTENCY_SUITE
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class NeuralVoiceClientAndAbTest {

    private lateinit var context: Context
    private var httpServer: HttpServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        VoiceProfileRepository.initialize(context)
        VoiceProfileRepository.restoreAllCharacterDefaults(context)
    }

    @After
    fun tearDown() {
        httpServer?.stop(0)
        httpServer = null
    }

    @Test
    fun testPhase3ModernConversationalHindiTranslations() {
        assertEquals(
            "यहाँ से निकलो, जल्दी!",
            HindiTranslationEngine.translateOfflinePhrasebook("Get out of here!")
        )
        assertEquals(
            "मुझे तुम्हारी कितनी फिक्र हो रही थी!",
            HindiTranslationEngine.translateOfflinePhrasebook("I was so worried about you.")
        )
        assertEquals(
            "तुम्हें लगता है तुम मुझे हरा दोगे?",
            HindiTranslationEngine.translateOfflinePhrasebook("You think you can beat me?")
        )
    }

    @Test
    fun testPhase3EightEmotionCategoriesAndChatterboxConditioning() {
        val emotions = SpeechEmotion.entries
        assertEquals("Must support all 8 emotion categories", 8, emotions.size)
        val names = emotions.map { it.name }.toSet()
        assertTrue(
            names.containsAll(
                listOf("NEUTRAL", "HAPPY", "SAD", "ANGRY", "FEAR", "WHISPER", "EXCITED", "SERIOUS")
            )
        )

        val strippedJenny = SpeakerHeuristicEngine.stripLeadingCharacterName("Jenny: I was so worried about you.")
        val fearAnalysis = EmotionSpeechEngine.analyzeEmotionAndBuildSsml(
            spokenText = "मुझे तुम्हारी कितनी फिक्र हो रही थी!",
            sourceEnglishText = strippedJenny
        )
        assertEquals(SpeechEmotion.FEAR, fearAnalysis.emotion)
        assertFalse(
            "Spoken text must never contain speaker name prefix 'Jenny:'",
            fearAnalysis.cleanSpokenText.contains("Jenny", ignoreCase = true)
        )

        val strippedAnon = SpeakerHeuristicEngine.stripLeadingCharacterName("Anon: Get out of here!")
        val angryAnalysis = EmotionSpeechEngine.analyzeEmotionAndBuildSsml(
            spokenText = "यहाँ से निकलो, जल्दी!",
            sourceEnglishText = strippedAnon
        )
        assertTrue(
            angryAnalysis.emotion == SpeechEmotion.ANGRY || angryAnalysis.emotion == SpeechEmotion.EXCITED
        )
        assertFalse(
            "Spoken text must never contain speaker name prefix 'Anon:'",
            angryAnalysis.cleanSpokenText.contains("Anon", ignoreCase = true)
        )

        val whisperAnalysis = EmotionSpeechEngine.analyzeEmotionAndBuildSsml(
            spokenText = "[whisper] चुप रहो, अपनी आवाज़ धीमी रखो...",
            sourceEnglishText = "[whisper] Quiet, keep your voice down..."
        )
        assertEquals(SpeechEmotion.WHISPER, whisperAnalysis.emotion)
        assertTrue(SpeechEmotion.ANGRY.chatterboxExaggeration > SpeechEmotion.NEUTRAL.chatterboxExaggeration)
        assertTrue(SpeechEmotion.WHISPER.chatterboxExaggeration < SpeechEmotion.NEUTRAL.chatterboxExaggeration)
    }

    @Test
    fun testPhase2ReferenceVoiceClipsDistinctSha256AndNonSilent() {
        val serverRefDir = File("../chatterbox_voice_server/reference_voices").let {
            if (it.exists()) it else File("chatterbox_voice_server/reference_voices")
        }
        assertTrue("chatterbox_voice_server/reference_voices must exist", serverRefDir.exists())

        val anonWav = File(serverRefDir, "anon_male_ref.wav")
        val jennyWav = File(serverRefDir, "jenny_female_ref.wav")
        assertTrue("anon_male_ref.wav must exist and be > 50KB", anonWav.exists() && anonWav.length() > 50_000L)
        assertTrue("jenny_female_ref.wav must exist and be > 50KB", jennyWav.exists() && jennyWav.length() > 50_000L)

        val anonSha = sha256Hex(anonWav.readBytes())
        val jennySha = sha256Hex(jennyWav.readBytes())
        assertNotEquals("Male and female reference voice WAVs must not be identical", anonSha, jennySha)
        assertEquals(CharacterVoiceAssignment.ANON_MALE_REF_SHA256, anonSha)
        assertEquals(CharacterVoiceAssignment.JENNY_FEMALE_REF_SHA256, jennySha)

        val parsedAnon = NeuralVoiceClient.parseWavBytes(anonWav.readBytes())
        val parsedJenny = NeuralVoiceClient.parseWavBytes(jennyWav.readBytes())
        assertNotNull(parsedAnon)
        assertNotNull(parsedJenny)
        assertTrue("anon_male_ref.wav must have non-silent RMS > 0.01", parsedAnon!!.waveformRms > 0.01f)
        assertTrue("jenny_female_ref.wav must have non-silent RMS > 0.01", parsedJenny!!.waveformRms > 0.01f)
    }

    @Test
    fun testPhase2HonestUniqueReferenceVoiceCountAndProfilePersistence() {
        val presets = CharacterVoiceAssignment.DEFAULT_PRESETS
        assertTrue("Must provide 20+ character profiles", presets.size >= 20)

        val uniqueCount = CharacterVoiceAssignment.countUniqueReferenceVoices(presets)
        assertEquals(
            "Must honestly report 2 unique bundled reference WAVs across the 24 default profiles",
            2,
            uniqueCount
        )

        val (unique, total, summary) = VoiceProfileRepository.getUniqueReferenceVoiceSummary()
        assertEquals(2, unique)
        assertTrue(total >= 20)
        assertTrue(
            "Summary must disclose 2 unique reference voice clips across $total profiles, got: $summary",
            summary.contains("2 unique reference voice clips")
        )

        val anon = VoiceProfileRepository.getAssignmentForCharacter("Anon", context)
        val jenny = VoiceProfileRepository.getAssignmentForCharacter("Jenny", context)
        assertEquals("male", anon.voicePresentation)
        assertEquals("anon_male_ref", anon.referenceVoiceId)
        assertEquals("female", jenny.voicePresentation)
        assertEquals("jenny_female_ref", jenny.referenceVoiceId)

        // Update profile with custom emotion & pronunciation override and verify persistence
        val updated = VoiceProfileRepository.setUserOverrideForCharacter(
            context = context,
            characterName = "Anon",
            sid = anon.speakerId,
            defaultEmotion = "ANGRY",
            exaggeration = 0.82f,
            cfgWeight = 0.38f,
            pronunciationOverrides = mapOf("GameVox" to "गेमवॉक्स")
        )
        assertEquals("ANGRY", updated.defaultEmotion)
        assertEquals(0.82f, updated.exaggeration, 1e-4f)
        assertEquals("गेमवॉक्स", updated.pronunciationOverrides["GameVox"])
    }

    @Test
    fun testPhase4UserVisibleDubbingModes() {
        assertEquals(
            "Human Voice — Local Neural Server",
            FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER.displayLabel
        )
        assertEquals(
            "Fast Offline Voice — Supertonic",
            FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT.displayLabel
        )
        assertEquals(
            "Pause Dubbing",
            FreeVoiceBackend.PAUSE_DUBBING.displayLabel
        )
    }

    @Test
    fun testPhase4And5NeuralVoiceClientLoopbackServerAuthSynthesisAndCancel() = runBlocking {
        val lastReceivedBody = AtomicReference("")
        val lastCancelledEpoch = AtomicReference(-1L)
        val validToken = "unit-test-secret-token"

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer = server
        val port = server.address.port

        server.createContext("/v1/health") { exchange ->
            val token = exchange.requestHeaders.getFirst("X-GameVox-Token")
            if (token != validToken) {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            val json = """
                {
                  "status": "ok",
                  "engine": "chatterbox_multilingual_v3_hi",
                  "model_id": "ResembleAI/Chatterbox-Multilingual-hi",
                  "device": "cuda",
                  "model_loaded": true,
                  "sample_rate": 24000,
                  "reference_voice_files_count": 2,
                  "unique_reference_voices_sha256_count": 2,
                  "conditionals_cached_count": 2
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, json.size.toLong())
            exchange.responseBody.use { it.write(json) }
        }

        server.createContext("/v1/synthesize") { exchange ->
            val token = exchange.requestHeaders.getFirst("X-GameVox-Token")
            if (token != validToken) {
                val err = """{"detail":"Invalid token"}""".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(401, err.size.toLong())
                exchange.responseBody.use { it.write(err) }
                return@createContext
            }
            val bodyStr = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
            lastReceivedBody.set(bodyStr)

            val returnSilent = bodyStr.contains("SILENT_TEST")
            val wavBytes = buildPcm16WavBytes(sampleRate = 24000, durationMs = 250, silent = returnSilent)

            exchange.responseHeaders.add("Content-Type", "audio/wav")
            exchange.responseHeaders.add("X-GameVox-Inference-Ms", "142")
            exchange.responseHeaders.add("X-GameVox-Conditioning-Ms", "4")
            exchange.responseHeaders.add("X-GameVox-Conditioning-Cached", "true")
            exchange.responseHeaders.add("X-GameVox-Sample-Rate", "24000")
            exchange.responseHeaders.add("X-GameVox-Audio-Rms", if (returnSilent) "0.0" else "0.1768")
            exchange.responseHeaders.add("X-GameVox-Voice-Sha256", CharacterVoiceAssignment.ANON_MALE_REF_SHA256)
            exchange.sendResponseHeaders(200, wavBytes.size.toLong())
            exchange.responseBody.use { it.write(wavBytes) }
        }

        server.createContext("/v1/cancel") { exchange ->
            val bodyStr = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
            val match = Regex(""""min_valid_epoch"\s*:\s*(\d+)""").find(bodyStr)
            if (match != null) {
                lastCancelledEpoch.set(match.groupValues[1].toLong())
            }
            val resp = """{"status":"cancelled"}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }

        server.start()
        val client = NeuralVoiceClient()
        val baseUrl = "http://127.0.0.1:$port"

        // 1. Verify wrong token is rejected with HTTP 401
        val badHealth = client.checkServerHealth(baseUrl, "wrong-token")
        assertFalse(badHealth.reachable && badHealth.modelLoaded)

        // 2. Verify valid token health check succeeds
        val goodHealth = client.checkServerHealth(baseUrl, validToken)
        assertTrue(goodHealth.reachable)
        assertTrue(goodHealth.modelLoaded)
        assertEquals("cuda", goodHealth.device)
        assertEquals(2, goodHealth.uniqueReferenceVoicesSha256Count)

        // 3. Verify synthesis strips speaker prefix and parses non-silent WAV + telemetry headers
        val synthOutcome = client.synthesizeHindiWav(
            rawBaseUrl = baseUrl,
            authToken = validToken,
            request = NeuralSynthesizeRequest(
                text = "Anon: यहाँ से निकलो, जल्दी!",
                characterId = "anon",
                referenceVoiceId = "anon_male_ref",
                emotion = "angry",
                exaggeration = 0.85f,
                cfgWeight = 0.25f,
                epoch = 7L
            )
        )
        assertTrue("Expected synthesis success, got: $synthOutcome", synthOutcome is NeuralSynthesisOutcome.Success)
        val payload = synthOutcome as NeuralSynthesisOutcome.Success
        assertFalse(
            "Speaker prefix 'Anon:' must be stripped before sending to server, got: ${lastReceivedBody.get()}",
            lastReceivedBody.get().contains("Anon:")
        )
        assertEquals(24000, payload.parsedWav.sampleRate)
        assertEquals(142L, payload.serverInferenceLatencyMs)
        assertEquals(4L, payload.conditioningLatencyMs)
        assertTrue(payload.conditioningCached)
        assertTrue(payload.parsedWav.waveformRms > 0.05f)
        assertEquals(CharacterVoiceAssignment.ANON_MALE_REF_SHA256, payload.voiceSha256)

        // 4. Verify silent WAV is strictly rejected by NeuralVoiceClient
        val silentOutcome = client.synthesizeHindiWav(
            rawBaseUrl = baseUrl,
            authToken = validToken,
            request = NeuralSynthesizeRequest(
                text = "SILENT_TEST",
                characterId = "anon",
                referenceVoiceId = "anon_male_ref"
            )
        )
        assertTrue("Silent WAV must be rejected as NeuralSynthesisOutcome.Error", silentOutcome is NeuralSynthesisOutcome.Error)

        // 5. Verify /v1/cancel sends epoch cancellation
        val cancelled = client.cancelStaleSynthesis(baseUrl, validToken, minValidEpoch = 42L)
        assertTrue(cancelled)
        assertEquals(42L, lastCancelledEpoch.get())
    }

    @Test
    fun testPhase6FiveLineCharacterConsistencySuite() {
        assertEquals(5, FIVE_LINE_CONSISTENCY_SUITE.size)
        for (line in FIVE_LINE_CONSISTENCY_SUITE) {
            val stripped = SpeakerHeuristicEngine.stripLeadingCharacterName(line.rawEnglishSubtitle)
            assertFalse(
                "Stripped line must not start with speaker prefix: $stripped",
                stripped.startsWith(line.characterKey, ignoreCase = true)
            )
            val translated = HindiTranslationEngine.translateOfflinePhrasebook(stripped)
            assertNotNull("Translated Hindi must not be null for: $stripped", translated)
            assertTrue("Translated Hindi must contain Devanagari characters: $translated", translated!!.any { it in '\u0900'..'\u097F' })
            val profile = VoiceProfileRepository.getAssignmentForCharacter(line.characterKey, context)
            assertNotNull(profile)
            if (line.characterKey == "jenny") {
                assertEquals("female", profile.voicePresentation)
                assertEquals("jenny_female_ref", profile.referenceVoiceId)
            } else {
                assertEquals("male", profile.voicePresentation)
                assertEquals("anon_male_ref", profile.referenceVoiceId)
            }
        }
    }

    @Test
    fun testHindiExoPlayerSentenceSplittingAndEllipsisPreservation() {
        val multiSentence = "रुको... उधर मत जाओ! वहाँ आगे खतरा है। क्या तुम तैयार हो?"
        val sentences = com.example.service.HindiExoPlayerManager.splitIntoSentences(multiSentence)
        assertEquals(3, sentences.size)
        assertEquals("रुको... उधर मत जाओ!", sentences[0])
        assertEquals("वहाँ आगे खतरा है।", sentences[1])
        assertEquals("क्या तुम तैयार हो?", sentences[2])
    }

    @Test
    fun testHindiExoPlayerSequentialSentenceTransitionsAndTempFileCleanup() = runBlocking {
        val exoManager = com.example.service.HindiExoPlayerManager(
            context = context,
            mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        try {
            val wav1 = buildPcm16WavBytes(sampleRate = 24000, durationMs = 120, silent = false)
            val wav2 = buildPcm16WavBytes(sampleRate = 24000, durationMs = 120, silent = false)

            val stagedFile = exoManager.stageWavBytesToTempFile(wav1, "unit_stage_check")
            assertTrue("Staged WAV file must exist before cleanup", stagedFile.exists())
            assertTrue(stagedFile.delete())

            val completedSentences = mutableListOf<String>()
            var batchFinished = false

            val ok = exoManager.playSentenceBatch(
                sentences = listOf(
                    "यहाँ से निकलो, जल्दी!" to wav1,
                    "मुझे तुम्हारी कितनी फिक्र हो रही थी!" to wav2
                ),
                epoch = 99L,
                onSentenceCompleted = { _, text ->
                    completedSentences.add(text)
                },
                onAllCompleted = {
                    batchFinished = true
                }
            )

            assertTrue("Expected multi-sentence ExoPlayer batch to complete", ok)
            assertTrue("Expected onAllCompleted callback", batchFinished)
            assertEquals(2, completedSentences.size)

            // Verify silent audio stream is rejected and cleaned up
            val silentWav = buildPcm16WavBytes(sampleRate = 24000, durationMs = 100, silent = true)
            val silentOk = exoManager.playWavBytesStream(
                wavBytes = silentWav,
                sentenceText = "Silent stream",
                epoch = 100L
            )
            assertFalse("Silent WAV stream must be rejected by HindiExoPlayerManager", silentOk)
        } finally {
            exoManager.release()
            assertFalse(
                "ExoPlayer must be deallocated after release()",
                exoManager.telemetry.value.isPlayerAllocated
            )
            val streamDir = File(context.cacheDir, "exo_audio_streams")
            val leftoverFiles = streamDir.listFiles()?.filter {
                it.name.startsWith(com.example.service.HindiExoPlayerManager.TEMP_WAV_PREFIX)
            }.orEmpty()
            assertTrue("All temporary ExoPlayer WAV files must be cleaned up on release()", leftoverFiles.isEmpty())
        }
    }

    @Test
    fun testVideoFrameCaptureMlKitOcrToTranslationPipeline() = runBlocking {
        val pipeline = com.example.engine.OcrTranslationPipeline.getInstance(context)
        val frameBitmap = android.graphics.Bitmap.createBitmap(1280, 720, android.graphics.Bitmap.Config.ARGB_8888)
        frameBitmap.eraseColor(android.graphics.Color.rgb(24, 28, 36))

        val testImageProcessor = com.example.engine.ImageProcessor(
            customBlockRecognitionOverride = { _, _, _ ->
                listOf(
                    com.example.engine.DetectedOverlayBlock(
                        text = "HP 100/100\nJenny: I was so worried about you.",
                        boundingBox = android.graphics.Rect(100, 560, 1180, 680),
                        isSubtitleRegion = true
                    )
                )
            }
        )

        val result = pipeline.captureAndTranslateVideoFrame(
            frameBitmap = frameBitmap,
            rotationDegrees = 0,
            targetRegionScreenRect = android.graphics.Rect(100, 540, 1180, 690),
            rawScreenWidth = 1280,
            rawScreenHeight = 720,
            skipIfRegionUnchanged = false,
            captureLatencyMs = 8L,
            customImageProcessor = testImageProcessor
        )

        if (!frameBitmap.isRecycled) {
            frameBitmap.recycle()
        }

        assertFalse("HUD noise 'HP 100/100' must be filtered out", result.extractedEnglish.contains("HP 100/100"))
        assertEquals("I was so worried about you.", result.spokenDialogue)
        assertTrue(
            "Expected speakerName to start with Jenny, got: ${result.speakerName}",
            result.speakerName.orEmpty().startsWith("Jenny", ignoreCase = true)
        )
        assertEquals("मुझे तुम्हारी कितनी फिक्र हो रही थी!", result.translatedHindi)
        assertEquals(
            "मुझे तुम्हारी कितनी फिक्र हो रही थी!",
            com.example.data.TranslatorStateRepository.state.value.lastTranslatedHindi
        )
    }

    private fun buildPcm16WavBytes(sampleRate: Int, durationMs: Int, silent: Boolean): ByteArray {
        val numSamples = (sampleRate * durationMs) / 1000
        val pcmBytes = ByteArray(numSamples * 2)
        val pb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val sample: Short = if (silent) {
                0
            } else {
                (sin(2.0 * Math.PI * 220.0 * i / sampleRate) * 8000.0).toInt().toShort()
            }
            pb.putShort(sample)
        }
        val out = ByteArrayOutputStream(44 + pcmBytes.size)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcmBytes.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmBytes.size)
        out.write(header.array())
        out.write(pcmBytes)
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
