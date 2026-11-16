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
 * Gender & 18-year-old vocal profile classification used to automatically switch between
 * Female TTS Voices (e.g. 'Jenny' -> 18yo Girl) and Male TTS Voices (e.g. 'Anon' -> 18yo Boy).
 */
enum class SpeakerGender(
    val code: String,
    val displayLabel: String,
    val defaultPitch18Yo: Float,
    val defaultSpeechRate18Yo: Float
) {
    FEMALE_18(
        code = "female_18",
        displayLabel = "18yo Girl (Female TTS Voice)",
        defaultPitch18Yo = 1.18f,
        defaultSpeechRate18Yo = 1.0f
    ),
    MALE_18(
        code = "male_18",
        displayLabel = "18yo Boy (Male TTS Voice)",
        defaultPitch18Yo = 0.90f,
        defaultSpeechRate18Yo = 1.0f
    );

    companion object {
        fun fromCode(code: String?): SpeakerGender {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: MALE_18
        }
    }
}

/**
 * Character slot in the Multi-Speaker Dialogue system.
 */
enum class SpeakerRole(val code: String, val displayLabel: String, val badgeColorHex: String) {
    CHARACTER_A("char_a", "Anon / Male Lead (18yo Boy)", "#00E5FF"),
    CHARACTER_B("char_b", "Jenny / Female Lead (18yo Girl)", "#00E676"),
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
 */
data class VoiceProfile(
    val id: String,
    val name: String,
    val description: String,
    val pitch: Float,
    val speechRate: Float,
    val engineVoiceIndex: Int = 0,
    val assignedRole: SpeakerRole = SpeakerRole.CHARACTER_A,
    val gender: SpeakerGender = if (assignedRole == SpeakerRole.CHARACTER_B || assignedRole == SpeakerRole.NARRATOR || pitch >= 1.12f) {
        SpeakerGender.FEMALE_18
    } else {
        SpeakerGender.MALE_18
    },
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
    val detectedGender: SpeakerGender,
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
    private const val KEY_CHARACTER_ASSIGNMENTS_JSON = "character_voice_assignments_json_v2"

    private val defaultPresets = listOf(
        VoiceProfile(
            id = "preset_char_a_hero",
            name = "Anon • 18yo Boy (Male Voice)",
            description = "Natural 18-year-old young male TTS voice for Anon & male characters",
            pitch = SpeakerGender.MALE_18.defaultPitch18Yo,
            speechRate = SpeakerGender.MALE_18.defaultSpeechRate18Yo,
            engineVoiceIndex = 0,
            assignedRole = SpeakerRole.CHARACTER_A,
            gender = SpeakerGender.MALE_18,
            isCustomCloned = false
        ),
        VoiceProfile(
            id = "preset_char_b_companion",
            name = "Jenny • 18yo Girl (Female Voice)",
            description = "Bright, expressive 18-year-old young female TTS voice for Jenny & female characters",
            pitch = SpeakerGender.FEMALE_18.defaultPitch18Yo,
            speechRate = SpeakerGender.FEMALE_18.defaultSpeechRate18Yo,
            engineVoiceIndex = 1,
            assignedRole = SpeakerRole.CHARACTER_B,
            gender = SpeakerGender.FEMALE_18,
            isCustomCloned = false
        ),
        VoiceProfile(
            id = "preset_char_c_boss",
            name = "Kaal • Male Boss Voice",
            description = "Deep male voice for bosses, guards & villains",
            pitch = 0.78f,
            speechRate = 0.98f,
            engineVoiceIndex = 6,
            assignedRole = SpeakerRole.CHARACTER_C,
            gender = SpeakerGender.MALE_18,
            isCustomCloned = false
        ),
        VoiceProfile(
            id = "preset_narrator_ai",
            name = "Ira-AI • Female Narrator",
            description = "Crisp young female voice for game terminals, quests & narration",
            pitch = 1.15f,
            speechRate = 1.0f,
            engineVoiceIndex = 3,
            assignedRole = SpeakerRole.NARRATOR,
            gender = SpeakerGender.FEMALE_18,
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

    private val _lastAssignedSpeakerId = MutableStateFlow(0)
    val lastAssignedSpeakerId: StateFlow<Int> = _lastAssignedSpeakerId.asStateFlow()

    private val _lastHeuristicRule = MutableStateFlow("Colon Prefix Tag ('Character A' → Character A)")
    val lastHeuristicRule: StateFlow<String> = _lastHeuristicRule.asStateFlow()

    private val _lastParsedDialogue = MutableStateFlow<ParsedDialogueLine?>(null)
    val lastParsedDialogue: StateFlow<ParsedDialogueLine?> = _lastParsedDialogue.asStateFlow()

    private val _roleVoiceAssignments = MutableStateFlow<Map<SpeakerRole, String>>(
        defaultPresets.associate { it.assignedRole to it.id }
    )
    val roleVoiceAssignments: StateFlow<Map<SpeakerRole, String>> = _roleVoiceAssignments.asStateFlow()

    private val _customTagVoiceBindings = MutableStateFlow<Map<String, String>>(emptyMap())
    val customTagVoiceBindings: StateFlow<Map<String, String>> = _customTagVoiceBindings.asStateFlow()

    // 20+ Persistent Character Voice Assignments
    private val _characterAssignments = MutableStateFlow<List<CharacterVoiceAssignment>>(
        CharacterVoiceAssignment.DEFAULT_PRESETS
    )
    val characterAssignments: StateFlow<List<CharacterVoiceAssignment>> = _characterAssignments.asStateFlow()

    private val _activeCharacterAssignment = MutableStateFlow<CharacterVoiceAssignment?>(
        CharacterVoiceAssignment.DEFAULT_PRESETS.first()
    )
    val activeCharacterAssignment: StateFlow<CharacterVoiceAssignment?> = _activeCharacterAssignment.asStateFlow()

    private var activeMediaPlayer: MediaPlayer? = null
    private var appContext: Context? = null

    @Volatile
    private var availableSpeakerCount: Int = 10

    @Volatile
    private var activeGameNamespace: String = "default_game"

    fun updateAvailableSpeakerCount(count: Int) {
        if (count > 0) {
            availableSpeakerCount = count
            Log.i(TAG, "Updated available Supertonic speaker count to $count")
        }
    }

    fun getAvailableSpeakerCount(): Int = availableSpeakerCount

    fun setGameNamespace(context: Context, namespace: String) {
        val clean = namespace.trim().lowercase(Locale.ROOT).ifBlank { "default_game" }
        if (activeGameNamespace != clean) {
            activeGameNamespace = clean
            appContext = context.applicationContext
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val gameKey = "${KEY_CHARACTER_ASSIGNMENTS_JSON}_$clean"
            val rawAssignments = prefs.getString(gameKey, null)
                ?: prefs.getString(KEY_CHARACTER_ASSIGNMENTS_JSON, null)
            if (!rawAssignments.isNullOrBlank()) {
                val loadedAssignments = CharacterVoiceAssignment.parseList(rawAssignments)
                if (loadedAssignments.isNotEmpty()) {
                    val merged = mutableMapOf<String, CharacterVoiceAssignment>()
                    for (preset in CharacterVoiceAssignment.DEFAULT_PRESETS) {
                        merged[preset.characterName] = preset
                    }
                    for (loaded in loadedAssignments) {
                        merged[loaded.characterName] = loaded
                    }
                    _characterAssignments.value = merged.values.toList()
                }
            }
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
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
                val updatedWithLatestPresets = loaded.map { existing ->
                    val matchingPreset = defaultPresets.find { it.id == existing.id && !existing.isCustomCloned }
                    matchingPreset ?: existing
                }
                _voices.value = updatedWithLatestPresets
                _selectedVoiceId.value = if (updatedWithLatestPresets.any { it.id == selectedId }) {
                    selectedId
                } else {
                    updatedWithLatestPresets.first().id
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

        // Load 20+ persistent Character Voice Assignments
        val rawAssignments = prefs.getString(KEY_CHARACTER_ASSIGNMENTS_JSON, null)
        if (!rawAssignments.isNullOrBlank()) {
            val loadedAssignments = CharacterVoiceAssignment.parseList(rawAssignments)
            if (loadedAssignments.isNotEmpty()) {
                // Merge with default presets to preserve any new defaults while honoring user overrides
                val merged = mutableMapOf<String, CharacterVoiceAssignment>()
                for (preset in CharacterVoiceAssignment.DEFAULT_PRESETS) {
                    merged[preset.characterName] = preset
                }
                for (loaded in loadedAssignments) {
                    merged[loaded.characterName] = loaded
                }
                _characterAssignments.value = merged.values.toList()
            } else {
                _characterAssignments.value = CharacterVoiceAssignment.DEFAULT_PRESETS
            }
        } else {
            _characterAssignments.value = CharacterVoiceAssignment.DEFAULT_PRESETS
            saveCharacterAssignmentsToDisk(context, CharacterVoiceAssignment.DEFAULT_PRESETS)
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

    /**
     * Adds a user-recorded or imported voice-style preset derived from microphone F0/cadence analysis.
     * Note: This is an acoustic pitch/rate preset, NOT true neural voice cloning.
     */
    fun addCustomVoiceStylePreset(
        context: Context,
        name: String,
        description: String,
        pitch: Float,
        speechRate: Float,
        assignedRole: SpeakerRole,
        sampleAudioPath: String?
    ): VoiceProfile {
        val cleanName = name.trim().ifEmpty { "Voice Preset ${_voices.value.size + 1}" }
        val newProfile = VoiceProfile(
            id = "voice_preset_${System.currentTimeMillis()}",
            name = cleanName,
            description = description.ifBlank { "Acoustic Voice-Style Preset" },
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

    // Backward compatibility alias
    fun addCustomClonedVoice(
        context: Context,
        name: String,
        description: String,
        pitch: Float,
        speechRate: Float,
        assignedRole: SpeakerRole,
        sampleAudioPath: String?
    ): VoiceProfile = addCustomVoiceStylePreset(
        context = context,
        name = name,
        description = description,
        pitch = pitch,
        speechRate = speechRate,
        assignedRole = assignedRole,
        sampleAudioPath = sampleAudioPath
    )

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

    fun getAssignmentForCharacter(rawName: String, context: Context? = null): CharacterVoiceAssignment {
        val norm = CharacterVoiceAssignment.normalizeName(rawName)
        if (norm.isBlank()) {
            return _characterAssignments.value.firstOrNull { it.characterName == "anon" }
                ?: CharacterVoiceAssignment.DEFAULT_PRESETS.first()
        }

        val currentList = _characterAssignments.value
        // 1. User overrides and exact matches take absolute priority
        val exactMatch = currentList.find { it.characterName == norm }
        if (exactMatch != null) {
            return exactMatch
        }

        // 2. Check prefix / contains match from presets or detected list
        val partialMatch = currentList.find { norm.contains(it.characterName) || it.characterName.contains(norm) }
        if (partialMatch != null) {
            return partialMatch
        }

        // 3. Deterministic assignment for newly encountered character:
        // Audited Supertonic 3 voice.bin mapping:
        // sid 0..4 = female F1..F5, sid 5..9 = male M1..M5
        val speakerCount = availableSpeakerCount.coerceAtLeast(1)
        val inferredGender = SpeakerHeuristicEngine.detectCharacterGender(rawName)
        val assignedSid = if (speakerCount >= 10) {
            if (inferredGender == SpeakerGender.FEMALE_18) {
                // Female characters mapped to F1..F5 (sid 0..4)
                Math.floorMod(norm.hashCode(), 5)
            } else {
                // Male characters mapped to M1..M5 (sid 5..9)
                5 + Math.floorMod(norm.hashCode(), 5)
            }
        } else {
            Math.floorMod(norm.hashCode(), speakerCount)
        }

        val cleanDisplay = rawName.trim()
            .trim('[', ']', '<', '>', '(', ')', '{', '}', ':', '：', '-', '–', '—', ' ', '【', '】', '《', '》', '「', '」', '`', '"', '\'')
            .replace(Regex("\\s+"), " ")
            .ifBlank { "Character ${currentList.size + 1}" }

        val genderTag = if (assignedSid in 0..4) "F${assignedSid + 1} (Female)" else "M${assignedSid - 4} (Male)"
        val newAssignment = CharacterVoiceAssignment(
            characterName = norm,
            displayName = cleanDisplay,
            speakerId = assignedSid,
            speed = 1.0f,
            pitch = 1.0f,
            isUserOverride = false,
            characterRoleNote = "Dynamically Discovered • $genderTag (sid: $assignedSid)"
        )

        // Add to state list and persist dynamically discovered character name
        val updated = currentList + newAssignment
        _characterAssignments.value = updated
        val targetContext = context ?: appContext
        if (targetContext != null) {
            saveCharacterAssignmentsToDisk(targetContext, updated)
        }
        return newAssignment
    }

    fun setUserOverrideForCharacter(
        context: Context,
        characterName: String,
        sid: Int,
        speed: Float = 1.0f,
        pitch: Float = 1.0f,
        displayName: String? = null,
        referenceVoiceId: String? = null,
        voicePresentation: String? = null,
        vocalStyleDescription: String? = null,
        voiceEngine: String? = null,
        defaultEmotion: String? = null,
        exaggeration: Float? = null,
        cfgWeight: Float? = null,
        pronunciationOverrides: Map<String, String>? = null
    ): CharacterVoiceAssignment {
        val norm = CharacterVoiceAssignment.normalizeName(characterName)
        val currentList = _characterAssignments.value
        val clampedSid = sid.coerceIn(0, 9)
        val clampedSpeed = speed.coerceIn(0.6f, 1.5f)
        val clampedPitch = pitch.coerceIn(0.6f, 1.5f)

        val existing = currentList.find { it.characterName == norm }
        val resolvedRefId = referenceVoiceId?.trim()?.ifBlank { null }
            ?: existing?.referenceVoiceId
            ?: if (clampedSid in 0..4) CharacterVoiceAssignment.DEFAULT_FEMALE_REF_ID else CharacterVoiceAssignment.DEFAULT_MALE_REF_ID
        val resolvedSha = when (resolvedRefId) {
            CharacterVoiceAssignment.DEFAULT_FEMALE_REF_ID -> CharacterVoiceAssignment.JENNY_FEMALE_REF_SHA256
            CharacterVoiceAssignment.DEFAULT_MALE_REF_ID -> CharacterVoiceAssignment.ANON_MALE_REF_SHA256
            else -> existing?.referenceAudioSha256 ?: CharacterVoiceAssignment.ANON_MALE_REF_SHA256
        }

        val updatedAssignment = if (existing != null) {
            existing.copy(
                displayName = displayName?.trim()?.ifBlank { null } ?: existing.displayName,
                speakerId = clampedSid,
                speed = clampedSpeed,
                pitch = clampedPitch,
                isUserOverride = true,
                lastDetectedTimestamp = System.currentTimeMillis(),
                referenceVoiceId = resolvedRefId,
                referenceAudioSha256 = resolvedSha,
                voicePresentation = voicePresentation?.trim()?.ifBlank { null } ?: existing.voicePresentation,
                vocalStyleDescription = vocalStyleDescription?.trim()?.ifBlank { null } ?: existing.vocalStyleDescription,
                voiceEngine = voiceEngine?.trim()?.ifBlank { null } ?: existing.voiceEngine,
                defaultEmotion = defaultEmotion?.trim()?.ifBlank { null } ?: existing.defaultEmotion,
                exaggeration = (exaggeration ?: existing.exaggeration).coerceIn(0.1f, 2.0f),
                cfgWeight = (cfgWeight ?: existing.cfgWeight).coerceIn(0.1f, 1.5f),
                pronunciationOverrides = pronunciationOverrides ?: existing.pronunciationOverrides
            )
        } else {
            val pres = voicePresentation?.trim()?.ifBlank { null } ?: if (clampedSid in 0..4) "female" else "male"
            CharacterVoiceAssignment(
                characterName = norm,
                displayName = displayName?.trim()?.ifBlank { null } ?: characterName.trim(),
                gameNamespace = activeGameNamespace,
                speakerId = clampedSid,
                speed = clampedSpeed,
                pitch = clampedPitch,
                isUserOverride = true,
                lastDetectedTimestamp = System.currentTimeMillis(),
                characterRoleNote = "User Override (sid: $clampedSid)",
                referenceVoiceId = resolvedRefId,
                referenceAudioSha256 = resolvedSha,
                voicePresentation = pres,
                vocalStyleDescription = vocalStyleDescription?.trim()?.ifBlank { null } ?: "User Override ($pres • sid: $clampedSid)",
                voiceEngine = voiceEngine?.trim()?.ifBlank { null } ?: "chatterbox_local_server",
                defaultEmotion = defaultEmotion?.trim()?.ifBlank { null } ?: "neutral",
                exaggeration = (exaggeration ?: 0.50f).coerceIn(0.1f, 2.0f),
                cfgWeight = (cfgWeight ?: 0.40f).coerceIn(0.1f, 1.5f),
                pronunciationOverrides = pronunciationOverrides ?: emptyMap()
            )
        }

        val updatedList = currentList.filterNot { it.characterName == norm } + updatedAssignment
        _characterAssignments.value = updatedList
        _activeCharacterAssignment.value = updatedAssignment
        _lastAssignedSpeakerId.value = clampedSid

        saveCharacterAssignmentsToDisk(context, updatedList)
        return updatedAssignment
    }

    fun updateCharacterVoiceProfile(
        context: Context,
        updated: CharacterVoiceAssignment
    ): CharacterVoiceAssignment {
        val norm = CharacterVoiceAssignment.normalizeName(updated.characterName)
        val currentList = _characterAssignments.value
        val finalProfile = updated.copy(
            characterName = norm,
            isUserOverride = true,
            lastDetectedTimestamp = System.currentTimeMillis()
        )
        val updatedList = currentList.filterNot { it.characterName == norm } + finalProfile
        _characterAssignments.value = updatedList
        if (_activeCharacterAssignment.value?.characterName == norm) {
            _activeCharacterAssignment.value = finalProfile
        }
        saveCharacterAssignmentsToDisk(context, updatedList)
        return finalProfile
    }

    /**
     * Resolves or extracts a character's reference voice `.wav` file on disk so it can be
     * played directly ("Play Reference Voice Clip") or inspected for SHA-256 and RMS verification.
     */
    fun resolveReferenceVoiceFile(context: Context, referenceVoiceId: String): File? {
        val cleanId = referenceVoiceId.trim().removeSuffix(".wav")
        if (cleanId.isBlank()) return null

        val customDir = File(context.filesDir, "custom_voices").apply { mkdirs() }
        val customFile = File(customDir, "$cleanId.wav")
        if (customFile.exists() && customFile.length() > 100L) {
            return customFile
        }

        return try {
            context.assets.open("reference_voices/$cleanId.wav").use { input ->
                FileOutputStream(customFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (customFile.exists() && customFile.length() > 100L) customFile else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Plays the raw reference voice clip (`.wav` prompt) for a character profile separately from
     * synthesized Hindi speech so the user can audition the source speaker identity directly.
     */
    fun playCharacterReferenceVoiceClip(
        context: Context,
        assignment: CharacterVoiceAssignment,
        onFinished: (Boolean) -> Unit = {}
    ) {
        val wavFile = resolveReferenceVoiceFile(context, assignment.referenceVoiceId)
        if (wavFile == null || !wavFile.exists()) {
            onFinished(false)
            return
        }
        playRecordedReferenceSample(wavFile.absolutePath) {
            onFinished(true)
        }
    }

    /**
     * Returns an honest summary of how many acoustically distinct reference voice clips (by SHA-256)
     * are currently assigned across all character profiles.
     */
    fun getUniqueReferenceVoiceSummary(): Triple<Int, Int, String> {
        val list = _characterAssignments.value
        val uniqueCount = CharacterVoiceAssignment.countUniqueReferenceVoices(list)
        val totalCount = list.size
        val summary = "$uniqueCount unique reference voice clips across $totalCount character profiles " +
            "(import consenting .wav clips to give additional characters dedicated voices)"
        return Triple(uniqueCount, totalCount, summary)
    }

    fun renameCharacter(
        context: Context,
        oldNormalizedName: String,
        newDisplayName: String
    ): CharacterVoiceAssignment? {
        val normOld = CharacterVoiceAssignment.normalizeName(oldNormalizedName)
        val cleanNew = newDisplayName.trim().ifBlank { return null }
        val normNew = CharacterVoiceAssignment.normalizeName(cleanNew)

        val currentList = _characterAssignments.value
        val existing = currentList.find { it.characterName == normOld } ?: return null

        val renamed = existing.copy(
            characterName = normNew,
            displayName = cleanNew,
            isUserOverride = true
        )

        val updatedList = currentList.filterNot { it.characterName == normOld || it.characterName == normNew } + renamed
        _characterAssignments.value = updatedList
        if (_activeCharacterAssignment.value?.characterName == normOld) {
            _activeCharacterAssignment.value = renamed
        }

        saveCharacterAssignmentsToDisk(context, updatedList)
        return renamed
    }

    fun resetCharacterToDefault(context: Context, characterName: String) {
        val norm = CharacterVoiceAssignment.normalizeName(characterName)
        val defaultMatch = CharacterVoiceAssignment.DEFAULT_PRESETS.find { it.characterName == norm }
        val currentList = _characterAssignments.value

        val updatedList = if (defaultMatch != null) {
            currentList.filterNot { it.characterName == norm } + defaultMatch
        } else {
            currentList.filterNot { it.characterName == norm }
        }

        _characterAssignments.value = updatedList
        saveCharacterAssignmentsToDisk(context, updatedList)
    }

    fun restoreAllCharacterDefaults(context: Context) {
        _characterAssignments.value = CharacterVoiceAssignment.DEFAULT_PRESETS
        _activeCharacterAssignment.value = CharacterVoiceAssignment.DEFAULT_PRESETS.first()
        saveCharacterAssignmentsToDisk(context, CharacterVoiceAssignment.DEFAULT_PRESETS)
    }

    fun deleteCharacterAssignment(context: Context, characterName: String) {
        val norm = CharacterVoiceAssignment.normalizeName(characterName)
        val currentList = _characterAssignments.value
        if (currentList.size <= 1) return // Keep at least one profile
        val updated = currentList.filterNot { it.characterName == norm }
        _characterAssignments.value = updated
        saveCharacterAssignmentsToDisk(context, updated)
    }

    private fun saveCharacterAssignmentsToDisk(
        context: Context,
        assignments: List<CharacterVoiceAssignment>
    ) {
        val json = CharacterVoiceAssignment.serializeList(assignments)
        val gameKey = "${KEY_CHARACTER_ASSIGNMENTS_JSON}_$activeGameNamespace"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHARACTER_ASSIGNMENTS_JSON, json)
            .putString(gameKey, json)
            .apply()
    }

    /**
     * Multi-Speaker Heuristic Dialogue Analyzer:
     * Analyzes scan results via [SpeakerHeuristicEngine] to identify character name tags
     * (e.g., `"Jenny:"`, `"[Anon]"`, `"Kaal: ..."`, or RPG nameplate headers)
     * and maps them to the corresponding persistent [CharacterVoiceAssignment] and [VoiceProfile].
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

        // Resolve persistent CharacterVoiceAssignment for Supertonic 3 sid assignment
        val candidateName = result.detectedSpeakerTag ?: result.displaySpeakerName.substringBefore(" •")
        val characterAssignment = getAssignmentForCharacter(candidateName)
        _activeCharacterAssignment.value = characterAssignment
        _lastAssignedSpeakerId.value = characterAssignment.speakerId

        _lastDetectedSpeaker.value = characterAssignment.displayName
        _lastAssignedVoiceName.value = "Supertonic Sid #${characterAssignment.speakerId} (${characterAssignment.displayName})"
        _lastHeuristicRule.value = result.matchedRuleDescription

        val effectiveVoice = result.assignedVoice.copy(
            engineVoiceIndex = characterAssignment.speakerId,
            pitch = characterAssignment.pitch,
            speechRate = characterAssignment.speed
        )

        val parsedLine = ParsedDialogueLine(
            speakerName = characterAssignment.displayName,
            spokenDialogue = result.cleanedDialogueText,
            resolvedRole = result.matchedRole,
            detectedGender = result.detectedGender,
            assignedVoice = effectiveVoice,
            matchedHeuristicRule = result.matchedRuleDescription
        )
        _lastParsedDialogue.value = parsedLine
        return parsedLine
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
                put("gender", v.gender.code)
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
                val role = SpeakerRole.fromCode(obj.optString("assignedRole", "char_a"))
                val pitch = obj.optDouble("pitch", 0.95).toFloat().coerceIn(0.4f, 1.8f)
                val defaultGenderCode = if (role == SpeakerRole.CHARACTER_B || role == SpeakerRole.NARRATOR || pitch >= 1.12f) {
                    SpeakerGender.FEMALE_18.code
                } else {
                    SpeakerGender.MALE_18.code
                }
                list.add(
                    VoiceProfile(
                        id = obj.optString("id", "voice_$i"),
                        name = obj.optString("name", "Voice ${i + 1}"),
                        description = obj.optString("description", "Custom TTS Voice Profile"),
                        pitch = pitch,
                        speechRate = obj.optDouble("speechRate", 0.95).toFloat().coerceIn(0.4f, 1.8f),
                        engineVoiceIndex = obj.optInt("engineVoiceIndex", i % 4),
                        assignedRole = role,
                        gender = SpeakerGender.fromCode(obj.optString("gender", defaultGenderCode)),
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
