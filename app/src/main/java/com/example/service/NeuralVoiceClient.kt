package com.example.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.example.engine.SpeakerHeuristicEngine
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ServerReferenceVoiceInfo(
    val voiceId: String,
    val fileName: String,
    val sha256: String,
    val sampleRate: Int,
    val durationSeconds: Float,
    val waveformRms: Float,
    val presentation: String,
    val description: String
)

data class NeuralServerHealthReport(
    val reachable: Boolean,
    val modelLoaded: Boolean,
    val status: String,
    val engineName: String,
    val modelId: String,
    val device: String,
    val sampleRate: Int,
    val referenceVoiceFilesCount: Int,
    val uniqueReferenceVoicesSha256Count: Int,
    val conditionalsCachedCount: Int,
    val roundTripLatencyMs: Long,
    val errorMessage: String = "",
    val referenceVoices: List<ServerReferenceVoiceInfo> = emptyList()
)

data class NeuralSynthesizeRequest(
    val text: String,
    val characterId: String,
    val referenceVoiceId: String,
    val languageId: String = "hi",
    val emotion: String = "neutral",
    val exaggeration: Float = 0.50f,
    val cfgWeight: Float = 0.40f,
    val temperature: Float = 0.80f,
    val requestId: String = "gv_${UUID.randomUUID().toString().take(8)}",
    val epoch: Long = 0L
)

data class ParsedWavData(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcmBytes: ByteArray,
    val waveformRms: Float,
    val durationMs: Long,
    val sha256Hex: String
)

sealed class NeuralSynthesisOutcome {
    data class Success(
        val rawWavBytes: ByteArray,
        val parsedWav: ParsedWavData,
        val serverRequestLatencyMs: Long,
        val serverInferenceLatencyMs: Long,
        val conditioningLatencyMs: Long,
        val conditioningCached: Boolean,
        val voiceId: String,
        val voiceSha256: String,
        val deviceUsed: String,
        val appliedExaggeration: Float,
        val appliedCfgWeight: Float
    ) : NeuralSynthesisOutcome()

    data class Error(
        val httpStatus: Int,
        val reason: String,
        val requestLatencyMs: Long
    ) : NeuralSynthesisOutcome()
}

/**
 * Local Wi-Fi HTTP client for the self-hosted Windows/Linux Python FastAPI Voice Server
 * powered by ResembleAI Chatterbox Multilingual V3 Dedicated Hindi (`ResembleAI/Chatterbox-Multilingual-hi`).
 *
 * Guarantees:
 * - Authenticates every request with `X-GameVox-Token` and `Authorization: Bearer <token>`.
 * - Strips leading character name tags before transmission so speaker labels are never spoken.
 * - Verifies returned WAV audio has non-silent PCM16 RMS >= [MIN_NON_SILENT_RMS] (`1e-4f`).
 * - Supports stale-dialogue epoch cancellation (`/v1/cancel`) and immediate `AudioTrack` interruption.
 * - Never returns fake or silent audio as a successful result.
 */
class NeuralVoiceClient {

    companion object {
        private const val TAG = "NeuralVoiceClient"
        const val DEFAULT_SERVER_URL = "http://192.168.1.100:8765"
        const val DEFAULT_AUTH_TOKEN = "gamevox-local-secret-2026"
        const val MIN_NON_SILENT_RMS = 1e-4f

        fun normalizeBaseUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return DEFAULT_SERVER_URL
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }

