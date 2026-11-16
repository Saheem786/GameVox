package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.TranslatorAppState
import com.example.data.TranslatorStateRepository
import com.example.service.FreeVoiceBackend

/**
 * A dedicated Compose-based UI card component for the main screen allowing instant 1-tap
 * switching between 'Human Voice (Neural)', 'Fast Offline (Supertonic)', and 'Pause' modes.
 */
@Composable
fun DubbingModeSwitchCard(
    state: TranslatorAppState,
    onOpenAbLab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentBackend = state.freeVoiceBackend

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("dubbing_mode_switch_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Dubbing Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clickable { onOpenAbLab() }
                ) {
                    Text(
                        text = "A/B Lab & Server Setup →",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Mode 1: Human Voice (Neural)
                DubbingModeChip(
                    title = "Human Voice",
                    subtitle = "Local Neural",
                    icon = Icons.Default.CloudSync,
                    isSelected = currentBackend == FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER,
                    onClick = {
                        TranslatorStateRepository.updateFreeVoiceBackend(
                            context,
                            FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER
                        )
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "mode_chip_neural"
                )

                // Mode 2: Fast Offline (Supertonic)
                DubbingModeChip(
                    title = "Fast Offline",
                    subtitle = "Supertonic 3",
                    icon = Icons.Default.FlashOn,
                    isSelected = currentBackend == FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT,
                    onClick = {
                        TranslatorStateRepository.updateFreeVoiceBackend(
                            context,
                            FreeVoiceBackend.SHERPA_SUPERTONIC_DIRECT
                        )
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "mode_chip_supertonic"
                )

                // Mode 3: Pause
                DubbingModeChip(
                    title = "Pause",
                    subtitle = "OCR Only",
                    icon = Icons.Default.PauseCircle,
                    isSelected = currentBackend == FreeVoiceBackend.PAUSE_DUBBING,
                    onClick = {
                        TranslatorStateRepository.updateFreeVoiceBackend(
                            context,
                            FreeVoiceBackend.PAUSE_DUBBING
                        )
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "mode_chip_pause"
                )
            }

            // Explicit First-Run Engine Readiness & Honest Active Backend Disclosure (Audit Item 9)
            val chatterboxReady = state.chatterboxServerReachable && state.chatterboxModelLoaded
            val supertonicReady = state.sherpaModelLoaded || com.example.service.SupertonicModelManager.isModelReady(context)
            val systemTtsStatus = if (state.supportsDistinctGenderVoices) {
                "READY (Distinct M/F Voices)"
            } else {
                "SINGLE VOICE (Pitch/Rate Only)"
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("first_run_engine_readiness_card")
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Engine Readiness • 100% Free (Zero Paid APIs)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "• PC Chatterbox Server: " + if (chatterboxReady) {
                            "CONNECTED (${state.chatterboxDevice.uppercase()} • ${state.chatterboxUniqueVoicesSha256Count} unique ref WAVs)"
                        } else {
                            "OFFLINE / UNTESTED (${state.chatterboxServerUrl})"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chatterboxReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Supertonic 3 Offline Model: " + if (supertonicReady) {
                            "INSTALLED (10 Neural Speakers)"
                        } else {
                            "NOT INSTALLED (Download in Voice Manager)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (supertonicReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Android System TTS (${state.activeTtsEngineLabel}): $systemTtsStatus",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (currentBackend == FreeVoiceBackend.LOCAL_CHATTERBOX_SERVER && !chatterboxReady) {
                        val actualActiveFallback = if (supertonicReady) {
                            "SHERPA_DIRECT (Supertonic 3 Offline)"
                        } else if (state.supportsDistinctGenderVoices) {
                            "SYSTEM_FALLBACK (${state.activeTtsEngineLabel})"
                        } else {
                            "SYSTEM_FALLBACK_SINGLE_VOICE (${state.activeTtsEngineLabel})"
                        }
                        Text(
                            text = "Honest Backend Notice: PC Chatterbox server is not connected right now. Actual audio uses $actualActiveFallback until the PC server is reachable.",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DubbingModeChip(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = ""
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
