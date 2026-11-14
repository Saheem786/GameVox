package com.example.engine

/**
 * Filters OCR text inside the game subtitle region to strip HUD labels, health/ammo bars,
 * timers, FPS counters, button prompts, and unrelated non-dialogue UI noise while preserving
 * genuine English dialogue lines (and character name headers if present).
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

    /**
     * Filters OCR [rawText] line-by-line, returning clean dialogue text or `""` if the block
     * contains only HUD/UI clutter.
     */
    fun filterDialogueText(rawText: String): String {
        if (rawText.isBlank()) return ""

        val keptLines = rawText
            .lines()
            .map { normalizeSpacing(it) }
            .filter { line -> isLikelyDialogueOrSpeakerLine(line) }

        return keptLines.joinToString("\n").trim()
    }

    /**
     * Returns `true` if a single OCR line looks like part of game dialogue (or a speaker header
     * followed by dialogue in the same block) rather than HUD / UI chrome.
     */
    fun isLikelyDialogueOrSpeakerLine(rawLine: String): Boolean {
        val line = normalizeSpacing(rawLine)
        if (line.length < 2) return false

        val lower = line.lowercase()
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

        // Single tiny ALL-CAPS label without punctuation (like "HUD", "MAP", "OK") is usually UI,
        // whereas "WAIT!" or "NO!" has punctuation and is dialogue.
        val words = line.split(" ").filter { it.isNotBlank() }
        if (words.size == 1 &&
            line.length <= 4 &&
            line.all { !it.isLetter() || it.isUpperCase() } &&
            !line.any { it == '!' || it == '?' || it == '.' }
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
