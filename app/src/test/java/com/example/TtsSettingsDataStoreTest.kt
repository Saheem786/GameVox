package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SpeakerGender
import com.example.data.TtsSettingsDataStore
import com.example.engine.EmotionSpeechEngine
import com.example.service.CloudTtsProvider
import com.example.service.CloudTtsSynthesisResult
import com.example.service.ElevenLabsTtsService
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtsSettingsDataStoreTest {

    @Test
    fun saveAndLoadCloudTtsProviderAndKeysInDataStore() = runTest {
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
        assertEquals(CloudTtsProvider.ELEVENLABS, stored.provider)
        assertEquals("AIzaTestGoogleKey123", stored.googleCloudApiKey)
        assertEquals("sk_test_elevenlabs_456", stored.elevenLabsApiKey)

        assertEquals(
            "AIzaTestGoogleKey123",
            TtsSettingsDataStore.getEffectiveApiKey(context, CloudTtsProvider.GOOGLE_CLOUD_TTS)
        )
        assertEquals(
            "sk_test_elevenlabs_456",
            TtsSettingsDataStore.getEffectiveApiKey(context, CloudTtsProvider.ELEVENLABS)
        )
    }

    @Test
    fun emptyKeyInDataStoreReturnsBlankForPhoneTtsFallback() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TtsSettingsDataStore.saveProviderAndApiKey(
            context = context,
            provider = CloudTtsProvider.ELEVENLABS,
            apiKey = ""
        )

        val key = TtsSettingsDataStore.getEffectiveApiKey(context, CloudTtsProvider.ELEVENLABS)
        assertEquals("", key)
    }

    @Test
    fun elevenLabsTtsServiceSendsDynamicApiKeyAndReturnsAudioBytes() = runTest {
        val fakeAudio = byteArrayOf(10, 20, 30, 40)
        var capturedKey = ""
        var capturedBody = ""

        val fakeTransport = object : ElevenLabsTtsService.HttpTransport {
            override fun postJsonForBytes(
                url: String,
                apiKey: String,
                jsonBody: String
            ): ElevenLabsTtsService.BinaryResponse {
                capturedKey = apiKey
                capturedBody = jsonBody
                return ElevenLabsTtsService.BinaryResponse(statusCode = 200, bytes = fakeAudio)
            }
        }

        val emotionResult = EmotionSpeechEngine.analyzeEmotionAndBuildSsml(
            spokenText = "अरे रुको! मेरा वेट करो!",
            sourceEnglishText = "Hold on! Wait for me!",
            gender = SpeakerGender.FEMALE_18
        )
        val service = ElevenLabsTtsService(
            apiKey = "sk_dynamic_datastore_key",
            transport = fakeTransport
        )

        val result = service.synthesizeEmotionAwareSpeech(
            gender = SpeakerGender.FEMALE_18,
            emotionResult = emotionResult
        )

        assertTrue(result is CloudTtsSynthesisResult.Success)
        val success = result as CloudTtsSynthesisResult.Success
        assertArrayEquals(fakeAudio, success.audioBytes)
        assertEquals("sk_dynamic_datastore_key", capturedKey)

        val payload = JSONObject(capturedBody)
        assertEquals("eleven_multilingual_v2", payload.getString("model_id"))
        assertTrue(payload.getString("text").contains("अरे रुको"))
    }
}
