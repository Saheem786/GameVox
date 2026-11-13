package com.example.engine

import android.util.Log
import com.example.data.TargetLanguage
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

sealed interface ContextAiTranslationResult {
    data class Success(val translatedText: String) : ContextAiTranslationResult
    data class Error(val message: String) : ContextAiTranslationResult
}

/**
 * Optional Context-Aware AI Translation engine backed by Firebase AI Logic (`gemini-3.8-flash`).
 * Receives only cropped dialogue strings + the last 3-4 recent dialogue lines for scene continuity
 * and emotional accuracy (action, comedy, anger, fear, drama). Never receives raw screenshots.
 */
object ContextAwareAiTranslator {

    private const val TAG = "ContextAwareAiTranslator"

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.35f
                maxOutputTokens = 160
            }
        )
    }

    suspend fun translateWithContext(
        englishDialogue: String,
        targetLanguage: TargetLanguage,
        speakerName: String?,
        recentLines: List<Pair<String, String>>
    ): ContextAiTranslationResult {
        val trimmed = englishDialogue.trim()
        if (trimmed.isEmpty()) {
            return ContextAiTranslationResult.Error("Empty dialogue")
        }

        val contextBlock = if (recentLines.isEmpty()) {
            "None"
        } else {
            recentLines.takeLast(4).joinToString("\n") { (eng, tgt) ->
                "- EN: $eng -> ${targetLanguage.displayName}: $tgt"
            }
        }

        val styleRule = if (targetLanguage == TargetLanguage.HINDI) {
            """
            Translate into natural, modern conversational Hindi as spoken in Hindi-dubbed games, movies, and everyday Indian conversations (Devanagari script, natural Hinglish words like sorry, please, wait, tension, backup when natural).
            NEVER use stiff textbook/Sanskritized Hindi (e.g., avoid 'प्रतीक्षा करें', 'सहायता', 'अंतिम संस्कार' — prefer 'वेट करो', 'मदद', 'फ्यूनरल').
            Examples:
            - "Are you kidding me?" -> "मज़ाक कर रहे हो क्या?"
            - "Let's get moving!" -> "चलो, जल्दी निकलते हैं!"
            - "I won't let you down." -> "भरोसा रखो, तुम्हें निराश नहीं करूँगा।"
            """.trimIndent()
        } else {
            "Translate into natural, conversational ${targetLanguage.displayName} suitable for live voice dubbing."
        }

        val prompt = """
            You are a real-time video game voice-dubbing translator.
            $styleRule
            Preserve character names (like Anon, Jenny) mentioned in the line, keep punctuation (!, ..., ?) to preserve action/anger/comedy/fear/dramatic tone, and output ONLY the translated dialogue line with no quotes, labels, or explanations.

            Speaker: ${speakerName ?: "Unknown"}
            Recent dialogue context:
            $contextBlock

            Current line to translate into ${targetLanguage.displayName}:
            $trimmed
        """.trimIndent()

        return try {
            val response = withTimeout(30_000L) {
                model.generateContent(prompt)
            }
            val output = response.text
                ?.trim()
                ?.removePrefix("\"")
                ?.removeSuffix("\"")
                ?.trim()
            if (!output.isNullOrBlank()) {
                ContextAiTranslationResult.Success(output)
            } else {
                ContextAiTranslationResult.Error("Gemini returned an empty translation.")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Context-Aware AI translation timed out", e)
            ContextAiTranslationResult.Error("AI translation timed out after 30s.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Context-Aware AI translation failed", e)
            ContextAiTranslationResult.Error(e.localizedMessage ?: "AI translation failed.")
        }
    }
}
