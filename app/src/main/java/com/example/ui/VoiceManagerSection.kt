package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SpeakerRole
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SaffronGold
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceVariant
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun VoiceManagerSection(
    onPlayVoicePreview: (VoiceProfile) -> Unit,
    onSelectVoice: (VoiceProfile) -> Unit,
    onOpenFullScreen: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val voices by VoiceProfileRepository.voices.collectAsStateWithLifecycle()
    val selectedVoiceId by VoiceProfileRepository.selectedVoiceId.collectAsStateWithLifecycle()
    val multiSpeakerEnabled by VoiceProfileRepository.multiSpeakerEnabled.collectAsStateWithLifecycle()
    val lastDetectedSpeaker by VoiceProfileRepository.lastDetectedSpeaker.collectAsStateWithLifecycle()
    val lastAssignedVoiceName by VoiceProfileRepository.lastAssignedVoiceName.collectAsStateWithLifecycle()
    val lastHeuristicRule by VoiceProfileRepository.lastHeuristicRule.collectAsStateWithLifecycle()

    var showAddVoiceCloneDialog by remember { mutableStateOf(false) }

    Surface(
        color = SlateSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .testTag("voice_manager_section")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header + "+ Add Voice" (Clone) Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Voice List & Character Cloning (${voices.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Text(
                            text = "Play, Select, Delete or Clone 3–5s Custom Character Voices",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onOpenFullScreen != null) {
                        OutlinedButton(
                            onClick = onOpenFullScreen,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("open_voice_manager_screen_button")
                        ) {
                            Text(
                                text = "Full Screen",
                                color = ElectricCyan,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Button(
                        onClick = { showAddVoiceCloneDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("add_voice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Cloned Voice",
                            tint = ObsidianBg,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Add Voice",
                            color = ObsidianBg,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Multi-Speaker Dialogue Auto-Detection Toggle & Telemetry
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateSurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Character Tag Heuristic Engine",
                        style = MaterialTheme.typography.labelLarge,
                        color = NeonEmerald
                    )
                    Text(
                        text = "Tag: $lastDetectedSpeaker → $lastAssignedVoiceName • $lastHeuristicRule",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = multiSpeakerEnabled,
                    onCheckedChange = { enabled ->
                        VoiceProfileRepository.setMultiSpeakerEnabled(context, enabled)
                    },
                    modifier = Modifier.testTag("multi_speaker_toggle_switch")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Voice List (Each voice has Play, Select, and Delete)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                voices.forEach { voice ->
                    val isSelected = voice.id == selectedVoiceId
                    VoiceProfileCardItem(
                        voice = voice,
                        isSelected = isSelected,
                        canDelete = voices.size > 1,
                        onPlayPreview = {
                            if (voice.sampleAudioPath != null) {
                                VoiceProfileRepository.playRecordedReferenceSample(voice.sampleAudioPath) {
                                    onPlayVoicePreview(voice)
                                }
                            } else {
                                onPlayVoicePreview(voice)
                            }
                        },
                        onSelect = {
                            VoiceProfileRepository.selectVoice(context, voice)
                            onSelectVoice(voice)
                        },
                        onDelete = {
                            VoiceProfileRepository.deleteVoice(context, voice.id)
                        }
                    )
                }
            }

            // Restore Default Voices button if any preset was deleted
            if (voices.count { !it.isCustomCloned } < 4) {
                TextButton(
                    onClick = { VoiceProfileRepository.restoreDefaultVoices(context) },
                    modifier = Modifier
                        .align(Alignment.End)
                        .testTag("restore_default_voices_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restore Default Character Voices")
                }
            }
        }
    }

    if (showAddVoiceCloneDialog) {
        AddClonedVoiceDialog(
            onDismiss = { showAddVoiceCloneDialog = false },
            onPreviewClonedParameters = { tempProfile ->
                onPlayVoicePreview(tempProfile)
            },
            onVoiceSaved = { savedProfile ->
                showAddVoiceCloneDialog = false
                onSelectVoice(savedProfile)
                onPlayVoicePreview(savedProfile)
            }
        )
    }
}

@Composable
internal fun VoiceProfileCardItem(
    voice: VoiceProfile,
    isSelected: Boolean,
    canDelete: Boolean,
    onPlayPreview: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = if (isSelected) SlateCardElevated else ObsidianBg,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) NeonEmerald else ElectricCyan.copy(alpha = 0.28f),
                shape = RoundedCornerShape(14.dp)
            )
            .testTag("voice_item_card_${voice.id}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) NeonEmerald.copy(alpha = 0.2f)
                                else ElectricCyan.copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = if (isSelected) NeonEmerald else ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = voice.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            if (voice.isCustomCloned) {
                                Badge(
                                    containerColor = SaffronGold.copy(alpha = 0.22f),
                                    contentColor = SaffronGold
                                ) {
                                    Text(
                                        text = "PRESET",
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${voice.assignedRole.displayLabel} • Pitch ${String.format(Locale.US, "%.2fx", voice.pitch)} • Rate ${String.format(Locale.US, "%.2fx", voice.speechRate)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                }

                if (canDelete) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_voice_button_${voice.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Voice ${voice.name}",
                            tint = CrimsonAlert
                        )
                    }
                }
            }

            Text(
                text = voice.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Action Row: Play (Preview) & Select (Use)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPlayPreview,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("play_voice_button_${voice.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Voice Preview",
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play Preview", color = ElectricCyan)
                }

                Button(
                    onClick = onSelect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) NeonEmerald else SlateSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("select_voice_button_${voice.id}")
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = ObsidianBg,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Selected",
                            color = ObsidianBg,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Select Voice",
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddClonedVoiceDialog(
    onDismiss: () -> Unit,
    onPreviewClonedParameters: (VoiceProfile) -> Unit,
    onVoiceSaved: (VoiceProfile) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var voiceName by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf(SpeakerRole.CHARACTER_A) }
    var clonedPitch by remember { mutableFloatStateOf(0.95f) }
    var clonedRate by remember { mutableFloatStateOf(0.95f) }
    var savedSamplePath by remember { mutableStateOf<String?>(null) }
    var analysisSummary by remember {
        mutableStateOf("Record 3–5 seconds of speech or upload an audio clip to clone its pitch & cadence.")
    }

    var isRecording by remember { mutableStateOf(false) }
    var recordingProgress by remember { mutableFloatStateOf(0f) }
    var liveMicLevel by remember { mutableFloatStateOf(0f) }

    val startFourSecondRecording: () -> Unit = {
        if (!isRecording) {
            isRecording = true
            recordingProgress = 0f
            analysisSummary = "Recording 4-second voice sample... Speak naturally into the mic!"
            scope.launch {
                val result = VoiceProfileRepository.recordAndAnalyzeVoiceSample(
                    context = context,
                    durationSeconds = 4,
                    onProgressUpdate = { progress, rms ->
                        recordingProgress = progress
                        liveMicLevel = rms
                    }
                )
                isRecording = false
                savedSamplePath = result.savedAudioPath
                clonedPitch = result.clonedPitch
                clonedRate = result.clonedSpeechRate
                analysisSummary = result.acousticSummary
                if (voiceName.isBlank()) {
                    voiceName = "My Cloned Voice (${result.estimatedFundamentalHz.toInt()}Hz)"
                }
            }
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startFourSecondRecording()
        } else {
            analysisSummary = "Microphone permission declined — you can still upload an audio file or tune sliders below."
        }
    }

    val audioFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            analysisSummary = "Analyzing uploaded audio file..."
            scope.launch {
                val result = VoiceProfileRepository.importAndAnalyzeAudioUri(context, uri)
                savedSamplePath = result.savedAudioPath
                clonedPitch = result.clonedPitch
                clonedRate = result.clonedSpeechRate
                analysisSummary = result.acousticSummary
                if (voiceName.isBlank()) {
                    voiceName = "Uploaded Voice Clone"
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isRecording) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Add Voice-Style Preset",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = voiceName,
                    onValueChange = { voiceName = it },
                    label = { Text("Voice Name (e.g. Jenny, Arjun)") },
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("clone_voice_name_input")
                )

                // Record 3-5s Audio OR Upload Audio File buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val hasMicPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMicPerm) {
                                startFourSecondRecording()
                            } else {
                                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !isRecording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("record_voice_sample_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Record 4s Voice Sample",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isRecording) "Recording..." else "Record 4s",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = { audioFilePickerLauncher.launch("audio/*") },
                        enabled = !isRecording,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("upload_voice_audio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AudioFile,
                            contentDescription = "Upload Audio File",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Upload")
                    }
                }

                AnimatedVisibility(visible = isRecording) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { recordingProgress },
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Capturing sample... ${(recordingProgress * 100).toInt()}% (Level ${(liveMicLevel * 100).toInt()}%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Text(
                    text = analysisSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (savedSamplePath != null) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                // Assign to Multi-Speaker Character Slot
                Text(
                    text = "Character Role:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SpeakerRole.entries.forEach { role ->
                        val selected = selectedRole == role
                        FilterChip(
                            selected = selected,
                            onClick = { selectedRole = role },
                            label = {
                                Text(
                                    text = role.displayLabel.substringBefore(" ("),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.testTag("clone_role_chip_${role.code}")
                        )
                    }
                }

                // Cloned Pitch & Speech Rate Fine-Tuning
                Column {
                    Text(
                        text = String.format(Locale.US, "Pitch: %.2fx", clonedPitch),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = clonedPitch,
                        onValueChange = { clonedPitch = it },
                        valueRange = 0.50f..1.65f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("clone_pitch_slider")
                    )

                    Text(
                        text = String.format(Locale.US, "Speed: %.2fx", clonedRate),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = clonedRate,
                        onValueChange = { clonedRate = it },
                        valueRange = 0.60f..1.50f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("clone_rate_slider")
                    )
                }

                OutlinedButton(
                    onClick = {
                        val tempVoice = VoiceProfile(
                            id = "preview_temp",
                            name = voiceName.ifBlank { "Cloned Voice" },
                            description = analysisSummary,
                            pitch = clonedPitch,
                            speechRate = clonedRate,
                            assignedRole = selectedRole,
                            isCustomCloned = true,
                            sampleAudioPath = savedSamplePath
                        )
                        onPreviewClonedParameters(tempVoice)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preview_cloned_voice_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Voice")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val created = VoiceProfileRepository.addCustomClonedVoice(
                        context = context,
                        name = voiceName.ifBlank { "Custom Voice" },
                        description = analysisSummary,
                        pitch = clonedPitch,
                        speechRate = clonedRate,
                        assignedRole = selectedRole,
                        sampleAudioPath = savedSamplePath
                    )
                    onVoiceSaved(created)
                },
                enabled = !isRecording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.testTag("save_cloned_voice_button")
            ) {
                Text(
                    text = "Save Voice",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRecording,
                modifier = Modifier.testTag("cancel_clone_voice_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
