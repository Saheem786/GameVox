package com.example.engine

import com.example.data.SpeakerGender
import com.example.data.SpeakerRole
import com.example.data.VoiceProfile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of analyzing raw OCR or Accessibility scan text through [SpeakerHeuristicEngine].
 */
data class SpeakerHeuristicResult(
    val detectedSpeakerTag: String?,
    val displaySpeakerName: String,
    val cleanedDialogueText: String,
    val matchedRole: SpeakerRole,
    val detectedGender: SpeakerGender,
    val assignedVoice: VoiceProfile,
    val matchedRuleDescription: String
)

/**
 * Heuristic engine that:
 * 1. Separates the Character Name (Line 1 or prefix tag like `'Jenny'`, `'Anon'`, `'Character A:'`)
 *    from the dialogue text so the character's name is NEVER spoken out loud or included in captions.
 * 2. Detects the character's gender and age profile (`SpeakerGender.FEMALE_18` for `'Jenny'` / female
 *    characters vs `SpeakerGender.MALE_18` for `'Anon'` / male characters).
 * 3. Maps the speaker to the corresponding Female or Male [VoiceProfile] so `tts.setVoice()` switches
 *    automatically between an 18-year-old Girl voice and an 18-year-old Boy voice.
 */
object SpeakerHeuristicEngine {

    // Colon or dash-delimited character tag: e.g., "Jenny: Hello there!" or "Anon - Wait up!"
    private val COLON_TAG_REGEX = Regex(
        "^\\s*\\[?([A-Za-z0-9_.'\\- ]{2,26})\\]?\\s*[:：\\-–—]\\s*(.+)$",
        RegexOption.DOT_MATCHES_ALL
    )

    // Bracketed character tag: e.g., "[Jenny] Follow me" or "<Anon> Let's go"
    private val BRACKET_TAG_REGEX = Regex(
        "^\\s*[\\[<(\\u3010]([A-Za-z0-9_.'\\- ]{2,26})[\\]>\\)\\u3011]\\s*[:：\\-–—]?\\s*(.+)$",
        RegexOption.DOT_MATCHES_ALL
    )

    // Known female character names -> 18-year-old Girl Voice (SpeakerGender.FEMALE_18)
    private val FEMALE_CHARACTER_NAMES = setOf(
        "jenny", "jennifer", "sarah", "sara", "alice", "emma", "olivia", "ava", "sophia",
        "mia", "chloe", "zoe", "lily", "grace", "ella", "aria", "elena", "lara", "maya",
        "ruby", "anna", "hannah", "jessica", "ashley", "emily", "samantha", "amanda",
        "melissa", "nicole", "rachel", "kate", "katie", "lucy", "claire", "stella",
        "violet", "hazel", "aurora", "nora", "leah", "audrey", "bella", "nova", "luna",
        "amy", "mary", "sally", "misty", "christy", "kelly", "maria", "julia", "sonia",
        "nina", "eva", "iris", "rose", "daisy", "ivy", "jade", "amber", "brooke", "paige",
        "priya", "ananya", "neha", "riya", "kavya", "pooja", "sneha", "divya", "aisha",
        "zara", "sakura", "hinata", "mikasa", "nami", "robin", "zelda", "peach", "aerith",
        "tifa", "yuna", "ciri", "yennefer", "triss", "aloy", "ellie", "mystic oracle",
        "oracle", "ira", "ira-ai", "character b", "char b", "speaker b",
        "girl", "sister", "sis", "mom", "mother", "mama", "queen", "princess", "lady",
        "madam", "miss", "mrs", "ms", "nurse", "witch", "priestess", "goddess", "heroine",
        "female", "woman", "aunt", "granny", "grandma", "niece", "daughter", "girlfriend"
    )

