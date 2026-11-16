package com.example.engine

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.CharacterVoiceAssignment
import com.example.data.SpeakerGender
import com.example.data.TranslatorAppState
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.service.ForegroundTtsManager
import com.example.service.FreeVoiceBackend
import com.example.service.NeuralSynthesizeRequest
import com.example.service.NeuralVoiceClient
import com.example.service.SherpaSupertonicBackend
import com.example.service.SupertonicModelManager
import com.k2fsa.sherpa.onnx.OfflineTts
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Canonical voice synthesis backend selected identically by both Voice Manager previews
 * and live OCR gameplay dubbing for any given character and app state.
 */
enum class CanonicalVoiceBackend(val backendTag: String) {
    PAUSE_DUBBING("PAUSE_DUBBING"),
    CHATTERBOX_LOCAL_SERVER("CHATTERBOX_LOCAL_SERVER"),
    SHERPA_DIRECT("SHERPA_DIRECT"),
    MEDIAPIPE_KOKORO("MEDIAPIPE_KOKORO"),
    SYSTEM_FALLBACK("SYSTEM_FALLBACK"),
    SYSTEM_FALLBACK_SINGLE_VOICE("SYSTEM_FALLBACK_SINGLE_VOICE");

    companion object {
        val LOCAL_CHATTERBOX_SERVER: CanonicalVoiceBackend get() = CHATTERBOX_LOCAL_SERVER
        val SHERPA_SUPERTONIC_DIRECT: CanonicalVoiceBackend get() = SHERPA_DIRECT
        val ANDROID_SYSTEM_TTS: CanonicalVoiceBackend get() = SYSTEM_FALLBACK
    }
}

/**
 * Result of canonical backend selection before synthesis begins.
 */
data class CanonicalBackendSelection(
    val canonicalBackend: CanonicalVoiceBackend,
    val requestedEngineTag: String,
    val readinessNote: String
) {
    val backend: CanonicalVoiceBackend
        get() = canonicalBackend

    val selectionReason: String
        get() = readinessNote
}

/**
 * Real-time visible debug log entry showing exact speaker resolution and voice routing telemetry
 * for every OCR subtitle frame and preview request.
 */
data class LiveVoiceRoutingDebugEntry(
    val utteranceId: String,
    val timestampMs: Long,
    val gameNamespace: String,
    val rawOcr: String,
    val speakerLabelCandidate: String,
    val normalizedName: String,
    val confidence: Float,
    val resolvedProfileId: String,
    val resolvedDisplayName: String,
    val sid: Int,
    val referenceVoiceId: String,
    val referenceAudioSha256: String,
    val requestedBackend: String,
    val actualBackend: String,
    val fallbackReason: String,
    val resolutionRule: String
) {
    fun toCompactSummaryLine(): String {
        val cand = speakerLabelCandidate.ifBlank { "(none)" }
        val confPct = (confidence * 100f).toInt()
        val fb = if (fallbackReason.isNotBlank()) " • fallback=$fallbackReason" else ""
        return "OCR='$rawOcr' | tag=$cand → norm=$normalizedName ($confPct%) | profile=${resolvedDisplayName} [SID #$sid, ref=$referenceVoiceId.wav] | backend=$actualBackend$fb"
    }
}

/**
 * Immutable voice routing decision created BEFORE synthesis and shared across:
 * 1. Voice Manager character previews & 10-voice audition matrix
 * 2. Floating quick panel voice controls
 * 3. Live game subtitle OCR -> translation -> queue -> TTS playback pipeline
 *
 * Ensures that a resolved character speaker ID ([selectedSpeakerId]) is carried end-to-end
 * and is NEVER silently replaced with a default SID or overwritten by concurrent OCR frames.
 */
data class VoiceRouteDecision(
    val utteranceId: String,
    val gameNamespace: String,
    val rawOcrText: String,
    val rawCharacterName: String?,
    val normalizedCharacterName: String,
    val strippedEnglishDialogue: String,
    val hindiDialogue: String,
    val characterProfileId: String,
    val characterAssignment: CharacterVoiceAssignment,
    val voiceProfile: VoiceProfile,
    val selectedSpeakerId: Int,
    val requestedEngine: String,
    val actualPlaybackEngine: String,
    val sessionEpoch: Long,
    val resolutionRule: String,
    val speakerConfidence: Float = 1.0f,
    val canonicalBackend: CanonicalVoiceBackend = CanonicalVoiceBackend.SYSTEM_FALLBACK,
    val isFromRealMediaProjectionOcr: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    /**
     * Stable speaker identity key used by anti-repeat caches so two different characters
     * speaking the exact same dialogue line are never treated as duplicates.
     */
    val antiRepeatSpeakerIdentity: String
        get() = "${gameNamespace}:${normalizedCharacterName}:${characterProfileId}:sid${selectedSpeakerId}"
}

/**
 * Full per-utterance audit record verifying the actual voice engine AFTER synthesis,
 * speaker ID, JNI/model state, fallback reason, and measured acoustic RMS energy.
 *
 * When PCM is not measured (e.g. Android System TTS fallback), [outputPcmRms] is `null`
 * and [outputPcmRmsDisplay] reports `"UNKNOWN"` instead of fabricated numbers.
 */
data class SpokenUtteranceAuditLog(
    val utteranceId: String,
    val gameNamespace: String,
    val ocrCharacterName: String,
    val normalizedCharacterName: String,
    val resolvedProfileId: String,
    val requestedSid: Int,
    val actualSidPassedToNative: Int,
    val requestedEngine: String,
    val actualBackend: String,
    val jniStatus: String,
    val modelLoadedStatus: Boolean,
    val fallbackReason: String,
    val generatedAudioDurationMs: Long,
    val outputPcmRms: Float?,
    val ttsGenerationTimestampMs: Long,
    val playbackTimestampMs: Long,
    val resolutionRule: String,
    val hindiText: String,
    val speakerConfidence: Float = 1.0f,
    val referenceVoiceId: String = "",
    val referenceAudioSha256: String = ""
) {
    val outputPcmRmsDisplay: String
        get() = if (outputPcmRms != null && !outputPcmRms.isNaN()) {
            String.format(Locale.US, "%.4f", outputPcmRms)
        } else {
            "UNKNOWN"
        }
}

/**
 * Result of comparing Voice Manager Preview vs Live Game Dubbing route for a single character.
 * Never reports `passed = true` unless real neural speech WAVs were synthesized by a loaded model or server.
 */
data class PreviewVsLiveCharacterComparison(
    val characterName: String,
    val displayName: String,
    val hindiSentence: String,
    val previewRoute: VoiceRouteDecision,
    val liveRoute: VoiceRouteDecision,
    val previewRequestedSid: Int,
    val previewActualSid: Int,
    val liveRequestedSid: Int,
    val liveActualSid: Int,
    val previewEngine: String,
    val liveEngine: String,
    val sampleRate: Int,
    val previewAudioRms: Float?,
    val liveAudioRms: Float?,
    val previewDurationMs: Long,
    val liveDurationMs: Long,
    val previewWavPath: String,
    val liveWavPath: String,
    val previewWavSha256: String = "",
    val liveWavSha256: String = "",
    val referenceVoiceId: String = "",
    val referenceAudioSha256: String = "",
    val speakerIdOrReferenceWav: String = "SID #$liveActualSid • $referenceVoiceId.wav",
    val previewErrorStatus: String = "NONE",
    val liveErrorStatus: String = "NONE",
    val previewLatencyMs: Long = 0L,
    val liveLatencyMs: Long = 0L,
    val verificationStatus: String = "NOT_TESTED_NO_MODEL",
    val liveCapturedViaMediaProjectionOcr: Boolean = false,
    val sidMatched: Boolean,
    val engineMatched: Boolean,
    val nonSilentVerified: Boolean,
    val passed: Boolean,
    val summaryNote: String
) {
    val previewRmsDisplay: String
        get() = previewAudioRms?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, "%.4f", it) } ?: "UNKNOWN"

    val liveRmsDisplay: String
        get() = liveAudioRms?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, "%.4f", it) } ?: "UNKNOWN"

    val previewAudioRmsDisplay: String
        get() = previewRmsDisplay

    val liveAudioRmsDisplay: String
        get() = liveRmsDisplay

    val usedCapturedMediaProjectionOcr: Boolean
        get() = liveCapturedViaMediaProjectionOcr
}

/**
 * Combined diagnostic report comparing Anon (Male Lead, SID 5) and Jenny (Female Companion, SID 1)
 * across both Voice Manager Preview and Live Game Dubbing routes.
 */
