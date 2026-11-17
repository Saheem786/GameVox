package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.data.SampleAudioInspection
import com.example.data.SpeakerGender
import com.example.data.VoiceCategory
import com.example.data.VoiceProfileRepository
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceSampleImportSection(
    voiceName: String,
    selectedGender: SpeakerGender,
    selectedLanguage: String,
    selectedStyle: String,
    consentConfirmed: Boolean,
    isChatterboxConnected: Boolean = false,
    onConsentChange: (Boolean) -> Unit,
    onSampleReadyForLibrary: (
        audioPath: String,
        inspection: SampleAudioInspection,
        category: VoiceCategory,
        sourceInfo: String,
        estimatedPitch: Float,
        estimatedRate: Float,
        isNeuralCloned: Boolean
    ) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var recordDurationSec by remember { mutableIntStateOf(6) }
    var isRecording by remember { mutableStateOf(false) }
    var recordProgress by remember { mutableFloatStateOf(0f) }
    var recordRms by remember { mutableFloatStateOf(0f) }

    var latestAudioPath by remember { mutableStateOf<String?>(null) }
    var latestInspection by remember { mutableStateOf<SampleAudioInspection?>(null) }
    var latestAcousticPitch by remember { mutableFloatStateOf(selectedGender.defaultPitch18Yo) }
    var latestAcousticRate by remember { mutableFloatStateOf(selectedGender.defaultSpeechRate18Yo) }

    fun startSampleRecording() {
        if (isRecording) return
        isRecording = true
        recordProgress = 0f
        recordRms = 0f
        scope.launch {
            val result = VoiceProfileRepository.recordAndAnalyzeVoiceSample(
                context = context,
                durationSeconds = recordDurationSec
            ) { prog, rms ->
                recordProgress = prog
                recordRms = rms
            }
            isRecording = false
            val savedPath = result.savedAudioPath
            if (savedPath != null && File(savedPath).exists()) {
                val inspected = VoiceProfileRepository.inspectAudioSample(File(savedPath))
                latestAudioPath = savedPath
                latestInspection = inspected
                latestAcousticPitch = result.clonedPitch
                latestAcousticRate = result.clonedSpeechRate
                onShowMessage("Recorded ${recordDurationSec}s sample: ${inspected.qualityVerdict}")
            } else {
                onShowMessage("Microphone capture failed or did not produce enough audio.")
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSampleRecording()
        } else {
            onShowMessage("Microphone permission denied — you can still import an audio file using Open Audio.")
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = VoiceProfileRepository.importAndAnalyzeAudioUri(context, uri)
                val savedPath = result.savedAudioPath
                if (savedPath != null && File(savedPath).exists()) {
                    val inspected = VoiceProfileRepository.inspectAudioSample(File(savedPath))
                    latestAudioPath = savedPath
                    latestInspection = inspected
                    latestAcousticPitch = result.clonedPitch
                    latestAcousticRate = result.clonedSpeechRate
                    onShowMessage("Imported audio: ${inspected.qualityVerdict}")
                } else {
                    onShowMessage("Could not read selected audio file.")
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Import or Record 5–10s Clean Reference Audio",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Select an audio file via Android's document picker (zero storage permissions needed) or record 5–10s from your microphone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(5, 6, 8, 10).forEach { sec ->
                FilterChip(
                    selected = recordDurationSec == sec,
                    onClick = { if (!isRecording) recordDurationSec = sec },
                    label = { Text("${sec}s Mic") },
                    modifier = Modifier.testTag("record_duration_${sec}s")
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                enabled = !isRecording,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("import_audio_sample_button")
            ) {
                Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import Audio")
            }

            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        startSampleRecording()
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !isRecording,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("record_audio_sample_button")
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isRecording) "Recording..." else "Record ${recordDurationSec}s")
            }
        }

        if (isRecording) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { recordProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Recording ${recordDurationSec}s sample • Mic RMS ${String.format(Locale.US, "%.2f", recordRms)}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        latestInspection?.let { info ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (info.recommendedForCloning) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
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
                            imageVector = if (info.recommendedForCloning) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = if (info.recommendedForCloning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Quality: ${info.qualityVerdict}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = info.qualityDetail,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (info.sha256.isNotBlank()) {
                        Text(
                            text = "SHA-256: ${info.sha256.take(16)}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = info.cloningCompatibilityNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (info.cloningBackendCompatible) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

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
                    onCheckedChange = onConsentChange,
                    modifier = Modifier.testTag("voice_clone_consent_checkbox")
                )
                Text(
                    text = "I confirm I own this voice sample or have explicit permission to use it for game dubbing.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        val inspected = latestInspection
        val samplePath = latestAudioPath
        if (inspected != null && samplePath != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onSampleReadyForLibrary(
                            samplePath,
                            inspected,
                            VoiceCategory.IMPORTED,
                            "Acoustic Pitch/Rate Preset (${inspected.qualityVerdict}) • Not Neural Cloning",
                            latestAcousticPitch,
                            latestAcousticRate,
                            false
                        )
                    },
                    enabled = consentConfirmed,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("save_imported_voice_button")
                ) {
                    Text("Save Acoustic Preset")
                }

                Button(
                    onClick = {
                        if (!isChatterboxConnected) {
                            onShowMessage("Neural cloning requires a connected PC Chatterbox server (open Online tab -> Test Server first).")
                            return@Button
                        }
                        onSampleReadyForLibrary(
                            samplePath,
                            inspected,
                            VoiceCategory.CLONED,
                            "Chatterbox Cloned Sample (${inspected.sha256.take(8)}) • $selectedLanguage",
                            latestAcousticPitch,
                            latestAcousticRate,
                            true
                        )
                    },
                    enabled = consentConfirmed && inspected.cloningBackendCompatible && isChatterboxConnected,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("clone_via_chatterbox_button")
                ) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isChatterboxConnected) "Neural Clone" else "Clone (Server Offline)")
                }
            }
        }
    }
}
