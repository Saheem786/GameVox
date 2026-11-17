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
    ),
    UNKNOWN(
        code = "unknown",
        displayLabel = "Unknown",
        defaultPitch18Yo = 1.00f,
        defaultSpeechRate18Yo = 1.0f
    );

    companion object {
        fun fromCode(code: String?): SpeakerGender {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: UNKNOWN
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
 * Category classification for voices in Voice Library.
 */
enum class VoiceCategory(val code: String, val displayLabel: String) {
    DEFAULT_MALE("default_male", "Default Male"),
    DEFAULT_FEMALE("default_female", "Default Female"),
    IMPORTED("imported", "Imported"),
    GENERATED_OFFLINE("generated_offline", "Generated (Offline)"),
    GENERATED_ONLINE("generated_online", "Generated (Online)"),
    CLONED("cloned", "Cloned Voice");

    companion object {
        fun fromCode(code: String?): VoiceCategory {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: IMPORTED
        }
    }
}

/**
 * Result of inspecting an imported or recorded voice sample before saving/cloning.
 */
data class SampleAudioInspection(
    val filePath: String,
    val fileSizeBytes: Long,
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val durationSeconds: Float,
    val rmsEnergy: Float,
    val clippingRatio: Float,
    val sha256: String,
    val isWavPcm: Boolean,
    val qualityVerdict: String,
    val qualityDetail: String,
    val recommendedForCloning: Boolean,
    val cloningBackendCompatible: Boolean,
    val cloningCompatibilityNote: String
)

/**
 * Represents a built-in, generated, imported, or cloned TTS Voice Profile stored locally on device.
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
    val sampleAudioPath: String? = null,
    val voiceCategory: VoiceCategory = if (isCustomCloned) {
        VoiceCategory.IMPORTED
    } else if (gender == SpeakerGender.FEMALE_18) {
        VoiceCategory.DEFAULT_FEMALE
    } else {
        VoiceCategory.DEFAULT_MALE
    },
    val sourceModelInfo: String = if (isCustomCloned) {
        "User Custom Audio Sample"
    } else {
        "Supertonic 3 Multilingual (SID #$engineVoiceIndex)"
    },
    val languageCode: String = "hi",
    val styleTag: String = "Neutral",
    val isSystemProtected: Boolean = !isCustomCloned,
    val isNeuralCloned: Boolean = false
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
    val matchedHeuristicRule: String = "",
    val characterAssignment: CharacterVoiceAssignment? = null,
    val routeDecision: com.example.engine.VoiceRouteDecision? = null
)

/**
 * Manages:
 * 1. Persistent storage of Built-in and Custom Cloned [VoiceProfile]s in `SharedPreferences` + `filesDir/custom_voices/`.
 * 2. Real 3–5 second microphone audio recording (`AudioRecord` 16kHz PCM -> `.wav`) and acoustic
 *    pitch ($F_0$ autocorrelation) + cadence analysis to generate a custom cloned TTS voice profile.
 * 3. Audio file import (`content://` URI -> local storage + acoustic analysis).
 * 4. Multi-Speaker Dialogue speaker detection (`Character A`, `Character B`, named game speakers)
 *    and automatic per-character voice assignment via [com.example.engine.CharacterVoiceRouter].
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
    private const val KEY_UNLABELED_OVERRIDE_CHAR = "unlabeled_subtitle_override_char_v1"

    private val defaultPresets = listOf(
        VoiceProfile(
            id = "preset_char_a_hero",
            name = "M1 • Anon (Young Male Lead)",
            description = "Natural young male TTS voice for Anon & male leads",
            pitch = SpeakerGender.MALE_18.defaultPitch18Yo,
            speechRate = SpeakerGender.MALE_18.defaultSpeechRate18Yo,
            engineVoiceIndex = 5,
            assignedRole = SpeakerRole.CHARACTER_A,
            gender = SpeakerGender.MALE_18,
            isCustomCloned = false,
            voiceCategory = VoiceCategory.DEFAULT_MALE,
            sourceModelInfo = "Supertonic 3 Neural • M1 (SID #5) / anon_male_ref.wav",
            languageCode = "hi",
            styleTag = "Heroic Conversational",
            isSystemProtected = true
        ),
        VoiceProfile(
            id = "preset_char_b_companion",
            name = "F2 • Jenny (Bright Female Lead)",
            description = "Warm, bright female TTS voice for Jenny & companions",
            pitch = SpeakerGender.FEMALE_18.defaultPitch18Yo,
            speechRate = SpeakerGender.FEMALE_18.defaultSpeechRate18Yo,
            engineVoiceIndex = 1,
            assignedRole = SpeakerRole.CHARACTER_B,
            gender = SpeakerGender.FEMALE_18,
            isCustomCloned = false,
            voiceCategory = VoiceCategory.DEFAULT_FEMALE,
            sourceModelInfo = "Supertonic 3 Neural • F2 (SID #1) / jenny_female_ref.wav",
            languageCode = "hi",
            styleTag = "Expressive Warm",
            isSystemProtected = true
        ),
        VoiceProfile(
            id = "preset_char_c_boss",
            name = "M3 • Kaal (Deep Male Villain)",
            description = "Deep male voice for bosses, warlords & villains",
            pitch = 0.78f,
            speechRate = 0.94f,
            engineVoiceIndex = 7,
            assignedRole = SpeakerRole.CHARACTER_C,
            gender = SpeakerGender.MALE_18,
            isCustomCloned = false,
            voiceCategory = VoiceCategory.DEFAULT_MALE,
            sourceModelInfo = "Supertonic 3 Neural • M3 (SID #7) / anon_male_ref.wav",
            languageCode = "hi",
            styleTag = "Commanding Intense",
            isSystemProtected = true
        ),
        VoiceProfile(
            id = "preset_narrator_ai",
            name = "F3 • Ira-AI (Composed Narrator)",
            description = "Crisp female voice for game terminals, quests & narration",
            pitch = 1.12f,
            speechRate = 1.0f,
            engineVoiceIndex = 2,
            assignedRole = SpeakerRole.NARRATOR,
            gender = SpeakerGender.FEMALE_18,
            isCustomCloned = false,
            voiceCategory = VoiceCategory.DEFAULT_FEMALE,
            sourceModelInfo = "Supertonic 3 Neural • F3 (SID #2) / jenny_female_ref.wav",
            languageCode = "hi",
            styleTag = "Composed Analytical",
            isSystemProtected = true
        ),
        VoiceProfile(
            id = "preset_f1_priya",
            name = "F1 • Priya (Gentle Female)",
            description = "Soft, melodic female timbre suited for empathetic & royal roles",
            pitch = 1.18f,
            speechRate = 0.98f,
            engineVoiceIndex = 0,
            assignedRole = SpeakerRole.CHARACTER_B,
            gender = SpeakerGender.FEMALE_18,
            isCustomCloned = false,
            voiceCategory = VoiceCategory.DEFAULT_FEMALE,
            sourceModelInfo = "Supertonic 3 Neural • F1 (SID #0) / jenny_female_ref.wav",
            languageCode = "hi",
            styleTag = "Gentle Melodic",
            isSystemProtected = true
        ),
        VoiceProfile(
            id = "preset_f4_sarah",
            name = "F4 • Sarah (Tactical Female)",
            description = "Resolute tactical female commander voice with confident diction",
            pitch = 1.06f,
            speechRate = 0.96f,
            engineVoiceIndex = 3,
            assignedRole = SpeakerRole.CHARACTER_B,
            gender = SpeakerGender.FEMALE_18,
            isCustomCloned = false,
            voiceCategory = VoiceCategory.DEFAULT_FEMALE,
            sourceModelInfo = "Supertonic 3 Neural • F4 (SID #3) / jenny_female_ref.wav",
            languageCode = "hi",
            styleTag = "Tactical Authoritative",
            isSystemProtected = true
        ),
        VoiceProfile(
            id = "preset_f5_oracle",
            name = "F5 • Oracle (Ethereal Female)",
            description = "Mystic, calm, resonant female timbre with measured pacing",
            pitch = 1.12f,
            speechRate = 0.90f,
            engineVoiceIndex = 4,
            assignedRole = SpeakerRole.NARRATOR,
            gender = SpeakerGender.FEMALE_18,
            isCustomCloned = false,
            voiceCategory = VoiceCategory.DEFAULT_FEMALE,
            sourceModelInfo = "Supertonic 3 Neural • F5 (SID #4) / jenny_female_ref.wav",
            languageCode = "hi",
            styleTag = "Mystic Calm",
            isSystemProtected = true
        ),
        VoiceProfile(
            id = "preset_m2_alex",
            name = "M2 • Alex (Energetic Male Scout)",
            description = "Agile, upbeat young male voice for scouts & marksmen",
            pitch = 1.00f,
            speechRate = 1.06f,
            engineVoiceIndex = 6,
            assignedRole = SpeakerRole.CHARACTER_A,
            gender = SpeakerGender.MALE_18,
            isCustomCloned = false,
            voiceCategory = VoiceCategory.DEFAULT_MALE,
            sourceModelInfo = "Supertonic 3 Neural • M2 (SID #6) / anon_male_ref.wav",
            languageCode = "hi",
            styleTag = "Energetic Fast",
            isSystemProtected = true
        ),
        VoiceProfile(
            id = "preset_m4_vikram",
            name = "M4 • Vikram (Mature Male Veteran)",
            description = "Grounded, seasoned battle veteran male voice",
            pitch = 0.85f,
            speechRate = 0.90f,
            engineVoiceIndex = 8,
            assignedRole = SpeakerRole.CHARACTER_C,
            gender = SpeakerGender.MALE_18,
            isCustomCloned = false,
            voiceCategory = VoiceCategory.DEFAULT_MALE,
            sourceModelInfo = "Supertonic 3 Neural • M4 (SID #8) / anon_male_ref.wav",
            languageCode = "hi",
            styleTag = "Mature Grounded",
            isSystemProtected = true
        ),
        VoiceProfile(
            id = "preset_m5_guard",
            name = "M5 • Guard (Commanding Sentinel)",
            description = "Heavy, urgent male voice for sentinels & announcers",
            pitch = 0.82f,
            speechRate = 0.94f,
            engineVoiceIndex = 9,
            assignedRole = SpeakerRole.CHARACTER_C,
            gender = SpeakerGender.MALE_18,
            isCustomCloned = false,
            voiceCategory = VoiceCategory.DEFAULT_MALE,
            sourceModelInfo = "Supertonic 3 Neural • M5 (SID #9) / anon_male_ref.wav",
            languageCode = "hi",
            styleTag = "Commanding Urgent",
            isSystemProtected = true
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

    private val _unlabeledOverrideCharName = MutableStateFlow<String?>(null)
    val unlabeledOverrideCharName: StateFlow<String?> = _unlabeledOverrideCharName.asStateFlow()

    private val unlabeledOverridesByGame = ConcurrentHashMap<String, String>()

    private var activeMediaPlayer: MediaPlayer? = null
    private var appContext: Context? = null

    @Volatile
    private var availableSpeakerCount: Int = 10

    @Volatile
    private var activeGameNamespace: String = "default_game"

    fun getActiveGameNamespace(): String = activeGameNamespace

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
            val savedOverride = prefs.getString("${KEY_UNLABELED_OVERRIDE_CHAR}_$clean", null)
                ?.trim()?.takeIf { it.isNotEmpty() }
            if (savedOverride != null) {
                unlabeledOverridesByGame[clean] = savedOverride
            }
            _unlabeledOverrideCharName.value = unlabeledOverridesByGame[clean]
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
                saveVoicesToDisk(context, defaultPresets, _selectedVoiceId.value)
            } else {
                // Preserve user custom voices AND any user-edited metadata/names on existing IDs,
                // while upgrading default system source metadata and appending any missing default presets.
                val loadedById = loaded.associateBy { it.id }
                val mergedDefaults = defaultPresets.map { preset ->
                    val existing = loadedById[preset.id]
                    if (existing != null) {
                        preset.copy(
                            name = existing.name.ifBlank { preset.name },
                            pitch = existing.pitch,
                            speechRate = existing.speechRate,
                            languageCode = existing.languageCode,
                            styleTag = existing.styleTag,
                            isSystemProtected = true
                        )
                    } else {
                        preset
                    }
                }
                val defaultIds = defaultPresets.map { it.id }.toSet()
                val customAndUserVoices = loaded.filter { it.id !in defaultIds }
                val finalVoices = mergedDefaults + customAndUserVoices
                _voices.value = finalVoices
                _selectedVoiceId.value = if (finalVoices.any { it.id == selectedId }) {
                    selectedId
                } else {
                    finalVoices.first().id
                }
                if (finalVoices.size != loaded.size) {
                    saveVoicesToDisk(context, finalVoices, _selectedVoiceId.value)
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

        val savedUnlabeledOverride = prefs.getString("${KEY_UNLABELED_OVERRIDE_CHAR}_$activeGameNamespace", null)
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (savedUnlabeledOverride != null) {
            unlabeledOverridesByGame[activeGameNamespace] = savedUnlabeledOverride
        }
        _unlabeledOverrideCharName.value = savedUnlabeledOverride
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

        // Synchronize active character assignment so Voice Test and live gameplay use the exact same voice configuration
        val targetCharacterKey = when {
            voice.id == "preset_char_b_companion" || voice.gender == SpeakerGender.FEMALE_18 -> "jenny"
            voice.id == "preset_char_c_boss" -> "kaal"
            voice.id == "preset_narrator_ai" -> "ira"
            else -> "anon"
        }
        findExistingAssignment(targetCharacterKey, activeGameNamespace)?.let { matchedChar ->
            _activeCharacterAssignment.value = matchedChar
            _lastAssignedSpeakerId.value = matchedChar.speakerId
        }

        // Also update the active pitch and speechRate in TranslatorStateRepository
        TranslatorStateRepository.updateTtsPitch(context, voice.pitch)
        TranslatorStateRepository.updateTtsSpeechRate(context, voice.speechRate)
    }

    /**
     * Selects the active character manually from the Floating Menu or Voice Manager and saves
     * that selection per game (`activeGameNamespace`).
     * - Passing `null` or `"unknown"` clears the manual unlabeled override so unlabeled lines show `"Unknown"`.
     * - Passing `"anon"`, `"jenny"`, `"kaal"`, `"ira"`, or any character name saves it per game as the
     *   active character for unlabeled subtitles AND updates `_selectedVoiceId` + `_activeCharacterAssignment`.
     *   Subsequent explicitly recognized character headers in OCR (`[Jenny]: ...`, `Anon: ...`) still
     *   automatically switch voices!
     */
    fun selectActiveCharacterForGame(
        context: Context?,
        characterNameOrNull: String?,
        gameNamespace: String = activeGameNamespace
    ): CharacterVoiceAssignment {
        val cleanNs = gameNamespace.trim().lowercase(Locale.ROOT).ifBlank { activeGameNamespace }
        val norm = characterNameOrNull?.let { CharacterVoiceAssignment.normalizeName(it) }.orEmpty()
        if (norm.isBlank() || norm == CharacterVoiceAssignment.UNKNOWN_SPEAKER_NAME) {
            setUnlabeledSubtitleManualOverride(context, null, cleanNs)
            val unknownAssignment = getUnknownSpeakerAssignment(cleanNs)
            _activeCharacterAssignment.value = unknownAssignment
            _lastDetectedSpeaker.value = unknownAssignment.displayName
            _lastAssignedSpeakerId.value = unknownAssignment.speakerId
            return unknownAssignment
        }

        setUnlabeledSubtitleManualOverride(context, norm, cleanNs)
        val assignment = findExistingAssignment(norm, cleanNs)
            ?: getOrCreateAssignmentForCharacter(norm, norm, cleanNs, context)
        _activeCharacterAssignment.value = assignment
        _lastDetectedSpeaker.value = assignment.displayName
        _lastAssignedSpeakerId.value = assignment.speakerId

        // Keep legacy VoiceProfile selection synchronized with the selected character's gender/role
        val matchingPreset = if (assignment.isFemale) {
            _voices.value.firstOrNull { it.gender == SpeakerGender.FEMALE_18 }
        } else {
            _voices.value.firstOrNull { it.gender == SpeakerGender.MALE_18 }
        }
        if (matchingPreset != null) {
            _selectedVoiceId.value = matchingPreset.id
            _lastAssignedVoiceName.value = "${assignment.displayName} (Sid #${assignment.speakerId})"
            if (context != null) {
                TranslatorStateRepository.updateTtsPitch(context, assignment.pitch)
                TranslatorStateRepository.updateTtsSpeechRate(context, assignment.speed)
            }
        }
        return assignment
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

    fun renameVoice(
        context: Context,
        voiceId: String,
        newName: String
    ): VoiceProfile? {
        val cleanName = newName.trim().ifBlank { return null }
        val currentList = _voices.value
        val existing = currentList.find { it.id == voiceId } ?: return null
        val renamed = existing.copy(name = cleanName)
        val updated = currentList.map { if (it.id == voiceId) renamed else it }
        _voices.value = updated
        if (_selectedVoiceId.value == voiceId) {
            _lastAssignedVoiceName.value = renamed.name
        }
        saveVoicesToDisk(context, updated, _selectedVoiceId.value)
        return renamed
    }

    fun getCharactersAssignedToVoice(voiceId: String): List<CharacterVoiceAssignment> {
        val voice = _voices.value.find { it.id == voiceId } ?: return emptyList()
        val voiceSampleBase = voice.sampleAudioPath?.let { File(it).name }
        return _characterAssignments.value.filter { charAssign ->
            charAssign.assignedVoiceId == voiceId ||
                (!voiceSampleBase.isNullOrBlank() && charAssign.referenceVoiceId == voiceSampleBase) ||
                (!voice.isCustomCloned && charAssign.assignedVoiceId.isBlank() &&
                    !charAssign.isUnknownOrNeutral && charAssign.speakerId == voice.engineVoiceIndex)
        }
    }

    fun assignVoiceToCharacter(
        context: Context,
        voice: VoiceProfile,
        rawCharacterName: String
    ): CharacterVoiceAssignment {
        val norm = CharacterVoiceAssignment.normalizeName(rawCharacterName).ifBlank { "anon" }
        val existing = findExistingAssignment(norm, activeGameNamespace)
            ?: getOrCreateAssignmentForCharacter(rawCharacterName, norm, activeGameNamespace, context)

        val customSampleFile = voice.sampleAudioPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 100L }
        val customSha = customSampleFile?.let { com.example.engine.CharacterVoiceRouter.computeFileSha256(it) }.orEmpty()
        val resolvedRefId = when {
            customSampleFile != null -> customSampleFile.nameWithoutExtension
            voice.gender == SpeakerGender.FEMALE_18 -> CharacterVoiceAssignment.DEFAULT_FEMALE_REF_ID
            else -> CharacterVoiceAssignment.DEFAULT_MALE_REF_ID
        }
        val resolvedSha = when {
            customSha.isNotBlank() -> customSha
            voice.gender == SpeakerGender.FEMALE_18 -> CharacterVoiceAssignment.JENNY_FEMALE_REF_SHA256
            else -> CharacterVoiceAssignment.ANON_MALE_REF_SHA256
        }
        val presentation = when (voice.gender) {
            SpeakerGender.FEMALE_18 -> "female"
            SpeakerGender.MALE_18 -> "male"
            SpeakerGender.UNKNOWN -> existing.voicePresentation
        }
        val updated = existing.copy(
            speakerId = voice.engineVoiceIndex.coerceIn(0, 9),
            speed = voice.speechRate.coerceIn(0.6f, 1.5f),
            pitch = voice.pitch.coerceIn(0.6f, 1.5f),
            referenceVoiceId = resolvedRefId,
            referenceAudioSha256 = resolvedSha,
            voicePresentation = presentation,
            vocalStyleDescription = "${voice.name} • ${voice.sourceModelInfo}",
            assignedVoiceId = voice.id,
            isUserOverride = true,
            lastDetectedTimestamp = System.currentTimeMillis()
        )
        return updateCharacterVoiceProfile(context, updated)
    }

    fun deleteVoiceSafely(
        context: Context,
        voiceId: String,
        replacementVoiceId: String? = null
    ): Result<Unit> {
        val currentList = _voices.value
        val target = currentList.find { it.id == voiceId }
            ?: return Result.failure(IllegalArgumentException("Voice not found."))
        if (target.isSystemProtected) {
            return Result.failure(IllegalStateException("Default system voice '${target.name}' is protected and cannot be deleted."))
        }
        if (currentList.size <= 1) {
            return Result.failure(IllegalStateException("At least one voice must remain in Voice Library."))
        }

        val affectedChars = getCharactersAssignedToVoice(voiceId)
        val affectedRoles = _roleVoiceAssignments.value.filterValues { it == voiceId }.keys
        val replacement = replacementVoiceId?.let { rId ->
            currentList.find { it.id == rId && it.id != voiceId }
        }
        if (affectedChars.isNotEmpty() && replacement == null) {
            return Result.failure(
                IllegalStateException(
                    "Voice '${target.name}' is currently assigned to ${affectedChars.joinToString { it.displayName }}. Select a replacement voice first."
                )
            )
        }

        val fallbackReplacement = replacement
            ?: currentList.firstOrNull { it.id != voiceId && it.gender == target.gender }
            ?: currentList.first { it.id != voiceId }

        if (affectedChars.isNotEmpty()) {
            affectedChars.forEach { charAssign ->
                assignVoiceToCharacter(context, fallbackReplacement, charAssign.characterName)
            }
        }
        if (affectedRoles.isNotEmpty()) {
            val updatedRoles = _roleVoiceAssignments.value.toMutableMap()
            affectedRoles.forEach { role -> updatedRoles[role] = fallbackReplacement.id }
            _roleVoiceAssignments.value = updatedRoles
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ROLE_ASSIGNMENTS_JSON, serializeRoleAssignmentsJson(updatedRoles))
                .apply()
        }

        val remaining = currentList.filterNot { it.id == voiceId }
        val targetSample = target.sampleAudioPath
        if (!targetSample.isNullOrBlank() && remaining.none { it.sampleAudioPath == targetSample }) {
            try {
                val f = File(targetSample)
                if (f.exists() && f.absolutePath.contains("custom_voices")) {
                    f.delete()
                }
            } catch (_: Exception) {
            }
        }

        val newSelectedId = if (_selectedVoiceId.value == voiceId) {
            fallbackReplacement.id
        } else {
            _selectedVoiceId.value
        }
        _voices.value = remaining
        _selectedVoiceId.value = newSelectedId
        saveVoicesToDisk(context, remaining, newSelectedId)
        return Result.success(Unit)
    }

    fun deleteVoice(context: Context, voiceId: String) {
        val target = _voices.value.find { it.id == voiceId } ?: return
        if (target.isSystemProtected) return
        val replacement = _voices.value.firstOrNull { it.id != voiceId && it.gender == target.gender }
            ?: _voices.value.firstOrNull { it.id != voiceId }
        deleteVoiceSafely(context, voiceId, replacement?.id)
    }

    fun restoreDefaultVoices(context: Context) {
        val customOnly = _voices.value.filter { !it.isSystemProtected }
        val merged = defaultPresets + customOnly
        _voices.value = merged
        saveVoicesToDisk(context, merged, _selectedVoiceId.value)
    }

    fun addGeneratedOrImportedVoice(
        context: Context,
        name: String,
        description: String,
        gender: SpeakerGender,
        pitch: Float = gender.defaultPitch18Yo,
        speechRate: Float = gender.defaultSpeechRate18Yo,
        engineVoiceIndex: Int = if (gender == SpeakerGender.FEMALE_18) 1 else 5,
        voiceCategory: VoiceCategory,
        sourceModelInfo: String,
        languageCode: String = "hi",
        styleTag: String = "Neutral",
        sampleAudioPath: String? = null,
        isNeuralCloned: Boolean = false,
        selectImmediately: Boolean = true
    ): VoiceProfile {
        val cleanName = name.trim().ifEmpty { "Voice ${_voices.value.size + 1}" }
        val role = if (gender == SpeakerGender.FEMALE_18) SpeakerRole.CHARACTER_B else SpeakerRole.CHARACTER_A
        val newProfile = VoiceProfile(
            id = "voice_${voiceCategory.code}_${System.currentTimeMillis()}",
            name = cleanName,
            description = description.ifBlank { sourceModelInfo },
            pitch = pitch.coerceIn(0.45f, 1.75f),
            speechRate = speechRate.coerceIn(0.55f, 1.65f),
            engineVoiceIndex = engineVoiceIndex.coerceIn(0, 9),
            assignedRole = role,
            gender = gender,
            isCustomCloned = true,
            sampleAudioPath = sampleAudioPath,
            voiceCategory = voiceCategory,
            sourceModelInfo = sourceModelInfo,
            languageCode = languageCode.trim().ifBlank { "hi" },
            styleTag = styleTag.trim().ifBlank { "Neutral" },
            isSystemProtected = false,
            isNeuralCloned = isNeuralCloned
        )
        val updated = listOf(newProfile) + _voices.value
        _voices.value = updated
        if (selectImmediately) {
            _selectedVoiceId.value = newProfile.id
        }
        saveVoicesToDisk(context, updated, _selectedVoiceId.value)
        if (selectImmediately) {
            selectVoice(context, newProfile)
        }
        return newProfile
    }

    /**
     * Adds a user-recorded or imported voice-style preset derived from microphone F0/cadence analysis.
     * Note: This is an acoustic pitch/rate preset, NOT true neural voice cloning unless Chatterbox cloned it.
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
        val inferredGender = if (assignedRole == SpeakerRole.CHARACTER_B || assignedRole == SpeakerRole.NARRATOR || pitch >= 1.12f) {
            SpeakerGender.FEMALE_18
        } else {
            SpeakerGender.MALE_18
        }
        val cleanName = name.trim().ifEmpty { "Voice Preset ${_voices.value.size + 1}" }
        val newProfile = VoiceProfile(
            id = "voice_preset_${System.currentTimeMillis()}",
            name = cleanName,
            description = description.ifBlank { "Acoustic Voice-Style Preset" },
            pitch = pitch.coerceIn(0.45f, 1.75f),
            speechRate = speechRate.coerceIn(0.55f, 1.65f),
            engineVoiceIndex = if (inferredGender == SpeakerGender.FEMALE_18) 1 else 5,
            assignedRole = assignedRole,
            gender = inferredGender,
            isCustomCloned = true,
            sampleAudioPath = sampleAudioPath,
            voiceCategory = VoiceCategory.IMPORTED,
            sourceModelInfo = "Imported Sample + Acoustic Pitch/Rate Preset",
            languageCode = "hi",
            styleTag = "Custom Sample",
            isSystemProtected = false,
            isNeuralCloned = false
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
     * Inspects an audio sample file for duration (5-10s recommended for cloning), sample rate,
     * RMS level, clipping ratio, SHA-256, and whether the Chatterbox neural cloning backend is available.
     */
    fun inspectAudioSample(file: File): SampleAudioInspection {
        val state = TranslatorStateRepository.state.value
        val cloningCompatible = state.chatterboxServerReachable && state.chatterboxModelLoaded
        val cloningNote = if (cloningCompatible) {
            "Chatterbox neural cloning backend ready (${state.chatterboxServerUrl})"
        } else {
            "Chatterbox server not connected — sample can be saved as Imported reference & acoustic preset, but true neural voice cloning requires connecting the Wi-Fi Chatterbox server."
        }
        if (!file.exists() || file.length() <= 44L) {
            return SampleAudioInspection(
                filePath = file.absolutePath,
                fileSizeBytes = file.length(),
                sampleRateHz = 0,
                channels = 0,
                bitsPerSample = 0,
                durationSeconds = 0f,
                rmsEnergy = 0f,
                clippingRatio = 0f,
                sha256 = "",
                isWavPcm = false,
                qualityVerdict = "Invalid / Missing Audio",
                qualityDetail = "Audio file is missing or empty (${file.length()} bytes).",
                recommendedForCloning = false,
                cloningBackendCompatible = cloningCompatible,
                cloningCompatibilityNote = cloningNote
            )
        }
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            ByteArray(0)
        }
        val sha = com.example.engine.CharacterVoiceRouter.computeFileSha256(file)
        val isRiffWav = bytes.size >= 44 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
        if (!isRiffWav) {
            val approxSec = (file.length() / 16000f).coerceAtLeast(0.5f)
            return SampleAudioInspection(
                filePath = file.absolutePath,
                fileSizeBytes = file.length(),
                sampleRateHz = 0,
                channels = 0,
                bitsPerSample = 0,
                durationSeconds = approxSec,
                rmsEnergy = 0f,
                clippingRatio = 0f,
                sha256 = sha,
                isWavPcm = false,
                qualityVerdict = "Encoded Audio (${file.extension.uppercase(Locale.ROOT).ifBlank { "AUDIO" }})",
                qualityDetail = "${file.length() / 1024} KB • Validated non-empty encoded audio file. WAV 16–24kHz PCM recommended for Chatterbox cloning.",
                recommendedForCloning = file.length() >= 16_000L,
                cloningBackendCompatible = cloningCompatible,
                cloningCompatibilityNote = cloningNote
            )
        }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = bb.getShort(22).toInt().coerceAtLeast(1)
        val sampleRate = bb.getInt(24).coerceAtLeast(8000)
        val bitsPerSample = bb.getShort(34).toInt().coerceAtLeast(8)
        val pcmByteCount = (bytes.size - 44).coerceAtLeast(0)
        val bytesPerSample = (bitsPerSample / 8).coerceAtLeast(1)
        val totalFrames = pcmByteCount / (channels * bytesPerSample)
        val durationSec = totalFrames.toFloat() / sampleRate.toFloat()

        var sumSq = 0.0
        var clipped = 0L
        var sampleCount = 0L
        if (bitsPerSample == 16) {
            var pos = 44
            while (pos + 1 < bytes.size) {
                val sample = ((bytes[pos + 1].toInt() shl 8) or (bytes[pos].toInt() and 0xFF)).toShort()
                val norm = sample / 32768.0
                sumSq += norm * norm
                if (abs(sample.toInt()) >= 32400) clipped++
                sampleCount++
                pos += 2
            }
        }
        val rms = if (sampleCount > 0) sqrt(sumSq / sampleCount).toFloat() else 0f
        val clipRatio = if (sampleCount > 0) clipped.toFloat() / sampleCount.toFloat() else 0f
        val verdict = when {
            durationSec < 2.5f -> "Too Short (${String.format(Locale.US, "%.1fs", durationSec)})"
            rms < 0.01f -> "Too Quiet / Silent"
            clipRatio > 0.02f -> "Clipping Detected (${(clipRatio * 100).toInt()}%)"
            durationSec in 4.5f..12.0f -> "Optimal Quality (${String.format(Locale.US, "%.1fs", durationSec)})"
            else -> "Usable (${String.format(Locale.US, "%.1fs", durationSec)})"
        }
        val detail = "${sampleRate}Hz • ${channels}ch • ${String.format(Locale.US, "%.1fs", durationSec)} • RMS ${String.format(Locale.US, "%.3f", rms)}"
        return SampleAudioInspection(
            filePath = file.absolutePath,
            fileSizeBytes = file.length(),
            sampleRateHz = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            durationSeconds = durationSec,
            rmsEnergy = rms,
            clippingRatio = clipRatio,
            sha256 = sha,
            isWavPcm = true,
            qualityVerdict = verdict,
            qualityDetail = detail,
            recommendedForCloning = durationSec >= 3.0f && rms >= 0.01f && clipRatio <= 0.05f,
            cloningBackendCompatible = cloningCompatible,
            cloningCompatibilityNote = cloningNote
        )
    }

    /**
     * Records a 5–10 second (or 3–10s configurable) 16kHz Mono PCM audio sample from the microphone,
     * saves it as a `.wav` file in `filesDir/custom_voices/`, and extracts acoustic
     * Fundamental Frequency ($F_0$) and energy cadence.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordAndAnalyzeVoiceSample(
        context: Context,
        durationSeconds: Int = 6,
        onProgressUpdate: (Float, Float) -> Unit
    ): ClonedAudioAnalysisResult = withContext(Dispatchers.IO) {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(sampleRate / 5)

        val clampedSeconds = durationSeconds.coerceIn(3, 10)
        val totalSamplesTarget = sampleRate * clampedSeconds
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
                val totalDurationMs = clampedSeconds * 1000L

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

        var finalFile = destFile
        if (destFile.exists() && destFile.length() > 12L) {
            try {
                val head = ByteArray(12)
                destFile.inputStream().use { it.read(head) }
                if (String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
                ) {
                    val wavDest = File(voiceDir, destFile.nameWithoutExtension + ".wav")
                    if (destFile.renameTo(wavDest)) {
                        finalFile = wavDest
                    }
                }
            } catch (_: Exception) {
            }
        }

        ClonedAudioAnalysisResult(
            savedAudioPath = if (finalFile.exists() && finalFile.length() > 0) finalFile.absolutePath else null,
            estimatedFundamentalHz = estimatedHz,
            clonedPitch = clonedPitch,
            clonedSpeechRate = clonedRate,
            acousticSummary = "Imported Audio (${estimatedHz.toInt()} Hz • Pitch ${String.format(Locale.US, "%.2fx", clonedPitch)})"
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

    fun findExistingAssignment(
        rawOrNormalizedName: String,
        gameNamespace: String = activeGameNamespace
    ): CharacterVoiceAssignment? {
        val norm = CharacterVoiceAssignment.normalizeName(rawOrNormalizedName)
        if (norm.isBlank()) return null
        val currentList = _characterAssignments.value
        // 1. Exact match in requested namespace or default namespace
        val exact = currentList.find { it.characterName == norm && it.gameNamespace == gameNamespace }
            ?: currentList.find { it.characterName == norm }
        if (exact != null) return exact

        // 2. Whole-word token match for multi-word tags like "Sister Jenny" or "Commander Sarah"
        val tokens = norm.split(" ").filter { it.length >= 2 }
        if (tokens.size > 1) {
            val tokenMatch = currentList.find { candidate ->
                tokens.any { token -> token == candidate.characterName }
            }
            if (tokenMatch != null) return tokenMatch
        }
        return null
    }

    fun getUnknownSpeakerAssignment(
        gameNamespace: String = activeGameNamespace
    ): CharacterVoiceAssignment {
        val cleanNs = gameNamespace.trim().lowercase(Locale.ROOT).ifBlank { activeGameNamespace }
        val userConfiguredUnknown = _characterAssignments.value.find {
            it.characterName == CharacterVoiceAssignment.UNKNOWN_SPEAKER_NAME &&
                (it.gameNamespace == cleanNs || it.gameNamespace == "default_game")
        }
        return userConfiguredUnknown ?: CharacterVoiceAssignment.UNKNOWN_SPEAKER_PRESET.copy(
            gameNamespace = cleanNs
        )
    }

    fun setUnlabeledSubtitleManualOverride(
        context: Context?,
        characterName: String?,
        gameNamespace: String = activeGameNamespace
    ) {
        val cleanNs = gameNamespace.trim().lowercase(Locale.ROOT).ifBlank { activeGameNamespace }
        val normChar = characterName?.let { CharacterVoiceAssignment.normalizeName(it) }?.takeIf {
            it.isNotBlank() && it != CharacterVoiceAssignment.UNKNOWN_SPEAKER_NAME
        }
        if (normChar == null) {
            unlabeledOverridesByGame.remove(cleanNs)
        } else {
            unlabeledOverridesByGame[cleanNs] = normChar
        }
        if (cleanNs == activeGameNamespace) {
            _unlabeledOverrideCharName.value = normChar
        }
        val targetCtx = context ?: appContext
        if (targetCtx != null) {
            targetCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("${KEY_UNLABELED_OVERRIDE_CHAR}_$cleanNs", normChar ?: "")
                .apply()
        }
    }

    fun getUnlabeledSubtitleManualOverride(
        gameNamespace: String = activeGameNamespace
    ): CharacterVoiceAssignment? {
        val cleanNs = gameNamespace.trim().lowercase(Locale.ROOT).ifBlank { activeGameNamespace }
        val overrideChar = unlabeledOverridesByGame[cleanNs]?.takeIf { it.isNotBlank() } ?: return null
        return findExistingAssignment(overrideChar, cleanNs)
            ?: getOrCreateAssignmentForCharacter(overrideChar, overrideChar, cleanNs, appContext)
    }

    fun getOrCreateAssignmentForCharacter(
        rawName: String,
        normalizedKey: String = CharacterVoiceAssignment.normalizeName(rawName),
        gameNamespace: String = activeGameNamespace,
        context: Context? = null
    ): CharacterVoiceAssignment {
        val norm = normalizedKey.ifBlank { CharacterVoiceAssignment.normalizeName(rawName) }
        if (norm.isBlank() || norm == CharacterVoiceAssignment.UNKNOWN_SPEAKER_NAME) {
            return getUnknownSpeakerAssignment(gameNamespace)
        }

        val existing = findExistingAssignment(norm, gameNamespace)
        if (existing != null) {
            return existing
        }

        val currentList = _characterAssignments.value
        val speakerCount = availableSpeakerCount.coerceAtLeast(1)
        val inferredGender = SpeakerHeuristicEngine.detectCharacterGender(rawName)
        val assignedSid = if (speakerCount >= 10) {
            if (inferredGender == SpeakerGender.FEMALE_18) {
                Math.floorMod(norm.hashCode(), 5)
            } else {
                5 + Math.floorMod(norm.hashCode(), 5)
            }
        } else {
            Math.floorMod(norm.hashCode(), speakerCount)
        }

        val cleanDisplay = rawName.trim()
            .trim('[', ']', '<', '>', '(', ')', '{', '}', ':', '：', '-', '–', '—', ' ', '【', '】', '《', '》', '「', '」', '`', '"', '\'')
            .replace(Regex("\\s+"), " ")
            .ifBlank { "Character ${currentList.size + 1}" }

        val isFem = assignedSid in 0..4
        val genderTag = if (isFem) "F${assignedSid + 1} (Female)" else "M${assignedSid - 4} (Male)"
        val newAssignment = CharacterVoiceAssignment(
            characterName = norm,
            displayName = cleanDisplay,
            gameNamespace = gameNamespace,
            speakerId = assignedSid,
            speed = 1.0f,
            pitch = 1.0f,
            isUserOverride = false,
            characterRoleNote = "Dynamically Discovered • $genderTag (sid: $assignedSid)",
            referenceVoiceId = if (isFem) CharacterVoiceAssignment.DEFAULT_FEMALE_REF_ID else CharacterVoiceAssignment.DEFAULT_MALE_REF_ID,
            referenceAudioSha256 = if (isFem) CharacterVoiceAssignment.JENNY_FEMALE_REF_SHA256 else CharacterVoiceAssignment.ANON_MALE_REF_SHA256,
            voicePresentation = if (isFem) "female" else "male"
        )

        val updated = currentList + newAssignment
        _characterAssignments.value = updated
        val targetContext = context ?: appContext
        if (targetContext != null) {
            saveCharacterAssignmentsToDisk(targetContext, updated)
        }
        return newAssignment
    }

    fun getAssignmentForCharacter(rawName: String, context: Context? = null): CharacterVoiceAssignment {
        val (correctedNorm, _) = com.example.engine.CharacterVoiceRouter.normalizeAndCorrectSpeakerName(rawName)
        return getOrCreateAssignmentForCharacter(
            rawName = rawName,
            normalizedKey = correctedNorm,
            gameNamespace = activeGameNamespace,
            context = context
        )
    }

    fun buildVoiceProfileForAssignment(assignment: CharacterVoiceAssignment): VoiceProfile {
        val role = when {
            assignment.isUnknownOrNeutral -> SpeakerRole.NARRATOR
            assignment.characterName == "jenny" || assignment.isFemale -> SpeakerRole.CHARACTER_B
            assignment.characterName in setOf("kaal", "ragnar", "shadow") -> SpeakerRole.CHARACTER_C
            assignment.characterName in setOf("ira", "oracle", "system", "narrator") -> SpeakerRole.NARRATOR
            else -> SpeakerRole.CHARACTER_A
        }
        val resolvedGender = when {
            assignment.isUnknownOrNeutral -> SpeakerGender.UNKNOWN
            assignment.isFemale -> SpeakerGender.FEMALE_18
            else -> SpeakerGender.MALE_18
        }
        val basePreset = getVoiceForRole(role)
        return basePreset.copy(
            id = "char_profile_${assignment.characterName}_sid${assignment.speakerId}",
            name = "${assignment.displayName} (Sid #${assignment.speakerId})",
            engineVoiceIndex = assignment.speakerId,
            pitch = assignment.pitch,
            speechRate = assignment.speed,
            assignedRole = role,
            gender = resolvedGender
        )
    }

    fun recordActiveRouteDecision(decision: com.example.engine.VoiceRouteDecision) {
        _activeCharacterAssignment.value = decision.characterAssignment
        _lastAssignedSpeakerId.value = decision.selectedSpeakerId
        _lastDetectedSpeaker.value = decision.characterAssignment.displayName
        _lastAssignedVoiceName.value = "Supertonic Sid #${decision.selectedSpeakerId} (${decision.characterAssignment.displayName})"
        _lastHeuristicRule.value = "${decision.resolutionRule} → ${decision.characterAssignment.displayName} (SID #${decision.selectedSpeakerId})"
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
                voiceEngine = voiceEngine?.trim()?.ifBlank { null } ?: "follow_global",
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
     * Explicitly distinguishes the 24 saved character profiles from the 2 bundled acoustic reference WAVs.
     */
    fun getUniqueReferenceVoiceSummary(): Triple<Int, Int, String> {
        val list = _characterAssignments.value
        val uniqueCount = CharacterVoiceAssignment.countUniqueReferenceVoices(list)
        val totalCount = list.size
        val summary = "$totalCount saved character profiles • $uniqueCount unique reference voice clips " +
            "(2 bundled acoustic WAV identities: anon_male_ref.wav & jenny_female_ref.wav; import consented/licensed .wav samples for additional distinct voices)"
        return Triple(uniqueCount, totalCount, summary)
    }

    /**
     * Imports a user-provided consented or licensed `.wav` reference voice file for a specific
     * character, verifies non-empty audio content, computes its genuine SHA-256 digest, and updates
     * the character's acoustic identity so it no longer shares one of the 2 default reference WAVs.
     */
    fun importConsentedReferenceVoiceFile(
        context: Context,
        characterName: String,
        sourceWavFile: File,
        speakerConsentConfirmed: Boolean
    ): Result<CharacterVoiceAssignment> {
        if (!speakerConsentConfirmed) {
            return Result.failure(IllegalArgumentException("Speaker consent or license confirmation is required before importing a reference voice."))
        }
        if (!sourceWavFile.exists() || sourceWavFile.length() <= 100L) {
            return Result.failure(IllegalArgumentException("Reference WAV file is missing or too small (${sourceWavFile.length()} bytes)."))
        }
        val norm = CharacterVoiceAssignment.normalizeName(characterName)
        if (norm.isBlank()) {
            return Result.failure(IllegalArgumentException("Invalid character name."))
        }
        val customDir = File(context.filesDir, "custom_voices").apply { mkdirs() }
        val customVoiceId = "consented_${norm}_${System.currentTimeMillis()}"
        val destFile = File(customDir, "$customVoiceId.wav")
        return try {
            sourceWavFile.inputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            val sha256 = com.example.engine.CharacterVoiceRouter.computeFileSha256(destFile)
            if (sha256.isBlank()) {
                return Result.failure(IllegalStateException("Could not compute SHA-256 for imported WAV."))
            }
            val existing = findExistingAssignment(norm, activeGameNamespace)
                ?: getOrCreateAssignmentForCharacter(characterName, norm, activeGameNamespace, context)
            val updated = existing.copy(
                referenceVoiceId = "$customVoiceId.wav",
                referenceAudioSha256 = sha256,
                isUserOverride = true,
                lastDetectedTimestamp = System.currentTimeMillis()
            )
            Result.success(updateCharacterVoiceProfile(context, updated))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importConsentedReferenceVoiceUri(
        context: Context,
        characterName: String,
        uri: Uri,
        speakerConsentConfirmed: Boolean
    ): Result<CharacterVoiceAssignment> = withContext(Dispatchers.IO) {
        if (!speakerConsentConfirmed) {
            return@withContext Result.failure(IllegalArgumentException("Speaker consent or license confirmation is required."))
        }
        val tempFile = File(context.cacheDir, "import_ref_${System.currentTimeMillis()}.wav")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            importConsentedReferenceVoiceFile(context, characterName, tempFile, speakerConsentConfirmed = true)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                tempFile.delete()
            } catch (_: Exception) {
            }
        }
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

        // Register OCR typo alias so future scans of normOld automatically route to normNew
        com.example.engine.CharacterVoiceRouter.registerOcrCharacterAlias(normOld, normNew)

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
        com.example.engine.CharacterVoiceRouter.resetContinuationContext()
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
     * Multi-Speaker Dialogue Analyzer routed through [com.example.engine.CharacterVoiceRouter]:
     * Extracts speaker labels BEFORE stripping them from [rawSubtitleText], resolves the persistent
     * [CharacterVoiceAssignment] (or continuation context when unlabeled), and returns [ParsedDialogueLine]
     * carrying the immutable [com.example.engine.VoiceRouteDecision].
     */
    fun resolveSpeakerAndVoiceForSubtitle(
        rawSubtitleText: String,
        isFromMediaProjectionOcr: Boolean = false
    ): ParsedDialogueLine {
        val decision = com.example.engine.CharacterVoiceRouter.routeLiveSubtitle(
            context = appContext,
            rawOcrSubtitle = rawSubtitleText,
            translatedHindi = "",
            gameNamespace = activeGameNamespace,
            isFromRealMediaProjectionOcr = isFromMediaProjectionOcr
        )
        val characterAssignment = decision.characterAssignment
        val effectiveVoice = decision.voiceProfile

        val parsedLine = ParsedDialogueLine(
            speakerName = characterAssignment.displayName,
            spokenDialogue = decision.strippedEnglishDialogue,
            resolvedRole = effectiveVoice.assignedRole,
            detectedGender = effectiveVoice.gender,
            assignedVoice = effectiveVoice,
            matchedHeuristicRule = "${decision.resolutionRule} (${characterAssignment.displayName} → SID #${decision.selectedSpeakerId})",
            characterAssignment = characterAssignment,
            routeDecision = decision
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
                put("voiceCategory", v.voiceCategory.code)
                put("sourceModelInfo", v.sourceModelInfo)
                put("languageCode", v.languageCode)
                put("styleTag", v.styleTag)
                put("isSystemProtected", v.isSystemProtected)
                put("isNeuralCloned", v.isNeuralCloned)
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
                val parsedGender = SpeakerGender.fromCode(obj.optString("gender", defaultGenderCode))
                val isCustom = obj.optBoolean("isCustomCloned", false)
                val sid = obj.optInt("engineVoiceIndex", i % 10).coerceIn(0, 9)
                val defaultCategory = when {
                    isCustom -> VoiceCategory.IMPORTED
                    parsedGender == SpeakerGender.FEMALE_18 -> VoiceCategory.DEFAULT_FEMALE
                    else -> VoiceCategory.DEFAULT_MALE
                }
                list.add(
                    VoiceProfile(
                        id = obj.optString("id", "voice_$i"),
                        name = obj.optString("name", "Voice ${i + 1}"),
                        description = obj.optString("description", "Custom TTS Voice Profile"),
                        pitch = pitch,
                        speechRate = obj.optDouble("speechRate", 0.95).toFloat().coerceIn(0.4f, 1.8f),
                        engineVoiceIndex = sid,
                        assignedRole = role,
                        gender = parsedGender,
                        isCustomCloned = isCustom,
                        sampleAudioPath = obj.optString("sampleAudioPath", "").takeIf { it.isNotBlank() },
                        voiceCategory = obj.optString("voiceCategory", "").takeIf { it.isNotBlank() }
                            ?.let { VoiceCategory.fromCode(it) } ?: defaultCategory,
                        sourceModelInfo = obj.optString("sourceModelInfo", "").ifBlank {
                            if (isCustom) "User Custom Audio Sample" else "Supertonic 3 Multilingual (SID #$sid)"
                        },
                        languageCode = obj.optString("languageCode", "hi").ifBlank { "hi" },
                        styleTag = obj.optString("styleTag", "Neutral").ifBlank { "Neutral" },
                        isSystemProtected = obj.optBoolean("isSystemProtected", !isCustom),
                        isNeuralCloned = obj.optBoolean("isNeuralCloned", false)
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