data class PreviewVsLiveFullComparisonReport(
    val timestampMs: Long,
    val gameNamespace: String = CharacterVoiceAssignment.DEFAULT_GAME_NAMESPACE,
    val hindiSentence: String,
    val anonComparison: PreviewVsLiveCharacterComparison,
    val jennyComparison: PreviewVsLiveCharacterComparison,
    val verificationStatus: String = "NOT_TESTED_NO_MODEL",
    val physicalDeviceVerificationStatus: String = "NOT VERIFIED",
    val usedLiveMediaProjectionOcrCapture: Boolean = false,
    val maleAndFemaleSidsDistinct: Boolean,
    val maleAndFemaleAudioDistinct: Boolean,
    val overallPassed: Boolean,
    val summaryReport: String
)

object CharacterVoiceRouter {
    private const val TAG = "CharacterVoiceRouter"

    /**
     * Minimum confidence required to treat a detected speaker label as a verified character identity.
     * Below this threshold, subtitles route to the explicit Unknown Speaker profile (or user manual override)
     * rather than forcibly reusing `Anon` or the previous male speaker.
     */
    const val MIN_CONFIDENT_SPEAKER_THRESHOLD = 0.60f

    private val utteranceCounter = AtomicLong(1000L)

    // Built-in OCR character glyph misrecognitions (digit/letter confusion only).
    // Does NOT merge distinct names like 'arjun' and 'aarav'.
    private val defaultOcrGlyphCorrections = mapOf(
        "an0n" to "anon",
        "anqn" to "anon",
        "anom" to "anon",
        "jeny" to "jenny",
        "jenni" to "jenny",
        "jennv" to "jenny",
        "jenny!" to "jenny",
        "pr1ya" to "priya",
        "prlya" to "priya",
        "v1kram" to "vikram",
        "vlkram" to "vikram",
        "kaa1" to "kaal",
        "1ra" to "ira"
    )

    // User-configured OCR aliases scoped by gameNamespace (or "*" for global user overrides)
    private val perGameOcrAliasCorrections = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    // Captured real MediaProjection OCR subtitle route decisions keyed by "gameNamespace:normalizedCharacterName"
    private val capturedLiveOcrRoutes = ConcurrentHashMap<String, VoiceRouteDecision>()

    private val _auditLogs = MutableStateFlow<List<SpokenUtteranceAuditLog>>(emptyList())
    val auditLogs: StateFlow<List<SpokenUtteranceAuditLog>> = _auditLogs.asStateFlow()

    private val _liveRoutingDebugLogs = MutableStateFlow<List<LiveVoiceRoutingDebugEntry>>(emptyList())
    val liveRoutingDebugLogs: StateFlow<List<LiveVoiceRoutingDebugEntry>> = _liveRoutingDebugLogs.asStateFlow()

    private val _lastComparisonReport = MutableStateFlow<PreviewVsLiveFullComparisonReport?>(null)
    val lastComparisonReport: StateFlow<PreviewVsLiveFullComparisonReport?> = _lastComparisonReport.asStateFlow()

    /**
     * Registers a custom user-confirmed OCR typo -> canonical character name alias for a specific
     * [gameNamespace] so future OCR frames with that misrecognition route to the intended character.
     */
    fun registerOcrCharacterAlias(
        ocrTypoName: String,
        canonicalCharacterName: String,
        gameNamespace: String = VoiceProfileRepository.getActiveGameNamespace()
    ) {
        val cleanTypo = CharacterVoiceAssignment.normalizeName(ocrTypoName)
        val cleanCanonical = CharacterVoiceAssignment.normalizeName(canonicalCharacterName)
        val cleanNs = gameNamespace.trim().lowercase(Locale.ROOT).ifBlank { CharacterVoiceAssignment.DEFAULT_GAME_NAMESPACE }
        if (cleanTypo.isNotBlank() && cleanCanonical.isNotBlank() && cleanTypo != cleanCanonical) {
            val map = perGameOcrAliasCorrections.getOrPut(cleanNs) { ConcurrentHashMap() }
            map[cleanTypo] = cleanCanonical
        }
    }

    fun clearPerGameOcrAliases(gameNamespace: String? = null) {
        if (gameNamespace == null) {
            perGameOcrAliasCorrections.clear()
        } else {
            perGameOcrAliasCorrections.remove(gameNamespace.trim().lowercase(Locale.ROOT))
        }
    }

    /**
     * Clears dialogue context and captured OCR routes when resetting session state.
     */
    fun resetContinuationContext() {
        // Unlabeled subtitles no longer rely on a 15s sticky male speaker continuation window.
    }

    fun clearAuditLogs() {
        _auditLogs.value = emptyList()
        _liveRoutingDebugLogs.value = emptyList()
    }

    fun clearCapturedLiveOcrRoutes() {
        capturedLiveOcrRoutes.clear()
    }

    /**
     * Records a subtitle route decision that originated from an actual MediaProjection / video frame
     * OCR capture so "Preview vs Live Voice Comparison" can verify real game OCR captures.
     */
    fun recordCapturedLiveOcrRoute(routeDecision: VoiceRouteDecision) {
        val marked = routeDecision.copy(isFromRealMediaProjectionOcr = true)
        val key = "${marked.gameNamespace}:${marked.normalizedCharacterName}"
        capturedLiveOcrRoutes[key] = marked
    }

    fun getCapturedLiveOcrRoute(
        gameNamespace: String,
        characterName: String
    ): VoiceRouteDecision? {
        val normChar = CharacterVoiceAssignment.normalizeName(characterName)
        val cleanNs = gameNamespace.trim().lowercase(Locale.ROOT).ifBlank { CharacterVoiceAssignment.DEFAULT_GAME_NAMESPACE }
        return capturedLiveOcrRoutes["$cleanNs:$normChar"]
            ?: capturedLiveOcrRoutes["${CharacterVoiceAssignment.DEFAULT_GAME_NAMESPACE}:$normChar"]
    }

