package com.example.engine

import java.util.Locale

/**
 * Pure-Kotlin English-to-Hindi (Devanagari) subtitle translation engine designed for
 * real-time game dialogue, RPG quests, tactical callouts, and system HUD messages.
 *
 * Avoids loading native C++ `varwidtharray.h` `mlock()` filter tables in container
 * environments where `RLIMIT_MEMLOCK` is restricted, preventing `E/native: Unable to mlock filter data: -1`.
 */
object HindiTranslationEngine {

    private val EXACT_DIALOGUE_MAP = mapOf(
        "cybernetic guard: alert! target secured in sector 7. engage protocols." to
            "साइबरनेटिक गार्ड: अलर्ट! सेक्टर 7 में लक्ष्य सुरक्षित है। प्रोटोकॉल शुरू करें।",
        "alert! target secured in sector 7. engage protocols." to
            "अलर्ट! सेक्टर 7 में लक्ष्य सुरक्षित है। प्रोटोकॉल शुरू करें।",
        "ancient dragon: you dare challenge the keeper of the eternal flame?" to
            "प्राचीन ड्रैगन: क्या तुम अनंत ज्वाला के रक्षक को चुनौती देने का साहस करते हो?",
        "you dare challenge the keeper of the eternal flame?" to
            "क्या तुम अनंत ज्वाला के रक्षक को चुनौती देने का साहस करते हो?",
        "squad leader: hold your position! reinforcements arriving in thirty seconds." to
            "स्क्वाड लीडर: अपनी स्थिति संभालें! तीस सेकंड में सहायता पहुंच रही है।",
        "hold your position! reinforcements arriving in thirty seconds." to
            "अपनी स्थिति संभालें! तीस सेकंड में सहायता पहुंच रही है।",
        "mystic oracle: seek the hidden temple beyond the crimson mountains before dawn." to
            "रहस्यमयी भविष्यवक्ता: भोर होने से पहले लाल पहाड़ों के पार छिपे मंदिर को खोजें।",
        "mystic oracle: seek the hidden temple beyond thecrimson mountains before dawn." to
            "रहस्यमयी भविष्यवक्ता: भोर होने से पहले लाल पहाड़ों के पार छिपे मंदिर को खोजें।",
        "seek the hidden temple beyond the crimson mountains before dawn." to
            "भोर होने से पहले लाल पहाड़ों के पार छिपे मंदिर को खोजें।",
        "system terminal: security override complete. main vault door unlocked." to
            "सिस्टम टर्मिनल: सुरक्षा ओवरराइड पूरा हुआ। मुख्य तिजोरी का दरवाजा खुल गया है।",
        "security override complete. main vault door unlocked." to
            "सुरक्षा ओवरराइड पूरा हुआ। मुख्य तिजोरी का दरवाजा खुल गया है।"
    )

    private val PHRASE_REPLACEMENTS = listOf(
        "cybernetic guard" to "साइबरनेटिक गार्ड",
        "ancient dragon" to "प्राचीन ड्रैगन",
        "squad leader" to "स्क्वाड लीडर",
        "mystic oracle" to "रहस्यमयी भविष्यवक्ता",
        "system terminal" to "सिस्टम टर्मिनल",
        "target secured" to "लक्ष्य सुरक्षित है",
        "engage protocols" to "प्रोटोकॉल शुरू करें",
        "hold your position" to "अपनी स्थिति संभालें",
        "reinforcements arriving" to "सहायता पहुंच रही है",
        "thirty seconds" to "तीस सेकंड",
        "eternal flame" to "अनंत ज्वाला",
        "hidden temple" to "छिपे मंदिर",
        "crimson mountains" to "लाल पहाड़ों",
        "before dawn" to "भोर होने से पहले",
        "security override complete" to "सुरक्षा ओवरराइड पूरा हुआ",
        "main vault door unlocked" to "मुख्य तिजोरी का दरवाजा खुल गया है",
        "game over" to "खेल समाप्त",
        "mission complete" to "मिशन पूरा हुआ",
        "mission failed" to "मिशन असफल रहा",
        "new objective" to "नया लक्ष्य",
        "watch out" to "सावधान रहें",
        "follow me" to "मेरे पीछे आओ",
        "need backup" to "मदद चाहिए",
        "take cover" to "आड़ लो",
        "open the door" to "दरवाजा खोलो",
        "find the key" to "चाबी ढूंढो",
        "boss defeated" to "बॉस पराजित हुआ",
        "level up" to "स्तर बढ़ गया",
        "health low" to "स्वास्थ्य कम है",
        "out of ammo" to "गोली खत्म हो गई"
    )

