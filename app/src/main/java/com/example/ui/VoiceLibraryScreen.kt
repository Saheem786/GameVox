package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.CharacterVoiceAssignment
import com.example.data.SpeakerGender
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceCategory
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.engine.SpeechEmotion
import com.example.service.ForegroundTtsManager
import com.example.service.SupertonicModelManager
import com.example.service.SupertonicModelState
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

enum class VoiceLibraryFilter(val label: String) {
    ALL("All"),
    DEFAULT_MALE("Default Male"),
    DEFAULT_FEMALE("Default Female"),
    IMPORTED("Imported"),
    GENERATED("Generated"),
    CLONED("Cloned"),
    HIDDEN("Hidden")
}

@Composable
fun VoiceLibraryScreen(
    snackbarHostState: SnackbarHostState,
    onAddVoiceClick: () -> Unit,
    onPlayVoicePreview: (VoiceProfile) -> Unit,
    onSelectVoice: (VoiceProfile) -> Unit,
    onOpenAbVoiceTesting: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val voices by VoiceProfileRepository.voices.collectAsStateWithLifecycle()
    val selectedVoiceId by VoiceProfileRepository.selectedVoiceId.collectAsStateWithLifecycle()
    val characterAssignments by VoiceProfileRepository.characterAssignments.collectAsStateWithLifecycle()
    val appState by TranslatorStateRepository.state.collectAsStateWithLifecycle()
    val supertonicState by SupertonicModelManager.modelState.collectAsStateWithLifecycle()

    var integrityReport by remember(supertonicState) {
        mutableStateOf(SupertonicModelManager.verifyModelIntegrity(context))
    }
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(VoiceLibraryFilter.ALL) }
    var showModelPanel by remember { mutableStateOf(true) }

    var voiceToRename by remember { mutableStateOf<VoiceProfile?>(null) }
    var voiceToAssign by remember { mutableStateOf<VoiceProfile?>(null) }
    var voicePendingDeletion by remember { mutableStateOf<VoiceProfile?>(null) }

    LaunchedEffect(Unit) {
        integrityReport = SupertonicModelManager.verifyModelIntegrity(context)
        if (integrityReport.allValid && supertonicState !is SupertonicModelState.Ready) {
            ForegroundTtsManager.getInstance(context).runSupertonicReadinessCheck { _, _ ->
                integrityReport = SupertonicModelManager.verifyModelIntegrity(context)
            }
        }
    }

    val filteredVoices = remember(voices, searchQuery, activeFilter) {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        voices.filter { v ->
            val matchesFilter = when (activeFilter) {
                VoiceLibraryFilter.HIDDEN -> v.isHidden
                VoiceLibraryFilter.ALL -> !v.isHidden
                VoiceLibraryFilter.DEFAULT_MALE -> !v.isHidden && v.voiceCategory == VoiceCategory.DEFAULT_MALE
                VoiceLibraryFilter.DEFAULT_FEMALE -> !v.isHidden && v.voiceCategory == VoiceCategory.DEFAULT_FEMALE
                VoiceLibraryFilter.IMPORTED -> !v.isHidden && v.voiceCategory == VoiceCategory.IMPORTED
                VoiceLibraryFilter.GENERATED -> !v.isHidden && (v.voiceCategory == VoiceCategory.GENERATED_OFFLINE ||
                    v.voiceCategory == VoiceCategory.GENERATED_ONLINE)
                VoiceLibraryFilter.CLONED -> !v.isHidden && v.voiceCategory == VoiceCategory.CLONED
            }
            val matchesQuery = q.isEmpty() ||
                v.name.lowercase(Locale.ROOT).contains(q) ||
                v.sourceModelInfo.lowercase(Locale.ROOT).contains(q) ||
                v.styleTag.lowercase(Locale.ROOT).contains(q)
            matchesFilter && matchesQuery
        }
    }
    val visibleCount = remember(voices) { voices.count { !it.isHidden } }
    val hiddenCount = remember(voices) { voices.count { it.isHidden } }

    voiceToRename?.let { target ->
        RenameVoiceDialog(
            voice = target,
            onDismiss = { voiceToRename = null },
            onConfirmRename = { newName ->
                VoiceProfileRepository.renameVoice(context, target.id, newName)
                voiceToRename = null
                scope.launch { snackbarHostState.showSnackbar("Renamed voice to \"$newName\"") }
            }
        )
    }

    voiceToAssign?.let { target ->
        AssignVoiceToCharacterDialog(
            voice = target,
            knownCharacters = characterAssignments,
            onDismiss = { voiceToAssign = null },
            onAssignToCharacter = { charName ->
                val assigned = VoiceProfileRepository.assignVoiceToCharacter(context, target, charName)
                voiceToAssign = null
                scope.launch {
                    snackbarHostState.showSnackbar("Assigned \"${target.name}\" to ${assigned.displayName}")
                }
            }
        )
    }

    voicePendingDeletion?.let { target ->
        val affected = VoiceProfileRepository.getCharactersAssignedToVoice(target.id)
        val replacements = voices.filter { it.id != target.id }
        DeleteVoiceConfirmationDialog(
            voice = target,
            affectedCharacters = affected,
            replacementCandidates = replacements,
            onDismiss = { voicePendingDeletion = null },
            onConfirmDelete = { replacementId ->
                val result = VoiceProfileRepository.deleteVoiceSafely(context, target.id, replacementId)
                voicePendingDeletion = null
                result.fold(
                    onSuccess = {
                        scope.launch { snackbarHostState.showSnackbar("Deleted \"${target.name}\"") }
                    },
                    onFailure = { err ->
                        scope.launch { snackbarHostState.showSnackbar(err.localizedMessage ?: "Delete failed") }
                    }
                )
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("voice_library_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
        ) {
            // Header Actions Row: Add Voice CTA + A/B Lab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Voice Library ($visibleCount" + if (hiddenCount > 0) " • $hiddenCount hidden" else "" + ")",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${VoiceProfileRepository.getUniqueReferenceVoiceSummary().first} unique audio refs • preview, hide or assign",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onOpenAbVoiceTesting != null) {
                        OutlinedButton(
                            onClick = onOpenAbVoiceTesting,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("voice_library_ab_lab_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Science,
                                contentDescription = "A/B Lab",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Button(
                        onClick = onAddVoiceClick,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("voice_library_add_voice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Voice", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Search Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search voices by name, model or style...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search voices") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("voice_library_search_input")
            )

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VoiceLibraryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = activeFilter == filter,
                        onClick = { activeFilter = filter },
                        label = { Text(filter.label) },
                        modifier = Modifier.testTag("voice_filter_${filter.name.lowercase(Locale.ROOT)}")
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("voice_library_lazy_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "supertonic_readiness_header") {
                    if (showModelPanel) {
                        SupertonicModelReadinessCard(
                            modelState = supertonicState,
                            integrityReport = integrityReport,
                            actualBackend = appState.voiceBackendType,
                            onDownloadAndVerify = {
                                ForegroundTtsManager.getInstance(context).downloadVerifyAndInitializeSupertonic(
                                    onComplete = { ok, finalState ->
                                        integrityReport = SupertonicModelManager.verifyModelIntegrity(context)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) "Supertonic 3 neural voices READY!" else (finalState as? SupertonicModelState.Error)?.errorMessage ?: "Download/init failed"
                                            )
                                        }
                                    }
                                )
                            },
                            onVerifyAndInit = {
                                ForegroundTtsManager.getInstance(context).runSupertonicReadinessCheck { ok, _ ->
                                    integrityReport = SupertonicModelManager.verifyModelIntegrity(context)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(if (ok) "Native Hindi inference verified!" else "Native verification failed")
                                    }
                                }
                            },
                            onTestDualCharacters = {
                                val tts = ForegroundTtsManager.getInstance(context)
                                val anon = VoiceProfileRepository.getAssignmentForCharacter("Anon", context)
                                tts.synthesizeCharacterPreview(anon, VOICE_PREVIEW_HINDI_SAMPLE)
                            },
                            onUninstallModel = {
                                SupertonicModelManager.uninstallModel(context)
                                integrityReport = SupertonicModelManager.verifyModelIntegrity(context)
                            }
                        )
                    } else {
                        TextButton(onClick = { showModelPanel = true }) {
                            Text("Show Neural Model Status")
                        }
                    }
                }

                items(filteredVoices, key = { it.id }) { voice ->
                    val boundChars = remember(voice.id, characterAssignments) {
                        VoiceProfileRepository.getCharactersAssignedToVoice(voice.id)
                    }
                    VoiceLibraryCard(
                        voice = voice,
                        isSelected = voice.id == selectedVoiceId,
                        boundCharacters = boundChars,
                        onPreview = {
                            val hasLocalSample = !voice.sampleAudioPath.isNullOrBlank() &&
                                File(voice.sampleAudioPath).exists() &&
                                (voice.voiceCategory == VoiceCategory.IMPORTED || voice.voiceCategory == VoiceCategory.CLONED)
                            if (hasLocalSample) {
                                VoiceProfileRepository.playRecordedReferenceSample(voice.sampleAudioPath)
                            } else {
                                val tts = ForegroundTtsManager.getInstance(context)
                                val previewAssign = CharacterVoiceAssignment(
                                    characterName = "preview_${voice.id}",
                                    displayName = voice.name,
                                    speakerId = voice.engineVoiceIndex.coerceIn(0, 9),
                                    speed = voice.speechRate,
                                    pitch = voice.pitch,
                                    voicePresentation = if (voice.gender == SpeakerGender.FEMALE_18) "female" else "male",
                                    assignedVoiceId = voice.id
                                )
                                tts.synthesizeCharacterPreview(
                                    assignment = previewAssign,
                                    hindiText = VOICE_PREVIEW_HINDI_SAMPLE,
                                    explicitEmotion = SpeechEmotion.NEUTRAL
                                )
                                onPlayVoicePreview(voice)
                            }
                        },
                        onSelect = { onSelectVoice(voice) },
                        onRename = { voiceToRename = voice },
                        onAssignToCharacter = { voiceToAssign = voice },
                        onToggleHide = {
                            val targetHidden = !voice.isHidden
                            VoiceProfileRepository.setVoiceHidden(context, voice.id, targetHidden).fold(
                                onSuccess = {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (targetHidden) "Hidden \"${voice.name}\" (switch filter to Hidden to restore)"
                                            else "Restored \"${voice.name}\" to Voice Library"
                                        )
                                    }
                                },
                                onFailure = { err ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(err.localizedMessage ?: "Cannot hide voice")
                                    }
                                }
                            )
                        },
                        onDelete = { voicePendingDeletion = voice }
                    )
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceLibraryCard(
    voice: VoiceProfile,
    isSelected: Boolean,
    boundCharacters: List<CharacterVoiceAssignment>,
    onPreview: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onAssignToCharacter: () -> Unit,
    onToggleHide: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_card_${voice.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (voice.gender == SpeakerGender.MALE_18) Icons.Default.Male else Icons.Default.Female,
                            contentDescription = voice.gender.displayLabel,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = voice.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("voice_card_name_${voice.id}")
                        )
                        Text(
                            text = "${voice.voiceCategory.displayLabel} • ${voice.sourceModelInfo}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("voice_menu_${voice.id}")
                    ) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Voice Actions")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                            modifier = Modifier.testTag("menu_rename_${voice.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("Assign to Character") },
                            onClick = {
                                menuExpanded = false
                                onAssignToCharacter()
                            },
                            modifier = Modifier.testTag("menu_assign_${voice.id}")
                        )
                        if (voice.isSystemProtected) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (voice.isHidden) "Unhide / Restore Voice" else "Hide Voice")
                                },
                                onClick = {
                                    menuExpanded = false
                                    onToggleHide()
                                },
                                modifier = Modifier.testTag("menu_hide_${voice.id}")
                            )
                        } else {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Delete",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                                modifier = Modifier.testTag("menu_delete_${voice.id}")
                            )
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = voice.voiceCategory.displayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                if (voice.isSystemProtected) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("System Default", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                boundCharacters.take(3).forEach { charAssign ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "Assigned: ${charAssign.displayName}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPreview,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("preview_voice_${voice.id}")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Preview")
                }
                Button(
                    onClick = onSelect,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("select_voice_${voice.id}")
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Selected")
                    } else {
                        Text("Select")
                    }
                }
                IconButton(
                    onClick = onAssignToCharacter,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("assign_voice_btn_${voice.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = "Assign ${voice.name} to character"
                    )
                }
            }
        }
    }
}
