package com.example.service

import android.util.Log
import com.example.data.SpeakerGender
import com.example.engine.EmotionAnalysisResult
import com.example.engine.SpeechEmotion
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Client service for the ElevenLabs Text-to-Speech REST API (`v1/text-to-speech/{voice_id}`)
 * using dynamically provided user API keys stored in Jetpack DataStore.
 *
 * Uses `eleven_multilingual_v2` so Hindi/Hinglish and English game dialogues synthesize with
 * natural human intonation and emotion-modulated `voice_settings`.
 */
class ElevenLabsTtsService(
    private val apiKey: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transport: HttpTransport = DefaultHttpTransport
) {

    interface HttpTransport {
        fun postJsonForBytes(url: String, apiKey: String, jsonBody: String): BinaryResponse
    }

    data class BinaryResponse(
        val statusCode: Int,
        val bytes: ByteArray,
        val errorBody: String = ""
    )

    companion object {
        private const val TAG = "ElevenLabsTtsService"
        private const val BASE_ENDPOINT = "https://api.elevenlabs.io/v1/text-to-speech"
        private const val MODEL_MULTILINGUAL_V2 = "eleven_multilingual_v2"

        // Well-known ElevenLabs Multilingual v2 voice IDs matched to character gender
        private const val VOICE_ID_FEMALE = "EXAVITQu4vr4xnSDxMaL" // Bella / expressive female
        private const val VOICE_ID_MALE = "pNInz6obpgDQGcFmaJgB"   // Adam / expressive male

        fun hasValidApiKey(key: String): Boolean {
            val trimmed = key.trim()
            return trimmed.isNotEmpty() && !trimmed.startsWith("MY_")
        }

        private object DefaultHttpTransport : HttpTransport {
            override fun postJsonForBytes(url: String, apiKey: String, jsonBody: String): BinaryResponse {
                var connection: HttpURLConnection? = null
                return try {
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 6000
                        readTimeout = 12000
                        doOutput = true
                        setRequestProperty("xi-api-key", apiKey)
                        setRequestProperty("Accept", "audio/mpeg")
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    }

                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(jsonBody)
                    }

                    val code = connection.responseCode
                    if (code in 200..299) {
                        val audioBytes = connection.inputStream.use { it.readBytes() }
                        BinaryResponse(statusCode = code, bytes = audioBytes)
                    } else {
                        val err = connection.errorStream?.let { stream ->
                            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                        }.orEmpty()
                        BinaryResponse(statusCode = code, bytes = ByteArray(0), errorBody = err)
                    }
                } finally {
                    try {
                        connection?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    suspend fun synthesizeEmotionAwareSpeech(
        gender: SpeakerGender,
        emotionResult: EmotionAnalysisResult
    ): CloudTtsSynthesisResult = withContext(ioDispatcher) {
        if (!CloudTtsService.PAID_CLOUD_TTS_ENABLED) {
            return@withContext CloudTtsSynthesisResult.Failure(
                message = "Paid ElevenLabs API is disabled. Use free user-selected Android TTS (Supertonic 3 / system TTS)."
            )
        }
        val trimmedKey = apiKey.trim()
        if (!hasValidApiKey(trimmedKey)) {
            return@withContext CloudTtsSynthesisResult.Failure(
                message = "ElevenLabs API key is not configured."
            )
        }
        val cleanText = emotionResult.cleanSpokenText.trim()
        if (cleanText.isEmpty()) {
            return@withContext CloudTtsSynthesisResult.Failure("Spoken text cannot be blank")
        }

        val voiceId = if (gender == SpeakerGender.FEMALE_18) VOICE_ID_FEMALE else VOICE_ID_MALE
        val voiceLabel = if (gender == SpeakerGender.FEMALE_18) {
            "ElevenLabs (Female 18y • Multilingual v2)"
        } else {
            "ElevenLabs (Male 18y • Multilingual v2)"
        }

        val (stability, similarity, style) = when (emotionResult.emotion) {
            SpeechEmotion.EXCITED -> Triple(0.32, 0.80, 0.75)
            SpeechEmotion.HAPPY -> Triple(0.40, 0.78, 0.65)
            SpeechEmotion.ANGRY -> Triple(0.28, 0.82, 0.85)
            SpeechEmotion.FEAR -> Triple(0.30, 0.80, 0.78)
            SpeechEmotion.SAD -> Triple(0.60, 0.78, 0.45)
            SpeechEmotion.WHISPER -> Triple(0.75, 0.85, 0.20)
            SpeechEmotion.SERIOUS -> Triple(0.65, 0.82, 0.30)
            SpeechEmotion.NEUTRAL -> Triple(0.50, 0.75, 0.35)
        }

        val payload = JSONObject().apply {
            put("text", cleanText)
            put("model_id", MODEL_MULTILINGUAL_V2)
            put("voice_settings", JSONObject().apply {
                put("stability", stability)
                put("similarity_boost", similarity)
                put("style", style)
                put("use_speaker_boost", true)
            })
        }

        return@withContext try {
            val response = transport.postJsonForBytes(
                url = "$BASE_ENDPOINT/$voiceId",
                apiKey = trimmedKey,
                jsonBody = payload.toString()
            )
            if (response.statusCode in 200..299 && response.bytes.isNotEmpty()) {
                CloudTtsSynthesisResult.Success(
                    audioBytes = response.bytes,
                    voiceName = voiceLabel,
                    emotion = emotionResult.emotion,
                    ssmlUsed = emotionResult.ssmlMarkup
                )
            } else {
                Log.w(TAG, "ElevenLabs HTTP ${response.statusCode}: ${response.errorBody.take(160)}")
                CloudTtsSynthesisResult.Failure(
                    message = "ElevenLabs HTTP ${response.statusCode}: ${response.errorBody.take(120)}",
                    httpStatusCode = response.statusCode
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "ElevenLabs TTS request failed", e)
            CloudTtsSynthesisResult.Failure(
                message = e.message ?: "ElevenLabs request failed",
                cause = e
            )
        }
    }
}
