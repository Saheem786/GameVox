package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.SpeakerGender
import com.example.data.TargetLanguage
import com.example.data.TranslatorStateRepository
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech as MediaPipeTextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.audio.texttospeech.VoiceInfo
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class KokoroSpeakOutcome {
    data class Success(
        val voiceLabel: String,
        val initLatencyMs: Long,
        val firstAudioLatencyMs: Long,
        val playbackDurationMs: Long,
        val activeDelegate: String
    ) : KokoroSpeakOutcome()

    data class FallbackToAndroidTts(
        val reason: String
    ) : KokoroSpeakOutcome()
}

data class KokoroModelDiscovery(
    val resolvedFile: File?,
    val searchedPaths: List<String>,
    val statusSummary: String
)

/**
 * Optional on-device **Kokoro-82M** integration powered by Google's official MediaPipe Tasks Audio
 * API (`com.google.mediapipe:tasks-audio:1.2.3`) using external `.litertlm` bundles
 * (`kokoro_int8_gpu_fp16.litertlm` or `kokoro_int8_fp16.litertlm`).
 *
 * Weights (~295 MB) are NEVER bundled into the APK; users import a `.litertlm` file via Android's
 * system file picker (`OpenDocument`) or place it in the app's `filesDir/kokoro_models` directory.
 * If absent or unsupported for the target language, returns [KokoroSpeakOutcome.FallbackToAndroidTts]
 * so GameVox falls back seamlessly to the user-selected Android TTS engine (e.g. Supertonic 3 Hindi).
 */
class KokoroMediaPipeTtsEngine private constructor(context: Context) {