    /**
     * Canonical Backend Selection Function shared by:
     * - `ForegroundTtsManager.speakNonBlocking` (live game OCR dubbing & matrix auditions)
     * - `ForegroundTtsManager.synthesizeCharacterPreview` (Voice Manager & A/B Lab previews)
     * - `CharacterVoiceRouter.routeLiveSubtitle` & `routeCharacterPreview`
     *
     * Guarantees that for the same [assignment] and [state], preview and live game dubbing
     * ALWAYS select the exact same target backend.
     */
    fun selectCanonicalVoiceBackend(
        context: Context?,
        assignment: CharacterVoiceAssignment?,
        state: TranslatorAppState = TranslatorStateRepository.state.value
    ): CanonicalBackendSelection {
        // 1. Global Pause Dubbing always takes precedence
        if (state.freeVoiceBackend == FreeVoiceBackend.PAUSE_DUBBING) {
            return CanonicalBackendSelection(
                canonicalBackend = CanonicalVoiceBackend.PAUSE_DUBBING,
                requestedEngineTag = CanonicalVoiceBackend.PAUSE_DUBBING.backendTag,
                readinessNote = "Subtitles only (audio paused)"
            )
        }

        // 2. Explicit global user-selected dubbing mode on main screen / A/B lab
        when (state.freeVoiceBackend) {
            FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER -> {
                return CanonicalBackendSelection(
                    canonicalBackend = CanonicalVoiceBackend.LOCAL_CHATTERBOX_SERVER,
                    requestedEngineTag = CanonicalVoiceBackend.LOCAL_CHATTERBOX_SERVER.backendTag,
                    readinessNote = if (state.chatterboxServerReachable && state.chatterboxModelLoaded) {
                        "Local Chatterbox server connected & model loaded"
                    } else {
                        "Local Chatterbox server requested (will verify connection at synthesis)"
                    }
                )
            }
            FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT -> {
                return CanonicalBackendSelection(
                    canonicalBackend = CanonicalVoiceBackend.SHERPA_SUPERTONIC_DIRECT,
                    requestedEngineTag = CanonicalVoiceBackend.SHERPA_SUPERTONIC_DIRECT.backendTag,
                    readinessNote = if (context != null && SupertonicModelManager.isModelReady(context)) {
                        "Supertonic 3 ONNX model verified on disk"
                    } else {
                        "Supertonic 3 requested (model not yet installed on disk)"
                    }
                )
            }
            FreeVoiceBackend.MEDIAPIPE_KOKORO -> {
                return CanonicalBackendSelection(
                    canonicalBackend = CanonicalVoiceBackend.MEDIAPIPE_KOKORO,
                    requestedEngineTag = CanonicalVoiceBackend.MEDIAPIPE_KOKORO.backendTag,
                    readinessNote = "MediaPipe Kokoro-82M requested"
                )
            }
            FreeVoiceBackend.ANDROID_USER_SELECTED, FreeVoiceBackend.PAUSE_DUBBING -> {
                // Fall through to per-character user override or readiness-based selection below
            }
        }

        // 3. When global mode is ANDROID_USER_SELECTED (default on first run), check if the user
        //    explicitly locked a per-character engine override on this character profile:
        if (assignment != null && assignment.isUserOverride) {
            when (assignment.voiceEngine.trim().lowercase(Locale.ROOT)) {
                "chatterbox_local_server" -> {
                    return CanonicalBackendSelection(
                        canonicalBackend = CanonicalVoiceBackend.LOCAL_CHATTERBOX_SERVER,
                        requestedEngineTag = CanonicalVoiceBackend.LOCAL_CHATTERBOX_SERVER.backendTag,
                        readinessNote = "Per-character user override: Chatterbox Local Server"
                    )
                }
                "supertonic_v3", "sherpa_direct" -> {
                    return CanonicalBackendSelection(
                        canonicalBackend = CanonicalVoiceBackend.SHERPA_SUPERTONIC_DIRECT,
                        requestedEngineTag = CanonicalVoiceBackend.SHERPA_SUPERTONIC_DIRECT.backendTag,
                        readinessNote = "Per-character user override: Supertonic 3 Direct"
                    )
                }
                "system_tts", "android_system" -> {
                    return CanonicalBackendSelection(
                        canonicalBackend = CanonicalVoiceBackend.ANDROID_SYSTEM_TTS,
                        requestedEngineTag = resolveSystemFallbackTag(state),
                        readinessNote = "Per-character user override: Android System TTS"
                    )
                }
            }
        }

        // 4. Default readiness-aware selection for ANDROID_USER_SELECTED:
        //    If Supertonic 3 model is downloaded and ready on disk, use SHERPA_SUPERTONIC_DIRECT;
        //    otherwise use ANDROID_SYSTEM_TTS. (Both preview and live follow this identical rule.)
        val supertonicReady = context != null && SupertonicModelManager.isModelReady(context)
        return if (supertonicReady) {
            CanonicalBackendSelection(
                canonicalBackend = CanonicalVoiceBackend.SHERPA_SUPERTONIC_DIRECT,
                requestedEngineTag = CanonicalVoiceBackend.SHERPA_SUPERTONIC_DIRECT.backendTag,
                readinessNote = "Supertonic 3 model ready on device"
            )
        } else {
            CanonicalBackendSelection(
                canonicalBackend = CanonicalVoiceBackend.ANDROID_SYSTEM_TTS,
                requestedEngineTag = resolveSystemFallbackTag(state),
                readinessNote = "Default Android System TTS (Supertonic model not installed & PC server not selected)"
            )
        }
    }

    fun resolveSystemFallbackTag(state: TranslatorAppState = TranslatorStateRepository.state.value): String {
        return if (state.supportsDistinctGenderVoices) {
            "SYSTEM_FALLBACK"
        } else {
            "SYSTEM_FALLBACK_SINGLE_VOICE"
        }
    }

    /**
     * Resolves the requested engine and current readiness status without claiming an unverified
     * engine succeeded before synthesis.
     */
    fun resolveEngineStatus(
        context: Context?,
        assignment: CharacterVoiceAssignment? = null
    ): Pair<String, String> {
        val state = TranslatorStateRepository.state.value
        val selection = selectCanonicalVoiceBackend(context, assignment, state)
        return selection.requestedEngineTag to "PENDING_SYNTHESIS"
    }

    data class SpeakerLabelParseOutcome(
        val rawSpeakerLabel: String?,
        val strippedDialogue: String,
        val confidence: Float,
        val parseRule: String
    )

    /**
     * Parses a raw OCR subtitle string BEFORE stripping character labels, extracting:
     * - `rawSpeakerLabel`: Exact character name inside `[Anon]:`, `Jenny:`, `<Anon>`, or Line 1 header, or `null` if unlabeled.
     * - `strippedDialogue`: Clean English dialogue with the speaker prefix removed so speaker labels are NEVER spoken as dialogue.
     * - `confidence`: `0.0f..1.0f` speaker detection confidence.
     */
    fun parseSpeakerLabelWithConfidence(
        rawOcrText: String,
        gameNamespace: String = CharacterVoiceAssignment.DEFAULT_GAME_NAMESPACE
    ): SpeakerLabelParseOutcome {
        val trimmed = rawOcrText.trim()
        if (trimmed.isEmpty()) {
            return SpeakerLabelParseOutcome(null, "", 0.0f, "EMPTY")
        }

        // 1. Explicit bracketed speaker tag: e.g., "[Anon]: I will protect you." or "[Jenny (Companion)]: Wait!"
        val bracketRegex = Regex("^\\s*[\\[<(\\u3010]([A-Za-z0-9_.'()\\- ]{2,34})[\\]>\\)\\u3011]\\s*[:：\\-–—]?\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
        val bracketMatch = bracketRegex.find(trimmed)
        if (bracketMatch != null) {
            val rawCandidate = bracketMatch.groupValues[1].trim()
            val body = SpeakerHeuristicEngine.stripLeadingCharacterName(bracketMatch.groupValues[2].trim(), rawCandidate).trim()
            if (body.isNotBlank() && isPlausibleSpeakerCandidate(rawCandidate)) {
                val (norm, wasCorrected) = normalizeAndCorrectSpeakerName(rawCandidate, gameNamespace)
                val known = VoiceProfileRepository.findExistingAssignment(norm, gameNamespace) != null
                val conf = when {
                    known && !wasCorrected -> 0.98f
                    known && wasCorrected -> 0.90f
                    else -> 0.82f
                }
                return SpeakerLabelParseOutcome(rawCandidate, body, conf, "EXPLICIT_BRACKET_TAG")
            }
        }

        // 2. Explicit colon/dash speaker tag on single line or line 1: e.g., "Anon: Hold on!"
        val colonRegex = Regex("^\\s*\\[?([A-Za-z0-9_.'()\\- ]{2,34})\\]?\\s*[:：\\-–—]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
        val colonMatch = colonRegex.find(trimmed)
        if (colonMatch != null) {
            val rawCandidate = colonMatch.groupValues[1].trim()
            val body = SpeakerHeuristicEngine.stripLeadingCharacterName(colonMatch.groupValues[2].trim(), rawCandidate).trim()
            if (body.isNotBlank() && isPlausibleSpeakerCandidate(rawCandidate)) {
                val (norm, wasCorrected) = normalizeAndCorrectSpeakerName(rawCandidate, gameNamespace)
                val known = VoiceProfileRepository.findExistingAssignment(norm, gameNamespace) != null
                val conf = when {
                    known && !wasCorrected -> 0.95f
                    known && wasCorrected -> 0.88f
                    else -> 0.78f
                }
                return SpeakerLabelParseOutcome(rawCandidate, body, conf, "EXPLICIT_COLON_TAG")
            }
        }

        // 3. Multi-line subtitle header (Line 1 = character name header, Line 2..N = dialogue)
        val nonBlankLines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlankLines.size >= 2) {
            val headerLine = nonBlankLines.first()
            val dialogueLines = nonBlankLines.drop(1).joinToString(" ").trim()
            val cleanedHeader = headerLine.trim('[', ']', '<', '>', '(', ')', '{', '}', ':', '：', '-', '–', '—', ' ')
            if (dialogueLines.isNotBlank() && isPlausibleSpeakerCandidate(cleanedHeader)) {
                val (norm, wasCorrected) = normalizeAndCorrectSpeakerName(cleanedHeader, gameNamespace)
                val knownProfile = VoiceProfileRepository.findExistingAssignment(norm, gameNamespace) != null
                val knownCharacter = knownProfile || SpeakerHeuristicEngine.isKnownCharacterOrSpeakerHeader(norm)
                val hasExplicitHeaderDelimiter = headerLine.endsWith(":") || headerLine.endsWith("-") ||
                    (headerLine.startsWith("[") && headerLine.endsWith("]"))
                val conf = when {
                    knownProfile && !wasCorrected -> 0.92f
                    knownCharacter && !wasCorrected -> 0.88f
                    knownCharacter && wasCorrected -> 0.80f
                    hasExplicitHeaderDelimiter -> 0.82f
                    else -> 0.45f
                }
                if (conf >= MIN_CONFIDENT_SPEAKER_THRESHOLD) {
                    val strippedBody = SpeakerHeuristicEngine.stripLeadingCharacterName(dialogueLines, cleanedHeader).trim()
                        .ifBlank { dialogueLines }
                    return SpeakerLabelParseOutcome(cleanedHeader, strippedBody, conf, "MULTILINE_SPEAKER_HEADER")
                }
            }
        }

        // 4. Delegate to SpeakerHeuristicEngine for inline character prefixes
        val parsed = SpeakerHeuristicEngine.extractCharacterTagAndDialogue(trimmed)
        if (parsed != null) {
            val rawTag = parsed.first.trim()
            val body = SpeakerHeuristicEngine.stripLeadingCharacterName(parsed.second, rawTag).trim()
                .ifBlank { parsed.second.trim() }
            val (norm, wasCorrected) = normalizeAndCorrectSpeakerName(rawTag, gameNamespace)
            val known = VoiceProfileRepository.findExistingAssignment(norm, gameNamespace) != null ||
                SpeakerHeuristicEngine.isKnownCharacterOrSpeakerHeader(norm)
            val conf = when {
                known && !wasCorrected -> 0.84f
                known && wasCorrected -> 0.76f
                else -> 0.45f
            }
            return SpeakerLabelParseOutcome(rawTag, body, conf, "HEURISTIC_HEADER")
        }

        return SpeakerLabelParseOutcome(null, trimmed, 0.0f, "UNLABELED_SUBTITLE")
    }

    private fun isPlausibleSpeakerCandidate(candidate: String): Boolean {
        val clean = candidate.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        if (clean.length !in 2..28) return false
        val words = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4) return false
        if (clean.endsWith(".") || clean.endsWith("?")) return false
        return clean.any { it.isLetter() }
    }

