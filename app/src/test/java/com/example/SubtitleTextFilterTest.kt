package com.example

import com.example.engine.SubtitleTextFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTextFilterTest {

    @Test
    fun `filters out health bars timers fps counters and UI prompts`() {
        val rawOcr = """
            HP: 100/100
            60 FPS
            12:45
            [E] Interact
            Press Any Key to Continue
            Skip
            Jenny
            Are you kidding me? Let's get moving!
        """.trimIndent()

        val cleaned = SubtitleTextFilter.filterDialogueText(rawOcr)

        assertEquals("Jenny\nAre you kidding me? Let's get moving!", cleaned)
    }

    @Test
    fun `rejects standalone telemetry stats and tiny uppercase labels`() {
        assertFalse(SubtitleTextFilter.isLikelyDialogueOrSpeakerLine("MP 45/120"))
        assertFalse(SubtitleTextFilter.isLikelyDialogueOrSpeakerLine("18 ms"))
        assertFalse(SubtitleTextFilter.isLikelyDialogueOrSpeakerLine("HUD"))
        assertFalse(SubtitleTextFilter.isLikelyDialogueOrSpeakerLine("v1.2 9941"))
        assertFalse(SubtitleTextFilter.isLikelyDialogueOrSpeakerLine("999+"))
    }

    @Test
    fun `preserves short dramatic interjections and normal sentences`() {
        assertTrue(SubtitleTextFilter.isLikelyDialogueOrSpeakerLine("NO!"))
        assertTrue(SubtitleTextFilter.isLikelyDialogueOrSpeakerLine("WAIT!"))
        assertTrue(SubtitleTextFilter.isLikelyDialogueOrSpeakerLine("I won't let you down."))
        assertTrue(SubtitleTextFilter.isLikelyDialogueOrSpeakerLine("Anon: Let's head back home."))
    }
}
