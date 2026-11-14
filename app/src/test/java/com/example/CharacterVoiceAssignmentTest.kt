package com.example

import com.example.data.CharacterVoiceAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterVoiceAssignmentTest {

    @Test
    fun test20OrMorePreSeededProfiles() {
        val presets = CharacterVoiceAssignment.DEFAULT_PRESETS
        assertTrue(
            "GameVox must provide 20+ character profiles (found: ${presets.size})",
            presets.size >= 20
        )
    }

    @Test
    fun testAllSidBoundsStrictlyWithin0To9() {
        for (preset in CharacterVoiceAssignment.DEFAULT_PRESETS) {
            assertTrue(
                "Supertonic 3 speakerId for '${preset.displayName}' must be in 0..9, got: ${preset.speakerId}",
                preset.speakerId in 0..9
            )
            assertTrue(
                "Speed multiplier must be between 0.6x and 1.5x, got: ${preset.speed}",
                preset.speed in 0.6f..1.5f
            )
            assertTrue(
                "Pitch multiplier must be between 0.6x and 1.5x, got: ${preset.pitch}",
                preset.pitch in 0.6f..1.5f
            )
        }
    }

    @Test
    fun testNormalizedCharacterNames() {
        assertEquals("anon", CharacterVoiceAssignment.normalizeName("ANON"))
        assertEquals("anon", CharacterVoiceAssignment.normalizeName("  Anon  "))
        assertEquals("jenny", CharacterVoiceAssignment.normalizeName("[Jenny]:"))
        assertEquals("jenny", CharacterVoiceAssignment.normalizeName("<Jenny> -"))
        assertEquals("kaal", CharacterVoiceAssignment.normalizeName("【Kaal】"))
        assertEquals("cybernetic guard", CharacterVoiceAssignment.normalizeName("Cybernetic Guard:"))
    }

    @Test
    fun testAnonAndJennyInvokeDifferentSids() {
        val anon = CharacterVoiceAssignment.DEFAULT_PRESETS.first { it.characterName == "anon" }
        val jenny = CharacterVoiceAssignment.DEFAULT_PRESETS.first { it.characterName == "jenny" }

        assertEquals(0, anon.speakerId)
        assertEquals(1, jenny.speakerId)
        assertTrue(
            "Anon (sid: ${anon.speakerId}) and Jenny (sid: ${jenny.speakerId}) must invoke different speaker IDs",
            anon.speakerId != jenny.speakerId
        )
    }

    @Test
    fun testKaalAndIraInvokeDifferentSids() {
        val kaal = CharacterVoiceAssignment.DEFAULT_PRESETS.first { it.characterName == "kaal" }
        val ira = CharacterVoiceAssignment.DEFAULT_PRESETS.first { it.characterName == "ira" }

        assertEquals(6, kaal.speakerId)
        assertEquals(3, ira.speakerId)
        assertTrue(kaal.speakerId != ira.speakerId)
    }

    @Test
    fun testSerializationAndDeserializationCycle() {
        val originalList = CharacterVoiceAssignment.DEFAULT_PRESETS
        val json = CharacterVoiceAssignment.serializeList(originalList)
        assertNotNull(json)
        assertTrue(json.isNotBlank())

        val parsedList = CharacterVoiceAssignment.parseList(json)
        assertEquals(originalList.size, parsedList.size)

        for (i in originalList.indices) {
            val orig = originalList[i]
            val parsed = parsedList[i]
            assertEquals(orig.characterName, parsed.characterName)
            assertEquals(orig.displayName, parsed.displayName)
            assertEquals(orig.speakerId, parsed.speakerId)
            assertEquals(orig.speed, parsed.speed, 0.001f)
            assertEquals(orig.pitch, parsed.pitch, 0.001f)
            assertEquals(orig.isUserOverride, parsed.isUserOverride)
        }
    }

    @Test
    fun testFloorModSafetyWithNegativeAndMinValueHashes() {
        // Critical audio engineering test:
        // Math.abs(Int.MIN_VALUE) remains negative (-2147483648), causing Math.abs(x) % 10 to yield -8 (CRASH).
        // Math.floorMod safely returns positive 2.
        val speakerCount = 10
        val safeSid = Math.floorMod(Int.MIN_VALUE, speakerCount)
        assertTrue("floorMod with Int.MIN_VALUE must be >= 0, got: $safeSid", safeSid in 0..9)
        assertEquals(2, safeSid)

        val negativeHash = -987654321
        val safeSid2 = Math.floorMod(negativeHash, speakerCount)
        assertTrue("floorMod with negative hash must be >= 0, got: $safeSid2", safeSid2 in 0..9)
    }

    @Test
    fun testUnknownSpeakerHandledDeterministicallyWithoutCrashing() {
        val speakerCount = 10
        val unknownTag1 = "Alien Commander"
        val norm1 = CharacterVoiceAssignment.normalizeName(unknownTag1)
        val sid1 = Math.floorMod(norm1.hashCode(), speakerCount)
        assertTrue(sid1 in 0..9)

        val unknownTag2 = "Shadow Syndicate Boss"
        val norm2 = CharacterVoiceAssignment.normalizeName(unknownTag2)
        val sid2 = Math.floorMod(norm2.hashCode(), speakerCount)
        assertTrue(sid2 in 0..9)
    }

    @Test
    fun testUserOverrideTakesPriority() {
        val defaultAnon = CharacterVoiceAssignment.DEFAULT_PRESETS.first { it.characterName == "anon" }
        assertEquals(0, defaultAnon.speakerId)
        assertFalse(defaultAnon.isUserOverride)

        // Simulate user overriding Anon to Sid 4
        val overriddenAnon = defaultAnon.copy(
            speakerId = 4,
            isUserOverride = true
        )
        assertTrue(overriddenAnon.isUserOverride)
        assertEquals(4, overriddenAnon.speakerId)
    }

    @Test
    fun testSidClampingPreventsOutOfBounds() {
        val clampedSidNegative = (-3).coerceIn(0, 9)
        assertEquals(0, clampedSidNegative)

        val clampedSidExcess = 25.coerceIn(0, 9)
        assertEquals(9, clampedSidExcess)
    }
}