    companion object {
        private const val TAG = "KokoroMediaPipeTts"
        private const val MODEL_SUBDIR = "kokoro_models"
        val DEFAULT_MODEL_FILENAMES = listOf(
            "kokoro_int8_gpu_fp16.litertlm",
            "kokoro_int8_fp16.litertlm",
            "kokoro.litertlm"
        )

        @Volatile
        private var INSTANCE: KokoroMediaPipeTtsEngine? = null

        fun getInstance(context: Context): KokoroMediaPipeTtsEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: KokoroMediaPipeTtsEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        internal fun resetInstanceForTesting() {
            synchronized(this) {
                INSTANCE?.closeEngineInternal()
                INSTANCE = null
            }
        }

        /**
         * Converts normalized [-1f, 1f] float32 PCM samples from MediaPipe `AudioResult.samples`
         * into signed 16-bit little-endian PCM shorts for universal `AudioTrack` playback.
         */
        fun floatSamplesToPcm16(samples: FloatArray, volume: Float = 1.0f): ShortArray {
            val gain = volume.coerceIn(0.0f, 1.0f)
            return ShortArray(samples.size) { idx ->
                val clamped = (samples[idx] * gain).coerceIn(-1.0f, 1.0f)
                (clamped * 32767.0f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        fun pickBestVoiceForGender(
            voices: List<VoiceInfo>,
            targetLanguage: TargetLanguage,
            gender: SpeakerGender
        ): VoiceInfo? {
            if (voices.isEmpty()) return null
            val langMatches = voices.filter { voice ->
                val loc = voice.locale.lowercase(Locale.ROOT)
                loc.startsWith(targetLanguage.code.lowercase(Locale.ROOT)) ||
                    (targetLanguage == TargetLanguage.HINDI && (loc.startsWith("hi") || loc == "h"))
            }
            val pool = langMatches.ifEmpty { voices }
            val femaleHints = listOf("_af", "af_", "female", "hf_", "bella", "sarah", "nicole", "sky", "aoede", "heart", "kore")
            val maleHints = listOf("_am", "am_", "male", "hm_", "adam", "michael", "fenrir", "puck", "echo", "onyx", "eric")
            val hints = if (gender == SpeakerGender.FEMALE_18) femaleHints else maleHints
            return pool.firstOrNull { info ->
                val idLower = info.voiceId.lowercase(Locale.ROOT)
                val nameLower = info.displayName.lowercase(Locale.ROOT)
                hints.any { hint -> idLower.contains(hint) || nameLower.contains(hint) }
            } ?: pool.firstOrNull()
        }
    }

    private val appContext: Context = context.applicationContext
    private val engineMutex = Mutex()
    private val audioTrackLock = Any()

    @Volatile
    private var mediaPipeTts: MediaPipeTextToSpeech? = null

    @Volatile
    private var loadedModelPath: String = ""

    @Volatile
    private var loadedDelegateLabel: String = ""

    @Volatile
    private var lastInitLatencyMs: Long = 0L

    @Volatile
    private var cachedVoices: List<VoiceInfo> = emptyList()

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    private var activePlaybackEpoch: Long = 0L

    fun discoverModelFile(configuredPath: String = ""): KokoroModelDiscovery {
        val candidates = mutableListOf<File>()
        val trimmed = configuredPath.trim()
        if (trimmed.isNotEmpty()) {
            candidates += File(trimmed)
        }
        val internalDir = File(appContext.filesDir, MODEL_SUBDIR)
        DEFAULT_MODEL_FILENAMES.forEach { name ->
            candidates += File(internalDir, name)
            candidates += File(appContext.filesDir, name)
        }
        appContext.getExternalFilesDir(null)?.let { extDir ->
            DEFAULT_MODEL_FILENAMES.forEach { name ->
                candidates += File(File(extDir, MODEL_SUBDIR), name)
                candidates += File(extDir, name)
            }
        }

        val resolved = candidates.firstOrNull { it.exists() && it.isFile && it.length() > 1024L }
        val status = if (resolved != null) {
            val sizeMb = (resolved.length() / (1024.0 * 1024.0)).roundToInt()
            "Model ready: ${resolved.name} (~${sizeMb} MB)"
        } else {
            "Optional: No .litertlm model file imported yet (import kokoro_int8_gpu_fp16.litertlm or kokoro_int8_fp16.litertlm)"
        }
        return KokoroModelDiscovery(
            resolvedFile = resolved,
            searchedPaths = candidates.map { it.absolutePath },
            statusSummary = status
        )
    }

    suspend fun importModelFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val rawName = queryDisplayName(uri)?.takeIf { it.isNotBlank() } ?: "kokoro_imported.litertlm"
            val safeName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            require(safeName.endsWith(".litertlm", ignoreCase = true)) {
                "Please select a valid .litertlm model file (e.g. kokoro_int8_gpu_fp16.litertlm)"
            }

            val destDir = File(appContext.filesDir, MODEL_SUBDIR).apply { mkdirs() }
            val destFile = File(destDir, safeName)
            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("Could not open selected .litertlm URI")

            input.use { stream ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            require(destFile.exists() && destFile.length() > 1024L) {
                "Imported file appears empty or incomplete"
            }

            closeEngineInternal()
            val sizeMb = (destFile.length() / (1024.0 * 1024.0)).roundToInt()
            TranslatorStateRepository.updateKokoroConfig(
                context = appContext,
                modelPath = destFile.absolutePath,
                statusMessage = "Imported ${destFile.name} (~${sizeMb} MB) — ready for MediaPipe synthesis"
            )
            destFile
        }.onFailure { err ->
            Log.w(TAG, "Failed importing Kokoro .litertlm file", err)
            TranslatorStateRepository.updateKokoroStatusMessage(
                "Kokoro import failed: ${err.localizedMessage ?: "invalid file"}"
            )
        }
    }

    suspend fun clearImportedModel() = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            closeEngineInternal()
            val currentPath = TranslatorStateRepository.state.value.kokoroModelPath
            if (currentPath.isNotBlank()) {
                val f = File(currentPath)
                if (f.absolutePath.startsWith(appContext.filesDir.absolutePath) && f.exists()) {
                    runCatching { f.delete() }
                }
            }
            TranslatorStateRepository.updateKokoroConfig(
                context = appContext,
                modelPath = "",
                statusMessage = "Optional: No .litertlm model loaded (Android TTS remains active)"
            )
        }
    }

