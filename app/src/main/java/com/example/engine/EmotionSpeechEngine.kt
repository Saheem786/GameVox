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
    val cloudVolumeGainDb: Double,
    val chatterboxExaggeration: Float = 0.50f,
    val chatterboxCfgWeight: Float = 0.40f
) {
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
        cloudVolumeGainDb = 0.0,
        chatterboxExaggeration = 0.45f,
        chatterboxCfgWeight = 0.45f
    ),
    HAPPY(
        code = "happy",
        displayLabel = "Happy / Warm",
        badgeColorHex = "#69F0AE",
        basePitchMultiplier = 1.12f,
        baseSpeechRate = 1.05f,
        baseVolume = 1.0f,
        ssmlRate = "105%",
        ssmlPitch = "+12%",
        ssmlVolume = "medium",
        cloudPitchSemitones = 1.8,
        cloudSpeakingRate = 1.05,
        cloudVolumeGainDb = 0.5,
        chatterboxExaggeration = 0.65f,
        chatterboxCfgWeight = 0.35f
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
        cloudVolumeGainDb = -1.5,
        chatterboxExaggeration = 0.62f,
        chatterboxCfgWeight = 0.48f
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
        cloudVolumeGainDb = 2.5,
        chatterboxExaggeration = 0.85f,
        chatterboxCfgWeight = 0.25f
    ),
    FEAR(
        code = "fear",
        displayLabel = "Fear / Scared",
        badgeColorHex = "#FFAB40",
        basePitchMultiplier = 1.15f,
        baseSpeechRate = 1.08f,
        baseVolume = 0.92f,
        ssmlRate = "108%",
        ssmlPitch = "+15%",
        ssmlVolume = "medium",
        cloudPitchSemitones = 2.0,
        cloudSpeakingRate = 1.08,
        cloudVolumeGainDb = 0.0,
        chatterboxExaggeration = 0.80f,
        chatterboxCfgWeight = 0.28f
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
        cloudVolumeGainDb = -8.0,
        chatterboxExaggeration = 0.28f,
        chatterboxCfgWeight = 0.60f
    ),
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
        cloudVolumeGainDb = 1.5,
        chatterboxExaggeration = 0.78f,
        chatterboxCfgWeight = 0.30f
    ),
    SERIOUS(
        code = "serious",
        displayLabel = "Serious / Composed",
        badgeColorHex = "#90CAF9",
        basePitchMultiplier = 0.94f,
        baseSpeechRate = 0.94f,
        baseVolume = 1.0f,
        ssmlRate = "94%",
        ssmlPitch = "-6%",
        ssmlVolume = "medium",
        cloudPitchSemitones = -1.2,
        cloudSpeakingRate = 0.94,
        cloudVolumeGainDb = 0.0,
        chatterboxExaggeration = 0.38f,
        chatterboxCfgWeight = 0.55f
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
 * final gender-combined pitch, speechRate, and volume values for Android TTS, plus calibrated
 * Chatterbox Multilingual V3 `exaggeration` and `cfgWeight` parameters for neural voice synthesis.
 */
data class EmotionAnalysisResult(
    val emotion: SpeechEmotion,
    val cleanSpokenText: String,
    val ssmlMarkup: String,
    val effectivePitch: Float,
    val effectiveSpeechRate: Float,
    val effectiveVolume: Float,
    val analysisReason: String,
    val chatterboxExaggeration: Float = emotion.chatterboxExaggeration,
    val chatterboxCfgWeight: Float = emotion.chatterboxCfgWeight
)

/**
 * Analyzes game dialogue punctuation & emotional vocabulary to drive expressive TTS voice modulation:
 * Supports 8 acting categories:
 * - [SpeechEmotion.NEUTRAL]
 * - [SpeechEmotion.HAPPY]
 * - [SpeechEmotion.SAD]
 * - [SpeechEmotion.ANGRY]
 * - [SpeechEmotion.FEAR]
 * - [SpeechEmotion.WHISPER]
 * - [SpeechEmotion.EXCITED]
 * - [SpeechEmotion.SERIOUS]
 *
 * Maps emotion categories to ResembleAI Chatterbox Multilingual V3 `exaggeration` (0.25..0.90)
 * and `cfg_weight` (0.25..0.60) conditioning parameters, as well as SSML/prosody for fallback TTS.
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
        "stop!", "get out", "shut up", "damn", "bastard", "enough!", "traitor", "beat me",
        "हिम्मत कैसे हुई", "अलर्ट", "बकवास बंद", "मार डालूंगा", "गुस्सा", "यहाँ से निकलो"
    )

    private val FEAR_KEYWORDS = listOf(
        "terrified", "panic", "monster", "run away", "watch out!", "behind you!",
        "worried about you", "something is moving", "danger!", "help me!", "trap!",
        "फिक्र हो रही थी", "बचाओ", "खतरा", "पीछे रहो"
    )

    private val SAD_HESITANT_KEYWORDS = listOf(
        "sad", "sorry", "funeral", "cry", "crying", "tears", "died", "dead",
        "miss you", "alone", "lonely", "afraid", "scared", "please...", "maybe...",
        "um,", "uh,", "i don't know", "forgive me", "goodbye", "lost everything",
        "फ्यूनरल", "सॉरी", "दुख", "माफ़", "रो", "अकेला", "अलविदा", "डर"
    )

    private val HAPPY_KEYWORDS = listOf(
        "glad", "thank goodness", "happy", "smile", "safe now", "good to see you",
        "welcome home", "proud of you", "great job",
        "खुश", "शुक्र है", "बधाई", "स्वागत"
    )

    private val SERIOUS_KEYWORDS = listOf(
        "protocol", "security override", "mission objective", "commander", "report",
        "understand this", "listen carefully", "ancient temple", "this ends right here",
        "प्रोटोकॉल", "सिक्योरिटी ओवरराइड", "ध्यान से सुनो"
    )

    private val EXCITED_KEYWORDS = listOf(
        "amazing", "awesome", "wow", "yay", "hurry", "quick", "victory", "we won",
        "let's go!", "wait for me!", "incredible", "fantastic", "finally!",
        "वाह", "कमाल", "जीत", "जल्दी", "अरे मेरा वेट करो"
    )

    /**
     * Detects the emotional state from [sourceEnglishText] + [spokenText] and constructs
     * both SSML markup, gender-aware pitch/rate/volume parameters, and Chatterbox conditioning weights.
     */
    fun analyzeEmotionAndBuildSsml(
        spokenText: String,
        sourceEnglishText: String = "",
        gender: SpeakerGender = SpeakerGender.MALE_18,
        profilePitchOverride: Float? = null,
        profileRateOverride: Float? = null,
        masterVolume: Float = 1.0f,
        explicitEmotionOverride: SpeechEmotion? = null
    ): EmotionAnalysisResult {
        val cleanSpoken = stripStageDirections(spokenText)
        val combinedText = "$sourceEnglishText $spokenText".trim()
        val lowerCombined = combinedText.lowercase(Locale.ROOT)

        val (emotion, reason) = if (explicitEmotionOverride != null) {
            explicitEmotionOverride to "Explicit emotion override → ${explicitEmotionOverride.displayLabel}"
        } else {
            detectEmotion(combinedText, lowerCombined)
        }

        val (effectivePitch, effectiveRate, emotionVolume) = computeGenderEmotionProsody(
            emotion = emotion,
            gender = gender,
            profilePitchOverride = profilePitchOverride,
            profileRateOverride = profileRateOverride
        )
        val effectiveVolume = (emotionVolume * masterVolume.coerceIn(0.1f, 1.0f)).coerceIn(0.08f, 1.0f)

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
            analysisReason = reason,
            chatterboxExaggeration = emotion.chatterboxExaggeration,
            chatterboxCfgWeight = emotion.chatterboxCfgWeight
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

        // 3. Check Exclamation '!' or Angry/Excited/Fear shouting
        val hasExclamation = combinedText.contains("!")
        val hasAngryKeyword = ANGRY_KEYWORDS.any { lowerCombined.contains(it) }
        val hasExcitedKeyword = EXCITED_KEYWORDS.any { lowerCombined.contains(it) }
        val hasFearKeyword = FEAR_KEYWORDS.any { lowerCombined.contains(it) }
        val hasHappyKeyword = HAPPY_KEYWORDS.any { lowerCombined.contains(it) }
        val hasSeriousKeyword = SERIOUS_KEYWORDS.any { lowerCombined.contains(it) }

        val uppercaseShoutWords = combinedText.split(Regex("\\s+")).count { word ->
            val letters = word.filter { it.isLetter() }
            letters.length >= 3 && letters.all { it.isUpperCase() }
        }

        if (hasExclamation || uppercaseShoutWords >= 2) {
            return when {
                hasFearKeyword ->
                    SpeechEmotion.FEAR to "Urgent concern/fear phrasing → Fear / Scared"
                hasAngryKeyword && !hasExcitedKeyword ->
                    SpeechEmotion.ANGRY to "Exclamation '!' + urgent/angry tone → Angry (pitch 1.2x, rate 1.1x)"
                else ->
                    SpeechEmotion.EXCITED to "Exclamation '!' detected → Excited (pitch 1.2x, rate 1.1x)"
            }
        }

        if (hasSadKeyword) {
            return SpeechEmotion.SAD to "Sad/somber context detected → Sad (pitch 0.8x, rate 0.8x)"
        }
        if (hasFearKeyword) {
            return SpeechEmotion.FEAR to "Urgent concern/fear phrasing → Fear / Scared"
        }
        if (hasAngryKeyword) {
            return SpeechEmotion.ANGRY to "Urgent/angry phrasing → Angry (pitch 1.2x, rate 1.1x)"
        }
        if (hasExcitedKeyword) {
            return SpeechEmotion.EXCITED to "Excited phrasing detected → Excited (pitch 1.2x, rate 1.1x)"
        }
        if (hasHappyKeyword) {
            return SpeechEmotion.HAPPY to "Warm/positive phrasing → Happy"
        }
        if (hasSeriousKeyword) {
            return SpeechEmotion.SERIOUS to "Tactical/serious phrasing → Serious"
        }

        return SpeechEmotion.NEUTRAL to "Normal sentence → Neutral (18yo natural baseline)"
    }

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

            SpeechEmotion.HAPPY -> {
                val p = (baseGenderPitch * 1.05f).coerceIn(0.85f, 1.32f)
                val r = (baseGenderRate * 1.05f).coerceIn(0.95f, 1.18f)
                Triple(p, r, 1.0f)
            }

            SpeechEmotion.FEAR -> {
                val p = (baseGenderPitch * 1.10f).coerceIn(0.92f, 1.36f)
                val r = (baseGenderRate * 1.08f).coerceIn(1.02f, 1.22f)
                Triple(p, r, 0.94f)
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

            SpeechEmotion.SERIOUS -> {
                val p = (baseGenderPitch * 0.94f).coerceIn(0.68f, 1.22f)
                val r = (baseGenderRate * 0.94f).coerceIn(0.82f, 1.05f)
                Triple(p, r, 1.0f)
            }

            SpeechEmotion.NEUTRAL -> {
                Triple(baseGenderPitch.coerceIn(0.6f, 1.5f), baseGenderRate.coerceIn(0.7f, 1.3f), 1.0f)
            }
        }
    }

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
            SpeechEmotion.EXCITED, SpeechEmotion.ANGRY, SpeechEmotion.FEAR ->
                "<emphasis level=\"strong\">$withPauses</emphasis>"
            else -> withPauses
        }

        val rateAttr = when (emotion) {
            SpeechEmotion.SAD -> "slow"
            SpeechEmotion.WHISPER -> "x-slow"
            SpeechEmotion.EXCITED, SpeechEmotion.ANGRY -> "110%"
            SpeechEmotion.FEAR -> "108%"
            SpeechEmotion.HAPPY -> "105%"
            SpeechEmotion.SERIOUS -> "94%"
            SpeechEmotion.NEUTRAL -> "${(effectiveRate * 100).toInt()}%"
        }

        val pitchAttr = when (emotion) {
            SpeechEmotion.SAD -> "low"
            SpeechEmotion.EXCITED, SpeechEmotion.ANGRY, SpeechEmotion.FEAR, SpeechEmotion.HAPPY -> {
                val pct = ((effectivePitch - 1.0f) * 100f).toInt().coerceIn(8, 35)
                "+${pct}%"
            }
            SpeechEmotion.WHISPER -> "-8%"
            SpeechEmotion.SERIOUS -> "-6%"
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
