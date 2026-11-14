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
    val backendId: String = "sherpa_supertonic",
    val modelId: String = "supertonic-3-hi",
    val speakerId: Int = 0,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val isUserOverride: Boolean = false,
    val lastDetectedTimestamp: Long = System.currentTimeMillis(),
    val characterRoleNote: String = "Game Character"
) {
    init {
        require(speakerId in 0..9) { "Supertonic 3 speakerId must be within 0..9, got: $speakerId" }
    }

    companion object {
        fun normalizeName(raw: String): String {
            return raw
                .trim()
                .trim('[', ']', '<', '>', '(', ')', '{', '}', ':', '：', '-', '–', '—', ' ', '【', '】', '《', '》', '「', '」', '`', '"', '\'')
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
        }

        /**
         * 24 pre-seeded Game Character Profiles mapped across Supertonic 3's 10 base speaker IDs (0..9).
         * Strictly audited Supertonic 3 voice.bin mapping:
         * - sid 0..4 = Female voices (F1..F5)
         * - sid 5..9 = Male voices (M1..M5)
         *
         * Anon is assigned sid 5 (Young Male Lead M1).
         * Jenny is assigned sid 1 (Bright Female Companion F2).
         */
        val DEFAULT_PRESETS = listOf(
            CharacterVoiceAssignment(
                characterName = "anon",
                displayName = "Anon (Lead Character)",
                speakerId = 5,
                speed = 1.00f,
                pitch = 0.95f,
                characterRoleNote = "Young Male Lead • M1 (Supertonic sid: 5)"
            ),
            CharacterVoiceAssignment(
                characterName = "jenny",
                displayName = "Jenny (Companion)",
                speakerId = 1,
                speed = 1.02f,
                pitch = 1.15f,
                characterRoleNote = "Bright Female Companion • F2 (Supertonic sid: 1)"
            ),
            CharacterVoiceAssignment(
                characterName = "kaal",
                displayName = "Kaal (Boss / Villain)",
                speakerId = 7,
                speed = 0.88f,
                pitch = 0.80f,
                characterRoleNote = "Deep Menacing Antagonist • M3 (Supertonic sid: 7)"
            ),
            CharacterVoiceAssignment(
                characterName = "ira",
                displayName = "Ira (AI Narrator)",
                speakerId = 2,
                speed = 1.00f,
                pitch = 1.10f,
                characterRoleNote = "Composed Terminal AI • F3 (Supertonic sid: 2)"
            ),
            CharacterVoiceAssignment(
                characterName = "alex",
                displayName = "Alex (Agile Scout)",
                speakerId = 6,
                speed = 1.08f,
                pitch = 1.00f,
                characterRoleNote = "Energetic Vanguard Scout • M2 (Supertonic sid: 6)"
            ),
            CharacterVoiceAssignment(
                characterName = "sarah",
                displayName = "Sarah (Commander)",
                speakerId = 3,
                speed = 0.96f,
                pitch = 1.05f,
                characterRoleNote = "Tactical Commander • F4 (Supertonic sid: 3)"
            ),
            CharacterVoiceAssignment(
                characterName = "vikram",
                displayName = "Vikram (Veteran Warrior)",
                speakerId = 8,
                speed = 0.90f,
                pitch = 0.85f,
                characterRoleNote = "Seasoned Battle Veteran • M4 (Supertonic sid: 8)"
            ),
            CharacterVoiceAssignment(
                characterName = "priya",
                displayName = "Priya (Princess)",
                speakerId = 0,
                speed = 0.98f,
                pitch = 1.18f,
                characterRoleNote = "Gentle Royal Princess • F1 (Supertonic sid: 0)"
            ),
            CharacterVoiceAssignment(
                characterName = "guard",
                displayName = "Cybernetic Guard",
                speakerId = 9,
                speed = 0.92f,
                pitch = 0.82f,
                characterRoleNote = "Armored Station Sentinel • M5 (Supertonic sid: 9)"
            ),
            CharacterVoiceAssignment(
                characterName = "oracle",
                displayName = "Mystic Oracle",
                speakerId = 4,
                speed = 0.90f,
                pitch = 1.12f,
                characterRoleNote = "Ethereal Arcane Mystic • F5 (Supertonic sid: 4)"
            ),
            CharacterVoiceAssignment(
                characterName = "aarav",
                displayName = "Aarav (Rookie Recruit)",
                speakerId = 5,
                speed = 1.05f,
                pitch = 1.02f,
                characterRoleNote = "Enthusiastic Junior Operative • M1 (Supertonic sid: 5)"
            ),
            CharacterVoiceAssignment(
                characterName = "ananya",
                displayName = "Ananya (Field Medic)",
                speakerId = 1,
                speed = 1.00f,
                pitch = 1.10f,
                characterRoleNote = "Empathetic Medical Officer • F2 (Supertonic sid: 1)"
            ),
            CharacterVoiceAssignment(
                characterName = "rohan",
                displayName = "Rohan (Sharpshooter)",
                speakerId = 6,
                speed = 0.94f,
                pitch = 0.92f,
                characterRoleNote = "Focused Precision Marksman • M2 (Supertonic sid: 6)"
            ),
            CharacterVoiceAssignment(
                characterName = "kavya",
                displayName = "Kavya (Shadow Assassin)",
                speakerId = 3,
                speed = 1.04f,
                pitch = 1.14f,
                characterRoleNote = "Whispering Stealth Operative • F4 (Supertonic sid: 3)"
            ),
            CharacterVoiceAssignment(
                characterName = "kabir",
                displayName = "Kabir (Elder Sage)",
                speakerId = 8,
                speed = 0.86f,
                pitch = 0.84f,
                characterRoleNote = "Wise Ancient Mentor • M4 (Supertonic sid: 8)"
            ),
            CharacterVoiceAssignment(
                characterName = "pooja",
                displayName = "Pooja (Temple Priestess)",
                speakerId = 4,
                speed = 0.92f,
                pitch = 1.16f,
                characterRoleNote = "Serene Shrine Guardian • F5 (Supertonic sid: 4)"
            ),
            CharacterVoiceAssignment(
                characterName = "dante",
                displayName = "Dante (Heavy Gunner)",
                speakerId = 9,
                speed = 0.98f,
                pitch = 0.88f,
                characterRoleNote = "Boisterous Arsenal Specialist • M5 (Supertonic sid: 9)"
            ),
            CharacterVoiceAssignment(
                characterName = "elena",
                displayName = "Elena (Merchant)",
                speakerId = 2,
                speed = 1.06f,
                pitch = 1.08f,
                characterRoleNote = "Shrewd Resource Quartermaster • F3 (Supertonic sid: 2)"
            ),
            CharacterVoiceAssignment(
                characterName = "ragnar",
                displayName = "Ragnar (Warlord)",
                speakerId = 7,
                speed = 0.92f,
                pitch = 0.78f,
                characterRoleNote = "Fierce Frontline Chieftain • M3 (Supertonic sid: 7)"
            ),
            CharacterVoiceAssignment(
                characterName = "zelda",
                displayName = "Zelda (Sorceress)",
                speakerId = 0,
                speed = 0.96f,
                pitch = 1.20f,
                characterRoleNote = "Noble Sorceress • F1 (Supertonic sid: 0)"
            ),
            CharacterVoiceAssignment(
                characterName = "leon",
                displayName = "Leon (Inspector)",
                speakerId = 5,
                speed = 0.96f,
                pitch = 0.94f,
                characterRoleNote = "Methodical Lead Investigator • M1 (Supertonic sid: 5)"
            ),
            CharacterVoiceAssignment(
                characterName = "maya",
                displayName = "Maya (Resistance Leader)",
                speakerId = 3,
                speed = 1.06f,
                pitch = 1.06f,
                characterRoleNote = "Passionate Underground Rebel • F4 (Supertonic sid: 3)"
            ),
            CharacterVoiceAssignment(
                characterName = "shadow",
                displayName = "Shadow (Stranger)",
                speakerId = 7,
                speed = 0.90f,
                pitch = 0.85f,
                characterRoleNote = "Mysterious Cloaked Figure • M3 (Supertonic sid: 7)"
            ),
            CharacterVoiceAssignment(
                characterName = "announcer",
                displayName = "Arena Announcer",
                speakerId = 9,
                speed = 1.02f,
                pitch = 0.95f,
                characterRoleNote = "Dynamic Tournament Broadcaster • M5 (Supertonic sid: 9)"
            )
        )

        fun serializeList(list: List<CharacterVoiceAssignment>): String {
            val arr = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("characterName", item.characterName)
                    put("displayName", item.displayName)
                    put("backendId", item.backendId)
                    put("modelId", item.modelId)
                    put("speakerId", item.speakerId)
                    put("speed", item.speed.toDouble())
                    put("pitch", item.pitch.toDouble())
                    put("isUserOverride", item.isUserOverride)
                    put("lastDetectedTimestamp", item.lastDetectedTimestamp)
                    put("characterRoleNote", item.characterRoleNote)
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
                    val rawSid = obj.optInt("speakerId", 0)
                    result.add(
                        CharacterVoiceAssignment(
                            characterName = obj.optString("characterName", ""),
                            displayName = obj.optString("displayName", ""),
                            backendId = obj.optString("backendId", "sherpa_supertonic"),
                            modelId = obj.optString("modelId", "supertonic-3-hi"),
                            speakerId = rawSid.coerceIn(0, 9),
                            speed = obj.optDouble("speed", 1.0).toFloat().coerceIn(0.6f, 1.5f),
                            pitch = obj.optDouble("pitch", 1.0).toFloat().coerceIn(0.6f, 1.5f),
                            isUserOverride = obj.optBoolean("isUserOverride", false),
                            lastDetectedTimestamp = obj.optLong("lastDetectedTimestamp", System.currentTimeMillis()),
                            characterRoleNote = obj.optString("characterRoleNote", "Game Character")
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
