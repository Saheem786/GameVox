package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.TranslatorAppState
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.SaffronGold
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurfaceVariant

data class GameScenePreset(
    val title: String,
    val speaker: String,
    val subtitleEnglish: String
)

val GAME_SCENE_PRESETS = listOf(
    GameScenePreset(
        title = "Cyberpunk Sector 7",
        speaker = "Cybernetic Guard",
        subtitleEnglish = "Alert! Target secured in Sector 7. Engage protocols."
    ),
    GameScenePreset(
        title = "Boss Lair",
        speaker = "Ancient Dragon",
        subtitleEnglish = "You dare challenge the keeper of the eternal flame?"
    ),
    GameScenePreset(
        title = "Tactical Ops",
        speaker = "Squad Leader",
        subtitleEnglish = "Hold your position! Reinforcements arriving in thirty seconds."
    ),
    GameScenePreset(
        title = "Mythic Quest",
        speaker = "Mystic Oracle",
        subtitleEnglish = "Seek the hidden temple beyond the crimson mountains before dawn."
    ),
    GameScenePreset(
        title = "Sci-Fi Vault",
        speaker = "System Terminal",
        subtitleEnglish = "Security override complete. Main vault door unlocked."
    )
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameSimulatorSection(
    state: TranslatorAppState,
    onSimulateFrameScan: (speaker: String, subtitle: String) -> Unit
) {
    var selectedPresetIndex by remember { mutableIntStateOf(0) }
    var customSubtitleText by remember {
        mutableStateOf(GAME_SCENE_PRESETS[0].subtitleEnglish)
    }
    var customSpeakerName by remember {
        mutableStateOf(GAME_SCENE_PRESETS[0].speaker)
    }

    Surface(
        color = SlateCardElevated,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ElectricCyan.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = "Game Subtitle Simulator",
                    tint = ElectricCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Live Game Subtitle & ColorMatrix Test Bench",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Renders white game subtitles on a dark background, applies ColorMatrix inversion, runs ML Kit OCR + Hindi Translator + Anti-Repeat + Hindi TTS.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Preset selector chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GAME_SCENE_PRESETS.forEachIndexed { index, preset ->
                    FilterChip(
                        selected = selectedPresetIndex == index,
                        onClick = {
                            selectedPresetIndex = index
                            customSpeakerName = preset.speaker
                            customSubtitleText = preset.subtitleEnglish
                        },
                        label = { Text(preset.title) },
                        modifier = Modifier.testTag("scene_preset_chip_$index")
                    )
                }
            }

            OutlinedTextField(
                value = customSubtitleText,
                onValueChange = { customSubtitleText = it },
                label = { Text("English Game Dialogue (White on Dark Scene)") },
                singleLine = false,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("simulator_subtitle_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        onSimulateFrameScan(customSpeakerName, customSubtitleText)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("simulate_ocr_scan_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Run Inversion + OCR + Hindi TTS",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan & Speak Hindi", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        // Re-scans the exact same frame to verify Anti-Repeat (lastSpokenText) skips duplicate speech!
                        onSimulateFrameScan(customSpeakerName, customSubtitleText)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("test_anti_repeat_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RepeatOne,
                        contentDescription = "Test Anti-Repeat Skip",
                        tint = SaffronGold,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Anti-Repeat", color = SaffronGold)
                }
            }

            // Live Before & After ColorMatrix Bitmap Inversion Preview
            if (state.originalFramePreview != null && state.invertedFramePreview != null) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SlateSurfaceVariant)
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Compare,
                            contentDescription = "ColorMatrix Inversion Comparison",
                            tint = NeonEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ColorMatrix Inversion Preprocessor (Frame #${state.scanFrameCount})",
                            style = MaterialTheme.typography.titleMedium,
                            color = NeonEmerald
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "1. Raw Captured Frame",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Image(
                                bitmap = state.originalFramePreview.asImageBitmap(),
                                contentDescription = "Original Game Frame with White Subtitles",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "2. Inverted for ML Kit OCR",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Image(
                                bitmap = state.invertedFramePreview.asImageBitmap(),
                                contentDescription = "ColorMatrix Inverted Frame with Black Text",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, ElectricCyan, RoundedCornerShape(8.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}
