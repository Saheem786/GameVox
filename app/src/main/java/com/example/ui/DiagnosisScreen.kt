package com.example.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.TranslatorAppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosisScreen(
    state: TranslatorAppState,
    femaleVoicesCount: Int,
    maleVoicesCount: Int,
    onNavigateBack: () -> Unit,
    onTestVoice: () -> Unit,
    onRunVoiceBenchmark: () -> Unit,
    onClearQueueAndCache: () -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

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
                    Text(
                        text = "Live Diagnosis",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
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
                // 1. Capture Status & Error Card + Quick Controls
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

                // 2. Live Cropped Subtitle ROI & OCR Preprocessed Bitmap Previews
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

                // 3. OCR & Translation Output Card
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

                // 4. TTS Lifecycle & Voice Telemetry Card
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
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val activeChar = com.example.data.VoiceProfileRepository.activeCharacterAssignment.value
                        val isModelVerifiedOnDisk = com.example.service.SupertonicModelManager.isModelReady(context)
                        val sherpaEngine = com.example.service.ForegroundTtsManager.getInstance(context).sherpaEngine
                        val isSherpaRuntimeReady = isModelVerifiedOnDisk &&
                            com.k2fsa.sherpa.onnx.OfflineTts.isNativeLibraryLoaded &&
                            sherpaEngine.isModelRuntimeInitialized
                        val speakerCount = com.example.data.VoiceProfileRepository.getAvailableSpeakerCount()

                        // Voice Engine Audit Panel
                        DiagnosisField(
                            label = "Speech Engine Mode",
                            value = if (isSherpaRuntimeReady && state.freeVoiceBackend == com.example.service.FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT) {
                                "SHERPA_DIRECT (Native JNI + ONNX Runtime Initialized • OpenRAIL-M)"
                            } else {
                                "SYSTEM_FALLBACK (${state.activeTtsEngineLabel} • ${sherpaEngine.initializationError ?: "Supertonic runtime not initialized"})"
                            }
                        )
                        DiagnosisField(
                            label = "Model Loaded & Verified",
                            value = when {
                                isSherpaRuntimeReady -> "true (Native handle valid • ${com.example.service.SupertonicModelManager.getModelDirectory(context).absolutePath})"
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
                            value = "SID #${activeChar?.speakerId ?: 0} (${if ((activeChar?.speakerId ?: 0) in 0..4) "Female F${(activeChar?.speakerId ?: 0) + 1}" else "Male M${(activeChar?.speakerId ?: 0) - 4}"} • Character: ${activeChar?.displayName ?: "Default"} • ${if (activeChar?.isUserOverride == true) "User Override" else "Heuristic Match"})"
                        )
                        DiagnosisField(
                            label = "Model Version & License",
                            value = "${com.example.service.SupertonicModelManager.modelInfo.name} • ${com.example.service.SupertonicModelManager.modelInfo.version} (${com.example.service.SupertonicModelManager.modelInfo.license})"
                        )
                        DiagnosisField(
                            label = "Model Licensing & Attribution",
                            value = com.example.service.SupertonicModelManager.modelInfo.attributionNotice
                        )
                        DiagnosisField(
                            label = "JNI Load Result",
                            value = com.k2fsa.sherpa.onnx.OfflineTts.jniStatusSummary
                        )
                        DiagnosisField(
                            label = "First PCM Chunk Latency",
                            value = if (state.ttsFirstPcmChunkLatencyMs > 0L) "${state.ttsFirstPcmChunkLatencyMs} ms (Neural generation callback)" else if (state.ttsFirstAudioLatencyMs > 0L) "${state.ttsFirstAudioLatencyMs} ms" else "0 ms (No dialogue synthesized yet)"
                        )
                        DiagnosisField(
                            label = "AudioTrack Write Latency",
                            value = if (state.ttsAudioTrackWriteLatencyMs > 0L) "${state.ttsAudioTrackWriteLatencyMs} ms (Bytes written to AudioTrack buffer)" else "—"
                        )
                        DiagnosisField(
                            label = "Estimated Acoustic Playout (DAC)",
                            value = if (state.ttsEstimatedPlayoutLatencyMs > 0L) "${state.ttsEstimatedPlayoutLatencyMs} ms (Write + ~HAL mixer delay; physical speaker acoustic latency)" else "—"
                        )
                        DiagnosisField(
                            label = "Utterance Duration",
                            value = if (state.ttsSpeechDurationMs > 0L) "${state.ttsSpeechDurationMs} ms" else "—"
                        )
                        DiagnosisField(
                            label = "Synthesis Real-Time Factor (RTF)",
                            value = if (state.ttsSynthesisRtf > 0.0f) {
                                "${String.format(java.util.Locale.US, "%.2fx", state.ttsSynthesisRtf)} (${if (state.ttsSynthesisRtf < 1.0f) "Faster than real-time" else "Slower than real-time"})"
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

                // 5. Real Voice Latency Benchmarks (Measured on Android TTS Engine)
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

                // 6. Measured Pipeline Latencies
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
            style = MaterialTheme.typography.bodySmall,
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
