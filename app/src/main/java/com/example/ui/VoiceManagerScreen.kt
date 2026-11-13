package com.example.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SpeakerRole
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.service.ForegroundTtsManager
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

private enum class VoiceListFilter(val label: String) {
    ALL("All Voices"),
    CUSTOM_ONLY("Custom Cloned"),
    CHARACTER_A("Character A"),
    CHARACTER_B("Character B"),
    CHARACTER_C("Character C (Boss)"),
    NARRATOR("Narrator")
}

/**
 * Dedicated Jetpack Compose screen that lists available voices (both built-in character
 * archetypes and locally stored custom cloned voices) and includes controls to Play, Select,
 * or Delete custom voice profiles, plus a 3-5 second Voice Cloning recorder & audio importer.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VoiceManagerScreen(
    onNavigateBack: () -> Unit,
    onPlayVoicePreview: (VoiceProfile) -> Unit,
    onSelectVoice: (VoiceProfile) -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ttsManager = remember(context) { ForegroundTtsManager.getInstance(context) }

    val voices by VoiceProfileRepository.voices.collectAsStateWithLifecycle()
    val selectedVoiceId by VoiceProfileRepository.selectedVoiceId.collectAsStateWithLifecycle()
    val multiSpeakerEnabled by VoiceProfileRepository.multiSpeakerEnabled.collectAsStateWithLifecycle()
    val lastDetectedSpeaker by VoiceProfileRepository.lastDetectedSpeaker.collectAsStateWithLifecycle()
    val lastAssignedVoiceName by VoiceProfileRepository.lastAssignedVoiceName.collectAsStateWithLifecycle()
    val lastHeuristicRule by VoiceProfileRepository.lastHeuristicRule.collectAsStateWithLifecycle()
    val roleVoiceAssignments by VoiceProfileRepository.roleVoiceAssignments.collectAsStateWithLifecycle()
    val customTagVoiceBindings by VoiceProfileRepository.customTagVoiceBindings.collectAsStateWithLifecycle()

    var activeFilter by remember { mutableStateOf(VoiceListFilter.ALL) }
    var showAddVoiceCloneDialog by remember { mutableStateOf(false) }
    var voicePendingDeletion by remember { mutableStateOf<VoiceProfile?>(null) }
    var customCharacterTagInput by remember { mutableStateOf("") }

    val filteredVoices = remember(voices, activeFilter) {
        when (activeFilter) {
            VoiceListFilter.ALL -> voices
            VoiceListFilter.CUSTOM_ONLY -> voices.filter { it.isCustomCloned }
            VoiceListFilter.CHARACTER_A -> voices.filter { it.assignedRole == SpeakerRole.CHARACTER_A }
            VoiceListFilter.CHARACTER_B -> voices.filter { it.assignedRole == SpeakerRole.CHARACTER_B }
            VoiceListFilter.CHARACTER_C -> voices.filter { it.assignedRole == SpeakerRole.CHARACTER_C }
            VoiceListFilter.NARRATOR -> voices.filter { it.assignedRole == SpeakerRole.NARRATOR }
        }
    }

    val customCount = remember(voices) { voices.count { it.isCustomCloned } }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = ObsidianBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ObsidianBg,
                    titleContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("voice_screen_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Dashboard",
                            tint = ElectricCyan
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Voice Studio & Character Voices",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "${voices.size} Total Voices • $customCount Custom Cloned Profiles",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                },
                actions = {
                    if (voices.count { !it.isCustomCloned } < 4) {
                        IconButton(
                            onClick = {
                                VoiceProfileRepository.restoreDefaultVoices(context)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Default character voices restored.")
                                }
                            },
                            modifier = Modifier.testTag("voice_screen_restore_defaults_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Restore Default Voices",
                                tint = SaffronGold
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddVoiceCloneDialog = true },
                containerColor = ElectricCyan,
                contentColor = ObsidianBg,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add or Clone New Voice"
                    )
                },
                text = {
                    Text(
                        text = "Add / Clone Voice",
                        fontWeight = FontWeight.Bold
                    )
                },
                modifier = Modifier.testTag("voice_screen_fab_add_voice")
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("voice_manager_screen"),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 0. Real-Time Canvas TTS Audio Visualizer synced with ForegroundTtsManager
                item {
                    val activeSelectedVoice = voices.find { it.id == selectedVoiceId } ?: voices.firstOrNull()
                    TtsAudioVisualizerCard(
                        ttsManager = ttsManager,
                        fallbackTextToSpeak = "नमस्ते! यह ${activeSelectedVoice?.name ?: "GameVox"} की आवाज़ है।",
                        onSpeakSample = {
                            if (activeSelectedVoice != null) {
                                onPlayVoicePreview(activeSelectedVoice)
                            }
                        }
                    )
                }

                // 1. Multi-Speaker Character Slot Mapping & Telemetry Banner
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = SlateSurface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Character Tag Heuristic Engine",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = NeonEmerald
                                    )
                                    Text(
                                        text = "Last Tag: $lastDetectedSpeaker → $lastAssignedVoiceName",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Matched Rule: $lastHeuristicRule",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ElectricCyan
                                    )
                                }
                                Switch(
                                    checked = multiSpeakerEnabled,
                                    onCheckedChange = { enabled ->
                                        VoiceProfileRepository.setMultiSpeakerEnabled(context, enabled)
                                    },
                                    modifier = Modifier.testTag("voice_screen_multi_speaker_switch")
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                            Text(
                                text = "Active Character Slot Assignments (Mapped from Scan Tags):",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SpeakerRole.entries.forEach { role ->
                                    val assignedVoice = remember(voices, roleVoiceAssignments, selectedVoiceId) {
                                        VoiceProfileRepository.getVoiceForRole(role)
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(SlateSurfaceVariant)
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = role.displayLabel,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = ElectricCyan,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "${assignedVoice.name} (${String.format(Locale.US, "%.2fx pitch • %.2fx rate", assignedVoice.pitch, assignedVoice.speechRate)})",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White
                                            )
                                        }
                                        IconButton(
                                            onClick = { onPlayVoicePreview(assignedVoice) },
                                            modifier = Modifier.testTag("test_role_voice_${role.code}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.VolumeUp,
                                                contentDescription = "Preview ${role.displayLabel}",
                                                tint = NeonEmerald
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                            // Custom Character Name Tag -> Selected Voice Binder
                            val currentSelectedVoice = voices.find { it.id == selectedVoiceId } ?: voices.firstOrNull()
                            Text(
                                text = "Bind Custom Scan Tag (e.g. 'Character A:', 'Guard:') to Selected Voice (${currentSelectedVoice?.name ?: "Default"}):",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customCharacterTagInput,
                                    onValueChange = { customCharacterTagInput = it },
                                    placeholder = { Text("e.g. Character A:") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("custom_character_tag_input")
                                )
                                Button(
                                    onClick = {
                                        val activeVoice = currentSelectedVoice ?: return@Button
                                        if (customCharacterTagInput.isNotBlank()) {
                                            VoiceProfileRepository.bindCustomCharacterTag(
                                                context = context,
                                                characterTag = customCharacterTagInput,
                                                voiceId = activeVoice.id
                                            )
                                            val tagLabel = customCharacterTagInput.trim()
                                            customCharacterTagInput = ""
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "Mapped scan tag '$tagLabel' → ${activeVoice.name}"
                                                )
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                                    modifier = Modifier.testTag("bind_character_tag_button")
                                ) {
                                    Text("Map Tag", color = ObsidianBg, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (customTagVoiceBindings.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    customTagVoiceBindings.forEach { (tagKey, boundId) ->
                                        val voiceName = voices.find { it.id == boundId }?.name ?: boundId
                                        FilterChip(
                                            selected = true,
                                            onClick = {
                                                VoiceProfileRepository.removeCustomCharacterTag(context, tagKey)
                                            },
                                            label = {
                                                Text(
                                                    text = "'$tagKey' → $voiceName (✕)",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = SaffronGold.copy(alpha = 0.22f),
                                                selectedLabelColor = SaffronGold
                                            )
                                        )
                                    }
                                }
                            }

                            // Quick Heuristic Scan Tester
                            Text(
                                text = "Test Scan Tag Heuristic Detection:",
                                style = MaterialTheme.typography.labelMedium,
                                color = SaffronGold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listOf(
                                    "Character A: Hold the northern gate!",
                                    "[Character B] The ancient portal is open.",
                                    "Character C: You dare challenge the Eternal Flame?",
                                    "System: Security override complete."
                                ).forEachIndexed { idx, sampleScan ->
                                    OutlinedButton(
                                        onClick = {
                                            val parsed = VoiceProfileRepository.resolveSpeakerAndVoiceForSubtitle(sampleScan)
                                            onPlayVoicePreview(parsed.assignedVoice)
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "Tag '${parsed.speakerName}' → ${parsed.assignedVoice.name}"
                                                )
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("test_heuristic_sample_$idx")
                                    ) {
                                        Text(
                                            text = sampleScan.substringBefore(" ").trimEnd(':') + " Test",
                                            color = ElectricCyan,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Filter Chips (All Voices, Custom Cloned, Character A/B/C, Narrator)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Available Voice Profiles (${filteredVoices.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            TextButton(
                                onClick = { showAddVoiceCloneDialog = true },
                                modifier = Modifier.testTag("voice_screen_header_add_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clone 3–5s Voice", color = ElectricCyan)
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            VoiceListFilter.entries.forEach { filter ->
                                val selected = activeFilter == filter
                                FilterChip(
                                    selected = selected,
                                    onClick = { activeFilter = filter },
                                    label = {
                                        Text(
                                            text = filter.label,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ElectricCyan,
                                        selectedLabelColor = ObsidianBg
                                    ),
                                    modifier = Modifier.testTag("voice_filter_chip_${filter.name.lowercase()}")
                                )
                            }
                        }
                    }
                }

                // 3. Empty State when filtering Custom Cloned voices and none exist yet
                if (filteredVoices.isEmpty()) {
                    item {
                        Surface(
                            color = SlateSurface,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("empty_voice_list_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "No Voice Profiles in '${activeFilter.label}'",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = "Tap 'Add / Clone Voice' below to record 3–5 seconds of audio or upload an audio clip to create a custom character voice profile.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = { showAddVoiceCloneDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                                    modifier = Modifier.testTag("empty_state_add_voice_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = ObsidianBg
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Create Custom Voice Profile",
                                        color = ObsidianBg,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 4. Voice Profile Cards with Play, Select, and Delete controls
                    items(filteredVoices, key = { it.id }) { voice ->
                        val isSelected = voice.id == selectedVoiceId
                        VoiceScreenProfileCard(
                            voice = voice,
                            isSelected = isSelected,
                            canDelete = voices.size > 1,
                            onPlayTtsPreview = {
                                onPlayVoicePreview(voice)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Playing Hindi preview: ${voice.name}")
                                }
                            },
                            onPlayRecordedSample = {
                                val path = voice.sampleAudioPath
                                if (path != null) {
                                    VoiceProfileRepository.playRecordedReferenceSample(path) {
                                        onPlayVoicePreview(voice)
                                    }
                                } else {
                                    onPlayVoicePreview(voice)
                                }
                            },
                            onSelect = {
                                VoiceProfileRepository.selectVoice(context, voice)
                                onSelectVoice(voice)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Selected '${voice.name}' for ${voice.assignedRole.displayLabel}"
                                    )
                                }
                            },
                            onRequestDelete = {
                                if (voice.isCustomCloned) {
                                    voicePendingDeletion = voice
                                } else {
                                    VoiceProfileRepository.deleteVoice(context, voice.id)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Removed '${voice.name}'. Tap Restore in top bar anytime.")
                                    }
                                }
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(88.dp)) }
            }
        }
    }

    // Add / Clone Custom Voice Modal Dialog
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
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Custom voice '${savedProfile.name}' saved locally!")
                }
            }
        )
    }

    // Confirmation Dialog for Deleting a Custom Cloned Voice Profile
    voicePendingDeletion?.let { targetVoice ->
        AlertDialog(
            onDismissRequest = { voicePendingDeletion = null },
            containerColor = SlateSurface,
            title = {
                Text(
                    text = "Delete Custom Voice Profile?",
                    style = MaterialTheme.typography.titleMedium,
                    color = CrimsonAlert
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete '${targetVoice.name}' and its locally saved voice sample?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        VoiceProfileRepository.deleteVoice(context, targetVoice.id)
                        voicePendingDeletion = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Deleted custom voice '${targetVoice.name}'")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert),
                    modifier = Modifier.testTag("confirm_delete_custom_voice_button")
                ) {
                    Text("Delete Voice", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { voicePendingDeletion = null },
                    modifier = Modifier.testTag("cancel_delete_custom_voice_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun VoiceScreenProfileCard(
    voice: VoiceProfile,
    isSelected: Boolean,
    canDelete: Boolean,
    onPlayTtsPreview: () -> Unit,
    onPlayRecordedSample: () -> Unit,
    onSelect: () -> Unit,
    onRequestDelete: () -> Unit
) {
    Surface(
        color = if (isSelected) SlateCardElevated else SlateSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) NeonEmerald else ElectricCyan.copy(alpha = 0.32f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("voice_screen_card_${voice.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) NeonEmerald.copy(alpha = 0.22f)
                                else ElectricCyan.copy(alpha = 0.16f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = if (isSelected) NeonEmerald else ElectricCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = voice.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            if (voice.isCustomCloned) {
                                Badge(
                                    containerColor = SaffronGold.copy(alpha = 0.22f),
                                    contentColor = SaffronGold
                                ) {
                                    Text(
                                        text = "CUSTOM CLONE",
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            } else {
                                Badge(
                                    containerColor = ElectricCyan.copy(alpha = 0.18f),
                                    contentColor = ElectricCyan
                                ) {
                                    Text(
                                        text = "PRESET",
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${voice.assignedRole.displayLabel} • Pitch ${String.format(Locale.US, "%.2fx", voice.pitch)} • Speed ${String.format(Locale.US, "%.2fx", voice.speechRate)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                }

                // Delete control
                if (canDelete) {
                    IconButton(
                        onClick = onRequestDelete,
                        modifier = Modifier.testTag("voice_screen_delete_button_${voice.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete ${voice.name}",
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

            // Controls Row: Play, Optional Recorded Sample Playback, and Select
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPlayTtsPreview,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("voice_screen_play_button_${voice.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Voice Preview",
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }

                if (voice.sampleAudioPath != null) {
                    OutlinedButton(
                        onClick = onPlayRecordedSample,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("voice_screen_play_sample_button_${voice.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Play Recorded Audio Sample",
                            tint = SaffronGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sample", color = SaffronGold)
                    }
                }

                Button(
                    onClick = onSelect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) NeonEmerald else SlateSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("voice_screen_select_button_${voice.id}")
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = ObsidianBg,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Selected",
                            color = ObsidianBg,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Select",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
