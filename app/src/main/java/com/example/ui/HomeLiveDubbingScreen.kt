package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.CharacterVoiceAssignment
import com.example.data.DubbingDeliveryMode
import com.example.data.SpeakerGender
import com.example.data.TargetLanguage
import com.example.data.TranslationMode
import com.example.data.TranslationStyle
import com.example.data.TranslatorAppState
import com.example.data.VoiceProfile
import java.util.Locale

private val QUICK_HOME_CHARACTERS = listOf("Anon", "Jenny", "Alice", "Kaal", "Ira")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeLiveDubbingScreen(
    state: TranslatorAppState,
    activeCharacter: CharacterVoiceAssignment?,
    activeVoice: VoiceProfile?,
    activeGameNamespace: String = "default_game",
    hasOverlayPermission: Boolean,
    onToggleStartStopService: () -> Unit,
    onToggleFloatingScanning: () -> Unit,
    onToggleSubtitleVisibility: (Boolean) -> Unit,
    onSelectQuickCharacter: (String) -> Unit,
    onTargetLanguageChange: (TargetLanguage) -> Unit,
    onToggleTranslationMode: () -> Unit,
    onTranslationStyleChange: (TranslationStyle) -> Unit,
    onDeliveryModeChange: (DubbingDeliveryMode) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenVoiceLibrary: () -> Unit,
    onOpenDiagnosis: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = state.isServiceRunning
    val primaryColor = animateColorAsState(
        targetValue = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        animationSpec = tween(250),
        label = "homeStartStopColor"
    ).value
    val haloColor = animateColorAsState(
        targetValue = if (isRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        animationSpec = tween(250),
        label = "homeStartStopHalo"
    ).value

    val activeCharName = activeCharacter?.displayName?.ifBlank { "Anon" } ?: "Anon"
    val assignedVoiceTitle = activeVoice?.name ?: "SID #${activeCharacter?.speakerId ?: 5}"
    val isMaleChar = (activeCharacter?.voicePresentation ?: activeVoice?.gender?.code)
        .orEmpty()
        .contains("male", ignoreCase = true) &&
        !(activeCharacter?.voicePresentation ?: activeVoice?.gender?.code)
            .orEmpty()
            .contains("female", ignoreCase = true)

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("home_live_dubbing_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Live Dubbing & Scanning Status Pill
            Surface(
                color = when {
                    state.isProtectedScreenDetected -> MaterialTheme.colorScheme.errorContainer
                    isRunning && state.isCapturingOcr -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(50),
                modifier = Modifier.testTag("overlay_status_badge")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    state.isProtectedScreenDetected -> MaterialTheme.colorScheme.error
                                    isRunning && state.isCapturingOcr -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                    )
                    Text(
                        text = when {
                            state.isProtectedScreenDetected -> "Protected Screen (FLAG_SECURE)"
                            isRunning && state.isCapturingOcr -> "Scanning ON • Live Dubbing Active"
                            isRunning -> "Scanning OFF • Floating Icon Ready"
                            else -> "Ready to Dub Game Subtitles"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (state.lastGestureFeedback.isNotBlank() &&
                System.currentTimeMillis() - state.lastGestureFeedbackTimestampMs < 15_000L
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.testTag("home_gesture_feedback_pill")
                ) {
                    Text(
                        text = "Last Bubble Action: ${state.lastGestureFeedback}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            if (state.isProtectedScreenDetected) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("protected_screen_warning_card")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "Protected screen warning",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = state.captureStatusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // 2. Primary Start / Stop Dubbing Control
            Box(
                modifier = Modifier
                    .size(164.dp)
                    .clip(CircleShape)
                    .background(haloColor.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = onToggleStartStopService,
                    shape = CircleShape,
                    color = primaryColor,
                    contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(132.dp)
                        .shadow(10.dp, CircleShape)
                        .testTag("toggle_overlay_demo_button")
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isRunning) "Stop Dubbing" else "Start Dubbing",
                            modifier = Modifier.size(46.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isRunning) "Stop" else "Start",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onToggleFloatingScanning,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("home_pause_resume_scanning_button")
                    ) {
                        Icon(
                            imageVector = if (state.isCapturingOcr) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (state.isCapturingOcr) "Pause Scan" else "Resume Scan")
                    }
                    OutlinedButton(
                        onClick = { onToggleSubtitleVisibility(!state.isSubtitleVisible) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("home_toggle_subtitles_button")
                    ) {
                        Icon(
                            imageVector = if (state.isSubtitleVisible) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (state.isSubtitleVisible) "Hide Subtitles" else "Show Subtitles")
                    }
                }
            }

            if (!hasOverlayPermission) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("overlay_permission_prompt_card")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Layers, contentDescription = null)
                            Text(
                                text = "Display Over Other Apps Permission Needed",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Grant overlay permission so the floating control bubble & subtitle captions work over games.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = onRequestOverlayPermission,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("grant_overlay_permission_button")
                        ) {
                            Text("Enable Overlay Permission")
                        }
                    }
                }
            }

            // 3. Current Character & Assigned Voice Card
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_current_character_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenVoiceLibrary)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isMaleChar) Icons.Default.Male else Icons.Default.Female,
                                    contentDescription = "Current character gender",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Character: $activeCharName • Game: $activeGameNamespace",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Assigned Voice: $assignedVoiceTitle • SID #${activeCharacter?.speakerId ?: 5}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open Voice Library"
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        QUICK_HOME_CHARACTERS.forEach { charName ->
                            val isSelected = activeCharName.equals(charName, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectQuickCharacter(charName) },
                                label = { Text(charName) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                modifier = Modifier.testTag("home_quick_char_$charName")
                            )
                        }
                    }
                }
            }

            // 4. Language & Subtitle Visibility Card
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_language_subtitles_card")
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
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Translation Language: ${state.targetLanguage.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TargetLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = state.targetLanguage == lang,
                                onClick = { onTargetLanguageChange(lang) },
                                label = { Text(lang.displayName) },
                                modifier = Modifier.testTag("home_lang_chip_${lang.code}")
                            )
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
                                text = "Floating Subtitle Captions",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Double-tap floating icon anytime to hide/show without stopping dubbing",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = state.isSubtitleVisible,
                            onCheckedChange = onToggleSubtitleVisibility,
                            modifier = Modifier.testTag("home_subtitle_visibility_switch")
                        )
                    }
                }
            }

            // 5. Performance Settings Card
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_performance_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onToggleTranslationMode)
                            .padding(vertical = 4.dp)
                            .testTag("home_translation_mode_card"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Mode: ${state.translationMode.displayName}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = state.mlKitModelStatusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "Switch",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DubbingDeliveryMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.dubbingDeliveryMode == mode,
                                onClick = { onDeliveryModeChange(mode) },
                                label = { Text("${mode.displayName} (${mode.shortBadge})") },
                                modifier = Modifier.testTag("home_delivery_${mode.code}")
                            )
                        }
                        TranslationStyle.entries.forEach { style ->
                            FilterChip(
                                selected = state.translationStyle == style,
                                onClick = { onTranslationStyleChange(style) },
                                label = { Text(style.displayName) },
                                modifier = Modifier.testTag("home_style_${style.code}")
                            )
                        }
                    }

                    Column {
                        Text(
                            text = String.format(Locale.US, "Speech Speed: %.2fx", state.ttsSpeechRate),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Slider(
                            value = state.ttsSpeechRate,
                            onValueChange = onSpeechRateChange,
                            valueRange = 0.6f..1.6f,
                            modifier = Modifier.testTag("home_speech_speed_slider")
                        )
                    }
                }
            }

            // 6. Compact Diagnostic Status Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenDiagnosis)
                    .testTag("home_compact_diagnostics_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Open Diagnostics",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val readinessBadge = when {
                                state.sherpaModelLoaded -> "Neural Offline Ready"
                                state.chatterboxServerReachable && state.chatterboxModelLoaded -> "Chatterbox Neural Ready"
                                state.voiceBackendType.contains("SYSTEM", ignoreCase = true) -> "System Fallback (1 Voice)"
                                else -> "Checking Engine"
                            }
                            Text(
                                text = "$readinessBadge • ${state.voiceBackendType.ifBlank { state.activeTtsEngineLabel }}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = state.lastTtsError.ifBlank { state.captureStatusMessage.ifBlank { state.voiceDownloadMessage } },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open Live Diagnosis"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
