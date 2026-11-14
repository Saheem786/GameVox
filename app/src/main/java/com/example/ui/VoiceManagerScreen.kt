package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.data.SpeakerRole
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.service.ForegroundTtsManager
import java.util.Locale
import kotlinx.coroutines.launch

const val VOICE_PREVIEW_HINDI_SAMPLE = "नमस्ते गेमर! यह गेमवॉक्स का न्यूरल हिन्दी वॉइस प्रीव्यू है।"

data class SupertonicVoiceSpec(
    val sid: Int,
    val code: String,
    val gender: String,
    val title: String,
    val timbreDescription: String,
    val typicalCharacters: String
)

val SUPERTONIC_VOICE_SPECS = listOf(
    SupertonicVoiceSpec(0, "F1", "Female", "F1 • Gentle / Princess (Priya)", "Soft, gentle, clear female timbre with melodic intonation", "Priya, Zelda"),
    SupertonicVoiceSpec(1, "F2", "Female", "F2 • Bright / Companion (Jenny)", "Youthful, bright, warm female tone suited for companion dialogue", "Jenny, Ananya"),
    SupertonicVoiceSpec(2, "F3", "Female", "F3 • Composed / Direct (Ira AI)", "Crisp, articulated, composed female cadence for system AI & merchants", "Ira-AI, Elena"),
    SupertonicVoiceSpec(3, "F4", "Female", "F4 • Tactical / Commander (Sarah)", "Resolute, tactical female tone with confident diction", "Sarah, Maya, Kavya"),
    SupertonicVoiceSpec(4, "F5", "Female", "F5 • Ethereal / Arcane (Oracle)", "Mystic, resonant, serene female timbre with calm pacing", "Oracle, Pooja"),
    SupertonicVoiceSpec(5, "M1", "Male", "M1 • Young / Lead (Anon)", "Natural young adult male lead voice with heroic cadence", "Anon, Aarav, Leon"),
    SupertonicVoiceSpec(6, "M2", "Male", "M2 • Energetic / Scout (Alex)", "Agile, energetic, sharp male tone for scouts and marksmen", "Alex, Rohan"),
    SupertonicVoiceSpec(7, "M3", "Male", "M3 • Deep / Villain (Kaal)", "Deep, resonant, low-register male voice suited for villains and bosses", "Kaal, Ragnar, Shadow"),
    SupertonicVoiceSpec(8, "M4", "Male", "M4 • Mature / Veteran (Vikram)", "Gravelly, mature, seasoned battle veteran male timbre", "Vikram, Kabir"),
    SupertonicVoiceSpec(9, "M5", "Male", "M5 • Commanding / Armored (Guard)", "Heavy, commanding, mechanical/armored male sentinel presence", "Guard, Dante, Announcer")
)

private val SUPERTONIC_BASE_SPEAKERS = SUPERTONIC_VOICE_SPECS.map { it.sid to "${it.code} (${it.gender}) • ${it.title}" }

