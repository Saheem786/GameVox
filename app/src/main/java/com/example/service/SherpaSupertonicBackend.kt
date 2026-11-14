package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Direct in-process sherpa-onnx Supertonic 3 Hindi Neural Speech Synthesis Engine.
 *
 * Runs 100% on-device without cloud APIs, keys, or credits.
 * Synthesizes PCM audio on a background coroutine and streams directly to [AudioTrack].
 */
class SherpaSupertonicBackend(
    private val context: Context,
    private val offlineTtsFactory: ((OfflineTtsConfig) -> OfflineTts)? = null
) : NeuralTtsEngine {

    companion object {
        private const val TAG = "SherpaSupertonic"
        const val BACKEND_ID = "sherpa_supertonic_direct"
        const val DEFAULT_SAMPLE_RATE = 24000
        const val MAX_SUPERTONIC_SPEAKERS = 10
    }

    override val backendId: String = BACKEND_ID

    private val synthesisMutex = Mutex()
    private val isStopping = AtomicBoolean(false)
    private val activePlayEpoch = AtomicLong(0L)

    @Volatile
    private var offlineTts: OfflineTts? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    override var isReady: Boolean = false
        private set

    @Volatile
    var initializationError: String? = null
        private set

    @Volatile
    var configuredNumSteps: Int = 5

    @Volatile
    var lastSynthesisRtf: Float = 0.0f
        private set

    @Volatile
    var lastTotalSynthesisTimeMs: Long = 0L
        private set

    data class StepBenchmarkResult(
        val numSteps: Int,
        val generationTimeMs: Long,
        val audioDurationMs: Long,
        val realTimeFactor: Float,
        val sampleCount: Int,
        val isRealHardwareExecution: Boolean = false,
        val executionNote: String = "Measured on Android device"
    )

    val isHardwareExecution: Boolean
        get() = OfflineTts.isNativeLibraryLoaded && (offlineTtsFactory == null)

    val isModelRuntimeInitialized: Boolean
        get() = isReady && offlineTts != null

    override val currentBackendLabel: String
        get() = if (isReady) {
            "Sherpa-ONNX Supertonic 3 (In-Process Neural • ${numSpeakers} Speakers • ${configuredNumSteps} Steps)"
        } else {
            "Sherpa-ONNX Supertonic 3 (Not Initialized / Model Missing)"
        }

    override val numSpeakers: Int
        get() = offlineTts?.numSpeakers() ?: MAX_SUPERTONIC_SPEAKERS

    /**
     * Initializes the in-process [OfflineTts] engine using on-device Supertonic 3 model files.
     */
    fun initialize(): Boolean {
        if (offlineTtsFactory != null) {
            return try {
                val tts = offlineTtsFactory.invoke(OfflineTtsConfig())
                offlineTts = tts
                isReady = true
                initializationError = null
                val count = tts.numSpeakers()
                com.example.data.VoiceProfileRepository.updateAvailableSpeakerCount(count)
                true
            } catch (t: Throwable) {
                isReady = false
                initializationError = t.localizedMessage ?: t.javaClass.simpleName
                false
            }
        }

        val modelDir = SupertonicModelManager.getModelDirectory(context)
        if (!SupertonicModelManager.isModelReady(context)) {
            isReady = false
            initializationError = "Model assets not found in ${modelDir.absolutePath}"
            return false
        }

        return try {
            val supertonicConfig = OfflineTtsSupertonicModelConfig(
                durationPredictor = File(modelDir, "duration_predictor.int8.onnx").absolutePath,
                textEncoder = File(modelDir, "text_encoder.int8.onnx").absolutePath,
                vectorEstimator = File(modelDir, "vector_estimator.int8.onnx").absolutePath,
                vocoder = File(modelDir, "vocoder.int8.onnx").absolutePath,
                ttsJson = File(modelDir, "tts.json").absolutePath,
                unicodeIndexer = File(modelDir, "unicode_indexer.bin").absolutePath,
                voiceStyle = File(modelDir, "voice.bin").absolutePath
            )

            val ttsConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = supertonicConfig,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )

            val tts = offlineTtsFactory?.invoke(ttsConfig) ?: OfflineTts(config = ttsConfig)
            offlineTts = tts
            isReady = true
            initializationError = null
            val count = tts.numSpeakers()
            com.example.data.VoiceProfileRepository.updateAvailableSpeakerCount(count)
            Log.i(TAG, "Initialized Sherpa-ONNX Supertonic 3 successfully. Speakers: $count, Rate: ${tts.sampleRate()}Hz")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize native OfflineTts", t)
            isReady = false
            initializationError = t.localizedMessage ?: t.javaClass.simpleName
            false
        }
    }

    override suspend fun synthesizeAndPlay(
        text: String,
        sid: Int,
        speed: Float,
        pitch: Float,
        epoch: Long,
        onFirstAudioStarted: (Long) -> Unit,
        onCompleted: () -> Unit,
        onError: (String) -> Unit
    ): Boolean = withContext(Dispatchers.Default) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            onCompleted()
            return@withContext true
        }

        synthesisMutex.withLock {
            if (!isReady && !initialize()) {
                val err = initializationError ?: "Supertonic engine not initialized"
                onError(err)
                return@withContext false
            }

            val tts = offlineTts
            if (tts == null) {
                onError("OfflineTts instance is null")
                return@withContext false
            }

            activePlayEpoch.set(epoch)
            isStopping.set(false)

            // Clamp sid strictly within 0 until numSpeakers()
            val availableSpeakers = tts.numSpeakers().coerceAtLeast(1)
            val validatedSid = sid.coerceIn(0, availableSpeakers - 1)
            val validatedSpeed = speed.coerceIn(0.65f, 1.45f)
            val sampleRate = tts.sampleRate().coerceAtLeast(8000)

            val startTimeMs = SystemClock.elapsedRealtime()
            var firstAudioNotified = false

            var audioTrack: AudioTrack? = null
            try {
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(sampleRate / 4)

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                activeAudioTrack = audioTrack
                audioTrack.play()

                val config = GenerationConfig(
                    sid = validatedSid,
                    speed = validatedSpeed,
                    silenceScale = 0.2f,
                    numSteps = configuredNumSteps,
                    extra = mapOf("lang" to "hi")
                )

                var totalSamplesWritten = 0

                // Generate and stream audio
                tts.generateWithConfigAndCallback(cleanText, config) { floatChunk ->
                    if (isStopping.get() || activePlayEpoch.get() != epoch) {
                        return@generateWithConfigAndCallback 0 // Stop generating
                    }

                    if (floatChunk.isNotEmpty()) {
                        totalSamplesWritten += floatChunk.size
                        val pcm16 = convertFloatsToPcm16Bytes(floatChunk)
                        if (!firstAudioNotified) {
                            firstAudioNotified = true
                            val firstChunkLatency = SystemClock.elapsedRealtime() - startTimeMs
                            audioTrack.write(pcm16, 0, pcm16.size)
                            val audioTrackWriteLatency = SystemClock.elapsedRealtime() - startTimeMs
                            // AudioTrack write is buffering in memory; sound is audible only after HAL DAC playout
                            val bufferFrames = (minBufferSize / 2).coerceAtLeast(1)
                            val halBufferDelayMs = (bufferFrames * 1000L) / sampleRate
                            val estimatedPlayoutLatency = audioTrackWriteLatency + halBufferDelayMs

                            onFirstAudioStarted(firstChunkLatency)
                            com.example.data.TranslatorStateRepository.updateDiagnosisMetrics(
                                ttsFirstAudioLatencyMs = firstChunkLatency,
                                ttsFirstPcmChunkLatencyMs = firstChunkLatency,
                                ttsAudioTrackWriteLatencyMs = audioTrackWriteLatency,
                                ttsEstimatedPlayoutLatencyMs = estimatedPlayoutLatency
                            )
                        } else {
                            audioTrack.write(pcm16, 0, pcm16.size)
                        }
                    }
                    1 // Continue
                }

                val totalSynthTime = SystemClock.elapsedRealtime() - startTimeMs
                lastTotalSynthesisTimeMs = totalSynthTime
                val audioDurationMs = (totalSamplesWritten * 1000L) / sampleRate.coerceAtLeast(8000)
                val rtf = if (audioDurationMs > 0) totalSynthTime.toFloat() / audioDurationMs else 0.0f
                lastSynthesisRtf = rtf

                if (!isStopping.get() && activePlayEpoch.get() == epoch) {
                    onCompleted()
                    true
                } else {
                    false
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error synthesizing with Supertonic", t)
                onError("Synthesis error: ${t.localizedMessage ?: t.javaClass.simpleName}")
                false
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (_: Exception) {
                }
                activeAudioTrack = null
            }
        }
    }

    /**
     * Benchmarks Supertonic synthesis quality and latency across different ODE step counts (e.g. 5 steps vs 8 steps).
     * Reports actual execution environment and never fabricates real hardware metrics in JVM/Robolectric test runs.
     */
    fun benchmarkSteps(text: String, stepsList: List<Int> = listOf(5, 8)): List<StepBenchmarkResult> {
        if (!isReady && !initialize()) {
            return emptyList()
        }
        val tts = offlineTts ?: return emptyList()
        val isHardware = OfflineTts.isNativeLibraryLoaded && (offlineTtsFactory == null)
        val note = if (isHardware) {
            "Measured on ARM64 Android device hardware (real ONNX execution)"
        } else {
            "JVM Stub execution (Physical ARM64 device required for real ONNX NPU/CPU latency and RTF)"
        }

        val results = mutableListOf<StepBenchmarkResult>()
        for (steps in stepsList) {
            val config = GenerationConfig(
                sid = 0,
                speed = 1.0f,
                silenceScale = 0.2f,
                numSteps = steps,
                extra = mapOf("lang" to "hi")
            )
            val startMs = SystemClock.elapsedRealtime()
            val audio = tts.generateWithConfig(text, config)
            val genTimeMs = SystemClock.elapsedRealtime() - startMs
            val audioDurationMs = (audio.samples.size * 1000L) / tts.sampleRate().coerceAtLeast(8000)
            val rtf = if (audioDurationMs > 0) genTimeMs.toFloat() / audioDurationMs else 0.0f
            results.add(
                StepBenchmarkResult(
                    numSteps = steps,
                    generationTimeMs = genTimeMs,
                    audioDurationMs = audioDurationMs,
                    realTimeFactor = rtf,
                    sampleCount = audio.samples.size,
                    isRealHardwareExecution = isHardware,
                    executionNote = note
                )
            )
        }
        return results
    }

    override fun stop() {
        isStopping.set(true)
        activePlayEpoch.incrementAndGet()
        try {
            activeAudioTrack?.pause()
            activeAudioTrack?.flush()
            activeAudioTrack?.stop()
        } catch (_: Exception) {
        }
    }

    override fun release() {
        stop()
        try {
            offlineTts?.release()
        } catch (_: Exception) {
        } finally {
            offlineTts = null
            isReady = false
        }
    }

    private fun convertFloatsToPcm16Bytes(floats: FloatArray): ByteArray {
        val bytes = ByteArray(floats.size * 2)
        var byteIdx = 0
        for (i in floats.indices) {
            val sample = floats[i]
            val s = (sample * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
            bytes[byteIdx++] = (s.toInt() and 0xFF).toByte()
            bytes[byteIdx++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
