package com.example.service

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import com.example.data.TargetLanguage
import java.util.Locale

/**
 * Free, zero-API-key speech synthesis backends supported by GameVox.
 * Paid cloud TTS APIs, API keys, credits, and subscriptions are disabled.
 */
enum class FreeVoiceBackend(
    val code: String,
    val displayLabel: String,
    val subtitle: String
) {
    LOCAL_CHATTERBOX_SERVER(
        code = "local_chatterbox_server",
        displayLabel = "Human Voice — Local Neural Server",
        subtitle = "Expressive Hindi zero-shot voice cloning via local Windows/PC ResembleAI Chatterbox Multilingual V3 server (100% free self-hosted)"
    ),
    SHERPA_SUPERTONIC_DIRECT(
        code = "sherpa_supertonic_direct",
        displayLabel = "Fast Offline Voice — Supertonic",
        subtitle = "Direct on-device Sherpa-ONNX Supertonic 3 neural voice with 10 speaker IDs & 20+ character profiles (Zero API keys)"
    ),
    PAUSE_DUBBING(
        code = "pause_dubbing",
        displayLabel = "Pause Dubbing",
        subtitle = "Keep screen OCR & modern Hindi subtitle translation active while pausing voice playback"
    ),
    ANDROID_USER_SELECTED(
        code = "android_user_selected",
        displayLabel = "Android System / Supertonic 3 TTS (Free)",
        subtitle = "Uses your selected Android TTS engine (e.g. sherpa-onnx Supertonic 3 Hindi) with zero API keys"
    ),
    MEDIAPIPE_KOKORO(
        code = "mediapipe_kokoro",
        displayLabel = "Kokoro-82M MediaPipe On-Device (Optional)",
        subtitle = "Optional on-device .litertlm neural model via Google MediaPipe Tasks Audio (falls back cleanly if not loaded)"
    );

    companion object {
        fun fromCode(code: String?): FreeVoiceBackend {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
                ?: ANDROID_USER_SELECTED
        }
    }
}

/**
 * Represents an Android [TextToSpeech.EngineInfo] installed on the device.
 */
data class InstalledTtsEngineInfo(
    val packageName: String,
    val label: String,
    val isSystemDefault: Boolean = false,
    val isSherpaOrSupertonic: Boolean = false
)

/**
 * Truthful inspection report of whether the bound TTS engine actually exposes
 * separate Female and Male voices for the active target language.
 */
data class GenderVoiceSupportReport(
    val supportsDistinctGenderVoices: Boolean,
    val femaleVoicesCount: Int,
    val maleVoicesCount: Int,
    val totalLanguageVoicesCount: Int,
    val statusNote: String
)

object FreeTtsEngineCatalog {

    val KNOWN_SHERPA_SUPERTONIC_PACKAGES = setOf(
        "com.k2fsa.sherpa.onnx.tts.engine",
        "com.k2fsa.sherpa.onnx",
        "org.k2fsa.sherpa.onnx.tts.engine",
        "com.k2fsa.sherpa.onnx.supertonic"
    )

    fun isSherpaOrSupertonicEngine(packageName: String?, label: String? = null): Boolean {
        val pkg = packageName.orEmpty().trim().lowercase(Locale.ROOT)
        val lbl = label.orEmpty().trim().lowercase(Locale.ROOT)
        if (pkg in KNOWN_SHERPA_SUPERTONIC_PACKAGES) return true
        return pkg.contains("sherpa") ||
            pkg.contains("supertonic") ||
            lbl.contains("supertonic") ||
            lbl.contains("sherpa")
    }

