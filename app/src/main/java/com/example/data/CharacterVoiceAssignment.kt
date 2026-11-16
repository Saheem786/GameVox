package com.example.data

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent per-character voice assignment for GameVox's multi-speaker dialogue engine.
 *
 * Maps a recognized game character to an in-process neural backend, model, and speaker ID (sid).
 *
 * Audio Engineering Note:
 * Supertonic 3 synthesizes speech via GenerationConfig(sid, speed, extra=mapOf("lang" to "hi"), numSteps).
 * The underlying ONNX model does NOT take a pitch offset input parameter; the 'pitch' field is stored
 * solely for the Android system TTS fallback (e.g. Google TTS).
 * Base speaker IDs (0..9) are acoustic numerical timbres and are NOT labeled male/female without
 * verified physical listening tests.
 */
data class CharacterVoiceAssignment(
    val characterName: String,
    val displayName: String,
    val gameNamespace: String = "default_game",
    val backendId: String = "chatterbox_local_server",
    val modelId: String = "ResembleAI/Chatterbox-Multilingual-hi",
    val speakerId: Int = 0,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val isUserOverride: Boolean = false,
    val lastDetectedTimestamp: Long = System.currentTimeMillis(),
    val characterRoleNote: String = "Game Character",
    val referenceVoiceId: String = if (speakerId in 0..4) DEFAULT_FEMALE_REF_ID else DEFAULT_MALE_REF_ID,
    val referenceAudioSha256: String = if (referenceVoiceId == DEFAULT_FEMALE_REF_ID) JENNY_FEMALE_REF_SHA256 else ANON_MALE_REF_SHA256,
    val voicePresentation: String = if (speakerId in 0..4) "female" else "male",
    val vocalStyleDescription: String = characterRoleNote,
    val voiceEngine: String = "chatterbox_local_server",
    val pronunciationOverrides: Map<String, String> = emptyMap(),
    val defaultEmotion: String = "neutral",
    val exaggeration: Float = 0.50f,
    val cfgWeight: Float = 0.40f,
    val speakerConsentConfirmed: Boolean = true
) {
    init {
        require(speakerId in 0..9) { "Supertonic 3 speakerId must be within 0..9, got: $speakerId" }
    }

    companion object {
        const val DEFAULT_MALE_REF_ID = "anon_male_ref"
        const val DEFAULT_FEMALE_REF_ID = "jenny_female_ref"
        const val ANON_MALE_REF_SHA256 = "67f576d05c67766b20a52acf2f33c729e34a43747862d4f1a45903f2bae7836f"
        const val JENNY_FEMALE_REF_SHA256 = "59a1ebb53f0641f6ddf39637922abc7a27975635a56321ef28a6fb3941204982"

        fun normalizeName(raw: String): String {
            return raw
                .trim()
                .trim('[', ']', '<', '>', '(', ')', '{', '}', ':', '：', '-', '–', '—', ' ', '【', '】', '《', '》', '「', '」', '`', '"', '\'')
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
        }

        /**
         * Returns the true count of acoustically distinct reference voice clips (by SHA-256 hash)
         * across the given character profile list. Never inflates unique voice count by counting
         * profiles that share the same reference audio file.
         */
        fun countUniqueReferenceVoices(profiles: List<CharacterVoiceAssignment>): Int {
            return profiles
                .map { it.referenceAudioSha256.trim().lowercase(Locale.ROOT).ifEmpty { it.referenceVoiceId } }
                .filter { it.isNotEmpty() }
                .distinct()
                .size
        }

        /**
         * Returns an honest disclosure string indicating whether this profile has a dedicated
         * reference voice clip or shares its reference audio with other character profiles.
         */
        fun buildVoiceSharingDisclosure(
            profile: CharacterVoiceAssignment,
            allProfiles: List<CharacterVoiceAssignment>
        ): String {
            val matchingCount = allProfiles.count {
                it.referenceAudioSha256.equals(profile.referenceAudioSha256, ignoreCase = true) &&
                    profile.referenceAudioSha256.isNotBlank()
            }.coerceAtLeast(1)
            return if (matchingCount > 1) {
                "Shared Reference Voice (${profile.referenceVoiceId}.wav • shared by $matchingCount profiles; import a consenting .wav clip for a dedicated voice)"
            } else {
                "Dedicated Unique Reference Voice (${profile.referenceVoiceId}.wav • SHA-256 ${profile.referenceAudioSha256.take(8)}…)"
            }
        }

        /**
         * 24 pre-seeded Game Character Profiles with:
         * - Real distinct Male (`anon_male_ref.wav`) and Female (`jenny_female_ref.wav`) reference voice clips
         *   for ResembleAI Chatterbox Multilingual V3 Hindi zero-shot voice conditioning.
         * - Audited Supertonic 3 offline fallback speaker IDs (`sid 0..4` = Female F1..F5, `sid 5..9` = Male M1..M5).
         * - Calibrated Chatterbox `exaggeration` (0.35..0.85) and `cfgWeight` (0.28..0.55) per character archetype.
         */
        val DEFAULT_PRESETS = listOf(
            CharacterVoiceAssignment(
                characterName = "anon",
                displayName = "Anon (Lead Character)",
                speakerId = 5,
                speed = 1.00f,
                pitch = 0.95f,
                characterRoleNote = "Young Male Lead • 20yo Heroic Conversational Hindi • M1 (Supertonic sid: 5)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "20yo Young Male Lead • Natural modern conversational Hindi",
                pronunciationOverrides = mapOf("Anon" to "अनोन", "HP" to "हेल्थ"),
                defaultEmotion = "neutral",
                exaggeration = 0.50f,
                cfgWeight = 0.40f
            ),
            CharacterVoiceAssignment(
                characterName = "jenny",
                displayName = "Jenny (Companion)",
                speakerId = 1,
                speed = 1.02f,
                pitch = 1.15f,
                characterRoleNote = "Bright Female Companion • 19yo Expressive Hindi • F2 (Supertonic sid: 1)",
                referenceVoiceId = DEFAULT_FEMALE_REF_ID,
                referenceAudioSha256 = JENNY_FEMALE_REF_SHA256,
                voicePresentation = "female",
                vocalStyleDescription = "19yo Bright Female Lead • Warm, expressive Hindi delivery",
                pronunciationOverrides = mapOf("Jenny" to "जेनी", "Anon" to "अनोन"),
                defaultEmotion = "happy",
                exaggeration = 0.58f,
                cfgWeight = 0.36f
            ),
            CharacterVoiceAssignment(
                characterName = "kaal",
                displayName = "Kaal (Boss / Villain)",
                speakerId = 7,
                speed = 0.88f,
                pitch = 0.80f,
                characterRoleNote = "Deep Menacing Antagonist • 38yo Commanding Warlord • M3 (Supertonic sid: 7)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "38yo Deep Menacing Antagonist • Intense dramatic emphasis",
                pronunciationOverrides = mapOf("Kaal" to "काल"),
                defaultEmotion = "angry",
                exaggeration = 0.78f,
                cfgWeight = 0.30f
            ),
            CharacterVoiceAssignment(
                characterName = "ira",
                displayName = "Ira (AI Narrator)",
                speakerId = 2,
                speed = 1.00f,
                pitch = 1.10f,
                characterRoleNote = "Composed Terminal AI • Calm Tactical Narrator • F3 (Supertonic sid: 2)",
                referenceVoiceId = DEFAULT_FEMALE_REF_ID,
                referenceAudioSha256 = JENNY_FEMALE_REF_SHA256,
                voicePresentation = "female",
                vocalStyleDescription = "25yo Composed AI Narrator • Crisp, steady Hindi cadence",
                pronunciationOverrides = mapOf("Ira" to "इरा", "AI" to "एआई"),
                defaultEmotion = "serious",
                exaggeration = 0.38f,
                cfgWeight = 0.52f
            ),
            CharacterVoiceAssignment(
                characterName = "alex",
                displayName = "Alex (Agile Scout)",
                speakerId = 6,
                speed = 1.08f,
                pitch = 1.00f,
                characterRoleNote = "Energetic Vanguard Scout • M2 (Supertonic sid: 6)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "19yo Fast Vanguard Scout • High-energy excited delivery",
                defaultEmotion = "excited",
                exaggeration = 0.72f,
                cfgWeight = 0.32f
            ),
            CharacterVoiceAssignment(
                characterName = "sarah",
                displayName = "Sarah (Commander)",
                speakerId = 3,
                speed = 0.96f,
                pitch = 1.05f,
                characterRoleNote = "Tactical Commander • F4 (Supertonic sid: 3)",
                referenceVoiceId = DEFAULT_FEMALE_REF_ID,
                referenceAudioSha256 = JENNY_FEMALE_REF_SHA256,
                voicePresentation = "female",
                vocalStyleDescription = "32yo Tactical Commander • Authoritative briefing tone",
                defaultEmotion = "serious",
                exaggeration = 0.45f,
                cfgWeight = 0.48f
            ),
            CharacterVoiceAssignment(
                characterName = "vikram",
                displayName = "Vikram (Veteran Warrior)",
                speakerId = 8,
                speed = 0.90f,
                pitch = 0.85f,
                characterRoleNote = "Seasoned Battle Veteran • M4 (Supertonic sid: 8)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "42yo Seasoned Warrior • Grounded, steady Hindi voice",
                defaultEmotion = "serious",
                exaggeration = 0.48f,
                cfgWeight = 0.45f
            ),
            CharacterVoiceAssignment(
                characterName = "priya",
                displayName = "Priya (Princess)",
                speakerId = 0,
                speed = 0.98f,
                pitch = 1.18f,
                characterRoleNote = "Gentle Royal Princess • F1 (Supertonic sid: 0)",
                referenceVoiceId = DEFAULT_FEMALE_REF_ID,
                referenceAudioSha256 = JENNY_FEMALE_REF_SHA256,
                voicePresentation = "female",
                vocalStyleDescription = "21yo Gentle Royal • Soft, empathetic conversational Hindi",
                defaultEmotion = "happy",
                exaggeration = 0.55f,
                cfgWeight = 0.42f
            ),
            CharacterVoiceAssignment(
                characterName = "guard",
                displayName = "Cybernetic Guard",
                speakerId = 9,
                speed = 0.92f,
                pitch = 0.82f,
                characterRoleNote = "Armored Station Sentinel • M5 (Supertonic sid: 9)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "30yo Armored Sentinel • Urgent security patrol tone",
                defaultEmotion = "angry",
                exaggeration = 0.68f,
                cfgWeight = 0.35f
            ),
            CharacterVoiceAssignment(
                characterName = "oracle",
                displayName = "Mystic Oracle",
                speakerId = 4,
                speed = 0.90f,
                pitch = 1.12f,
                characterRoleNote = "Ethereal Arcane Mystic • F5 (Supertonic sid: 4)",
                referenceVoiceId = DEFAULT_FEMALE_REF_ID,
                referenceAudioSha256 = JENNY_FEMALE_REF_SHA256,
                voicePresentation = "female",
                vocalStyleDescription = "Ancient Mystic Seer • Measured, mysterious delivery",
                defaultEmotion = "whisper",
                exaggeration = 0.36f,
                cfgWeight = 0.55f
            ),
            CharacterVoiceAssignment(
                characterName = "aarav",
                displayName = "Aarav (Rookie Recruit)",
                speakerId = 5,
                speed = 1.05f,
                pitch = 1.02f,
                characterRoleNote = "Enthusiastic Junior Operative • M1 (Supertonic sid: 5)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "18yo Rookie Recruit • Eager, upbeat Hindi delivery",
                defaultEmotion = "excited",
                exaggeration = 0.66f,
                cfgWeight = 0.35f
            ),
            CharacterVoiceAssignment(
                characterName = "ananya",
                displayName = "Ananya (Field Medic)",
                speakerId = 1,
                speed = 1.00f,
                pitch = 1.10f,
                characterRoleNote = "Empathetic Medical Officer • F2 (Supertonic sid: 1)",
                referenceVoiceId = DEFAULT_FEMALE_REF_ID,
                referenceAudioSha256 = JENNY_FEMALE_REF_SHA256,
                voicePresentation = "female",
                vocalStyleDescription = "24yo Field Medic • Caring, reassuring Hindi voice",
                defaultEmotion = "neutral",
                exaggeration = 0.52f,
                cfgWeight = 0.42f
            ),
            CharacterVoiceAssignment(
                characterName = "rohan",
                displayName = "Rohan (Sharpshooter)",
                speakerId = 6,
                speed = 0.94f,
                pitch = 0.92f,
                characterRoleNote = "Focused Precision Marksman • M2 (Supertonic sid: 6)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "26yo Precision Marksman • Calm, low-key delivery",
                defaultEmotion = "serious",
                exaggeration = 0.42f,
                cfgWeight = 0.50f
            ),
            CharacterVoiceAssignment(
                characterName = "kavya",
                displayName = "Kavya (Shadow Assassin)",
                speakerId = 3,
                speed = 1.04f,
                pitch = 1.14f,
                characterRoleNote = "Whispering Stealth Operative • F4 (Supertonic sid: 3)",
                referenceVoiceId = DEFAULT_FEMALE_REF_ID,
                referenceAudioSha256 = JENNY_FEMALE_REF_SHA256,
                voicePresentation = "female",
                vocalStyleDescription = "23yo Stealth Operative • Quiet, sharp Hindi cadence",
                defaultEmotion = "whisper",
                exaggeration = 0.34f,
                cfgWeight = 0.54f
            ),
            CharacterVoiceAssignment(
                characterName = "kabir",
                displayName = "Kabir (Elder Sage)",
                speakerId = 8,
                speed = 0.86f,
                pitch = 0.84f,
                characterRoleNote = "Wise Ancient Mentor • M4 (Supertonic sid: 8)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "60yo Wise Mentor • Deliberate, reflective Hindi tone",
                defaultEmotion = "serious",
                exaggeration = 0.40f,
                cfgWeight = 0.52f
            ),
            CharacterVoiceAssignment(
                characterName = "pooja",
                displayName = "Pooja (Temple Priestess)",
                speakerId = 4,
                speed = 0.92f,
                pitch = 1.16f,
                characterRoleNote = "Serene Shrine Guardian • F5 (Supertonic sid: 4)",
                referenceVoiceId = DEFAULT_FEMALE_REF_ID,
                referenceAudioSha256 = JENNY_FEMALE_REF_SHA256,
                voicePresentation = "female",
                vocalStyleDescription = "27yo Shrine Guardian • Serene, melodic Hindi voice",
                defaultEmotion = "neutral",
                exaggeration = 0.46f,
                cfgWeight = 0.46f
            ),
            CharacterVoiceAssignment(
                characterName = "dante",
                displayName = "Dante (Heavy Gunner)",
                speakerId = 9,
                speed = 0.98f,
                pitch = 0.88f,
                characterRoleNote = "Boisterous Arsenal Specialist • M5 (Supertonic sid: 9)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "34yo Heavy Gunner • Boisterous, high-intensity voice",
                defaultEmotion = "excited",
                exaggeration = 0.75f,
                cfgWeight = 0.32f
            ),
            CharacterVoiceAssignment(
                characterName = "elena",
                displayName = "Elena (Merchant)",
                speakerId = 2,
                speed = 1.06f,
                pitch = 1.08f,
                characterRoleNote = "Shrewd Resource Quartermaster • F3 (Supertonic sid: 2)",
                referenceVoiceId = DEFAULT_FEMALE_REF_ID,
                referenceAudioSha256 = JENNY_FEMALE_REF_SHA256,
                voicePresentation = "female",
                vocalStyleDescription = "28yo Outpost Merchant • Lively, persuasive Hindi tone",
                defaultEmotion = "happy",
                exaggeration = 0.62f,
                cfgWeight = 0.36f
            ),
            CharacterVoiceAssignment(
                characterName = "ragnar",
                displayName = "Ragnar (Warlord)",
                speakerId = 7,
                speed = 0.92f,
                pitch = 0.78f,
                characterRoleNote = "Fierce Frontline Chieftain • M3 (Supertonic sid: 7)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "40yo Frontline Chieftain • Fierce battle-ready delivery",
                defaultEmotion = "angry",
                exaggeration = 0.80f,
                cfgWeight = 0.28f
            ),
            CharacterVoiceAssignment(
                characterName = "zelda",
                displayName = "Zelda (Sorceress)",
                speakerId = 0,
                speed = 0.96f,
                pitch = 1.20f,
                characterRoleNote = "Noble Sorceress • F1 (Supertonic sid: 0)",
                referenceVoiceId = DEFAULT_FEMALE_REF_ID,
                referenceAudioSha256 = JENNY_FEMALE_REF_SHA256,
                voicePresentation = "female",
                vocalStyleDescription = "22yo Noble Sorceress • Poised, expressive Hindi voice",
                defaultEmotion = "serious",
                exaggeration = 0.54f,
                cfgWeight = 0.42f
            ),
            CharacterVoiceAssignment(
                characterName = "leon",
                displayName = "Leon (Inspector)",
                speakerId = 5,
                speed = 0.96f,
                pitch = 0.94f,
                characterRoleNote = "Methodical Lead Investigator • M1 (Supertonic sid: 5)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "29yo Lead Investigator • Analytical, composed Hindi",
                defaultEmotion = "serious",
                exaggeration = 0.44f,
                cfgWeight = 0.48f
            ),
            CharacterVoiceAssignment(
                characterName = "maya",
                displayName = "Maya (Resistance Leader)",
                speakerId = 3,
                speed = 1.06f,
                pitch = 1.06f,
                characterRoleNote = "Passionate Underground Rebel • F4 (Supertonic sid: 3)",
                referenceVoiceId = DEFAULT_FEMALE_REF_ID,
                referenceAudioSha256 = JENNY_FEMALE_REF_SHA256,
                voicePresentation = "female",
                vocalStyleDescription = "26yo Resistance Leader • Passionate, urgent Hindi voice",
                defaultEmotion = "excited",
                exaggeration = 0.70f,
                cfgWeight = 0.34f
            ),
            CharacterVoiceAssignment(
                characterName = "shadow",
                displayName = "Shadow (Stranger)",
                speakerId = 7,
                speed = 0.90f,
                pitch = 0.85f,
                characterRoleNote = "Mysterious Cloaked Figure • M3 (Supertonic sid: 7)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "neutral",
                vocalStyleDescription = "Enigmatic Stranger • Hushed, understated delivery",
                defaultEmotion = "whisper",
                exaggeration = 0.35f,
                cfgWeight = 0.55f
            ),
            CharacterVoiceAssignment(
                characterName = "announcer",
                displayName = "Arena Announcer",
                speakerId = 9,
                speed = 1.02f,
                pitch = 0.95f,
                characterRoleNote = "Dynamic Tournament Broadcaster • M5 (Supertonic sid: 9)",
                referenceVoiceId = DEFAULT_MALE_REF_ID,
                referenceAudioSha256 = ANON_MALE_REF_SHA256,
                voicePresentation = "male",
                vocalStyleDescription = "35yo Arena Broadcaster • High-energy commentary",
                defaultEmotion = "excited",
                exaggeration = 0.82f,
                cfgWeight = 0.28f
            )
        )

        fun serializeList(list: List<CharacterVoiceAssignment>): String {
            val arr = JSONArray()
            for (item in list) {
                val overridesJson = JSONObject()
                for ((k, v) in item.pronunciationOverrides) {
                    overridesJson.put(k, v)
                }
                val obj = JSONObject().apply {
                    put("characterName", item.characterName)
                    put("displayName", item.displayName)
                    put("gameNamespace", item.gameNamespace)
                    put("backendId", item.backendId)
                    put("modelId", item.modelId)
                    put("speakerId", item.speakerId)
                    put("speed", item.speed.toDouble())
                    put("pitch", item.pitch.toDouble())
                    put("isUserOverride", item.isUserOverride)
                    put("lastDetectedTimestamp", item.lastDetectedTimestamp)
                    put("characterRoleNote", item.characterRoleNote)
                    put("referenceVoiceId", item.referenceVoiceId)
                    put("referenceAudioSha256", item.referenceAudioSha256)
                    put("voicePresentation", item.voicePresentation)
                    put("vocalStyleDescription", item.vocalStyleDescription)
                    put("voiceEngine", item.voiceEngine)
                    put("pronunciationOverrides", overridesJson)
                    put("defaultEmotion", item.defaultEmotion)
                    put("exaggeration", item.exaggeration.toDouble())
                    put("cfgWeight", item.cfgWeight.toDouble())
                    put("speakerConsentConfirmed", item.speakerConsentConfirmed)
                }
                arr.put(obj)
            }
            return arr.toString()
        }

        fun parseList(jsonString: String?): List<CharacterVoiceAssignment> {
            if (jsonString.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonString)
                val result = mutableListOf<CharacterVoiceAssignment>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val rawSid = obj.optInt("speakerId", 0).coerceIn(0, 9)
                    val defaultRefId = if (rawSid in 0..4) DEFAULT_FEMALE_REF_ID else DEFAULT_MALE_REF_ID
                    val refId = obj.optString("referenceVoiceId", defaultRefId).ifBlank { defaultRefId }
                    val defaultSha = if (refId == DEFAULT_FEMALE_REF_ID) JENNY_FEMALE_REF_SHA256 else ANON_MALE_REF_SHA256
                    val defaultPres = if (rawSid in 0..4) "female" else "male"
                    val roleNote = obj.optString("characterRoleNote", "Game Character")
                    val overridesMap = mutableMapOf<String, String>()
                    val overridesObj = obj.optJSONObject("pronunciationOverrides")
                    if (overridesObj != null) {
                        val keys = overridesObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = overridesObj.optString(k, "")
                            if (k.isNotBlank() && v.isNotBlank()) {
                                overridesMap[k] = v
                            }
                        }
                    }
                    result.add(
                        CharacterVoiceAssignment(
                            characterName = obj.optString("characterName", ""),
                            displayName = obj.optString("displayName", ""),
                            gameNamespace = obj.optString("gameNamespace", "default_game").ifBlank { "default_game" },
                            backendId = obj.optString("backendId", "chatterbox_local_server"),
                            modelId = obj.optString("modelId", "ResembleAI/Chatterbox-Multilingual-hi"),
                            speakerId = rawSid,
                            speed = obj.optDouble("speed", 1.0).toFloat().coerceIn(0.6f, 1.5f),
                            pitch = obj.optDouble("pitch", 1.0).toFloat().coerceIn(0.6f, 1.5f),
                            isUserOverride = obj.optBoolean("isUserOverride", false),
                            lastDetectedTimestamp = obj.optLong("lastDetectedTimestamp", System.currentTimeMillis()),
                            characterRoleNote = roleNote,
                            referenceVoiceId = refId,
                            referenceAudioSha256 = obj.optString("referenceAudioSha256", defaultSha).ifBlank { defaultSha },
                            voicePresentation = obj.optString("voicePresentation", defaultPres).ifBlank { defaultPres },
                            vocalStyleDescription = obj.optString("vocalStyleDescription", roleNote).ifBlank { roleNote },
                            voiceEngine = obj.optString("voiceEngine", "chatterbox_local_server").ifBlank { "chatterbox_local_server" },
                            pronunciationOverrides = overridesMap,
                            defaultEmotion = obj.optString("defaultEmotion", "neutral").ifBlank { "neutral" },
                            exaggeration = obj.optDouble("exaggeration", 0.50).toFloat().coerceIn(0.1f, 2.0f),
                            cfgWeight = obj.optDouble("cfgWeight", 0.40).toFloat().coerceIn(0.1f, 1.5f),
                            speakerConsentConfirmed = obj.optBoolean("speakerConsentConfirmed", true)
                        )
                    )
                }
                result
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