    suspend fun synthesizeAndPlay(
        text: String,
        targetLanguage: TargetLanguage,
        gender: SpeakerGender,
        speakingRate: Float,
        volume: Float,
        configuredModelPath: String,
        useGpuDelegate: Boolean,
        isBenchmarkRun: Boolean = false
    ): KokoroSpeakOutcome = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return@withContext KokoroSpeakOutcome.FallbackToAndroidTts("Empty subtitle text")
        }

        val discovery = discoverModelFile(configuredModelPath)
        val modelFile = discovery.resolvedFile
        if (modelFile == null) {
            TranslatorStateRepository.updateKokoroStatusMessage(discovery.statusSummary)
            if (isBenchmarkRun) {
                TranslatorStateRepository.updateTtsLatencyBenchmarks(
                    isRunningBenchmark = false,
                    summary = "Cannot benchmark Kokoro-82M: no .litertlm model file loaded."
                )
            }
            return@withContext KokoroSpeakOutcome.FallbackToAndroidTts(
                "Kokoro .litertlm model file is not loaded; falling back to Android TTS"
            )
        }

        val playbackEpoch = synchronized(audioTrackLock) { ++activePlaybackEpoch }
        stopAudioTrackOnly()

        return@withContext engineMutex.withLock {
            try {
                val engine = ensureInitializedLocked(modelFile, useGpuDelegate)
                val voices = cachedVoices
                val selectedVoice = pickBestVoiceForGender(voices, targetLanguage, gender)

                val synthOptions = SynthesisOptions(
                    voiceId = selectedVoice?.voiceId,
                    locale = selectedVoice?.locale ?: targetLanguage.ttsLocaleTag
                )

                val synthStartElapsed = SystemClock.elapsedRealtime()
                val audioResult = engine.synthesizeAsync(cleanText, synthOptions)
                val firstAudioLatencyMs = (SystemClock.elapsedRealtime() - synthStartElapsed).coerceAtLeast(0L)

                if (audioResult.samples.isEmpty()) {
                    val reason = "Kokoro-82M returned 0 audio samples for ${targetLanguage.displayName}"
                    TranslatorStateRepository.updateKokoroStatusMessage(reason)
                    return@withLock KokoroSpeakOutcome.FallbackToAndroidTts(reason)
                }

                if (isBenchmarkRun) {
                    TranslatorStateRepository.updateTtsLatencyBenchmarks(
                        initLatencyMs = lastInitLatencyMs,
                        firstAudioLatencyMs = firstAudioLatencyMs,
                        summary = "Kokoro-82M ($loadedDelegateLabel) synth in ${firstAudioLatencyMs}ms — playing..."
                    )
                }

                val playStartElapsed = SystemClock.elapsedRealtime()
                val played = playPcmSamples(
                    samples = audioResult.samples,
                    sampleRateHz = audioResult.sampleRateHz.coerceAtLeast(8000),
                    volume = volume,
                    expectedEpoch = playbackEpoch
                )
                val playbackDurationMs = (SystemClock.elapsedRealtime() - playStartElapsed).coerceAtLeast(0L)
                val voiceLabel = "Kokoro-82M (${selectedVoice?.displayName ?: selectedVoice?.voiceId ?: loadedDelegateLabel})"

                TranslatorStateRepository.updateKokoroStatusMessage(
                    "$voiceLabel active • $loadedDelegateLabel"
                )
                if (isBenchmarkRun) {
                    TranslatorStateRepository.updateTtsLatencyBenchmarks(
                        initLatencyMs = lastInitLatencyMs,
                        firstAudioLatencyMs = firstAudioLatencyMs,
                        speechDurationMs = playbackDurationMs,
                        isRunningBenchmark = false,
                        summary = "Kokoro-82M ($loadedDelegateLabel): Init ${lastInitLatencyMs}ms • Synth ${firstAudioLatencyMs}ms • Playback ${playbackDurationMs}ms"
                    )
                }

                if (played) {
                    KokoroSpeakOutcome.Success(
                        voiceLabel = voiceLabel,
                        initLatencyMs = lastInitLatencyMs,
                        firstAudioLatencyMs = firstAudioLatencyMs,
                        playbackDurationMs = playbackDurationMs,
                        activeDelegate = loadedDelegateLabel
                    )
                } else {
                    KokoroSpeakOutcome.FallbackToAndroidTts("Kokoro AudioTrack playback cancelled or failed")
                }
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: e.javaClass.simpleName
                Log.w(TAG, "Kokoro MediaPipe synthesis failed; falling back to Android TTS: $msg", e)
                TranslatorStateRepository.updateKokoroStatusMessage(
                    "Kokoro fallback (${e.javaClass.simpleName}): $msg"
                )
                if (isBenchmarkRun) {
                    TranslatorStateRepository.updateTtsLatencyBenchmarks(
                        isRunningBenchmark = false,
                        summary = "Kokoro-82M synthesis failed: $msg"
                    )
                }
                KokoroSpeakOutcome.FallbackToAndroidTts(msg)
            }
        }
    }

    private fun ensureInitializedLocked(modelFile: File, preferGpu: Boolean): MediaPipeTextToSpeech {
        val desiredPath = modelFile.absolutePath
        val existing = mediaPipeTts
        if (existing != null && loadedModelPath == desiredPath) {
            return existing
        }
        closeEngineInternal()

        val cacheDirPath = File(appContext.cacheDir, "kokoro_mediapipe_cache").apply { mkdirs() }.absolutePath
        val initStart = SystemClock.elapsedRealtime()
        val delegatesToTry = if (preferGpu) listOf(Delegate.GPU, Delegate.CPU) else listOf(Delegate.CPU)
        var lastError: Exception? = null

        for (delegate in delegatesToTry) {
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(desiredPath)
                    .setDelegate(delegate)
                    .build()
                val ttsOptions = TextToSpeechOptions(
                    baseOptions = baseOptions,
                    cacheDir = cacheDirPath
                )
                val created = MediaPipeTextToSpeech.create(ttsOptions)
                val initElapsed = (SystemClock.elapsedRealtime() - initStart).coerceAtLeast(0L)
                mediaPipeTts = created
                loadedModelPath = desiredPath
                loadedDelegateLabel = if (delegate == Delegate.GPU) "GPU Delegate" else "CPU Fallback"
                lastInitLatencyMs = initElapsed
                cachedVoices = runCatching { created.listVoices() }.getOrDefault(emptyList())
                TranslatorStateRepository.updateTtsLatencyBenchmarks(initLatencyMs = initElapsed)
                TranslatorStateRepository.updateKokoroStatusMessage(
                    "Kokoro-82M ready ($loadedDelegateLabel, ${cachedVoices.size} voices)"
                )
                return created
            } catch (e: Exception) {
                Log.w(TAG, "Kokoro init with $delegate failed", e)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Unable to initialize MediaPipe TextToSpeech")
    }

    private fun playPcmSamples(
        samples: FloatArray,
        sampleRateHz: Int,
        volume: Float,
        expectedEpoch: Long
    ): Boolean {
        val pcm16 = floatSamplesToPcm16(samples, volume)
        if (pcm16.isEmpty()) return false
        val byteCount = pcm16.size * 2
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(byteCount, minBuf))
            .build()

        synchronized(audioTrackLock) {
            if (activePlaybackEpoch != expectedEpoch) {
                track.release()
                return false
            }
            activeAudioTrack = track
        }

        return try {
            val written = track.write(pcm16, 0, pcm16.size)
            if (written <= 0) {
                false
            } else {
                track.play()
                val durationMs = ((pcm16.size.toDouble() / sampleRateHz.toDouble()) * 1000.0).toLong().coerceAtLeast(50L)
                val deadline = SystemClock.elapsedRealtime() + durationMs + 500L
                while (SystemClock.elapsedRealtime() < deadline) {
                    val sameEpoch = synchronized(audioTrackLock) { activePlaybackEpoch == expectedEpoch }
                    if (!sameEpoch) break
                    if (track.playbackHeadPosition >= pcm16.size) break
                    Thread.sleep(25L)
                }
                true
            }
        } catch (_: InterruptedException) {
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

    fun stopPlayback() {
        synchronized(audioTrackLock) {
            activePlaybackEpoch++
        }
        stopAudioTrackOnly()
        try {
            mediaPipeTts?.cancel()
        } catch (_: Exception) {
        }
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

    private fun closeEngineInternal() {
        stopPlayback()
        try {
            mediaPipeTts?.close()
        } catch (_: Exception) {
        } finally {
            mediaPipeTts = null
            loadedModelPath = ""
            loadedDelegateLabel = ""
            cachedVoices = emptyList()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        null
                    }
                } ?: uri.lastPathSegment?.substringAfterLast('/')
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/')
        }
    }
}