    /**
     * Discovers installed Android TTS engines from both [TextToSpeech.getEngines] and
     * [android.content.pm.PackageManager] (`android.intent.action.TTS_SERVICE`).
     * Never hardcodes or prioritizes Google TTS over the user's selected engine.
     */
    fun discoverInstalledEngines(
        context: Context,
        ttsEngine: TextToSpeech? = null
    ): List<InstalledTtsEngineInfo> {
        val defaultPkg = try {
            ttsEngine?.defaultEngine
        } catch (_: Exception) {
            null
        }

        val merged = linkedMapOf<String, InstalledTtsEngineInfo>()

        try {
            ttsEngine?.engines.orEmpty().forEach { info ->
                val pkg = info.name?.trim().orEmpty()
                if (pkg.isNotEmpty()) {
                    val label = info.label?.trim().orEmpty().ifBlank { pkg }
                    merged[pkg] = InstalledTtsEngineInfo(
                        packageName = pkg,
                        label = label,
                        isSystemDefault = pkg == defaultPkg,
                        isSherpaOrSupertonic = isSherpaOrSupertonicEngine(pkg, label)
                    )
                }
            }
        } catch (_: Exception) {
        }

        try {
            val pm = context.packageManager
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            @Suppress("DEPRECATION")
            val resolveInfos = pm.queryIntentServices(intent, 0)
            resolveInfos.forEach { ri ->
                val svcInfo = ri.serviceInfo ?: return@forEach
                val pkg = svcInfo.packageName?.trim().orEmpty()
                if (pkg.isNotEmpty() && !merged.containsKey(pkg)) {
                    val label = try {
                        ri.loadLabel(pm).toString().trim().ifBlank { pkg }
                    } catch (_: Exception) {
                        pkg
                    }
                    merged[pkg] = InstalledTtsEngineInfo(
                        packageName = pkg,
                        label = label,
                        isSystemDefault = pkg == defaultPkg,
                        isSherpaOrSupertonic = isSherpaOrSupertonicEngine(pkg, label)
                    )
                }
            }
        } catch (_: Exception) {
        }

        return merged.values.sortedWith(
            compareByDescending<InstalledTtsEngineInfo> { it.isSherpaOrSupertonic }
                .thenByDescending { it.isSystemDefault }
                .thenBy { it.label.lowercase(Locale.ROOT) }
        )
    }

    /**
     * Resolves which Android TTS engine package should be bound when initializing [TextToSpeech]:
     * - If the user explicitly picked an engine package in GameVox, return it (if present or requested).
     * - Otherwise (`selectedPackage.isBlank()`), respect the user's Android System Default TTS engine
     *   by returning `null` (which binds to Android's active default engine without forcing Google TTS).
     * - If [preferInstalledSupertonicWhenSystemDefaultUnset] is true and a sherpa-onnx / Supertonic
     *   engine is installed while no system default package is set, select the Supertonic package.
     */
    fun resolveEnginePackageForBinding(
        userSelectedPackage: String,
        installedEngines: List<InstalledTtsEngineInfo>,
        systemDefaultPackage: String?,
        preferInstalledSupertonicWhenSystemDefaultUnset: Boolean = true
    ): String? {
        val cleanedSelection = userSelectedPackage.trim()
        if (cleanedSelection.isNotEmpty()) {
            return cleanedSelection
        }
        val cleanedDefault = systemDefaultPackage?.trim().orEmpty()
        if (cleanedDefault.isNotEmpty()) {
            return null // Respect user's Android System Default TTS engine
        }
        if (preferInstalledSupertonicWhenSystemDefaultUnset) {
            return installedEngines.firstOrNull { it.isSherpaOrSupertonic }?.packageName
        }
        return null
    }

    /**
     * Evaluates whether the currently active engine actually supports switching between distinct
     * female and male voices for [targetLanguage].
     */
    fun buildGenderSupportReport(
        engineLabel: String,
        targetLanguage: TargetLanguage,
        femaleVoicesCount: Int,
        maleVoicesCount: Int,
        totalLanguageVoicesCount: Int
    ): GenderVoiceSupportReport {
        val supportsDistinct = femaleVoicesCount > 0 && maleVoicesCount > 0
        val note = when {
            supportsDistinct ->
                "$femaleVoicesCount female + $maleVoicesCount male ${targetLanguage.displayName} voices detected on $engineLabel"
            femaleVoicesCount > 0 || maleVoicesCount > 0 ->
                "Only single gender metadata exposed on $engineLabel (F:$femaleVoicesCount / M:$maleVoicesCount) — character switching uses installed voice + pitch contour"
            totalLanguageVoicesCount == 1 ->
                "Single-speaker voice active on $engineLabel — gender switching disabled (pitch contour only)"
            totalLanguageVoicesCount > 1 ->
                "$totalLanguageVoicesCount voices found on $engineLabel without male/female tags — using engine default + pitch contour"
            else ->
                "No separate gender voices exposed by $engineLabel — using engine default for ${targetLanguage.displayName}"
        }
        return GenderVoiceSupportReport(
            supportsDistinctGenderVoices = supportsDistinct,
            femaleVoicesCount = femaleVoicesCount,
            maleVoicesCount = maleVoicesCount,
            totalLanguageVoicesCount = totalLanguageVoicesCount,
            statusNote = note
        )
    }
}