        fun computePcm16Rms(pcmBytes: ByteArray): Float {
            val sampleCount = pcmBytes.size / 2
            if (sampleCount <= 0) return 0f
            val shortBuf = ByteBuffer.wrap(pcmBytes, 0, sampleCount * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            var sumSq = 0.0
            for (i in 0 until sampleCount) {
                val norm = shortBuf.get(i) / 32768.0
                sumSq += norm * norm
            }
            return sqrt(sumSq / sampleCount).toFloat()
        }

        fun computeSha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * Parses a standard RIFF WAVE byte array, extracting sampleRate, channels, bitsPerSample,
         * raw PCM16 payload, normalized RMS amplitude, and duration in milliseconds.
         */
        fun parseWavBytes(wavBytes: ByteArray): ParsedWavData? {
            if (wavBytes.size < 44) return null
            val riff = String(wavBytes, 0, 4, Charsets.US_ASCII)
            val wave = String(wavBytes, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE") return null

            val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12
            var sampleRate = 24000
            var channels = 1
            var bitsPerSample = 16
            var dataOffset = -1
            var dataSize = 0

            while (offset + 8 <= wavBytes.size) {
                val chunkId = String(wavBytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = buf.getInt(offset + 4)
                val chunkBodyStart = offset + 8
                if (chunkSize < 0 || chunkBodyStart > wavBytes.size) break

                if (chunkId == "fmt " && chunkSize >= 16 && chunkBodyStart + 16 <= wavBytes.size) {
                    channels = buf.getShort(chunkBodyStart + 2).toInt().coerceAtLeast(1)
                    sampleRate = buf.getInt(chunkBodyStart + 4).coerceAtLeast(8000)
                    bitsPerSample = buf.getShort(chunkBodyStart + 14).toInt().coerceAtLeast(8)
                } else if (chunkId == "data") {
                    dataOffset = chunkBodyStart
                    dataSize = chunkSize.coerceAtMost(wavBytes.size - chunkBodyStart)
                    break
                }
                val paddedSize = if (chunkSize % 2 == 1) chunkSize + 1 else chunkSize
                offset = chunkBodyStart + paddedSize
            }

            if (dataOffset < 0 || dataSize <= 0) return null
            val pcmBytes = wavBytes.copyOfRange(dataOffset, dataOffset + dataSize)
            val rms = computePcm16Rms(pcmBytes)
            val bytesPerSampleFrame = (channels * (bitsPerSample / 8)).coerceAtLeast(2)
            val totalFrames = pcmBytes.size / bytesPerSampleFrame
            val durationMs = ((totalFrames * 1000L) / sampleRate.toLong()).coerceAtLeast(1L)

            return ParsedWavData(
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                pcmBytes = pcmBytes,
                waveformRms = rms,
                durationMs = durationMs,
                sha256Hex = computeSha256Hex(wavBytes)
            )
        }
    }

    private val activePlaybackEpoch = AtomicLong(0L)
    private val audioTrackLock = Any()

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    /**
     * Queries `GET /v1/health` on the local Chatterbox Python server.
     */
    suspend fun checkServerHealth(
        rawBaseUrl: String,
        authToken: String
    ): NeuralServerHealthReport = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawBaseUrl)
        val startMs = SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/v1/health")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 3500
                setRequestProperty("Accept", "application/json")
                applyAuthHeaders(this, authToken)
            }

            val code = conn.responseCode
            val rttMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            if (code != HttpURLConnection.HTTP_OK) {
                val errBody = readStreamSafely(conn, isError = true)
                return@withContext NeuralServerHealthReport(
                    reachable = true,
                    modelLoaded = false,
                    status = "HTTP_$code",
                    engineName = "Chatterbox Local Server",
                    modelId = "",
                    device = "",
                    sampleRate = 24000,
                    referenceVoiceFilesCount = 0,
                    uniqueReferenceVoicesSha256Count = 0,
                    conditionalsCachedCount = 0,
                    roundTripLatencyMs = rttMs,
                    errorMessage = "HTTP $code: $errBody"
                )
            }

            val body = readStreamSafely(conn, isError = false)
            val json = JSONObject(body)
            val voicesList = mutableListOf<ServerReferenceVoiceInfo>()
            val voicesArr = json.optJSONArray("reference_voices")
            if (voicesArr != null) {
                for (i in 0 until voicesArr.length()) {
                    val v = voicesArr.getJSONObject(i)
                    voicesList.add(
                        ServerReferenceVoiceInfo(
                            voiceId = v.optString("voice_id", ""),
                            fileName = v.optString("file_name", ""),
                            sha256 = v.optString("sha256", ""),
                            sampleRate = v.optInt("sample_rate", 24000),
                            durationSeconds = v.optDouble("duration_seconds", 0.0).toFloat(),
                            waveformRms = v.optDouble("waveform_rms", 0.0).toFloat(),
                            presentation = v.optString("presentation", "neutral"),
                            description = v.optString("description", "")
                        )
                    )
                }
            }

