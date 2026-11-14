package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TtsSettingsDataStore
import com.example.service.CloudTtsProvider
import com.example.service.FreeVoiceBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtsSettingsDataStoreTest {

    @Test
    fun paidApiKeysAreIgnoredAndNeverStoredInDataStore() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        TtsSettingsDataStore.saveProviderAndApiKey(
            context = context,
            provider = CloudTtsProvider.GOOGLE_CLOUD_TTS,
            apiKey = "AIzaTestGoogleKey123"
        )
        TtsSettingsDataStore.saveProviderAndApiKey(
            context = context,
            provider = CloudTtsProvider.ELEVENLABS,
            apiKey = "sk_test_elevenlabs_456"
        )

        val stored = TtsSettingsDataStore.readPreferences(context)
        assertEquals(CloudTtsProvider.ANDROID_SSML_NETWORK, stored.provider)
        assertEquals("", stored.googleCloudApiKey)
        assertEquals("", stored.elevenLabsApiKey)
        assertEquals("", stored.openAiApiKey)

        assertEquals(
            "",
            TtsSettingsDataStore.getEffectiveApiKey(context, CloudTtsProvider.GOOGLE_CLOUD_TTS)
        )
        assertEquals(
            "",
            TtsSettingsDataStore.getEffectiveApiKey(context, CloudTtsProvider.ELEVENLABS)
        )
        assertEquals(
            "",
            TtsSettingsDataStore.getEffectiveApiKey(context, CloudTtsProvider.OPENAI_TTS)
        )
    }

    @Test
    fun saveAndLoadSelectedAndroidTtsEngineAndBackendInDataStore() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        TtsSettingsDataStore.saveSelectedAndroidTtsEngine(
            context = context,
            enginePackage = "com.k2fsa.sherpa.onnx.tts.engine"
        )
        TtsSettingsDataStore.saveFreeVoiceBackend(
            context = context,
            backend = FreeVoiceBackend.ANDROID_USER_SELECTED
        )

        val storedSupertonic = TtsSettingsDataStore.readPreferences(context)
        assertEquals("com.k2fsa.sherpa.onnx.tts.engine", storedSupertonic.selectedTtsEnginePackage)
        assertEquals(FreeVoiceBackend.ANDROID_USER_SELECTED, storedSupertonic.freeVoiceBackend)

        // Switching back to System Default ("")
        TtsSettingsDataStore.saveSelectedAndroidTtsEngine(
            context = context,
            enginePackage = ""
        )
        TtsSettingsDataStore.saveFreeVoiceBackend(
            context = context,
            backend = FreeVoiceBackend.MEDIAPIPE_KOKORO
        )
        TtsSettingsDataStore.saveKokoroConfig(
            context = context,
            modelPath = "/data/user/0/com.example/files/kokoro_models/kokoro_int8_gpu_fp16.litertlm",
            useGpu = false
        )

        val storedKokoro = TtsSettingsDataStore.readPreferences(context)
        assertEquals("", storedKokoro.selectedTtsEnginePackage)
        assertEquals(FreeVoiceBackend.MEDIAPIPE_KOKORO, storedKokoro.freeVoiceBackend)
        assertTrue(storedKokoro.kokoroModelPath.endsWith("kokoro_int8_gpu_fp16.litertlm"))
        assertFalse(storedKokoro.kokoroUseGpu)
    }
}
