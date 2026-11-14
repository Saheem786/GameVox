package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.SherpaSupertonicBackend
import com.example.service.SupertonicModelManager
import com.example.service.SupertonicModelState
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        SupertonicModelManager.overrideModelDirectory = null
    }

    @After
    fun tearDown() {
        SupertonicModelManager.overrideModelDirectory = null
    }

    @Test
    fun testBackendIdAndUninitializedProperties() {
        SupertonicModelManager.uninstallModel(context)
        val backend = SherpaSupertonicBackend(context)
        assertEquals(SherpaSupertonicBackend.BACKEND_ID, backend.backendId)
        assertEquals(10, backend.numSpeakers)
        assertEquals(44100, backend.sampleRate)
        assertFalse(backend.isReady)
        assertFalse(backend.isModelRuntimeInitialized)
        assertTrue(backend.currentBackendLabel.startsWith("SYSTEM_FALLBACK"))
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
        SupertonicModelManager.uninstallModel(context)
        val status = SupertonicModelManager.checkStatus(context)
        assertTrue(
            "When model files are absent, status must be NotDownloaded",
            status is SupertonicModelState.NotDownloaded
        )
        assertFalse(SupertonicModelManager.isModelReady(context))
    }

    @Test
    fun testCorruptedModelFileRejectedBySha256Check() {
        val tempDir = File(context.cacheDir, "corrupted_supertonic_test")
        tempDir.deleteRecursively()
        tempDir.mkdirs()
        try {
            for (fileName in SupertonicModelManager.REQUIRED_FILES) {
                File(tempDir, fileName).writeBytes(ByteArray(128) { 0x42 })
            }
            SupertonicModelManager.overrideModelDirectory = tempDir
            val status = SupertonicModelManager.checkStatus(context)
            assertTrue(
                "Corrupted model files with mismatched SHA-256 must be rejected with Error state, got: $status",
                status is SupertonicModelState.Error
            )
            assertFalse(
                "isModelReady must return false when SHA-256 verification fails",
                SupertonicModelManager.isModelReady(context)
            )
        } finally {
            tempDir.deleteRecursively()
            SupertonicModelManager.overrideModelDirectory = null
        }
    }

    @Test
    fun testModelMetadataPropertiesAndOpenRailLicense() {
        val info = SupertonicModelManager.modelInfo
        assertEquals("Supertonic 3 Multilingual (Hindi)", info.name)
        assertEquals(10, info.baseSpeakerCount)
        assertEquals(44100, info.sampleRateHz)
        assertTrue(
            "Model license must specify OpenRAIL-M for Supertonic weights, got: ${info.license}",
            info.license.contains("OpenRAIL-M")
        )
        assertTrue(info.primaryDownloadUrl.startsWith("https://"))
    }

    @Test
    fun testSilentWaveformRejectedByRmsCheck() {
        val silentSamples = FloatArray(44100) { 0f }
        val silentRms = SherpaSupertonicBackend.computeWaveformRms(silentSamples)
        assertEquals(0f, silentRms, 1e-7f)
        assertFalse(
            "All-zero silent FloatArray must be rejected by isNonSilentWaveform",
            SherpaSupertonicBackend.isNonSilentWaveform(silentSamples)
        )

        val nonSilentWave = FloatArray(44100) { i ->
            (0.25 * sin(2.0 * Math.PI * 440.0 * i / 44100.0)).toFloat()
        }
        val activeRms = SherpaSupertonicBackend.computeWaveformRms(nonSilentWave)
        assertTrue("Sine wave RMS must exceed threshold, got: $activeRms", activeRms > 0.1f)
        assertTrue(SherpaSupertonicBackend.isNonSilentWaveform(nonSilentWave))
    }

    @Test
    fun testOfflineTtsFailsExplicitlyWithoutSilentFallbackWhenModelMissing() {
        try {
            val emptyConfig = OfflineTtsConfig()
            val tts = OfflineTts(config = emptyConfig)
            tts.release()
            fail("OfflineTts with empty model paths must fail explicitly and never return silent stub audio")
        } catch (e: RuntimeException) {
            assertTrue(
                "Expected explicit native initialization error, got: ${e.message}",
                e.message.orEmpty().contains("OfflineTts")
            )
        }
    }

    @Test
    fun testGenerationConfigHindiAndNumSteps() {
        val defaultConfig = GenerationConfig(sid = 1, speed = 1.0f)
        assertEquals("hi", defaultConfig.extra?.get("lang"))
        assertEquals(5, defaultConfig.numSteps)

        val customStepsConfig = GenerationConfig(
            sid = 5,
            speed = 1.05f,
            numSteps = 8,
            extra = mapOf("lang" to "hi")
        )
        assertEquals(8, customStepsConfig.numSteps)
        assertEquals("hi", customStepsConfig.extra?.get("lang"))
        assertEquals(5, customStepsConfig.sid)
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
    fun testRealNativeSupertonicHindiSynthesisAndWavExport() {
        val realModelDir = File("/tmp/supertonic_test")
        assertTrue(
            "Real Supertonic 3 model directory must exist at /tmp/supertonic_test for integration verification",
            realModelDir.exists() && realModelDir.isDirectory
        )
        SupertonicModelManager.overrideModelDirectory = realModelDir

        // 1. Verify strict SHA-256 validation succeeds on the real downloaded 7 model files
        val modelStatus = SupertonicModelManager.checkStatus(context)
        assertTrue(
            "Real Supertonic 3 model files must pass SHA-256 verification, got: $modelStatus",
            modelStatus is SupertonicModelState.Ready
        )
        assertTrue(SupertonicModelManager.isModelReady(context))

        // 2. Initialize real native Sherpa-ONNX backend via JNI
        val backend = SherpaSupertonicBackend(context)
        val initialized = backend.initialize()
        assertTrue(
            "Native SherpaSupertonicBackend.initialize() must succeed (initializationError=${backend.initializationError})",
            initialized
        )
        assertTrue(backend.isReady)
        assertTrue(backend.isModelRuntimeInitialized)
        assertEquals(44100, backend.sampleRate)
        assertEquals(10, backend.numSpeakers)

        val wavOutDir = File("build/wav_samples")
        wavOutDir.mkdirs()

        // 3. Synthesize Utterance 1: Anon (Young Male M1, SID = 5)
        val wavSid5 = File(wavOutDir, "sid5_anon_hi.wav")
        val resSid5 = backend.synthesizeAndValidateToWav(
            text = "नमस्ते दोस्त, चलो मिशन शुरू करते हैं।",
            sid = 5,
            speed = 1.0f,
            numSteps = 5,
            outputWavFile = wavSid5
        )
        println(
            "REAL_SYNTHESIS_SID5: sr=${resSid5.sampleRate}, " +
                "samples=${resSid5.sampleCount}, chunks=${resSid5.callbackChunkCount}, " +
                "firstChunkMs=${resSid5.firstChunkLatencyMs}, totalMs=${resSid5.totalSynthesisTimeMs}, " +
                "rms=${resSid5.waveformRms}, rtf=${resSid5.realTimeFactor}, wavBytes=${wavSid5.length()}"
        )
        assertEquals(44100, resSid5.sampleRate)
        assertTrue("SID 5 sample count must be > 44100", resSid5.sampleCount > 44100)
        assertTrue("SID 5 native callback must fire at least once", resSid5.callbackChunkCount >= 1)
        assertTrue("SID 5 waveform RMS must be non-silent (> 0.01f), got ${resSid5.waveformRms}", resSid5.waveformRms > 0.01f)
        assertTrue("SID 5 exported WAV file must exist and be > 88KB", wavSid5.exists() && wavSid5.length() > 88200L)

        // 4. Synthesize Utterance 2: Jenny (Young Female F2, SID = 1)
        val wavSid1 = File(wavOutDir, "sid1_jenny_hi.wav")
        val resSid1 = backend.synthesizeAndValidateToWav(
            text = "सावधान रहो, आगे दुश्मन हो सकते हैं।",
            sid = 1,
            speed = 1.0f,
            numSteps = 5,
            outputWavFile = wavSid1
        )
        println(
            "REAL_SYNTHESIS_SID1: sr=${resSid1.sampleRate}, " +
                "samples=${resSid1.sampleCount}, chunks=${resSid1.callbackChunkCount}, " +
                "firstChunkMs=${resSid1.firstChunkLatencyMs}, totalMs=${resSid1.totalSynthesisTimeMs}, " +
                "rms=${resSid1.waveformRms}, rtf=${resSid1.realTimeFactor}, wavBytes=${wavSid1.length()}"
        )
        assertEquals(44100, resSid1.sampleRate)
        assertTrue("SID 1 sample count must be > 44100", resSid1.sampleCount > 44100)
        assertTrue("SID 1 native callback must fire at least once", resSid1.callbackChunkCount >= 1)
        assertTrue("SID 1 waveform RMS must be non-silent (> 0.01f), got ${resSid1.waveformRms}", resSid1.waveformRms > 0.01f)
        assertTrue("SID 1 exported WAV file must exist and be > 88KB", wavSid1.exists() && wavSid1.length() > 88200L)

        // 5. Run real 5 vs 8 ODE steps benchmark on the real native model
        val benchResults = backend.benchmarkSteps(
            text = "नमस्ते गेमर, क्या हाल है?",
            stepsList = listOf(5, 8)
        )
        assertEquals(2, benchResults.size)
        for (b in benchResults) {
            println(
                "REAL_BENCHMARK_STEPS_${b.numSteps}: firstChunkMs=${b.firstChunkLatencyMs}, " +
                    "totalGenMs=${b.generationTimeMs}, audioDurMs=${b.audioDurationMs}, " +
                    "rtf=${b.realTimeFactor}, rms=${b.waveformRms}, samples=${b.sampleCount}"
            )
            assertTrue(b.sampleCount > 22050)
            assertTrue(b.generationTimeMs > 0L)
            assertTrue(b.firstChunkLatencyMs > 0L)
            assertTrue(b.waveformRms > 0.01f)
        }

        backend.release()
    }

    @Test
    fun testArm64JniLibrariesPackagedAndValidElf() {
        val candidates = listOf(
            File("src/main/jniLibs/arm64-v8a"),
            File("app/src/main/jniLibs/arm64-v8a")
        )
        val jniDir = candidates.firstOrNull { it.exists() && it.isDirectory }
        assertNotNull("arm64-v8a jniLibs directory must exist in project", jniDir)

        val requiredLibs = listOf(
            "libsherpa-onnx-jni.so",
            "libonnxruntime.so",
            "libsherpa-onnx-c-api.so",
            "libsherpa-onnx-cxx-api.so"
        )
        for (libName in requiredLibs) {
            val soFile = File(jniDir!!, libName)
            assertTrue("Missing required native library: ${soFile.path}", soFile.exists() && soFile.length() > 100_000L)
            RandomAccessFile(soFile, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                assertEquals("ELF magic 0x7F for $libName", 0x7F.toByte(), magic[0])
                assertEquals("ELF 'E' for $libName", 'E'.code.toByte(), magic[1])
                assertEquals("ELF 'L' for $libName", 'L'.code.toByte(), magic[2])
                assertEquals("ELF 'F' for $libName", 'F'.code.toByte(), magic[3])
                // ELF class 2 = 64-bit
                val elfClass = raf.readByte().toInt() and 0xFF
                assertEquals("$libName must be 64-bit ELF (ELFCLASS64)", 2, elfClass)
                // Offset 18 (0x12) = e_machine (little-endian uint16), 0xB7 (183) = EM_AARCH64
                raf.seek(18L)
                val lo = raf.readByte().toInt() and 0xFF
                val hi = raf.readByte().toInt() and 0xFF
                val eMachine = lo or (hi shl 8)
                assertEquals("$libName must target arm64-v8a (EM_AARCH64 = 183)", 183, eMachine)
            }
        }

        // If debug APK is already built, also verify Zip entries inside app-debug.apk
        val apkCandidates = listOf(
            File("build/outputs/apk/debug/app-debug.apk"),
            File("app/build/outputs/apk/debug/app-debug.apk")
        )
        val builtApk = apkCandidates.firstOrNull { it.exists() && it.isFile }
        if (builtApk != null) {
            ZipFile(builtApk).use { zip ->
                for (libName in requiredLibs) {
                    val entryPath = "lib/arm64-v8a/$libName"
                    val entry = zip.getEntry(entryPath)
                    assertNotNull("APK must package $entryPath", entry)
                    assertTrue("APK entry $entryPath must be non-empty", entry!!.size > 100_000L)
                }
            }
        }
    }

    @Test
    fun testDynamicCharacterDiscoveryPersistence() {
        com.example.data.VoiceProfileRepository.initialize(context)
        val discoveredName = "General Raiden"
        val assignment = com.example.data.VoiceProfileRepository.getAssignmentForCharacter(discoveredName, context)
        assertNotNull(assignment)
        assertEquals("general raiden", assignment.characterName)
        assertTrue(assignment.speakerId in 0..9)

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
        backend.stop()
        backend.release()
    }
}
