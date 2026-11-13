package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.TranslatorAppState
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SaffronGold
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceVariant

/**
 * Interactive Jetpack Compose Floating Icon & Subtitle Box Controller using
 * `Modifier.pointerInput` and `detectTapGestures`:
 *
 * - **Single Tap (`onTap`)**: Toggles ONLY [isVoiceMuted] (Mutes/Unmutes TTS Voice).
 *   Translation & the Subtitle Box stay visible and active.
 * - **Double Tap (`onDoubleTap`)**: Toggles ONLY [isSubtitleVisible] (Shows/Hides the Compose
 *   Subtitle/Translation Box and pauses/resumes new text scanning). Does NOT affect Voice ([isVoiceMuted]).
 */
@Composable
fun FloatingIconGestureOverlayCard(
    state: TranslatorAppState,
    onSingleTapVoiceMuteChanged: (isVoiceMuted: Boolean) -> Unit,
    onDoubleTapSubtitleVisibilityChanged: (isSubtitleVisible: Boolean) -> Unit,
    onLongPressOpenSettings: () -> Unit,
    onTestCasualHindiTranslation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Separate boolean variables for Single Tap (Voice Mute) and Double Tap (Subtitle Box Visibility)
    var isVoiceMuted by remember { mutableStateOf(state.isVoiceMuted) }
    var isSubtitleVisible by remember { mutableStateOf(state.isSubtitleVisible) }

    LaunchedEffect(state.isVoiceMuted) {
        isVoiceMuted = state.isVoiceMuted
    }
    LaunchedEffect(state.isSubtitleVisible) {
        isSubtitleVisible = state.isSubtitleVisible
    }

    Surface(
        color = SlateSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = if (isSubtitleVisible) ElectricCyan else SaffronGold.copy(alpha = 0.6f),
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("floating_icon_gesture_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row + Interactive Floating Icon using Modifier.pointerInput & detectTapGestures
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Floating Icon Tap Controls & Subtitle Box",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "• Single Tap: Voice Mute/Unmute Only\n• Double Tap: Show/Hide Subtitle Box & Scan",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricCyan
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Interactive Floating Icon powered by Modifier.pointerInput + detectTapGestures
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSubtitleVisible) SlateCardElevated else ObsidianBg
                        )
                        .border(
                            width = 2.dp,
                            color = when {
                                isVoiceMuted -> CrimsonAlert
                                isSubtitleVisible -> NeonEmerald
                                else -> SaffronGold
                            },
                            shape = CircleShape
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    // Single Tap: ONLY toggle Voice (TTS Mute/Unmute).
                                    // Do NOT hide Subtitle Box or stop translation!
                                    val newMuted = !isVoiceMuted
                                    isVoiceMuted = newMuted
                                    onSingleTapVoiceMuteChanged(newMuted)
                                },
                                onDoubleTap = {
                                    // Double Tap: ONLY toggle Subtitle Box visibility & scanning.
                                    // Do NOT change Voice Mute state!
                                    val newVisible = !isSubtitleVisible
                                    isSubtitleVisible = newVisible
                                    onDoubleTapSubtitleVisibilityChanged(newVisible)
                                },
                                onLongPress = {
                                    onLongPressOpenSettings()
                                }
                            )
                        }
                        .testTag("compose_floating_icon_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isVoiceMuted) "🔇" else state.targetLanguage.glyphBadge,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isVoiceMuted -> CrimsonAlert
                                    isSubtitleVisible -> NeonEmerald
                                    else -> SaffronGold
                                }
                            )
                            .border(1.5.dp, ObsidianBg, CircleShape)
                    )
                }
            }

            // Live Independent State Badges (isVoiceMuted vs isSubtitleVisible)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = if (isVoiceMuted) CrimsonAlert.copy(alpha = 0.18f) else NeonEmerald.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("voice_mute_status_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isVoiceMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = if (isVoiceMuted) CrimsonAlert else NeonEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = if (isVoiceMuted) "Voice: MUTED (OFF)" else "Voice: ACTIVE (ON)",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isVoiceMuted) CrimsonAlert else NeonEmerald,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Single-Tap Icon",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Surface(
                    color = if (isSubtitleVisible) ElectricCyan.copy(alpha = 0.18f) else SaffronGold.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("subtitle_visibility_status_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSubtitleVisible) Icons.Default.Subtitles else Icons.Default.SubtitlesOff,
                            contentDescription = null,
                            tint = if (isSubtitleVisible) ElectricCyan else SaffronGold,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = if (isSubtitleVisible) "Subtitle Box: SHOWN" else "Subtitle Box: HIDDEN",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSubtitleVisible) ElectricCyan else SaffronGold,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Double-Tap Icon",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Compose Subtitle / Translation Box (Controlled strictly by isSubtitleVisible via Double Tap)
            AnimatedVisibility(
                visible = isSubtitleVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, ElectricCyan, RoundedCornerShape(14.dp))
                        .testTag("compose_hindi_subtitle_box")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Badge(
                            containerColor = ElectricCyan.copy(alpha = 0.18f),
                            contentColor = ElectricCyan
                        ) {
                            Text(
                                text = "MODERN CASUAL HINDI SUBTITLE BOX",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = state.lastTranslatedHindi,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("compose_subtitle_box_hindi_text")
                        )
                    }
                }
            }

            // Quick Test Row for Modern/Casual Hindi (e.g. "My father's funeral" -> "मेरे पापा का फ्यूनरल")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateSurfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Casual Hinglish / Bolchal Mode Active",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonEmerald,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "e.g. 'My father's funeral' → 'मेरे पापा का फ्यूनरल'",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        onTestCasualHindiTranslation("Character A: I was at my father's funeral.")
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("test_casual_hindi_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Test Casual",
                        color = ElectricCyan,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
