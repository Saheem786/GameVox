package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SpeakerGender
import com.example.data.TargetLanguage
import com.example.data.TranslatorStateRepository
import com.example.engine.EmotionSpeechEngine
import com.example.service.CloudTtsProvider
import com.example.service.CloudTtsService
import com.example.service.ForegroundTtsManager
import com.example.service.FreeTtsEngineCatalog
import com.example.service.FreeVoiceBackend
import com.example.service.InstalledTtsEngineInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FreeVoiceIntegrationTest {

    @Test
    fun `detects sherpa-onnx and Supertonic 3 Hindi TTS engines cleanly`() {
        assertTrue(
            FreeTtsEngineCatalog.isSherpaOrSupertonicEngine(
                packageName = "com.k2fsa.sherpa.onnx.tts.engine",
                label = "Next-gen Kaldi TTS"
            )
        )
        assertTrue(
            FreeTtsEngineCatalog.isSherpaOrSupertonicEngine(
                packageName = "com.k2fsa.sherpa.onnx",
                label = "Supertonic 3 Hindi"
            )
        )
        assertTrue(
            FreeTtsEngineCatalog.isSherpaOrSupertonicEngine(
                packageName = "org.custom.tts",
                label = "Supertonic Hindi Neural TTS"
            )
        )
        assertFalse(
            FreeTtsEngineCatalog.isSherpaOrSupertonicEngine(
                packageName = "com.google.android.tts",
                label = "Speech Recognition and Synthesis from Google"
            )
        )
    }

    @Test
    fun `resolveEnginePackageForBinding never forces Google TTS and respects user or system choice`() {
        val enginesWithGoogleAndSupertonic = listOf(
            InstalledTtsEngineInfo(
                packageName = "com.k2fsa.sherpa.onnx.tts.engine",
                label = "Supertonic 3 Hindi TTS",
                isSystemDefault = false,
                isSherpaOrSupertonic = true
            ),
            InstalledTtsEngineInfo(
                packageName = "com.google.android.tts",
                label = "Google TTS",
                isSystemDefault = true,
                isSherpaOrSupertonic = false
            )
        )

        // 1. User selected Supertonic explicitly -> binds Supertonic
        val explicitSupertonic = FreeTtsEngineCatalog.resolveEnginePackageForBinding(
            userSelectedPackage = "com.k2fsa.sherpa.onnx.tts.engine",
            installedEngines = enginesWithGoogleAndSupertonic,
            systemDefaultPackage = "com.google.android.tts"
        )
        assertEquals("com.k2fsa.sherpa.onnx.tts.engine", explicitSupertonic)

        // 2. User picked "Follow Android System Default" ("") and device has a system default -> returns null
        // so Android binds the user's configured System Default TTS engine rather than forcing Google TTS
        val systemDefaultChoice = FreeTtsEngineCatalog.resolveEnginePackageForBinding(
            userSelectedPackage = "",
            installedEngines = enginesWithGoogleAndSupertonic,
            systemDefaultPackage = "com.k2fsa.sherpa.onnx.tts.engine"
        )
        assertNull(systemDefaultChoice)

        // 3. System default unset + Supertonic installed -> prefers installed Supertonic over Google TTS
        val autoSupertonic = FreeTtsEngineCatalog.resolveEnginePackageForBinding(
            userSelectedPackage = "",
            installedEngines = enginesWithGoogleAndSupertonic,
            systemDefaultPackage = null
        )
        assertEquals("com.k2fsa.sherpa.onnx.tts.engine", autoSupertonic)

        // 4. Even if only Google TTS is installed and unset, never hardcodes com.google.android.tts
        val onlyGoogleUnset = FreeTtsEngineCatalog.resolveEnginePackageForBinding(
            userSelectedPackage = "",
            installedEngines = listOf(
                InstalledTtsEngineInfo(
                    packageName = "com.google.android.tts",
                    label = "Google TTS",
                    isSystemDefault = false,
                    isSherpaOrSupertonic = false
                )
            ),
            systemDefaultPackage = null
        )
        assertNull(onlyGoogleUnset)
    }

    @Test
    fun `gender voice support report only enables gender switching when distinct female and male voices exist`() {
        val singleSpeakerSupertonic = FreeTtsEngineCatalog.buildGenderSupportReport(
            engineLabel = "Supertonic 3 Hindi",
            targetLanguage = TargetLanguage.HINDI,
            femaleVoicesCount = 0,
            maleVoicesCount = 0,
            totalLanguageVoicesCount = 1
        )
        assertFalse(singleSpeakerSupertonic.supportsDistinctGenderVoices)
        assertTrue(singleSpeakerSupertonic.statusNote.contains("Single-speaker", ignoreCase = true))

        val femaleOnlyEngine = FreeTtsEngineCatalog.buildGenderSupportReport(
            engineLabel = "Custom Hindi TTS",
            targetLanguage = TargetLanguage.HINDI,
            femaleVoicesCount = 2,
            maleVoicesCount = 0,
            totalLanguageVoicesCount = 2
        )
        assertFalse(femaleOnlyEngine.supportsDistinctGenderVoices)

        val multiGenderEngine = FreeTtsEngineCatalog.buildGenderSupportReport(
            engineLabel = "Dual-Voice Hindi TTS",
            targetLanguage = TargetLanguage.HINDI,
            femaleVoicesCount = 2,
            maleVoicesCount = 2,
            totalLanguageVoicesCount = 4
        )
        assertTrue(multiGenderEngine.supportsDistinctGenderVoices)
        assertTrue(multiGenderEngine.statusNote.contains("2 female + 2 male"))
    }

    @Test
    fun `synthesizeAndPlay short-circuits cleanly and benchmark reports failure honestly without fake metrics`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val analysis = EmotionSpeechEngine.analyzeEmotionAndBuildSsml(
            spokenText = "जल्दी भागो! खतरा है!",
            sourceEnglishText = "Run quickly! Danger!",
            gender = SpeakerGender.MALE_18
        )

        // Paid cloud API must never synthesize or succeed
        val usedPaid = CloudTtsService.synthesizeAndPlay(
            context = context,
            provider = CloudTtsProvider.GOOGLE_CLOUD_TTS,
            targetLanguage = TargetLanguage.HINDI,
            gender = SpeakerGender.MALE_18,
            emotionResult = analysis,
            apiKeyOverride = "AIzaShouldBeIgnored",
            onStarted = {},
            onCompleted = {},
            onError = {}
        )
        assertFalse(usedPaid)

        // Running voice benchmark when no real Android TTS engine is bound in Robolectric JVM
        // must report real failure rather than fabricating latency metrics.
        TranslatorStateRepository.updateTtsLatencyBenchmarks(
            initLatencyMs = 0L,
            firstAudioLatencyMs = 0L,
            speechDurationMs = 0L,
            isRunningBenchmark = false,
            summary = ""
        )
        val manager = ForegroundTtsManager.getInstance(context)
        val started = manager.runVoiceBenchmark(sampleText = "चलो, जल्दी निकलते हैं!")
        val benchmarkState = TranslatorStateRepository.state.value
        assertFalse(started)
        assertEquals(0L, benchmarkState.ttsFirstAudioLatencyMs)
        assertFalse(benchmarkState.isRunningTtsBenchmark)
        assertTrue(
            benchmarkState.ttsBenchmarkSummary.contains("not ready", ignoreCase = true) ||
                benchmarkState.ttsBenchmarkSummary.contains("unavailable", ignoreCase = true)
        )
    }

    @Test
    fun `repository updates free engine selection and strips legacy high-quality network flag defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        TranslatorStateRepository.updateSelectedTtsEnginePackage(
            context = context,
            enginePackage = "com.k2fsa.sherpa.onnx.tts.engine"
        )
        TranslatorStateRepository.updateFreeVoiceBackend(
            context = context,
            backend = FreeVoiceBackend.ANDROID_USER_SELECTED
        )
        TranslatorStateRepository.updateTtsEngineCatalogAndCapability(
            installedEngines = emptyList(),
            activeEnginePackage = "com.k2fsa.sherpa.onnx.tts.engine",
            activeEngineLabel = "Supertonic 3 Hindi",
            isSupertonicOrSherpaActive = true,
            supportsDistinctGenderVoices = false,
            genderVoiceCapabilityNote = "Single-speaker Supertonic voice"
        )

        val current = TranslatorStateRepository.state.value
        assertEquals("com.k2fsa.sherpa.onnx.tts.engine", current.selectedTtsEnginePackage)
        assertEquals(FreeVoiceBackend.ANDROID_USER_SELECTED, current.freeVoiceBackend)
        assertTrue(current.isSupertonicOrSherpaActive)
        assertFalse(current.supportsDistinctGenderVoices)
        assertEquals("", current.savedKeyFor(CloudTtsProvider.GOOGLE_CLOUD_TTS))
        assertEquals("", current.savedKeyFor(CloudTtsProvider.ELEVENLABS))
        assertEquals("", current.savedKeyFor(CloudTtsProvider.OPENAI_TTS))
    }
}
