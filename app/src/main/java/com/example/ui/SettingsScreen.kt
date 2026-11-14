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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.TargetLanguage
import com.example.data.TranslationMode
import com.example.data.TranslatorAppState
import com.example.data.TtsEngineStatus
import com.example.service.FreeVoiceBackend
import java.io.File
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
    onTranslationModeChange: (TranslationMode, Boolean) -> Unit,
    onDownloadOfflinePack: () -> Unit,
    onSelectAndroidTtsEngine: (String) -> Unit,
    onRefreshTtsEngines: () -> Unit,
    onOpenSystemTtsSettings: () -> Unit,
    onSelectFreeVoiceBackend: (FreeVoiceBackend) -> Unit,
    onImportKokoroModel: () -> Unit,
    onToggleKokoroGpu: (Boolean) -> Unit,
    onClearKokoroModel: () -> Unit,
    onHighQualityVoiceChange: (Boolean) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onShowTargetBoxChange: (Boolean) -> Unit,
    onShowSubtitleCaptionsChange: (Boolean) -> Unit,
    onAddGlossaryEntry: (String, String) -> Boolean,
    onRemoveGlossaryEntry: (String) -> Unit,
    onResetGlossaryDefaults: () -> Unit,
    onTestHindiTts: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onInstallVoiceData: () -> Unit,
    onOpenDiagnosis: () -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showAiConsentDialog by remember { mutableStateOf(false) }

    var glossaryTermInput by remember { mutableStateOf("") }
    var glossaryPronunciationInput by remember { mutableStateOf("") }

    val fieldShape = RoundedCornerShape(14.dp)

    if (showAiConsentDialog) {
        AlertDialog(
            onDismissRequest = { showAiConsentDialog = false },
            title = {
                Text(
                    text = "Enable Context-Aware AI (Gemini)?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "When active, only the cropped English subtitle text and up to 4 recent dialogue lines are sent to Gemini (Firebase AI Logic) for natural Hindi dubbing. Requires internet and uses cloud quota. Free On-Device (ML Kit) remains available offline anytime.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAiConsentDialog = false
                        onTranslationModeChange(TranslationMode.CONTEXT_AWARE_AI, true)
                    },
                    modifier = Modifier.testTag("confirm_ai_privacy_consent_button")
                ) {
                    Text("Allow & Enable AI")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAiConsentDialog = false },
                    modifier = Modifier.testTag("cancel_ai_privacy_consent_button")
                ) {
                    Text("Keep Offline")
                }
            }
        )
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
                // SECTION 1: Subtitle Translation Engine (Offline vs AI)
                // =====================================================
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Translation Mode",
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
                            .testTag("translation_mode_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = "Offline vs Context-Aware AI",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = state.mlKitModelStatusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            TranslationModeOptionCard(
                                title = TranslationMode.ON_DEVICE_MLKIT.displayName,
                                badge = TranslationMode.ON_DEVICE_MLKIT.shortBadge,
                                description = TranslationMode.ON_DEVICE_MLKIT.costAndLimitNote,
                                selected = state.translationMode == TranslationMode.ON_DEVICE_MLKIT,
                                icon = Icons.Default.OfflinePin,
                                testTag = "mode_on_device_mlkit",
                                onClick = {
                                    onTranslationModeChange(
                                        TranslationMode.ON_DEVICE_MLKIT,
                                        state.aiPrivacyConsentAccepted
                                    )
                                }
                            )

                            TranslationModeOptionCard(
                                title = TranslationMode.CONTEXT_AWARE_AI.displayName,
                                badge = TranslationMode.CONTEXT_AWARE_AI.shortBadge,
                                description = TranslationMode.CONTEXT_AWARE_AI.costAndLimitNote,
                                selected = state.translationMode == TranslationMode.CONTEXT_AWARE_AI,
                                icon = Icons.Default.AutoAwesome,
                                testTag = "mode_context_aware_ai",
                                onClick = {
                                    if (state.aiPrivacyConsentAccepted) {
                                        onTranslationModeChange(TranslationMode.CONTEXT_AWARE_AI, true)
                                    } else {
                                        showAiConsentDialog = true
                                    }
                                }
                            )

                            OutlinedButton(
                                onClick = {
                                    onDownloadOfflinePack()
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Checking & downloading offline translation pack...")
                                    }
                                },
                                shape = fieldShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("download_offline_pack_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download / Verify Offline ${state.targetLanguage.displayName} Pack")
                            }
                        }
                    }
                }

                // =====================================================
                // SECTION 2: Free Neural Voice Engine (No API Key / No Billing)
                // =====================================================
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Free Voice Engine",
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
                            .testTag("free_tts_engine_settings_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (state.isSupertonicOrSherpaActive) {
                                            "Active: ${state.activeTtsEngineLabel} (Supertonic / sherpa-onnx)"
                                        } else {
                                            "Active: ${state.activeTtsEngineLabel}"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "100% Free • Zero API keys, credits, or subscriptions required",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }

                            TranslationModeOptionCard(
                                title = "Android System Default TTS Engine",
                                badge = if (state.selectedTtsEnginePackage.isBlank()) "Selected" else "Default",
                                description = "Automatically uses whatever engine you selected in Android System Settings (e.g. sherpa-onnx Supertonic 3 Hindi).",
                                selected = state.selectedTtsEnginePackage.isBlank(),
                                icon = Icons.Default.Settings,
                                testTag = "engine_option_system_default",
                                onClick = {
                                    onSelectAndroidTtsEngine("")
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Using Android System Default TTS engine")
                                    }
                                }
                            )

                            val hasSupertonicInstalled = state.installedTtsEngines.any { it.isSherpaOrSupertonic }
                            state.installedTtsEngines.forEach { engineInfo ->
                                val isSelected = state.selectedTtsEnginePackage == engineInfo.packageName
                                val badge = when {
                                    engineInfo.isSherpaOrSupertonic -> "Supertonic / sherpa-onnx"
                                    engineInfo.isSystemDefault -> "System Default"
                                    else -> "Installed"
                                }
                                TranslationModeOptionCard(
                                    title = engineInfo.label,
                                    badge = badge,
                                    description = "${engineInfo.packageName} • Free on-device Android TTS engine",
                                    selected = isSelected,
                                    icon = Icons.Default.RecordVoiceOver,
                                    testTag = "engine_option_${engineInfo.packageName}",
                                    onClick = {
                                        onSelectAndroidTtsEngine(engineInfo.packageName)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Switched TTS engine to ${engineInfo.label}")
                                        }
                                    }
                                )
                            }

                            if (!hasSupertonicInstalled) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Tip: Install the official sherpa-onnx Supertonic 3 Hindi TTS Engine app on your phone, then tap Refresh or set it in Android TTS Settings for free offline neural Hindi voice.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }

                            Text(
                                text = "Character Gender Support: ${state.genderVoiceCapabilityNote}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onRefreshTtsEngines()
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Refreshed installed TTS engines")
                                        }
                                    },
                                    shape = fieldShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("refresh_tts_engines_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Refresh")
                                }
                                OutlinedButton(
                                    onClick = onOpenSystemTtsSettings,
                                    shape = fieldShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("open_system_tts_settings_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("TTS Settings")
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Text(
                                text = "Optional MediaPipe Kokoro-82M (.litertlm)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Primary recommended engine: Supertonic 3 Hindi Android TTS above. Optionally import a ~295 MB Kokoro .litertlm bundle for on-device MediaPipe Tasks Audio synthesis (falls back automatically to your Android TTS engine if absent or unsupported).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("free_voice_backend_selector")
                            ) {
                                FreeVoiceBackend.entries.forEach { backend ->
                                    val isSelected = state.freeVoiceBackend == backend
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onSelectFreeVoiceBackend(backend) },
                                        label = {
                                            Text(
                                                text = if (backend == FreeVoiceBackend.ANDROID_USER_SELECTED) {
                                                    "Android / Supertonic 3"
                                                } else {
                                                    "Kokoro-82M MediaPipe"
                                                },
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        modifier = Modifier.testTag("backend_chip_${backend.code}")
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = state.kokoroStatusMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val fileName = state.kokoroModelPath
                                        .takeIf { it.isNotBlank() }
                                        ?.let { File(it).name }
                                        ?: "No .litertlm file selected"
                                    Text(
                                        text = "Bundle: $fileName",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Use GPU Delegate (with CPU fallback)",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Use OpenCL GPU for kokoro_int8_gpu_fp16.litertlm; CPU XNNPACK on emulators",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = state.kokoroUseGpu,
                                    onCheckedChange = onToggleKokoroGpu,
                                    modifier = Modifier.testTag("kokoro_gpu_switch")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onImportKokoroModel,
                                    shape = fieldShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("import_kokoro_model_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Import .litertlm")
                                }
                                if (state.kokoroModelPath.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            onClearKokoroModel()
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Removed Kokoro model reference")
                                            }
                                        },
                                        shape = fieldShape,
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("clear_kokoro_model_button")
                                    ) {
                                        Text("Clear")
                                    }
                                }
                            }
                        }
                    }
                }

                // =====================================================
                // SECTION 3: Voice & Speech
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Prefer Engine High-Quality Voice",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Pick highest quality free voice available on your selected engine",
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Show Subtitle Scan Region",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Draggable box positioned near bottom of screen",
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Show Hindi Subtitle Captions",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Floating translated text box over game",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = state.isSubtitleVisible,
                                    onCheckedChange = onShowSubtitleCaptionsChange,
                                    modifier = Modifier.testTag("show_subtitle_captions_switch")
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Volume Slider
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Dubbing Volume: ${(state.ttsVolume * 100).toInt()}%",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    TextButton(
                                        onClick = { onVolumeChange(1.0f) },
                                        modifier = Modifier.testTag("reset_volume_chip")
                                    ) {
                                        Text("Reset")
                                    }
                                }
                                Slider(
                                    value = state.ttsVolume,
                                    onValueChange = onVolumeChange,
                                    valueRange = 0.1f..1.0f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.testTag("tts_volume_slider")
                                )
                            }

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
                // SECTION 4: Pronunciation Glossary (Names & Game Terms)
                // =====================================================
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Pronunciation Glossary",
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pronunciation_glossary_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Customize how English names & Hinglish terms sound in Hindi TTS (on-screen text stays clean).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = glossaryTermInput,
                                    onValueChange = { glossaryTermInput = it },
                                    label = { Text("English") },
                                    placeholder = { Text("Anon") },
                                    singleLine = true,
                                    shape = fieldShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("glossary_term_input")
                                )
                                OutlinedTextField(
                                    value = glossaryPronunciationInput,
                                    onValueChange = { glossaryPronunciationInput = it },
                                    label = { Text("Spoken") },
                                    placeholder = { Text("एनॉन") },
                                    singleLine = true,
                                    shape = fieldShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("glossary_pronunciation_input")
                                )
                                IconButton(
                                    onClick = {
                                        if (onAddGlossaryEntry(glossaryTermInput, glossaryPronunciationInput)) {
                                            glossaryTermInput = ""
                                            glossaryPronunciationInput = ""
                                        } else {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Enter both English term and Hindi pronunciation")
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("add_glossary_entry_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add Pronunciation Rule",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            if (state.pronunciationGlossary.isNotEmpty()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                state.pronunciationGlossary.entries.forEach { (term, spoken) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$term → $spoken",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { onRemoveGlossaryEntry(term) },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("delete_glossary_${term}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Remove $term",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }

                            TextButton(
                                onClick = {
                                    onResetGlossaryDefaults()
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Pronunciation glossary reset to defaults")
                                    }
                                },
                                modifier = Modifier.testTag("reset_glossary_button")
                            ) {
                                Text("Reset Default Glossary")
                            }
                        }
                    }
                }

                // =====================================================
                // SECTION 5: Permissions & System
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
                                        text = "Game Screen Reader (Optional)",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isAccessibilityEnabled) {
                                            "Accessibility service enabled"
                                        } else {
                                            "Optional helper for UI-based dialogue apps"
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

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenDiagnosis() }
                                .padding(vertical = 6.dp)
                                .testTag("open_diagnosis_screen_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BugReport,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Live OCR & TTS Diagnosis",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Inspect cropped ROI preview, extracted text, TTS callbacks & latencies",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Open Live Diagnosis",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

@Composable
private fun TranslationModeOptionCard(
    title: String,
    badge: String,
    description: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
