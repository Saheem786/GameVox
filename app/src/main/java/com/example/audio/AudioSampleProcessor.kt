package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Detailed quality and compatibility telemetry produced after decoding, trimming silence,
 * downmixing to mono, and resampling a reference voice sample to 24 kHz PCM16 WAV.
 */
data class ProcessedVoiceSampleReport(
    val outputWavPath: String,
    val sourceFormat: String,
    val sourceSampleRateHz: Int,
    val sourceChannels: Int,
    val processedSampleRateHz: Int,
    val durationSeconds: Float,
    val originalDurationSeconds: Float,
    val trimmedSilenceMs: Long,
    val rmsEnergy: Float,
    val noiseFloorRms: Float,
    val estimatedSnrDb: Float,
    val clippingRatio: Float,
    val estimatedF0Hz: Float,
    val multiSpeakerSuspected: Boolean,
    val sha256: String,
    val qualityVerdict: String,
    val warnings: List<String>,
    val recommendedForCloning: Boolean
)

/**
 * Pure Kotlin + Android MediaCodec reference audio preprocessor for Chatterbox voice cloning.
 *
 * Pipeline:
 * 1. Decode input (`WAV` via RIFF parser; `MP3` / `M4A` / `AAC` / `OGG` / `FLAC` via `MediaExtractor` + `MediaCodec`).
 * 2. Downmix to single-channel mono PCM16.
 * 3. Trim leading/trailing silence & compact >700 ms dead pauses while preserving 80 ms speech fade boundaries.
 * 4. Resample to 24,000 Hz (`TARGET_SAMPLE_RATE_HZ`, Chatterbox Multilingual V3 native rate).
 * 5. Enforce 120 s maximum prompt cap, soft-normalize ultra-quiet speech without clipping, and measure SNR / clipping / F0 stability.
 */
object AudioSampleProcessor {
    private const val TAG = "AudioSampleProcessor"
    const val TARGET_SAMPLE_RATE_HZ = 24000
    const val MAX_DURATION_SECONDS = 120f
    const val MIN_EXPERIMENT_DURATION_SECONDS = 5.0f
    const val RECOMMENDED_MIN_DURATION_SECONDS = 30.0f

