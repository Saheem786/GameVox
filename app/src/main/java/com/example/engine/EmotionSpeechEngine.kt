package com.example.engine

import com.example.data.SpeakerGender
import java.util.Locale

/**
 * Emotional tone detected from scanned game dialogue punctuation and vocabulary.
 */
enum class SpeechEmotion(
    val code: String,
    val displayLabel: String,
    val badgeColorHex: String,
    val basePitchMultiplier: Float,
    val baseSpeechRate: Float,
    val baseVolume: Float,
    val ssmlRate: String,
    val ssmlPitch: String,
    val ssmlVolume: String,
    val cloudPitchSemitones: Double,
    val cloudSpeakingRate: Double,
    val cloudVolumeGainDb: Double
) {
    EXCITED(
        code = "excited",
        displayLabel = "Excited (!)",
        badgeColorHex = "#FFB300",
        basePitchMultiplier = 1.2f,
        baseSpeechRate = 1.1f,
        baseVolume = 1.0f,
        ssmlRate = "110%",
        ssmlPitch = "+20%",
        ssmlVolume = "loud",
        cloudPitchSemitones = 3.2,
        cloudSpeakingRate = 1.12,
        cloudVolumeGainDb = 1.5
    ),
    ANGRY(
        code = "angry",
        displayLabel = "Angry / Urgent (!)",
        badgeColorHex = "#FF5252",
        basePitchMultiplier = 1.2f,
        baseSpeechRate = 1.1f,
        baseVolume = 1.0f,
        ssmlRate = "110%",
        ssmlPitch = "+18%",
        ssmlVolume = "x-loud",
        cloudPitchSemitones = 2.4,
        cloudSpeakingRate = 1.14,
        cloudVolumeGainDb = 2.5
    ),
    SAD(
        code = "sad",
        displayLabel = "Sad / Hesitant (...)",
        badgeColorHex = "#7C9EFF",
        basePitchMultiplier = 0.8f,
        baseSpeechRate = 0.8f,
        baseVolume = 0.90f,
        ssmlRate = "slow",
        ssmlPitch = "low",
        ssmlVolume = "medium",
        cloudPitchSemitones = -3.5,
        cloudSpeakingRate = 0.80,
        cloudVolumeGainDb = -1.5
    ),
    WHISPER(
        code = "whisper",
        displayLabel = "Whisper (Soft)",
        badgeColorHex = "#CE93D8",
        basePitchMultiplier = 0.92f,
        baseSpeechRate = 0.72f,
        baseVolume = 0.35f,
        ssmlRate = "x-slow",
        ssmlPitch = "-8%",
        ssmlVolume = "soft",
        cloudPitchSemitones = -2.0,
        cloudSpeakingRate = 0.75,
        cloudVolumeGainDb = -8.0
    ),
    NEUTRAL(
        code = "neutral",
        displayLabel = "Neutral",
        badgeColorHex = "#00E676",
        basePitchMultiplier = 1.0f,
        baseSpeechRate = 1.0f,
        baseVolume = 1.0f,
        ssmlRate = "medium",
        ssmlPitch = "default",
        ssmlVolume = "medium",
        cloudPitchSemitones = 0.0,
        cloudSpeakingRate = 1.0,
        cloudVolumeGainDb = 0.0
    );

    companion object {
        fun fromCode(code: String?): SpeechEmotion {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: NEUTRAL
        }
    }
}

/**
 * Full output of [EmotionSpeechEngine.analyzeEmotionAndBuildSsml]:
 * includes the detected [SpeechEmotion], clean dialogue text, W3C SSML `<speak><prosody ...>` markup,
 * and final gender-combined pitch, speechRate, and volume values for both Android TTS and Cloud TTS.
 */
data class EmotionAnalysisResult(
    val emotion: SpeechEmotion,
    val cleanSpokenText: String,
    val ssmlMarkup: String,
    val effectivePitch: Float,
    val effectiveSpeechRate: Float,
    val effectiveVolume: Float,
    val analysisReason: String
)