    // Known male character names -> 18-year-old Boy Voice (SpeakerGender.MALE_18)
    private val MALE_CHARACTER_NAMES = setOf(
        "anon", "anonymous", "alex", "john", "max", "leo", "jack", "ryan", "luke", "lucas",
        "ethan", "noah", "liam", "mason", "jacob", "william", "james", "logan", "ben",
        "benjamin", "daniel", "dan", "michael", "mike", "david", "dave", "chris", "kevin",
        "brian", "jason", "justin", "brandon", "tyler", "zack", "zach", "sam", "tom",
        "nick", "matt", "matthew", "josh", "joshua", "andrew", "andy", "adam", "aaron",
        "eric", "kyle", "mark", "paul", "steven", "steve", "peter", "tony", "bruce",
        "clark", "barry", "hal", "arthur", "dante", "vergil", "leon", "nathan", "cloud",
        "link", "geralt", "kratos", "cj", "franklin", "trevor", "naruto", "sasuke",
        "goku", "luffy", "zoro", "vikram", "aarav", "rohan", "rahul", "arjun", "kabir",
        "raj", "aman", "aditya", "siddharth", "kaal", "cybernetic guard", "guard",
        "ancient dragon", "dragon", "squad leader", "leader", "system terminal",
        "character a", "char a", "speaker a", "character c", "char c", "speaker c",
        "boy", "brother", "bro", "dad", "father", "papa", "king", "prince", "lord",
        "sir", "mr", "boss", "captain", "soldier", "knight", "warrior", "player",
        "hero", "protagonist", "mc", "male", "man", "uncle", "grandpa", "son", "boyfriend"
    )

    // Common English words that start a regular dialogue sentence (NOT a standalone speaker name)
    private val NON_NAME_SENTENCE_STARTERS = setOf(
        "the", "a", "an", "i", "i'm", "i've", "i'll", "i'd", "you", "you're", "you'll",
        "we", "we're", "they", "he", "she", "it", "it's", "this", "that", "these", "those",
        "what", "where", "when", "why", "how", "who", "which", "is", "are", "was", "were",
        "do", "does", "did", "can", "could", "will", "would", "shall", "should", "may",
        "might", "must", "have", "has", "had", "let", "let's", "please", "wait", "hold",
        "stop", "go", "come", "look", "listen", "watch", "hey", "hello", "hi", "yes", "no",
        "okay", "ok", "well", "so", "but", "and", "or", "if", "because", "don't", "do",
        "never", "always", "maybe", "perhaps", "thank", "thanks", "sorry", "excuse", "alert",
        "seek", "security", "press", "loading", "mission", "game"
    )

    // Known RPG / Visual Novel speaker prefixes when OCR merges lines without ':'
    private val KNOWN_SPEAKER_PREFIXES = listOf(
        "Jenny",
        "Anon",
        "Sarah",
        "Alex",
        "Priya",
        "Aarav",
        "Vikram",
        "Ananya",
        "Character A",
        "Character B",
        "Character C",
        "Character D",
        "Cybernetic Guard",
        "Ancient Dragon",
        "Squad Leader",
        "Mystic Oracle",
        "System Terminal",
        "Narrator",
        "System"
    )

    // Session memory mapping dynamically encountered NPC tags -> SpeakerGender
    private val dynamicTagGenderMemory = ConcurrentHashMap<String, SpeakerGender>()