    /**
     * Decodes a user-selected `content://` URI (WAV, MP3, M4A/AAC, OGG, FLAC),
     * preprocesses it to 24 kHz mono PCM16 WAV in `filesDir/custom_voices/`, and returns [ProcessedVoiceSampleReport].
     */
    suspend fun decodeAndNormalizeUri(
        context: Context,
        uri: Uri,
        destWavFile: File
    ): Result<ProcessedVoiceSampleReport> = withContext(Dispatchers.IO) {
        val rawTemp = File(context.cacheDir, "raw_import_${System.currentTimeMillis()}.tmp")
        try {
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(rawTemp).use { out ->
                    input.copyTo(out)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Could not open selected audio stream."))

            if (!rawTemp.exists() || rawTemp.length() < 64L) {
                return@withContext Result.failure(IllegalStateException("Selected audio file is empty."))
            }

            decodeAndNormalizeFile(
                sourceFile = rawTemp,
                destWavFile = destWavFile,
                sourceFormatHint = mimeType.ifBlank { uri.lastPathSegment.orEmpty() }
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                rawTemp.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Decodes an existing local audio file (WAV or encoded audio) and outputs a normalized 24 kHz mono PCM16 WAV.
     */
    fun decodeAndNormalizeFile(
        sourceFile: File,
        destWavFile: File,
        sourceFormatHint: String = sourceFile.extension
    ): Result<ProcessedVoiceSampleReport> {
        return try {
            val bytes = sourceFile.readBytes()
            val isRiffWav = bytes.size >= 44 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"

            val decoded = if (isRiffWav) {
                parseRiffWavToMonoShorts(bytes)
                    ?: return Result.failure(IllegalArgumentException("Malformed or unsupported RIFF WAV file."))
            } else {
                decodeWithMediaCodec(sourceFile)
                    ?: return Result.failure(
                        IllegalArgumentException(
                            "Could not decode audio format ($sourceFormatHint). Please pick a valid WAV, MP3, M4A, or OGG recording."
                        )
                    )
            }

            val report = processPcmToNormalizedWav(
                monoSamples = decoded.monoSamples,
                sourceSampleRateHz = decoded.sampleRateHz,
                sourceChannels = decoded.originalChannels,
                sourceFormat = if (isRiffWav) "WAV" else sourceFormatHint.uppercase(Locale.ROOT).ifBlank { "ENCODED" },
                destWavFile = destWavFile
            )
            Result.success(report)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Core pure-JVM signal processing stage: trims silence, resamples to 24 kHz, soft-normalizes,
     * writes PCM16 WAV to [destWavFile], and computes SNR, clipping, and F0 speaker continuity.
     */
    fun processPcmToNormalizedWav(
        monoSamples: ShortArray,
        sourceSampleRateHz: Int,
        sourceChannels: Int,
        sourceFormat: String,
        destWavFile: File,
        targetSampleRateHz: Int = TARGET_SAMPLE_RATE_HZ
    ): ProcessedVoiceSampleReport {
        val srIn = sourceSampleRateHz.coerceAtLeast(8000)
        val originalDurSec = monoSamples.size.toFloat() / srIn.toFloat()

        // 1. Frame energy analysis (20ms frames) for noise floor + silence trimming
        val frameSize = (srIn / 50).coerceAtLeast(1)
        val frameCount = (monoSamples.size / frameSize).coerceAtLeast(1)
        val frameRmsList = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            val start = f * frameSize
            val end = min(monoSamples.size, start + frameSize)
            var sumSq = 0.0
            for (i in start until end) {
                val n = monoSamples[i] / 32768.0
                sumSq += n * n
            }
            frameRmsList[f] = sqrt(sumSq / max(1, end - start)).toFloat()
        }

        val sortedRms = frameRmsList.sorted()
        val noiseFloor = if (sortedRms.isNotEmpty()) sortedRms[(sortedRms.size * 0.12f).toInt().coerceIn(0, sortedRms.lastIndex)] else 0f
        val speechThreshold = max(0.008f, noiseFloor * 2.4f)

        val firstSpeechFrame = frameRmsList.indexOfFirst { it >= speechThreshold }.let { if (it < 0) 0 else it }
        val lastSpeechFrame = frameRmsList.indexOfLast { it >= speechThreshold }.let { if (it < 0) frameRmsList.lastIndex else it }

        val marginFrames = 4 // 80ms natural margin
        val startFrame = max(0, firstSpeechFrame - marginFrames)
        val endFrame = min(frameRmsList.lastIndex, lastSpeechFrame + marginFrames)

        // Compact internal dead gaps > 700ms down to 340ms (17 frames)
        val compactedList = ArrayList<Short>(monoSamples.size)
        var silentRunFrames = 0
        val gapTriggerFrames = 35 // 700ms max internal pause
        for (f in startFrame..endFrame) {
            val isSilent = frameRmsList[f] < speechThreshold
            if (isSilent) {
                silentRunFrames++
                if (silentRunFrames > gapTriggerFrames) {
                    continue // Cap overly long internal pauses cleanly at 700ms
                }
            } else {
                silentRunFrames = 0
            }
            val sIdx = f * frameSize
            val eIdx = min(monoSamples.size, sIdx + frameSize)
            for (i in sIdx until eIdx) {
                compactedList.add(monoSamples[i])
            }
        }

        val trimmedSamples = if (compactedList.size > 160) compactedList.toShortArray() else monoSamples
        val removedSamplesCount = (monoSamples.size - trimmedSamples.size).coerceAtLeast(0)
        val trimmedSilenceMs = ((removedSamplesCount * 1000L) / srIn.toLong()).coerceAtLeast(0L)

        // 2. Resample to 24 kHz mono
        val resampled = resampleLinear(trimmedSamples, srIn, targetSampleRateHz)

        // 3. Enforce max 120s cap
        val maxSamples = (targetSampleRateHz * MAX_DURATION_SECONDS).toInt()
        val capped = if (resampled.size > maxSamples) resampled.copyOf(maxSamples) else resampled

        // 4. Soft normalization if speech is quiet (target peak ~0.82 without clipping loud tracks)
        var maxAbs = 0
        for (s in capped) {
            val a = abs(s.toInt())
            if (a > maxAbs) maxAbs = a
        }
        val normalized = if (maxAbs in 600..18000) {
            val gain = (26800.0 / maxAbs.toDouble()).coerceAtMost(4.5)
            ShortArray(capped.size) { idx ->
                (capped[idx] * gain).roundToInt().coerceIn(-32767, 32767).toShort()
            }
        } else {
            capped
        }

        // 5. Write 24kHz Mono PCM16 RIFF WAV
        destWavFile.parentFile?.mkdirs()
        writePcm16MonoWav(normalized, targetSampleRateHz, destWavFile)

        // 6. Compute final quality metrics on normalized WAV
        val metrics = analyzePcmQuality(normalized, targetSampleRateHz, noiseFloor)
        val sha256 = computeSha256(destWavFile.readBytes())
        val durSec = normalized.size.toFloat() / targetSampleRateHz.toFloat()

        val warnings = mutableListOf<String>()
        if (durSec < MIN_EXPERIMENT_DURATION_SECONDS) {
            warnings += "Too short (${String.format(Locale.US, "%.1fs", durSec)}): record at least 5–10s for experimentation (30–120s clean speech recommended)."
        } else if (durSec < 15.0f) {
            warnings += "Experimentation duration (${String.format(Locale.US, "%.1fs", durSec)}): usable for quick zero-shot tests, but 30–120s gives much more consistent vocal identity."
        } else if (durSec < RECOMMENDED_MIN_DURATION_SECONDS) {
            warnings += "Good duration (${String.format(Locale.US, "%.1fs", durSec)}): 30–120s recommended for best accent & timbre stability."
        }
        if (metrics.clippingRatio > 0.01f) {
            warnings += "Audio clipping detected (${String.format(Locale.US, "%.1f%%", metrics.clippingRatio * 100f)}): lower mic gain or avoid shouting close to mic."
        }
        if (metrics.rmsEnergy < 0.012f) {
            warnings += "Low speech energy (RMS ${String.format(Locale.US, "%.3f", metrics.rmsEnergy)}): speak closer or import a clearer clip."
        }
        if (metrics.estimatedSnrDb < 12f && metrics.rmsEnergy >= 0.01f) {
            warnings += "High background noise detected (~${metrics.estimatedSnrDb.toInt()} dB SNR): quiet room recording works much better."
        }
        if (metrics.multiSpeakerSuspected) {
            warnings += "Possible multiple speakers or abrupt pitch shifts detected — use a single clean speaker clip."
        }

        val verdict = when {
            durSec < MIN_EXPERIMENT_DURATION_SECONDS -> "Too Short (< 5s)"
            metrics.rmsEnergy < 0.008f -> "Too Quiet / Silent"
            metrics.clippingRatio > 0.04f -> "Severe Clipping"
            metrics.multiSpeakerSuspected -> "Multi-Speaker / Unstable Warning"
            metrics.estimatedSnrDb < 10f -> "Noisy Background Warning"
            durSec >= RECOMMENDED_MIN_DURATION_SECONDS -> "Optimal Clean Reference (${String.format(Locale.US, "%.1fs", durSec)})"
            durSec >= 15.0f -> "Strong Reference (${String.format(Locale.US, "%.1fs", durSec)})"
            else -> "Experimentation Ready (${String.format(Locale.US, "%.1fs", durSec)})"
        }

        val recommended = durSec >= MIN_EXPERIMENT_DURATION_SECONDS &&
            metrics.rmsEnergy >= 0.008f &&
            metrics.clippingRatio <= 0.05f

        return ProcessedVoiceSampleReport(
            outputWavPath = destWavFile.absolutePath,
            sourceFormat = sourceFormat,
            sourceSampleRateHz = srIn,
            sourceChannels = sourceChannels,
            processedSampleRateHz = targetSampleRateHz,
            durationSeconds = durSec,
            originalDurationSeconds = originalDurSec,
            trimmedSilenceMs = trimmedSilenceMs,
            rmsEnergy = metrics.rmsEnergy,
            noiseFloorRms = noiseFloor,
            estimatedSnrDb = metrics.estimatedSnrDb,
            clippingRatio = metrics.clippingRatio,
            estimatedF0Hz = metrics.estimatedF0Hz,
            multiSpeakerSuspected = metrics.multiSpeakerSuspected,
            sha256 = sha256,
            qualityVerdict = verdict,
            warnings = warnings,
            recommendedForCloning = recommended
        )
    }

    private data class PcmQualityMetrics(
        val rmsEnergy: Float,
        val clippingRatio: Float,
        val estimatedSnrDb: Float,
        val estimatedF0Hz: Float,
        val multiSpeakerSuspected: Boolean
    )

    private fun analyzePcmQuality(samples: ShortArray, sampleRate: Int, priorNoiseFloor: Float): PcmQualityMetrics {
        if (samples.isEmpty()) {
            return PcmQualityMetrics(0f, 0f, 0f, 150f, false)
        }
        var sumSq = 0.0
        var clipped = 0L
        for (s in samples) {
            val n = s / 32768.0
            sumSq += n * n
            if (abs(s.toInt()) >= 32400) clipped++
        }
        val totalRms = sqrt(sumSq / samples.size).toFloat()
        val clippingRatio = clipped.toFloat() / samples.size.toFloat()

        // Frame RMS & F0 tracking across voiced 80ms windows spanning the full clip
        val winSize = (sampleRate / 12).coerceAtLeast(512)
        val totalWindows = ((samples.size - winSize) / winSize).coerceAtLeast(1)
        val f0WindowStride = (totalWindows / 120).coerceAtLeast(1)
        val frameRms = mutableListOf<Float>()
        val voicedF0 = mutableListOf<Float>()

        var pos = 0
        var winIdx = 0
        while (pos + winSize <= samples.size) {
            var wSq = 0.0
            for (i in 0 until winSize) {
                val n = samples[pos + i] / 32768.0
                wSq += n * n
            }
            val rms = sqrt(wSq / winSize).toFloat()
            frameRms.add(rms)
            if (rms > 0.025f && (winIdx % f0WindowStride == 0)) {
                val f0 = estimateWindowF0(samples, pos, min(winSize, 2048), sampleRate)
                if (f0 in 75f..330f) {
                    voicedF0.add(f0)
                }
            }
            winIdx++
            pos += winSize
        }

        val sortedF = frameRms.sorted()
        val speechRms = if (sortedF.isNotEmpty()) sortedF[(sortedF.size * 0.85f).toInt().coerceIn(0, sortedF.lastIndex)] else totalRms
        val quietestFrame = sortedF.firstOrNull() ?: totalRms
        val effectiveNoise = when {
            priorNoiseFloor <= 0.001f || quietestFrame <= 0.001f -> 0.0005f
            priorNoiseFloor < speechRms * 0.5f -> priorNoiseFloor
            else -> max(0.0005f, min(quietestFrame, speechRms * 0.03f))
        }
        val snrDb = (20.0 * log10((speechRms / effectiveNoise).toDouble().coerceAtLeast(1.0))).toFloat().coerceIn(0f, 55f)

        val avgF0 = if (voicedF0.isNotEmpty()) voicedF0.average().toFloat() else 155f
        var multiSpeaker = false
        if (voicedF0.size >= 6) {
            val sortedP = voicedF0.sorted()
            val q25 = sortedP[sortedP.size / 4]
            val q75 = sortedP[(sortedP.size * 3) / 4]
            val mean = voicedF0.average()
            val std = sqrt(voicedF0.map { (it - mean) * (it - mean) }.average())
            // Flag genuine bimodal pitch spread across distant speaker ranges (e.g., male ~115Hz vs female ~225Hz)
            if ((q75 - q25) > 78f && (std / mean) > 0.28) {
                multiSpeaker = true
            }
        }

        return PcmQualityMetrics(
            rmsEnergy = totalRms,
            clippingRatio = clippingRatio,
            estimatedSnrDb = snrDb,
            estimatedF0Hz = avgF0,
            multiSpeakerSuspected = multiSpeaker
        )
    }

    private fun estimateWindowF0(samples: ShortArray, offset: Int, windowLen: Int, sampleRate: Int): Float {
        val minLag = (sampleRate / 340).coerceAtLeast(10)
        val maxLag = (sampleRate / 75).coerceAtMost(windowLen / 2)
        if (maxLag <= minLag) return 0f
        var zeroEnergy = 0.0
        for (i in 0 until (windowLen - maxLag)) {
            val s = samples[offset + i] / 32768.0
            zeroEnergy += s * s
        }
        if (zeroEnergy < 1e-5) return 0f

        var bestLag = minLag
        var maxCorr = -1.0
        for (lag in minLag..maxLag) {
            var corr = 0.0
            for (i in 0 until (windowLen - maxLag)) {
                val s1 = samples[offset + i] / 32768.0
                val s2 = samples[offset + i + lag] / 32768.0
                corr += s1 * s2
            }
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }
        return if ((maxCorr / zeroEnergy) > 0.32) {
            sampleRate.toFloat() / bestLag.toFloat()
        } else {
            0f
        }
    }

    fun resampleLinear(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate || input.size <= 1) return input
        val outLen = ((input.size.toDouble() * dstRate.toDouble()) / srcRate.toDouble()).roundToInt().coerceAtLeast(1)
        val output = ShortArray(outLen)
        val step = (input.size - 1).toDouble() / max(1, outLen - 1).toDouble()
        for (i in 0 until outLen) {
            val pos = i * step
            val idx0 = pos.toInt().coerceIn(0, input.lastIndex)
            val idx1 = (idx0 + 1).coerceAtMost(input.lastIndex)
            val frac = pos - idx0
            val v = input[idx0] + (input[idx1] - input[idx0]) * frac
            output[i] = v.roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }

    data class DecodedRawPcm(
        val monoSamples: ShortArray,
        val sampleRateHz: Int,
        val originalChannels: Int
    )

    fun parseRiffWavToMonoShorts(wavBytes: ByteArray): DecodedRawPcm? {
        if (wavBytes.size < 44) return null
        if (String(wavBytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(wavBytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) {
            return null
        }
        val bb = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var channels = 1
        var sampleRate = 24000
        var bitsPerSample = 16
        var audioFormat = 1
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= wavBytes.size) {
            val chunkId = String(wavBytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = bb.getInt(offset + 4)
            val bodyStart = offset + 8
            if (chunkSize < 0 || bodyStart > wavBytes.size) break
            if (chunkId == "fmt " && chunkSize >= 16 && bodyStart + 16 <= wavBytes.size) {
                audioFormat = bb.getShort(bodyStart).toInt() and 0xFFFF
                channels = bb.getShort(bodyStart + 2).toInt().coerceAtLeast(1)
                sampleRate = bb.getInt(bodyStart + 4).coerceAtLeast(8000)
                bitsPerSample = bb.getShort(bodyStart + 14).toInt().coerceAtLeast(8)
            } else if (chunkId == "data") {
                dataOffset = bodyStart
                dataSize = chunkSize.coerceAtMost(wavBytes.size - bodyStart)
                break
            }
            val padded = if (chunkSize % 2 == 1) chunkSize + 1 else chunkSize
            offset = bodyStart + padded
        }
        if (dataOffset < 0 || dataSize <= 0) return null

        val bytesPerSample = (bitsPerSample / 8).coerceAtLeast(1)
        val frameStride = bytesPerSample * channels
        val totalFrames = dataSize / frameStride
        if (totalFrames <= 0) return null

        val mono = ShortArray(totalFrames)
        for (f in 0 until totalFrames) {
            val framePos = dataOffset + f * frameStride
            var acc = 0.0
            for (ch in 0 until channels) {
                val samplePos = framePos + ch * bytesPerSample
                val sampleVal: Double = when {
                    audioFormat == 3 && bitsPerSample == 32 && samplePos + 4 <= wavBytes.size ->
                        (bb.getFloat(samplePos).coerceIn(-1f, 1f) * 32767f).toDouble()
                    bitsPerSample == 16 && samplePos + 2 <= wavBytes.size ->
                        bb.getShort(samplePos).toDouble()
                    bitsPerSample == 8 ->
                        (((wavBytes[samplePos].toInt() and 0xFF) - 128) shl 8).toDouble()
                    bitsPerSample == 24 && samplePos + 3 <= wavBytes.size -> {
                        val b0 = wavBytes[samplePos].toInt() and 0xFF
                        val b1 = wavBytes[samplePos + 1].toInt() and 0xFF
                        val b2 = wavBytes[samplePos + 2].toInt()
                        ((b2 shl 16) or (b1 shl 8) or b0) / 256.0
                    }
                    else -> 0.0
                }
                acc += sampleVal
            }
            mono[f] = (acc / channels.toDouble()).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return DecodedRawPcm(mono, sampleRate, channels)
    }

    private fun decodeWithMediaCodec(file: File): DecodedRawPcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || trackFormat == null) return null

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(trackFormat, null, null, 0)
                start()
            }

            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxBytes = TARGET_SAMPLE_RATE_HZ * 2 * channels * 125

            while (!outputDone && pcmOut.size() < maxBytes) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val read = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (read < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5000L)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.get(chunk)
                            pcmOut.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = codec.outputFormat
                        if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            }

            val rawPcm = pcmOut.toByteArray()
            if (rawPcm.size < 64) return null
            val totalSamples = rawPcm.size / 2
            val bb = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN)
            val totalFrames = totalSamples / max(1, channels)
            val mono = ShortArray(totalFrames)
            for (f in 0 until totalFrames) {
                var sum = 0
                for (ch in 0 until max(1, channels)) {
                    sum += bb.getShort((f * channels + ch) * 2).toInt()
                }
                mono[f] = (sum / max(1, channels)).coerceIn(-32768, 32767).toShort()
            }
            DecodedRawPcm(mono, sampleRate.coerceAtLeast(8000), channels.coerceAtLeast(1))
        } catch (e: Throwable) {
            Log.w(TAG, "MediaCodec audio decode failed: ${e.message}")
            null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    fun writePcm16MonoWav(samples: ShortArray, sampleRateHz: Int, outFile: File) {
        val dataBytes = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            bb.putShort(s)
        }
        val totalLen = dataBytes.size + 36
        val byteRate = sampleRateHz * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(sampleRateHz)
            putInt(byteRate)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.size)
        }.array()
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { fos ->
            fos.write(header)
            fos.write(dataBytes)
        }
    }

    fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Interactive start/stop microphone recorder supporting 5–120 second recordings for voice cloning.
 * Captures 24 kHz (or fallback 16 kHz) PCM16 mono and immediately runs [AudioSampleProcessor]
 * on stop to trim silence, normalize to 24 kHz WAV, and produce quality metrics.
 */
class ContinuousMicRecorder {
    private val shouldContinue = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    suspend fun recordUntilStopped(
        outputWavFile: File,
        maxDurationSeconds: Int = 120,
        onTelemetry: (elapsedSeconds: Float, rmsLevel: Float) -> Unit
    ): Result<ProcessedVoiceSampleReport> = withContext(Dispatchers.IO) {
        shouldContinue.set(true)
        val preferredRate = AudioSampleProcessor.TARGET_SAMPLE_RATE_HZ
        val fallbackRate = 16000

        var activeRate = preferredRate
        var minBuf = AudioRecord.getMinBufferSize(
            activeRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            activeRate = fallbackRate
            minBuf = AudioRecord.getMinBufferSize(
                activeRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)
        }

        val maxCapSamples = activeRate * maxDurationSeconds.coerceIn(5, 120)
        val collected = ArrayList<Short>(activeRate * 15)
        var recorder: AudioRecord? = null
        var lastUiTickMs = 0L

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                activeRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    activeRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Result.failure(IllegalStateException("Could not initialize device microphone AudioRecord."))
            }

            recorder.startRecording()
            val readBuf = ShortArray(1024)
            while (isActive && shouldContinue.get() && collected.size < maxCapSamples) {
                val read = recorder.read(readBuf, 0, readBuf.size)
                if (read > 0) {
                    val toTake = min(read, maxCapSamples - collected.size)
                    var sumSq = 0.0
                    for (i in 0 until toTake) {
                        val s = readBuf[i]
                        collected.add(s)
                        val n = s / 32768.0
                        sumSq += n * n
                    }
                    val rms = sqrt(sumSq / max(1, toTake)).toFloat().coerceIn(0f, 1f)
                    val now = System.currentTimeMillis()
                    if (now - lastUiTickMs >= 80L) {
                        lastUiTickMs = now
                        val elapsed = collected.size.toFloat() / activeRate.toFloat()
                        withContext(Dispatchers.Main) {
                            onTelemetry(elapsed, rms)
                        }
                    }
                }
            }
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        } finally {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
        }

        if (collected.size < activeRate / 2) {
            return@withContext Result.failure(IllegalStateException("Recording too short (< 0.5s). Speak longer and tap Stop when finished."))
        }

        val rawSamples = ShortArray(collected.size) { collected[it] }
        val report = AudioSampleProcessor.processPcmToNormalizedWav(
            monoSamples = rawSamples,
            sourceSampleRateHz = activeRate,
            sourceChannels = 1,
            sourceFormat = "MIC_PCM",
            destWavFile = outputWavFile
        )
        Result.success(report)
    }

    fun requestStop() {
        shouldContinue.set(false)
    }
}
