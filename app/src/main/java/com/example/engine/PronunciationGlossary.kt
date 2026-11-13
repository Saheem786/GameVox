package com.example.engine

import java.util.Locale
import org.json.JSONObject

/**
 * Configurable pronunciation glossary mapping English character names, abbreviations,
 * and Hinglish gaming terms to phonetic Devanagari forms for clearer Hindi TTS (`hi-IN`)
 * pronunciation without modifying the visual subtitle captions displayed to the user.
 */
object PronunciationGlossary {

    val DEFAULT_ENTRIES: Map<String, String> = linkedMapOf(
        "Anon" to "एनॉन",
        "Jenny" to "जेनी",
        "HP" to "हेल्थ",
        "MP" to "मैना",
        "XP" to "एक्स पी",
        "NPC" to "एन पी सी",
        "Boss" to "बॉस",
        "GG" to "जी जी",
        "AFK" to "ए एफ के",
        "Respawn" to "रीस्पॉन"
    )

    /**
     * Replaces glossary keys on case-insensitive word boundaries in [spokenText] right before
     * sending text to the Hindi TTS engine.
     */
    fun applyForSpeech(
        spokenText: String,
        glossary: Map<String, String>
    ): String {
        if (spokenText.isBlank() || glossary.isEmpty()) return spokenText
        var result = spokenText
        // Sort longest keys first so multi-word names match before single words
        val sortedEntries = glossary.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedByDescending { it.key.length }

        for ((term, pronunciation) in sortedEntries) {
            val escaped = Regex.escape(term.trim())
            val pattern = Regex("(?<![\\p{L}\\p{N}_])$escaped(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, pronunciation.trim())
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    fun sanitizeEntry(term: String, pronunciation: String): Pair<String, String>? {
        val cleanTerm = term.trim().replace(Regex("\\s+"), " ")
        val cleanPronunciation = pronunciation.trim().replace(Regex("\\s+"), " ")
        if (cleanTerm.isEmpty() || cleanPronunciation.isEmpty()) return null
        return cleanTerm to cleanPronunciation
    }

    fun toJsonString(glossary: Map<String, String>): String {
        val obj = JSONObject()
        glossary.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) {
                obj.put(k, v)
            }
        }
        return obj.toString()
    }

    fun fromJsonString(rawJson: String?): Map<String, String> {
        if (rawJson.isNullOrBlank()) return DEFAULT_ENTRIES.toMap()
        return try {
            val obj = JSONObject(rawJson)
            val parsed = linkedMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next().trim()
                val value = obj.optString(key, "").trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    parsed[key] = value
                }
            }
            if (parsed.isEmpty()) DEFAULT_ENTRIES.toMap() else parsed
        } catch (_: Exception) {
            DEFAULT_ENTRIES.toMap()
        }
    }

    fun normalizedKeyMatch(glossary: Map<String, String>, candidateKey: String): String? {
        val lower = candidateKey.trim().lowercase(Locale.ROOT)
        return glossary.keys.firstOrNull { it.trim().lowercase(Locale.ROOT) == lower }
    }
}