    /**
     * Analyzes [rawScannedText] to:
     * 1. Separate the first line (Character Name, e.g. `'Jenny'` or `'Anon'`) from the rest of the text.
     * 2. Ensure the Character Name is stripped completely from `cleanedDialogueText` so it is NEVER spoken.
     * 3. Detect the character's gender (`FEMALE_18` vs `MALE_18`) and select the matching 18yo Female/Male voice.
     */
    fun analyzeScanResult(
        rawScannedText: String,
        availableVoices: List<VoiceProfile>,
        selectedDefaultVoice: VoiceProfile,
        roleVoiceAssignments: Map<SpeakerRole, String>,
        customTagVoiceBindings: Map<String, String>,
        multiSpeakerEnabled: Boolean
    ): SpeakerHeuristicResult {
        // Step 1: Always extract and strip the Character Name even if single-voice mode is toggled,
        // because the character's name (e.g. 'Jenny' or 'Anon') must NEVER be read out loud!
        val extraction = extractCharacterTagAndDialogue(rawScannedText, availableVoices, customTagVoiceBindings)
        val rawSpeakerTag = extraction.speakerTag
        val cleanDialogue = stripLeadingCharacterName(
            dialogue = normalizeWhitespace(extraction.dialogueText),
            speakerTag = rawSpeakerTag
        ).ifBlank {
            stripLeadingCharacterName(normalizeWhitespace(rawScannedText), rawSpeakerTag)
        }

        val detectedGender = detectCharacterGender(rawSpeakerTag, cleanDialogue)

        if (!multiSpeakerEnabled) {
            return SpeakerHeuristicResult(
                detectedSpeakerTag = rawSpeakerTag,
                displaySpeakerName = rawSpeakerTag ?: "Default (${selectedDefaultVoice.name})",
                cleanedDialogueText = cleanDialogue,
                matchedRole = selectedDefaultVoice.assignedRole,
                detectedGender = detectedGender,
                assignedVoice = selectedDefaultVoice.copy(gender = detectedGender),
                matchedRuleDescription = "Single-Voice Mode (Name Stripped)"
            )
        }

        // Step 2: Check explicit custom tag bindings or voice name matches
        if (!rawSpeakerTag.isNullOrBlank()) {
            val normalizedTagKey = normalizeSpeakerKey(rawSpeakerTag)

            val boundVoiceId = customTagVoiceBindings[normalizedTagKey]
            if (boundVoiceId != null) {
                val boundVoice = availableVoices.find { it.id == boundVoiceId }
                if (boundVoice != null) {
                    return SpeakerHeuristicResult(
                        detectedSpeakerTag = rawSpeakerTag,
                        displaySpeakerName = "$rawSpeakerTag (${detectedGender.displayLabel})",
                        cleanedDialogueText = cleanDialogue,
                        matchedRole = boundVoice.assignedRole,
                        detectedGender = detectedGender,
                        assignedVoice = boundVoice.copy(gender = detectedGender),
                        matchedRuleDescription = "Custom Tag ($rawSpeakerTag → ${boundVoice.name})"
                    )
                }
            }
        }

        // Step 3: Map speaker tag & gender to SpeakerRole
        val matchedRole = classifySpeakerRole(rawSpeakerTag, cleanDialogue, detectedGender)

        // Step 4: Resolve the 18yo Female or 18yo Male VoiceProfile
        val baseVoice = resolveVoiceForGenderAndRole(
            gender = detectedGender,
            role = matchedRole,
            availableVoices = availableVoices,
            selectedDefaultVoice = selectedDefaultVoice,
            roleVoiceAssignments = roleVoiceAssignments
        )

        // Ensure 18yo Female ('Jenny') or 18yo Male ('Anon') pitch/rate & gender are applied
        val effectiveVoice = if (! baseVoice.isCustomCloned) {
            baseVoice.copy(
                gender = detectedGender,
                pitch = if (detectedGender == SpeakerGender.FEMALE_18) {
                    SpeakerGender.FEMALE_18.defaultPitch18Yo
                } else {
                    SpeakerGender.MALE_18.defaultPitch18Yo
                },
                speechRate = if (detectedGender == SpeakerGender.FEMALE_18) {
                    SpeakerGender.FEMALE_18.defaultSpeechRate18Yo
                } else {
                    SpeakerGender.MALE_18.defaultSpeechRate18Yo
                }
            )
        } else {
            baseVoice.copy(gender = detectedGender)
        }

        val displaySpeaker = if (!rawSpeakerTag.isNullOrBlank()) {
            "$rawSpeakerTag • ${if (detectedGender == SpeakerGender.FEMALE_18) "18yo Girl" else "18yo Boy"}"
        } else {
            matchedRole.displayLabel
        }

        val ruleDesc = if (rawSpeakerTag != null) {
            "${extraction.ruleName} ('$rawSpeakerTag' stripped → ${detectedGender.displayLabel})"
        } else {
            "Dialogue Heuristic (${detectedGender.displayLabel})"
        }

        return SpeakerHeuristicResult(
            detectedSpeakerTag = rawSpeakerTag,
            displaySpeakerName = displaySpeaker,
            cleanedDialogueText = cleanDialogue,
            matchedRole = matchedRole,
            detectedGender = detectedGender,
            assignedVoice = effectiveVoice,
            matchedRuleDescription = ruleDesc
        )
    }

