package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.service.CloudTtsProvider
import com.example.service.FreeVoiceBackend
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
    val openAiApiKey: String = "",
    val selectedTtsEnginePackage: String = "",
    val freeVoiceBackend: FreeVoiceBackend = FreeVoiceBackend.ANDROID_USER_SELECTED,
    val kokoroModelPath: String = "",
    val kokoroUseGpu: Boolean = true
) {
    fun keyFor(targetProvider: CloudTtsProvider): String = ""
}

/**
 * Jetpack DataStore Preferences storage for GameVox's FREE Voice Engine settings:
 * - Selected Android TTS Engine package (`""` = Follow Android System Default, or e.g. `com.k2fsa.sherpa.onnx.tts.engine`)
 * - Free Voice Backend (`ANDROID_USER_SELECTED` or optional `MEDIAPIPE_KOKORO`)
 * - Optional Kokoro-82M `.litertlm` local model path & GPU delegate preference
 *
 * Paid cloud TTS API keys, billing, and subscriptions are permanently disabled.
 */
object TtsSettingsDataStore {

    private val KEY_SELECTED_PROVIDER = stringPreferencesKey("selected_cloud_tts_provider")
    private val KEY_GOOGLE_CLOUD_API_KEY = stringPreferencesKey("google_cloud_tts_api_key")
    private val KEY_ELEVENLABS_API_KEY = stringPreferencesKey("elevenlabs_tts_api_key")
    private val KEY_OPENAI_API_KEY = stringPreferencesKey("openai_tts_api_key")

    private val KEY_SELECTED_ANDROID_TTS_ENGINE = stringPreferencesKey("selected_android_tts_engine_pkg_v1")
    private val KEY_FREE_VOICE_BACKEND = stringPreferencesKey("free_voice_backend_v1")
    private val KEY_KOKORO_MODEL_PATH = stringPreferencesKey("kokoro_model_path_v1")
    private val KEY_KOKORO_USE_GPU = booleanPreferencesKey("kokoro_use_gpu_v1")

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
                    provider = CloudTtsProvider.ANDROID_SSML_NETWORK,
                    googleCloudApiKey = "",
                    elevenLabsApiKey = "",
                    openAiApiKey = "",
                    selectedTtsEnginePackage = prefs[KEY_SELECTED_ANDROID_TTS_ENGINE].orEmpty().trim(),
                    freeVoiceBackend = FreeVoiceBackend.fromCode(prefs[KEY_FREE_VOICE_BACKEND]),
                    kokoroModelPath = prefs[KEY_KOKORO_MODEL_PATH].orEmpty().trim(),
                    kokoroUseGpu = prefs[KEY_KOKORO_USE_GPU] ?: true
                )
            }
    }

    suspend fun readPreferences(context: Context): CloudTtsStoredPreferences {
        return observePreferences(context).first()
    }

    suspend fun saveSelectedAndroidTtsEngine(context: Context, enginePackage: String) {
        val cleaned = enginePackage.trim()
        context.applicationContext.ttsSettingsDataStore.edit { prefs ->
            prefs[KEY_SELECTED_ANDROID_TTS_ENGINE] = cleaned
            prefs[KEY_SELECTED_PROVIDER] = CloudTtsProvider.ANDROID_SSML_NETWORK.code
        }
    }

    suspend fun saveFreeVoiceBackend(context: Context, backend: FreeVoiceBackend) {
        context.applicationContext.ttsSettingsDataStore.edit { prefs ->
            prefs[KEY_FREE_VOICE_BACKEND] = backend.code
        }
    }

    suspend fun saveKokoroConfig(context: Context, modelPath: String, useGpu: Boolean) {
        context.applicationContext.ttsSettingsDataStore.edit { prefs ->
            prefs[KEY_KOKORO_MODEL_PATH] = modelPath.trim()
            prefs[KEY_KOKORO_USE_GPU] = useGpu
        }
    }

    suspend fun saveProvider(context: Context, provider: CloudTtsProvider) {
        context.applicationContext.ttsSettingsDataStore.edit { prefs ->
            prefs[KEY_SELECTED_PROVIDER] = CloudTtsProvider.ANDROID_SSML_NETWORK.code
        }
    }

    suspend fun saveProviderAndApiKey(
        context: Context,
        provider: CloudTtsProvider,
        apiKey: String
    ) {
        // Paid cloud API keys are disabled; clear legacy keys and keep free TTS active
        context.applicationContext.ttsSettingsDataStore.edit { prefs ->
            prefs[KEY_SELECTED_PROVIDER] = CloudTtsProvider.ANDROID_SSML_NETWORK.code
            prefs.remove(KEY_GOOGLE_CLOUD_API_KEY)
            prefs.remove(KEY_ELEVENLABS_API_KEY)
            prefs.remove(KEY_OPENAI_API_KEY)
        }
    }

    /**
     * Paid voice APIs are disabled in GameVox; always returns `""`.
     */
    suspend fun getEffectiveApiKey(
        context: Context,
        provider: CloudTtsProvider
    ): String = ""
}