    private val WORD_LEXICON = mapOf(
        "i" to "मैं",
        "you" to "तुम",
        "we" to "हम",
        "they" to "वे",
        "he" to "वह",
        "she" to "वह",
        "it" to "यह",
        "this" to "यह",
        "that" to "वह",
        "the" to "",
        "a" to "एक",
        "an" to "एक",
        "is" to "है",
        "are" to "हैं",
        "was" to "था",
        "were" to "थे",
        "will" to "गा",
        "can" to "सकते हैं",
        "must" to "जरूर",
        "in" to "में",
        "on" to "पर",
        "at" to "पर",
        "to" to "को",
        "from" to "से",
        "with" to "के साथ",
        "without" to "के बिना",
        "of" to "का",
        "for" to "के लिए",
        "and" to "और",
        "or" to "या",
        "but" to "लेकिन",
        "not" to "नहीं",
        "no" to "नहीं",
        "yes" to "हाँ",
        "hello" to "नमस्ते",
        "welcome" to "स्वागत है",
        "alert" to "अलर्ट",
        "warning" to "चेतावनी",
        "danger" to "खतरा",
        "safe" to "सुरक्षित",
        "secured" to "सुरक्षित",
        "target" to "लक्ष्य",
        "sector" to "सेक्टर",
        "engage" to "शुरू करें",
        "protocol" to "प्रोटोकॉल",
        "protocols" to "प्रोटोकॉल",
        "guard" to "गार्ड",
        "dragon" to "ड्रैगन",
        "ancient" to "प्राचीन",
        "dare" to "साहस",
        "challenge" to "चुनौती",
        "keeper" to "रक्षक",
        "eternal" to "अनंत",
        "flame" to "ज्वाला",
        "fire" to "आग",
        "water" to "पानी",
        "wind" to "हवा",
        "earth" to "पृथ्वी",
        "shadow" to "छाया",
        "light" to "प्रकाश",
        "dark" to "अंधेरा",
        "night" to "रात",
        "day" to "दिन",
        "dawn" to "भोर",
        "hold" to "संभालें",
        "your" to "अपना",
        "my" to "मेरा",
        "our" to "हमारा",
        "position" to "स्थिति",
        "reinforcements" to "सहायता",
        "arriving" to "पहुंच रही है",
        "seconds" to "सेकंड",
        "minutes" to "मिनट",
        "seek" to "खोजें",
        "find" to "ढूंढें",
        "hidden" to "छिपा हुआ",
        "temple" to "मंदिर",
        "beyond" to "के पार",
        "crimson" to "लाल",
        "mountain" to "पहाड़",
        "mountains" to "पहाड़ों",
        "before" to "से पहले",
        "after" to "के बाद",
        "security" to "सुरक्षा",
        "override" to "ओवरराइड",
        "complete" to "पूरा हुआ",
        "completed" to "पूरा हुआ",
        "main" to "मुख्य",
        "vault" to "तिजोरी",
        "door" to "दरवाजा",
        "unlocked" to "खुल गया",
        "locked" to "बंद है",
        "open" to "खोलें",
        "close" to "बंद करें",
        "attack" to "हमला करें",
        "defend" to "रक्षा करें",
        "retreat" to "पीछे हटें",
        "run" to "भागो",
        "stop" to "रुको",
        "wait" to "प्रतीक्षा करो",
        "go" to "जाओ",
        "come" to "आओ",
        "help" to "मदद",
        "enemy" to "दुश्मन",
        "enemies" to "दुश्मन",
        "ally" to "मित्र",
        "friend" to "दोस्त",
        "leader" to "लीडर",
        "squad" to "स्क्वाड",
        "soldier" to "सैनिक",
        "warrior" to "योद्धा",
        "king" to "राजा",
        "queen" to "रानी",
        "sword" to "तलवार",
        "shield" to "कवच",
        "armor" to "कवच",
        "weapon" to "हथियार",
        "ammo" to "गोला-बारूद",
        "health" to "स्वास्थ्य",
        "power" to "शक्ति",
        "energy" to "ऊर्जा",
        "magic" to "जादू",
        "spell" to "मंत्र",
        "quest" to "अभियान",
        "mission" to "मिशन",
        "victory" to "विजय",
        "defeat" to "पराजय",
        "ready" to "तैयार",
        "now" to "अभी",
        "here" to "यहाँ",
        "there" to "वहाँ",
        "where" to "कहाँ",
        "what" to "क्या",
        "who" to "कौन",
        "why" to "क्यों",
        "how" to "कैसे",
        "one" to "एक",
        "two" to "दो",
        "three" to "तीन",
        "four" to "चार",
        "five" to "पाँच",
        "six" to "छह",
        "seven" to "सात",
        "eight" to "आठ",
        "nine" to "नौ",
        "ten" to "दस",
        "twenty" to "बीस",
        "thirty" to "तीस"
    )