    /**
     * Determines whether the character is Female (`SpeakerGender.FEMALE_18`, e.g. `'Jenny'`)
     * or Male (`SpeakerGender.MALE_18`, e.g. `'Anon'`).
     */
    fun detectCharacterGender(speakerTag: String?, dialogue: String = ""): SpeakerGender {
        if (!speakerTag.isNullOrBlank()) {
            val normFull = normalizeSpeakerKey(speakerTag)
            val firstWord = normFull.split(" ").firstOrNull().orEmpty()

            if (FEMALE_CHARACTER_NAMES.contains(normFull) || FEMALE_CHARACTER_NAMES.contains(firstWord)) {
                return SpeakerGender.FEMALE_18
            }
            if (MALE_CHARACTER_NAMES.contains(normFull) || MALE_CHARACTER_NAMES.contains(firstWord)) {
                return SpeakerGender.MALE_18
            }

            // Check female title keywords inside multi-word tags (e.g. "Young Girl", "Sister Jenny", "Queen")
            if (normFull.contains("girl") || normFull.contains("female") || normFull.contains("woman") ||
                normFull.contains("sister") || normFull.contains("mom") || normFull.contains("mother") ||
                normFull.contains("queen") || normFull.contains("princess") || normFull.contains("lady") ||
                normFull.contains("miss") || normFull.contains("mrs") || normFull.contains("oracle") ||
                normFull.contains("jenny")
            ) {
                return SpeakerGender.FEMALE_18
            }

            if (normFull.contains("boy") || normFull.contains("male") || normFull.contains("man") ||
                normFull.contains("brother") || normFull.contains("dad") || normFull.contains("father") ||
                normFull.contains("king") || normFull.contains("prince") || normFull.contains("lord") ||
                normFull.contains("guard") || normFull.contains("leader") || normFull.contains("anon")
            ) {
                return SpeakerGender.MALE_18
            }

            // Phonetic/morphological name suffix heuristics for female first names (e.g. -a, -i, -ie, -y, -ia, -na, -ra, -la, -elle)
            if (firstWord.length >= 3) {
                val isFemaleSuffix = firstWord.endsWith("a") ||
                    firstWord.endsWith("i") ||
                    firstWord.endsWith("ie") ||
                    firstWord.endsWith("y") ||
                    firstWord.endsWith("ee") ||
                    firstWord.endsWith("lyn") ||
                    firstWord.endsWith("elle") ||
                    firstWord.endsWith("ette")
                if (isFemaleSuffix) {
                    return SpeakerGender.FEMALE_18
                }
            }

            return dynamicTagGenderMemory.getOrPut(normFull) {
                SpeakerGender.MALE_18
            }
        }

        return SpeakerGender.MALE_18
    }

    private data class TagExtraction(
        val speakerTag: String?,
        val dialogueText: String,
        val ruleName: String
    )