/**
 * Analyzes game dialogue punctuation & emotional vocabulary to drive expressive TTS voice modulation:
 * 1. `'!'` or excited/angry cues -> [SpeechEmotion.EXCITED] / [SpeechEmotion.ANGRY] (`pitch = 1.2f`, `rate = 1.1f`)
 * 2. `'...'` (Ellipsis) or sad/hesitant cues -> [SpeechEmotion.SAD] (`pitch = 0.8f`, `rate = 0.8f`)
 * 3. Whisper markers (`*whispers*`, `(quietly)`, `shh`) -> [SpeechEmotion.WHISPER] (`volume = 0.35f`, `rate = 0.72f`)
 * 4. Normal dialogue -> [SpeechEmotion.NEUTRAL]
 *
 * Also wraps spoken text into standard W3C SSML (`<speak><prosody ...>...</prosody></speak>`)
 * while preserving the character's 18-year-old Female (`Jenny`) or Male (`Anon`) vocal baseline.
 */
object EmotionSpeechEngine {

    private val WHISPER_STAGE_DIRECTION_REGEX = Regex(
        "(\\*\\s*whisper(?:s|ing)?\\s*\\*|\\[\\s*whisper(?:s|ing)?\\s*\\]|\\(\\s*(?:whisper(?:s|ing)?|quietly|softly)\\s*\\))",
        RegexOption.IGNORE_CASE
    )

    private val WHISPER_KEYWORDS = listOf(
        "whisper", "whispering", "whispers", "quietly", "softly", "shh", "shhh",
        "keep your voice down", "don't make a sound", "hush", "secretly",
        "धीरे से", "चुप रहो", "आवाज़ नीचे"
    )

    private val ANGRY_KEYWORDS = listOf(
        "angry", "furious", "hate", "kill", "destroy", "dare", "alert", "attack",
        "stop!", "get out", "shut up", "damn", "bastard", "enough!", "traitor",
        "हिम्मत कैसे हुई", "अलर्ट", "बकवास बंद", "मार डालूंगा", "गुस्सा"
    )

    private val SAD_HESITANT_KEYWORDS = listOf(
        "sad", "sorry", "funeral", "cry", "crying", "tears", "died", "dead",
        "miss you", "alone", "lonely", "afraid", "scared", "please...", "maybe...",
        "um,", "uh,", "i don't know", "forgive me", "goodbye", "lost everything",
        "फ्यूनरल", "सॉरी", "दुख", "माफ़", "रो", "अकेला", "अलविदा", "डर"
    )

    private val EXCITED_KEYWORDS = listOf(
        "amazing", "awesome", "wow", "yay", "hurry", "quick", "victory", "we won",
        "let's go!", "wait for me!", "incredible", "fantastic", "finally!",
        "वाह", "कमाल", "जीत", "जल्दी", "अरे मेरा वेट करो"
    )

    /**
     * Detects the emotional state from [sourceEnglishText] + [spokenText] and constructs
     * both SSML markup and gender-aware pitch/rate/volume parameters.
     */
    fun analyzeEmotionAndBuildSsml(
        spokenText: String,
        sourceEnglishText: String = "",
        gender: SpeakerGender = SpeakerGender.MALE_18,
        profilePitchOverride: Float? = null,
        profileRateOverride: Float? = null
    ): EmotionAnalysisResult {
        val cleanSpoken = stripStageDirections(spokenText)
        val combinedText = "$sourceEnglishText $spokenText".trim()
        val lowerCombined = combinedText.lowercase(Locale.ROOT)

        val (emotion, reason) = detectEmotion(combinedText, lowerCombined)

        val (effectivePitch, effectiveRate, effectiveVolume) = computeGenderEmotionProsody(
            emotion = emotion,
            gender = gender,
            profilePitchOverride = profilePitchOverride,
            profileRateOverride = profileRateOverride
        )

        val ssml = buildSsmlString(
            cleanText = cleanSpoken,
            emotion = emotion,
            gender = gender,
            effectivePitch = effectivePitch,
            effectiveRate = effectiveRate,
            effectiveVolume = effectiveVolume
        )

        return EmotionAnalysisResult(
            emotion = emotion,
            cleanSpokenText = cleanSpoken,
            ssmlMarkup = ssml,
            effectivePitch = effectivePitch,
            effectiveSpeechRate = effectiveRate,
            effectiveVolume = effectiveVolume,
            analysisReason = reason
        )
    }

