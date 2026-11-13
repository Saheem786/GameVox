package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SpeakerGender
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import java.util.Locale
import kotlinx.coroutines.launch

private enum class VoiceGenderFilter(val label: String) {
    ALL("All"),
    FEMALE("Female"),
    MALE("Male"),
    CUSTOM("Custom")
}

/**
 * Clean, modern Material 3 Voice Manager Screen using LazyColumn.
 * Each voice card displays Voice Name, Gender icon (Male/Female), and 3 clear actions:
 * Play, Select, and Delete (styled in Red).
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

    val voices by VoiceProfileRepository.voices.collectAsStateWithLifecycle()
    val selectedVoiceId by VoiceProfileRepository.selectedVoiceId.collectAsStateWithLifecycle()

    var activeFilter by remember { mutableStateOf(VoiceGenderFilter.ALL) }
    var showAddVoiceCloneDialog by remember { mutableStateOf(false) }
    var voicePendingDeletion by remember { mutableStateOf<VoiceProfile?>(null) }

    val filteredVoices = remember(voices, activeFilter) {
        when (activeFilter) {
            VoiceGenderFilter.ALL -> voices
            VoiceGenderFilter.FEMALE -> voices.filter { it.gender == SpeakerGender.FEMALE_18 }
            VoiceGenderFilter.MALE -> voices.filter { it.gender == SpeakerGender.MALE_18 }
            VoiceGenderFilter.CUSTOM -> voices.filter { it.isCustomCloned }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("voice_screen_back_button")
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
                            text = "Voice Manager",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${voices.size} voices available",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (voices.count { !it.isCustomCloned } < 4) {
                        IconButton(
                            onClick = {
                                VoiceProfileRepository.restoreDefaultVoices(context)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Default voices restored")
                                }
                            },
                            modifier = Modifier.testTag("voice_screen_restore_defaults_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Restore Default Voices",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddVoiceCloneDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Voice"
                    )
                },
                text = {
                    Text(
                        text = "Add Voice",
                        fontWeight = FontWeight.SemiBold
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
                    .widthIn(max = 600.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Compact filter chips row
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        VoiceGenderFilter.entries.forEach { filter ->
                            val selected = activeFilter == filter
                            FilterChip(
                                selected = selected,
                                onClick = { activeFilter = filter },
                                label = {
                                    Text(
                                        text = filter.label,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("voice_filter_chip_${filter.name.lowercase()}")
                            )
                        }
                    }
                }

                if (filteredVoices.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("empty_voice_list_card")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "No ${activeFilter.label} voices yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Button(
                                    onClick = { showAddVoiceCloneDialog = true },
                                    modifier = Modifier.testTag("empty_state_add_voice_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add Voice")
                                }
                            }
                        }
                    }
                } else {
                    items(filteredVoices, key = { it.id }) { voice ->
                        val isSelected = voice.id == selectedVoiceId
                        CleanVoiceCard(
                            voice = voice,
                            isSelected = isSelected,
                            canDelete = voices.size > 1,
                            onPlay = {
                                val path = voice.sampleAudioPath
                                if (path != null) {
                                    VoiceProfileRepository.playRecordedReferenceSample(path) {
                                        onPlayVoicePreview(voice)
                                    }
                                } else {
                                    onPlayVoicePreview(voice)
                                }
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Playing ${voice.name}")
                                }
                            },
                            onSelect = {
                                VoiceProfileRepository.selectVoice(context, voice)
                                onSelectVoice(voice)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Active voice: ${voice.name}")
                                }
                            },
                            onDelete = {
                                if (voice.isCustomCloned) {
                                    voicePendingDeletion = voice
                                } else {
                                    VoiceProfileRepository.deleteVoice(context, voice.id)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Deleted ${voice.name}")
                                    }
                                }
                            }
                        )
                    }
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
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Saved '${savedProfile.name}'")
                }
            }
        )
    }

    voicePendingDeletion?.let { targetVoice ->
        AlertDialog(
            onDismissRequest = { voicePendingDeletion = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Delete Voice?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = "Delete '${targetVoice.name}' from your saved voices?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        VoiceProfileRepository.deleteVoice(context, targetVoice.id)
                        voicePendingDeletion = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Deleted '${targetVoice.name}'")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_custom_voice_button")
                ) {
                    Text("Delete", fontWeight = FontWeight.SemiBold)
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
private fun CleanVoiceCard(
    voice: VoiceProfile,
    isSelected: Boolean,
    canDelete: Boolean,
    onPlay: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val isFemale = voice.gender == SpeakerGender.FEMALE_18
    val genderLabel = if (isFemale) "Female" else "Male"
    val genderIcon = if (isFemale) Icons.Default.Female else Icons.Default.Male

    val borderStroke = if (isSelected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = borderStroke,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_screen_card_${voice.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Gender Icon + Voice Name + Active Badge
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
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = genderIcon,
                            contentDescription = genderLabel,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = voice.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = String.format(
                                Locale.US,
                                "%s • Pitch %.2fx • Speed %.2fx",
                                genderLabel,
                                voice.pitch,
                                voice.speechRate
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 3 Clean Actions Row: Play | Select | Delete (Red)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onPlay,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("voice_screen_play_button_${voice.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play ${voice.name}",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Play",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Button(
                    onClick = onSelect,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondary
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("voice_screen_select_button_${voice.id}")
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = if (isSelected) "Selected" else "Select",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onDelete,
                    enabled = canDelete,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (canDelete) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("voice_screen_delete_button_${voice.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete ${voice.name}",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Delete",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
