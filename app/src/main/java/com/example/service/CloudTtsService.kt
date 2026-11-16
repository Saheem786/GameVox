package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.example.BuildConfig
import com.example.data.SpeakerGender
import com.example.data.TargetLanguage
import com.example.engine.EmotionAnalysisResult
import com.example.engine.SpeechEmotion
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Selectable Speech Synthesis Engine in GameVox Hindi:
 * 1. [ANDROID_SSML_NETWORK]: Default Phone TTS engine with SSML + High-Quality Network Voice
 * 2. [GOOGLE_CLOUD_TTS]: Google Cloud Text-to-Speech API (Neural2 / WaveNet voices with full SSML)
 * 3. [ELEVENLABS]: ElevenLabs Multilingual v2 AI voices with emotion-tuned voice settings
 * 4. [OPENAI_TTS]: OpenAI Audio Speech API (`gpt-4o-mini-tts` with expressive emotional instructions)
 */
enum class CloudTtsProvider(
    val code: String,
    val displayLabel: String,
    val subtitle: String,
    val requiresApiKey: Boolean
) {
    ANDROID_SSML_NETWORK(
        code = "android_ssml",
        displayLabel = "User-Selected Android TTS (Free)",
        subtitle = "Supports sherpa-onnx Supertonic 3 Hindi & system TTS (No API key or billing)",
        requiresApiKey = false
    ),
    GOOGLE_CLOUD_TTS(
        code = "google_cloud_tts",
        displayLabel = "Google Cloud TTS (Disabled)",
        subtitle = "Paid cloud voice APIs are disabled in GameVox",
        requiresApiKey = false
    ),
    ELEVENLABS(
        code = "elevenlabs",
        displayLabel = "ElevenLabs (Disabled)",
        subtitle = "Paid cloud voice APIs are disabled in GameVox",
        requiresApiKey = false
    ),
    OPENAI_TTS(
        code = "openai_tts",
        displayLabel = "OpenAI TTS (Disabled)",
        subtitle = "Paid cloud voice APIs are disabled in GameVox",
        requiresApiKey = false
    );

    companion object {
        fun fromCode(code: String?): CloudTtsProvider {
            return ANDROID_SSML_NETWORK
        }
    }
}

/**
 * Paid cloud TTS APIs are permanently disabled in GameVox Free Neural Voice Edition.
 * GameVox requires zero API keys, zero credits, and zero billing/subscription plans.
 */
object CloudTtsService {

    const val PAID_CLOUD_TTS_ENABLED = false
    private const val TAG = "CloudTtsService"
    private const val OPENAI_TTS_ENDPOINT =
        "https://api.openai.com/v1/audio/speech"

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    fun hasGoogleCloudKey(userKey: String = ""): Boolean = false

    fun hasElevenLabsKey(userKey: String = ""): Boolean = false

    fun hasOpenAiKey(userKey: String = ""): Boolean = false