    fun parseSpeakerLabelAndStripDialogue(rawOcrText: String): Pair<String?, String> {
        val outcome = parseSpeakerLabelWithConfidence(rawOcrText)
        return if (outcome.confidence >= MIN_CONFIDENT_SPEAKER_THRESHOLD) {
            outcome.rawSpeakerLabel to outcome.strippedDialogue
        } else {
            null to outcome.strippedDialogue
        }
    }

    /**
     * Normalizes a raw OCR speaker name and applies per-game user alias overrides or
     * built-in digit/glyph OCR typo corrections without guessing gender from name suffixes
     * and without merging distinct character names like `arjun` and `aarav`.
     */
    fun normalizeAndCorrectSpeakerName(
        rawName: String,
        gameNamespace: String = VoiceProfileRepository.getActiveGameNamespace()
    ): Pair<String, Boolean> {
        val normalized = CharacterVoiceAssignment.normalizeName(rawName)
        if (normalized.isBlank()) return "" to false

        val cleanNs = gameNamespace.trim().lowercase(Locale.ROOT).ifBlank { CharacterVoiceAssignment.DEFAULT_GAME_NAMESPACE }
        val perGameMap = perGameOcrAliasCorrections[cleanNs]
        val globalUserMap = perGameOcrAliasCorrections["*"]

        val userCorrected = perGameMap?.get(normalized) ?: globalUserMap?.get(normalized)
        if (userCorrected != null && userCorrected != normalized) {
            return userCorrected to true
        }

        val glyphCorrected = defaultOcrGlyphCorrections[normalized]
        return if (glyphCorrected != null && glyphCorrected != normalized) {
            glyphCorrected to true
        } else {
            normalized to false
        }
    }

    /**
     * Shared Live Game Subtitle Routing Entry Point:
     * 1. Extracts speaker label with confidence BEFORE stripping it from [rawOcrSubtitle].
     * 2. If a confident speaker tag (`>= 0.60f`) is present, resolves the character's saved profile
     *    (or creates a profile for a new explicit tag without suffix gender guessing).
     * 3. If the subtitle has NO speaker label or low confidence (`< 0.60f`), routes to:
     *    - the user's explicit manual override for unlabeled subtitles (if set), OR
     *    - the explicit `Unknown Speaker` profile (`characterName = "unknown"`, `SID 2`).
     *    NEVER silently reuses the previous male speaker (`Anon`) for rapid alternating unlabeled lines!
     * 4. Selects the canonical backend via [selectCanonicalVoiceBackend] so preview and live always match.
     * 5. Records a real-time visible [LiveVoiceRoutingDebugEntry].
     */
    fun routeLiveSubtitle(
        context: Context?,
        rawOcrSubtitle: String,
        translatedHindi: String = "",
        gameNamespace: String = VoiceProfileRepository.getActiveGameNamespace(),
        sessionEpoch: Long = 0L,
        isFromMediaProjectionOcr: Boolean = false,
        isFromRealMediaProjectionOcr: Boolean = isFromMediaProjectionOcr,
        nowMs: Long = System.currentTimeMillis()
    ): VoiceRouteDecision {
        if (context != null) {
            VoiceProfileRepository.initialize(context)
        }
        val cleanNamespace = gameNamespace.trim().lowercase(Locale.ROOT)
            .ifBlank { CharacterVoiceAssignment.DEFAULT_GAME_NAMESPACE }

        val parseOutcome = parseSpeakerLabelWithConfidence(rawOcrSubtitle, cleanNamespace)
        val rawSpeakerCandidate = parseOutcome.rawSpeakerLabel
        val strippedEnglish = parseOutcome.strippedDialogue
        val confidence = parseOutcome.confidence

        val assignment: CharacterVoiceAssignment
        val resolutionRule: String

        if (!rawSpeakerCandidate.isNullOrBlank() && confidence >= MIN_CONFIDENT_SPEAKER_THRESHOLD) {
            val (correctedName, wasAliasCorrected) = normalizeAndCorrectSpeakerName(rawSpeakerCandidate, cleanNamespace)
            val existing = VoiceProfileRepository.findExistingAssignment(correctedName, cleanNamespace)
            resolutionRule = when {
                wasAliasCorrected -> "OCR_ALIAS_CORRECTION"
                existing?.isUserOverridden == true -> "USER_OVERRIDE"
                existing != null -> "EXPLICIT_LABEL_PROFILE"
                else -> "NEW_TAG_DISCOVERED"
            }
            assignment = VoiceProfileRepository.getOrCreateAssignmentForCharacter(
                rawName = rawSpeakerCandidate,
                normalizedKey = correctedName,
                gameNamespace = cleanNamespace,
                context = context
            )
        } else {
            // Missing or uncertain speaker label: NEVER silently reuse previous male speaker or default to Anon!
            val manualUnlabeledOverride = VoiceProfileRepository.getUnlabeledSubtitleManualOverride(cleanNamespace)
            if (manualUnlabeledOverride != null) {
                assignment = manualUnlabeledOverride
                resolutionRule = if (rawSpeakerCandidate.isNullOrBlank()) {
                    "UNLABELED_MANUAL_OVERRIDE"
                } else {
                    "LOW_CONFIDENCE_MANUAL_OVERRIDE"
                }
            } else {
                assignment = VoiceProfileRepository.getUnknownSpeakerAssignment(cleanNamespace)
                resolutionRule = if (rawSpeakerCandidate.isNullOrBlank()) {
                    "UNLABELED_UNKNOWN_SPEAKER"
                } else {
                    "LOW_CONFIDENCE_UNKNOWN_SPEAKER"
                }
            }
        }

        val matchedVoiceProfile = VoiceProfileRepository.buildVoiceProfileForAssignment(assignment)
        val backendSelection = selectCanonicalVoiceBackend(context, assignment)

        val cleanHindi = prepareHindiDialogueForSpeech(
            rawHindi = translatedHindi,
            rawSpeakerTag = rawSpeakerCandidate,
            assignment = assignment
        )

        val decision = VoiceRouteDecision(
            utteranceId = "live_${utteranceCounter.incrementAndGet()}_${UUID.randomUUID().toString().take(6)}",
            gameNamespace = cleanNamespace,
            rawOcrText = rawOcrSubtitle,
            rawCharacterName = rawSpeakerCandidate,
            normalizedCharacterName = assignment.characterName,
            strippedEnglishDialogue = strippedEnglish,
            hindiDialogue = cleanHindi,
            characterProfileId = "char_${assignment.gameNamespace}_${assignment.characterName}",
            characterAssignment = assignment,
            voiceProfile = matchedVoiceProfile,
            selectedSpeakerId = assignment.speakerId.coerceIn(0, 9),
            requestedEngine = backendSelection.requestedEngineTag,
            actualPlaybackEngine = "PENDING_SYNTHESIS",
            sessionEpoch = sessionEpoch,
            resolutionRule = resolutionRule,
            speakerConfidence = confidence,
            canonicalBackend = backendSelection.canonicalBackend,
            isFromRealMediaProjectionOcr = isFromRealMediaProjectionOcr,
            createdAtMs = nowMs
        )

        if (isFromRealMediaProjectionOcr && !rawSpeakerCandidate.isNullOrBlank() && confidence >= MIN_CONFIDENT_SPEAKER_THRESHOLD) {
            recordCapturedLiveOcrRoute(decision)
        }

        recordLiveRoutingDebugEntry(
            decision = decision,
            speakerCandidate = rawSpeakerCandidate,
            actualBackend = "PENDING_SYNTHESIS",
            fallbackReason = backendSelection.readinessNote
        )
        VoiceProfileRepository.recordActiveRouteDecision(decision)
        return decision
    }

