package com.example.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.TargetLanguage
import com.example.data.TranslatorAppState
import com.example.data.TtsEngineStatus
import com.example.service.CloudTtsProvider
import com.example.service.CloudTtsService
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SaffronGold
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsBottomSheet(
    state: TranslatorAppState,
    onDismiss: () -> Unit,
    onTargetLanguageChange: (TargetLanguage) -> Unit,
    onHighQualityVoiceChange: (Boolean) -> Unit,
    onSsmlEnabledChange: (Boolean) -> Unit,
    onCloudTtsProviderChange: (CloudTtsProvider) -> Unit,
    onShowTargetBoxChange: (Boolean) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onInvertColorsChange: (Boolean) -> Unit,
    onIntervalChange: (Long) -> Unit,
    onTestHindiTts: () -> Unit,
    onRefreshTtsStatus: () -> Unit,
    onInstallHindiVoiceData: () -> Unit,
    onResetAntiRepeat: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val dismissCleanly: () -> Unit = {
        scope.launch {
            try {
                sheetState.hide()
            } catch (_: Exception) {
            } finally {
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SlateSurface,
        modifier = Modifier.testTag("settings_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with immediate Close / Dismiss button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Voice and OCR Settings",
                        tint = ElectricCyan,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Translator, HD Voice & Scan Box",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Google Translate API • Neural Network TTS • Draggable Scan Box",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = dismissCleanly,
                    modifier = Modifier.testTag("close_settings_sheet_icon_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Settings Dialog",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // 1. Target Translation & TTS Language Selector Card
            Surface(
                color = SlateCardElevated,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = ElectricCyan.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Target Language Selection",
                            tint = ElectricCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Target Language: ${state.targetLanguage.displayName} (${state.targetLanguage.nativeName})",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Translates English game text via Google Translate API + Offline Fallback",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

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
                                        text = "${lang.nativeName} • ${lang.displayName}",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan,
                                    selectedLabelColor = ObsidianBg
                                ),
                                modifier = Modifier.testTag("target_lang_chip_${lang.code}")
                            )
                        }
                    }
                }
            }

            // 2. High-Quality Neural / Network TTS Voice Card
            val isTtsWorking = state.ttsStatus == TtsEngineStatus.READY ||
                state.ttsStatus == TtsEngineStatus.FALLBACK_VOICE_READY

            Surface(
                color = SlateCardElevated,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (isTtsWorking) NeonEmerald.copy(alpha = 0.5f) else SaffronGold.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isTtsWorking) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = "TTS Status",
                                tint = if (isTtsWorking) NeonEmerald else SaffronGold,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "${state.targetLanguage.displayName} TTS (${state.targetLanguage.ttsLocaleTag}): ${state.ttsStatus.label}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Active Voice: ${state.activeVoiceName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ElectricCyan
                                )
                                Text(
                                    text = state.voiceDownloadMessage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        AssistChip(
                            onClick = onRefreshTtsStatus,
                            label = { Text("Re-check") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Re-check TTS",
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("recheck_tts_chip")
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "High-Quality Network Voice (Non-Robotic)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Selects Google TTS QUALITY_HIGH / VERY_HIGH neural voice & enables KEY_FEATURE_NETWORK_SYNTHESIS",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = state.useHighQualityNetworkVoice,
                            onCheckedChange = onHighQualityVoiceChange,
                            modifier = Modifier.testTag("high_quality_voice_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onTestHindiTts,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sheet_test_tts_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = "Speak Sample Voice",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Test ${state.targetLanguage.displayName} Voice",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onInstallHindiVoiceData,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("install_hindi_tts_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Install Voice Data",
                                tint = SaffronGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Voice Data", color = SaffronGold)
                        }
                    }
                }
            }

            // 2b. Emotion SSML & Cloud AI Voice Engine Card (Google Cloud Neural2 / OpenAI TTS)
            Surface(
                color = SlateCardElevated,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = SaffronGold.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = "Emotion SSML and Cloud TTS",
                                tint = SaffronGold,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "SSML Emotion Markup (<speak><prosody>)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Auto-detects '!' (Excited/Angry), '...' (Sad), and Whisper cues for dynamic pitch, speed & pauses.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = state.ssmlEnabledOnDevice,
                            onCheckedChange = onSsmlEnabledChange,
                            modifier = Modifier.testTag("ssml_emotion_switch")
                        )
                    }

                    if (state.lastSsmlPreview.isNotBlank()) {
                        Surface(
                            color = ObsidianBg,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.lastSsmlPreview,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = ElectricCyan,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                    Text(
                        text = "Voice Synthesis Engine (On-Device SSML or Cloud AI)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cloud_tts_provider_group")
                    ) {
                        CloudTtsProvider.entries.forEach { provider ->
                            val isSelected = state.cloudTtsProvider == provider
                            FilterChip(
                                selected = isSelected,
                                onClick = { onCloudTtsProviderChange(provider) },
                                label = {
                                    Text(
                                        text = provider.displayLabel,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SaffronGold,
                                    selectedLabelColor = ObsidianBg
                                ),
                                modifier = Modifier.testTag("cloud_provider_chip_${provider.code}")
                            )
                        }
                    }

                    val googleKeyReady = CloudTtsService.hasGoogleCloudKey()
                    val openAiKeyReady = CloudTtsService.hasOpenAiKey()
                    val statusNote = when (state.cloudTtsProvider) {
                        CloudTtsProvider.ANDROID_SSML_NETWORK ->
                            "Active: Built-in Android SSML + High-Quality Network voices (hi-in-x-hif-network / hi-in-x-him-network)."
                        CloudTtsProvider.GOOGLE_CLOUD_TTS -> if (googleKeyReady) {
                            "Google Cloud TTS API Key Ready! Using Neural2 / WaveNet SSML voices with instant on-device fallback."
                        } else {
                            "GOOGLE_CLOUD_TTS_API_KEY not set in AI Studio Secrets panel — falling back seamlessly to Android SSML + Network."
                        }
                        CloudTtsProvider.OPENAI_TTS -> if (openAiKeyReady) {
                            "OpenAI TTS API Key Ready! Using gpt-4o-mini-tts expressive voices with instant on-device fallback."
                        } else {
                            "OPENAI_TTS_API_KEY not set in AI Studio Secrets panel — falling back seamlessly to Android SSML + Network."
                        }
                    }
                    Text(
                        text = statusNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (
                            (state.cloudTtsProvider == CloudTtsProvider.GOOGLE_CLOUD_TTS && !googleKeyReady) ||
                            (state.cloudTtsProvider == CloudTtsProvider.OPENAI_TTS && !openAiKeyReady)
                        ) {
                            SaffronGold
                        } else {
                            NeonEmerald
                        }
                    )
                }
            }

            // 3. Draggable & Resizable Target Scan Box Card
            Surface(
                color = SlateCardElevated,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = NeonEmerald.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
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
                            Icon(
                                imageVector = Icons.Default.CropFree,
                                contentDescription = "Draggable Target Scan Box",
                                tint = NeonEmerald,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Draggable & Resizable Scan Box",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Drag the transparent square box over any game subtitle area & resize via the bottom-right corner grip (◢). Only text inside this box is read.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = state.showTargetBoxOverlay,
                            onCheckedChange = onShowTargetBoxChange,
                            modifier = Modifier.testTag("show_target_box_switch")
                        )
                    }

                    val rect = state.targetBoxScreenRect
                    if (rect != null && rect.width() > 0 && rect.height() > 0) {
                        Text(
                            text = "Active Scan Region: [${rect.left}, ${rect.top}] → [${rect.right}, ${rect.bottom}] (${rect.width()}×${rect.height()} px)",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonEmerald
                        )
                    }
                }
            }

            // 4. TTS Pitch Slider (Default 0.95f)
            Surface(
                color = SlateCardElevated,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "TTS Pitch",
                                tint = ElectricCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = String.format(Locale.US, "Voice Pitch: %.2fx", state.ttsPitch),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        AssistChip(
                            onClick = { onPitchChange(1.0f) },
                            label = { Text("Natural 1.00f") },
                            modifier = Modifier.testTag("reset_pitch_chip")
                        )
                    }
                    Slider(
                        value = state.ttsPitch,
                        onValueChange = onPitchChange,
                        valueRange = 0.4f..1.8f,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricCyan,
                            activeTrackColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("tts_pitch_slider")
                    )
                }
            }

            // 5. TTS Speech Rate Slider (Default 1.00f)
            Surface(
                color = SlateCardElevated,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = "TTS Speech Rate",
                                tint = SaffronGold,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = String.format(Locale.US, "Speech Rate: %.2fx", state.ttsSpeechRate),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        AssistChip(
                            onClick = { onSpeechRateChange(1.0f) },
                            label = { Text("Natural 1.00f") },
                            modifier = Modifier.testTag("reset_rate_chip")
                        )
                    }
                    Slider(
                        value = state.ttsSpeechRate,
                        onValueChange = onSpeechRateChange,
                        valueRange = 0.4f..1.8f,
                        colors = SliderDefaults.colors(
                            thumbColor = SaffronGold,
                            activeTrackColor = SaffronGold
                        ),
                        modifier = Modifier.testTag("tts_rate_slider")
                    )
                }
            }

            // 6. ImageProcessor Grayscale + ColorMatrix Inversion & Capture Interval
            Surface(
                color = SlateCardElevated,
                shape = RoundedCornerShape(16.dp),
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
                                imageVector = Icons.Default.InvertColors,
                                contentDescription = "Invert Colors",
                                tint = ElectricCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "ImageProcessor Grayscale & Inversion",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Crops to the Draggable Scan Box, converts to grayscale, and inverts white-on-dark overlays before ML Kit OCR.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = state.invertColorsEnabled,
                            onCheckedChange = onInvertColorsChange,
                            modifier = Modifier.testTag("invert_colors_switch")
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                    Text(
                        text = "Screen Capture & OCR Interval",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            1500L to "1.5s Fast",
                            2000L to "2.0s Standard",
                            3000L to "3.0s Battery"
                        ).forEach { (ms, label) ->
                            FilterChip(
                                selected = state.captureIntervalMs == ms,
                                onClick = { onIntervalChange(ms) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("interval_chip_$ms")
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
                                text = "Anti-Repeat Memory (lastSpokenText)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (state.lastSpokenText.isBlank()) {
                                    "Empty — Next subtitle will be spoken"
                                } else {
                                    "Last: \"${state.lastSpokenText.take(40)}\""
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onResetAntiRepeat,
                            modifier = Modifier.testTag("sheet_reset_antirepeat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset Anti-Repeat",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear")
                        }
                    }
                }
            }

            // Explicit Close / Dismiss Button at bottom so the dialog always closes in 1 tap
            Button(
                onClick = dismissCleanly,
                colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("close_settings_sheet_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Close / Dismiss Settings", fontWeight = FontWeight.Bold)
            }
        }
    }
}
