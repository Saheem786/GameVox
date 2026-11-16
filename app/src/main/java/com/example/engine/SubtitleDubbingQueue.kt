package com.example.engine

import com.example.data.CharacterVoiceAssignment
import com.example.data.VoiceProfile
import java.util.Locale

data class PendingDubbingItem(
    val sourceEnglish: String,
    val spokenDialogue: String,
    val translatedText: String,
    val voiceProfile: VoiceProfile?,
    val speakerLabel: String,
    val characterAssignment: CharacterVoiceAssignment? = null,
    val routeDecision: VoiceRouteDecision? = null
) {
    fun buildSpeakerContextKey(): String {
        val ns = (routeDecision?.gameNamespace ?: characterAssignment?.gameNamespace ?: "default_game")
            .lowercase(Locale.ROOT)
        val speakerIdentity = routeDecision?.antiRepeatSpeakerIdentity
            ?: "$ns:${characterAssignment?.characterName ?: CharacterVoiceAssignment.normalizeName(speakerLabel)}"
        val epoch = routeDecision?.sessionEpoch ?: 0L
        return "$speakerIdentity:ep$epoch"
    }
}

sealed interface DubbingEnqueueOutcome {
    data class SpeakImmediately(val item: PendingDubbingItem) : DubbingEnqueueOutcome
    data class Queued(val queueSize: Int) : DubbingEnqueueOutcome
    data class QueuedAndSkippedStale(
        val droppedItem: PendingDubbingItem,
        val queueSize: Int
    ) : DubbingEnqueueOutcome
    data object SkippedDuplicate : DubbingEnqueueOutcome
}

/**
 * Short bounded queue (max 2 upcoming lines) for live subtitle dubbing:
 * - Prevents duplicate queuing of near-identical lines by the SAME speaker in the same scene/session.
 * - Allows up to [maxCapacity] upcoming subtitles to wait for the current sentence to finish.
 * - Intelligently skips stale older queued subtitles whenever fast scene transitions arrive
 *   more quickly than TTS can finish speaking.
 */
class SubtitleDubbingQueue(
    private val maxCapacity: Int = 2
) {
    private val lock = Any()
    private val pending = ArrayDeque<PendingDubbingItem>()

    fun enqueue(
        item: PendingDubbingItem,
        isCurrentlySpeaking: Boolean,
        activeSpokenEnglishNormalized: String,
        activeSpokenTranslated: String,
        activeSpokenSpeakerContextKey: String = ""
    ): DubbingEnqueueOutcome = synchronized(lock) {
        if (isDuplicate(item, activeSpokenEnglishNormalized, activeSpokenTranslated, activeSpokenSpeakerContextKey)) {
            return DubbingEnqueueOutcome.SkippedDuplicate
        }

        if (!isCurrentlySpeaking && pending.isEmpty()) {
            return DubbingEnqueueOutcome.SpeakImmediately(item)
        }

        var dropped: PendingDubbingItem? = null
        if (pending.size >= maxCapacity.coerceAtLeast(1)) {
            dropped = pending.removeFirst()
        }
        pending.addLast(item)

        return if (dropped != null) {
            DubbingEnqueueOutcome.QueuedAndSkippedStale(dropped, pending.size)
        } else {
            DubbingEnqueueOutcome.Queued(pending.size)
        }
    }

    fun pollNext(): PendingDubbingItem? = synchronized(lock) {
        if (pending.isEmpty()) null else pending.removeFirst()
    }

    fun size(): Int = synchronized(lock) { pending.size }

    fun clear(): Unit = synchronized(lock) {
        pending.clear()
    }

    private fun isDuplicate(
        candidate: PendingDubbingItem,
        activeEnglishNorm: String,
        activeTranslated: String,
        activeSpokenSpeakerContextKey: String
    ): Boolean {
        val candEng = normalize(candidate.spokenDialogue)
        val candTgt = normalize(candidate.translatedText)
        val candSpeakerKey = candidate.buildSpeakerContextKey()

        if (candEng.isEmpty() || candTgt.isEmpty()) return true
        val sameAsActiveSpeaker = activeSpokenSpeakerContextKey.isBlank() ||
            candSpeakerKey == activeSpokenSpeakerContextKey
        if (sameAsActiveSpeaker &&
            (areSimilar(candEng, activeEnglishNorm) || areSimilar(candTgt, normalize(activeTranslated)))
        ) {
            return true
        }
        return pending.any { existing ->
            existing.buildSpeakerContextKey() == candSpeakerKey &&
                (areSimilar(candEng, normalize(existing.spokenDialogue)) ||
                    areSimilar(candTgt, normalize(existing.translatedText)))
        }
    }

    companion object {
        fun normalize(text: String): String {
            return text
                .lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun areSimilar(normA: String, normB: String): Boolean {
            if (normA.isEmpty() || normB.isEmpty()) return false
            if (normA == normB) return true

            val shorterLen = minOf(normA.length, normB.length)
            val longerLen = maxOf(normA.length, normB.length)
            if (shorterLen >= 8 && (normA.contains(normB) || normB.contains(normA)) &&
                shorterLen.toFloat() / longerLen.toFloat() >= 0.70f
            ) {
                return true
            }

            val wordsA = normA.split(" ").filter { it.isNotEmpty() }.toSet()
            val wordsB = normB.split(" ").filter { it.isNotEmpty() }.toSet()
            if (wordsA.isNotEmpty() && wordsB.isNotEmpty()) {
                val intersection = wordsA.intersect(wordsB).size
                val union = wordsA.union(wordsB).size.coerceAtLeast(1)
                if (intersection.toFloat() / union.toFloat() >= 0.78f) {
                    return true
                }
            }
            return false
        }
    }
}