    /**
     * Sentence Parsing Logic:
     * 1. Separates Line 1 (the Character Name, such as `'Jenny'` or `'Anon'`) when scanned text has multiple lines.
     * 2. Separates `[Name]`, `Name:`, `Name -`, or leading character name prefixes when on a single line.
     * 3. Returns ONLY the dialogue text in [TagExtraction.dialogueText] so the character name is never spoken.
     */
    private fun extractCharacterTagAndDialogue(
        rawScannedText: String,
        availableVoices: List<VoiceProfile>,
        customTagVoiceBindings: Map<String, String>
    ): TagExtraction {
        val rawTrimmed = rawScannedText.trim()
        if (rawTrimmed.isEmpty()) {
            return TagExtraction(null, "", "Empty")
        }

        val nonBlankLines = rawTrimmed
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Rule 1: Multi-line scan where Line 1 is the Character Name (e.g. "Jenny" or "Anon")
        // and Line 2..N is the dialogue to translate & speak.
        if (nonBlankLines.size >= 2) {
            val firstLine = nonBlankLines.first()
            val remainingLines = nonBlankLines.drop(1).joinToString(" ").trim()

            // 1a. First line ends with ':' or '-' (e.g. "Jenny:" or "Anon -")
            if (firstLine.endsWith(":") || firstLine.endsWith("-")) {
                val candidateTag = firstLine.trimEnd(':', '-', ' ').trim()
                if (isValidSpeakerNameTag(candidateTag) && remainingLines.isNotBlank()) {
                    return TagExtraction(candidateTag, remainingLines, "Line-1 Name Header")
                }
            }

            // 1b. First line itself has "Name: Dialogue" while Line 2 continues the dialogue
            val firstLineColon = COLON_TAG_REGEX.find(firstLine)
            if (firstLineColon != null) {
                val tag = firstLineColon.groupValues[1].trim()
                val firstLineBody = firstLineColon.groupValues[2].trim()
                if (isValidSpeakerNameTag(tag)) {
                    val combinedDialogue = "$firstLineBody $remainingLines".trim()
                    return TagExtraction(tag, combinedDialogue, "Line-1 Inline Name")
                }
            }

            // 1c. First line is a standalone Character Name line (e.g. "Jenny", "Anon", "Sarah", "Cybernetic Guard")
            if (isFirstLineCharacterName(firstLine) && remainingLines.isNotBlank()) {
                val cleanedTag = firstLine.trim('[', ']', '<', '>', '(', ')', ':', '-', ' ')
                return TagExtraction(cleanedTag, remainingLines, "Line-1 Character Name")
            }
        }

        // Collapse into single line for inline prefix heuristics
        val singleLine = nonBlankLines.joinToString(" ").replace(Regex("\\s+"), " ").trim()

        // Rule 2: Bracketed tag "[Jenny] Dialogue..." or "<Anon> Dialogue..."
        val bracketMatch = BRACKET_TAG_REGEX.find(singleLine)
        if (bracketMatch != null) {
            val tag = bracketMatch.groupValues[1].trim()
            val body = bracketMatch.groupValues[2].trim()
            if (isValidSpeakerNameTag(tag) && body.isNotBlank()) {
                return TagExtraction(tag, body, "Bracketed Name Tag")
            }
        }

        // Rule 3: Colon/Dash prefix tag "Jenny: Dialogue..." or "Anon - Dialogue..."
        val colonMatch = COLON_TAG_REGEX.find(singleLine)
        if (colonMatch != null) {
            val tag = colonMatch.groupValues[1].trim()
            val body = colonMatch.groupValues[2].trim()
            if (isValidSpeakerNameTag(tag) && body.isNotBlank()) {
                return TagExtraction(tag, body, "Colon Name Tag")
            }
        }

        // Rule 4: Known character names or user-defined tags at the very start of the text when OCR omits ':' or newline
        val dynamicPrefixes = buildList {
            addAll(KNOWN_SPEAKER_PREFIXES)
            FEMALE_CHARACTER_NAMES.forEach { add(it) }
            MALE_CHARACTER_NAMES.forEach { add(it) }
            customTagVoiceBindings.keys.forEach { add(it) }
            availableVoices.forEach { add(it.name.substringBefore("•").substringBefore("—").trim()) }
        }.filter { it.length >= 3 }.distinctBy { it.lowercase(Locale.ROOT) }
            .sortedByDescending { it.length }

        for (prefix in dynamicPrefixes) {
            if (singleLine.startsWith(prefix, ignoreCase = true) &&
                singleLine.length > prefix.length + 2
            ) {
                val nextChar = singleLine[prefix.length]
                if (!nextChar.isLetterOrDigit()) {
                    val detectedName = singleLine.substring(0, prefix.length).trim()
                    val remainder = singleLine.substring(prefix.length)
                        .trimStart(':', '：', '-', '–', '—', ' ', '•', ']', ')', ',')
                        .trim()
                    if (remainder.isNotBlank()) {
                        return TagExtraction(detectedName, remainder, "Leading Character Name")
                    }
                }
            }
        }

        return TagExtraction(null, singleLine, "Dialogue Only")
    }

    /**
     * Checks whether Line 1 of a multi-line OCR block is a Character Name header
     * (such as `"Jenny"`, `"Anon"`, `"Mom"`, `"Cybernetic Guard"`).
     */
    private fun isFirstLineCharacterName(firstLine: String): Boolean {
        val clean = firstLine.trim('[', ']', '<', '>', '(', ')', ':', '-', ' ')
        if (!isValidSpeakerNameTag(clean)) return false
        val words = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 3) return false

        val normFull = clean.lowercase(Locale.ROOT)
        val firstWordNorm = words.first().lowercase(Locale.ROOT).trim { !it.isLetterOrDigit() }

        // Known character name (e.g., Jenny, Anon, Sarah, Alex, etc.)
        if (FEMALE_CHARACTER_NAMES.contains(normFull) ||
            FEMALE_CHARACTER_NAMES.contains(firstWordNorm) ||
            MALE_CHARACTER_NAMES.contains(normFull) ||
            MALE_CHARACTER_NAMES.contains(firstWordNorm) ||
            KNOWN_SPEAKER_PREFIXES.any { it.equals(clean, ignoreCase = true) }
        ) {
            return true
        }

