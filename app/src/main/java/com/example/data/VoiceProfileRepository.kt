package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.example.engine.SpeakerHeuristicEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Character slot in the Multi-Speaker Dialogue system.
 */
enum class SpeakerRole(val code: String, val displayLabel: String, val badgeColorHex: String) {
    CHARACTER_A("char_a", "Character A (Lead / Hero)", "#00E5FF"),
    CHARACTER_B("char_b", "Character B (Companion / NPC)", "#00E676"),
    CHARACTER_C("char_c", "Character C (Boss / Villain)", "#FFB300"),
    NARRATOR("narrator", "Narrator / System AI", "#CE93D8");

    companion object {
        fun fromCode(code: String?): SpeakerRole {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: CHARACTER_A
        }
    }
}

/**
 * Represents a built-in or user-cloned Custom TTS Voice Profile stored locally on device.
 *
 * @property id Unique identifier for the voice profile.
 * @property name User-visible name (e.g., "Aarav (Hero)", "My Cloned Voice").
 * @property description Short description of the acoustic profile or cloned sample.
 * @property pitch TTS pitch multiplier (`0.4f..1.8f`) derived from voice cloning or preset.
 * @property speechRate TTS speech rate multiplier (`0.4f..1.8f`).
 * @property engineVoiceIndex Index offset used to pick distinct underlying `android.speech.tts.Voice`
 *                            models from `TextToSpeech.getVoices()` so different characters sound distinct.
 * @property assignedRole Default multi-speaker slot assigned to this voice.
 * @property isCustomCloned Whether this voice was created by the user via 3-5s recording or audio upload.
 * @property sampleAudioPath Optional local file path in `filesDir/custom_voices/` for the recorded/uploaded reference audio.
 */
data class VoiceProfile(
    val id: String,
    val name: String,
    val description: String,
    val pitch: Float,
    val speechRate: Float,
    val engineVoiceIndex: Int = 0,
    val assignedRole: SpeakerRole = SpeakerRole.CHARACTER_A,
    val isCustomCloned: Boolean = false,
    val sampleAudioPath: String? = null
)

/**
 * Result of parsing an extracted game subtitle for multi-speaker character attribution.
 */
data class ParsedDialogueLine(
    val speakerName: String?,
    val spokenDialogue: String,
    val resolvedRole: SpeakerRole,
    val assignedVoice: VoiceProfile,
    val matchedHeuristicRule: String = ""
)

/**
 * Manages:
 * 1. Persistent storage of Built-in and Custom Cloned [VoiceProfile]s in `SharedPreferences` + `filesDir/custom_voices/`.
 * 2. Real 3–5 second microphone audio recording (`AudioRecord` 16kHz PCM -> `.wav`) and acoustic
 *    pitch ($F_0$ autocorrelation) + cadence analysis to generate a custom cloned TTS voice profile.
 * 3. Audio file import (`content://` URI -> local storage + acoustic analysis).
 * 4. Multi-Speaker Dialogue speaker detection (`Character A`, `Character B`, named game speakers)
 *    and automatic per-character voice assignment.
 */
object VoiceProfileRepository {
    private const val TAG = "VoiceProfileRepo"
    private const val PREFS_NAME = "gamevox_multi_voice_prefs"
    private const val KEY_VOICES_JSON = "saved_voice_profiles_json_v1"
    private const val KEY_SELECTED_VOICE_ID = "selected_primary_voice_id_v1"
    private const val KEY_MULTI_SPEAKER_ENABLED = "multi_speaker_enabled_v1"
    private const val KEY_ROLE_ASSIGNMENTS_JSON = "role_voice_assignments_json_v1"
    private const val KEY_CUSTOM_TAG_BINDINGS_JSON = "custom_tag_voice_bindings_json_v1"

