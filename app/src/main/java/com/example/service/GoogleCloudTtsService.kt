package com.example.service

import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.SpeakerGender
import com.example.data.TargetLanguage
import com.example.engine.EmotionAnalysisResult
import com.example.engine.EmotionSpeechEngine
import com.example.engine.SpeechEmotion
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class CloudAudioEncoding(val apiValue: String) {
    MP3("MP3"),
    LINEAR16("LINEAR16"),
    OGG_OPUS("OGG_OPUS")
}

sealed class CloudTtsSynthesisResult {
    data class Success(
        val audioBytes: ByteArray,
        val voiceName: String,
        val emotion: SpeechEmotion,
        val ssmlUsed: String
    ) : CloudTtsSynthesisResult()

    data class Failure(
        val message: String,
        val httpStatusCode: Int? = null,
        val cause: Throwable? = null
    ) : CloudTtsSynthesisResult()
}

/**
 * Dedicated client service for the Google Cloud Text-to-Speech REST API (`v1/text:synthesize`)
 * using [BuildConfig.GOOGLE_CLOUD_TTS_API_KEY] configured in the AI Studio Secrets panel.
 *
 * Supports:
 * - Asynchronous coroutine execution on [ioDispatcher]
 * - W3C SSML (`<speak><prosody ...>...</prosody></speak>`) emotion-aware synthesis
 * - High-quality `Neural2` and `WaveNet` voice selection matched to character gender (`FEMALE_18` / `MALE_18`)
 * - Base64 audio decoding into raw [ByteArray] for immediate playback
 */
