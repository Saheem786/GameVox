package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.service.CloudTtsProvider
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ttsSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gamevox_cloud_tts_datastore"
)

data class CloudTtsStoredPreferences(
    val provider: CloudTtsProvider = CloudTtsProvider.ANDROID_SSML_NETWORK,
    val googleCloudApiKey: String = "",
    val elevenLabsApiKey: String = "",
    val openAiApiKey: String = ""
) {
    fun keyFor(targetProvider: CloudTtsProvider): String {
        return when (targetProvider) {
            CloudTtsProvider.GOOGLE_CLOUD_TTS -> googleCloudApiKey
            CloudTtsProvider.ELEVENLABS -> elevenLabsApiKey
            CloudTtsProvider.OPENAI_TTS -> openAiApiKey
            CloudTtsProvider.ANDROID_SSML_NETWORK -> ""
        }
    }
}

/**
 * Jetpack DataStore Preferences storage for user-configured Cloud TTS Provider and API keys
 * (Google Cloud TTS, ElevenLabs, OpenAI TTS).
 *
 * Keys saved here are loaded automatically when the app starts and fetched dynamically
 * before making any Cloud TTS API request, falling back to the Default Phone TTS engine
 * if no valid key is present.
 */
object TtsSettingsDataStore {

    private val KEY_SELECTED_PROVIDER = stringPreferencesKey("selected_cloud_tts_provider")
    private val KEY_GOOGLE_CLOUD_API_KEY = stringPreferencesKey("google_cloud_tts_api_key")
    private val KEY_ELEVENLABS_API_KEY = stringPreferencesKey("elevenlabs_tts_api_key")
    private val KEY_OPENAI_API_KEY = stringPreferencesKey("openai_tts_api_key")

    fun observePreferences(context: Context): Flow<CloudTtsStoredPreferences> {
        return context.applicationContext.ttsSettingsDataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { prefs ->
                CloudTtsStoredPreferences(
                    provider = CloudTtsProvider.fromCode(prefs[KEY_SELECTED_PROVIDER]),
                    googleCloudApiKey = prefs[KEY_GOOGLE_CLOUD_API_KEY].orEmpty().trim(),
                    elevenLabsApiKey = prefs[KEY_ELEVENLABS_API_KEY].orEmpty().trim(),
                    openAiApiKey = prefs[KEY_OPENAI_API_KEY].orEmpty().trim()
                )
            }
    }

    suspend fun readPreferences(context: Context): CloudTtsStoredPreferences {
        return observePreferences(context).first()
    }

    suspend fun saveProvider(context: Context, provider: CloudTtsProvider) {
        context.applicationContext.ttsSettingsDataStore.edit { prefs ->
            prefs[KEY_SELECTED_PROVIDER] = provider.code
        }
    }

    suspend fun saveProviderAndApiKey(
        context: Context,
        provider: CloudTtsProvider,
        apiKey: String
    ) {
        val cleanedKey = apiKey.trim()
        context.applicationContext.ttsSettingsDataStore.edit { prefs ->
            prefs[KEY_SELECTED_PROVIDER] = provider.code
            when (provider) {
                CloudTtsProvider.GOOGLE_CLOUD_TTS -> prefs[KEY_GOOGLE_CLOUD_API_KEY] = cleanedKey
                CloudTtsProvider.ELEVENLABS -> prefs[KEY_ELEVENLABS_API_KEY] = cleanedKey
                CloudTtsProvider.OPENAI_TTS -> prefs[KEY_OPENAI_API_KEY] = cleanedKey
                CloudTtsProvider.ANDROID_SSML_NETWORK -> Unit
            }
        }
    }

    /**
     * Dynamically resolves the API key for [provider]:
     * 1. Prioritizes the user-entered key stored in Jetpack DataStore Preferences.
     * 2. Falls back to non-placeholder BuildConfig secrets if configured via AI Studio Secrets.
     * 3. Returns empty string (`""`) if no key is configured, signaling [ForegroundTtsManager]
     *    to use the Default Phone TTS engine.
     */
    suspend fun getEffectiveApiKey(
        context: Context,
        provider: CloudTtsProvider
    ): String {
        val stored = readPreferences(context)
        val userSavedKey = stored.keyFor(provider).trim()
        if (userSavedKey.isNotEmpty() && !userSavedKey.startsWith("MY_")) {
            return userSavedKey
        }
        val buildConfigFallback = when (provider) {
            CloudTtsProvider.GOOGLE_CLOUD_TTS -> BuildConfig.GOOGLE_CLOUD_TTS_API_KEY.trim()
            CloudTtsProvider.OPENAI_TTS -> BuildConfig.OPENAI_TTS_API_KEY.trim()
            CloudTtsProvider.ELEVENLABS, CloudTtsProvider.ANDROID_SSML_NETWORK -> ""
        }
        return if (buildConfigFallback.isNotEmpty() && !buildConfigFallback.startsWith("MY_")) {
            buildConfigFallback
        } else {
            ""
        }
    }
}
