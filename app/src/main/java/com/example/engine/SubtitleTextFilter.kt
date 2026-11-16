package com.example.engine

import com.example.data.CharacterVoiceAssignment
import java.util.Locale

/**
 * Filters OCR text inside the game subtitle region to strip HUD labels, health/ammo bars,
 * timers, FPS counters, button prompts, and unrelated non-dialogue UI noise while preserving
 * genuine English dialogue lines and character name headers (including 1-word 3–4 letter
 * ALL-CAPS speaker headers like `"ANON"`, `"IRA"`, `"KAAL"`, `"ALEX"`, `"MAYA"`, `"LEON"`).
 */
object SubtitleTextFilter {

    private val PURE_NUMBER_OR_SYMBOL_REGEX = Regex("^[\\d\\s.,:/\\\\+%\\-_|~•*()\\[\\]{}<>]+$")
    private val CLOCK_OR_TIMER_REGEX = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?(\\s*(AM|PM))?$", RegexOption.IGNORE_CASE)
    private val STAT_RATIO_REGEX = Regex("^(?:HP|MP|SP|XP|AP|EP|GP|LV|LVL|LEVEL|SCORE|COIN|COINS|GOLD|AMMO|MAG|SHIELD|ARMOR|WAVE|ROUND|STAGE|FLOOR|KILL|KILLS|PING|FPS|MS)\\s*[:=\\-/]?\\s*[\\d.,/+%]+.*$", RegexOption.IGNORE_CASE)
    private val FPS_OR_PING_REGEX = Regex("^\\d{1,4}\\s*(?:FPS|HZ|MS|PING|KB/S|MB/S|%)$", RegexOption.IGNORE_CASE)
    private val BUTTON_PROMPT_REGEX = Regex("^(?:\\[?[A-Z0-9]{1,2}\\]?|LMB|RMB|SPACE|ESC|ENTER|SHIFT|CTRL|ALT|TAB)\\s*[:\\-]?\\s*(?:INTERACT|TALK|OPEN|USE|SKIP|CONTINUE|NEXT|CLOSE|ATTACK|DODGE|JUMP|RUN|PICK UP|RELOAD|PAUSE)?$", RegexOption.IGNORE_CASE)
    private val PRESS_PROMPT_REGEX = Regex(
        "^(?:PRESS|HOLD|TAP|CLICK)\\s+(?:ANY\\s+KEY(?:\\s+TO\\s+.*)?|SCREEN|HERE|TO\\s+(?:SKIP|CONTINUE|START|PLAY|RESUME|INTERACT|TALK|OPEN)|\\[?[A-Z0-9]{1,2}\\]?(?:\\s+TO\\s+.*|\\s*))$",
        RegexOption.IGNORE_CASE
    )

    private val UI_CONTROL_WORDS = setOf(
        "skip", "auto", "log", "history", "menu", "save", "load", "q.save", "q.load",
        "pause", "settings", "options", "back", "next", "continue", "close", "exit",
        "quit", "inventory", "map", "quests", "skills", "status", "shop", "mute",
        "loading", "loading...", "saving...", "connecting...", "reconnecting...",
        "tap to continue", "tap to skip", "click to continue", "press to skip"
    )

    // Explicit short UI/HUD abbreviations that are NOT character names
    private val UI_SHORT_HUD_ABBREVIATIONS = setOf(
        "hud", "map", "fps", "ping", "hp", "mp", "sp", "xp", "ap", "ep", "gp",
        "lv", "lvl", "atk", "def", "agi", "str", "int", "dex", "luk", "vit",
        "sta", "ammo", "mag", "esc", "tab", "alt", "lmb", "rmb", "qte", "npc",
        "pvp", "pve", "dlc", "afk"
    )

    /**
     * Filters OCR [rawText] line-by-line, returning clean dialogue text (with speaker headers
     * intact before stripping/translation) or `""` if the block contains only HUD/UI clutter.
     */
    fun filterDialogueText(rawText: String): String {
        if (rawText.isBlank()) return ""

        val normalizedLines = rawText
            .lines()
            .map { normalizeSpacing(it) }
            .filter { it.isNotEmpty() }

        val keptLines = normalizedLines.filterIndexed { index, line ->
            val hasSubsequentDialogueLine = normalizedLines
                .drop(index + 1)
                .any { nextLine -> isLikelyDialogueOrSpeakerLine(nextLine, hasSubsequentLine = false) }
            isLikelyDialogueOrSpeakerLine(line, hasSubsequentLine = hasSubsequentDialogueLine)
        }

        return keptLines.joinToString("\n").trim()
    }

    /**
     * Returns `true` if a single OCR line looks like part of game dialogue or a character speaker header
     * rather than HUD / UI chrome.
     */
    fun isLikelyDialogueOrSpeakerLine(rawLine: String, hasSubsequentLine: Boolean = false): Boolean {
        val line = normalizeSpacing(rawLine)
        if (line.length < 2) return false

        val lower = line.lowercase(Locale.ROOT)
        if (lower in UI_CONTROL_WORDS) return false
        if (PURE_NUMBER_OR_SYMBOL_REGEX.matches(line)) return false
        if (CLOCK_OR_TIMER_REGEX.matches(line)) return false
        if (STAT_RATIO_REGEX.matches(line)) return false
        if (FPS_OR_PING_REGEX.matches(line)) return false
        if (BUTTON_PROMPT_REGEX.matches(line)) return false
        if (PRESS_PROMPT_REGEX.matches(line)) return false

        val letterCount = line.count { it.isLetter() }
        val digitCount = line.count { it.isDigit() }
        if (letterCount < 2) return false
        // Reject telemetry strings dominated by numbers (e.g. "v1.4.2 build 98324")
        if (digitCount > letterCount && letterCount < 5) return false

        // Always keep known character names and speaker headers (e.g., "ANON", "JENNY", "IRA", "KAAL", "[Anon]", "Jenny:")
        val normalizedCandidate = CharacterVoiceAssignment.normalizeName(line)
        if (SpeakerHeuristicEngine.isKnownCharacterOrSpeakerHeader(normalizedCandidate)) {
            return true
        }

        // If this line sits above another dialogue line in a multi-line subtitle block and looks like a 1-3 word speaker header, keep it
        val words = line.split(" ").filter { it.isNotBlank() }
        if (hasSubsequentLine && words.size in 1..3 && line.length in 2..28 && normalizedCandidate !in UI_SHORT_HUD_ABBREVIATIONS) {
            return true
        }

        // Reject explicit short HUD abbreviations like "HUD", "FPS", "MAP", "LVL" when standalone
        if (words.size == 1 && normalizedCandidate in UI_SHORT_HUD_ABBREVIATIONS) {
            return false
        }

        // Single 2-letter ALL-CAPS token without punctuation (like "OK", "UI") is usually UI chrome unless followed by dialogue
        if (!hasSubsequentLine &&
            words.size == 1 &&
            line.length <= 2 &&
            line.all { !it.isLetter() || it.isUpperCase() } &&
            !line.any { it == '!' || it == '?' || it == '.' || it == ':' }
        ) {
            return false
        }

        return true
    }

    private fun normalizeSpacing(input: String): String {
        return input
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
