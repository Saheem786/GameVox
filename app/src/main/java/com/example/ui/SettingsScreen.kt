package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.TargetLanguage
import com.example.data.TranslatorAppState
import com.example.data.TtsEngineStatus
import com.example.service.CloudTtsProvider
import com.example.service.CloudTtsService
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: TranslatorAppState,
    activeVoiceSummary: String,
    isAccessibilityEnabled: Boolean,
    onNavigateBack: () -> Unit,
    onOpenVoiceManager: () -> Unit,
    onTargetLanguageChange: (TargetLanguage) -> Unit,
    onHighQualityVoiceChange: (Boolean) -> Unit,
    onSsmlEnabledChange: (Boolean) -> Unit,
    onCloudTtsProviderChange: (CloudTtsProvider) -> Unit,
    onSaveCloudTtsApiConfig: (CloudTtsProvider, String) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onShowTargetBoxChange: (Boolean) -> Unit,
    onTestHindiTts: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onInstallVoiceData: () -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selectedProviderDraft by remember { mutableStateOf(state.cloudTtsProvider) }
    var apiKeyInput by remember { mutableStateOf(state.savedKeyFor(state.cloudTtsProvider)) }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var isProviderMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(
        state.cloudTtsProvider,
        state.userGoogleCloudApiKey,
        state.userElevenLabsApiKey,
        state.userOpenAiApiKey
    ) {
        selectedProviderDraft = state.cloudTtsProvider
        apiKeyInput = state.savedKeyFor(state.cloudTtsProvider)
    }

    val fieldShape = RoundedCornerShape(14.dp)

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
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("settings_screen"),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // =====================================================
                // SECTION 1: API Configuration
                // =====================================================
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "API Configuration",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cloud_tts_api_settings_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = "Cloud TTS Provider",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Keys are saved locally via Jetpack DataStore",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Provider Selection Dropdown
                            ExposedDropdownMenuBox(
                                expanded = isProviderMenuExpanded,
                                onExpandedChange = { isProviderMenuExpanded = !isProviderMenuExpanded },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = selectedProviderDraft.displayLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("TTS Provider") },
                                    shape = fieldShape,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            expanded = isProviderMenuExpanded
                                        )
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                    ),
                                    modifier = Modifier
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                        .fillMaxWidth()
                                        .testTag("tts_provider_dropdown_field")
                                )

                                ExposedDropdownMenu(
                                    expanded = isProviderMenuExpanded,
                                    onDismissRequest = { isProviderMenuExpanded = false }
                                ) {
                                    CloudTtsProvider.entries.forEach { provider ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = provider.displayLabel,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    Text(
                                                        text = provider.subtitle,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            onClick = {
                                                selectedProviderDraft = provider
                                                apiKeyInput = state.savedKeyFor(provider)
                                                isProviderMenuExpanded = false
                                                onCloudTtsProviderChange(provider)
                                            },
                                            modifier = Modifier.testTag("dropdown_provider_${provider.code}")
                                        )
                                    }
                                }
                            }

                            // API Key Rounded M3 OutlinedTextField with Eye Show/Hide toggle
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("Enter API Key") },
                                placeholder = {
                                    Text(
                                        when (selectedProviderDraft) {
                                            CloudTtsProvider.GOOGLE_CLOUD_TTS -> "AIza... (Google Cloud TTS)"
                                            CloudTtsProvider.ELEVENLABS -> "sk_... (ElevenLabs)"
                                            CloudTtsProvider.OPENAI_TTS -> "sk-... (OpenAI TTS)"
                                            CloudTtsProvider.ANDROID_SSML_NETWORK -> "Not required for Default Phone TTS"
                                        }
                                    )
                                },
                                singleLine = true,
                                shape = fieldShape,
                                visualTransformation = if (isApiKeyVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { isApiKeyVisible = !isApiKeyVisible },
                                        modifier = Modifier.testTag("toggle_api_key_visibility_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isApiKeyVisible) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                            contentDescription = if (isApiKeyVisible) {
                                                "Hide API Key"
                                            } else {
                                                "Show API Key"
                                            },
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                supportingText = {
                                    val hasConfiguredKey = when (selectedProviderDraft) {
                                        CloudTtsProvider.GOOGLE_CLOUD_TTS ->
                                            CloudTtsService.hasGoogleCloudKey(apiKeyInput)
                                        CloudTtsProvider.ELEVENLABS ->
                                            CloudTtsService.hasElevenLabsKey(apiKeyInput)
                                        CloudTtsProvider.OPENAI_TTS ->
                                            CloudTtsService.hasOpenAiKey(apiKeyInput)
                                        CloudTtsProvider.ANDROID_SSML_NETWORK -> true
                                    }
                                    val helperText = when {
                                        !selectedProviderDraft.requiresApiKey ->
                                            "Using built-in device neural TTS engine."
                                        !hasConfiguredKey ->
                                            "No key entered — falls back to Default Phone TTS."
                                        else ->
                                            "Ready to use ${selectedProviderDraft.displayLabel}."
                                    }
                                    Text(
                                        text = helperText,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("enter_api_key_textfield")
                            )

                            // Full-width Clean Save Button
                            Button(
                                onClick = {
                                    val trimmed = apiKeyInput.trim()
                                    onSaveCloudTtsApiConfig(selectedProviderDraft, trimmed)
                                    scope.launch {
                                        val msg = when {
                                            !selectedProviderDraft.requiresApiKey ->
                                                "Saved: Default Phone TTS active"
                                            trimmed.isNotEmpty() ->
                                                "${selectedProviderDraft.displayLabel} API Key saved"
                                            else ->
                                                "Empty key — falling back to Default Phone TTS"
                                        }
                                        snackbarHostState.showSnackbar(msg)
                                    }
                                },
                                shape = fieldShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("save_api_key_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Save",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // =====================================================
                // SECTION 2: Voice & Speech
                // =====================================================
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Voice & Speech",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Active Voice -> Open Voice Manager
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenVoiceManager() }
                                    .padding(vertical = 6.dp)
                                    .testTag("settings_open_voice_manager_row"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RecordVoiceOver,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Voice Manager",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = activeVoiceSummary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Open Voice Manager",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Target Language
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Target Language",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("target_language_selector_group")
                                ) {
                                    TargetLanguage.entries.forEach { lang ->
                                        val isSelected = state.targetLanguage == lang
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { onTargetLanguageChange(lang) },
                                            label = {
                                                Text(
                                                    text = "${lang.displayName} (${lang.nativeName})",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            ),
                                            modifier = Modifier.testTag("target_lang_chip_${lang.code}")
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Emotion SSML Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Emotion Modulation (SSML)",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Adjust tone for '!' and '...' cues",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = state.ssmlEnabledOnDevice,
                                    onCheckedChange = onSsmlEnabledChange,
                                    modifier = Modifier.testTag("ssml_emotion_switch")
                                )
                            }

                            // High Quality Network Voice Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "High-Quality Network Voice",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Prefer natural neural voices over offline robotic TTS",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = state.useHighQualityNetworkVoice,
                                    onCheckedChange = onHighQualityVoiceChange,
                                    modifier = Modifier.testTag("high_quality_voice_switch")
                                )
                            }

                            // Show Green Scan Box Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Show Scan Box Overlay",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Single-tap floating button toggles scan box",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = state.showTargetBoxOverlay,
                                    onCheckedChange = onShowTargetBoxChange,
                                    modifier = Modifier.testTag("show_target_box_switch")
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Pitch Slider
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "Voice Pitch: %.2fx", state.ttsPitch),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    TextButton(
                                        onClick = { onPitchChange(1.0f) },
                                        modifier = Modifier.testTag("reset_pitch_chip")
                                    ) {
                                        Text("Reset")
                                    }
                                }
                                Slider(
                                    value = state.ttsPitch,
                                    onValueChange = onPitchChange,
                                    valueRange = 0.4f..1.8f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.testTag("tts_pitch_slider")
                                )
                            }

                            // Speech Rate Slider
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "Speech Rate: %.2fx", state.ttsSpeechRate),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    TextButton(
                                        onClick = { onSpeechRateChange(1.0f) },
                                        modifier = Modifier.testTag("reset_rate_chip")
                                    ) {
                                        Text("Reset")
                                    }
                                }
                                Slider(
                                    value = state.ttsSpeechRate,
                                    onValueChange = onSpeechRateChange,
                                    valueRange = 0.4f..1.8f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.testTag("tts_rate_slider")
                                )
                            }

                            OutlinedButton(
                                onClick = onTestHindiTts,
                                shape = fieldShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("sheet_test_tts_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Test Voice (${state.targetLanguage.displayName})")
                            }
                        }
                    }
                }

                // =====================================================
                // SECTION 3: Permissions & System
                // =====================================================
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                Icon(
                                    imageVector = if (isAccessibilityEnabled) {
                                        Icons.Default.CheckCircle
                                    } else {
                                        Icons.Default.AccessibilityNew
                                    },
                                    contentDescription = null,
                                    tint = if (isAccessibilityEnabled) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Game Screen Reader",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isAccessibilityEnabled) {
                                            "Accessibility service enabled"
                                        } else {
                                            "Required to read text from other games"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onEnableAccessibility,
                                modifier = Modifier.testTag("enable_accessibility_button")
                            ) {
                                Text(if (isAccessibilityEnabled) "Active" else "Enable")
                            }
                        }

                        if (state.ttsStatus == TtsEngineStatus.MISSING_HINDI_DATA) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            OutlinedButton(
                                onClick = onInstallVoiceData,
                                shape = fieldShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("install_hindi_tts_button")
                            ) {
                                Text("Install ${state.targetLanguage.displayName} Voice Data")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