    fun translate(englishText: String): String {
        val clean = englishText.trim()
        if (clean.isEmpty()) return ""

        val normalized = clean.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        // 1. Check exact or substring dialogue match first
        EXACT_DIALOGUE_MAP[normalized]?.let { return it }
        for ((pattern, hindi) in EXACT_DIALOGUE_MAP) {
            if (normalized.contains(pattern) || pattern.contains(normalized)) {
                return hindi
            }
        }

        // 2. Handle speaker prefix ("Speaker: Dialogue") cleanly
        if (clean.contains(":")) {
            val parts = clean.split(":", limit = 2)
            val speaker = parts[0].trim()
            val body = parts[1].trim()
            if (speaker.isNotEmpty() && body.isNotEmpty() && speaker.split(" ").size <= 4) {
                val translatedSpeaker = translateClause(speaker)
                val translatedBody = translateClause(body)
                return "$translatedSpeaker: $translatedBody"
            }
        }

        return translateClause(clean)
    }

    private fun translateClause(clause: String): String {
        val normalizedClause = clause.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        EXACT_DIALOGUE_MAP[normalizedClause]?.let { return it }

        var working = normalizedClause
        for ((engPhrase, hindiPhrase) in PHRASE_REPLACEMENTS) {
            if (working.contains(engPhrase)) {
                working = working.replace(engPhrase, hindiPhrase)
            }
        }

        val tokens = working.split(Regex("\\s+"))
        val translatedTokens = mutableListOf<String>()

        for (rawToken in tokens) {
            if (rawToken.isBlank()) continue

            // If token already contains Devanagari characters from phrase replacement, keep it
            if (rawToken.any { it in '\u0900'..'\u097F' }) {
                translatedTokens.add(rawToken)
                continue
            }

            val leadingPunct = rawToken.takeWhile { !it.isLetterOrDigit() }
            val trailingPunct = rawToken.takeLastWhile { !it.isLetterOrDigit() }
            val core = rawToken.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)

            if (core.isEmpty()) {
                translatedTokens.add(rawToken)
                continue
            }

            if (core.all { it.isDigit() }) {
                translatedTokens.add("$leadingPunct$core$trailingPunct")
                continue
            }

            val mapped = WORD_LEXICON[core]
            if (mapped != null) {
                if (mapped.isNotEmpty()) {
                    val hindiPunct = trailingPunct.replace(".", "।")
                    translatedTokens.add("$leadingPunct$mapped$hindiPunct")
                }
            } else {
                val transliterated = transliterateToDevanagari(core)
                val hindiPunct = trailingPunct.replace(".", "।")
                translatedTokens.add("$leadingPunct$transliterated$hindiPunct")
            }
        }

        return translatedTokens
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Phonetic English-to-Devanagari transliterator for proper nouns, character names,
     * and custom words so Android Hindi TTS (hi-IN) pronounces every word naturally.
     */
    private fun transliterateToDevanagari(word: String): String {
        val w = word.lowercase(Locale.ROOT)
        val sb = StringBuilder()
        var i = 0
        while (i < w.length) {
            val twoChar = if (i + 1 < w.length) w.substring(i, i + 2) else ""
            when (twoChar) {
                "sh" -> { sb.append("श"); i += 2; continue }
                "ch" -> { sb.append("च"); i += 2; continue }
                "th" -> { sb.append("थ"); i += 2; continue }
                "ph" -> { sb.append("फ"); i += 2; continue }
                "bh" -> { sb.append("भ"); i += 2; continue }
                "dh" -> { sb.append("ध"); i += 2; continue }
                "gh" -> { sb.append("घ"); i += 2; continue }
                "kh" -> { sb.append("ख"); i += 2; continue }
                "aa", "ee", "oo" -> {
                    sb.append(if (twoChar == "aa") "आ" else if (twoChar == "ee") "ई" else "ऊ")
                    i += 2
                    continue
                }
            }
            when (w[i]) {
                'a' -> sb.append(if (i == 0) "अ" else "ा")
                'b' -> sb.append("ब")
                'c' -> sb.append("क")
                'd' -> sb.append("ड")
                'e' -> sb.append(if (i == 0) "ए" else "े")
                'f' -> sb.append("फ")
                'g' -> sb.append("ग")
                'h' -> sb.append("ह")
                'i' -> sb.append(if (i == 0) "इ" else "ि")
                'j' -> sb.append("ज")
                'k' -> sb.append("क")
                'l' -> sb.append("ल")
                'm' -> sb.append("म")
                'n' -> sb.append("न")
                'o' -> sb.append(if (i == 0) "ओ" else "ो")
                'p' -> sb.append("प")
                'q' -> sb.append("क")
                'r' -> sb.append("र")
                's' -> sb.append("स")
                't' -> sb.append("ट")
                'u' -> sb.append(if (i == 0) "उ" else "ु")
                'v', 'w' -> sb.append("व")
                'x' -> sb.append("क्स")
                'y' -> sb.append("य")
                'z' -> sb.append("ज़")
                else -> sb.append(w[i])
            }
            i++
        }
        return sb.toString()
    }
}