        // Single-word or 2-word capitalized character name on Line 1 that is NOT a regular sentence starter
        if (words.size <= 2 && clean.length <= 20 && !NON_NAME_SENTENCE_STARTERS.contains(firstWordNorm)) {
            val startsWithUpper = words.all { w -> w.firstOrNull()?.isUpperCase() == true }
            if (startsWithUpper) {
                return true
            }
        }

        return false
    }

    /**
     * Ensures any residual speaker name prefix is stripped from the front of [dialogue]
     * so the TTS engine and subtitle box ONLY receive the character's spoken dialogue.
     */
    fun stripLeadingCharacterName(dialogue: String, speakerTag: String? = null): String {
        var working = dialogue.trim()
        if (working.isEmpty()) return working

        if (!speakerTag.isNullOrBlank()) {
            val escapedTag = Regex.escape(speakerTag.trim())
            working = working.replaceFirst(
                Regex("^\\[?$escapedTag\\]?\\s*[:：\\-–—]?\\s*", RegexOption.IGNORE_CASE),
                ""
            ).trim()
        }

        // Also strip if dialogue still begins with "Name: ..."
        val colonMatch = COLON_TAG_REGEX.find(working)
        if (colonMatch != null) {
            val candidateTag = colonMatch.groupValues[1].trim()
            val body = colonMatch.groupValues[2].trim()
            if (isValidSpeakerNameTag(candidateTag) && body.isNotBlank()) {
                working = body
            }
        }

        return working.trim()
    }

    private fun isValidSpeakerNameTag(candidate: String): Boolean {
        val clean = candidate.trim()
        if (clean.length !in 2..26) return false
        val wordCount = clean.split(Regex("\\s+")).size
        if (wordCount > 4) return false
        if (clean.endsWith(".") || clean.endsWith("!") || clean.endsWith("?")) return false
        return clean.any { it.isLetter() }
    }

    fun classifySpeakerRole(
        speakerTag: String?,
        dialogue: String,
        detectedGender: SpeakerGender = detectCharacterGender(speakerTag, dialogue)
    ): SpeakerRole {
        if (!speakerTag.isNullOrBlank()) {
            val norm = normalizeSpeakerKey(speakerTag)
            if (norm == "character d" || norm.contains("narrator") || norm.contains("system") ||
                norm.contains("terminal") || norm.contains("announcer") || norm == "ai"
            ) {
                return SpeakerRole.NARRATOR
            }
            if (norm == "character c" || norm.contains("boss") || norm.contains("dragon") ||
                norm.contains("villain") || norm.contains("monster")
            ) {
                return SpeakerRole.CHARACTER_C
            }
            return if (detectedGender == SpeakerGender.FEMALE_18) {
                SpeakerRole.CHARACTER_B
            } else {
                SpeakerRole.CHARACTER_A
            }
        }

        return if (detectedGender == SpeakerGender.FEMALE_18) {
            SpeakerRole.CHARACTER_B
        } else {
            SpeakerRole.CHARACTER_A
        }
    }

    private fun resolveVoiceForGenderAndRole(
        gender: SpeakerGender,
        role: SpeakerRole,
        availableVoices: List<VoiceProfile>,
        selectedDefaultVoice: VoiceProfile,
        roleVoiceAssignments: Map<SpeakerRole, String>
    ): VoiceProfile {
        val explicitlyAssignedVoiceId = roleVoiceAssignments[role]
        if (explicitlyAssignedVoiceId != null) {
            val explicitVoice = availableVoices.find { it.id == explicitlyAssignedVoiceId }
            if (explicitVoice != null && explicitVoice.gender == gender) {
                return explicitVoice
            }
        }

        return availableVoices.firstOrNull { it.isCustomCloned && it.gender == gender }
            ?: availableVoices.firstOrNull { it.gender == gender && it.assignedRole == role }
            ?: availableVoices.firstOrNull { it.gender == gender }
            ?: selectedDefaultVoice
    }

    fun normalizeSpeakerKey(rawTag: String): String {
        return rawTag
            .trim()
            .trim('[', ']', '<', '>', '(', ')', ':', '-', ' ')
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
    }

    private fun normalizeWhitespace(text: String): String {
        return text
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun clearSessionMemory() {
        dynamicTagGenderMemory.clear()
    }
}
