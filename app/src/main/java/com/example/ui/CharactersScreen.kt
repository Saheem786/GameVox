package com.example.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.engine.SpeechEmotion
import com.example.service.ForegroundTtsManager
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CharactersScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val characters by VoiceProfileRepository.characterAssignments.collectAsStateWithLifecycle()
    val voices by VoiceProfileRepository.voices.collectAsStateWithLifecycle()
    val activeGameNamespace by VoiceProfileRepository.activeGameNamespaceFlow.collectAsStateWithLifecycle()
    val knownNamespaces by VoiceProfileRepository.knownGameNamespaces.collectAsStateWithLifecycle()
    val unlabeledOverride by VoiceProfileRepository.unlabeledOverrideCharName.collectAsStateWithLifecycle()
    val ocrAliases by VoiceProfileRepository.ocrAliasesForActiveGame.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showAddOrEditDialog by remember { mutableStateOf(false) }
    var editingCharacter by remember { mutableStateOf<CharacterVoiceAssignment?>(null) }
    var showOcrFixDialog by remember { mutableStateOf(false) }
    var ocrFixInitialTypo by remember { mutableStateOf("") }
    var showAddGameDialog by remember { mutableStateOf(false) }
    var newGameNamespaceInput by remember { mutableStateOf("") }
    var characterPendingDelete by remember { mutableStateOf<CharacterVoiceAssignment?>(null) }

    val filteredCharacters = remember(characters, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        characters.filter { c ->
            q.isEmpty() ||
                c.displayName.lowercase(Locale.ROOT).contains(q) ||
                c.characterName.lowercase(Locale.ROOT).contains(q) ||
                c.voicePresentation.lowercase(Locale.ROOT).contains(q)
        }
    }

    if (showAddOrEditDialog) {
        AddOrEditCharacterDialog(
            initialCharacter = editingCharacter,
            availableVoices = voices,
            activeGameNamespace = activeGameNamespace,
            onDismiss = {
                showAddOrEditDialog = false
                editingCharacter = null
            },
            onSave = { displayName, sid, presentation, voiceId, speed, pitch, voiceEngine ->
                val saved = VoiceProfileRepository.addOrUpdateCharacter(
                    context = context,
                    displayName = displayName,
                    speakerId = sid,
                    voicePresentation = presentation,
                    voiceId = voiceId,
                    speed = speed,
                    pitch = pitch,
                    voiceEngine = voiceEngine,
                    gameNamespace = activeGameNamespace
                )
                showAddOrEditDialog = false
                editingCharacter = null
                scope.launch {
                    snackbarHostState.showSnackbar("Saved ${saved.displayName} (SID #${saved.speakerId}) in $activeGameNamespace")
                }
            }
        )
    }

    if (showOcrFixDialog) {
        FixOcrCharacterNameDialog(
            knownCharacters = characters,
            activeGameNamespace = activeGameNamespace,
            initialTypo = ocrFixInitialTypo,
            onDismiss = {
                showOcrFixDialog = false
                ocrFixInitialTypo = ""
            },
            onSaveCorrection = { typo, canonical ->
                val ok = VoiceProfileRepository.setOcrCharacterCorrection(
                    context = context,
                    misreadOcrName = typo,
                    canonicalCharacterName = canonical,
                    gameNamespace = activeGameNamespace
                )
                showOcrFixDialog = false
                ocrFixInitialTypo = ""
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (ok) "Saved OCR fix: '$typo' → '$canonical'" else "Enter two distinct names"
                    )
                }
            }
        )
    }

    if (showAddGameDialog) {
        AlertDialog(
            onDismissRequest = { showAddGameDialog = false },
            title = { Text("New Game Profile", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newGameNamespaceInput,
                    onValueChange = { newGameNamespaceInput = it },
                    label = { Text("Game Name or Tag") },
                    placeholder = { Text("e.g. persona_5 or cyber_quest") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_game_namespace_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = newGameNamespaceInput.trim()
                        if (clean.isNotEmpty()) {
                            VoiceProfileRepository.setGameNamespace(context, clean)
                            newGameNamespaceInput = ""
                            showAddGameDialog = false
                            scope.launch {
                                snackbarHostState.showSnackbar("Switched to game profile: ${clean.lowercase(Locale.ROOT)}")
                            }
                        }
                    },
                    enabled = newGameNamespaceInput.isNotBlank(),
                    modifier = Modifier.height(48.dp).testTag("confirm_new_game_namespace")
                ) { Text("Switch / Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddGameDialog = false }) { Text("Cancel") }
            }
        )
    }

    characterPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { characterPendingDelete = null },
            title = { Text("Delete ${target.displayName}?", fontWeight = FontWeight.Bold) },
            text = { Text("Remove this character assignment from $activeGameNamespace? You can re-add it anytime.") },
            confirmButton = {
                Button(
                    onClick = {
                        VoiceProfileRepository.deleteCharacterAssignment(context, target.characterName)
                        characterPendingDelete = null
                        scope.launch { snackbarHostState.showSnackbar("Deleted ${target.displayName}") }
                    },
                    modifier = Modifier.height(48.dp)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { characterPendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("characters_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Characters (${characters.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Game: $activeGameNamespace • ${VoiceProfileRepository.getUniqueReferenceVoiceSummary().first} unique audio refs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showOcrFixDialog = true },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("characters_ocr_fix_button")
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("OCR Fix")
                    }
                    Button(
                        onClick = {
                            editingCharacter = null
                            showAddOrEditDialog = true
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("characters_add_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search characters...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search characters") },
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
                    .testTag("characters_search_input")
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("characters_lazy_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Per-game profile selector + Unknown override card
                item(key = "game_profile_card") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.SportsEsports, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Per-Game Profile", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                }
                                TextButton(
                                    onClick = { showAddGameDialog = true },
                                    modifier = Modifier.testTag("characters_new_game_button")
                                ) { Text("+ New Game") }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                knownNamespaces.forEach { ns ->
                                    FilterChip(
                                        selected = activeGameNamespace == ns,
                                        onClick = { VoiceProfileRepository.setGameNamespace(context, ns) },
                                        label = { Text(ns) },
                                        modifier = Modifier.testTag("game_namespace_chip_$ns")
                                    )
                                }
                            }
                            Text(
                                text = "Unlabeled Subtitles Override: ${unlabeledOverride ?: "Auto → Unknown Speaker (No forced reuse)"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = unlabeledOverride == null,
                                    onClick = { VoiceProfileRepository.setUnlabeledSubtitleManualOverride(context, null, activeGameNamespace) },
                                    label = { Text("Auto (Unknown)") },
                                    modifier = Modifier.testTag("unlabeled_override_auto")
                                )
                                listOf("Anon", "Jenny", "Alice").forEach { target ->
                                    val normTarget = CharacterVoiceAssignment.normalizeName(target)
                                    FilterChip(
                                        selected = unlabeledOverride == normTarget,
                                        onClick = { VoiceProfileRepository.setUnlabeledSubtitleManualOverride(context, normTarget, activeGameNamespace) },
                                        label = { Text("Force $target") },
                                        modifier = Modifier.testTag("unlabeled_override_$normTarget")
                                    )
                                }
                            }
                        }
                    }
                }

                // Saved OCR Typo / Alias Corrections card
                if (ocrAliases.isNotEmpty()) {
                    item(key = "ocr_aliases_card") {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("characters_ocr_aliases_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Saved OCR Name Corrections ($activeGameNamespace)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                ocrAliases.forEach { (typo, canonical) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "'$typo' → $canonical",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                VoiceProfileRepository.removeOcrCharacterCorrection(
                                                    context = context,
                                                    misreadOcrName = typo,
                                                    gameNamespace = activeGameNamespace
                                                )
                                            },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove OCR correction $typo")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                items(filteredCharacters, key = { it.characterName }) { character ->
                    val assignedVoice = remember(character.assignedVoiceId, voices) {
                        voices.find { it.id == character.assignedVoiceId }
                    }
                    CharacterAssignmentCard(
                        character = character,
                        assignedVoice = assignedVoice,
                        canDelete = characters.size > 1 && character.characterName !in setOf("anon", "jenny"),
                        onPreview = {
                            ForegroundTtsManager.getInstance(context).synthesizeCharacterPreview(
                                assignment = character,
                                hindiText = VOICE_PREVIEW_HINDI_SAMPLE,
                                explicitEmotion = SpeechEmotion.NEUTRAL
                            )
                        },
                        onEdit = {
                            editingCharacter = character
                            showAddOrEditDialog = true
                        },
                        onFixOcrAlias = {
                            ocrFixInitialTypo = character.displayName
                            showOcrFixDialog = true
                        },
                        onDelete = { characterPendingDelete = character }
                    )
                }

                item(key = "characters_bottom_spacer") {
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterAssignmentCard(
    character: CharacterVoiceAssignment,
    assignedVoice: VoiceProfile?,
    canDelete: Boolean,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onFixOcrAlias: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("character_card_${character.characterName}")
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
                            imageVector = when {
                                character.isFemale -> Icons.Default.Female
                                character.voicePresentation.equals("male", ignoreCase = true) -> Icons.Default.Male
                                else -> Icons.Default.Person
                            },
                            contentDescription = character.voicePresentation,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = character.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = assignedVoice?.name ?: "SID #${character.speakerId} • ${character.referenceVoiceId}.wav",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (canDelete) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("character_delete_${character.characterName}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete ${character.displayName}",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        text = character.voicePresentation.replaceFirstChar { it.uppercase(Locale.ROOT) },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        text = "SID #${character.speakerId}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        text = "${character.referenceVoiceId}.wav",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
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
                        .testTag("character_preview_${character.characterName}")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Preview")
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("character_edit_${character.characterName}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit")
                }
                IconButton(
                    onClick = onFixOcrAlias,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("character_ocr_map_${character.characterName}")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = "Map OCR error to ${character.displayName}"
                    )
                }
            }
        }
    }
}
