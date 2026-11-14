package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SpeakerGender
import com.example.data.TargetLanguage
import com.example.data.TranslatorStateRepository
import com.example.service.ForegroundTtsManager
import com.example.service.FreeVoiceBackend
import com.example.service.KokoroMediaPipeTtsEngine
import com.example.service.KokoroSpeakOutcome
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KokoroMediaPipeTtsEngineTest {

    private lateinit var context: Context
    private lateinit var kokoroModelsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KokoroMediaPipeTtsEngine.resetInstanceForTesting()
        kokoroModelsDir = File(context.filesDir, "kokoro_models")
        kokoroModelsDir.deleteRecursively()
        TranslatorStateRepository.updateKokoroConfig(
            context = context,
            modelPath = "",
            useGpu = true,
            statusMessage = "Reset"
        )
        TranslatorStateRepository.updateFreeVoiceBackend(
            context = context,
            backend = FreeVoiceBackend.ANDROID_USER_SELECTED
        )
    }

    @After
    fun tearDown() {
        KokoroMediaPipeTtsEngine.resetInstanceForTesting()
        kokoroModelsDir.deleteRecursively()
        TranslatorStateRepository.updateFreeVoiceBackend(
            context = context,
            backend = FreeVoiceBackend.ANDROID_USER_SELECTED
        )
    }

    @Test
    fun `discoverModelFile returns null cleanly when no litertlm model exists and resolves default file when present`() {
        val engine = KokoroMediaPipeTtsEngine.getInstance(context)

        val missingDiscovery = engine.discoverModelFile("")
        assertNull(missingDiscovery.resolvedFile)
        assertTrue(missingDiscovery.statusSummary.contains("No .litertlm model file imported yet"))
        assertTrue(missingDiscovery.searchedPaths.any { it.contains("kokoro_int8_gpu_fp16.litertlm") })

        // Files <= 1024 bytes are ignored as incomplete/empty bundles
        kokoroModelsDir.mkdirs()
        val tinyFile = File(kokoroModelsDir, "kokoro_int8_gpu_fp16.litertlm")
        tinyFile.writeBytes(ByteArray(512) { 1 })
        assertNull(engine.discoverModelFile("").resolvedFile)

        // Valid >1024 byte bundle is discovered in filesDir/kokoro_models
        val validBytes = ByteArray(4096) { 7 }
        tinyFile.writeBytes(validBytes)
        val foundDiscovery = engine.discoverModelFile("")
        assertNotNull(foundDiscovery.resolvedFile)
        assertEquals(tinyFile.absolutePath, foundDiscovery.resolvedFile?.absolutePath)
        assertTrue(foundDiscovery.statusSummary.contains("Model ready: kokoro_int8_gpu_fp16.litertlm"))
    }

    @Test
    fun `discoverModelFile prioritizes explicit configured path when valid`() {
        val engine = KokoroMediaPipeTtsEngine.getInstance(context)
        val customDir = File(context.cacheDir, "custom_kokoro_test").apply { mkdirs() }
        val customFile = File(customDir, "kokoro_int8_fp16.litertlm").apply {
            writeBytes(ByteArray(2048) { 3 })
        }
        try {
            val discovery = engine.discoverModelFile(customFile.absolutePath)
            assertEquals(customFile.absolutePath, discovery.resolvedFile?.absolutePath)
        } finally {
            customDir.deleteRecursively()
        }
    }

    @Test
    fun `floatSamplesToPcm16 scales volume accurately and clamps out-of-range floats`() {
        val fullVolume = KokoroMediaPipeTtsEngine.floatSamplesToPcm16(
            samples = floatArrayOf(-1.5f, -1.0f, 0.0f, 1.0f, 1.5f),
            volume = 1.0f
        )
        assertArrayEquals(
            shortArrayOf(-32767, -32767, 0, 32767, 32767),
            fullVolume
        )

        val halfVolume = KokoroMediaPipeTtsEngine.floatSamplesToPcm16(
            samples = floatArrayOf(-1.0f, 0.5f, 1.0f),
            volume = 0.5f
        )
        assertEquals((-32767.0f * 0.5f).roundToInt().toShort(), halfVolume[0])
        assertEquals((32767.0f * 0.25f).roundToInt().toShort(), halfVolume[1])
        assertEquals((32767.0f * 0.5f).roundToInt().toShort(), halfVolume[2])
    }

    @Test
    fun `pickBestVoiceForGender handles empty voice list safely`() {
        val result = KokoroMediaPipeTtsEngine.pickBestVoiceForGender(
            voices = emptyList(),
            targetLanguage = TargetLanguage.HINDI,
            gender = SpeakerGender.FEMALE_18
        )
        assertNull(result)
    }

    @Test
    fun `synthesizeAndPlay and ForegroundTtsManager benchmark fall back honestly when Kokoro model is missing`() = runTest {
        val engine = KokoroMediaPipeTtsEngine.getInstance(context)
        TranslatorStateRepository.updateTtsLatencyBenchmarks(
            initLatencyMs = 0L,
            firstAudioLatencyMs = 0L,
            speechDurationMs = 0L,
            isRunningBenchmark = true,
            summary = "pending"
        )

        val outcome = engine.synthesizeAndPlay(
            text = "चलो, जल्दी निकलते हैं!",
            targetLanguage = TargetLanguage.HINDI,
            gender = SpeakerGender.FEMALE_18,
            speakingRate = 1.0f,
            volume = 1.0f,
            configuredModelPath = "",
            useGpuDelegate = true,
            isBenchmarkRun = true
        )

        assertTrue(outcome is KokoroSpeakOutcome.FallbackToAndroidTts)
        val afterSynth = TranslatorStateRepository.state.value
        assertFalse(afterSynth.isRunningTtsBenchmark)
        assertEquals(0L, afterSynth.ttsFirstAudioLatencyMs)
        assertTrue(afterSynth.ttsBenchmarkSummary.contains("no .litertlm model file loaded", ignoreCase = true))

        TranslatorStateRepository.updateFreeVoiceBackend(
            context = context,
            backend = FreeVoiceBackend.MEDIAPIPE_KOKORO
        )
        val started = ForegroundTtsManager.getInstance(context).runVoiceBenchmark("नमस्ते गेमर")
        assertFalse(started)
        val afterManagerBench = TranslatorStateRepository.state.value
        assertFalse(afterManagerBench.isRunningTtsBenchmark)
        assertTrue(afterManagerBench.ttsBenchmarkSummary.contains("no .litertlm model file loaded", ignoreCase = true))
    }

    @Test
    fun `FreeVoiceBackend parses codes reliably with safe default`() {
        assertEquals(FreeVoiceBackend.ANDROID_USER_SELECTED, FreeVoiceBackend.fromCode("android_user_selected"))
        assertEquals(FreeVoiceBackend.MEDIAPIPE_KOKORO, FreeVoiceBackend.fromCode("mediapipe_kokoro"))
        assertEquals(FreeVoiceBackend.ANDROID_USER_SELECTED, FreeVoiceBackend.fromCode("unknown"))
        assertEquals(FreeVoiceBackend.ANDROID_USER_SELECTED, FreeVoiceBackend.fromCode(null))
    }
}
