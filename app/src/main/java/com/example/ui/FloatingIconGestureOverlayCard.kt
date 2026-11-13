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
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
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
 * Interactive Jetpack Compose Floating Icon, Green Scan Box & Translated Captions Controller
 * using `Modifier.pointerInput` and `detectTapGestures`:
 *
 * - **Single Tap (`onTap`)**: Strictly toggles ONLY [isScanBoxVisible] (Hides/Shows the Green Scan Box).
 *   Does NOT affect the Translated Captions Box!
 * - **Double Tap (`onDoubleTap`)**: Strictly toggles ONLY [isSubtitleVisible] (Hides/Shows the dark
 *   Translated Captions Box on top). Does NOT affect the Green Scan Box!
 * - **Long Press (`onLongPress`)**: Toggles Voice ON/OFF ([isVoiceMuted], default ON).
 */
@Composable
fun FloatingIconGestureOverlayCard(
    state: TranslatorAppState,
    onSingleTapScanBoxVisibilityChanged: (isScanBoxVisible: Boolean) -> Unit,
    onDoubleTapSubtitleVisibilityChanged: (isSubtitleVisible: Boolean) -> Unit,
    onLongPressVoiceMuteChanged: (isVoiceMuted: Boolean) -> Unit,
    onTestCasualHindiTranslation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Separate boolean states for Single Tap (Green Scan Box), Double Tap (Translated Captions), and Long Press (Voice Mute)
    var isScanBoxVisible by remember { mutableStateOf(state.showTargetBoxOverlay) }
    var isSubtitleVisible by remember { mutableStateOf(state.isSubtitleVisible) }
    var isVoiceMuted by remember { mutableStateOf(state.isVoiceMuted) }

    LaunchedEffect(state.showTargetBoxOverlay) {
        isScanBoxVisible = state.showTargetBoxOverlay
    }
    LaunchedEffect(state.isSubtitleVisible) {
        isSubtitleVisible = state.isSubtitleVisible
    }
    LaunchedEffect(state.isVoiceMuted) {
        isVoiceMuted = state.isVoiceMuted
    }

    Surface(
        color = SlateSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = if (isScanBoxVisible) NeonEmerald else SaffronGold.copy(alpha = 0.6f),
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
                        text = "Floating Icon Tap Controls",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "• Single Tap: Green Scan Box Hide/Show\n• Double Tap: Translated Captions Hide/Show\n• Long Press: Voice ON/OFF (Default ON)",
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
                            if (isScanBoxVisible) SlateCardElevated else ObsidianBg
                        )
                        .border(
                            width = 2.dp,
                            color = when {
                                isVoiceMuted -> CrimsonAlert
                                isScanBoxVisible -> NeonEmerald
                                else -> SaffronGold
                            },
                            shape = CircleShape
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    // Single Tap: Strictly toggle ONLY Green Scan Box (isScanBoxVisible).
                                    // Does NOT affect Translated Captions (isSubtitleVisible)!
                                    val newScanBoxVisible = !isScanBoxVisible
                                    isScanBoxVisible = newScanBoxVisible
                                    onSingleTapScanBoxVisibilityChanged(newScanBoxVisible)
                                },
                                onDoubleTap = {
                                    // Double Tap: Strictly toggle ONLY Translated Captions Box (isSubtitleVisible).
                                    // Does NOT affect Green Scan Box (isScanBoxVisible)!
                                    val newSubtitleVisible = !isSubtitleVisible
                                    isSubtitleVisible = newSubtitleVisible
                                    onDoubleTapSubtitleVisibilityChanged(newSubtitleVisible)
                                },
                                onLongPress = {
                                    // Long Press: Toggle Voice ON/OFF (isVoiceMuted)
                                    val newMuted = !isVoiceMuted
                                    isVoiceMuted = newMuted
                                    onLongPressVoiceMuteChanged(newMuted)
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
                                    isScanBoxVisible -> NeonEmerald
                                    else -> SaffronGold
                                }
                            )
                            .border(1.5.dp, ObsidianBg, CircleShape)
                    )
                }
            }

            // Live Independent State Badges (Single Tap: Green Scan Box | Double Tap: Translated Captions | Long Press: Voice)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = if (isScanBoxVisible) NeonEmerald.copy(alpha = 0.18f) else SaffronGold.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scan_box_visibility_status_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CropFree,
                            contentDescription = null,
                            tint = if (isScanBoxVisible) NeonEmerald else SaffronGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = if (isScanBoxVisible) "Scan Box: SHOWN" else "Scan Box: HIDDEN",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isScanBoxVisible) NeonEmerald else SaffronGold,
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSubtitleVisible) Icons.Default.Subtitles else Icons.Default.SubtitlesOff,
                            contentDescription = null,
                            tint = if (isSubtitleVisible) ElectricCyan else SaffronGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = if (isSubtitleVisible) "Captions: SHOWN" else "Captions: HIDDEN",
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

            // 1. Translated Captions Dark Box (Controlled strictly by Double Tap -> isSubtitleVisible)
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
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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

            // 2. Green Scan Box Preview (Controlled strictly by Single Tap -> isScanBoxVisible)
            AnimatedVisibility(
                visible = isScanBoxVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    color = NeonEmerald.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, NeonEmerald, RoundedCornerShape(12.dp))
                        .testTag("compose_green_scan_box")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CropFree,
                                contentDescription = null,
                                tint = NeonEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Green Scan Box Active",
                                style = MaterialTheme.typography.labelLarge,
                                color = NeonEmerald,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Badge(
                            containerColor = if (isVoiceMuted) CrimsonAlert.copy(alpha = 0.2f) else NeonEmerald.copy(alpha = 0.2f),
                            contentColor = if (isVoiceMuted) CrimsonAlert else NeonEmerald
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = if (isVoiceMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = if (isVoiceMuted) "Voice OFF" else "Voice ON")
                            }
                        }
                    }
                }
            }

            // Quick Character Name & Gender Voice Switch Test Row (Jenny = 18yo Girl Voice, Anon = 18yo Boy Voice)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateSurfaceVariant)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Smart Name Strip & 18yo Gender Voice Switch (Name Not Spoken)",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonEmerald,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onTestCasualHindiTranslation("Jenny\nWait for me! Where are you going so fast?")
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_jenny_female_voice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Test 'Jenny' (Girl 18y)",
                            color = ElectricCyan,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            onTestCasualHindiTranslation("Anon\nI was at my father's funeral. Let's head back home.")
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_anon_male_voice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = NeonEmerald,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Test 'Anon' (Boy 18y)",
                            color = NeonEmerald,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}
