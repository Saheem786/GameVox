package com.example

import android.util.Base64
import com.example.data.SpeakerGender
import com.example.data.TargetLanguage
import com.example.engine.SpeechEmotion
import com.example.service.CloudAudioEncoding
import com.example.service.CloudTtsSynthesisResult
import com.example.service.GoogleCloudTtsService
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GoogleCloudTtsServiceTest {

    @Test
    fun synthesizeSsml_sendsSsmlPayloadAndDecodesAudioBytes() = runTest {
        val expectedAudio = "fake-mp3-audio-payload".toByteArray()
        val encodedAudio = Base64.encodeToString(expectedAudio, Base64.NO_WRAP)

        var capturedUrl = ""
        var capturedApiKey = ""
        var capturedBody = ""

        val fakeTransport = object : GoogleCloudTtsService.HttpTransport {
            override fun postJson(
                url: String,
                apiKey: String,
                jsonBody: String
            ): GoogleCloudTtsService.HttpResponse {
                capturedUrl = url
                capturedApiKey = apiKey
                capturedBody = jsonBody
                return GoogleCloudTtsService.HttpResponse(
                    statusCode = 200,
                    body = """{"audioContent":"$encodedAudio"}"""
                )
            }
        }

        val service = GoogleCloudTtsService(
            apiKey = "test-cloud-tts-key",
            transport = fakeTransport
        )

        val result = service.synthesizeSsml(
            ssmlText = "<speak><prosody rate=\"110%\" pitch=\"+20%\">अरे रुको!</prosody></speak>",
            languageCode = "hi-IN",
            gender = SpeakerGender.FEMALE_18,
            emotion = SpeechEmotion.EXCITED,
            audioEncoding = CloudAudioEncoding.MP3
        )

        assertTrue(result.isSuccess)
        assertArrayEquals(expectedAudio, result.getOrThrow())
        assertEquals(GoogleCloudTtsService.DEFAULT_ENDPOINT_URL, capturedUrl)
        assertEquals("test-cloud-tts-key", capturedApiKey)

        val payload = JSONObject(capturedBody)
        assertTrue(payload.getJSONObject("input").getString("ssml").startsWith("<speak>"))
        assertEquals("hi-IN-Neural2-A", payload.getJSONObject("voice").getString("name"))
        assertEquals("FEMALE", payload.getJSONObject("voice").getString("ssmlGender"))
        assertEquals("MP3", payload.getJSONObject("audioConfig").getString("audioEncoding"))
    }

    @Test
    fun synthesizeEmotionAwareText_wrapsPlainTextIntoEmotionSsml() = runTest {
        val expectedAudio = byteArrayOf(1, 2, 3, 4, 5)
        val encodedAudio = Base64.encodeToString(expectedAudio, Base64.NO_WRAP)

        var capturedBody = ""
        val fakeTransport = object : GoogleCloudTtsService.HttpTransport {
            override fun postJson(
                url: String,
                apiKey: String,
                jsonBody: String
            ): GoogleCloudTtsService.HttpResponse {
                capturedBody = jsonBody
                return GoogleCloudTtsService.HttpResponse(
                    statusCode = 200,
                    body = """{"audioContent":"$encodedAudio"}"""
                )
            }
        }

        val service = GoogleCloudTtsService(
            apiKey = "test-cloud-tts-key",
            transport = fakeTransport
        )

        val result = service.synthesizeEmotionAwareText(
            spokenText = "मैं बहुत दुखी हूँ...",
            sourceEnglishText = "I am so sad...",
            targetLanguage = TargetLanguage.HINDI,
            gender = SpeakerGender.MALE_18
        )

        assertTrue(result is CloudTtsSynthesisResult.Success)
        val success = result as CloudTtsSynthesisResult.Success
        assertEquals(SpeechEmotion.SAD, success.emotion)
        assertEquals("hi-IN-Neural2-B", success.voiceName)
        assertArrayEquals(expectedAudio, success.audioBytes)
        assertTrue(success.ssmlUsed.contains("<prosody rate=\"slow\" pitch=\"low\""))

        val payload = JSONObject(capturedBody)
        assertEquals("MALE", payload.getJSONObject("voice").getString("ssmlGender"))
    }

    @Test
    fun synthesizeSsml_rejectsMissingOrPlaceholderApiKey() = runTest {
        val service = GoogleCloudTtsService(apiKey = "MY_GOOGLE_CLOUD_TTS_API_KEY")
        val result = service.synthesizeSsml(ssmlText = "<speak>Hello</speak>")
        assertTrue(result.isFailure)
    }
}