class GoogleCloudTtsService(
    private val apiKey: String = BuildConfig.GOOGLE_CLOUD_TTS_API_KEY,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val endpointUrl: String = DEFAULT_ENDPOINT_URL,
    private val transport: HttpTransport = DefaultHttpTransport
) {

    interface HttpTransport {
        fun postJson(url: String, apiKey: String, jsonBody: String): HttpResponse
    }

    data class HttpResponse(
        val statusCode: Int,
        val body: String
    )

    companion object {
        private const val TAG = "GoogleCloudTtsService"
        const val DEFAULT_ENDPOINT_URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val READ_TIMEOUT_MS = 10000

        private object DefaultHttpTransport : HttpTransport {
            override fun postJson(url: String, apiKey: String, jsonBody: String): HttpResponse {
                var connection: HttpURLConnection? = null
                return try {
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        setRequestProperty("X-Goog-Api-Key", apiKey)
                    }

                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(jsonBody)
                    }

                    val code = connection.responseCode
                    val stream = if (code in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    val responseText = if (stream != null) {
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                    } else {
                        ""
                    }
                    HttpResponse(statusCode = code, body = responseText)
                } finally {
                    try {
                        connection?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        fun hasValidApiKey(key: String = BuildConfig.GOOGLE_CLOUD_TTS_API_KEY): Boolean {
            val trimmed = key.trim()
            return trimmed.isNotEmpty() && !trimmed.startsWith("MY_")
        }
    }

    fun hasValidApiKey(): Boolean = hasValidApiKey(apiKey)

    /**
     * Asynchronously sends [ssmlText] to the Google Cloud Text-to-Speech API and returns
     * the synthesized audio as a [Result] wrapping [ByteArray] for playback.
     *
     * If [ssmlText] is plain text without `<speak>` tags, it is automatically wrapped in
     * emotion-aware SSML via [EmotionSpeechEngine].
     */
    suspend fun synthesizeSsml(
        ssmlText: String,
        languageCode: String = TargetLanguage.HINDI.ttsLocaleTag,
        voiceName: String? = null,
        gender: SpeakerGender = SpeakerGender.FEMALE_18,
        emotion: SpeechEmotion = SpeechEmotion.NEUTRAL,
        speakingRate: Double = emotion.cloudSpeakingRate,
        pitchSemitones: Double? = null,
        volumeGainDb: Double = emotion.cloudVolumeGainDb,
        audioEncoding: CloudAudioEncoding = CloudAudioEncoding.MP3
    ): Result<ByteArray> {
        return when (
            val detailed = synthesizeSsmlDetailed(
                ssmlText = ssmlText,
                languageCode = languageCode,
                voiceName = voiceName,
                gender = gender,
                emotion = emotion,
                speakingRate = speakingRate,
                pitchSemitones = pitchSemitones,
                volumeGainDb = volumeGainDb,
                audioEncoding = audioEncoding
            )
        ) {
            is CloudTtsSynthesisResult.Success -> Result.success(detailed.audioBytes)
            is CloudTtsSynthesisResult.Failure -> Result.failure(
                detailed.cause ?: IllegalStateException(detailed.message)
            )
        }
    }

    /**
     * Analyzes [spokenText] (and optional [sourceEnglishText]) with [EmotionSpeechEngine],
     * formats the text as emotion-aware SSML, and synthesizes Neural2/WaveNet audio bytes.
     */
    suspend fun synthesizeEmotionAwareText(
        spokenText: String,
        sourceEnglishText: String = "",
        targetLanguage: TargetLanguage = TargetLanguage.HINDI,
        gender: SpeakerGender = SpeakerGender.FEMALE_18,
        audioEncoding: CloudAudioEncoding = CloudAudioEncoding.MP3
    ): CloudTtsSynthesisResult {
        if (spokenText.isBlank()) {
            return CloudTtsSynthesisResult.Failure("Spoken text cannot be blank")
        }
        val emotionResult = EmotionSpeechEngine.analyzeEmotionAndBuildSsml(
            spokenText = spokenText,
            sourceEnglishText = sourceEnglishText,
            gender = gender
        )
        return synthesizeWithEmotionResult(
            targetLanguage = targetLanguage,
            gender = gender,
            emotionResult = emotionResult,
            audioEncoding = audioEncoding
        )
    }

    /**
     * Synthesizes a precomputed [EmotionAnalysisResult] using Google Cloud Neural2 / WaveNet voices.
     */
    suspend fun synthesizeWithEmotionResult(
        targetLanguage: TargetLanguage,
        gender: SpeakerGender,
        emotionResult: EmotionAnalysisResult,
        audioEncoding: CloudAudioEncoding = CloudAudioEncoding.MP3
    ): CloudTtsSynthesisResult {
        return synthesizeSsmlDetailed(
            ssmlText = emotionResult.ssmlMarkup,
            languageCode = targetLanguage.ttsLocaleTag,
            voiceName = null,
            gender = gender,
            emotion = emotionResult.emotion,
            speakingRate = emotionResult.emotion.cloudSpeakingRate,
            pitchSemitones = computePitchSemitones(emotionResult.emotion, gender),
            volumeGainDb = emotionResult.emotion.cloudVolumeGainDb,
            audioEncoding = audioEncoding
        )
    }

    /**
     * Sends an SSML payload asynchronously to `v1/text:synthesize` and returns
     * [CloudTtsSynthesisResult.Success] containing the decoded audio bytes and selected voice name.
     */
    suspend fun synthesizeSsmlDetailed(
        ssmlText: String,
        languageCode: String = TargetLanguage.HINDI.ttsLocaleTag,
        voiceName: String? = null,
        gender: SpeakerGender = SpeakerGender.FEMALE_18,
        emotion: SpeechEmotion = SpeechEmotion.NEUTRAL,
        speakingRate: Double = emotion.cloudSpeakingRate,
        pitchSemitones: Double? = null,
        volumeGainDb: Double = emotion.cloudVolumeGainDb,
        audioEncoding: CloudAudioEncoding = CloudAudioEncoding.MP3
    ): CloudTtsSynthesisResult = withContext(ioDispatcher) {
        if (!CloudTtsService.PAID_CLOUD_TTS_ENABLED) {
            return@withContext CloudTtsSynthesisResult.Failure(
                message = "Paid Google Cloud TTS API is disabled. Use free user-selected Android TTS (Supertonic 3 / system TTS)."
            )
        }
        val trimmedKey = apiKey.trim()
        if (!hasValidApiKey(trimmedKey)) {
            return@withContext CloudTtsSynthesisResult.Failure(
                message = "GOOGLE_CLOUD_TTS_API_KEY is missing or placeholder. Set it in the AI Studio Secrets panel."
            )
        }
        if (ssmlText.isBlank()) {
            return@withContext CloudTtsSynthesisResult.Failure("SSML text cannot be blank")
        }

        val normalizedSsml = ensureSsmlWrapped(
            rawOrSsml = ssmlText,
            emotion = emotion,
            gender = gender
        )
        val effectivePitch = (pitchSemitones ?: computePitchSemitones(emotion, gender))
            .coerceIn(-12.0, 12.0)
        val effectiveRate = speakingRate.coerceIn(0.25, 2.0)
        val effectiveVolumeGain = volumeGainDb.coerceIn(-16.0, 16.0)

        val candidates = if (!voiceName.isNullOrBlank()) {
            listOf(voiceName)
        } else {
            selectVoiceCandidates(languageCode = languageCode, gender = gender)
        }

        var lastFailure: CloudTtsSynthesisResult.Failure? = null

        for (candidateVoice in candidates) {
            coroutineContext.ensureActive()

            val payloadJson = buildRequestPayload(
                ssml = normalizedSsml,
                languageCode = languageCode,
                voiceName = candidateVoice,
                gender = gender,
                speakingRate = effectiveRate,
                pitchSemitones = effectivePitch,
                volumeGainDb = effectiveVolumeGain,
                audioEncoding = audioEncoding
            ).toString()

            try {
                val response = transport.postJson(
                    url = endpointUrl,
                    apiKey = trimmedKey,
                    jsonBody = payloadJson
                )

                if (response.statusCode in 200..299) {
                    val json = JSONObject(response.body)
                    val base64Audio = json.optString("audioContent", "")
                    if (base64Audio.isNotBlank()) {
                        val decodedBytes = Base64.decode(base64Audio, Base64.DEFAULT)
                        if (decodedBytes.isNotEmpty()) {
                            return@withContext CloudTtsSynthesisResult.Success(
                                audioBytes = decodedBytes,
                                voiceName = candidateVoice,
                                emotion = emotion,
                                ssmlUsed = normalizedSsml
                            )
                        }
                    }
                    lastFailure = CloudTtsSynthesisResult.Failure(
                        message = "Google Cloud TTS returned empty audioContent for $candidateVoice",
                        httpStatusCode = response.statusCode
                    )
                } else {
                    val apiErrorMessage = extractGoogleApiError(response.body)
                    Log.w(
                        TAG,
                        "Google Cloud TTS HTTP ${response.statusCode} for $candidateVoice: $apiErrorMessage"
                    )
                    lastFailure = CloudTtsSynthesisResult.Failure(
                        message = "Google Cloud TTS HTTP ${response.statusCode}: $apiErrorMessage",
                        httpStatusCode = response.statusCode
                    )
                    // Auth / quota errors will fail identically for every candidate; abort fast.
                    if (response.statusCode == 401 || response.statusCode == 403 || response.statusCode == 429) {
                        return@withContext lastFailure
                    }
                }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                Log.w(TAG, "Google Cloud TTS request failed for $candidateVoice", e)
                lastFailure = CloudTtsSynthesisResult.Failure(
                    message = e.message ?: "Network request failed for $candidateVoice",
                    cause = e
                )
            }
        }

        lastFailure ?: CloudTtsSynthesisResult.Failure("No Google Cloud TTS voice candidates succeeded")
    }

    internal fun buildRequestPayload(
        ssml: String,
        languageCode: String,
        voiceName: String,
        gender: SpeakerGender,
        speakingRate: Double,
        pitchSemitones: Double,
        volumeGainDb: Double,
        audioEncoding: CloudAudioEncoding
    ): JSONObject {
        return JSONObject().apply {
            put("input", JSONObject().apply {
                put("ssml", ssml)
            })
            put("voice", JSONObject().apply {
                put("languageCode", languageCode)
                put("name", voiceName)
                put("ssmlGender", if (gender == SpeakerGender.FEMALE_18) "FEMALE" else "MALE")
            })
            put("audioConfig", JSONObject().apply {
                put("audioEncoding", audioEncoding.apiValue)
                put("speakingRate", speakingRate)
                put("pitch", pitchSemitones)
                put("volumeGainDb", volumeGainDb)
            })
        }
    }

    internal fun ensureSsmlWrapped(
        rawOrSsml: String,
        emotion: SpeechEmotion,
        gender: SpeakerGender
    ): String {
        val trimmed = rawOrSsml.trim()
        if (trimmed.startsWith("<speak", ignoreCase = true) && trimmed.contains("</speak>", ignoreCase = true)) {
            return trimmed
        }
        return EmotionSpeechEngine.buildSsmlString(
            cleanText = trimmed,
            emotion = emotion,
            gender = gender,
            effectivePitch = emotion.basePitchMultiplier,
            effectiveRate = emotion.baseSpeechRate,
            effectiveVolume = emotion.baseVolume
        )
    }

    internal fun selectVoiceCandidates(
        languageCode: String,
        gender: SpeakerGender
    ): List<String> {
        val normalizedLang = languageCode.ifBlank { TargetLanguage.HINDI.ttsLocaleTag }
        return if (normalizedLang.equals("hi-IN", ignoreCase = true)) {
            when (gender) {
                SpeakerGender.FEMALE_18 -> listOf("hi-IN-Neural2-A", "hi-IN-Neural2-D", "hi-IN-Wavenet-A")
                SpeakerGender.MALE_18 -> listOf("hi-IN-Neural2-B", "hi-IN-Neural2-C", "hi-IN-Wavenet-B")
            }
        } else {
            when (gender) {
                SpeakerGender.FEMALE_18 -> listOf("$normalizedLang-Neural2-A", "$normalizedLang-Wavenet-A")
                SpeakerGender.MALE_18 -> listOf("$normalizedLang-Neural2-B", "$normalizedLang-Wavenet-B")
            }
        }
    }

    private fun computePitchSemitones(
        emotion: SpeechEmotion,
        gender: SpeakerGender
    ): Double {
        val genderPitchOffset = if (gender == SpeakerGender.FEMALE_18) 1.5 else -1.5
        return (emotion.cloudPitchSemitones + genderPitchOffset).coerceIn(-12.0, 12.0)
    }

    private fun extractGoogleApiError(rawBody: String): String {
        return try {
            val root = JSONObject(rawBody)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: rawBody.take(180)
        } catch (_: Exception) {
            rawBody.take(180).ifBlank { "Unknown API error" }
        }
    }
}
