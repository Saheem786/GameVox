package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.NeuralTtsEngine
import com.example.service.SherpaSupertonicBackend
import com.example.service.SupertonicModelManager
import com.example.service.SupertonicModelState
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SherpaSupertonicBackendTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testBackendIdAndProperties() {
        val backend = SherpaSupertonicBackend(context)
        assertEquals(SherpaSupertonicBackend.BACKEND_ID, backend.backendId)
        assertEquals(10, backend.numSpeakers)
        assertFalse(backend.isReady)
    }

    @Test
    fun testModelDirectoryPath() {
        val dir = SupertonicModelManager.getModelDirectory(context)
        assertNotNull(dir)
        assertTrue(dir.path.contains("sherpa_models"))
        assertTrue(dir.path.contains("supertonic_3_hi"))
    }

    @Test
    fun testModelStatusWhenFilesAbsent() {
        // Ensure clean state
        SupertonicModelManager.uninstallModel(context)
        val status = SupertonicModelManager.checkStatus(context)
        assertTrue(
            "When model files are absent, status must be NotDownloaded",
            status is SupertonicModelState.NotDownloaded
        )
        assertFalse(SupertonicModelManager.isModelReady(context))
    }

    @Test
    fun testModelMetadataProperties() {
        val info = SupertonicModelManager.modelInfo
        assertEquals("Supertonic 3 Multilingual (Hindi)", info.name)
        assertEquals(10, info.baseSpeakerCount)
        assertEquals(24000, info.sampleRateHz)
        assertTrue(info.license.contains("Apache 2.0") || info.license.contains("Free"))
        assertTrue(info.primaryDownloadUrl.startsWith("https://"))
    }

    @Test
    fun testOfflineTtsApiContract() {
        val config = OfflineTtsConfig()
        val tts = OfflineTts(config = config)
        assertEquals(24000, tts.sampleRate())
        assertEquals(10, tts.numSpeakers())

        val genAudio = tts.generate("नमस्ते", sid = 1, speed = 1.0f)
        assertNotNull(genAudio)
        assertEquals(24000, genAudio.sampleRate)
        assertTrue(genAudio.samples.isNotEmpty())

        tts.release()
    }

    @Test
    fun testGenerationConfigHindiAndNumSteps() {
        val defaultConfig = GenerationConfig(sid = 1, speed = 1.0f)
        assertEquals("hi", defaultConfig.extra?.get("lang"))
        assertEquals(5, defaultConfig.numSteps)

        val customStepsConfig = GenerationConfig(
            sid = 2,
            speed = 1.1f,
            numSteps = 8,
            extra = mapOf("lang" to "hi")
        )
        assertEquals(8, customStepsConfig.numSteps)
        assertEquals("hi", customStepsConfig.extra?.get("lang"))
        assertEquals(2, customStepsConfig.sid)
    }

    @Test
    fun testExpectedSha256HashesCoverage() {
        val hashes = SupertonicModelManager.EXPECTED_SHA256_HASHES
        assertEquals(7, hashes.size)
        for (file in SupertonicModelManager.REQUIRED_FILES) {
            val hash = hashes[file]
            assertNotNull("Missing trusted expected hash for $file", hash)
            assertEquals("Hash for $file must be 64-char hex", 64, hash?.length)
        }
    }

    @Test
    fun testStepBenchmarkExecution() {
        val backend = SherpaSupertonicBackend(context) { cfg ->
            OfflineTts(config = cfg)
        }
        val results = backend.benchmarkSteps("नमस्ते गेमर", listOf(5, 8))
        assertEquals(2, results.size)
        assertEquals(5, results[0].numSteps)
        assertEquals(8, results[1].numSteps)
        assertTrue(results[0].sampleCount > 0)
        assertTrue(results[1].sampleCount > 0)
    }

    @Test
    fun testDynamicCharacterDiscoveryPersistence() {
        com.example.data.VoiceProfileRepository.initialize(context)
        val discoveredName = "General Raiden"
        val assignment = com.example.data.VoiceProfileRepository.getAssignmentForCharacter(discoveredName, context)
        assertNotNull(assignment)
        assertEquals("general raiden", assignment.characterName)
        assertTrue(assignment.speakerId in 0..9)

        // Lookup again should return the exact same persisted assignment
        val retrieved = com.example.data.VoiceProfileRepository.getAssignmentForCharacter(discoveredName, context)
        assertEquals(assignment.speakerId, retrieved.speakerId)
        assertEquals(assignment.displayName, retrieved.displayName)
    }

    @Test
    fun testJniDiagnosticsReported() {
        val summary = OfflineTts.jniStatusSummary
        assertNotNull(summary)
        assertTrue(summary.contains("SUCCESS") || summary.contains("NOT TESTED") || summary.contains("FAILED"))
    }

    @Test
    fun testBackendEpochCancellation() = runBlocking {
        val backend = SherpaSupertonicBackend(context)
        // Call stop() and verify it doesn't throw
        backend.stop()
        backend.release()
    }
}