    private fun detectEmotion(
        combinedText: String,
        lowerCombined: String
    ): Pair<SpeechEmotion, String> {
        // 1. Check Whisper cues first (stage directions or hushed words)
        if (WHISPER_STAGE_DIRECTION_REGEX.containsMatchIn(combinedText) ||
            WHISPER_KEYWORDS.any { lowerCombined.contains(it) }
        ) {
            return SpeechEmotion.WHISPER to "Whisper cue detected (soft volume 0.35x, x-slow rate)"
        }

        // 2. Check Ellipsis '...' or '…' or sad/hesitant emotional vocabulary
        val hasEllipsis = combinedText.contains("...") ||
            combinedText.contains("…") ||
            combinedText.contains(". . .")
        val hasSadKeyword = SAD_HESITANT_KEYWORDS.any { lowerCombined.contains(it) }
        if (hasEllipsis) {
            return SpeechEmotion.SAD to "Ellipsis '...' detected → Sad / Hesitant (pitch 0.8x, rate 0.8x)"
        }

        // 3. Check Exclamation '!' or Angry/Excited shouting
        val hasExclamation = combinedText.contains("!")
        val hasAngryKeyword = ANGRY_KEYWORDS.any { lowerCombined.contains(it) }
        val hasExcitedKeyword = EXCITED_KEYWORDS.any { lowerCombined.contains(it) }
        val uppercaseShoutWords = combinedText.split(Regex("\\s+")).count { word ->
            val letters = word.filter { it.isLetter() }
            letters.length >= 3 && letters.all { it.isUpperCase() }
        }

        if (hasExclamation || uppercaseShoutWords >= 2) {
            return if (hasAngryKeyword && !hasExcitedKeyword) {
                SpeechEmotion.ANGRY to "Exclamation '!' + urgent/angry tone → Angry (pitch 1.2x, rate 1.1x)"
            } else {
                SpeechEmotion.EXCITED to "Exclamation '!' detected → Excited (pitch 1.2x, rate 1.1x)"
            }
        }

        if (hasSadKeyword) {
            return SpeechEmotion.SAD to "Sad/somber context detected → Sad (pitch 0.8x, rate 0.8x)"
        }
        if (hasAngryKeyword) {
            return SpeechEmotion.ANGRY to "Urgent/angry phrasing → Angry (pitch 1.2x, rate 1.1x)"
        }
        if (hasExcitedKeyword) {
            return SpeechEmotion.EXCITED to "Excited phrasing detected → Excited (pitch 1.2x, rate 1.1x)"
        }

        return SpeechEmotion.NEUTRAL to "Normal sentence → Neutral (18yo natural baseline)"
    }

