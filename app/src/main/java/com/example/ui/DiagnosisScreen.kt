package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.TranslatorAppState
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceProfileRepository
import com.example.engine.CharacterVoiceRouter
import com.example.service.ForegroundTtsManager
import com.example.service.FreeVoiceBackend
import com.example.service.SupertonicModelManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosisScreen(
    state: TranslatorAppState,
    femaleVoicesCount: Int,
    maleVoicesCount: Int,
    onNavigateBack: () -> Unit,
    onTestVoice: () -> Unit,
    onRunVoiceBenchmark: () -> Unit,
    onClearQueueAndCache: () -> Unit,
    onOpenAbVoiceTesting: (() -> Unit)? = null,
    onTestServerConnection: (() -> Unit)? = null
) {
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val roiBitmap = state.originalFramePreview
    val ocrBitmap = state.invertedFramePreview
    val roiImageBitmap = remember(roiBitmap) {
        if (roiBitmap != null && !roiBitmap.isRecycled) roiBitmap.asImageBitmap() else null
    }
    val ocrImageBitmap = remember(ocrBitmap) {
        if (ocrBitmap != null && !ocrBitmap.isRecycled) ocrBitmap.asImageBitmap() else null
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("diagnosis_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Voice Server & System Diagnostics",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Local Wi-Fi Neural Server • Loaded Models • Pipeline Telemetry",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("diagnosis_screen"),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Local Wi-Fi Voice Server & Loaded Model Diagnostic Card
                LocalWifiVoiceServerDiagnosticCard(
                    state = state,
                    onTestConnection = { editedUrl, editedToken ->
                        TranslatorStateRepository.updateChatterboxServerConfig(
                            context = context,
                            serverUrl = editedUrl,
                            authToken = editedToken
                        )
                        if (onTestServerConnection != null) {
                            onTestServerConnection()
                        } else {
                            ForegroundTtsManager.getInstance(context).testLocalChatterboxServerConnection()
                        }
                    },
                    onOpenAbVoiceTesting = onOpenAbVoiceTesting
                )

                // 1b. Preview vs Live Voice Comparison Diagnostic Card (Anon & Jenny)
                PreviewVsLiveVoiceComparisonCard()

                // 2. Capture Status & Error Card + Quick Controls
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("diagnosis_status_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Pipeline Status",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = state.captureStatusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Projection Active: ${if (state.hasActiveMediaProjection) "Yes" else "No"} • Scanning: ${if (state.isCapturingOcr) "Yes" else "Paused"} • Frames: ${state.scanFrameCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (state.isProtectedScreenDetected || state.lastPipelineError.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = "Pipeline error warning",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = state.lastPipelineError.ifBlank {
                                            "Protected screen (FLAG_SECURE) blocked pixel capture."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onTestVoice,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("diagnosis_test_voice_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Voice")
                            }
                            OutlinedButton(
                                onClick = onClearQueueAndCache,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("diagnosis_clear_cache_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CleaningServices,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reset Cache")
                            }
                        }
                    }
                }

                // 3. Live Cropped Subtitle ROI & OCR Preprocessed Bitmap Previews
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("diagnosis_bitmap_preview_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CropFree,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Captured Subtitle ROI & OCR Bitmap",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "ROI: ${state.roiDimensionsText}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (roiImageBitmap == null && ocrImageBitmap == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(92.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Start live scanning to preview captured ROI & OCR frames",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        } else {
                            if (roiImageBitmap != null) {
                                Text(
                                    text = "Raw Cropped Subtitle Region",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Image(
                                    bitmap = roiImageBitmap,
                                    contentDescription = "Raw cropped subtitle region preview",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(96.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                            }
                            if (ocrImageBitmap != null) {
                                Text(
                                    text = "OCR Preprocessed Image",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Image(
                                    bitmap = ocrImageBitmap,
                                    contentDescription = "OCR preprocessed subtitle bitmap",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(96.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                            }
                        }
                    }
                }

                // 4. OCR & Translation Output Card
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("diagnosis_translation_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "OCR & Translation Output",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = state.translationSourceLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        DiagnosisField(label = "Extracted OCR English", value = state.lastExtractedEnglish)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosisField(
                            label = "Translated (${state.targetLanguage.displayName})",
                            value = state.lastTranslatedHindi
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosisField(label = "ML Kit Offline Pack", value = state.mlKitModelStatusText)
                        DiagnosisField(label = "Firebase Context AI", value = state.firebaseAiStatusText)
                    }
                }

                // 5. TTS Lifecycle & Voice Telemetry Card
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("diagnosis_tts_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "TTS Engine & Playback Lifecycle",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        DiagnosisField(
                            label = "Bound Android TTS Engine",
                            value = buildString {
                                append(state.activeTtsEngineLabel)
                                if (state.activeTtsEnginePackage.isNotBlank()) {
                                    append(" (${state.activeTtsEnginePackage})")
                                }
                                if (state.isSupertonicOrSherpaActive) {
                                    append(" • Supertonic / sherpa-onnx Active")
                                }
                            }
                        )
                        val activeChar = VoiceProfileRepository.activeCharacterAssignment.value
                        val isModelVerifiedOnDisk = SupertonicModelManager.isModelReady(context)
                        val sherpaEngine = ForegroundTtsManager.getInstance(context).sherpaEngine
                        val isSherpaRuntimeReady = isModelVerifiedOnDisk &&
                            com.k2fsa.sherpa.onnx.OfflineTts.isNativeLibraryLoaded &&
                            sherpaEngine.isModelRuntimeInitialized
                        val speakerCount = VoiceProfileRepository.getAvailableSpeakerCount()

                        // Voice Engine Audit Panel
                        DiagnosisField(
                            label = "Speech Engine Mode",
                            value = when {
                                state.freeVoiceBackend == FreeVoiceBackend.PAUSE_DUBBING ->
                                    "PAUSE_DUBBING (Subtitle translation only; audio synthesis paused)"
                                state.freeVoiceBackend == FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER &&
                                    state.chatterboxServerReachable && state.chatterboxModelLoaded ->
                                    "CHATTERBOX_LOCAL_SERVER (ResembleAI/Chatterbox-Multilingual-hi • ${state.chatterboxDevice})"
                                isSherpaRuntimeReady && state.freeVoiceBackend == FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT ->
                                    "SHERPA_DIRECT (Native JNI + ONNX Runtime Initialized • OpenRAIL-M)"
                                isSherpaRuntimeReady && state.freeVoiceBackend == FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER ->
                                    "SHERPA_FALLBACK (Local server unreachable; Supertonic 3 active)"
                                state.supportsDistinctGenderVoices ->
                                    "SYSTEM_FALLBACK (${state.activeTtsEngineLabel} • ${sherpaEngine.initializationError ?: "Supertonic runtime not initialized"})"
                                else ->
                                    "SYSTEM_FALLBACK_SINGLE_VOICE (${state.activeTtsEngineLabel} • Single voice only; pitch/rate changes are not distinct voice identities)"
                            }
                        )
                        DiagnosisField(
                            label = "Chatterbox Local Neural Server",
                            value = "${state.chatterboxServerUrl} • ${state.chatterboxServerStatusText}"
                        )
                        DiagnosisField(
                            label = "Reference Voice Library Identity",
                            value = VoiceProfileRepository.getUniqueReferenceVoiceSummary().third
                        )
                        DiagnosisField(
                            label = "Model Loaded & Verified",
                            value = when {
                                isSherpaRuntimeReady -> "true (Native handle valid • ${SupertonicModelManager.getModelDirectory(context).absolutePath})"
                                isModelVerifiedOnDisk -> "Files verified on disk; native handle not initialized yet"
                                else -> "false (Not installed or SHA-256 unverified in private app storage)"
                            }
                        )
                        DiagnosisField(
                            label = "Speaker Count",
                            value = "$speakerCount speakers (from OfflineTts.numSpeakers() • 0..4 Female, 5..9 Male)"
                        )
                        DiagnosisField(
                            label = "Selected Speaker SID",
                            value = "SID #${activeChar?.speakerId ?: 0} (${if ((activeChar?.speakerId ?: 0) in 0..4) "Female F${(activeChar?.speakerId ?: 0) + 1}" else "Male M${(activeChar?.speakerId ?: 0) - 4}"} • Character: ${activeChar?.displayName ?: "Default"} • Ref: ${activeChar?.referenceVoiceId ?: "anon_male_ref"}.wav • ${if (activeChar?.isUserOverride == true) "User Override" else "Heuristic Match"})"
                        )
                        DiagnosisField(
                            label = "Model Version & License",
                            value = "${SupertonicModelManager.modelInfo.name} • ${SupertonicModelManager.modelInfo.version} (${SupertonicModelManager.modelInfo.license})"
                        )
                        DiagnosisField(
                            label = "Model Licensing & Attribution",
                            value = SupertonicModelManager.modelInfo.attributionNotice
                        )
                        DiagnosisField(
                            label = "JNI Load Result",
                            value = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary
                        )
                        DiagnosisField(
                            label = "Local Server Request Latency",
                            value = if (state.serverRequestLatencyMs > 0L) "${state.serverRequestLatencyMs} ms (HTTP round-trip)" else "—"
                        )
                        DiagnosisField(
                            label = "Neural Voice Inference Time",
                            value = if (state.serverInferenceLatencyMs > 0L) "${state.serverInferenceLatencyMs} ms (Conditioning: ${state.conditioningLatencyMs} ms • Cached: ${state.conditioningCached})" else "—"
                        )
                        DiagnosisField(
                            label = "Time to First Playable Audio",
                            value = if (state.ttsFirstPcmChunkLatencyMs > 0L) "${state.ttsFirstPcmChunkLatencyMs} ms (First non-silent PCM chunk ready)" else if (state.ttsFirstAudioLatencyMs > 0L) "${state.ttsFirstAudioLatencyMs} ms" else "0 ms (No dialogue synthesized yet)"
                        )
                        DiagnosisField(
                            label = "Actual Playback Start (AudioTrack / ExoPlayer)",
                            value = if (state.ttsAudioTrackWriteLatencyMs > 0L) "${state.ttsAudioTrackWriteLatencyMs} ms (Bytes written to audio buffer)" else "—"
                        )
                        DiagnosisField(
                            label = "Estimated Acoustic Playout (DAC)",
                            value = if (state.ttsEstimatedPlayoutLatencyMs > 0L) "${state.ttsEstimatedPlayoutLatencyMs} ms (Write + ~HAL mixer delay; physical speaker acoustic latency)" else "—"
                        )
                        DiagnosisField(
                            label = "End-to-End Subtitle-to-Audio Latency",
                            value = if (state.endToEndSubtitleToAudioLatencyMs > 0L) "${state.endToEndSubtitleToAudioLatencyMs} ms (OCR + Translation + Synthesis + Playback Start)" else "—"
                        )
                        DiagnosisField(
                            label = "Utterance Duration & RMS",
                            value = if (state.ttsSpeechDurationMs > 0L || state.lastSynthesizedRmsDisplay != "UNKNOWN") {
                                "${state.ttsSpeechDurationMs} ms • RMS=${state.lastSynthesizedRmsDisplay} • RefSHA=${state.lastSynthesizedVoiceSha256.take(12).ifBlank { "n/a" }}"
                            } else {
                                "— (RMS=UNKNOWN)"
                            }
                        )
                        DiagnosisField(
                            label = "Synthesis Real-Time Factor (RTF)",
                            value = if (state.ttsSynthesisRtf > 0.0f) {
                                "${String.format(Locale.US, "%.2fx", state.ttsSynthesisRtf)} (${if (state.ttsSynthesisRtf < 1.0f) "Faster than real-time" else "Slower than real-time"})"
                            } else {
                                "— (Run benchmark or speak dialogue)"
                            }
                        )
                        DiagnosisField(
                            label = "Engine Errors",
                            value = state.lastTtsError.ifBlank { "None (Operational)" }
                        )
                        DiagnosisField(
                            label = "ODE Generation Steps",
                            value = "${state.sherpaConfiguredSteps} steps (Quality benchmark supports 5 vs 8 steps)"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosisField(
                            label = "Voice Backend Selection",
                            value = state.freeVoiceBackend.displayLabel
                        )
                        DiagnosisField(
                            label = "Active Spoken Voice",
                            value = state.activeVoiceName
                        )
                        DiagnosisField(
                            label = "Kokoro-82M (MediaPipe Optional)",
                            value = state.kokoroStatusMessage
                        )
                        DiagnosisField(label = "TTS Engine Status", value = state.ttsStatus.label)
                        DiagnosisField(
                            label = "Installed Voice Catalog",
                            value = "Female: $femaleVoicesCount • Male: $maleVoicesCount • Total matching: ${state.availableVoicesCount}"
                        )
                        DiagnosisField(
                            label = "Last Callback Event",
                            value = "${state.lastTtsEvent}${if (state.lastTtsUtteranceId.isNotBlank()) " (${state.lastTtsUtteranceId.take(16)})" else ""}"
                        )
                        DiagnosisField(
                            label = "Emotion Analysis",
                            value = "${state.lastDetectedEmotion.displayLabel} — ${state.lastEmotionReason}"
                        )
                    }
                }

                // 6. Real Voice Latency Benchmarks (Measured on Android TTS Engine)
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("diagnosis_tts_benchmark_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "TTS Engine Latency Benchmarks",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LatencyBadge("Init", "${state.ttsInitLatencyMs}ms", Modifier.weight(1f))
                            LatencyBadge("PCM Chunk", "${state.ttsFirstPcmChunkLatencyMs.coerceAtLeast(state.ttsFirstAudioLatencyMs)}ms", Modifier.weight(1f))
                            LatencyBadge("Buffer Write", "${state.ttsAudioTrackWriteLatencyMs.coerceAtLeast(state.ttsFirstAudioLatencyMs)}ms", Modifier.weight(1f))
                            LatencyBadge("Est. Playout", "${state.ttsEstimatedPlayoutLatencyMs}ms", Modifier.weight(1f))
                        }

                        Text(
                            text = state.ttsBenchmarkSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Button(
                            onClick = onRunVoiceBenchmark,
                            enabled = !state.isRunningTtsBenchmark,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("diagnosis_run_tts_benchmark_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (state.isRunningTtsBenchmark) {
                                    "Benchmarking Active TTS Engine..."
                                } else {
                                    "Run TTS Latency Benchmark"
                                }
                            )
                        }

                        Text(
                            text = if (state.isRealHardwareBenchmark) {
                                "Hardware Execution: Measured on ARM64 Android device. Real acoustic playout includes ~HAL mixer buffering."
                            } else {
                                "Notice: Measured in JVM stub test mode. AudioTrack buffer write does not equal immediate acoustic sound. Physical ARM64 device required for actual ONNX NPU/CPU neural latency and speaker output."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 7. Measured Pipeline Latencies
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("diagnosis_latency_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Measured OCR & Translation Latencies",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LatencyBadge("Capture", "${state.captureLatencyMs}ms", Modifier.weight(1f))
                            LatencyBadge("OCR", "${state.ocrLatencyMs}ms", Modifier.weight(1f))
                            LatencyBadge("Translate", "${state.translationLatencyMs}ms", Modifier.weight(1f))
                            LatencyBadge("Total", "${state.totalPipelineLatencyMs}ms", Modifier.weight(1f))
                        }

                        Text(
                            text = "Queued: ${state.queuedSubtitlesCount} • Duplicates skipped: ${state.duplicateSkippedCount} • Rapid skipped: ${state.skippedRapidCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Dedicated diagnostic card showing the live status of the local Wi-Fi FastAPI Voice Server
 * connection, currently loaded neural model information (Chatterbox Multilingual V3 Hindi &
 * on-device Supertonic 3), editable server URL/token, and a 'Test Connection' button.
 */
@Composable
private fun LocalWifiVoiceServerDiagnosticCard(
    state: TranslatorAppState,
    onTestConnection: (serverUrl: String, authToken: String) -> Unit,
    onOpenAbVoiceTesting: (() -> Unit)?
) {
    val context = LocalContext.current
    var serverUrlInput by remember(state.chatterboxServerUrl) {
        mutableStateOf(state.chatterboxServerUrl)
    }
    var authTokenInput by remember(state.chatterboxAuthToken) {
        mutableStateOf(state.chatterboxAuthToken)
    }

    val isReady = state.chatterboxServerReachable && state.chatterboxModelLoaded
    val isReachableUnloaded = state.chatterboxServerReachable && !state.chatterboxModelLoaded
    val isTesting = state.isTestingChatterboxConnection

    val statusContainerColor by animateColorAsState(
        targetValue = when {
            isTesting -> MaterialTheme.colorScheme.secondaryContainer
            isReady -> MaterialTheme.colorScheme.primaryContainer
            isReachableUnloaded -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f)
        },
        animationSpec = tween(250),
        label = "serverStatusContainerColor"
    )

    val statusContentColor by animateColorAsState(
        targetValue = when {
            isTesting -> MaterialTheme.colorScheme.onSecondaryContainer
            isReady -> MaterialTheme.colorScheme.onPrimaryContainer
            isReachableUnloaded -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onErrorContainer
        },
        animationSpec = tween(250),
        label = "serverStatusContentColor"
    )

    val statusBadgeLabel = when {
        isTesting -> "TESTING..."
        isReady -> "CONNECTED & MODEL READY"
        isReachableUnloaded -> "REACHABLE (MODEL UNLOADED)"
        else -> "OFFLINE / UNTESTED"
    }

    val isSupertonicOnDisk = SupertonicModelManager.isModelReady(context)
    val sherpaEngine = ForegroundTtsManager.getInstance(context).sherpaEngine
    val isSupertonicInitialized = isSupertonicOnDisk &&
        com.k2fsa.sherpa.onnx.OfflineTts.isNativeLibraryLoaded &&
        sherpaEngine.isModelRuntimeInitialized

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isReady) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("diagnosis_wifi_server_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row with title + live connection status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = "Local Wi-Fi Voice Server",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "Local Wi-Fi Voice Server",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Windows/Linux FastAPI • Chatterbox V3 Hindi",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusContainerColor,
                    modifier = Modifier.testTag("diagnosis_wifi_server_status_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                isReady -> Icons.Default.CheckCircle
                                isReachableUnloaded -> Icons.Default.Dns
                                else -> Icons.Default.CloudOff
                            },
                            contentDescription = null,
                            tint = statusContentColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = statusBadgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusContentColor
                        )
                    }
                }
            }

            // Status summary banner
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusContainerColor.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("diagnosis_wifi_server_status_text")
            ) {
                Text(
                    text = state.chatterboxServerStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = statusContentColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }

            // Editable Local Wi-Fi URL & Auth Token
            OutlinedTextField(
                value = serverUrlInput,
                onValueChange = { serverUrlInput = it },
                label = { Text("Local Wi-Fi Server URL (FastAPI)") },
                placeholder = { Text("http://192.168.1.100:8765") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("diagnosis_server_url_input")
            )

            OutlinedTextField(
                value = authTokenInput,
                onValueChange = { authTokenInput = it },
                label = { Text("Local Auth Token (X-GameVox-Token)") },
                placeholder = { Text("gamevox-local-secret-2026") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("diagnosis_server_token_input")
            )

            // Currently Loaded Model Information Section
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("diagnosis_loaded_model_card")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Currently Loaded Model Information",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    DiagnosisField(
                        label = "Neural Server Model ID",
                        value = "${state.chatterboxModelId} (${if (state.chatterboxModelLoaded) "Loaded in VRAM/RAM" else "Not loaded / Server offline"})"
                    )
                    DiagnosisField(
                        label = "Server Engine & Compute Device",
                        value = "${state.chatterboxEngineName} • Device: ${state.chatterboxDevice.uppercase(Locale.ROOT)} • ${state.chatterboxSampleRate} Hz PCM16 WAV"
                    )
                    DiagnosisField(
                        label = "Server Reference Voices & Conditionals Cache",
                        value = "${state.chatterboxUniqueVoicesSha256Count} unique SHA-256 voices (${state.chatterboxReferenceVoiceFilesCount} .wav files) • ${state.chatterboxConditionalsCachedCount} cached voice conditionals"
                    )
                    DiagnosisField(
                        label = "Server Ping & Synthesis Latency",
                        value = buildString {
                            append("Health RTT: ")
                            append(if (state.chatterboxPingRttMs > 0L) "${state.chatterboxPingRttMs} ms" else "—")
                            append(" • Infer: ")
                            append(if (state.serverInferenceLatencyMs > 0L) "${state.serverInferenceLatencyMs} ms" else "—")
                            append(" • Cond: ")
                            append(if (state.conditioningLatencyMs > 0L) "${state.conditioningLatencyMs} ms (Cached=${state.conditioningCached})" else "—")
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DiagnosisField(
                        label = "On-Device Offline Model (Sherpa-ONNX Supertonic 3)",
                        value = buildString {
                            append("${SupertonicModelManager.modelInfo.name} (${SupertonicModelManager.modelInfo.version}) • ")
                            append(
                                when {
                                    isSupertonicInitialized -> "LOADED & INITIALIZED (${sherpaEngine.numSpeakers} speakers, ${sherpaEngine.sampleRate} Hz)"
                                    isSupertonicOnDisk -> "Verified on disk (ready to initialize)"
                                    else -> "Not downloaded yet"
                                }
                            )
                        }
                    )
                }
            }

            // Primary 'Test Connection' button + optional A/B Lab button
            Button(
                onClick = {
                    onTestConnection(serverUrlInput.trim(), authTokenInput.trim())
                },
                enabled = !isTesting,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("diagnosis_test_connection_button")
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Testing Connection...",
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Test Connection",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (onOpenAbVoiceTesting != null) {
                OutlinedButton(
                    onClick = onOpenAbVoiceTesting,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("diagnosis_open_ab_lab_button")
                ) {
                    Text("Open A/B Neural Voice Lab & Windows Server Setup")
                }
            }
        }
    }
}

@Composable
private fun PreviewVsLiveVoiceComparisonCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val comparisonReport by CharacterVoiceRouter.lastComparisonReport.collectAsStateWithLifecycle()
    val auditLogs by CharacterVoiceRouter.auditLogs.collectAsStateWithLifecycle()
    val liveRoutingLogs by CharacterVoiceRouter.liveRoutingDebugLogs.collectAsStateWithLifecycle()
    var isRunningComparison by remember { mutableStateOf(false) }
    var selectedGameNamespace by remember { mutableStateOf(VoiceProfileRepository.getActiveGameNamespace()) }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("preview_vs_live_comparison_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Preview vs Live OCR Voice Verification (Anon & Jenny)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Selects game namespace, routes Anon (Male Lead, SID #5) and Jenny (Female Companion, SID #1) through Voice Manager Preview and Live OCR Dubbing (using captured MediaProjection OCR frames when available), passes routeDecision unchanged to neural generation, and verifies SID, engine, and reference WAV SHA-256. Never generates dummy sine-wave WAVs (reports NOT_TESTED_NO_MODEL when neural model/server is absent).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("default_game", "genshin_hi", "valorant_hi").forEach { ns ->
                    OutlinedButton(
                        onClick = {
                            selectedGameNamespace = ns
                            VoiceProfileRepository.setGameNamespace(context, ns)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (selectedGameNamespace == ns) "✓ $ns" else ns,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Button(
                onClick = {
                    isRunningComparison = true
                    scope.launch {
                        withContext(Dispatchers.Default) {
                            CharacterVoiceRouter.runFullAnonAndJennyComparison(
                                context = context,
                                gameNamespace = selectedGameNamespace
                            )
                        }
                        isRunningComparison = false
                    }
                },
                enabled = !isRunningComparison,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("run_preview_vs_live_comparison_button")
            ) {
                Text(
                    text = if (isRunningComparison) {
                        "Verifying Preview vs Live Voice Routes..."
                    } else {
                        "Run Preview vs Live Verification ($selectedGameNamespace)"
                    },
                    fontWeight = FontWeight.Bold
                )
            }

            comparisonReport?.let { report ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (report.overallPassed) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preview_vs_live_comparison_result_box")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Status: ${report.verificationStatus} • Physical Device: ${report.physicalDeviceVerificationStatus}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = report.summaryReport,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        DiagnosisField(
                            label = "Anon (Male Lead) — Preview vs Live (${report.anonComparison.verificationStatus})",
                            value = "Preview SID=${report.anonComparison.previewActualSid} vs Live SID=${report.anonComparison.liveActualSid} • Engine=${report.anonComparison.liveEngine} • RefSHA=${report.anonComparison.referenceAudioSha256.take(12)} • RMS=${report.anonComparison.liveAudioRmsDisplay} • MediaProjectionOCR=${report.anonComparison.usedCapturedMediaProjectionOcr}"
                        )
                        DiagnosisField(
                            label = "Jenny (Female Companion) — Preview vs Live (${report.jennyComparison.verificationStatus})",
                            value = "Preview SID=${report.jennyComparison.previewActualSid} vs Live SID=${report.jennyComparison.liveActualSid} • Engine=${report.jennyComparison.liveEngine} • RefSHA=${report.jennyComparison.referenceAudioSha256.take(12)} • RMS=${report.jennyComparison.liveAudioRmsDisplay} • MediaProjectionOCR=${report.jennyComparison.usedCapturedMediaProjectionOcr}"
                        )
                    }
                }
            }

            if (liveRoutingLogs.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "Real-Time Live OCR Voice Routing Debug Log (${liveRoutingLogs.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                liveRoutingLogs.take(5).forEach { debugEntry ->
                    Text(
                        text = "• ${debugEntry.toCompactSummaryLine()}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (auditLogs.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "Recent Spoken Utterance Post-Synthesis Audit Logs (${auditLogs.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                auditLogs.take(4).forEach { entry ->
                    Text(
                        text = "• [${entry.ocrCharacterName} → ${entry.normalizedCharacterName}] rule=${entry.resolutionRule} • reqSid=${entry.requestedSid} actSid=${entry.actualSidPassedToNative} • backend=${entry.actualBackend} • RMS=${entry.outputPcmRmsDisplay} • fallback=${entry.fallbackReason.ifBlank { "none" }}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosisField(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.SansSerif),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LatencyBadge(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}