    private val defaultPresets = listOf(
        VoiceProfile(
            id = "preset_char_a_hero",
            name = "Vikram • Character A (Hero)",
            description = "Balanced, natural HD neural voice for lead characters",
            pitch = 0.96f,
            speechRate = 0.96f,
            engineVoiceIndex = 0,
            assignedRole = SpeakerRole.CHARACTER_A,
            isCustomCloned = false
        ),
        VoiceProfile(
            id = "preset_char_b_companion",
            name = "Ananya • Character B (Companion)",
            description = "Bright, expressive higher-pitch voice for companions & allies",
            pitch = 1.24f,
            speechRate = 1.02f,
            engineVoiceIndex = 1,
            assignedRole = SpeakerRole.CHARACTER_B,
            isCustomCloned = false
        ),
        VoiceProfile(
            id = "preset_char_c_boss",
            name = "Kaal • Character C (Boss / Villain)",
            description = "Deep, commanding bass-heavy voice for bosses, dragons & guards",
            pitch = 0.68f,
            speechRate = 0.86f,
            engineVoiceIndex = 2,
            assignedRole = SpeakerRole.CHARACTER_C,
            isCustomCloned = false
        ),
        VoiceProfile(
            id = "preset_narrator_ai",
            name = "ira-AI • System / Narrator",
            description = "Crisp, steady cadence for game terminals, quests & narration",
            pitch = 1.08f,
            speechRate = 1.06f,
            engineVoiceIndex = 3,
            assignedRole = SpeakerRole.NARRATOR,
            isCustomCloned = false
        )
    )

    private val _voices = MutableStateFlow<List<VoiceProfile>>(defaultPresets)
    val voices: StateFlow<List<VoiceProfile>> = _voices.asStateFlow()

    private val _selectedVoiceId = MutableStateFlow(defaultPresets.first().id)
    val selectedVoiceId: StateFlow<String> = _selectedVoiceId.asStateFlow()

    private val _multiSpeakerEnabled = MutableStateFlow(true)
    val multiSpeakerEnabled: StateFlow<Boolean> = _multiSpeakerEnabled.asStateFlow()

    private val _lastDetectedSpeaker = MutableStateFlow("Character A")
    val lastDetectedSpeaker: StateFlow<String> = _lastDetectedSpeaker.asStateFlow()

    private val _lastAssignedVoiceName = MutableStateFlow(defaultPresets.first().name)
    val lastAssignedVoiceName: StateFlow<String> = _lastAssignedVoiceName.asStateFlow()

    private val _lastHeuristicRule = MutableStateFlow("Colon Prefix Tag ('Character A' → Character A)")
    val lastHeuristicRule: StateFlow<String> = _lastHeuristicRule.asStateFlow()

    private val _roleVoiceAssignments = MutableStateFlow<Map<SpeakerRole, String>>(
        defaultPresets.associate { it.assignedRole to it.id }
    )
    val roleVoiceAssignments: StateFlow<Map<SpeakerRole, String>> = _roleVoiceAssignments.asStateFlow()

    private val _customTagVoiceBindings = MutableStateFlow<Map<String, String>>(emptyMap())
    val customTagVoiceBindings: StateFlow<Map<String, String>> = _customTagVoiceBindings.asStateFlow()

    private var activeMediaPlayer: MediaPlayer? = null

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val selectedId = prefs.getString(KEY_SELECTED_VOICE_ID, defaultPresets.first().id)
            ?: defaultPresets.first().id
        val multiSpeaker = prefs.getBoolean(KEY_MULTI_SPEAKER_ENABLED, true)
        _multiSpeakerEnabled.value = multiSpeaker

        val rawJson = prefs.getString(KEY_VOICES_JSON, null)
        if (rawJson.isNullOrBlank()) {
            _voices.value = defaultPresets
            _selectedVoiceId.value = defaultPresets.first().id
            saveVoicesToDisk(context, defaultPresets, _selectedVoiceId.value)
        } else {
            val loaded = parseVoicesJson(rawJson)
            if (loaded.isEmpty()) {
                _voices.value = defaultPresets
                _selectedVoiceId.value = defaultPresets.first().id
            } else {
                _voices.value = loaded
                _selectedVoiceId.value = if (loaded.any { it.id == selectedId }) {
                    selectedId
                } else {
                    loaded.first().id
                }
            }
        }