            NeuralServerHealthReport(
                reachable = true,
                modelLoaded = json.optBoolean("model_loaded", false),
                status = json.optString("status", "UNKNOWN"),
                engineName = json.optString("engine", "ResembleAI Chatterbox Multilingual V3 (Hindi)"),
                modelId = json.optString("model_id", "ResembleAI/Chatterbox-Multilingual-hi"),
                device = json.optString("device", "cpu"),
                sampleRate = json.optInt("sample_rate", 24000),
                referenceVoiceFilesCount = json.optInt("reference_voice_files_count", voicesList.size),
                uniqueReferenceVoicesSha256Count = json.optInt("unique_reference_voices_sha256_count", 0),
                conditionalsCachedCount = json.optInt("conditionals_cached_count", 0),
                roundTripLatencyMs = rttMs,
                errorMessage = json.optString("load_error", ""),
                referenceVoices = voicesList
            )
        } catch (e: Exception) {
            val rttMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            NeuralServerHealthReport(
                reachable = false,
                modelLoaded = false,
                status = "UNREACHABLE",
                engineName = "Chatterbox Local Server",
                modelId = "ResembleAI/Chatterbox-Multilingual-hi",
                device = "offline",
                sampleRate = 24000,
                referenceVoiceFilesCount = 0,
                uniqueReferenceVoicesSha256Count = 0,
                conditionalsCachedCount = 0,
                roundTripLatencyMs = rttMs,
                errorMessage = "${e.javaClass.simpleName}: ${e.localizedMessage ?: "Connection failed"}"
            )
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Sends `POST /v1/cancel` to abort any in-flight or stale synthesis on the Python server.
     */
    suspend fun cancelStaleSynthesis(
        rawBaseUrl: String,
        authToken: String,
        requestId: String = "",
        minValidEpoch: Long = 0L
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawBaseUrl)
        var conn: HttpURLConnection? = null
        try {
            val payload = JSONObject().apply {
                put("request_id", requestId)
                put("min_valid_epoch", minValidEpoch)
            }.toString().toByteArray(Charsets.UTF_8)

            val url = URL("$baseUrl/v1/cancel")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1500
                readTimeout = 1500
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                applyAuthHeaders(this, authToken)
            }
            conn.outputStream.use { it.write(payload) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Calls `POST /v1/synthesize` on the local Chatterbox FastAPI server, validates WAV headers
     * and non-silent PCM16 RMS energy (`>= 1e-4f`), and returns disaggregated server + inference timing.
     */
    suspend fun synthesizeHindiWav(
        rawBaseUrl: String,
        authToken: String,
        request: NeuralSynthesizeRequest
    ): NeuralSynthesisOutcome = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawBaseUrl)
        val cleanDialogue = SpeakerHeuristicEngine.stripLeadingCharacterName(request.text).trim()
        if (cleanDialogue.isBlank()) {
            return@withContext NeuralSynthesisOutcome.Error(
                httpStatus = 422,
                reason = "Dialogue text is empty after stripping speaker prefix",
                requestLatencyMs = 0L
            )
        }

        val startMs = SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        try {
            val jsonBody = JSONObject().apply {
                put("text", cleanDialogue)
                put("character_id", request.characterId)
                put("reference_voice_id", request.referenceVoiceId.removeSuffix(".wav"))
                put("language_id", request.languageId)
                put("emotion", request.emotion)
                put("exaggeration", request.exaggeration.toDouble())
                put("cfg_weight", request.cfgWeight.toDouble())
                put("temperature", request.temperature.toDouble())
                put("request_id", request.requestId)
                put("epoch", request.epoch)
            }.toString().toByteArray(Charsets.UTF_8)

            val url = URL("$baseUrl/v1/synthesize")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "audio/wav")
                applyAuthHeaders(this, authToken)
            }
            conn.outputStream.use { out ->
                out.write(jsonBody)
                out.flush()
            }

            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val errDetail = readStreamSafely(conn, isError = true)
                val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                return@withContext NeuralSynthesisOutcome.Error(
                    httpStatus = status,
                    reason = "Server HTTP $status: $errDetail",
                    requestLatencyMs = elapsed
                )
            }

            val wavBytes = conn.inputStream.use { it.readBytes() }
            val totalRequestMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)

            val parsed = parseWavBytes(wavBytes)
                ?: return@withContext NeuralSynthesisOutcome.Error(
                    httpStatus = 502,
                    reason = "Invalid or truncated WAV header from neural voice server (${wavBytes.size} bytes)",
                    requestLatencyMs = totalRequestMs
                )

            if (parsed.waveformRms < MIN_NON_SILENT_RMS) {
                return@withContext NeuralSynthesisOutcome.Error(
                    httpStatus = 500,
                    reason = String.format(
                        Locale.US,
                        "Rejected silent audio from server (RMS=%.6f < %.4f)",
                        parsed.waveformRms,
                        MIN_NON_SILENT_RMS
                    ),
                    requestLatencyMs = totalRequestMs
                )
            }

            val inferMs = conn.getHeaderField("X-GameVox-Inference-Ms")?.toLongOrNull() ?: totalRequestMs
            val condMs = conn.getHeaderField("X-GameVox-Conditioning-Ms")?.toLongOrNull() ?: 0L
            val condCached = conn.getHeaderField("X-GameVox-Conditioning-Cached")?.equals("true", ignoreCase = true) == true
            val voiceId = conn.getHeaderField("X-GameVox-Voice-Id").orEmpty().ifBlank { request.referenceVoiceId }
            val voiceSha = conn.getHeaderField("X-GameVox-Voice-Sha256").orEmpty()
            val deviceUsed = conn.getHeaderField("X-GameVox-Device").orEmpty().ifBlank { "cpu" }
            val exagUsed = conn.getHeaderField("X-GameVox-Exaggeration")?.toFloatOrNull() ?: request.exaggeration
            val cfgUsed = conn.getHeaderField("X-GameVox-Cfg-Weight")?.toFloatOrNull() ?: request.cfgWeight

            NeuralSynthesisOutcome.Success(
                rawWavBytes = wavBytes,
                parsedWav = parsed,
                serverRequestLatencyMs = totalRequestMs,
                serverInferenceLatencyMs = inferMs,
                conditioningLatencyMs = condMs,
                conditioningCached = condCached,
                voiceId = voiceId,
                voiceSha256 = voiceSha,
                deviceUsed = deviceUsed,
                appliedExaggeration = exagUsed,
                appliedCfgWeight = cfgUsed
            )
        } catch (e: Exception) {
            val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            NeuralSynthesisOutcome.Error(
                httpStatus = -1,
                reason = "${e.javaClass.simpleName}: ${e.localizedMessage ?: "Network error"}",
                requestLatencyMs = elapsed
            )
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    @Volatile
    private var attachedExoPlayerManager: HindiExoPlayerManager? = null

    fun attachExoPlayerManager(manager: HindiExoPlayerManager) {
        attachedExoPlayerManager = manager
    }

    /**
     * Synthesizes multi-sentence Hindi dialogue sentence-by-sentence and plays the resulting WAV
     * audio streams sequentially via [HindiExoPlayerManager] with clean transitions between sentences
     * and automatic cleanup of staged audio files.
     */
    suspend fun synthesizeAndPlaySentencesWithExoPlayer(
        exoPlayerManager: HindiExoPlayerManager,
        rawBaseUrl: String,
        authToken: String,
        request: NeuralSynthesizeRequest,
        exportWavFile: File? = null,
        onSynthesisTelemetry: (NeuralSynthesisOutcome.Success) -> Unit = {},
        onFirstAudioStarted: (Long) -> Unit = {},
        onCompleted: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        attachedExoPlayerManager = exoPlayerManager
        val currentEpoch = request.epoch
        activePlaybackEpoch.set(currentEpoch)
        exoPlayerManager.stopAndClearQueue()
        stopAudioTrackOnly()

        val cleanDialogue = SpeakerHeuristicEngine.stripLeadingCharacterName(request.text).trim()
        val sentences = HindiExoPlayerManager.splitIntoSentences(cleanDialogue)
        if (sentences.isEmpty()) {
            onError("Dialogue text is empty after sentence splitting")
            return@withContext false
        }

        val pipelineStartMs = SystemClock.elapsedRealtime()
        var firstAudioReported = false

        for ((index, sentence) in sentences.withIndex()) {
            if (activePlaybackEpoch.get() != currentEpoch) {
                exoPlayerManager.stopAndClearQueue()
                return@withContext false
            }

            val sentenceRequest = request.copy(
                text = sentence,
                requestId = "${request.requestId}_s$index"
            )
            when (val outcome = synthesizeHindiWav(rawBaseUrl, authToken, sentenceRequest)) {
                is NeuralSynthesisOutcome.Error -> {
                    exoPlayerManager.stopAndClearQueue()
                    onError(outcome.reason)
                    return@withContext false
                }

                is NeuralSynthesisOutcome.Success -> {
                    if (activePlaybackEpoch.get() != currentEpoch) {
                        exoPlayerManager.stopAndClearQueue()
                        return@withContext false
                    }
                    if (index == 0) {
                        onSynthesisTelemetry(outcome)
                    }
                    val playedOk = exoPlayerManager.playWavBytesStream(
                        wavBytes = outcome.rawWavBytes,
                        sentenceText = sentence,
                        epoch = currentEpoch,
                        flushExistingQueue = (index == 0),
                        exportWavFile = if (index == 0) exportWavFile else null,
                        onFirstAudioStarted = {
                            if (!firstAudioReported) {
                                firstAudioReported = true
                                val totalFirstMs = (SystemClock.elapsedRealtime() - pipelineStartMs).coerceAtLeast(1L)
                                onFirstAudioStarted(totalFirstMs)
                            }
                        },
                        onError = onError
                    )
                    if (!playedOk) {
                        return@withContext false
                    }
                }
            }
        }

        if (activePlaybackEpoch.get() == currentEpoch) {
            onCompleted()
            return@withContext true
        }
        false
    }

    /**
     * Synthesizes Hindi speech via the local Chatterbox FastAPI server, exports the latest WAV
     * for A/B inspection if [exportWavFile] is provided, and plays the synthesized stream via
     * [HindiExoPlayerManager] (when attached or provided) or [AudioTrack] with full hardware buffer
     * drain and epoch cancellation support.
     */
    suspend fun synthesizeAndPlay(
        rawBaseUrl: String,
        authToken: String,
        request: NeuralSynthesizeRequest,
        exportWavFile: File? = null,
        exoPlayerManager: HindiExoPlayerManager? = attachedExoPlayerManager,
        onSynthesisTelemetry: (NeuralSynthesisOutcome.Success) -> Unit = {},
        onFirstAudioStarted: (Long) -> Unit = {},
        onCompleted: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (exoPlayerManager != null) {
            return@withContext synthesizeAndPlaySentencesWithExoPlayer(
                exoPlayerManager = exoPlayerManager,
                rawBaseUrl = rawBaseUrl,
                authToken = authToken,
                request = request,
                exportWavFile = exportWavFile,
                onSynthesisTelemetry = onSynthesisTelemetry,
                onFirstAudioStarted = onFirstAudioStarted,
                onCompleted = onCompleted,
                onError = onError
            )
        }

        val currentEpoch = request.epoch
        activePlaybackEpoch.set(currentEpoch)
        stopAudioTrackOnly()

        val pipelineStartMs = SystemClock.elapsedRealtime()
        when (val outcome = synthesizeHindiWav(rawBaseUrl, authToken, request)) {
            is NeuralSynthesisOutcome.Error -> {
                onError(outcome.reason)
                return@withContext false
            }

            is NeuralSynthesisOutcome.Success -> {
                if (activePlaybackEpoch.get() != currentEpoch) {
                    Log.i(TAG, "Discarding stale neural server audio for epoch $currentEpoch")
                    return@withContext false
                }

                if (exportWavFile != null) {
                    try {
                        exportWavFile.parentFile?.mkdirs()
                        FileOutputStream(exportWavFile).use { it.write(outcome.rawWavBytes) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to export A/B wav file: ${e.message}")
                    }
                }

                onSynthesisTelemetry(outcome)

                val wav = outcome.parsedWav
                val channelMask = if (wav.channels >= 2) {
                    AudioFormat.CHANNEL_OUT_STEREO
                } else {
                    AudioFormat.CHANNEL_OUT_MONO
                }
                val minBuf = AudioTrack.getMinBufferSize(
                    wav.sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val track = try {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(wav.sampleRate)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(channelMask)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } catch (e: Exception) {
                    onError("AudioTrack creation failed: ${e.localizedMessage}")
                    return@withContext false
                }

                synchronized(audioTrackLock) {
                    activeAudioTrack = track
                }

                try {
                    track.play()
                    val firstPlayableMs = (SystemClock.elapsedRealtime() - pipelineStartMs).coerceAtLeast(1L)
                    onFirstAudioStarted(firstPlayableMs)

                    val pcm = wav.pcmBytes
                    val chunkBytes = 4096
                    var offset = 0
                    while (offset < pcm.size) {
                        if (activePlaybackEpoch.get() != currentEpoch) {
                            stopAudioTrackOnly()
                            return@withContext false
                        }
                        val toWrite = minOf(chunkBytes, pcm.size - offset)
                        val written = track.write(pcm, offset, toWrite)
                        if (written < 0) break
                        offset += written
                    }

                    // Drain queued hardware frames before releasing AudioTrack
                    val bytesPerFrame = (wav.channels * 2).coerceAtLeast(2)
                    val totalFrames = pcm.size / bytesPerFrame
                    val drainTimeoutMs = wav.durationMs + 1500L
                    val drainStartMs = SystemClock.elapsedRealtime()
                    while (activePlaybackEpoch.get() == currentEpoch) {
                        val elapsedDrain = SystemClock.elapsedRealtime() - drainStartMs
                        val headFrames = try {
                            track.playbackHeadPosition
                        } catch (_: Exception) {
                            totalFrames
                        }
                        if (headFrames >= totalFrames || elapsedDrain >= drainTimeoutMs || elapsedDrain >= wav.durationMs) {
                            break
                        }
                        delay(20L)
                    }

                    if (activePlaybackEpoch.get() == currentEpoch) {
                        onCompleted()
                    }
                    true
                } catch (e: Exception) {
                    onError("Neural audio playback error: ${e.localizedMessage}")
                    false
                } finally {
                    synchronized(audioTrackLock) {
                        if (activeAudioTrack === track) {
                            activeAudioTrack = null
                        }
                    }
                    try {
                        track.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        track.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun stop() {
        activePlaybackEpoch.incrementAndGet()
        attachedExoPlayerManager?.stopAndClearQueue()
        stopAudioTrackOnly()
    }

    private fun stopAudioTrackOnly() {
        val track = synchronized(audioTrackLock) {
            val t = activeAudioTrack
            activeAudioTrack = null
            t
        }
        if (track != null) {
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (_: Exception) {
            }
            try {
                track.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun applyAuthHeaders(conn: HttpURLConnection, authToken: String) {
        val clean = authToken.trim()
        if (clean.isNotEmpty()) {
            conn.setRequestProperty("X-GameVox-Token", clean)
            conn.setRequestProperty("Authorization", "Bearer $clean")
        }
    }

    private fun readStreamSafely(conn: HttpURLConnection, isError: Boolean): String {
        return try {
            val stream = if (isError) conn.errorStream else conn.inputStream
            if (stream == null) return ""
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText().trim() }
        } catch (_: Exception) {
            ""
        }
    }
}