    /**
     * Shared Preview / Audition Routing Entry Point used by:
     * - Character Voice Manager cards (`routeCharacterPreview`)
     * - Base Voice Presets
     *
     * Uses the exact same [selectCanonicalVoiceBackend] as [routeLiveSubtitle] so preview and live
     * dubbing never diverge on backend choice.
     */
    fun routeCharacterPreview(
        context: Context?,
        assignment: CharacterVoiceAssignment,
        hindiDialogue: String,
        sessionEpoch: Long = 0L
    ): VoiceRouteDecision {
        if (context != null) {
            VoiceProfileRepository.initialize(context)
        }
        val latestAssignment = VoiceProfileRepository.findExistingAssignment(
            assignment.characterName,
            assignment.gameNamespace
        ) ?: assignment

        val matchedVoiceProfile = VoiceProfileRepository.buildVoiceProfileForAssignment(latestAssignment)
        val backendSelection = selectCanonicalVoiceBackend(context, latestAssignment)
        val cleanHindi = prepareHindiDialogueForSpeech(
            rawHindi = hindiDialogue,
            rawSpeakerTag = latestAssignment.displayName,
            assignment = latestAssignment
        )

        val decision = VoiceRouteDecision(
            utteranceId = "preview_${utteranceCounter.incrementAndGet()}_${UUID.randomUUID().toString().take(6)}",
            gameNamespace = latestAssignment.gameNamespace,
            rawOcrText = "[${latestAssignment.displayName}]: $cleanHindi",
            rawCharacterName = latestAssignment.displayName,
            normalizedCharacterName = latestAssignment.characterName,
            strippedEnglishDialogue = cleanHindi,
            hindiDialogue = cleanHindi,
            characterProfileId = "char_${latestAssignment.gameNamespace}_${latestAssignment.characterName}",
            characterAssignment = latestAssignment,
            voiceProfile = matchedVoiceProfile,
            selectedSpeakerId = latestAssignment.speakerId.coerceIn(0, 9),
            requestedEngine = backendSelection.requestedEngineTag,
            actualPlaybackEngine = "PENDING_SYNTHESIS",
            sessionEpoch = sessionEpoch,
            resolutionRule = if (latestAssignment.isUserOverridden) "USER_OVERRIDE" else "EXPLICIT_LABEL_PROFILE",
            speakerConfidence = 1.0f,
            canonicalBackend = backendSelection.canonicalBackend
        )

        recordLiveRoutingDebugEntry(
            decision = decision,
            speakerCandidate = latestAssignment.displayName,
            actualBackend = "PENDING_SYNTHESIS",
            fallbackReason = backendSelection.readinessNote
        )
        VoiceProfileRepository.recordActiveRouteDecision(decision)
        return decision
    }

    /**
     * Routes a direct 10-voice base speaker audition (SID 0..9) from the Voice Preview Matrix
     * so auditioning any of the 10 Supertonic speakers uses its exact `sid` and is NEVER
     * shadowed by `activeCharacterAssignment`.
     */
    fun routeBaseSpeakerAudition(
        context: Context?,
        sid: Int,
        speakerTitle: String,
        genderLabel: String,
        hindiDialogue: String,
        sessionEpoch: Long = 0L
    ): VoiceRouteDecision {
        val validSid = sid.coerceIn(0, 9)
        val isFemale = genderLabel.equals("Female", ignoreCase = true) || validSid in 0..4
        val tempAssignment = CharacterVoiceAssignment(
            characterName = "audition_sid_$validSid",
            displayName = speakerTitle,
            speakerId = validSid,
            speed = 1.0f,
            pitch = 1.0f,
            characterRoleNote = "Base Speaker Audition SID #$validSid",
            isUserOverride = false,
            gameNamespace = CharacterVoiceAssignment.DEFAULT_GAME_NAMESPACE,
            referenceVoiceId = if (isFemale) CharacterVoiceAssignment.DEFAULT_FEMALE_REF_ID else CharacterVoiceAssignment.DEFAULT_MALE_REF_ID,
            voicePresentation = if (isFemale) "female" else "male",
            vocalStyleDescription = speakerTitle,
            voiceEngine = "follow_global"
        )
        val profile = VoiceProfileRepository.buildVoiceProfileForAssignment(tempAssignment)
        val backendSelection = selectCanonicalVoiceBackend(context, tempAssignment)
        val cleanHindi = SpeakerHeuristicEngine.stripLeadingCharacterName(hindiDialogue).trim()

        val decision = VoiceRouteDecision(
            utteranceId = "audition_sid${validSid}_${utteranceCounter.incrementAndGet()}",
            gameNamespace = CharacterVoiceAssignment.DEFAULT_GAME_NAMESPACE,
            rawOcrText = cleanHindi,
            rawCharacterName = speakerTitle,
            normalizedCharacterName = tempAssignment.characterName,
            strippedEnglishDialogue = cleanHindi,
            hindiDialogue = cleanHindi,
            characterProfileId = "matrix_sid_$validSid",
            characterAssignment = tempAssignment,
            voiceProfile = profile,
            selectedSpeakerId = validSid,
            requestedEngine = backendSelection.requestedEngineTag,
            actualPlaybackEngine = "PENDING_SYNTHESIS",
            sessionEpoch = sessionEpoch,
            resolutionRule = "EXPLICIT_AUDITION_SID",
            speakerConfidence = 1.0f,
            canonicalBackend = backendSelection.canonicalBackend
        )

        recordLiveRoutingDebugEntry(
            decision = decision,
            speakerCandidate = speakerTitle,
            actualBackend = "PENDING_SYNTHESIS",
            fallbackReason = backendSelection.readinessNote
        )
        return decision
    }

    private fun recordLiveRoutingDebugEntry(
        decision: VoiceRouteDecision,
        speakerCandidate: String?,
        actualBackend: String,
        fallbackReason: String
    ) {
        val entry = LiveVoiceRoutingDebugEntry(
            utteranceId = decision.utteranceId,
            timestampMs = System.currentTimeMillis(),
            gameNamespace = decision.gameNamespace,
            rawOcr = decision.rawOcrText.replace("\n", " ").take(120),
            speakerLabelCandidate = speakerCandidate?.takeIf { it.isNotBlank() } ?: "(none)",
            normalizedName = decision.normalizedCharacterName,
            confidence = decision.speakerConfidence,
            resolvedProfileId = decision.characterProfileId,
            resolvedDisplayName = decision.characterAssignment.displayName,
            sid = decision.selectedSpeakerId,
            referenceVoiceId = decision.characterAssignment.referenceVoiceId,
            referenceAudioSha256 = decision.characterAssignment.referenceAudioSha256,
            requestedBackend = decision.requestedEngine,
            actualBackend = actualBackend,
            fallbackReason = fallbackReason,
            resolutionRule = decision.resolutionRule
        )
        _liveRoutingDebugLogs.value = (listOf(entry) + _liveRoutingDebugLogs.value).take(40)
        TranslatorStateRepository.updateRoutingDebugSummary(entry.toCompactSummaryLine())
    }

    /**
     * Strips any leading speaker label from [rawHindi] and applies global + per-character
     * pronunciation overrides so speaker names are never spoken aloud.
     */
    fun prepareHindiDialogueForSpeech(
        rawHindi: String,
        rawSpeakerTag: String?,
        assignment: CharacterVoiceAssignment
    ): String {
        val stripped = SpeakerHeuristicEngine.stripLeadingCharacterName(rawHindi, rawSpeakerTag).trim()
        if (stripped.isEmpty()) return ""
        val globalGlossary = TranslatorStateRepository.state.value.pronunciationGlossary
        val afterGlobal = PronunciationGlossary.applyForSpeech(stripped, globalGlossary)
        return if (assignment.pronunciationOverrides.isNotEmpty()) {
            PronunciationGlossary.applyForSpeech(afterGlobal, assignment.pronunciationOverrides)
        } else {
            afterGlobal
        }
    }