    fun stopPlayback() {
        try {
            activeMediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {
        } finally {
            activeMediaPlayer = null
        }
    }

    fun isPlaying(): Boolean {
        return try {
            activeMediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Paid cloud voice APIs are disabled; always returns `false` without making any paid HTTP calls.
     */
    suspend fun synthesizeAndPlay(
        context: Context,
        provider: CloudTtsProvider,
        targetLanguage: TargetLanguage,
        gender: SpeakerGender,
        emotionResult: EmotionAnalysisResult,
        apiKeyOverride: String = "",
        onStarted: (voiceModelLabel: String) -> Unit,
        onCompleted: () -> Unit,
        onError: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (!PAID_CLOUD_TTS_ENABLED) {
            Log.i(TAG, "Paid voice API ($provider) is disabled — using free user-selected Android TTS")
            return@withContext false
        }
        val dynamicKey = apiKeyOverride.trim()
        val mp3Result: Pair<ByteArray, String>? = when (provider) {
            CloudTtsProvider.GOOGLE_CLOUD_TTS -> {
                val resolvedKey = dynamicKey.ifEmpty { BuildConfig.GOOGLE_CLOUD_TTS_API_KEY.trim() }
                if (!GoogleCloudTtsService.hasValidApiKey(resolvedKey)) return@withContext false
                val googleClient = GoogleCloudTtsService(apiKey = resolvedKey)
                when (
                    val result = googleClient.synthesizeWithEmotionResult(
                        targetLanguage = targetLanguage,
                        gender = gender,
                        emotionResult = emotionResult,
                        audioEncoding = CloudAudioEncoding.MP3
                    )
                ) {
                    is CloudTtsSynthesisResult.Success -> result.audioBytes to result.voiceName
                    is CloudTtsSynthesisResult.Failure -> {
                        Log.w(TAG, "Google Cloud TTS fallback triggered: ${result.message}")
                        null
                    }
                }
            }
            CloudTtsProvider.ELEVENLABS -> {
                if (!ElevenLabsTtsService.hasValidApiKey(dynamicKey)) return@withContext false
                val elevenLabsClient = ElevenLabsTtsService(apiKey = dynamicKey)
                when (
                    val result = elevenLabsClient.synthesizeEmotionAwareSpeech(
                        gender = gender,
                        emotionResult = emotionResult
                    )
                ) {
                    is CloudTtsSynthesisResult.Success -> result.audioBytes to result.voiceName
                    is CloudTtsSynthesisResult.Failure -> {
                        Log.w(TAG, "ElevenLabs TTS fallback triggered: ${result.message}")
                        null
                    }
                }
            }
            CloudTtsProvider.OPENAI_TTS -> {
                val resolvedKey = dynamicKey.ifEmpty { BuildConfig.OPENAI_TTS_API_KEY.trim() }
                if (resolvedKey.isEmpty() || resolvedKey.startsWith("MY_")) return@withContext false
                synthesizeViaOpenAiTts(
                    apiKey = resolvedKey,
                    gender = gender,
                    emotionResult = emotionResult
                )
            }
            CloudTtsProvider.ANDROID_SSML_NETWORK -> return@withContext false
        }

        if (mp3Result == null || mp3Result.first.isEmpty()) {
            return@withContext false
        }

        val (audioBytes, voiceModelLabel) = mp3Result
        val outFile = File(context.cacheDir, "gamevox_cloud_tts_${System.currentTimeMillis()}.mp3")
        return@withContext try {
            FileOutputStream(outFile).use { fos ->
                fos.write(audioBytes)
            }
            withContext(Dispatchers.Main) {
                playMp3File(
                    audioFile = outFile,
                    volume = emotionResult.effectiveVolume,
                    onStarted = { onStarted(voiceModelLabel) },
                    onCompleted = {
                        outFile.delete()
                        onCompleted()
                    },
                    onError = { msg ->
                        outFile.delete()
                        onError(msg)
                    }
                )
            }
            true
        } catch (e: Exception) {
            outFile.delete()
            Log.w(TAG, "Cloud TTS playback setup failed", e)
            false
        }
    }

    /**
     * Calls OpenAI Audio Speech API (`v1/audio/speech`) with `gpt-4o-mini-tts` and natural
     * emotional delivery instructions matched to the character's gender/age and detected emotion.
     */
    private fun synthesizeViaOpenAiTts(
        apiKey: String,
        gender: SpeakerGender,
        emotionResult: EmotionAnalysisResult
    ): Pair<ByteArray, String>? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(OPENAI_TTS_ENDPOINT)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val voiceId = if (gender == SpeakerGender.FEMALE_18) "nova" else "onyx"
            val characterDesc = if (gender == SpeakerGender.FEMALE_18) {
                "an expressive 18-year-old young woman"
            } else {
                "an expressive 18-year-old young man"
            }
            val emotionInstruction = when (emotionResult.emotion) {
                SpeechEmotion.EXCITED -> "Speak as $characterDesc with bright excitement, high energy, and lively urgency."
                SpeechEmotion.HAPPY -> "Speak as $characterDesc with warm happiness, friendly energy, and a cheerful tone."
                SpeechEmotion.ANGRY -> "Speak as $characterDesc with intense frustration, firmness, and emotional urgency."
                SpeechEmotion.FEAR -> "Speak as $characterDesc with breathless urgency, fear, and anxious concern."
                SpeechEmotion.SAD -> "Speak as $characterDesc with a soft, sad, hesitant tone and natural emotional pauses."
                SpeechEmotion.WHISPER -> "Speak as $characterDesc in a hushed, secretive, quiet whisper with slow pacing."
                SpeechEmotion.SERIOUS -> "Speak as $characterDesc with a composed, serious, tactical, and steady tone."
                SpeechEmotion.NEUTRAL -> "Speak as $characterDesc in a warm, natural conversational Indian Hindi/Hinglish tone."
            }

            val payload = JSONObject().apply {
                put("model", "gpt-4o-mini-tts")
                put("input", emotionResult.cleanSpokenText)
                put("voice", voiceId)
                put("instructions", emotionInstruction)
                put("speed", emotionResult.effectiveSpeechRate.toDouble().coerceIn(0.65, 1.35))
                put("response_format", "mp3")
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            if (connection.responseCode in 200..299) {
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty()) {
                    bytes to "OpenAI gpt-4o-mini-tts ($voiceId)"
                } else {
                    null
                }
            } else {
                Log.w(TAG, "OpenAI TTS HTTP ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenAI TTS request failed", e)
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun playMp3File(
        audioFile: File,
        volume: Float,
        onStarted: () -> Unit,
        onCompleted: () -> Unit,
        onError: (String) -> Unit
    ) {
        stopPlayback()
        val safeVol = volume.coerceIn(0.2f, 1.0f)
        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(audioFile.absolutePath)
                setVolume(safeVol, safeVol)
                setOnPreparedListener { mp ->
                    onStarted()
                    mp.start()
                }
                setOnCompletionListener { mp ->
                    mp.release()
                    if (activeMediaPlayer === mp) {
                        activeMediaPlayer = null
                    }
                    onCompleted()
                }
                setOnErrorListener { mp, what, extra ->
                    mp.release()
                    if (activeMediaPlayer === mp) {
                        activeMediaPlayer = null
                    }
                    onError("MediaPlayer error $what/$extra")
                    true
                }
                prepareAsync()
            }
            activeMediaPlayer = player
        } catch (e: Exception) {
            onError(e.message ?: "Unable to play Cloud TTS MP3")
        }
    }
}
