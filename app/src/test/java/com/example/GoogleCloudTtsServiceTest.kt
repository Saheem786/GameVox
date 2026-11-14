package com.example

import com.example.data.SpeakerGender
import com.example.data.TargetLanguage
import com.example.engine.EmotionSpeechEngine
import com.example.service.CloudAudioEncoding
import com.example.service.CloudTtsProvider
import com.example.service.CloudTtsService
import com.example.service.CloudTtsSynthesisResult
import com.example.service.ElevenLabsTtsService
import com.example.service.GoogleCloudTtsService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GoogleCloudTtsServiceTest {

    @Test
    fun paidCloudApisArePermanentlyDisabled_zeroHttpRequestsSent() = runTest {
        assertFalse(CloudTtsService.PAID_CLOUD_TTS_ENABLED)
        assertFalse(CloudTtsService.hasGoogleCloudKey("AIzaRealLookingKey1234567890"))
        assertFalse(CloudTtsService.hasElevenLabsKey("sk_real_looking_key_1234567890"))
        assertFalse(CloudTtsService.hasOpenAiKey("sk-openai-real-looking-123456"))

        var googleHttpCalls = 0
        val googleService = GoogleCloudTtsService(
            apiKey = "AIzaRealLookingKey1234567890",
            transport = object : GoogleCloudTtsService.HttpTransport {
                override fun postJson(
                    url: String,
                    apiKey: String,
                    jsonBody: String
                ): GoogleCloudTtsService.HttpResponse {
                    googleHttpCalls++
                    return GoogleCloudTtsService.HttpResponse(statusCode = 200, body = "{}")
                }
            }
        )

        val ssmlResult = googleService.synthesizeSsml(
            ssmlText = "<speak><prosody rate=\"110%\" pitch=\"+20%\">अरे रुको!</prosody></speak>",
            languageCode = "hi-IN",
            gender = SpeakerGender.FEMALE_18,
            audioEncoding = CloudAudioEncoding.MP3
        )
        assertTrue(ssmlResult.isFailure)

        val emotionResult = googleService.synthesizeEmotionAwareText(
            spokenText = "मैं बहुत दुखी हूँ...",
            sourceEnglishText = "I am so sad...",
            targetLanguage = TargetLanguage.HINDI,
            gender = SpeakerGender.MALE_18
        )
        assertTrue(emotionResult is CloudTtsSynthesisResult.Failure)
        assertEquals(0, googleHttpCalls)

        var elevenLabsHttpCalls = 0
        val elevenService = ElevenLabsTtsService(
            apiKey = "sk_real_looking_key_1234567890",
            transport = object : ElevenLabsTtsService.HttpTransport {
                override fun postJsonForBytes(
                    url: String,
                    apiKey: String,
                    jsonBody: String
                ): ElevenLabsTtsService.BinaryResponse {
                    elevenLabsHttpCalls++
                    return ElevenLabsTtsService.BinaryResponse(statusCode = 200, bytes = byteArrayOf(1))
                }
            }
        )

        val analysis = EmotionSpeechEngine.analyzeEmotionAndBuildSsml(
            spokenText = "अरे रुको! मेरा वेट करो!",
            sourceEnglishText = "Hold on! Wait for me!",
            gender = SpeakerGender.FEMALE_18
        )
        val elevenResult = elevenService.synthesizeEmotionAwareSpeech(
            gender = SpeakerGender.FEMALE_18,
            emotionResult = analysis
        )
        assertTrue(elevenResult is CloudTtsSynthesisResult.Failure)
        assertEquals(0, elevenLabsHttpCalls)
    }

    @Test
    fun cloudProviderFromCodeAlwaysResolvesToFreeAndroidTts() {
        assertEquals(
            CloudTtsProvider.ANDROID_SSML_NETWORK,
            CloudTtsProvider.fromCode("google_cloud_tts")
        )
        assertEquals(
            CloudTtsProvider.ANDROID_SSML_NETWORK,
            CloudTtsProvider.fromCode("elevenlabs")
        )
        assertEquals(
            CloudTtsProvider.ANDROID_SSML_NETWORK,
            CloudTtsProvider.fromCode("openai_tts")
        )
    }
}