        // Load per-role voice selections and custom character tag bindings
        val rawRoles = prefs.getString(KEY_ROLE_ASSIGNMENTS_JSON, null)
        if (!rawRoles.isNullOrBlank()) {
            _roleVoiceAssignments.value = parseRoleAssignmentsJson(rawRoles)
        } else {
            _roleVoiceAssignments.value = _voices.value.associate { it.assignedRole to it.id }
        }

        val rawTags = prefs.getString(KEY_CUSTOM_TAG_BINDINGS_JSON, null)
        if (!rawTags.isNullOrBlank()) {
            _customTagVoiceBindings.value = parseCustomTagBindingsJson(rawTags)
        }
    }

    fun selectVoice(context: Context, voice: VoiceProfile) {
        _selectedVoiceId.value = voice.id
        _lastAssignedVoiceName.value = voice.name

        // Map this voice profile to its assigned character slot (e.g., Character A, Character B, etc.)
        val updatedRoles = _roleVoiceAssignments.value.toMutableMap().apply {
            put(voice.assignedRole, voice.id)
        }
        _roleVoiceAssignments.value = updatedRoles

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_VOICE_ID, voice.id)
            .putString(KEY_ROLE_ASSIGNMENTS_JSON, serializeRoleAssignmentsJson(updatedRoles))
            .apply()

        // Also update the active pitch and speechRate in TranslatorStateRepository
        TranslatorStateRepository.updateTtsPitch(context, voice.pitch)
        TranslatorStateRepository.updateTtsSpeechRate(context, voice.speechRate)
    }

    fun assignVoiceToRole(context: Context, role: SpeakerRole, voice: VoiceProfile) {
        val updatedRoles = _roleVoiceAssignments.value.toMutableMap().apply {
            put(role, voice.id)
        }
        _roleVoiceAssignments.value = updatedRoles
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROLE_ASSIGNMENTS_JSON, serializeRoleAssignmentsJson(updatedRoles))
            .apply()
    }

    fun bindCustomCharacterTag(context: Context, characterTag: String, voiceId: String) {
        val normalized = SpeakerHeuristicEngine.normalizeSpeakerKey(characterTag)
        if (normalized.isBlank()) return
        val updated = _customTagVoiceBindings.value.toMutableMap().apply {
            put(normalized, voiceId)
        }
        _customTagVoiceBindings.value = updated
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_TAG_BINDINGS_JSON, serializeCustomTagBindingsJson(updated))
            .apply()
    }

    fun removeCustomCharacterTag(context: Context, characterTag: String) {
        val normalized = SpeakerHeuristicEngine.normalizeSpeakerKey(characterTag)
        val updated = _customTagVoiceBindings.value.toMutableMap().apply {
            remove(normalized)
        }
        _customTagVoiceBindings.value = updated
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_TAG_BINDINGS_JSON, serializeCustomTagBindingsJson(updated))
            .apply()
    }

    fun setMultiSpeakerEnabled(context: Context, enabled: Boolean) {
        _multiSpeakerEnabled.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MULTI_SPEAKER_ENABLED, enabled)
            .apply()
    }

    fun deleteVoice(context: Context, voiceId: String) {
        val currentList = _voices.value
        if (currentList.size <= 1) return // Keep at least 1 voice available

        val target = currentList.find { it.id == voiceId }
        if (target?.sampleAudioPath != null) {
            try {
                File(target.sampleAudioPath).delete()
            } catch (_: Exception) {
            }
        }

        val updated = currentList.filterNot { it.id == voiceId }
        val newSelectedId = if (_selectedVoiceId.value == voiceId) {
            updated.first().id
        } else {
            _selectedVoiceId.value
        }
        _voices.value = updated
        _selectedVoiceId.value = newSelectedId
        saveVoicesToDisk(context, updated, newSelectedId)
    }

    fun restoreDefaultVoices(context: Context) {
        val customOnly = _voices.value.filter { it.isCustomCloned }
        val merged = defaultPresets + customOnly
        _voices.value = merged
        saveVoicesToDisk(context, merged, _selectedVoiceId.value)
    }

    fun addCustomClonedVoice(
        context: Context,
        name: String,
        description: String,
        pitch: Float,
        speechRate: Float,
        assignedRole: SpeakerRole,
        sampleAudioPath: String?
    ): VoiceProfile {
        val cleanName = name.trim().ifEmpty { "Cloned Voice ${_voices.value.size + 1}" }
        val newProfile = VoiceProfile(
            id = "custom_clone_${System.currentTimeMillis()}",
            name = cleanName,
            description = description,
            pitch = pitch.coerceIn(0.45f, 1.75f),
            speechRate = speechRate.coerceIn(0.55f, 1.65f),
            engineVoiceIndex = (_voices.value.size) % 5,
            assignedRole = assignedRole,
            isCustomCloned = true,
            sampleAudioPath = sampleAudioPath
        )
        val updated = listOf(newProfile) + _voices.value
        _voices.value = updated
        _selectedVoiceId.value = newProfile.id
        saveVoicesToDisk(context, updated, newProfile.id)
        selectVoice(context, newProfile)
        return newProfile
    }

    /**
     * Records a 3–5 second 16kHz Mono PCM audio sample from the device microphone,
     * saves it as a `.wav` file in `filesDir/custom_voices/`, and extracts acoustic
     * Fundamental Frequency ($F_0$) and energy cadence to clone the user's vocal profile.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordAndAnalyzeVoiceSample(
        context: Context,
        durationSeconds: Int = 4,
        onProgressUpdate: (Float, Float) -> Unit
    ): ClonedAudioAnalysisResult = withContext(Dispatchers.IO) {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(sampleRate / 5)

        val totalSamplesTarget = sampleRate * durationSeconds.coerceIn(3, 5)
        val collectedShorts = ShortArray(totalSamplesTarget)
        var collectedCount = 0

        var recorder: AudioRecord? = null
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufSize * 2
            )

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder.startRecording()
                val readBuffer = ShortArray(1024)
                val startMs = System.currentTimeMillis()
                val totalDurationMs = durationSeconds * 1000L

                while (isActive && collectedCount < totalSamplesTarget) {
                    val read = recorder.read(readBuffer, 0, readBuffer.size)
                    if (read > 0) {
                        val copyLen = minOf(read, totalSamplesTarget - collectedCount)
                        System.arraycopy(readBuffer, 0, collectedShorts, collectedCount, copyLen)
                        collectedCount += copyLen

                        // Compute instant RMS level for visual meter
                        var sumSq = 0.0
                        for (i in 0 until copyLen) {
                            val norm = readBuffer[i] / 32768.0
                            sumSq += norm * norm
                        }
                        val rms = sqrt(sumSq / copyLen).toFloat().coerceIn(0f, 1f)
                        val progress = (collectedCount.toFloat() / totalSamplesTarget).coerceIn(0f, 1f)
                        withContext(Dispatchers.Main) {
                            onProgressUpdate(progress, rms)
                        }
                    } else {
                        val elapsed = System.currentTimeMillis() - startMs
                        if (elapsed >= totalDurationMs) break
                    }
                }
                recorder.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord capture exception, generating fallback acoustic profile", e)
        } finally {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
        }

        val voiceDir = File(context.filesDir, "custom_voices").apply { mkdirs() }
        val wavFile = File(voiceDir, "voice_clone_${System.currentTimeMillis()}.wav")
        if (collectedCount > 1000) {
            writePcmShortsToWavFile(collectedShorts, collectedCount, sampleRate, wavFile)
        }

        val analysis = analyzePcmSamplesForClone(collectedShorts, collectedCount, sampleRate)
        ClonedAudioAnalysisResult(
            savedAudioPath = if (wavFile.exists()) wavFile.absolutePath else null,
            estimatedFundamentalHz = analysis.first,
            clonedPitch = analysis.second,
            clonedSpeechRate = analysis.third,
            acousticSummary = "Cloned (${analysis.first.toInt()} Hz F0 • Pitch ${String.format(Locale.US, "%.2fx", analysis.second)} • Rate ${String.format(Locale.US, "%.2fx", analysis.third)})"
        )
    }

    /**
     * Imports an external audio file selected by the user (`content://` URI), copies it into
     * `filesDir/custom_voices/`, and derives a custom voice clone profile from its byte stream.
     */
    suspend fun importAndAnalyzeAudioUri(
        context: Context,
        uri: Uri
    ): ClonedAudioAnalysisResult = withContext(Dispatchers.IO) {
        val voiceDir = File(context.filesDir, "custom_voices").apply { mkdirs() }
        val destFile = File(voiceDir, "imported_voice_${System.currentTimeMillis()}.audio")

        var bytesReadTotal = 0L
        var energySum = 0L
        var zeroCrossings = 0L

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(4096)
                    var prevSample = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        bytesReadTotal += read
                        for (i in 0 until read step 2) {
                            val s = buffer[i].toInt()
                            energySum += abs(s)
                            if ((s >= 0 && prevSample < 0) || (s < 0 && prevSample >= 0)) {
                                zeroCrossings++
                            }
                            prevSample = s
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy imported audio URI", e)
        }

        val zcrRatio = if (bytesReadTotal > 0) {
            (zeroCrossings.toFloat() / (bytesReadTotal / 2f)).coerceIn(0.05f, 0.45f)
        } else {
            0.18f
        }
        val estimatedHz = (95f + zcrRatio * 420f).coerceIn(85f, 265f)
        val clonedPitch = (estimatedHz / 165f).coerceIn(0.65f, 1.45f)
        val avgEnergy = if (bytesReadTotal > 0) (energySum.toFloat() / (bytesReadTotal / 2f)) else 40f
        val clonedRate = (0.85f + (avgEnergy / 128f) * 0.28f).coerceIn(0.80f, 1.25f)

        ClonedAudioAnalysisResult(
            savedAudioPath = if (destFile.exists() && destFile.length() > 0) destFile.absolutePath else null,
            estimatedFundamentalHz = estimatedHz,
            clonedPitch = clonedPitch,
            clonedSpeechRate = clonedRate,
            acousticSummary = "Imported Audio Clone (${estimatedHz.toInt()} Hz • Pitch ${String.format(Locale.US, "%.2fx", clonedPitch)})"
        )
    }

    /**
     * Plays back the user's recorded/imported reference audio sample if present.
     */
    fun playRecordedReferenceSample(audioPath: String?, onFinished: () -> Unit = {}) {
        if (audioPath.isNullOrBlank()) {
            onFinished()
            return
        }
        val file = File(audioPath)
        if (!file.exists()) {
            onFinished()
            return
        }
        try {
            activeMediaPlayer?.release()
            activeMediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    activeMediaPlayer = null
                    onFinished()
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    activeMediaPlayer = null
                    onFinished()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            onFinished()
        }
    }

    /**
     * Multi-Speaker Heuristic Dialogue Analyzer:
     * Analyzes scan results via [SpeakerHeuristicEngine] to identify character name tags
     * (e.g., `"Character A:"`, `"[Character B]"`, `"Cybernetic Guard: ..."`, or RPG nameplate headers)
     * and maps them to the corresponding [VoiceProfile] selected in the Voice Manager.
     */
    fun resolveSpeakerAndVoiceForSubtitle(rawSubtitleText: String): ParsedDialogueLine {
        val allVoices = _voices.value.ifEmpty { defaultPresets }
        val primaryVoice = allVoices.find { it.id == _selectedVoiceId.value } ?: allVoices.first()

        val result = SpeakerHeuristicEngine.analyzeScanResult(
            rawScannedText = rawSubtitleText,
            availableVoices = allVoices,
            selectedDefaultVoice = primaryVoice,
            roleVoiceAssignments = _roleVoiceAssignments.value,
            customTagVoiceBindings = _customTagVoiceBindings.value,
            multiSpeakerEnabled = _multiSpeakerEnabled.value
        )

        _lastDetectedSpeaker.value = result.displaySpeakerName
        _lastAssignedVoiceName.value = result.assignedVoice.name
        _lastHeuristicRule.value = result.matchedRuleDescription

        return ParsedDialogueLine(
            speakerName = result.detectedSpeakerTag ?: result.displaySpeakerName,
            spokenDialogue = result.cleanedDialogueText,
            resolvedRole = result.matchedRole,
            assignedVoice = result.assignedVoice,
            matchedHeuristicRule = result.matchedRuleDescription
        )
    }

    fun getVoiceForRole(role: SpeakerRole): VoiceProfile {
        val currentVoices = _voices.value.ifEmpty { defaultPresets }
        val explicitId = _roleVoiceAssignments.value[role]
        if (explicitId != null) {
            val explicitVoice = currentVoices.find { it.id == explicitId }
            if (explicitVoice != null) return explicitVoice
        }
        val fallback = currentVoices.firstOrNull { it.id == _selectedVoiceId.value }
            ?: currentVoices.first()
        return currentVoices.firstOrNull { it.isCustomCloned && it.assignedRole == role }
            ?: currentVoices.firstOrNull { it.assignedRole == role }
            ?: fallback
    }

    private fun analyzePcmSamplesForClone(
        samples: ShortArray,
        sampleCount: Int,
        sampleRate: Int
    ): Triple<Float, Float, Float> {
        if (sampleCount < 2048) {
            return Triple(155f, 0.92f, 0.96f)
        }

        // Estimate Fundamental Frequency (F0) in 75Hz..300Hz band using autocorrelation on center window
        val windowSize = minOf(2048, sampleCount)
        val startOffset = (sampleCount - windowSize) / 2
        val minLag = sampleRate / 300 // 300 Hz max F0
        val maxLag = (sampleRate / 75).coerceAtMost(windowSize / 2) // 75 Hz min F0

        var bestLag = minLag
        var maxCorr = -1.0

        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until (windowSize - lag)) {
                val s1 = samples[startOffset + i] / 32768.0
                val s2 = samples[startOffset + i + lag] / 32768.0
                sum += s1 * s2
            }
            if (sum > maxCorr) {
                maxCorr = sum
                bestLag = lag
            }
        }

        val fundamentalHz = (sampleRate.toFloat() / bestLag.toFloat()).coerceIn(80f, 280f)
        // Map 80Hz..280Hz human vocal fundamental frequency to TTS pitch multiplier 0.62f..1.48f
        val clonedPitch = (fundamentalHz / 160f).coerceIn(0.62f, 1.48f)

        // Compute active speech syllable energy density to estimate natural speaking rate
        var activeFrames = 0
        val frameSize = 320 // 20ms at 16kHz
        val totalFrames = (sampleCount / frameSize).coerceAtLeast(1)
        for (f in 0 until totalFrames) {
            var sumSq = 0.0
            val base = f * frameSize
            for (i in 0 until frameSize) {
                val v = samples[base + i] / 32768.0
                sumSq += v * v
            }
            val rms = sqrt(sumSq / frameSize)
            if (rms > 0.025) activeFrames++
        }
        val activityRatio = activeFrames.toFloat() / totalFrames.toFloat()
        val clonedRate = (0.82f + activityRatio * 0.34f).coerceIn(0.80f, 1.22f)

        return Triple(fundamentalHz, clonedPitch, clonedRate)
    }

    private fun writePcmShortsToWavFile(
        samples: ShortArray,
        sampleCount: Int,
        sampleRate: Int,
        outFile: File
    ) {
        try {
            val byteData = ByteBuffer.allocate(sampleCount * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                byteData.putShort(samples[i])
            }
            val pcmBytes = byteData.array()
            val totalDataLen = pcmBytes.size + 36
            val byteRate = sampleRate * 2

            val header = ByteArrayOutputStream(44)
            header.write("RIFF".toByteArray(Charsets.US_ASCII))
            header.write(intToLittleEndian(totalDataLen))
            header.write("WAVE".toByteArray(Charsets.US_ASCII))
            header.write("fmt ".toByteArray(Charsets.US_ASCII))
            header.write(intToLittleEndian(16)) // PCM chunk size
            header.write(shortToLittleEndian(1)) // AudioFormat = 1 (PCM)
            header.write(shortToLittleEndian(1)) // Channels = 1 (Mono)
            header.write(intToLittleEndian(sampleRate))
            header.write(intToLittleEndian(byteRate))
            header.write(shortToLittleEndian(2)) // BlockAlign
            header.write(shortToLittleEndian(16)) // BitsPerSample
            header.write("data".toByteArray(Charsets.US_ASCII))
            header.write(intToLittleEndian(pcmBytes.size))

            FileOutputStream(outFile).use { fos ->
                fos.write(header.toByteArray())
                fos.write(pcmBytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error writing WAV header", e)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortToLittleEndian(value: Short): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()

    private fun saveVoicesToDisk(
        context: Context,
        voices: List<VoiceProfile>,
        selectedId: String
    ) {
        val arr = JSONArray()
        voices.forEach { v ->
            val obj = JSONObject().apply {
                put("id", v.id)
                put("name", v.name)
                put("description", v.description)
                put("pitch", v.pitch.toDouble())
                put("speechRate", v.speechRate.toDouble())
                put("engineVoiceIndex", v.engineVoiceIndex)
                put("assignedRole", v.assignedRole.code)
                put("isCustomCloned", v.isCustomCloned)
                if (v.sampleAudioPath != null) {
                    put("sampleAudioPath", v.sampleAudioPath)
                }
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VOICES_JSON, arr.toString())
            .putString(KEY_SELECTED_VOICE_ID, selectedId)
            .apply()
    }

    private fun parseVoicesJson(rawJson: String): List<VoiceProfile> {
        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<VoiceProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    VoiceProfile(
                        id = obj.optString("id", "voice_$i"),
                        name = obj.optString("name", "Voice ${i + 1}"),
                        description = obj.optString("description", "Custom TTS Voice Profile"),
                        pitch = obj.optDouble("pitch", 0.95).toFloat().coerceIn(0.4f, 1.8f),
                        speechRate = obj.optDouble("speechRate", 0.95).toFloat().coerceIn(0.4f, 1.8f),
                        engineVoiceIndex = obj.optInt("engineVoiceIndex", i % 4),
                        assignedRole = SpeakerRole.fromCode(obj.optString("assignedRole", "char_a")),
                        isCustomCloned = obj.optBoolean("isCustomCloned", false),
                        sampleAudioPath = obj.optString("sampleAudioPath", "").takeIf { it.isNotBlank() }
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeRoleAssignmentsJson(map: Map<SpeakerRole, String>): String {
        val obj = JSONObject()
        map.forEach { (role, voiceId) ->
            obj.put(role.code, voiceId)
        }
        return obj.toString()
    }

    private fun parseRoleAssignmentsJson(rawJson: String): Map<SpeakerRole, String> {
        return try {
            val obj = JSONObject(rawJson)
            val result = mutableMapOf<SpeakerRole, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val role = SpeakerRole.fromCode(key)
                val voiceId = obj.optString(key, "")
                if (voiceId.isNotBlank()) {
                    result[role] = voiceId
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serializeCustomTagBindingsJson(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (tagKey, voiceId) ->
            obj.put(tagKey, voiceId)
        }
        return obj.toString()
    }

    private fun parseCustomTagBindingsJson(rawJson: String): Map<String, String> {
        return try {
            val obj = JSONObject(rawJson)
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val voiceId = obj.optString(key, "")
                if (key.isNotBlank() && voiceId.isNotBlank()) {
                    result[key] = voiceId
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

data class ClonedAudioAnalysisResult(
    val savedAudioPath: String?,
    val estimatedFundamentalHz: Float,
    val clonedPitch: Float,
    val clonedSpeechRate: Float,
    val acousticSummary: String
)
