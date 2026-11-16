package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.service.ForegroundTtsManager
import com.example.service.SupertonicModelManager
import com.example.service.SupertonicModelState
import com.k2fsa.sherpa.onnx.OfflineTts
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
    onSelectVoice: (VoiceProfile) -> Unit,
    onOpenAbVoiceTesting: (() -> Unit)? = null
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
    val unlabeledOverrideChar by VoiceProfileRepository.unlabeledOverrideCharName.collectAsStateWithLifecycle()
    val voices by VoiceProfileRepository.voices.collectAsStateWithLifecycle()
    val selectedVoiceId by VoiceProfileRepository.selectedVoiceId.collectAsStateWithLifecycle()
    val appState by TranslatorStateRepository.state.collectAsStateWithLifecycle()
    val supertonicModelState by SupertonicModelManager.modelState.collectAsStateWithLifecycle()
    var integrityReport by remember(supertonicModelState) {
        mutableStateOf(SupertonicModelManager.verifyModelIntegrity(context))
    }

    LaunchedEffect(Unit) {
        integrityReport = SupertonicModelManager.verifyModelIntegrity(context)
        if (integrityReport.allValid && supertonicModelState !is SupertonicModelState.Ready) {
            ForegroundTtsManager.getInstance(context).runSupertonicReadinessCheck { _, _ ->
                integrityReport = SupertonicModelManager.verifyModelIntegrity(context)
            }
        }
    }

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
                            text = VoiceProfileRepository.getUniqueReferenceVoiceSummary().third,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (onOpenAbVoiceTesting != null) {
                        TextButton(
                            onClick = onOpenAbVoiceTesting,
                            modifier = Modifier.testTag("voice_screen_open_ab_lab_button")
                        ) {
                            Text("A/B Lab", fontWeight = FontWeight.Bold)
                        }
                    }
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
                        val uniqueSummary = remember(characterAssignments) {
                            VoiceProfileRepository.getUniqueReferenceVoiceSummary()
                        }

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
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .testTag("character_search_input")
                        )

                        // Character Cards List with Supertonic 3 Model Readiness & Disclosure at top
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("character_assignment_list")
                        ) {
                            item(key = "supertonic_model_readiness_card") {
                                SupertonicModelReadinessCard(
                                    modelState = supertonicModelState,
                                    integrityReport = integrityReport,
                                    voiceEngineWarningMessage = appState.voiceEngineWarningMessage,
                                    activeBackendType = appState.voiceBackendType,
                                    onDownloadVerifyAndInit = {
                                        ForegroundTtsManager.getInstance(context).downloadVerifyAndInitializeSupertonic(
                                            requireWifi = false,
                                            onComplete = { ok, finalState ->
                                                integrityReport = SupertonicModelManager.verifyModelIntegrity(context)
                                                coroutineScope.launch {
                                                    val msg = when (finalState) {
                                                        is SupertonicModelState.Ready ->
                                                            "Supertonic 3 READY: ${finalState.readinessSummary}"
                                                        is SupertonicModelState.Error ->
                                                            "Supertonic 3 Error: ${finalState.errorMessage}"
                                                        else ->
                                                            if (ok) "Supertonic 3 initialized" else "Supertonic 3 not ready"
                                                    }
                                                    snackbarHostState.showSnackbar(msg)
                                                }
                                            }
                                        )
                                    },
                                    onRunReadinessCheck = {
                                        ForegroundTtsManager.getInstance(context).runSupertonicReadinessCheck { ok, finalState ->
                                            integrityReport = SupertonicModelManager.verifyModelIntegrity(context)
                                            coroutineScope.launch {
                                                val msg = when (finalState) {
                                                    is SupertonicModelState.Ready ->
                                                        "Readiness Verified: ${finalState.readinessSummary}"
                                                    is SupertonicModelState.Error ->
                                                        "Readiness Failed: ${finalState.errorMessage}"
                                                    else ->
                                                        if (ok) "Supertonic 3 ready" else "Supertonic 3 files not downloaded yet"
                                                }
                                                snackbarHostState.showSnackbar(msg)
                                            }
                                        }
                                    },
                                    onTestAnonAndJenny = {
                                        val anon = VoiceProfileRepository.getAssignmentForCharacter("anon", context)
                                        val jenny = VoiceProfileRepository.getAssignmentForCharacter("jenny", context)
                                        val ttsMgr = ForegroundTtsManager.getInstance(context)
                                        ttsMgr.synthesizeCharacterPreview(
                                            assignment = anon,
                                            hindiText = "नमस्ते, मैं अनॉन हूँ। मैं तुम्हारी रक्षा करूँगा।"
                                        ) { _, anonStatus, _ ->
                                            ttsMgr.synthesizeCharacterPreview(
                                                assignment = jenny,
                                                hindiText = "कृपया मुझे मत छोड़ो! सावधान रहो।"
                                            ) { _, jennyStatus, _ ->
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        "Anon (SID #${anon.speakerId}): $anonStatus | Jenny (SID #${jenny.speakerId}): $jennyStatus"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                            }

                            item(key = "voice_library_honest_disclosure_card") {
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("voice_library_honest_disclosure_card")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "${uniqueSummary.second} Saved Character Profiles • ${uniqueSummary.first} Unique Acoustic Reference WAV Identities",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "Bundled reference audio includes 2 distinct WAVs (anon_male_ref.wav & jenny_female_ref.wav). Profiles sharing a reference WAV share its base vocal timbre unless you import a consented/licensed .wav sample in Configure Voice.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Unlabeled Subtitle Handling (No Speaker Tag in OCR):",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            FilterChip(
                                                selected = unlabeledOverrideChar == null,
                                                onClick = {
                                                    VoiceProfileRepository.setUnlabeledSubtitleManualOverride(context, null)
                                                },
                                                label = { Text("Unknown Speaker (Neutral SID #2)", style = MaterialTheme.typography.labelSmall) },
                                                modifier = Modifier.testTag("unlabeled_mode_unknown")
                                            )
                                            FilterChip(
                                                selected = unlabeledOverrideChar == "anon",
                                                onClick = {
                                                    VoiceProfileRepository.setUnlabeledSubtitleManualOverride(context, "anon")
                                                },
                                                label = { Text("Override: Anon", style = MaterialTheme.typography.labelSmall) },
                                                modifier = Modifier.testTag("unlabeled_mode_anon")
                                            )
                                            FilterChip(
                                                selected = unlabeledOverrideChar == "jenny",
                                                onClick = {
                                                    VoiceProfileRepository.setUnlabeledSubtitleManualOverride(context, "jenny")
                                                },
                                                label = { Text("Override: Jenny", style = MaterialTheme.typography.labelSmall) },
                                                modifier = Modifier.testTag("unlabeled_mode_jenny")
                                            )
                                        }
                                    }
                                }
                            }

                            items(filteredCharacters, key = { it.characterName }) { charItem ->
                                val isCurrentlyActive = activeCharacterAssignment?.characterName == charItem.characterName
                                val sharingDisclosure = remember(charItem, characterAssignments) {
                                    CharacterVoiceAssignment.buildVoiceSharingDisclosure(charItem, characterAssignments)
                                }
                                CharacterAssignmentCard(
                                    item = charItem,
                                    sharingDisclosure = sharingDisclosure,
                                    isActiveInDialogue = isCurrentlyActive,
                                    onChangeSid = { characterToChangeSid = charItem },
                                    onRename = { characterToRename = charItem },
                                    onPlayReferenceClip = {
                                        VoiceProfileRepository.playCharacterReferenceVoiceClip(
                                            context = context,
                                            assignment = charItem
                                        ) { ok ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (ok) {
                                                        "Playing raw reference clip: ${charItem.referenceVoiceId}.wav (SHA-256: ${charItem.referenceAudioSha256.take(12)}…)"
                                                    } else {
                                                        "Reference clip not found for ${charItem.displayName}"
                                                    }
                                                )
                                            }
                                        }
                                    },
                                    onPlayPreview = {
                                        val sampleText = "नमस्ते, मैं ${charItem.displayName} हूँ। यहाँ से निकलो, जल्दी!"
                                        ForegroundTtsManager.getInstance(context).synthesizeCharacterPreview(
                                            assignment = charItem,
                                            hindiText = sampleText
                                        ) { _, status, _ ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar("${charItem.displayName}: $status")
                                            }
                                        }
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
                                    val route = com.example.engine.CharacterVoiceRouter.routeBaseSpeakerAudition(
                                        context = context,
                                        sid = spec.sid,
                                        speakerTitle = spec.title,
                                        genderLabel = spec.gender,
                                        hindiDialogue = VOICE_PREVIEW_HINDI_SAMPLE
                                    )
                                    ForegroundTtsManager.getInstance(context).speakNonBlocking(
                                        text = VOICE_PREVIEW_HINDI_SAMPLE,
                                        voiceProfile = route.voiceProfile,
                                        characterAssignment = route.characterAssignment,
                                        routeDecision = route
                                    )
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Auditioning ${spec.code} (${spec.title} • SID #${spec.sid})")
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
                                    val route = com.example.engine.CharacterVoiceRouter.routeBaseSpeakerAudition(
                                        context = context,
                                        sid = spec.sid,
                                        speakerTitle = spec.title,
                                        genderLabel = spec.gender,
                                        hindiDialogue = VOICE_PREVIEW_HINDI_SAMPLE
                                    )
                                    ForegroundTtsManager.getInstance(context).speakNonBlocking(
                                        text = VOICE_PREVIEW_HINDI_SAMPLE,
                                        voiceProfile = route.voiceProfile,
                                        characterAssignment = route.characterAssignment,
                                        routeDecision = route
                                    )
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Auditioning ${spec.code} (${spec.title} • SID #${spec.sid})")
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

    // Dialog: Change Character Voice Profile (Reference Clip, Presentation, SID 0..9, Emotion)
    characterToChangeSid?.let { charItem ->
        var selectedSid by remember { mutableIntStateOf(charItem.speakerId) }
        var selectedRefId by remember { mutableStateOf(charItem.referenceVoiceId) }
        var selectedPresentation by remember { mutableStateOf(charItem.voicePresentation) }
        var selectedEmotion by remember { mutableStateOf(charItem.defaultEmotion) }
        var speakerConsentConfirmed by remember { mutableStateOf(false) }

        val customWavPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                coroutineScope.launch {
                    val importResult = VoiceProfileRepository.importConsentedReferenceVoiceUri(
                        context = context,
                        characterName = charItem.characterName,
                        uri = uri,
                        speakerConsentConfirmed = speakerConsentConfirmed
                    )
                    importResult.onSuccess { updated ->
                        selectedRefId = updated.referenceVoiceId
                        snackbarHostState.showSnackbar(
                            "Imported unique reference WAV: ${updated.referenceVoiceId} (SHA-256: ${updated.referenceAudioSha256.take(12)}…)"
                        )
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar("Import failed: ${err.localizedMessage}")
                    }
                }
            }
        }

        AlertDialog(
            onDismissRequest = { characterToChangeSid = null },
            title = {
                Text(
                    text = "Configure Voice: ${charItem.displayName}",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "1. Reference Voice Identity (Chatterbox V3):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedRefId == CharacterVoiceAssignment.DEFAULT_MALE_REF_ID,
                            onClick = {
                                selectedRefId = CharacterVoiceAssignment.DEFAULT_MALE_REF_ID
                                selectedPresentation = "male"
                            },
                            label = { Text("anon_male_ref.wav") }
                        )
                        FilterChip(
                            selected = selectedRefId == CharacterVoiceAssignment.DEFAULT_FEMALE_REF_ID,
                            onClick = {
                                selectedRefId = CharacterVoiceAssignment.DEFAULT_FEMALE_REF_ID
                                selectedPresentation = "female"
                            },
                            label = { Text("jenny_female_ref.wav") }
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { speakerConsentConfirmed = !speakerConsentConfirmed }
                    ) {
                        Checkbox(
                            checked = speakerConsentConfirmed,
                            onCheckedChange = { speakerConsentConfirmed = it }
                        )
                        Text(
                            text = "I confirm speaker consent / license for custom reference WAV",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            customWavPickerLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*"))
                        },
                        enabled = speakerConsentConfirmed,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import Consented .WAV Reference Sample")
                    }

                    Text(
                        text = "2. Default Emotion Preset: $selectedEmotion",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("NEUTRAL", "HAPPY", "SAD", "ANGRY", "FEAR", "WHISPER", "EXCITED", "SERIOUS").forEach { emo ->
                            FilterChip(
                                selected = selectedEmotion == emo,
                                onClick = { selectedEmotion = emo },
                                label = { Text(emo, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Text(
                        text = "3. Supertonic 3 Offline Speaker SID (0..9):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    LazyColumn(modifier = Modifier.height(180.dp)) {
                        items(SUPERTONIC_BASE_SPEAKERS, key = { it.first }) { (sid, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSid = sid }
                                    .padding(vertical = 2.dp)
                            ) {
                                RadioButton(
                                    selected = selectedSid == sid,
                                    onClick = { selectedSid = sid }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (selectedSid == sid) FontWeight.Bold else FontWeight.Normal
                                )
                            }
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
                            referenceVoiceId = selectedRefId,
                            voicePresentation = selectedPresentation,
                            defaultEmotion = selectedEmotion
                        )
                        characterToChangeSid = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                "Saved ${charItem.displayName}: Ref=$selectedRefId, SID=$selectedSid, Emotion=$selectedEmotion"
                            )
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
    sharingDisclosure: String,
    isActiveInDialogue: Boolean,
    onChangeSid: () -> Unit,
    onRename: () -> Unit,
    onPlayReferenceClip: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            text = "${item.displayName} (${item.voicePresentation.uppercase(Locale.ROOT)})",
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
                        text = "OCR Tag: '${item.characterName}' • Namespace: ${item.gameNamespace} • ${item.characterRoleNote}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Vocal Style: ${item.vocalStyleDescription} • Emotion: ${item.defaultEmotion} (exag=${String.format(Locale.US, "%.2f", item.exaggeration)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = sharingDisclosure,
                        style = MaterialTheme.typography.labelSmall,
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

            // Separate Play Reference Voice Clip + Synthesize Hindi Preview + Edit Voice Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onPlayReferenceClip,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("play_ref_clip_${item.characterName}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Play Reference Voice Clip",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ref Clip", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = onPlayPreview,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1.1f)
                        .height(40.dp)
                        .testTag("synthesize_preview_${item.characterName}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Synthesize Hindi Preview",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Hindi Preview", style = MaterialTheme.typography.labelSmall)
                }

                Button(
                    onClick = onChangeSid,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("configure_voice_${item.characterName}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Configure Voice",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit Voice", style = MaterialTheme.typography.labelSmall)
                }

                if (isUserOverride) {
                    IconButton(
                        onClick = onResetDefault,
                        modifier = Modifier.size(36.dp)
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

@Composable
private fun SupertonicModelReadinessCard(
    modelState: SupertonicModelState,
    integrityReport: SupertonicModelManager.ModelIntegrityReport,
    voiceEngineWarningMessage: String,
    activeBackendType: String,
    onDownloadVerifyAndInit: () -> Unit,
    onRunReadinessCheck: () -> Unit,
    onTestAnonAndJenny: () -> Unit
) {
    val context = LocalContext.current
    val modelDirPath = remember(context) {
        SupertonicModelManager.getModelDirectory(context).absolutePath
    }
    val isReady = modelState is SupertonicModelState.Ready && modelState.nativeInferenceVerified
    val isBusy = modelState is SupertonicModelState.Downloading ||
        modelState is SupertonicModelState.VerifyingSha256 ||
        modelState is SupertonicModelState.InitializingNative
    val isSingleVoiceFallbackActive = !isReady ||
        activeBackendType.startsWith("SYSTEM_FALLBACK_SINGLE_VOICE") ||
        voiceEngineWarningMessage.isNotBlank()

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isReady) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            }
        ),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isReady) {
                MaterialTheme.colorScheme.primary
            } else if (modelState is SupertonicModelState.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("supertonic_model_readiness_card")
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Supertonic 3 Hindi Neural TTS Model",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "10 On-Device Neural Speakers • Anon (SID #5) & Jenny (SID #1)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val (badgeText, badgeContainer, badgeContent) = when (modelState) {
                    is SupertonicModelState.Ready -> Triple(
                        "READY (NATIVE VERIFIED)",
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.onPrimary
                    )
                    is SupertonicModelState.Downloading -> Triple(
                        "DOWNLOADING ${(modelState.progressFraction * 100).toInt()}%",
                        MaterialTheme.colorScheme.tertiary,
                        MaterialTheme.colorScheme.onTertiary
                    )
                    is SupertonicModelState.VerifyingSha256 -> Triple(
                        "VERIFYING SHA-256",
                        MaterialTheme.colorScheme.tertiary,
                        MaterialTheme.colorScheme.onTertiary
                    )
                    is SupertonicModelState.InitializingNative -> Triple(
                        "PROBING NATIVE TTS",
                        MaterialTheme.colorScheme.tertiary,
                        MaterialTheme.colorScheme.onTertiary
                    )
                    is SupertonicModelState.Error -> Triple(
                        "ERROR / NOT READY",
                        MaterialTheme.colorScheme.error,
                        MaterialTheme.colorScheme.onError
                    )
                    else -> Triple(
                        "NOT DOWNLOADED",
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeContainer,
                    modifier = Modifier.testTag("supertonic_readiness_status_badge")
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeContent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Explicit Warning Banner when SYSTEM_FALLBACK_SINGLE_VOICE is used
            if (isSingleVoiceFallbackActive) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("system_fallback_single_voice_warning_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "⚠ SYSTEM_FALLBACK_SINGLE_VOICE WARNING",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = voiceEngineWarningMessage.ifBlank {
                                "Supertonic 3 neural model is not ready in $modelDirPath. Anon (SID #5) and Jenny (SID #1) will use Android's single-voice fallback and sound identical during live gameplay until you download, SHA-256 verify, and initialize Supertonic 3 below."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Progress telemetry during Download / SHA-256 / Native Initialization
            when (modelState) {
                is SupertonicModelState.Downloading -> {
                    val mbDone = String.format(Locale.US, "%.1f", modelState.bytesDownloaded / 1_000_000.0)
                    val mbTotal = String.format(Locale.US, "%.1f", modelState.totalBytes / 1_000_000.0)
                    val pct = (modelState.progressFraction * 100f).toInt().coerceIn(0, 100)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supertonic_download_progress_section")
                    ) {
                        LinearProgressIndicator(
                            progress = { modelState.progressFraction.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Text(
                            text = "Downloading ${modelState.currentFileName} (${modelState.currentFileIndex}/${modelState.totalFilesCount}) • $mbDone / $mbTotal MB ($pct%)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is SupertonicModelState.VerifyingSha256 -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supertonic_sha256_progress_section")
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Text(
                            text = "Verifying SHA-256 hash: ${modelState.currentFileName} (${modelState.verifiedFilesCount + 1}/${modelState.totalFilesCount})...",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is SupertonicModelState.InitializingNative -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supertonic_native_init_progress_section")
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Text(
                            text = modelState.stepDescription,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is SupertonicModelState.Error -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supertonic_exact_error_box")
                    ) {
                        Text(
                            text = "Exact Error: ${modelState.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                is SupertonicModelState.Ready -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supertonic_ready_summary_box")
                    ) {
                        Text(
                            text = modelState.readinessSummary.ifBlank {
                                "All 7 model files SHA-256 verified (${modelState.totalSizeBytes / 1_000_000} MB) & native Hindi inference verified for Anon (SID #5) and Jenny (SID #1)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                else -> {}
            }

            // Diagnostic details row
            Text(
                text = "Directory: $modelDirPath\nSHA-256 Verified Files: ${integrityReport.verifiedFilesCount} / ${integrityReport.totalFilesCount} • JNI: ${OfflineTts.jniStatusSummary}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("supertonic_diagnostic_details_text")
            )

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDownloadVerifyAndInit,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("supertonic_download_verify_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Download and Verify Supertonic 3",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isReady -> "Re-Verify Model"
                            integrityReport.verifiedFilesCount in 1 until integrityReport.totalFilesCount ->
                                "Resume Download (${integrityReport.verifiedFilesCount}/7)"
                            modelState is SupertonicModelState.Error ->
                                "Retry Download & Verify"
                            else ->
                                "Download & Verify (~145 MB)"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onRunReadinessCheck,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("supertonic_verify_readiness_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Run Readiness Check",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Readiness Check",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            OutlinedButton(
                onClick = onTestAnonAndJenny,
                enabled = !isBusy,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("supertonic_test_anon_jenny_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Test Anon and Jenny Voices",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Test Both Characters: Anon (Male SID #5) & Jenny (Female SID #1)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

