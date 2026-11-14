package com.example.engine

import android.content.Context
import android.util.Log
import com.example.data.TargetLanguage
import com.example.data.TranslatorStateRepository
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
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
 * Receives only cropped dialogue strings + up to 4 recent dialogue lines for scene continuity
 * and emotional accuracy. Never initializes Firebase AI when [FirebaseApp] is absent or unconfigured.
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

    /**
     * Verifies at runtime that FirebaseApp is initialized with valid user project credentials
     * (and not a template placeholder) before claiming Gemini AI mode is available.
     */
    fun isFirebaseAiAvailable(context: Context? = null): Boolean {
        return try {
            val app = if (context != null) {
                val existing = FirebaseApp.getApps(context)
                if (existing.isEmpty()) {
                    FirebaseApp.initializeApp(context) ?: run {
                        TranslatorStateRepository.setFirebaseAiStatusText("Not configured (missing google-services.json)")
                        return false
                    }
                } else {
                    existing.first()
                }
            } else {
                val existing = FirebaseApp.getApps(com.example.AppContextHolder.getOrNull() ?: return false)
                existing.firstOrNull() ?: return false
            }

            val opts = app.options
            val projectId = opts.projectId.orEmpty().trim()
            val apiKey = opts.apiKey.trim()
            val appId = opts.applicationId.trim()

            val valid = projectId.isNotEmpty() &&
                !projectId.equals("remixed-project-id", ignoreCase = true) &&
                apiKey.isNotEmpty() &&
                appId.isNotEmpty()

            if (valid) {
                TranslatorStateRepository.setFirebaseAiStatusText("Configured (Firebase AI • quota applies)")
            } else {
                TranslatorStateRepository.setFirebaseAiStatusText("Not configured (incomplete Firebase project config)")
            }
            valid
        } catch (e: Exception) {
            TranslatorStateRepository.setFirebaseAiStatusText("Unavailable (${e.javaClass.simpleName})")
            false
        }
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

        if (!isFirebaseAiAvailable()) {
            return ContextAiTranslationResult.Error("Gemini AI is not configured (FirebaseApp unavailable)")
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
            Translate into natural, modern conversational Hindi as spoken in Hindi-dubbed games, movies, and everyday Indian conversations (Devanagari script). Use natural Hinglish loanwords (like sorry, please, wait, tension, backup) ONLY when they sound natural and fit the scene.
            NEVER use stiff textbook/Sanskritized Hindi (avoid 'प्रतीक्षा करें', 'सहायता', 'अंतिम संस्कार' — prefer 'वेट करो', 'मदद', 'फ्यूनरल').
            NEVER inject unnecessary forced slang or alter the original meaning.
            Examples:
            - "Are you kidding me?" -> "मज़ाक कर रहे हो क्या?"
            - "Let's get moving!" -> "चलो, जल्दी निकलते हैं!"
            - "I won't let you down." -> "भरोसा रखो, तुम्हें निराश नहीं करूँगा।"
            """.trimIndent()
        } else {
            "Translate into natural, conversational ${targetLanguage.displayName} suitable for live voice dubbing without altering meaning."
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