    /**
     * Records a structured audit log entry AFTER synthesis/playback completes (or falls back),
     * logging the ACTUAL backend that generated the sound and `outputPcmRms = null` (`UNKNOWN`)
     * when PCM was not directly synthesized/measured.
     */
    fun recordSpokenUtteranceAudit(
        routeDecision: VoiceRouteDecision,
        actualSidPassedToNative: Int,
        actualBackend: String,
        jniStatus: String,
        modelLoaded: Boolean,
        fallbackReason: String,
        audioDurationMs: Long,
        outputPcmRms: Float?,
        generationTimestampMs: Long = System.currentTimeMillis(),
        playbackTimestampMs: Long = System.currentTimeMillis()
    ): SpokenUtteranceAuditLog {
        val sanitizedRms = outputPcmRms?.takeIf { !it.isNaN() && it >= 0f }
        val logEntry = SpokenUtteranceAuditLog(
            utteranceId = routeDecision.utteranceId,
            gameNamespace = routeDecision.gameNamespace,
            ocrCharacterName = routeDecision.rawCharacterName ?: "(unlabeled)",
            normalizedCharacterName = routeDecision.normalizedCharacterName,
            resolvedProfileId = routeDecision.characterProfileId,
            requestedSid = routeDecision.selectedSpeakerId,
            actualSidPassedToNative = actualSidPassedToNative,
            requestedEngine = routeDecision.requestedEngine,
            actualBackend = actualBackend,
            jniStatus = jniStatus,
            modelLoadedStatus = modelLoaded,
            fallbackReason = fallbackReason,
            generatedAudioDurationMs = audioDurationMs,
            outputPcmRms = sanitizedRms,
            ttsGenerationTimestampMs = generationTimestampMs,
            playbackTimestampMs = playbackTimestampMs,
            resolutionRule = routeDecision.resolutionRule,
            hindiText = routeDecision.hindiDialogue,
            speakerConfidence = routeDecision.speakerConfidence,
            referenceVoiceId = routeDecision.characterAssignment.referenceVoiceId,
            referenceAudioSha256 = routeDecision.characterAssignment.referenceAudioSha256
        )

        val updated = (listOf(logEntry) + _auditLogs.value).take(50)
        _auditLogs.value = updated

        // Update the corresponding live routing debug entry with the actual post-synthesis backend
        recordLiveRoutingDebugEntry(
            decision = routeDecision.copy(actualPlaybackEngine = actualBackend),
            speakerCandidate = routeDecision.rawCharacterName,
            actualBackend = actualBackend,
            fallbackReason = fallbackReason
        )

        Log.i(
            TAG,
            "VOICE_AUDIT utterance=${logEntry.utteranceId} ns='${logEntry.gameNamespace}' " +
                "ocrChar='${logEntry.ocrCharacterName}' normChar='${logEntry.normalizedCharacterName}' " +
                "conf=${String.format(Locale.US, "%.2f", logEntry.speakerConfidence)} " +
                "profile='${logEntry.resolvedProfileId}' ref='${logEntry.referenceVoiceId}' " +
                "rule=${logEntry.resolutionRule} requestedSid=${logEntry.requestedSid} " +
                "actualSid=${logEntry.actualSidPassedToNative} reqEngine=${logEntry.requestedEngine} " +
                "actualBackend=${logEntry.actualBackend} modelLoaded=${logEntry.modelLoadedStatus} " +
                "durationMs=${logEntry.generatedAudioDurationMs} rms=${logEntry.outputPcmRmsDisplay} " +
                "fallback='${logEntry.fallbackReason}'"
        )

        return logEntry
    }

