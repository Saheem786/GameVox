package com.example.engine

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
    val assignedVoice: VoiceProfile,
    val matchedRuleDescription: String
)

/**
 * Heuristic engine that analyzes OCR and Accessibility scan results to identify character
 * name tags (e.g., `"Character A:"`, `"[Character B]"`, `"Boss - "`, or RPG nameplate headers)
 * and maps them to the corresponding [VoiceProfile] selected in the Voice Manager.
 */
object SpeakerHeuristicEngine {

    // Colon or dash-delimited character tag: e.g., "Character A: Hello there!" or "Guard - Halt!"
    private val COLON_TAG_REGEX = Regex(
        "^\\s*\\[?([A-Za-z0-9_.'\\- ]{2,26})\\]?\\s*[:：\\-–—]\\s*(.+)$",
        RegexOption.DOT_MATCHES_ALL
    )

    // Bracketed character tag: e.g., "[Character A] Follow me" or "<System> Door unlocked"
    private val BRACKET_TAG_REGEX = Regex(
        "^\\s*[\\[<(\\u3010]([A-Za-z0-9_.'\\- ]{2,26})[\\]>\\)\\u3011]\\s*[:：\\-–—]?\\s*(.+)$",
        RegexOption.DOT_MATCHES_ALL
    )

    // Known RPG / Action game speaker prefixes when OCR misses the colon separator
    private val KNOWN_SPEAKER_PREFIXES = listOf(
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

    // Session memory mapping dynamically encountered NPC tags -> SpeakerRole for voice consistency
    private val dynamicTagRoleMemory = ConcurrentHashMap<String, SpeakerRole>()
    private var nextDynamicRoleCursor = 0

    /**
     * Analyzes [rawScannedText] to extract any character name tag (such as `"Character A:"`),
     * separates the clean dialogue text for translation, and maps the speaker tag to the
     * corresponding [VoiceProfile] configured in the Voice Manager.
     */
    fun analyzeScanResult(
        rawScannedText: String,
        availableVoices: List<VoiceProfile>,
        selectedDefaultVoice: VoiceProfile,
        roleVoiceAssignments: Map<SpeakerRole, String>,
        customTagVoiceBindings: Map<String, String>,
        multiSpeakerEnabled: Boolean
    ): SpeakerHeuristicResult {
        val normalizedDialogueFallback = normalizeWhitespace(rawScannedText)
        if (!multiSpeakerEnabled) {
            return SpeakerHeuristicResult(
                detectedSpeakerTag = null,
                displaySpeakerName = "Default (${selectedDefaultVoice.name})",
                cleanedDialogueText = normalizedDialogueFallback,
                matchedRole = selectedDefaultVoice.assignedRole,
                assignedVoice = selectedDefaultVoice,
                matchedRuleDescription = "Single-Voice Mode"
            )
        }

        // Step 1: Extract speaker name tag and clean dialogue using multi-pattern heuristics
        val extraction = extractCharacterTagAndDialogue(rawScannedText, availableVoices, customTagVoiceBindings)
        val rawSpeakerTag = extraction.speakerTag
        val cleanDialogue = normalizeWhitespace(extraction.dialogueText).ifBlank { normalizedDialogueFallback }

        // Step 2: If a speaker tag was detected, check explicit custom tag bindings or voice name matches first
        if (!rawSpeakerTag.isNullOrBlank()) {
            val normalizedTagKey = normalizeSpeakerKey(rawSpeakerTag)

            // 2a. Explicit Custom Tag -> Voice Profile ID binding
            val boundVoiceId = customTagVoiceBindings[normalizedTagKey]
            if (boundVoiceId != null) {
                val boundVoice = availableVoices.find { it.id == boundVoiceId }
                if (boundVoice != null) {
                    return SpeakerHeuristicResult(
                        detectedSpeakerTag = rawSpeakerTag,
                        displaySpeakerName = rawSpeakerTag,
                        cleanedDialogueText = cleanDialogue,
                        matchedRole = boundVoice.assignedRole,
                        assignedVoice = boundVoice,
                        matchedRuleDescription = "Custom Tag Binding ($rawSpeakerTag → ${boundVoice.name})"
                    )
                }
            }

            // 2b. Direct match against a VoiceProfile's custom name in Voice Manager
            val directNamedVoice = availableVoices.firstOrNull { voice ->
                val vName = normalizeSpeakerKey(voice.name)
                vName == normalizedTagKey ||
                    (normalizedTagKey.length >= 4 && vName.contains(normalizedTagKey))
            }
            if (directNamedVoice != null) {
                return SpeakerHeuristicResult(
                    detectedSpeakerTag = rawSpeakerTag,
                    displaySpeakerName = rawSpeakerTag,
                    cleanedDialogueText = cleanDialogue,
                    matchedRole = directNamedVoice.assignedRole,
                    assignedVoice = directNamedVoice,
                    matchedRuleDescription = "${extraction.ruleName} → Direct Voice Match (${directNamedVoice.name})"
                )
            }
        }

        // Step 3: Map speaker tag (e.g., "Character A", "Character B", "Boss") or dialogue cues to a SpeakerRole
        val matchedRole = classifySpeakerRole(rawSpeakerTag, cleanDialogue)

        // Step 4: Resolve the VoiceProfile selected for that SpeakerRole in the Voice Manager
        val assignedVoice = resolveVoiceForRole(
            role = matchedRole,
            availableVoices = availableVoices,
            selectedDefaultVoice = selectedDefaultVoice,
            roleVoiceAssignments = roleVoiceAssignments
        )

        val displaySpeaker = rawSpeakerTag ?: matchedRole.displayLabel.substringBefore(" (")
        val ruleDesc = if (rawSpeakerTag != null) {
            "${extraction.ruleName} ('$rawSpeakerTag' → ${matchedRole.displayLabel.substringBefore(" (")})"
        } else {
            "Dialogue Context Heuristic (${matchedRole.displayLabel.substringBefore(" (")})"
        }

        return SpeakerHeuristicResult(
            detectedSpeakerTag = rawSpeakerTag,
            displaySpeakerName = displaySpeaker,
            cleanedDialogueText = cleanDialogue,
            matchedRole = matchedRole,
            assignedVoice = assignedVoice,
            matchedRuleDescription = ruleDesc
        )
    }

    private data class TagExtraction(
        val speakerTag: String?,
        val dialogueText: String,
        val ruleName: String
    )

    private fun extractCharacterTagAndDialogue(
        rawScannedText: String,
        availableVoices: List<VoiceProfile>,
        customTagVoiceBindings: Map<String, String>
    ): TagExtraction {
        val rawTrimmed = rawScannedText.trim()
        if (rawTrimmed.isEmpty()) {
            return TagExtraction(null, "", "Empty")
        }

        // Heuristic 1: Multi-line RPG dialogue box where Line 1 is the Character Nameplate
        // Example:
        // "Character A:" or "CYBERNETIC GUARD"
        // "Halt! Show your identification before entering Sector 7."
        val nonBlankLines = rawTrimmed
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (nonBlankLines.size >= 2) {
            val firstLine = nonBlankLines.first()
            val remainingLines = nonBlankLines.drop(1).joinToString(" ")

            // Check if first line ends with ':' or '-' (e.g. "Character A:")
            if (firstLine.endsWith(":") || firstLine.endsWith("-")) {
                val candidateTag = firstLine.trimEnd(':', '-', ' ').trim()
                if (isValidSpeakerNameTag(candidateTag) && remainingLines.isNotBlank()) {
                    return TagExtraction(candidateTag, remainingLines, "Line-1 Colon Tag")
                }
            }

            // Check if first line is a short standalone RPG nameplate (e.g. "CHARACTER A" or "ANCIENT DRAGON")
            if (isStandaloneRpgNameplate(firstLine) && remainingLines.length >= 4) {
                val cleanedTag = firstLine.trim('[', ']', '<', '>', ':', '-', ' ')
                return TagExtraction(cleanedTag, remainingLines, "RPG Nameplate Line")
            }
        }

        // Collapse into single line for inline prefix tag heuristics
        val singleLine = nonBlankLines.joinToString(" ").replace(Regex("\\s+"), " ").trim()

        // Heuristic 2: Bracketed tag "[Character A] Dialogue..." or "<Character B> Dialogue..."
        val bracketMatch = BRACKET_TAG_REGEX.find(singleLine)
        if (bracketMatch != null) {
            val tag = bracketMatch.groupValues[1].trim()
            val body = bracketMatch.groupValues[2].trim()
            if (isValidSpeakerNameTag(tag) && body.isNotBlank()) {
                return TagExtraction(tag, body, "Bracketed Name Tag")
            }
        }

        // Heuristic 3: Colon/Dash prefix tag "Character A: Dialogue..."
        val colonMatch = COLON_TAG_REGEX.find(singleLine)
        if (colonMatch != null) {
            val tag = colonMatch.groupValues[1].trim()
            val body = colonMatch.groupValues[2].trim()
            if (isValidSpeakerNameTag(tag) && body.isNotBlank()) {
                return TagExtraction(tag, body, "Colon Prefix Tag")
            }
        }

        // Heuristic 4: User-defined custom tags or known game character prefixes when OCR drops ':'
        val dynamicPrefixes = buildList {
            addAll(KNOWN_SPEAKER_PREFIXES)
            customTagVoiceBindings.keys.forEach { add(it) }
            availableVoices.forEach { add(it.name.substringBefore(" —").trim()) }
        }.distinct()

        for (prefix in dynamicPrefixes) {
            if (prefix.length >= 3 &&
                singleLine.startsWith(prefix, ignoreCase = true) &&
                singleLine.length > prefix.length + 2
            ) {
                val nextChar = singleLine[prefix.length]
                if (!nextChar.isLetterOrDigit()) {
                    val remainder = singleLine.substring(prefix.length)
                        .trimStart(':', '：', '-', '–', '—', ' ', '•', ']', ')')
                    if (remainder.isNotBlank()) {
                        return TagExtraction(prefix, remainder, "Known Prefix Tag")
                    }
                }
            }
        }

        return TagExtraction(null, singleLine, "Unlabeled Subtitle")
    }

    private fun isValidSpeakerNameTag(candidate: String): Boolean {
        val clean = candidate.trim()
        if (clean.length !in 2..26) return false
        val wordCount = clean.split(Regex("\\s+")).size
        if (wordCount > 4) return false
        // Speaker tags shouldn't end with sentence punctuation
        if (clean.endsWith(".") || clean.endsWith("!") || clean.endsWith("?")) return false
        return clean.any { it.isLetter() }
    }

    private fun isStandaloneRpgNameplate(line: String): Boolean {
        val clean = line.trim('[', ']', '<', '>', ' ')
        if (!isValidSpeakerNameTag(clean)) return false
        val words = clean.split(Regex("\\s+"))
        if (words.size > 3) return false
        // Either all-caps nameplate ("CHARACTER A", "CYBERNETIC GUARD") or "Character X"
        val isAllCaps = clean.filter { it.isLetter() }.all { it.isUpperCase() }
        val isCharacterTag = clean.startsWith("Character ", ignoreCase = true) ||
            KNOWN_SPEAKER_PREFIXES.any { it.equals(clean, ignoreCase = true) }
        return isAllCaps || isCharacterTag
    }

    /**
     * Classifies a detected speaker name tag (e.g. `"Character A"`, `"Character B"`, `"Boss"`)
     * into one of the Voice Manager's [SpeakerRole] slots.
     */
    fun classifySpeakerRole(speakerTag: String?, dialogue: String): SpeakerRole {
        if (!speakerTag.isNullOrBlank()) {
            val norm = normalizeSpeakerKey(speakerTag)
            return when {
                // Explicit Character A / Player 1 / Hero / Protagonist / Guard Captain
                norm == "character a" || norm == "char a" || norm == "speaker a" ||
                    norm == "player" || norm == "player 1" || norm.contains("character a") ||
                    norm.contains("hero") || norm.contains("squad") || norm.contains("leader") ||
                    norm.contains("captain") || norm.contains("protagonist") ->
                    SpeakerRole.CHARACTER_A

                // Explicit Character B / Player 2 / Companion / Ally / Mage / Oracle / Merchant
                norm == "character b" || norm == "char b" || norm == "speaker b" ||
                    norm == "player 2" || norm.contains("character b") ||
                    norm.contains("oracle") || norm.contains("mystic") || norm.contains("ally") ||
                    norm.contains("companion") || norm.contains("mage") || norm.contains("merchant") ->
                    SpeakerRole.CHARACTER_B

                // Explicit Character C / Boss / Dragon / Villain / Guard / Enemy
                norm == "character c" || norm == "char c" || norm == "speaker c" ||
                    norm.contains("character c") || norm.contains("boss") ||
                    norm.contains("dragon") || norm.contains("guard") || norm.contains("enemy") ||
                    norm.contains("villain") || norm.contains("warlord") || norm.contains("monster") ->
                    SpeakerRole.CHARACTER_C

                // Narrator / System / HUD / Terminal / AI
                norm == "character d" || norm.contains("narrator") || norm.contains("system") ||
                    norm.contains("terminal") || norm.contains("announcer") || norm == "ai" ||
                    norm.contains("hud") || norm.contains("quest") ->
                    SpeakerRole.NARRATOR

                else -> {
                    // Consistent voice mapping for recurring named NPCs in a game session
                    dynamicTagRoleMemory.getOrPut(norm) {
                        val conversationalRoles = listOf(
                            SpeakerRole.CHARACTER_A,
                            SpeakerRole.CHARACTER_B,
                            SpeakerRole.CHARACTER_C
                        )
                        val assigned = conversationalRoles[nextDynamicRoleCursor % conversationalRoles.size]
                        nextDynamicRoleCursor++
                        assigned
                    }
                }
            }
        }

        // Fallback heuristic when no explicit speaker tag is present in the subtitle
        val lower = dialogue.lowercase(Locale.ROOT)
        return when {
            lower.contains("security override") || lower.contains("system") ||
                lower.contains("unlocked") || lower.contains("objective") ||
                lower.contains("mission complete") ->
                SpeakerRole.NARRATOR

            lower.contains("eternal flame") || lower.contains("dare challenge") ||
                lower.contains("destroy") || lower.contains("mortal") ||
                lower.contains("kneel") ->
                SpeakerRole.CHARACTER_C

            lower.contains("temple") || lower.contains("oracle") ||
                lower.contains("dawn") || lower.contains("heal") ||
                lower.contains("magic") ->
                SpeakerRole.CHARACTER_B

            else -> SpeakerRole.CHARACTER_A
        }
    }

    private fun resolveVoiceForRole(
        role: SpeakerRole,
        availableVoices: List<VoiceProfile>,
        selectedDefaultVoice: VoiceProfile,
        roleVoiceAssignments: Map<SpeakerRole, String>
    ): VoiceProfile {
        // 1. Check if the user explicitly selected a VoiceProfile for this SpeakerRole in Voice Manager
        val explicitlyAssignedVoiceId = roleVoiceAssignments[role]
        if (explicitlyAssignedVoiceId != null) {
            val explicitVoice = availableVoices.find { it.id == explicitlyAssignedVoiceId }
            if (explicitVoice != null) {
                return explicitVoice
            }
        }

        // 2. If the currently selected default voice is assigned to this role, prefer it
        if (selectedDefaultVoice.assignedRole == role) {
            return selectedDefaultVoice
        }

        // 3. Prefer a user-created custom cloned voice assigned to this role, then built-in preset
        return availableVoices.firstOrNull { it.isCustomCloned && it.assignedRole == role }
            ?: availableVoices.firstOrNull { it.assignedRole == role }
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
        dynamicTagRoleMemory.clear()
        nextDynamicRoleCursor = 0
    }
}
