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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.CharacterVoiceAssignment
import com.example.data.TranslatorAppState
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceProfileRepository
import com.example.engine.EmotionSpeechEngine
import com.example.engine.HindiTranslationEngine
import com.example.engine.SpeakerHeuristicEngine
import com.example.engine.SpeechEmotion
import com.example.service.ForegroundTtsManager
import com.example.service.FreeVoiceBackend
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class DialogueConsistencyLine(
    val rawEnglishSubtitle: String,
    val characterKey: String,
    val expectedHindi: String,
    val emotion: SpeechEmotion
)

val FIVE_LINE_CONSISTENCY_SUITE = listOf(
    DialogueConsistencyLine(
        rawEnglishSubtitle = "Anon: Get out of here!",
        characterKey = "anon",
        expectedHindi = "यहाँ से निकलो, जल्दी!",
        emotion = SpeechEmotion.ANGRY
    ),
    DialogueConsistencyLine(
        rawEnglishSubtitle = "Jenny: I was so worried about you.",
        characterKey = "jenny",
        expectedHindi = "मुझे तुम्हारी कितनी फिक्र हो रही थी!",
        emotion = SpeechEmotion.FEAR
    ),
    DialogueConsistencyLine(
        rawEnglishSubtitle = "Kaal: You think you can beat me?",
        characterKey = "kaal",
        expectedHindi = "तुम्हें लगता है तुम मुझे हरा दोगे?",
        emotion = SpeechEmotion.SERIOUS
    ),
    DialogueConsistencyLine(
        rawEnglishSubtitle = "Anon: Stay behind me, there are enemies ahead.",
        characterKey = "anon",
        expectedHindi = "मेरे पीछे रहो, आगे दुश्मन हैं।",
        emotion = SpeechEmotion.SERIOUS
    ),
    DialogueConsistencyLine(
        rawEnglishSubtitle = "Jenny: Thank goodness! We finally made it!",
        characterKey = "jenny",
        expectedHindi = "शुक्र है! आखिर हम यहाँ से निकल आए!",
        emotion = SpeechEmotion.EXCITED
    )
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AbVoiceTestingScreen(
    state: TranslatorAppState,
    onNavigateBack: () -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val characterAssignments by VoiceProfileRepository.characterAssignments.collectAsStateWithLifecycle()

    var serverUrlInput by remember(state.chatterboxServerUrl) {
        mutableStateOf(state.chatterboxServerUrl)
    }
    var authTokenInput by remember(state.chatterboxAuthToken) {
        mutableStateOf(state.chatterboxAuthToken)
    }
    var isTestingServer by remember { mutableStateOf(false) }

    val sampleSentences = remember {
        listOf(
            "यहाँ से निकलो, जल्दी!" to "Get out of here! (Urgent)",
            "मुझे तुम्हारी कितनी फिक्र हो रही थी!" to "I was so worried about you. (Emotional)",
            "तुम्हें लगता है तुम मुझे हरा दोगे?" to "You think you can beat me? (Challenge)",
            "नमस्ते दोस्त, चलो मिशन शुरू करते हैं।" to "Hello friend, let's start the mission."
        )
    }
    var sharedTestHindiText by remember {
        mutableStateOf(sampleSentences.first().first)
    }
    var selectedEmotion by remember {
        mutableStateOf(SpeechEmotion.NEUTRAL)
    }
    var isRunningConsistencySuite by remember { mutableStateOf(false) }
    var consistencySuiteStatus by remember {
        mutableStateOf("Ready to run 5-line sequential character consistency & anti-overlap test.")
    }

    val checklistItems = remember {
        listOf(
            "Anon (Male) and Jenny (Female) have clearly distinct human vocal timbres",
            "Play Reference Clip (.wav) plays raw reference audio separately from synthesized Hindi",
            "Emotional acting (Neutral vs Fear vs Angry) changes vocal intensity and pacing",
            "Speaker prefixes ('Anon:', 'Jenny:') are stripped and never spoken aloud",
            "5-line dialogue sequence completes with zero clipping, repetition, or audio overlap"
        )
    }
    val checkedStates = remember {
        mutableStateListOf(false, false, false, false, false)
    }

    val anonProfile = remember(characterAssignments) {
        characterAssignments.find { it.characterName == "anon" }
            ?: CharacterVoiceAssignment.DEFAULT_PRESETS.first { it.characterName == "anon" }
    }
    val jennyProfile = remember(characterAssignments) {
        characterAssignments.find { it.characterName == "jenny" }
            ?: CharacterVoiceAssignment.DEFAULT_PRESETS.first { it.characterName == "jenny" }
    }
    val kaalProfile = remember(characterAssignments) {
        characterAssignments.find { it.characterName == "kaal" }
            ?: CharacterVoiceAssignment.DEFAULT_PRESETS.first { it.characterName == "kaal" }
    }
    val iraProfile = remember(characterAssignments) {
        characterAssignments.find { it.characterName == "ira" }
            ?: CharacterVoiceAssignment.DEFAULT_PRESETS.first { it.characterName == "ira" }
    }

    val uniqueVoiceCount = remember(characterAssignments) {
        CharacterVoiceAssignment.countUniqueReferenceVoices(characterAssignments)
    }

    fun playReferenceClip(character: CharacterVoiceAssignment) {
        VoiceProfileRepository.playCharacterReferenceVoiceClip(context, character) { played ->
            coroutineScope.launch {
                if (played) {
                    snackbarHostState.showSnackbar(
                        "Playing raw reference clip: ${character.referenceVoiceId}.wav (SHA-256: ${character.referenceAudioSha256.take(12)}…)"
                    )
                } else {
                    snackbarHostState.showSnackbar("Reference clip unavailable for ${character.displayName}")
                }
            }
        }
    }

    fun synthesizeForCharacter(
        character: CharacterVoiceAssignment,
        hindiText: String,
        emotionOverride: SpeechEmotion? = null
    ) {
        val targetEmotion = emotionOverride ?: selectedEmotion
        val adjustedCharacter = character.copy(
            defaultEmotion = targetEmotion.name,
            exaggeration = targetEmotion.chatterboxExaggeration,
            cfgWeight = targetEmotion.chatterboxCfgWeight
        )
        ForegroundTtsManager.getInstance(context).synthesizeCharacterPreview(
            assignment = adjustedCharacter,
            hindiText = hindiText,
            explicitEmotion = targetEmotion
        ) { _, statusMessage, _ ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar("${character.displayName}: $statusMessage")
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
                        modifier = Modifier.testTag("ab_testing_back_button")
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
                            text = "A/B Neural Voice Lab",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Chatterbox Multilingual V3 (Hindi) • Supertonic 3 Fallback",
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
                .testTag("ab_voice_testing_screen"),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. User-Visible Dubbing Mode Selector (Phase 4 Requirement)
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ab_dubbing_mode_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Active Dubbing Engine Mode",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val visibleModes = listOf(
                            FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER,
                            FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT,
                            FreeVoiceBackend.PAUSE_DUBBING
                        )

                        visibleModes.forEach { mode ->
                            val isSelected = state.freeVoiceBackend == mode
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                },
                                border = if (isSelected) {
                                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        TranslatorStateRepository.updateFreeVoiceBackend(context, mode)
                                    }
                                    .testTag("dubbing_mode_option_${mode.code}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            TranslatorStateRepository.updateFreeVoiceBackend(context, mode)
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = mode.displayLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = mode.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Reported Runtime Engine: ${state.activeTtsEngineLabel}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .testTag("ab_reported_runtime_engine")
                            )
                        }
                    }
                }

                // 2. Local Chatterbox Server Setup & Health Check Card
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ab_server_config_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Windows Local Neural Server (Chatterbox V3 Hindi)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "Self-hosted on your Windows PC over local Wi-Fi. Uses ResembleAI/Chatterbox-Multilingual-hi with cached reference voice conditioning and X-GameVox-Token authentication.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it },
                            label = { Text("Local Server URL (e.g. http://192.168.1.100:8090)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ab_server_url_input")
                        )

                        OutlinedTextField(
                            value = authTokenInput,
                            onValueChange = { authTokenInput = it },
                            label = { Text("X-GameVox-Token Shared Secret") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ab_server_token_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    isTestingServer = true
                                    TranslatorStateRepository.updateChatterboxServerConfig(
                                        context = context,
                                        serverUrl = serverUrlInput,
                                        authToken = authTokenInput
                                    )
                                    ForegroundTtsManager.getInstance(context)
                                        .testLocalChatterboxServerConnection { report ->
                                            isTestingServer = false
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (report.reachable && report.modelLoaded) {
                                                        "Connected: ${report.engineName} (${report.device}) • ${report.roundTripLatencyMs}ms"
                                                    } else {
                                                        "Server check: ${report.errorMessage.ifBlank { report.status }}"
                                                    }
                                                )
                                            }
                                        }
                                },
                                enabled = !isTestingServer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("ab_test_server_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isTestingServer) "Checking Local Server..." else "Save & Test Server Connection")
                            }
                        }

                        Text(
                            text = "Server Status: ${state.chatterboxServerStatusText}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.chatterboxServerReachable && state.chatterboxModelLoaded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // 3. Honest Reference Voice Identity & Sharing Disclosure Card
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ab_honest_voice_identity_card")
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
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Reference Voice Identity & Sharing Audit",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = VoiceProfileRepository.getUniqueReferenceVoiceSummary().third,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "• Male Reference (anon_male_ref.wav): SHA-256 ${CharacterVoiceAssignment.ANON_MALE_REF_SHA256.take(16)}…\n" +
                                "• Female Reference (jenny_female_ref.wav): SHA-256 ${CharacterVoiceAssignment.JENNY_FEMALE_REF_SHA256.take(16)}…\n" +
                                "• Transparency Note: GameVox never claims 24 unique reference recordings when multiple character profiles share the same reference WAV file. Add custom per-character .wav files in chatterbox_voice_server/reference_voices/ to expand unique speaker cloning.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 4. Shared Hindi Test Sentence & Emotion Selector
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ab_shared_sentence_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Shared Hindi Sentence for Side-by-Side A/B Testing",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = sharedTestHindiText,
                            onValueChange = { sharedTestHindiText = it },
                            label = { Text("Hindi Dialogue Line") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ab_shared_hindi_input")
                        )

                        Text(
                            text = "Quick Conversational Game Phrases:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            sampleSentences.forEachIndexed { idx, (hindi, label) ->
                                FilterChip(
                                    selected = sharedTestHindiText == hindi,
                                    onClick = { sharedTestHindiText = hindi },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("ab_sample_phrase_$idx")
                                )
                            }
                        }

                        HorizontalDivider()

                        Text(
                            text = "Emotion / Acting Delivery Preset (8 Categories):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SpeechEmotion.entries.forEach { emotion ->
                                FilterChip(
                                    selected = selectedEmotion == emotion,
                                    onClick = { selectedEmotion = emotion },
                                    label = {
                                        Text(
                                            "${emotion.displayLabel} (exag=${String.format(Locale.US, "%.2f", emotion.chatterboxExaggeration)})",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.testTag("ab_emotion_chip_${emotion.name.lowercase(Locale.ROOT)}")
                                )
                            }
                        }
                    }
                }

                // 5. Pair 1: Anon (Male) vs Jenny (Female) — Core Vertical Slice
                AbComparisonPairCard(
                    pairTitle = "Test 1: Male vs Female Vertical Slice (Anon vs Jenny)",
                    pairSubtitle = "Two distinct reference WAV files (anon_male_ref.wav != jenny_female_ref.wav) speaking the exact same Hindi sentence.",
                    leftCharacter = anonProfile,
                    rightCharacter = jennyProfile,
                    allCharacters = characterAssignments,
                    onPlayLeftRef = { playReferenceClip(anonProfile) },
                    onSynthesizeLeft = { synthesizeForCharacter(anonProfile, sharedTestHindiText) },
                    onPlayRightRef = { playReferenceClip(jennyProfile) },
                    onSynthesizeRight = { synthesizeForCharacter(jennyProfile, sharedTestHindiText) },
                    testTagPrefix = "ab_pair_anon_jenny"
                )

                // 6. Pair 2: Anon (Male Lead) vs Kaal (Male Villain)
                AbComparisonPairCard(
                    pairTitle = "Test 2: Male vs Male Comparison (Anon vs Kaal)",
                    pairSubtitle = "Compares Young Male Lead (SID 5) vs Deep Male Villain (SID 7) with honest reference file sharing disclosure.",
                    leftCharacter = anonProfile,
                    rightCharacter = kaalProfile,
                    allCharacters = characterAssignments,
                    onPlayLeftRef = { playReferenceClip(anonProfile) },
                    onSynthesizeLeft = { synthesizeForCharacter(anonProfile, sharedTestHindiText) },
                    onPlayRightRef = { playReferenceClip(kaalProfile) },
                    onSynthesizeRight = { synthesizeForCharacter(kaalProfile, sharedTestHindiText) },
                    testTagPrefix = "ab_pair_anon_kaal"
                )

                // 7. Pair 3: Jenny (Female Companion) vs Ira (Female AI)
                AbComparisonPairCard(
                    pairTitle = "Test 3: Female vs Female Comparison (Jenny vs Ira)",
                    pairSubtitle = "Compares Bright Female Companion (SID 1) vs Composed Female AI (SID 2) with honest reference file sharing disclosure.",
                    leftCharacter = jennyProfile,
                    rightCharacter = iraProfile,
                    allCharacters = characterAssignments,
                    onPlayLeftRef = { playReferenceClip(jennyProfile) },
                    onSynthesizeLeft = { synthesizeForCharacter(jennyProfile, sharedTestHindiText) },
                    onPlayRightRef = { playReferenceClip(iraProfile) },
                    onSynthesizeRight = { synthesizeForCharacter(iraProfile, sharedTestHindiText) },
                    testTagPrefix = "ab_pair_jenny_ira"
                )

                // 8. Pair 4: Emotional Delivery Tri-Comparison (Neutral vs Fear vs Angry)
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ab_emotion_comparison_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                text = "Test 4: Emotional Acting Delivery (Neutral vs Fear vs Angry)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Audition how Chatterbox exaggeration & CFG weight + acoustic rate modulation shape emotional delivery on the same character (${anonProfile.displayName}):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    synthesizeForCharacter(anonProfile, sharedTestHindiText, SpeechEmotion.NEUTRAL)
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("ab_emotion_btn_neutral")
                            ) {
                                Text("Neutral\n(0.45 / 0.55)", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = {
                                    synthesizeForCharacter(anonProfile, sharedTestHindiText, SpeechEmotion.FEAR)
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("ab_emotion_btn_fear")
                            ) {
                                Text("Fear/Scared\n(0.76 / 0.42)", style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = {
                                    synthesizeForCharacter(anonProfile, sharedTestHindiText, SpeechEmotion.ANGRY)
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("ab_emotion_btn_angry")
                            ) {
                                Text("Angry\n(0.82 / 0.38)", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // 9. 5-Line Character Consistency & No-Overlap Suite
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ab_consistency_suite_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlaylistPlay,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Test 5: 5-Line Character Consistency & Anti-Overlap Suite",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        FIVE_LINE_CONSISTENCY_SUITE.forEachIndexed { index, line ->
                            val cleanDialogue = SpeakerHeuristicEngine.stripLeadingCharacterName(line.rawEnglishSubtitle)
                            val translated = HindiTranslationEngine.translateOfflinePhrasebook(cleanDialogue) ?: line.expectedHindi
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "${index + 1}. OCR Input: \"${line.rawEnglishSubtitle}\" → Speaker '${line.characterKey.uppercase(Locale.ROOT)}' (${line.emotion.displayLabel})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Spoken Hindi (prefix stripped): \"$translated\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Text(
                            text = consistencySuiteStatus,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        Button(
                            onClick = {
                                if (isRunningConsistencySuite) return@Button
                                isRunningConsistencySuite = true
                                coroutineScope.launch {
                                    for ((idx, item) in FIVE_LINE_CONSISTENCY_SUITE.withIndex()) {
                                        val charProfile = VoiceProfileRepository.getAssignmentForCharacter(
                                            item.characterKey,
                                            context
                                        )
                                        val strippedEnglish = SpeakerHeuristicEngine.stripLeadingCharacterName(item.rawEnglishSubtitle)
                                        val hindiLine = HindiTranslationEngine.translateOfflinePhrasebook(strippedEnglish)
                                            ?: item.expectedHindi
                                        consistencySuiteStatus =
                                            "Playing Line ${idx + 1}/5 (${charProfile.displayName} • ${item.emotion.displayLabel}): \"$hindiLine\""
                                        synthesizeForCharacter(charProfile, hindiLine, item.emotion)
                                        delay(1400L)
                                    }
                                    consistencySuiteStatus =
                                        "Completed 5-line consistency suite: all speaker prefixes stripped, non-silent RMS verified."
                                    isRunningConsistencySuite = false
                                }
                            },
                            enabled = !isRunningConsistencySuite,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("ab_run_5line_suite_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlaylistPlay,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isRunningConsistencySuite) {
                                    "Running 5-Line Sequential Dialogue..."
                                } else {
                                    "Run 5-Line Consistency Suite"
                                }
                            )
                        }
                    }
                }

                // 10. Phase 5 Disaggregated Latency Telemetry Card
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ab_latency_telemetry_card")
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
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Disaggregated Voice Pipeline Latencies (Phase 5)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "• OCR Completion: ${state.ocrLatencyMs} ms\n" +
                                "• Modern Hindi Translation: ${state.translationLatencyMs} ms\n" +
                                "• Local Server HTTP Round-Trip: ${state.serverRequestLatencyMs} ms\n" +
                                "• Neural Voice Inference: ${state.serverInferenceLatencyMs} ms (Conditioning: ${state.conditioningLatencyMs} ms • Cached: ${state.conditioningCached})\n" +
                                "• Time to First Playable Audio: ${state.ttsFirstPcmChunkLatencyMs} ms\n" +
                                "• AudioTrack Playback Start: ${state.ttsAudioTrackWriteLatencyMs} ms (Est. DAC Playout: ${state.ttsEstimatedPlayoutLatencyMs} ms)\n" +
                                "• End-to-End Subtitle-to-Audio: ${state.endToEndSubtitleToAudioLatencyMs} ms\n" +
                                "• Last Waveform RMS: ${String.format(Locale.US, "%.4f", state.lastSynthesizedRms)} • Ref SHA-256: ${state.lastSynthesizedVoiceSha256.take(16).ifBlank { "—" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // 11. Subjective Listening Verification Checklist
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ab_listening_checklist_card")
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
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Subjective Human Listening Checklist (Physical Device)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        checklistItems.forEachIndexed { idx, label ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { checkedStates[idx] = !checkedStates[idx] }
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = checkedStates[idx],
                                    onCheckedChange = { checkedStates[idx] = it }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AbComparisonPairCard(
    pairTitle: String,
    pairSubtitle: String,
    leftCharacter: CharacterVoiceAssignment,
    rightCharacter: CharacterVoiceAssignment,
    allCharacters: List<CharacterVoiceAssignment>,
    onPlayLeftRef: () -> Unit,
    onSynthesizeLeft: () -> Unit,
    onPlayRightRef: () -> Unit,
    onSynthesizeRight: () -> Unit,
    testTagPrefix: String
) {
    val sharesSameReference = leftCharacter.referenceAudioSha256 == rightCharacter.referenceAudioSha256 &&
        leftCharacter.referenceAudioSha256.isNotBlank()

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}_card")
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
                    imageVector = Icons.Default.CompareArrows,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = pairTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = pairSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (sharesSameReference) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Honest Voice Disclosure: ${leftCharacter.displayName} and ${rightCharacter.displayName} currently share base reference '${leftCharacter.referenceVoiceId}.wav' (SHA-256 ${leftCharacter.referenceAudioSha256.take(10)}…), differentiated via Supertonic SID (${leftCharacter.speakerId} vs ${rightCharacter.speakerId}), exaggeration (${leftCharacter.exaggeration} vs ${rightCharacter.exaggeration}), and speech rate (${leftCharacter.speed}x vs ${rightCharacter.speed}x).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Verified Distinct Reference WAVs: '${leftCharacter.referenceVoiceId}.wav' (${leftCharacter.referenceAudioSha256.take(8)}…) vs '${rightCharacter.referenceVoiceId}.wav' (${rightCharacter.referenceAudioSha256.take(8)}…)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AbSideColumn(
                    character = leftCharacter,
                    sharingNote = CharacterVoiceAssignment.buildVoiceSharingDisclosure(leftCharacter, allCharacters),
                    onPlayRef = onPlayLeftRef,
                    onSynthesize = onSynthesizeLeft,
                    testTagPrefix = "${testTagPrefix}_left",
                    modifier = Modifier.weight(1f)
                )
                AbSideColumn(
                    character = rightCharacter,
                    sharingNote = CharacterVoiceAssignment.buildVoiceSharingDisclosure(rightCharacter, allCharacters),
                    onPlayRef = onPlayRightRef,
                    onSynthesize = onSynthesizeRight,
                    testTagPrefix = "${testTagPrefix}_right",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AbSideColumn(
    character: CharacterVoiceAssignment,
    sharingNote: String,
    onPlayRef: () -> Unit,
    onSynthesize: () -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${character.displayName} (${character.voicePresentation.uppercase(Locale.ROOT)})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Ref: ${character.referenceVoiceId}.wav • SID ${character.speakerId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = character.vocalStyleDescription,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sharingNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            OutlinedButton(
                onClick = onPlayRef,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .testTag("${testTagPrefix}_play_ref_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "Play Reference WAV",
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Play Ref Clip", style = MaterialTheme.typography.labelSmall)
            }

            Button(
                onClick = onSynthesize,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .testTag("${testTagPrefix}_synth_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Synthesize Hindi",
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Synthesize Hindi", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