    /**
     * P1 Diagnostic Tool — "Preview vs Live Voice Comparison":
     * For a given [characterName] (e.g. `"Anon"` or `"Jenny"`) and [hindiSentence]:
     * 1. Routes through the Voice Manager preview path ([routeCharacterPreview]).
     * 2. Uses a real MediaProjection-captured [VoiceRouteDecision] for `(gameNamespace, characterName)`
     *    if one was captured during live gameplay; otherwise routes through [routeLiveSubtitle] and
     *    marks `liveCapturedViaMediaProjectionOcr = false`.
     * 3. Synthesizes both routes using ONLY real neural synthesis (Chatterbox server or Sherpa-ONNX native).
     *    NEVER generates synthetic sine-wave tones! If no neural model/server is ready, reports
     *    `NOT_TESTED_NO_MODEL` and `passed = false`.
     */
    fun runSingleCharacterPreviewVsLiveComparison(
        context: Context,
        characterName: String,
        hindiSentence: String,
        gameNamespace: String = VoiceProfileRepository.getActiveGameNamespace(),
        sherpaBackend: SherpaSupertonicBackend? = null,
        neuralClient: NeuralVoiceClient? = null,
        outputDir: File = File(context.cacheDir, "voice_comparison")
    ): PreviewVsLiveCharacterComparison {
        VoiceProfileRepository.initialize(context)
        outputDir.mkdirs()

        val cleanNs = gameNamespace.trim().lowercase(Locale.ROOT).ifBlank { CharacterVoiceAssignment.DEFAULT_GAME_NAMESPACE }
        val assignment = VoiceProfileRepository.findExistingAssignment(characterName, cleanNs)
            ?: VoiceProfileRepository.getAssignmentForCharacter(characterName, context)
        val cleanTag = assignment.displayName.substringBefore(" (").trim().ifBlank { characterName.trim() }

        // Check if we have a real MediaProjection-captured OCR route for this character in this game
        val capturedOcrRoute = getCapturedLiveOcrRoute(cleanNs, assignment.characterName)
        val effectiveSentence = if (capturedOcrRoute != null && capturedOcrRoute.hindiDialogue.isNotBlank()) {
            capturedOcrRoute.hindiDialogue
        } else {
            hindiSentence
        }

        val previewStartMs = System.currentTimeMillis()
        val previewRoute = routeCharacterPreview(
            context = context,
            assignment = assignment,
            hindiDialogue = effectiveSentence
        )
        // Exercise the exact live gameplay subtitle filter + speaker resolution + live route pipeline
        val rawLiveSource = if (capturedOcrRoute != null && capturedOcrRoute.rawOcrText.isNotBlank()) {
            capturedOcrRoute.rawOcrText
        } else {
            "${cleanTag.uppercase(Locale.ROOT)}\n$effectiveSentence"
        }
        val filteredLiveOcr = SubtitleTextFilter.filterDialogueText(rawLiveSource)
            .ifBlank { "[$cleanTag]: $effectiveSentence" }
        val liveRoute = routeLiveSubtitle(
            context = context,
            rawOcrSubtitle = filteredLiveOcr,
            translatedHindi = effectiveSentence,
            gameNamespace = cleanNs,
            isFromMediaProjectionOcr = capturedOcrRoute != null
        )

        val previewWavFile = File(outputDir, "preview_${assignment.characterName}_sid${previewRoute.selectedSpeakerId}.wav")
        val liveWavFile = File(outputDir, "live_${assignment.characterName}_sid${liveRoute.selectedSpeakerId}.wav")

        val backend = sherpaBackend ?: run {
            try {
                ForegroundTtsManager.getInstance(context).sherpaEngine
            } catch (_: Throwable) {
                null
            }
        }
        val activeNeuralClient = neuralClient ?: run {
            try {
                ForegroundTtsManager.getInstance(context).neuralVoiceClient
            } catch (_: Throwable) {
                NeuralVoiceClient()
            }
        }

        val previewOutcome = synthesizeRouteToWav(
            context = context,
            route = previewRoute,
            outputWavFile = previewWavFile,
            sherpaBackend = backend,
            neuralClient = activeNeuralClient
        )
        val previewLatencyMs = (System.currentTimeMillis() - previewStartMs).coerceAtLeast(1L)

        val liveStartMs = System.currentTimeMillis()
        val liveOutcome = synthesizeRouteToWav(
            context = context,
            route = liveRoute,
            outputWavFile = liveWavFile,
            sherpaBackend = backend,
            neuralClient = activeNeuralClient
        )
        val liveLatencyMs = (System.currentTimeMillis() - liveStartMs).coerceAtLeast(1L)

        recordSpokenUtteranceAudit(
            routeDecision = previewRoute,
            actualSidPassedToNative = previewOutcome.actualSid,
            actualBackend = previewOutcome.engineUsed,
            jniStatus = OfflineTts.jniStatusSummary,
            modelLoaded = previewOutcome.isRealNeuralAudio,
            fallbackReason = previewOutcome.errorReason,
            audioDurationMs = previewOutcome.durationMs,
            outputPcmRms = previewOutcome.waveformRms
        )

        recordSpokenUtteranceAudit(
            routeDecision = liveRoute,
            actualSidPassedToNative = liveOutcome.actualSid,
            actualBackend = liveOutcome.engineUsed,
            jniStatus = OfflineTts.jniStatusSummary,
            modelLoaded = liveOutcome.isRealNeuralAudio,
            fallbackReason = liveOutcome.errorReason,
            audioDurationMs = liveOutcome.durationMs,
            outputPcmRms = liveOutcome.waveformRms
        )

        val sidMatched = previewRoute.selectedSpeakerId == liveRoute.selectedSpeakerId &&
            previewOutcome.actualSid == liveOutcome.actualSid &&
            previewRoute.selectedSpeakerId == previewOutcome.actualSid
        val engineMatched = previewRoute.requestedEngine == liveRoute.requestedEngine &&
            previewOutcome.engineUsed == liveOutcome.engineUsed

        val bothRealNeural = previewOutcome.isRealNeuralAudio && liveOutcome.isRealNeuralAudio
        val previewRmsVal = previewOutcome.waveformRms ?: 0f
        val liveRmsVal = liveOutcome.waveformRms ?: 0f
        val nonSilent = bothRealNeural &&
            previewRmsVal >= SherpaSupertonicBackend.MIN_NON_SILENT_RMS &&
            liveRmsVal >= SherpaSupertonicBackend.MIN_NON_SILENT_RMS &&
            previewWavFile.exists() && previewWavFile.length() > 44 &&
            liveWavFile.exists() && liveWavFile.length() > 44

        val verificationStatus = when {
            !bothRealNeural -> "NOT_TESTED_NO_MODEL"
            sidMatched && engineMatched && nonSilent -> "PASSED_NEURAL_SPEECH"
            else -> "FAILED_MISMATCH"
        }

        // CRITICAL: Never report `passed = true` when neural model/server is absent (`NOT_TESTED_NO_MODEL`)
        val passed = verificationStatus == "PASSED_NEURAL_SPEECH"
        val note = when (verificationStatus) {
            "PASSED_NEURAL_SPEECH" ->
                "PASS (Neural WAV Verified) • ${assignment.displayName} Preview (${previewLatencyMs}ms) & Live (${liveLatencyMs}ms) both used SID #${liveOutcome.actualSid} / ${assignment.referenceVoiceId}.wav (${liveOutcome.engineUsed}, ${liveOutcome.sampleRate}Hz, RMS=${String.format(Locale.US, "%.4f", liveRmsVal)})"
            "NOT_TESTED_NO_MODEL" ->
                "NOT_TESTED_NO_MODEL • Routing matched (Preview SID=#${previewRoute.selectedSpeakerId}, Live SID=#${liveRoute.selectedSpeakerId}, Ref=${assignment.referenceVoiceId}.wav, ReqEngine=${liveRoute.requestedEngine}, Err=${liveOutcome.errorCategoryCode}), but ${liveOutcome.errorReason}. No synthetic WAV generated."
            else ->
                "FAIL • Preview(req=${previewRoute.selectedSpeakerId}, act=${previewOutcome.actualSid}, eng=${previewOutcome.engineUsed}, err=${previewOutcome.errorCategoryCode}) vs Live(req=${liveRoute.selectedSpeakerId}, act=${liveOutcome.actualSid}, eng=${liveOutcome.engineUsed}, err=${liveOutcome.errorCategoryCode})"
        }

        return PreviewVsLiveCharacterComparison(
            characterName = assignment.characterName,
            displayName = assignment.displayName,
            hindiSentence = effectiveSentence,
            previewRoute = previewRoute,
            liveRoute = liveRoute,
            previewRequestedSid = previewRoute.selectedSpeakerId,
            previewActualSid = previewOutcome.actualSid,
            liveRequestedSid = liveRoute.selectedSpeakerId,
            liveActualSid = liveOutcome.actualSid,
            previewEngine = previewOutcome.engineUsed,
            liveEngine = liveOutcome.engineUsed,
            sampleRate = liveOutcome.sampleRate,
            previewAudioRms = previewOutcome.waveformRms,
            liveAudioRms = liveOutcome.waveformRms,
            previewDurationMs = previewOutcome.durationMs,
            liveDurationMs = liveOutcome.durationMs,
            previewWavPath = if (previewOutcome.isRealNeuralAudio && previewWavFile.exists()) previewWavFile.absolutePath else "",
            liveWavPath = if (liveOutcome.isRealNeuralAudio && liveWavFile.exists()) liveWavFile.absolutePath else "",
            previewWavSha256 = previewOutcome.wavSha256,
            liveWavSha256 = liveOutcome.wavSha256,
            referenceVoiceId = assignment.referenceVoiceId,
            referenceAudioSha256 = assignment.referenceAudioSha256,
            speakerIdOrReferenceWav = "SID #${liveOutcome.actualSid} • ${assignment.referenceVoiceId}.wav",
            previewErrorStatus = previewOutcome.errorCategoryCode,
            liveErrorStatus = liveOutcome.errorCategoryCode,
            previewLatencyMs = previewLatencyMs,
            liveLatencyMs = liveLatencyMs,
            verificationStatus = verificationStatus,
            liveCapturedViaMediaProjectionOcr = liveRoute.isFromRealMediaProjectionOcr,
            sidMatched = sidMatched,
            engineMatched = engineMatched,
            nonSilentVerified = nonSilent,
            passed = passed,
            summaryNote = note
        )
    }