    /**
     * Combines the character's 18-year-old Female (`Jenny`) or Male (`Anon`) vocal baseline
     * with the detected [SpeechEmotion] modulation:
     * - **Excited / Angry**: High pitch (~1.2f) & faster speed (~1.1f)
     * - **Sad / Hesitant**: Low pitch (~0.8f) & slower speed (~0.8f)
     * - **Whisper**: Low volume (0.35f) & very slow speed (0.72f)
     * - **Neutral**: Female `1.18f` / `1.0f`, Male `0.90f` / `1.0f`
     */
    private fun computeGenderEmotionProsody(
        emotion: SpeechEmotion,
        gender: SpeakerGender,
        profilePitchOverride: Float?,
        profileRateOverride: Float?
    ): Triple<Float, Float, Float> {
        val baseGenderPitch = profilePitchOverride ?: gender.defaultPitch18Yo
        val baseGenderRate = profileRateOverride ?: gender.defaultSpeechRate18Yo

        return when (emotion) {
            SpeechEmotion.EXCITED, SpeechEmotion.ANGRY -> {
                val p = when (gender) {
                    SpeakerGender.FEMALE_18 -> (baseGenderPitch * 1.07f).coerceIn(1.20f, 1.38f)
                    SpeakerGender.MALE_18 -> (baseGenderPitch * 1.25f).coerceIn(1.10f, 1.24f)
                }
                val r = (baseGenderRate * 1.10f).coerceIn(1.08f, 1.25f)
                Triple(p, r, 1.0f)
            }

            SpeechEmotion.SAD -> {
                val p = when (gender) {
                    SpeakerGender.FEMALE_18 -> (baseGenderPitch * 0.74f).coerceIn(0.80f, 0.94f)
                    SpeakerGender.MALE_18 -> (baseGenderPitch * 0.84f).coerceIn(0.70f, 0.82f)
                }
                val r = (baseGenderRate * 0.80f).coerceIn(0.74f, 0.86f)
                Triple(p, r, 0.90f)
            }

            SpeechEmotion.WHISPER -> {
                val p = when (gender) {
                    SpeakerGender.FEMALE_18 -> 0.96f
                    SpeakerGender.MALE_18 -> 0.82f
                }
                Triple(p, 0.72f, 0.35f)
            }

            SpeechEmotion.NEUTRAL -> {
                Triple(baseGenderPitch.coerceIn(0.6f, 1.5f), baseGenderRate.coerceIn(0.7f, 1.3f), 1.0f)
            }
        }
    }

    /**
     * Converts [cleanText] into standard W3C SSML (`<speak><prosody ...>...</prosody></speak>`),
     * inserting dramatic `<break>` pauses on ellipses (`...`) and `<emphasis>` on excited phrases.
     */
    fun buildSsmlString(
        cleanText: String,
        emotion: SpeechEmotion,
        gender: SpeakerGender,
        effectivePitch: Float,
        effectiveRate: Float,
        effectiveVolume: Float
    ): String {
        val escaped = escapeXml(cleanText)
        val withPauses = escaped
            .replace("...", "<break time=\"450ms\"/>")
            .replace("…", "<break time=\"450ms\"/>")
            .replace("। ", "। <break time=\"220ms\"/>")
            .replace(", ", ", <break time=\"140ms\"/>")

        val body = when (emotion) {
            SpeechEmotion.EXCITED, SpeechEmotion.ANGRY -> "<emphasis level=\"strong\">$withPauses</emphasis>"
            else -> withPauses
        }

        val rateAttr = when (emotion) {
            SpeechEmotion.SAD -> "slow"
            SpeechEmotion.WHISPER -> "x-slow"
            SpeechEmotion.EXCITED, SpeechEmotion.ANGRY -> "110%"
            SpeechEmotion.NEUTRAL -> "${(effectiveRate * 100).toInt()}%"
        }

        val pitchAttr = when (emotion) {
            SpeechEmotion.SAD -> "low"
            SpeechEmotion.EXCITED, SpeechEmotion.ANGRY -> {
                val pct = ((effectivePitch - 1.0f) * 100f).toInt().coerceIn(10, 35)
                "+${pct}%"
            }
            SpeechEmotion.WHISPER -> "-8%"
            SpeechEmotion.NEUTRAL -> when (gender) {
                SpeakerGender.FEMALE_18 -> "+14%"
                SpeakerGender.MALE_18 -> "-8%"
            }
        }

        val volumeAttr = when {
            effectiveVolume <= 0.45f -> "soft"
            emotion == SpeechEmotion.EXCITED || emotion == SpeechEmotion.ANGRY -> "loud"
            else -> "medium"
        }

        return "<speak><prosody rate=\"$rateAttr\" pitch=\"$pitchAttr\" volume=\"$volumeAttr\">$body</prosody></speak>"
    }

    private fun stripStageDirections(text: String): String {
        return WHISPER_STAGE_DIRECTION_REGEX
            .replace(text, "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { text.trim() }
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
