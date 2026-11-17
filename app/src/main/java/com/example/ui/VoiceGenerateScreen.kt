package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.CharacterVoiceAssignment
import com.example.data.SpeakerGender
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceCategory
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import com.example.engine.SpeechEmotion
import com.example.service.ForegroundTtsManager
import com.example.service.FreeVoiceBackend
import com.example.service.KokoroMediaPipeTtsEngine
import com.example.service.SupertonicModelManager
import com.example.service.SupertonicModelState
import java.io.File
import kotlinx.coroutines.launch

private data class PreparedVoiceCandidate(
    val name: String,
    val gender: SpeakerGender,
    val category: VoiceCategory,
    val sourceModelInfo: String,
    val languageCode: String,
    val styleTag: String,
    val speakerId: Int,
    val pitch: Float,
    val speechRate: Float,
    val sampleAudioPath: String?,
    val isNeuralCloned: Boolean,
    val previewText: String
)

private val GENERATE_STYLES = listOf("Neutral", "Conversational", "Heroic", "Expressive", "Commanding", "Calm")
private val GENERATE_LANGUAGES = listOf("hi" to "Hindi", "en" to "English", "mr" to "Marathi", "ta" to "Tamil", "ja" to "Japanese")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceGenerateScreen(
    snackbarHostState: SnackbarHostState,
    onSavedToLibrary: (VoiceProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appState by TranslatorStateRepository.state.collectAsStateWithLifecycle()
    val supertonicState by SupertonicModelManager.modelState.collectAsStateWithLifecycle()

    var voiceNameInput by remember { mutableStateOf("") }
    var selectedGender by remember { mutableStateOf(SpeakerGender.MALE_18) }
    var selectedLanguage by remember { mutableStateOf("hi") }
    var selectedStyle by remember { mutableStateOf("Neutral") }
    var previewTextInput by remember { mutableStateOf(VOICE_PREVIEW_HINDI_SAMPLE) }

    var modeTab by remember { mutableIntStateOf(0) } // 0=Offline, 1=Online, 2=Import/Clone
    var offlineModelId by remember { mutableStateOf("supertonic_3") } // supertonic_3 | android_tts | kokoro_82m
    var selectedSid by remember { mutableIntStateOf(5) }
    var isSynthesizing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var consentConfirmed by remember { mutableStateOf(false) }

    var serverUrlInput by remember(appState.chatterboxServerUrl) { mutableStateOf(appState.chatterboxServerUrl) }
    var serverTokenInput by remember(appState.chatterboxAuthToken) { mutableStateOf(appState.chatterboxAuthToken) }

    var preparedCandidate by remember { mutableStateOf<PreparedVoiceCandidate?>(null) }
    var lastSavedVoice by remember { mutableStateOf<VoiceProfile?>(null) }
    var customAssignInput by remember { mutableStateOf("") }
    var generatedPitch by remember { mutableFloatStateOf(1.0f) }
    var generatedRate by remember { mutableFloatStateOf(1.0f) }

    val ttsManager = remember { ForegroundTtsManager.getInstance(context) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("voice_generate_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Capability Integrity Guide: clearly separates existing selection, acoustic presets, neural cloning, TTS synthesis, and saving
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("voice_generate_capability_disclosure")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "How Voice Generate Works (No Fake Cloning)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "• Existing Voices: browse & pick in Voices tab.\n" +
                            "• Modified Acoustic Preset (Offline): adjusts Pitch/Speed on Supertonic 3 / Android TTS (NOT neural cloning).\n" +
                            "• Neural Voice Cloning (Import / Clone): requires your consented 5–10s WAV sample AND a connected PC Chatterbox server.\n" +
                            "• Save New Voice: stores the verified audio/preset in your Voice Library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Section 0: Generation Mode Selector right at top
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                TabRow(selectedTabIndex = modeTab) {
                    Tab(
                        selected = modeTab == 0,
                        onClick = { modeTab = 0 },
                        text = { Text("Offline", maxLines = 1) },
                        modifier = Modifier.testTag("gen_tab_offline")
                    )
                    Tab(
                        selected = modeTab == 1,
                        onClick = { modeTab = 1 },
                        text = { Text("Online", maxLines = 1) },
                        modifier = Modifier.testTag("gen_tab_online")
                    )
                    Tab(
                        selected = modeTab == 2,
                        onClick = { modeTab = 2 },
                        text = { Text("Import / Clone", maxLines = 1) },
                        modifier = Modifier.testTag("gen_tab_import_clone")
                    )
                }
            }

            // Section 1: New Voice Identity
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "New Voice",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = voiceNameInput,
                        onValueChange = { voiceNameInput = it },
                        label = { Text("Voice Name") },
                        placeholder = { Text("e.g. Alice Heroine Hindi, Kaal Deep Boss") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("generate_voice_name_input")
                    )

                    Text("Category / Timbre", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            SpeakerGender.MALE_18 to "Male",
                            SpeakerGender.FEMALE_18 to "Female",
                            SpeakerGender.UNKNOWN to "Custom / Neutral"
                        ).forEach { (gender, label) ->
                            FilterChip(
                                selected = selectedGender == gender,
                                onClick = {
                                    selectedGender = gender
                                    selectedSid = if (gender == SpeakerGender.FEMALE_18) 1 else 5
                                    generatedPitch = gender.defaultPitch18Yo
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("gen_gender_${gender.code}")
                            )
                        }
                    }

                    Text("Language", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GENERATE_LANGUAGES.forEach { (code, label) ->
                            FilterChip(
                                selected = selectedLanguage == code,
                                onClick = { selectedLanguage = code },
                                label = { Text(label) },
                                modifier = Modifier.testTag("gen_lang_$code")
                            )
                        }
                    }

                    Text("Voice Style", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GENERATE_STYLES.forEach { style ->
                            FilterChip(
                                selected = selectedStyle == style,
                                onClick = { selectedStyle = style },
                                label = { Text(style) },
                                modifier = Modifier.testTag("gen_style_$style")
                            )
                        }
                    }

                    OutlinedTextField(
                        value = previewTextInput,
                        onValueChange = { previewTextInput = it },
                        label = { Text("Preview Dialogue Text") },
                        maxLines = 3,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("generate_voice_preview_text_input")
                    )
                }
            }

            // Section 2: Active Generation / Import / Clone Mode Body
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (modeTab) {
                            0 -> {
                                // GENERATE OFFLINE
                                Text("Local Neural / System Engine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = offlineModelId == "supertonic_3",
                                        onClick = { offlineModelId = "supertonic_3" },
                                        label = { Text("Supertonic 3 (Offline)") },
                                        modifier = Modifier.testTag("gen_offline_model_supertonic")
                                    )
                                    FilterChip(
                                        selected = offlineModelId == "android_tts",
                                        onClick = { offlineModelId = "android_tts" },
                                        label = { Text("Android System TTS") },
                                        modifier = Modifier.testTag("gen_offline_model_android")
                                    )
                                    FilterChip(
                                        selected = offlineModelId == "kokoro_82m",
                                        onClick = { offlineModelId = "kokoro_82m" },
                                        label = { Text("Kokoro-82M (.litertlm)") },
                                        modifier = Modifier.testTag("gen_offline_model_kokoro")
                                    )
                                }

                                Text("Speaker Archetype (SID 0–9)", style = MaterialTheme.typography.labelLarge)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    SUPERTONIC_VOICE_SPECS.forEach { spec ->
                                        FilterChip(
                                            selected = selectedSid == spec.sid,
                                            onClick = {
                                                selectedSid = spec.sid
                                                selectedGender = if (spec.gender == "Female") SpeakerGender.FEMALE_18 else SpeakerGender.MALE_18
                                                generatedPitch = selectedGender.defaultPitch18Yo
                                            },
                                            label = { Text("${spec.code} (${spec.gender.take(1)})") },
                                            modifier = Modifier.testTag("gen_sid_${spec.sid}")
                                        )
                                    }
                                }

                                // Honest Acoustic Preset Tweaks (pitch/speed — explicitly not neural cloning)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Modified Acoustic Preset (Pitch & Speed Only — Not Cloning)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = String.format(java.util.Locale.US, "Pitch: %.2fx   Speed: %.2fx", generatedPitch, generatedRate),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Slider(
                                            value = generatedPitch,
                                            onValueChange = { generatedPitch = it },
                                            valueRange = 0.7f..1.4f,
                                            modifier = Modifier.testTag("gen_offline_pitch_slider")
                                        )
                                        Slider(
                                            value = generatedRate,
                                            onValueChange = { generatedRate = it },
                                            valueRange = 0.7f..1.4f,
                                            modifier = Modifier.testTag("gen_offline_rate_slider")
                                        )
                                    }
                                }

                                val isSupertonicReady = supertonicState is SupertonicModelState.Ready &&
                                    (supertonicState as SupertonicModelState.Ready).nativeInferenceVerified
                                val kokoroReady = KokoroMediaPipeTtsEngine.getInstance(context)
                                    .discoverModelFile(appState.kokoroModelPath).resolvedFile != null

                                if (offlineModelId == "supertonic_3" && !isSupertonicReady) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Supertonic 3 Hindi model (~145 MB) is missing/unverified on device. Download & verify first, or switch to Android System TTS.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            OutlinedButton(
                                                onClick = {
                                                    ttsManager.downloadVerifyAndInitializeSupertonic { ok, stateAfter ->
                                                        statusMessage = if (ok) {
                                                            "Supertonic 3 READY!"
                                                        } else {
                                                            (stateAfter as? SupertonicModelState.Error)?.errorMessage ?: "Download/init failed"
                                                        }
                                                    }
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(48.dp)
                                            ) {
                                                Text("Download & Verify Supertonic 3")
                                            }
                                        }
                                    }
                                } else if (offlineModelId == "kokoro_82m" && !kokoroReady) {
                                    Text(
                                        text = "Kokoro-82M .litertlm model is not imported yet. Import the bundle in Settings first.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                Button(
                                    onClick = {
                                        val effectiveName = voiceNameInput.trim().ifBlank {
                                            "Offline ${if (selectedGender == SpeakerGender.FEMALE_18) "Female" else "Male"} SID#$selectedSid"
                                        }
                                        val cleanPrompt = previewTextInput.trim().ifBlank { VOICE_PREVIEW_HINDI_SAMPLE }
                                        if (offlineModelId == "supertonic_3" && !isSupertonicReady) {
                                            statusMessage = "Supertonic 3 is not ready yet. Download/verify first."
                                            return@Button
                                        }
                                        if (offlineModelId == "kokoro_82m" && !kokoroReady) {
                                            statusMessage = "Kokoro-82M .litertlm bundle not found."
                                            return@Button
                                        }

                                        isSynthesizing = true
                                        val wavDir = File(context.filesDir, "custom_voices").apply { mkdirs() }
                                        val wavOut = File(wavDir, "gen_offline_${System.currentTimeMillis()}.wav")
                                        val engineStr = when (offlineModelId) {
                                            "supertonic_3" -> "sherpa_supertonic_3"
                                            "kokoro_82m" -> "kokoro_mediapipe"
                                            else -> "android_system_tts"
                                        }
                                        val assignment = CharacterVoiceAssignment(
                                            characterName = "gen_${System.currentTimeMillis()}",
                                            displayName = effectiveName,
                                            speakerId = selectedSid.coerceIn(0, 9),
                                            speed = generatedRate,
                                            pitch = generatedPitch,
                                            voicePresentation = if (selectedGender == SpeakerGender.FEMALE_18) "female" else "male",
                                            voiceEngine = engineStr
                                        )
                                        ttsManager.synthesizeCharacterPreview(
                                            assignment = assignment,
                                            hindiText = cleanPrompt,
                                            explicitEmotion = SpeechEmotion.NEUTRAL,
                                            exportWavFile = wavOut
                                        ) { ok, engineSummary, rms ->
                                            isSynthesizing = false
                                            if (ok) {
                                                lastSavedVoice = null
                                                preparedCandidate = PreparedVoiceCandidate(
                                                    name = effectiveName,
                                                    gender = selectedGender,
                                                    category = VoiceCategory.GENERATED_OFFLINE,
                                                    sourceModelInfo = "$engineSummary • Acoustic Preset ($selectedStyle)",
                                                    languageCode = selectedLanguage,
                                                    styleTag = selectedStyle,
                                                    speakerId = selectedSid,
                                                    pitch = generatedPitch,
                                                    speechRate = generatedRate,
                                                    sampleAudioPath = wavOut.takeIf { it.exists() && it.length() > 100L }?.absolutePath,
                                                    isNeuralCloned = false,
                                                    previewText = cleanPrompt
                                                )
                                                statusMessage = "Generated offline preview via $engineSummary (RMS %.4f)".format(rms)
                                            } else {
                                                statusMessage = "Offline generation failed: $engineSummary"
                                            }
                                        }
                                    },
                                    enabled = !isSynthesizing,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("generate_offline_button")
                                ) {
                                    if (isSynthesizing) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.OfflineBolt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Generate Offline Preview")
                                }
                            }

                            1 -> {
                                // GENERATE ONLINE
                                Text("Local Wi-Fi Chatterbox Neural Server", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "Cloud LLM (Gemini) translates game dialogue text with scene context only — it does not generate TTS audio. Configure your local Chatterbox FastAPI server below for online neural Hindi synthesis.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                OutlinedTextField(
                                    value = serverUrlInput,
                                    onValueChange = { serverUrlInput = it },
                                    label = { Text("Chatterbox Server URL") },
                                    placeholder = { Text("http://192.168.1.100:8000") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("gen_online_server_url_input")
                                )
                                OutlinedTextField(
                                    value = serverTokenInput,
                                    onValueChange = { serverTokenInput = it },
                                    label = { Text("Bearer Token (Optional)") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("gen_online_server_token_input")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            TranslatorStateRepository.updateChatterboxServerConfig(
                                                context = context,
                                                serverUrl = serverUrlInput,
                                                authToken = serverTokenInput
                                            )
                                            ttsManager.testLocalChatterboxServerConnection { report ->
                                                statusMessage = if (report.reachable && report.modelLoaded) {
                                                    "Connected (${report.device}): ${report.uniqueReferenceVoicesSha256Count} voices ready"
                                                } else {
                                                    "[${report.errorCategory.code}] ${report.errorMessage}"
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("gen_online_test_server_button")
                                    ) {
                                        Text("Test Server")
                                    }

                                    Button(
                                        onClick = {
                                            TranslatorStateRepository.updateChatterboxServerConfig(
                                                context = context,
                                                serverUrl = serverUrlInput,
                                                authToken = serverTokenInput
                                            )
                                            if (!appState.chatterboxServerReachable || !appState.chatterboxModelLoaded) {
                                                statusMessage = "Server not ready: ${appState.chatterboxServerStatusText.ifBlank { "Tap Test Server first" }}"
                                                return@Button
                                            }
                                            val effectiveName = voiceNameInput.trim().ifBlank { "Online Chatterbox Voice" }
                                            val cleanPrompt = previewTextInput.trim().ifBlank { VOICE_PREVIEW_HINDI_SAMPLE }
                                            isSynthesizing = true
                                            val wavDir = File(context.filesDir, "custom_voices").apply { mkdirs() }
                                            val wavOut = File(wavDir, "gen_online_${System.currentTimeMillis()}.wav")
                                            val assign = CharacterVoiceAssignment(
                                                characterName = "gen_online_${System.currentTimeMillis()}",
                                                displayName = effectiveName,
                                                speakerId = selectedSid,
                                                voicePresentation = if (selectedGender == SpeakerGender.FEMALE_18) "female" else "male",
                                                voiceEngine = FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER.code
                                            )
                                            ttsManager.synthesizeCharacterPreview(
                                                assignment = assign,
                                                hindiText = cleanPrompt,
                                                explicitEmotion = SpeechEmotion.NEUTRAL,
                                                exportWavFile = wavOut
                                            ) { ok, engineSummary, _ ->
                                                isSynthesizing = false
                                                if (ok) {
                                                    lastSavedVoice = null
                                                    preparedCandidate = PreparedVoiceCandidate(
                                                        name = effectiveName,
                                                        gender = selectedGender,
                                                        category = VoiceCategory.GENERATED_ONLINE,
                                                        sourceModelInfo = "Chatterbox Online • $engineSummary",
                                                        languageCode = selectedLanguage,
                                                        styleTag = selectedStyle,
                                                        speakerId = selectedSid,
                                                        pitch = selectedGender.defaultPitch18Yo,
                                                        speechRate = generatedRate,
                                                        sampleAudioPath = wavOut.takeIf { it.exists() && it.length() > 100L }?.absolutePath,
                                                        isNeuralCloned = false,
                                                        previewText = cleanPrompt
                                                    )
                                                    statusMessage = "Online generation complete via $engineSummary"
                                                } else {
                                                    statusMessage = "Online generation failed: $engineSummary"
                                                }
                                            }
                                        },
                                        enabled = !isSynthesizing && appState.chatterboxServerReachable && appState.chatterboxModelLoaded,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("generate_online_button")
                                    ) {
                                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Generate Online")
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = appState.chatterboxServerStatusText.ifBlank { "Server status: Not tested" },
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            else -> {
                                // SAMPLE IMPORT / CLONE
                                VoiceSampleImportSection(
                                    voiceName = voiceNameInput,
                                    selectedGender = selectedGender,
                                    selectedLanguage = selectedLanguage,
                                    selectedStyle = selectedStyle,
                                    consentConfirmed = consentConfirmed,
                                    isChatterboxConnected = appState.chatterboxServerReachable && appState.chatterboxModelLoaded,
                                    onConsentChange = { consentConfirmed = it },
                                    onSampleReadyForLibrary = { audioPath, inspection, category, sourceInfo, estPitch, estRate, isNeuralCloned ->
                                        lastSavedVoice = null
                                        val effectiveName = voiceNameInput.trim().ifBlank {
                                            if (category == VoiceCategory.CLONED) "Cloned Voice" else "Imported Sample Voice"
                                        }
                                        preparedCandidate = PreparedVoiceCandidate(
                                            name = effectiveName,
                                            gender = selectedGender,
                                            category = category,
                                            sourceModelInfo = sourceInfo,
                                            languageCode = selectedLanguage,
                                            styleTag = selectedStyle,
                                            speakerId = selectedSid,
                                            pitch = estPitch,
                                            speechRate = estRate,
                                            sampleAudioPath = audioPath,
                                            isNeuralCloned = isNeuralCloned,
                                            previewText = previewTextInput.ifBlank { VOICE_PREVIEW_HINDI_SAMPLE }
                                        )
                                        statusMessage = "Ready to preview, save, or assign \"$effectiveName\" (${inspection.qualityVerdict})."
                                    },
                                    onShowMessage = { msg ->
                                        statusMessage = msg
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                )
                            }
                        }

                        if (statusMessage.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("voice_generate_status_banner")
                            ) {
                                Text(
                                    text = statusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Section 3: Post-Generation / Import Actions (Preview, Save to Library, Assign to Character)
            preparedCandidate?.let { candidate ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("post_generation_actions_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Text(
                                text = "Ready: ${candidate.name}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "${candidate.category.displayLabel} • ${candidate.sourceModelInfo}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val path = candidate.sampleAudioPath
                                    if (!path.isNullOrBlank() && File(path).exists()) {
                                        VoiceProfileRepository.playRecordedReferenceSample(path)
                                    } else {
                                        val assign = CharacterVoiceAssignment(
                                            characterName = "preview_candidate",
                                            displayName = candidate.name,
                                            speakerId = candidate.speakerId,
                                            speed = candidate.speechRate,
                                            pitch = candidate.pitch,
                                            voicePresentation = if (candidate.gender == SpeakerGender.FEMALE_18) "female" else "male"
                                        )
                                        ttsManager.synthesizeCharacterPreview(assign, candidate.previewText)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("post_gen_play_preview_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play Preview")
                            }

                            Button(
                                onClick = {
                                    val saved = VoiceProfileRepository.addGeneratedOrImportedVoice(
                                        context = context,
                                        name = voiceNameInput.trim().ifBlank { candidate.name },
                                        description = candidate.sourceModelInfo,
                                        gender = candidate.gender,
                                        pitch = candidate.pitch,
                                        speechRate = candidate.speechRate,
                                        engineVoiceIndex = candidate.speakerId,
                                        voiceCategory = candidate.category,
                                        sourceModelInfo = candidate.sourceModelInfo,
                                        languageCode = candidate.languageCode,
                                        styleTag = candidate.styleTag,
                                        sampleAudioPath = candidate.sampleAudioPath,
                                        isNeuralCloned = candidate.isNeuralCloned,
                                        selectImmediately = true
                                    )
                                    lastSavedVoice = saved
                                    onSavedToLibrary(saved)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Saved \"${saved.name}\" to Voice Library")
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("post_gen_save_to_library_button")
                            ) {
                                Icon(
                                    imageVector = if (lastSavedVoice != null) Icons.Default.CheckCircle else Icons.Default.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (lastSavedVoice != null) "Saved!" else "Save to Library")
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Assign directly to character:", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Anon", "Jenny", "Alice").forEach { charTarget ->
                                OutlinedButton(
                                    onClick = {
                                        val saved = lastSavedVoice ?: VoiceProfileRepository.addGeneratedOrImportedVoice(
                                            context = context,
                                            name = voiceNameInput.trim().ifBlank { candidate.name },
                                            description = candidate.sourceModelInfo,
                                            gender = candidate.gender,
                                            pitch = candidate.pitch,
                                            speechRate = candidate.speechRate,
                                            engineVoiceIndex = candidate.speakerId,
                                            voiceCategory = candidate.category,
                                            sourceModelInfo = candidate.sourceModelInfo,
                                            languageCode = candidate.languageCode,
                                            styleTag = candidate.styleTag,
                                            sampleAudioPath = candidate.sampleAudioPath,
                                            isNeuralCloned = candidate.isNeuralCloned,
                                            selectImmediately = true
                                        ).also { lastSavedVoice = it }
                                        val assigned = VoiceProfileRepository.assignVoiceToCharacter(context, saved, charTarget)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Assigned \"${saved.name}\" to ${assigned.displayName}")
                                        }
                                    },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("post_gen_assign_$charTarget")
                                ) {
                                    Text("Assign to $charTarget")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