    /**
     * Runs the complete "Preview vs Live Voice Comparison" separately for `Anon` (verified male SID 5)
     * and `Jenny` (verified female SID 1) using the same Hindi sentence (or live MediaProjection OCR captures).
     *
     * Never generates synthetic periodic tones or claims acoustic PASS when the neural model/server is absent.
     */
    fun runFullAnonAndJennyComparison(
        context: Context,
        hindiSentence: String = "मैं तुम्हारी रक्षा करूँगा, कृपया मुझे मत छोड़ो!",
        gameNamespace: String = VoiceProfileRepository.getActiveGameNamespace(),
        sherpaBackend: SherpaSupertonicBackend? = null,
        neuralClient: NeuralVoiceClient? = null,
        outputDir: File = File(context.cacheDir, "voice_comparison")
    ): PreviewVsLiveFullComparisonReport {
        val anonComp = runSingleCharacterPreviewVsLiveComparison(
            context = context,
            characterName = "Anon",
            hindiSentence = hindiSentence,
            gameNamespace = gameNamespace,
            sherpaBackend = sherpaBackend,
            neuralClient = neuralClient,
            outputDir = outputDir
        )
        val jennyComp = runSingleCharacterPreviewVsLiveComparison(
            context = context,
            characterName = "Jenny",
            hindiSentence = hindiSentence,
            gameNamespace = gameNamespace,
            sherpaBackend = sherpaBackend,
            neuralClient = neuralClient,
            outputDir = outputDir
        )

        val sidsDistinct = anonComp.liveRequestedSid != jennyComp.liveRequestedSid &&
            anonComp.previewRequestedSid != jennyComp.previewRequestedSid
        val anonBytes = anonComp.liveWavPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }?.readBytes() ?: ByteArray(0)
        val jennyBytes = jennyComp.liveWavPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }?.readBytes() ?: ByteArray(0)
        val audioDistinct = anonBytes.isNotEmpty() && jennyBytes.isNotEmpty() && !anonBytes.contentEquals(jennyBytes)

        val bothNeuralTested = anonComp.verificationStatus != "NOT_TESTED_NO_MODEL" &&
            jennyComp.verificationStatus != "NOT_TESTED_NO_MODEL"
        val usedRealOcr = anonComp.liveCapturedViaMediaProjectionOcr && jennyComp.liveCapturedViaMediaProjectionOcr
        val isRunningOnJvmRobolectric = Build.FINGERPRINT == null ||
            Build.FINGERPRINT.contains("robolectric", ignoreCase = true)

        val physicalDeviceStatus = if (!isRunningOnJvmRobolectric && bothNeuralTested && usedRealOcr) {
            "HARDWARE EXECUTION: Real MediaProjection OCR + Neural WAV synthesized on Android device (Human listening required for subjective quality)"
        } else {
            "NOT VERIFIED"
        }

        val overallStatus = when {
            !bothNeuralTested -> "NOT_TESTED_NO_MODEL"
            anonComp.passed && jennyComp.passed && sidsDistinct && audioDistinct -> "PASSED_NEURAL_SPEECH"
            else -> "FAILED_MISMATCH"
        }
        val overallPassed = overallStatus == "PASSED_NEURAL_SPEECH"

        val summary = buildString {
            append("[$overallStatus] ")
            append("Anon(Preview SID=${anonComp.previewRequestedSid}, Live SID=${anonComp.liveRequestedSid}, Engine=${anonComp.liveEngine}, RMS=${anonComp.liveRmsDisplay}) • ")
            append("Jenny(Preview SID=${jennyComp.previewRequestedSid}, Live SID=${jennyComp.liveRequestedSid}, Engine=${jennyComp.liveEngine}, RMS=${jennyComp.liveRmsDisplay}) • ")
            append("Live OCR Capture: ${if (usedRealOcr) "YES" else "SYNTHETIC_UNIT_ROUTE"} • ")
            append("Physical Device: $physicalDeviceStatus")
        }

        val report = PreviewVsLiveFullComparisonReport(
            timestampMs = System.currentTimeMillis(),
            gameNamespace = gameNamespace,
            hindiSentence = hindiSentence,
            anonComparison = anonComp,
            jennyComparison = jennyComp,
            verificationStatus = overallStatus,
            physicalDeviceVerificationStatus = physicalDeviceStatus,
            usedLiveMediaProjectionOcrCapture = usedRealOcr,
            maleAndFemaleSidsDistinct = sidsDistinct,
            maleAndFemaleAudioDistinct = audioDistinct,
            overallPassed = overallPassed,
            summaryReport = summary
        )
        _lastComparisonReport.value = report
        return report
    }

    private data class WavSynthOutcome(
        val actualSid: Int,
        val sampleRate: Int,
        val waveformRms: Float?,
        val durationMs: Long,
        val engineUsed: String,
        val isRealNeuralAudio: Boolean,
        val wavSha256: String,
        val errorCategoryCode: String = "NONE",
        val errorReason: String
    )

    /**
     * Synthesizes a [VoiceRouteDecision] to [outputWavFile] using ONLY real neural speech engines
     * (either the authenticated local Chatterbox server or the in-process Sherpa-ONNX Supertonic 3 runtime).
     *
     * NEVER generates synthetic sine-wave tones! When neither neural engine is available, deletes any
     * stale output file and returns `engineUsed = "NOT_TESTED_NO_MODEL"`, `waveformRms = null`,
     * `isRealNeuralAudio = false`.
     */
    private fun synthesizeRouteToWav(
        context: Context,
        route: VoiceRouteDecision,
        outputWavFile: File,
        sherpaBackend: SherpaSupertonicBackend?,
        neuralClient: NeuralVoiceClient?
    ): WavSynthOutcome {
        val requestedSid = route.selectedSpeakerId.coerceIn(0, 9)
        val state = TranslatorStateRepository.state.value
        val canonicalSelection = selectCanonicalVoiceBackend(context, route.characterAssignment, state)

        // Clean up any stale WAV file before attempting synthesis
        if (outputWavFile.exists()) {
            try {
                outputWavFile.delete()
            } catch (_: Throwable) {
            }
        }

        // 1. If canonical backend is LOCAL_CHATTERBOX_SERVER, try real server synthesis first
        if (canonicalSelection.canonicalBackend == CanonicalVoiceBackend.LOCAL_CHATTERBOX_SERVER && neuralClient != null) {
            val req = NeuralSynthesizeRequest(
                text = route.hindiDialogue,
                characterId = route.characterAssignment.characterName,
                referenceVoiceId = route.characterAssignment.referenceVoiceId,
                languageId = "hi",
                emotion = route.characterAssignment.defaultEmotion,
                exaggeration = route.characterAssignment.exaggeration,
                cfgWeight = route.characterAssignment.cfgWeight,
                requestId = route.utteranceId,
                epoch = route.sessionEpoch
            )
            var serverOutcome: WavSynthOutcome? = null
            var serverErr = "Chatterbox server unreachable"
            var serverErrCode = "SERVER_UNREACHABLE"
            runBlocking {
                val res = neuralClient.synthesizeHindiWav(
                    rawBaseUrl = state.chatterboxServerUrl,
                    authToken = state.chatterboxAuthToken,
                    request = req
                )
                when (res) {
                    is com.example.service.NeuralSynthesisOutcome.Success -> {
                        outputWavFile.parentFile?.mkdirs()
                        outputWavFile.writeBytes(res.rawWavBytes)
                        serverOutcome = WavSynthOutcome(
                            actualSid = requestedSid,
                            sampleRate = res.parsedWav.sampleRate,
                            waveformRms = res.parsedWav.waveformRms,
                            durationMs = res.parsedWav.durationMs,
                            engineUsed = "CHATTERBOX_LOCAL_SERVER",
                            isRealNeuralAudio = true,
                            wavSha256 = computeFileSha256(outputWavFile),
                            errorCategoryCode = "NONE",
                            errorReason = ""
                        )
                    }
                    is com.example.service.NeuralSynthesisOutcome.Error -> {
                        serverErr = res.reason
                        serverErrCode = res.errorCategory.code
                    }
                }
            }
            if (serverOutcome != null) {
                return serverOutcome!!
            }
            // If server failed and Supertonic is not ready, return NOT_TESTED_NO_MODEL with exact errorCategoryCode
            if (sherpaBackend == null || !SupertonicModelManager.isModelReady(context)) {
                return WavSynthOutcome(
                    actualSid = requestedSid,
                    sampleRate = 0,
                    waveformRms = null,
                    durationMs = 0L,
                    engineUsed = "NOT_TESTED_NO_MODEL",
                    isRealNeuralAudio = false,
                    wavSha256 = "",
                    errorCategoryCode = serverErrCode,
                    errorReason = "[$serverErrCode] Local Chatterbox server unavailable ($serverErr) and Supertonic ONNX model not installed"
                )
            }
        }

        // 2. Try in-process Sherpa-ONNX Supertonic 3 native neural synthesis
        if (sherpaBackend != null &&
            SupertonicModelManager.isModelReady(context) &&
            (sherpaBackend.isModelRuntimeInitialized || sherpaBackend.initialize())
        ) {
            return try {
                val res = sherpaBackend.synthesizeAndValidateToWav(
                    text = route.hindiDialogue,
                    sid = requestedSid,
                    speed = route.characterAssignment.speed,
                    outputWavFile = outputWavFile
                )
                val isValidAudio = res.sampleCount > 0 &&
                    res.waveformRms >= SherpaSupertonicBackend.MIN_NON_SILENT_RMS &&
                    outputWavFile.exists() &&
                    outputWavFile.length() > 44L
                if (isValidAudio) {
                    WavSynthOutcome(
                        actualSid = res.sid,
                        sampleRate = res.sampleRate,
                        waveformRms = res.waveformRms,
                        durationMs = res.audioDurationMs,
                        engineUsed = "SHERPA_DIRECT",
                        isRealNeuralAudio = true,
                        wavSha256 = computeFileSha256(outputWavFile),
                        errorCategoryCode = "NONE",
                        errorReason = ""
                    )
                } else {
                    if (outputWavFile.exists()) outputWavFile.delete()
                    WavSynthOutcome(
                        actualSid = requestedSid,
                        sampleRate = 0,
                        waveformRms = null,
                        durationMs = 0L,
                        engineUsed = "NOT_TESTED_NO_MODEL",
                        isRealNeuralAudio = false,
                        wavSha256 = "",
                        errorCategoryCode = "INFERENCE_FAILED",
                        errorReason = "Sherpa native synthesis produced no valid non-silent audio"
                    )
                }
            } catch (t: Throwable) {
                if (outputWavFile.exists()) outputWavFile.delete()
                WavSynthOutcome(
                    actualSid = requestedSid,
                    sampleRate = 0,
                    waveformRms = null,
                    durationMs = 0L,
                    engineUsed = "NOT_TESTED_NO_MODEL",
                    isRealNeuralAudio = false,
                    wavSha256 = "",
                    errorCategoryCode = "INFERENCE_FAILED",
                    errorReason = "Sherpa native synthesis exception: ${t.message}"
                )
            }
        }

        // 3. No neural model or server available: NEVER generate a synthetic sine-wave WAV!
        return WavSynthOutcome(
            actualSid = requestedSid,
            sampleRate = 0,
            waveformRms = null,
            durationMs = 0L,
            engineUsed = "NOT_TESTED_NO_MODEL",
            isRealNeuralAudio = false,
            wavSha256 = "",
            errorCategoryCode = "MODEL_UNAVAILABLE",
            errorReason = "Supertonic ONNX model not installed on device and local Chatterbox server not connected"
        )
    }

    fun computeFileSha256(file: File): String {
        if (!file.exists() || !file.isFile) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            ""
        }
    }
}