/**
 * Usable, comprehensive Character Voice Manager Screen:
 * 1. 20+ persistent character voice assignments mapped across Supertonic 3's 10 base speaker IDs.
 * 2. Search, rename/correct OCR typos, change sid (0..9), live preview, delete, and restore defaults.
 * 3. Base voice presets & voice-style presets (accurately labeled as acoustic F0/rate analysis).
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

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val characterAssignments by VoiceProfileRepository.characterAssignments.collectAsStateWithLifecycle()
    val activeCharacterAssignment by VoiceProfileRepository.activeCharacterAssignment.collectAsStateWithLifecycle()
    val voices by VoiceProfileRepository.voices.collectAsStateWithLifecycle()
    val selectedVoiceId by VoiceProfileRepository.selectedVoiceId.collectAsStateWithLifecycle()

    var characterToChangeSid by remember { mutableStateOf<CharacterVoiceAssignment?>(null) }
    var characterToRename by remember { mutableStateOf<CharacterVoiceAssignment?>(null) }
    var voiceSpecToAssign by remember { mutableStateOf<SupertonicVoiceSpec?>(null) }
    var showAddCharacterDialog by remember { mutableStateOf(false) }
    var showAddVoicePresetDialog by remember { mutableStateOf(false) }
    var voicePendingDeletion by remember { mutableStateOf<VoiceProfile?>(null) }

    val filteredCharacters = remember(characterAssignments, searchQuery) {
        if (searchQuery.isBlank()) {
            characterAssignments
        } else {
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            characterAssignments.filter {
                it.displayName.lowercase(Locale.ROOT).contains(q) ||
                    it.characterName.contains(q) ||
                    it.characterRoleNote.lowercase(Locale.ROOT).contains(q)
            }
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
                            text = "Character Voice Manager",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${characterAssignments.size} Character Profiles • Supertonic 3 (sid: 0..9)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            VoiceProfileRepository.restoreAllCharacterDefaults(context)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Restored 24 default character voice profiles")
                            }
                        },
                        modifier = Modifier.testTag("voice_screen_restore_defaults_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Restore All Defaults",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (selectedTabIndex == 0 || selectedTabIndex == 1) {
                        showAddCharacterDialog = true
                    } else {
                        showAddVoicePresetDialog = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add"
                    )
                },
                text = {
                    Text(
                        text = if (selectedTabIndex == 0 || selectedTabIndex == 1) "Add Character" else "Add Voice Preset",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                modifier = Modifier.testTag("voice_screen_fab_add")
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("voice_manager_screen_container"),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
            ) {
                // Top Tab Selector
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = {
                            Text(
                                "Character Voices (${characterAssignments.size})",
                                fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.testTag("voice_tab_characters")
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = {
                            Text(
                                "Voice Preview Matrix (10 Voices)",
                                fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.testTag("voice_tab_preview_matrix")
                    )
                    Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        text = {
                            Text(
                                "Base Voices & Presets",
                                fontWeight = if (selectedTabIndex == 2) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.testTag("voice_tab_base_presets")
                    )
                }

                if (selectedTabIndex == 0) {
                    // Character Voice Manager Tab
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Search bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search characters (e.g. Jenny, Anon, Kaal)...") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("character_search_input")
                        )

                        // Character Cards List
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("character_assignment_list")
                        ) {
                            items(filteredCharacters, key = { it.characterName }) { charItem ->
                                val isCurrentlyActive = activeCharacterAssignment?.characterName == charItem.characterName
                                CharacterAssignmentCard(
                                    item = charItem,
                                    isActiveInDialogue = isCurrentlyActive,
                                    onChangeSid = { characterToChangeSid = charItem },
                                    onRename = { characterToRename = charItem },
                                    onPlayPreview = {
                                        val sampleText = "नमस्ते, मैं ${charItem.displayName} हूँ।"
                                        val previewProfile = VoiceProfile(
                                            id = "char_preview_${charItem.characterName}",
                                            name = charItem.displayName,
                                            description = charItem.characterRoleNote,
                                            pitch = charItem.pitch,
                                            speechRate = charItem.speed,
                                            engineVoiceIndex = charItem.speakerId
                                        )
                                        ForegroundTtsManager.getInstance(context).speakNonBlocking(
                                            text = sampleText,
                                            voiceProfile = previewProfile
                                        )
                                    },
                                    onResetDefault = {
                                        VoiceProfileRepository.resetCharacterToDefault(context, charItem.characterName)
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Reset ${charItem.displayName} to default voice")
                                        }
                                    },
                                    onDelete = {
                                        VoiceProfileRepository.deleteCharacterAssignment(context, charItem.characterName)
                                    }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(80.dp))
                            }
                        }
                    }
                } else if (selectedTabIndex == 1) {
                    // Voice Preview Matrix Tab (10 Voices)
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("voice_preview_matrix_list")
                    ) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("voice_matrix_header_card")
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.RecordVoiceOver,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Supertonic 3 Voice Preview Matrix",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = "Audition all 10 neural voice profiles with standardized Hindi test dialogue. Weights by Supertone Inc. under OpenRAIL-M license. Sids 0..4 = Female (F1..F5); sids 5..9 = Male (M1..M5).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = "Standard Hindi Audition Phrase:",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "\"$VOICE_PREVIEW_HINDI_SAMPLE\"",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Female Voices Group
                        item {
                            Text(
                                text = "Female Neural Voices (F1–F5 • sids 0..4)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        items(SUPERTONIC_VOICE_SPECS.filter { it.gender == "Female" }, key = { "matrix_${it.sid}" }) { spec ->
                            val assignedChars = characterAssignments.filter { it.speakerId == spec.sid }
                            VoiceMatrixCard(
                                spec = spec,
                                assignedCharacters = assignedChars,
                                onAudition = {
                                    val previewProfile = VoiceProfile(
                                        id = "matrix_preview_${spec.sid}",
                                        name = spec.title,
                                        description = spec.timbreDescription,
                                        pitch = 1.0f,
                                        speechRate = 1.0f,
                                        engineVoiceIndex = spec.sid
                                    )
                                    ForegroundTtsManager.getInstance(context).speakNonBlocking(
                                        text = VOICE_PREVIEW_HINDI_SAMPLE,
                                        voiceProfile = previewProfile
                                    )
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Auditioning ${spec.code} (${spec.title})")
                                    }
                                },
                                onAssignToCharacter = {
                                    voiceSpecToAssign = spec
                                }
                            )
                        }

                        // Male Voices Group
                        item {
                            Text(
                                text = "Male Neural Voices (M1–M5 • sids 5..9)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }

                        items(SUPERTONIC_VOICE_SPECS.filter { it.gender == "Male" }, key = { "matrix_${it.sid}" }) { spec ->
                            val assignedChars = characterAssignments.filter { it.speakerId == spec.sid }
                            VoiceMatrixCard(
                                spec = spec,
                                assignedCharacters = assignedChars,
                                onAudition = {
                                    val previewProfile = VoiceProfile(
                                        id = "matrix_preview_${spec.sid}",
                                        name = spec.title,
                                        description = spec.timbreDescription,
                                        pitch = 1.0f,
                                        speechRate = 1.0f,
                                        engineVoiceIndex = spec.sid
                                    )
                                    ForegroundTtsManager.getInstance(context).speakNonBlocking(
                                        text = VOICE_PREVIEW_HINDI_SAMPLE,
                                        voiceProfile = previewProfile
                                    )
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Auditioning ${spec.code} (${spec.title})")
                                    }
                                },
                                onAssignToCharacter = {
                                    voiceSpecToAssign = spec
                                }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                } else {
                    // Base Voices & Presets Tab
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("base_voices_list")
                    ) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Supertonic 3 Neural Engine",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Supertonic 3 provides 10 acoustically unique base speaker models (sids 0..9). GameVox maps 20+ persistent character profiles across these 10 base sids with distinct cadence and pitch contour.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        items(voices, key = { it.id }) { voice ->
                            CleanVoiceCard(
                                voice = voice,
                                isSelected = voice.id == selectedVoiceId,
                                canDelete = voice.isCustomCloned,
                                onPlay = { onPlayVoicePreview(voice) },
                                onSelect = {
                                    onSelectVoice(voice)
                                    VoiceProfileRepository.selectVoice(context, voice)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Selected ${voice.name}")
                                    }
                                },
                                onDelete = { voicePendingDeletion = voice }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                }
            }
        }
    }

    // Dialog: Change Supertonic Speaker SID (0..9)
    characterToChangeSid?.let { charItem ->
        var selectedSid by remember { mutableIntStateOf(charItem.speakerId) }

        AlertDialog(
            onDismissRequest = { characterToChangeSid = null },
            title = {
                Text(
                    text = "Assign Voice: ${charItem.displayName}",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Select one of Supertonic 3's 10 base speaker IDs (0..9):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SUPERTONIC_BASE_SPEAKERS.forEach { (sid, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedSid = sid }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedSid == sid,
                                onClick = { selectedSid = sid }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selectedSid == sid) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        VoiceProfileRepository.setUserOverrideForCharacter(
                            context = context,
                            characterName = charItem.characterName,
                            sid = selectedSid,
                            displayName = charItem.displayName
                        )
                        characterToChangeSid = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Assigned Sid $selectedSid to ${charItem.displayName}")
                        }
                    }
                ) {
                    Text("Save Voice")
                }
            },
            dismissButton = {
                TextButton(onClick = { characterToChangeSid = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Rename / Correct OCR Name
    characterToRename?.let { charItem ->
        var newNameInput by remember { mutableStateOf(charItem.displayName) }

        AlertDialog(
            onDismissRequest = { characterToRename = null },
            title = { Text("Correct Character Name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Fix OCR misrecognitions or customize the character's display name:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = newNameInput,
                        onValueChange = { newNameInput = it },
                        label = { Text("Character Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newNameInput.isNotBlank()) {
                            VoiceProfileRepository.renameCharacter(
                                context = context,
                                oldNormalizedName = charItem.characterName,
                                newDisplayName = newNameInput
                            )
                            characterToRename = null
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { characterToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Add New Character Assignment
    if (showAddCharacterDialog) {
        var newCharName by remember { mutableStateOf("") }
        var chosenSid by remember { mutableIntStateOf(0) }

        AlertDialog(
            onDismissRequest = { showAddCharacterDialog = false },
            title = { Text("Add Character Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newCharName,
                        onValueChange = { newCharName = it },
                        label = { Text("Character Name (e.g. Commander)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Choose Base Voice:", style = MaterialTheme.typography.labelMedium)
                    SUPERTONIC_BASE_SPEAKERS.take(5).forEach { (sid, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { chosenSid = sid }
                        ) {
                            RadioButton(selected = chosenSid == sid, onClick = { chosenSid = sid })
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCharName.isNotBlank()) {
                            VoiceProfileRepository.setUserOverrideForCharacter(
                                context = context,
                                characterName = newCharName,
                                sid = chosenSid,
                                displayName = newCharName
                            )
                            showAddCharacterDialog = false
                        }
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCharacterDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Voice Preset Dialog
    voicePendingDeletion?.let { voice ->
        AlertDialog(
            onDismissRequest = { voicePendingDeletion = null },
            title = { Text("Delete Voice Preset") },
            text = { Text("Are you sure you want to delete '${voice.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        VoiceProfileRepository.deleteVoice(context, voice.id)
                        voicePendingDeletion = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { voicePendingDeletion = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Assign Voice from Matrix to Character
    voiceSpecToAssign?.let { spec ->
        var selectedCharName by remember { mutableStateOf(characterAssignments.firstOrNull()?.characterName ?: "anon") }
        var customCharInput by remember { mutableStateOf("") }
        var isCustomChar by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { voiceSpecToAssign = null },
            title = {
                Text(
                    text = "Assign ${spec.code} (${spec.title})",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Assign Supertonic SID #${spec.sid} (${spec.gender}) to a game character profile. This override is saved across sessions and per-game.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text("Select Existing Detected Character:", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    ) {
                        items(characterAssignments, key = { it.characterName }) { charItem ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedCharName = charItem.characterName
                                        isCustomChar = false
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                RadioButton(
                                    selected = !isCustomChar && selectedCharName == charItem.characterName,
                                    onClick = {
                                        selectedCharName = charItem.characterName
                                        isCustomChar = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = charItem.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (!isCustomChar && selectedCharName == charItem.characterName) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = "Current: sid ${charItem.speakerId} • ${charItem.characterRoleNote}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    Text("Or Enter New Character Name:", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = customCharInput,
                        onValueChange = {
                            customCharInput = it
                            if (it.isNotBlank()) isCustomChar = true
                        },
                        placeholder = { Text("e.g. Commander, Questgiver...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetChar = if (isCustomChar && customCharInput.isNotBlank()) {
                            customCharInput.trim()
                        } else {
                            selectedCharName
                        }
                        VoiceProfileRepository.setUserOverrideForCharacter(
                            context = context,
                            characterName = targetChar,
                            sid = spec.sid,
                            displayName = if (isCustomChar && customCharInput.isNotBlank()) customCharInput.trim() else null
                        )
                        voiceSpecToAssign = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Saved: Assigned $targetChar to ${spec.code} (sid: ${spec.sid})")
                        }
                    }
                ) {
                    Text("Save Assignment")
                }
            },
            dismissButton = {
                TextButton(onClick = { voiceSpecToAssign = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CharacterAssignmentCard(
    item: CharacterVoiceAssignment,
    isActiveInDialogue: Boolean,
    onChangeSid: () -> Unit,
    onRename: () -> Unit,
    onPlayPreview: () -> Unit,
    onResetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    val isUserOverride = item.isUserOverride
    val borderStroke = if (isActiveInDialogue) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = borderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("character_card_${item.characterName}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Display Name + Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = onRename,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Rename",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = "OCR Tag: '${item.characterName}' • ${item.characterRoleNote}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = if (isUserOverride) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = if (isUserOverride) "Locked Sid ${item.speakerId}" else "Sid ${item.speakerId}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isUserOverride) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onPlayPreview,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Preview",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Preview", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onChangeSid,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .height(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Change SID",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Change SID", style = MaterialTheme.typography.labelMedium)
                }

                if (isUserOverride) {
                    IconButton(
                        onClick = onResetDefault,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Default",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = borderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_screen_card_${voice.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = genderIcon,
                            contentDescription = genderLabel,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = voice.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (voice.isCustomCloned) "Voice-Style Preset • Pitch ${String.format(Locale.US, "%.2f", voice.pitch)}x"
                            else "${voice.description} • sid: ${voice.engineVoiceIndex}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isSelected) {
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onPlay,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play")
                }

                Button(
                    onClick = onSelect,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    if (isSelected) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(if (isSelected) "Selected" else "Select")
                }

                if (canDelete) {
                    OutlinedButton(
                        onClick = onDelete,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceMatrixCard(
    spec: SupertonicVoiceSpec,
    assignedCharacters: List<CharacterVoiceAssignment>,
    onAudition: () -> Unit,
    onAssignToCharacter: () -> Unit
) {
    val isFemale = spec.gender == "Female"
    val badgeColor = if (isFemale) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
    val badgeContentColor = if (isFemale) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    val icon = if (isFemale) Icons.Default.Female else Icons.Default.Male

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_matrix_card_${spec.sid}")
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeColor
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = spec.gender,
                                tint = badgeContentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${spec.code} • sid ${spec.sid}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = badgeContentColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = spec.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = spec.timbreDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Assigned characters chips
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Assigned Characters (${assignedCharacters.size}):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (assignedCharacters.isEmpty()) {
                    Text(
                        text = "None assigned yet (Typical: ${spec.typicalCharacters})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        assignedCharacters.forEach { charItem ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (charItem.isUserOverride) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = if (charItem.isUserOverride) "${charItem.displayName} ★" else charItem.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (charItem.isUserOverride) FontWeight.Bold else FontWeight.Normal,
                                    color = if (charItem.isUserOverride) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Buttons: Audition + Assign to Character
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onAudition,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("audition_voice_${spec.sid}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Audition",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Audition Voice", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onAssignToCharacter,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.1f)
                        .height(40.dp)
                        .testTag("assign_voice_${spec.sid}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Assign",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Assign to Character...", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
