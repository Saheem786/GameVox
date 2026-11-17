package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.audio.AudioSampleProcessor
import com.example.audio.ContinuousMicRecorder
import com.example.data.SampleAudioInspection
import com.example.data.SpeakerGender
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceCategory
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.service.CloneGenerationResult
import com.example.service.ForegroundTtsManager
import com.example.service.NeuralVoiceClient
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * ElevenLabs-style Instant Neural Voice Cloning workflow powered by self-hosted free/open-source
 * Chatterbox Multilingual V3 (`ResembleAI/Chatterbox-Multilingual-hi`).
 *
 * Uses shared [voiceName] and [previewHindiText] fields from [VoiceGenerateScreen] so there are
 * zero duplicate inputs on screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiVoiceCloneSection(
    voiceName: String,
    previewHindiText: String,
    selectedGender: SpeakerGender,
    selectedLanguage: String,
    selectedStyle: String,
    consentConfirmed: Boolean,
    onConsentChange: (Boolean) -> Unit,
    serverUrl: String,
    serverToken: String,
    onServerConfigChange: (url: String, token: String) -> Unit,
    isChatterboxConnected: Boolean,
    chatterboxStatusText: String,
    onSavedToLibrary: (VoiceProfile) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ttsManager = remember { ForegroundTtsManager.getInstance(context) }

    var isTestingServer by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var activeRecorder by remember { mutableStateOf<ContinuousMicRecorder?>(null) }
    var recordingElapsedSec by remember { mutableFloatStateOf(0f) }
    var recordingRms by remember { mutableFloatStateOf(0f) }

    var isProcessingAudio by remember { mutableStateOf(false) }
    var latestAudioPath by remember { mutableStateOf<String?>(null) }
    var latestInspection by remember { mutableStateOf<SampleAudioInspection?>(null) }
    var estimatedPitch by remember { mutableFloatStateOf(selectedGender.defaultPitch18Yo) }
    var estimatedRate by remember { mutableFloatStateOf(selectedGender.defaultSpeechRate18Yo) }

    var exaggeration by remember { mutableFloatStateOf(0.50f) }
    var cfgWeight by remember { mutableFloatStateOf(0.40f) }

    var isGeneratingClone by remember { mutableStateOf(false) }
    var cloneErrorMessage by remember { mutableStateOf("") }
    var lastCloneResult by remember { mutableStateOf<CloneGenerationResult?>(null) }
    var consentTimestampMs by remember { mutableLongStateOf(0L) }

    var activePlaybackTag by remember { mutableStateOf<String?>(null) } // "original" | "clone" | null
    var savedProfile by remember { mutableStateOf<VoiceProfile?>(null) }
    var customCharInput by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            activeRecorder?.requestStop()
            VoiceProfileRepository.stopReferenceAudioPlayback()
        }
    }

    fun startInteractiveRecording() {
        if (isRecording || isProcessingAudio) return
        val recorder = ContinuousMicRecorder()
        activeRecorder = recorder
        isRecording = true
        recordingElapsedSec = 0f
        recordingRms = 0f
        cloneErrorMessage = ""

        val outDir = File(context.filesDir, "custom_voices").apply { mkdirs() }
        val outWav = File(outDir, "mic_clone_ref_${System.currentTimeMillis()}.wav")

        scope.launch {
            val result = recorder.recordUntilStopped(
                outputWavFile = outWav,
                maxDurationSeconds = AudioSampleProcessor.MAX_DURATION_SECONDS.toInt()
            ) { elapsed, rms ->
                recordingElapsedSec = elapsed
                recordingRms = rms
            }
            isRecording = false
            activeRecorder = null

            result.fold(
                onSuccess = { report ->
                    val inspection = VoiceProfileRepository.inspectionFromProcessedReport(report)
                    latestAudioPath = report.outputWavPath
                    latestInspection = inspection
                    estimatedPitch = (report.estimatedF0Hz / 160f).coerceIn(0.65f, 1.45f)
                    estimatedRate = 1.0f
                    lastCloneResult = null
                    savedProfile = null
                    onShowMessage("Recorded ${String.format(Locale.US, "%.1fs", report.durationSeconds)} sample: ${inspection.qualityVerdict}")
                },
                onFailure = { err ->
                    onShowMessage(err.localizedMessage ?: "Microphone capture failed.")
                }
            )
        }
    }

    fun stopInteractiveRecording() {
        activeRecorder?.requestStop()
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startInteractiveRecording()
        } else {
            onShowMessage("Microphone permission denied — use Import Reference Audio to upload a WAV/MP3/M4A clip.")
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isProcessingAudio = true
            cloneErrorMessage = ""
            scope.launch {
                val result = VoiceProfileRepository.importAndAnalyzeAudioUri(context, uri)
                isProcessingAudio = false
                val savedPath = result.savedAudioPath
                if (savedPath != null && File(savedPath).exists()) {
                    val inspection = result.processedReport?.let {
                        VoiceProfileRepository.inspectionFromProcessedReport(it)
                    } ?: VoiceProfileRepository.inspectAudioSample(File(savedPath))
                    latestAudioPath = savedPath
                    latestInspection = inspection
                    estimatedPitch = result.clonedPitch
                    estimatedRate = result.clonedSpeechRate
                    lastCloneResult = null
                    savedProfile = null
                    onShowMessage("Imported & normalized: ${inspection.qualityVerdict}")
                } else {
                    onShowMessage("Could not decode or import the selected audio file.")
                }
            }
        }
    }

    fun saveClonedVoiceOrReuse(): VoiceProfile? {
        val samplePath = latestAudioPath
        val inspection = latestInspection
        val cloneRes = lastCloneResult
        if (samplePath.isNullOrBlank() || inspection == null || cloneRes == null || !cloneRes.success) {
            return null
        }
        savedProfile?.let { existing ->
            if (existing.generatedPreviewPath == cloneRes.outputWavPath &&
                existing.name == (voiceName.trim().ifBlank { existing.name })
            ) {
                return existing
            }
        }
        val cleanName = voiceName.trim().ifBlank {
            "Cloned ${selectedGender.name.take(1)} Voice (${cloneRes.referenceSha256.take(6)})"
        }
        val previewText = previewHindiText.trim().ifBlank { VOICE_PREVIEW_HINDI_SAMPLE }
        val profile = VoiceProfileRepository.addGeneratedOrImportedVoice(
            context = context,
            name = cleanName,
            description = "Chatterbox Multilingual V3 • ResembleAI/Chatterbox-Multilingual-hi • ${String.format(Locale.US, "%.1fs", inspection.durationSeconds)} ref",
            gender = selectedGender,
            pitch = estimatedPitch,
            speechRate = estimatedRate,
            engineVoiceIndex = if (selectedGender == SpeakerGender.FEMALE_18) 1 else 5,
            voiceCategory = VoiceCategory.CLONED,
            sourceModelInfo = "Chatterbox V3 Hindi Clone • ${inspection.qualityVerdict}",
            languageCode = selectedLanguage,
            styleTag = selectedStyle,
            sampleAudioPath = samplePath,
            isNeuralCloned = true,
            selectImmediately = true,
            referenceAudioSha256 = inspection.sha256.ifBlank { cloneRes.referenceSha256 },
            generatedPreviewPath = cloneRes.outputWavPath,
            generatedPreviewText = previewText,
            neuralModelName = "Chatterbox Multilingual V3 (Hindi)",
            neuralModelVersion = "ResembleAI/Chatterbox-Multilingual-hi",
            cloneExaggeration = exaggeration,
            cloneCfgWeight = cfgWeight,
            consentConfirmed = consentConfirmed,
            consentTimestampMs = if (consentTimestampMs > 0L) consentTimestampMs else System.currentTimeMillis(),
            engineReadinessRequirement = "CHATTERBOX_SERVER_REQUIRED"
        )
        savedProfile = profile
        onSavedToLibrary(profile)
        return profile
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Server Readiness Banner + Inline Config
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isChatterboxConnected) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            ),
            border = BorderStroke(
                1.dp,
                if (isChatterboxConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_clone_server_card")
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isChatterboxConnected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = if (isChatterboxConnected) "Chatterbox connected" else "Chatterbox server required",
                        tint = if (isChatterboxConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isChatterboxConnected) {
                                "Chatterbox Neural Server Ready"
                            } else {
                                "PC / Local Server Required (Chatterbox V3 Hindi)"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = chatterboxStatusText.ifBlank {
                                "Free open-source cloning via ResembleAI/Chatterbox-Multilingual-hi. Connect your PC server over HTTPS or LAN."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { onServerConfigChange(it, serverToken) },
                    label = { Text("Chatterbox Server URL") },
                    placeholder = { Text("http://192.168.1.100:8000") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_clone_server_url_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = serverToken,
                        onValueChange = { onServerConfigChange(serverUrl, it) },
                        label = { Text("Bearer Token (Optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_clone_server_token_input")
                    )
                    OutlinedButton(
                        onClick = {
                            isTestingServer = true
                            TranslatorStateRepository.updateChatterboxServerConfig(
                                context = context,
                                serverUrl = serverUrl,
                                authToken = serverToken
                            )
                            ttsManager.testLocalChatterboxServerConnection { report ->
                                isTestingServer = false
                                val msg = if (report.reachable && report.modelLoaded) {
                                    "Connected (${report.device}): ${report.modelId.ifBlank { "Chatterbox Hindi" }}"
                                } else {
                                    "[${report.errorCategory.code}] ${report.errorMessage}"
                                }
                                onShowMessage(msg)
                            }
                        },
                        enabled = !isTestingServer && serverUrl.isNotBlank(),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("ai_clone_test_server_button")
                    ) {
                        if (isTestingServer) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Test Server")
                        }
                    }
                }
            }
        }

        // 2. Reference Voice Capture (Record My Voice / Import Reference Audio / Play Original)
        Text(
            text = "Step 1 • Record or Import Reference Audio (5s min, 30–120s ideal)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Single clean speaker, no background music. Audio is automatically trimmed of dead silence and normalized to 24 kHz mono WAV.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (isRecording) {
                        stopInteractiveRecording()
                    } else {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            startInteractiveRecording()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                enabled = !isProcessingAudio && !isGeneratingClone,
                colors = if (isRecording) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("record_audio_sample_button")
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop Recording" else "Record My Voice",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isRecording) "Stop (${String.format(Locale.US, "%.0fs", recordingElapsedSec)})" else "Record My Voice")
            }

            OutlinedButton(
                onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                enabled = !isRecording && !isProcessingAudio && !isGeneratingClone,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("import_audio_sample_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AudioFile,
                    contentDescription = "Import Reference Audio",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isProcessingAudio) "Decoding..." else "Import Audio")
            }
        }

        AnimatedVisibility(visible = isRecording) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val recProgress = (recordingElapsedSec / AudioSampleProcessor.RECOMMENDED_MIN_DURATION_SECONDS).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { recProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Recording %.1fs / 120s • Mic RMS %.2f • %s",
                            recordingElapsedSec,
                            recordingRms,
                            if (recordingElapsedSec < 5f) "Speak at least 5s"
                            else if (recordingElapsedSec < 30f) "Minimum met (30s+ recommended for best clone)"
                            else "Optimal cloning length reached — tap Stop when done"
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        val currentSamplePath = latestAudioPath
        if (!currentSamplePath.isNullOrBlank() && File(currentSamplePath).exists()) {
            OutlinedButton(
                onClick = {
                    if (activePlaybackTag == "original") {
                        VoiceProfileRepository.stopReferenceAudioPlayback()
                        activePlaybackTag = null
                    } else {
                        activePlaybackTag = "original"
                        VoiceProfileRepository.playRecordedReferenceSample(currentSamplePath) {
                            activePlaybackTag = null
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("play_original_recording_button")
            ) {
                Icon(
                    imageVector = if (activePlaybackTag == "original") Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = "Play Original Recording",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (activePlaybackTag == "original") "Stop Original Recording" else "Play Original Recording")
            }
        }

        // 3. Voice Quality Analysis Card
        latestInspection?.let { info ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (info.recommendedForCloning && !info.multiSpeakerSuspected) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    }
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sample_inspection_card")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (info.recommendedForCloning && !info.multiSpeakerSuspected) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.WarningAmber
                            },
                            contentDescription = "Voice Quality Verdict",
                            tint = if (info.recommendedForCloning && !info.multiSpeakerSuspected) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Voice Quality Analysis: ${info.qualityVerdict}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = info.qualityDetail,
                        style = MaterialTheme.typography.bodySmall
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MetricPill("F0 ~${info.estimatedF0Hz.toInt()} Hz")
                        MetricPill("SNR ~${info.estimatedSnrDb.toInt()} dB")
                        MetricPill("Clipping ${String.format(Locale.US, "%.1f%%", info.clippingRatio * 100f)}")
                        MetricPill("Trimmed ${info.trimmedSilenceMs}ms")
                        MetricPill(
                            if (info.multiSpeakerSuspected) "Multi-Speaker Risk" else "Single-Speaker OK",
                            isWarning = info.multiSpeakerSuspected
                        )
                    }

                    if (info.sha256.isNotBlank()) {
                        Text(
                            text = "Ref SHA-256: ${info.sha256.take(16)}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    info.qualityWarnings.forEach { warn ->
                        Text(
                            text = "• $warn",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 4. Voice Ownership / Permission Confirmation
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = consentConfirmed,
                    onCheckedChange = { checked ->
                        if (checked) consentTimestampMs = System.currentTimeMillis()
                        onConsentChange(checked)
                    },
                    modifier = Modifier.testTag("voice_clone_consent_checkbox")
                )
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "I confirm this is my own voice or I have explicit permission from the speaker for private Hindi game dubbing.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // 5. Neural Conditioning Controls
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Chatterbox Neural Expressiveness & Identity Weight",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "Expressiveness (Exaggeration): %.2f   •   Speaker Fidelity (CFG): %.2f",
                        exaggeration,
                        cfgWeight
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = exaggeration,
                    onValueChange = { exaggeration = it },
                    valueRange = 0.10f..1.20f,
                    modifier = Modifier.testTag("ai_clone_exaggeration_slider")
                )
                Slider(
                    value = cfgWeight,
                    onValueChange = { cfgWeight = it },
                    valueRange = 0.10f..1.00f,
                    modifier = Modifier.testTag("ai_clone_cfg_slider")
                )
            }
        }

        // 6. Generate Clone Preview Button
        val normalizedUrl = remember(serverUrl) {
            if (serverUrl.isBlank()) "" else NeuralVoiceClient.normalizeBaseUrl(serverUrl)
        }
        val canAttemptClone = !currentSamplePath.isNullOrBlank() &&
            File(currentSamplePath).exists() &&
            consentConfirmed &&
            !isGeneratingClone &&
            !isRecording

        Button(
            onClick = {
                val sampleFile = currentSamplePath?.let(::File)
                if (sampleFile == null || !sampleFile.exists()) {
                    cloneErrorMessage = "Record or import a reference audio sample first."
                    return@Button
                }
                if (!consentConfirmed) {
                    cloneErrorMessage = "Please confirm voice ownership / speaker permission first."
                    return@Button
                }
                if (normalizedUrl.isBlank()) {
                    cloneErrorMessage = "Enter a valid Chatterbox server URL (PC/Server required for real neural cloning)."
                    return@Button
                }

                isGeneratingClone = true
                cloneErrorMessage = ""
                TranslatorStateRepository.updateChatterboxServerConfig(
                    context = context,
                    serverUrl = serverUrl,
                    authToken = serverToken
                )
                val targetName = voiceName.trim().ifBlank { "Custom Clone" }
                val promptText = previewHindiText.trim().ifBlank { VOICE_PREVIEW_HINDI_SAMPLE }

                scope.launch {
                    val result = ttsManager.generateNeuralClonePreview(
                        voiceName = targetName,
                        referenceAudioFile = sampleFile,
                        previewHindiText = promptText,
                        characterId = "clone_preview",
                        exaggeration = exaggeration,
                        cfgWeight = cfgWeight,
                        speakerConsentConfirmed = consentConfirmed,
                        rawBaseUrl = serverUrl,
                        authToken = serverToken
                    )
                    isGeneratingClone = false
                    lastCloneResult = result
                    if (result.success) {
                        savedProfile = null
                        onShowMessage(
                            "Synthesized new Hindi clone (${result.audioDurationMs}ms audio, ${result.inferenceLatencyMs}ms GPU/CPU inference)"
                        )
                    } else {
                        cloneErrorMessage = "[${result.errorCategory.displayTitle}] ${result.errorMessage}"
                        onShowMessage("Clone synthesis failed: ${result.errorMessage}")
                    }
                }
            },
            enabled = canAttemptClone,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("clone_via_chatterbox_button")
        ) {
            if (isGeneratingClone) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                when {
                    isGeneratingClone -> "Cloning & Synthesizing Hindi..."
                    isChatterboxConnected -> "Generate Clone Preview"
                    else -> "Generate Clone Preview (Requires Server)"
                }
            )
        }

        if (cloneErrorMessage.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_clone_error_banner")
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = cloneErrorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // 7. Synthesized Output, Compare Original vs Clone, Save to Library, Assign to Character
        val cloneRes = lastCloneResult
        if (cloneRes != null && cloneRes.success && cloneRes.outputWavPath.isNotBlank()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                ),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clone_ab_comparison_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Column {
                            Text(
                                text = "Synthesized Neural Hindi Clone Ready",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Model: ResembleAI/Chatterbox-Multilingual-hi (${cloneRes.deviceUsed.uppercase(Locale.ROOT)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MetricPill("Conditioning ${cloneRes.conditioningLatencyMs}ms" + if (cloneRes.conditioningCached) " (Cached)" else "")
                        MetricPill("Inference ${cloneRes.inferenceLatencyMs}ms")
                        MetricPill("Audio ${cloneRes.audioDurationMs}ms")
                        MetricPill("RMS ${String.format(Locale.US, "%.4f", cloneRes.synthesizedRms)}")
                    }

                    Text(
                        text = "Identity proof: Ref SHA ${cloneRes.referenceSha256.take(10)} ≠ Synthesized SHA ${cloneRes.outputWavSha256.take(10)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    // Play Synthesized Voice
                    Button(
                        onClick = {
                            if (activePlaybackTag == "clone") {
                                VoiceProfileRepository.stopReferenceAudioPlayback()
                                activePlaybackTag = null
                            } else {
                                activePlaybackTag = "clone"
                                VoiceProfileRepository.playRecordedReferenceSample(cloneRes.outputWavPath) {
                                    activePlaybackTag = null
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("play_synthesized_clone_button")
                    ) {
                        Icon(
                            imageVector = if (activePlaybackTag == "clone") Icons.Default.Stop else Icons.Default.GraphicEq,
                            contentDescription = "Play Synthesized Voice",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (activePlaybackTag == "clone") "Stop Synthesized Voice" else "Play Synthesized Voice")
                    }

                    // Compare Original vs Clone
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!currentSamplePath.isNullOrBlank()) {
                                    activePlaybackTag = "original"
                                    VoiceProfileRepository.playRecordedReferenceSample(currentSamplePath) {
                                        activePlaybackTag = null
                                    }
                                }
                            },
                            enabled = !currentSamplePath.isNullOrBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("ab_compare_original_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Compare: Original")
                        }
                        OutlinedButton(
                            onClick = {
                                activePlaybackTag = "clone"
                                VoiceProfileRepository.playRecordedReferenceSample(cloneRes.outputWavPath) {
                                    activePlaybackTag = null
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("ab_compare_clone_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Compare: Clone")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Save to Voice Library
                    Button(
                        onClick = {
                            val saved = saveClonedVoiceOrReuse()
                            if (saved != null) {
                                onShowMessage("Saved cloned voice \"${saved.name}\" to Voice Library")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_cloned_voice_button")
                    ) {
                        Icon(
                            imageVector = if (savedProfile != null) Icons.Default.CheckCircle else Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (savedProfile != null) "Saved to Voice Library" else "Save to Voice Library")
                    }

                    // Assign to Game Character
                    Text("Assign to Game Character:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Anon", "Jenny", "Alice").forEach { charName ->
                            OutlinedButton(
                                onClick = {
                                    val saved = saveClonedVoiceOrReuse() ?: return@OutlinedButton
                                    val assigned = VoiceProfileRepository.assignVoiceToCharacter(context, saved, charName)
                                    onShowMessage("Assigned neural clone \"${saved.name}\" to ${assigned.displayName}")
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("clone_assign_$charName")
                            ) {
                                Text("Assign $charName")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customCharInput,
                            onValueChange = { customCharInput = it },
                            label = { Text("Other Character (e.g. Boss)") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("clone_assign_custom_input")
                        )
                        OutlinedButton(
                            onClick = {
                                val target = customCharInput.trim()
                                if (target.isEmpty()) return@OutlinedButton
                                val saved = saveClonedVoiceOrReuse() ?: return@OutlinedButton
                                val assigned = VoiceProfileRepository.assignVoiceToCharacter(context, saved, target)
                                onShowMessage("Assigned neural clone \"${saved.name}\" to ${assigned.displayName}")
                            },
                            enabled = customCharInput.trim().isNotEmpty(),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("clone_assign_custom_button")
                        ) {
                            Text("Assign")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricPill(
    text: String,
    isWarning: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (isWarning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (isWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
