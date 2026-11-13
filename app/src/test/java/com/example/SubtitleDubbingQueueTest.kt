package com.example

import com.example.engine.DubbingEnqueueOutcome
import com.example.engine.PendingDubbingItem
import com.example.engine.SubtitleDubbingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleDubbingQueueTest {

    private fun item(eng: String, hin: String) = PendingDubbingItem(
        sourceEnglish = eng,
        spokenDialogue = eng,
        translatedText = hin,
        voiceProfile = null,
        speakerLabel = "Jenny"
    )

    @Test
    fun `speaks immediately when idle and suppresses near-duplicate lines`() {
        val queue = SubtitleDubbingQueue(maxCapacity = 2)
        val first = item("Watch your back!", "पीछे ध्यान रखना!")

        val outcome1 = queue.enqueue(
            item = first,
            isCurrentlySpeaking = false,
            activeSpokenEnglishNormalized = "",
            activeSpokenTranslated = ""
        )
        assertTrue(outcome1 is DubbingEnqueueOutcome.SpeakImmediately)

        val duplicateOutcome = queue.enqueue(
            item = item("Watch your back", "पीछे ध्यान रखना"),
            isCurrentlySpeaking = true,
            activeSpokenEnglishNormalized = SubtitleDubbingQueue.normalize(first.spokenDialogue),
            activeSpokenTranslated = first.translatedText
        )
        assertEquals(DubbingEnqueueOutcome.SkippedDuplicate, duplicateOutcome)
    }

    @Test
    fun `drops oldest stale queued line when rapid subtitles overflow bounded queue`() {
        val queue = SubtitleDubbingQueue(maxCapacity = 2)

        val q1 = queue.enqueue(
            item = item("Line one", "लाइन एक"),
            isCurrentlySpeaking = true,
            activeSpokenEnglishNormalized = "active line",
            activeSpokenTranslated = "मौजूदा लाइन"
        )
        assertTrue(q1 is DubbingEnqueueOutcome.Queued)

        val q2 = queue.enqueue(
            item = item("Line two", "लाइन दो"),
            isCurrentlySpeaking = true,
            activeSpokenEnglishNormalized = "active line",
            activeSpokenTranslated = "मौजूदा लाइन"
        )
        assertTrue(q2 is DubbingEnqueueOutcome.Queued)

        val q3 = queue.enqueue(
            item = item("Line three", "लाइन तीन"),
            isCurrentlySpeaking = true,
            activeSpokenEnglishNormalized = "active line",
            activeSpokenTranslated = "मौजूदा लाइन"
        )
        assertTrue(q3 is DubbingEnqueueOutcome.QueuedAndSkippedStale)
        val skipped = q3 as DubbingEnqueueOutcome.QueuedAndSkippedStale
        assertEquals("Line one", skipped.droppedItem.spokenDialogue)
        assertEquals(2, skipped.queueSize)

        assertEquals("Line two", queue.pollNext()?.spokenDialogue)
        assertEquals("Line three", queue.pollNext()?.spokenDialogue)
        assertNull(queue.pollNext())
    }
}
