package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.engine.SpeechEmotion
import com.example.service.ForegroundTtsManager
import com.example.service.TtsVisualizerState
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SaffronGold
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceVariant
import java.util.Locale
import kotlin.math.sin

/**
 * Real-time TTS Audio Visualizer Card that syncs directly with [ForegroundTtsManager.visualizerState]
 * to render live 24-band frequency bars, a harmonic oscilloscope waveform on a Jetpack Compose [Canvas],
 * and real-time word-boundary highlighting while text is being spoken.
 */
@Composable
fun TtsAudioVisualizerCard(
    ttsManager: ForegroundTtsManager,
    fallbackTextToSpeak: String,
    onSpeakSample: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visualizerState by ttsManager.visualizerState.collectAsStateWithLifecycle()

    Surface(
        color = SlateSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (visualizerState.isSpeaking) 1.5.dp else 1.dp,
                color = if (visualizerState.isSpeaking) NeonEmerald else ElectricCyan.copy(alpha = 0.42f),
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("tts_audio_visualizer_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Live Status Badge + Active Character Voice Telemetry
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                if (visualizerState.isSpeaking) NeonEmerald.copy(alpha = 0.22f)
                                else ElectricCyan.copy(alpha = 0.14f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = if (visualizerState.isSpeaking) NeonEmerald else ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Real-Time TTS Audio Visualizer",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${visualizerState.activeSpeakerRoleLabel} • ${visualizerState.activeVoiceName} (${
                                String.format(
                                    Locale.US,
                                    "%.2fx pitch • %.2fx rate • %d%% vol",
                                    visualizerState.activePitch,
                                    visualizerState.activeSpeechRate,
                                    (visualizerState.activeVolume * 100).toInt()
                                )
                            })",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    color = if (visualizerState.isSpeaking) NeonEmerald.copy(alpha = 0.20f) else SlateCardElevated,
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (visualizerState.isSpeaking) NeonEmerald else SaffronGold)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (visualizerState.isSpeaking) "SPEAKING" else "IDLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (visualizerState.isSpeaking) NeonEmerald else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Emotion Modulation Telemetry Pill + Live SSML Markup Badge
            val emotionTint = when (visualizerState.activeEmotion) {
                SpeechEmotion.EXCITED -> SaffronGold
                SpeechEmotion.HAPPY -> NeonEmerald
                SpeechEmotion.ANGRY -> CrimsonAlert
                SpeechEmotion.FEAR -> SaffronGold
                SpeechEmotion.SAD -> Color(0xFF7C9EFF)
                SpeechEmotion.WHISPER -> Color(0xFFCE93D8)
                SpeechEmotion.SERIOUS -> Color(0xFF90CAF9)
                SpeechEmotion.NEUTRAL -> NeonEmerald
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = emotionTint.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .border(1.dp, emotionTint.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .testTag("visualizer_emotion_badge")
                ) {
                    Text(
                        text = "Emotion: ${visualizerState.activeEmotion.displayLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = emotionTint,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                if (visualizerState.ssmlPreview.isNotBlank()) {
                    Surface(
                        color = ObsidianBg,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("visualizer_ssml_badge")
                    ) {
                        Text(
                            text = visualizerState.ssmlPreview,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = ElectricCyan.copy(alpha = 0.90f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Real-Time Jetpack Compose Canvas Spectrum & Waveform Visualizer
            TtsAudioVisualizerCanvas(
                visualizerState = visualizerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianBg)
                    .border(
                        width = 1.dp,
                        color = if (visualizerState.isSpeaking) {
                            NeonEmerald.copy(alpha = 0.55f)
                        } else {
                            SlateSurfaceVariant
                        },
                        shape = RoundedCornerShape(14.dp)
                    )
                    .testTag("tts_audio_visualizer_canvas")
            )

            // Word-Synchronized Utterance Readout & Progress Bar
            val activeText = visualizerState.spokenText.ifBlank { fallbackTextToSpeak }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateSurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val highlightedUtterance = buildAnnotatedString {
                    if (visualizerState.isSpeaking &&
                        activeText.isNotEmpty() &&
                        visualizerState.currentRangeEnd > visualizerState.currentRangeStart
                    ) {
                        val safeStart = visualizerState.currentRangeStart.coerceIn(0, activeText.length)
                        val safeEnd = visualizerState.currentRangeEnd.coerceIn(safeStart, activeText.length)

                        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.72f))) {
                            append(activeText.substring(0, safeStart))
                        }
                        withStyle(
                            SpanStyle(
                                color = NeonEmerald,
                                fontWeight = FontWeight.ExtraBold,
                                background = ObsidianBg.copy(alpha = 0.75f)
                            )
                        ) {
                            append(activeText.substring(safeStart, safeEnd))
                        }
                        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.90f))) {
                            append(activeText.substring(safeEnd))
                        }
                    } else {
                        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.85f))) {
                            append(activeText)
                        }
                    }
                }

                Text(
                    text = highlightedUtterance,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("tts_visualizer_utterance_text")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { visualizerState.progressFraction.coerceIn(0f, 1f) },
                        color = if (visualizerState.isSpeaking) NeonEmerald else ElectricCyan,
                        trackColor = ObsidianBg,
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(50))
                    )

                    if (visualizerState.isSpeaking) {
                        OutlinedButton(
                            onClick = { ttsManager.stopSpeaking() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("tts_visualizer_stop_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Speaking",
                                tint = CrimsonAlert,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Stop",
                                color = CrimsonAlert,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = onSpeakSample,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("tts_visualizer_speak_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Speak & Visualize",
                                tint = ObsidianBg,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Speak Live",
                                color = ObsidianBg,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pure Jetpack Compose [Canvas] audio visualizer that strictly uses `DrawScope` `size.width`
 * and `size.height` to draw:
 * 1. Background acoustic reference lines
 * 2. 24 mirrored frequency spectrum bars animated in real time
 * 3. A harmonic oscilloscope wave path reflecting vocal pitch and RMS amplitude
 * 4. A synchronized utterance progress playhead
 */
@Composable
fun TtsAudioVisualizerCanvas(
    visualizerState: TtsVisualizerState,
    modifier: Modifier = Modifier
) {
    val animatedRms by animateFloatAsState(
        targetValue = if (visualizerState.isSpeaking) visualizerState.rmsAmplitude else 0.05f,
        animationSpec = tween(durationMillis = 70, easing = FastOutSlowInEasing),
        label = "tts_rms_amplitude"
    )

    val bands = visualizerState.frequencyBands
    val animatedBands = bands.mapIndexed { index, targetVal ->
        val state by animateFloatAsState(
            targetValue = if (visualizerState.isSpeaking) targetVal else 0.08f,
            animationSpec = tween(durationMillis = 65, easing = FastOutSlowInEasing),
            label = "tts_band_$index"
        )
        state
    }

    val barBrushActive = Brush.verticalGradient(
        colors = listOf(
            NeonEmerald,
            ElectricCyan,
            SaffronGold.copy(alpha = 0.85f)
        )
    )
    val barBrushIdle = Brush.verticalGradient(
        colors = listOf(
            ElectricCyan.copy(alpha = 0.35f),
            SlateCardElevated
        )
    )

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val centerY = canvasHeight / 2f

        // 1. Subtle horizontal acoustic grid lines
        val gridLines = 3
        for (i in 1..gridLines) {
            val y = (canvasHeight / (gridLines + 1)) * i
            drawLine(
                color = SlateCardElevated.copy(alpha = 0.65f),
                start = Offset(0f, y),
                end = Offset(canvasWidth, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        // 2. Draw 24 Mirrored Frequency Spectrum Bars
        val bandCount = animatedBands.size.coerceAtLeast(1)
        val horizontalPadding = 12.dp.toPx()
        val usableWidth = (canvasWidth - horizontalPadding * 2f).coerceAtLeast(1f)
        val slotWidth = usableWidth / bandCount
        val barWidth = (slotWidth * 0.62f).coerceAtLeast(3.dp.toPx())
        val maxBarHeight = canvasHeight * 0.82f

        for (i in 0 until bandCount) {
            val amplitude = animatedBands[i].coerceIn(0.06f, 1f)
            val barHeight = (amplitude * maxBarHeight).coerceAtLeast(6.dp.toPx())
            val left = horizontalPadding + i * slotWidth + (slotWidth - barWidth) / 2f
            val top = centerY - barHeight / 2f

            drawRoundRect(
                brush = if (visualizerState.isSpeaking) barBrushActive else barBrushIdle,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }

        // 3. Draw Continuous Harmonic Oscilloscope Waveform Path across the Canvas
        val wavePath = Path()
        val segments = 64
        val stepX = canvasWidth / segments.toFloat()
        val pitchFactor = visualizerState.activePitch.coerceIn(0.5f, 1.7f)
        val waveMaxAmp = (canvasHeight * 0.36f) * animatedRms

        for (s in 0..segments) {
            val x = s * stepX
            val normalizedX = s.toFloat() / segments.toFloat()
            // Window envelope so the wave tapers smoothly at the left and right edges
            val edgeEnvelope = sin(normalizedX * Math.PI).toFloat()
            val yOffset = sin(
                normalizedX * Math.PI * 4.0 * pitchFactor + visualizerState.phaseRadians.toDouble()
            ).toFloat() * waveMaxAmp * edgeEnvelope

            val y = centerY + yOffset
            if (s == 0) {
                wavePath.moveTo(x, y)
            } else {
                wavePath.lineTo(x, y)
            }
        }

        drawPath(
            path = wavePath,
            color = if (visualizerState.isSpeaking) {
                SaffronGold.copy(alpha = 0.90f)
            } else {
                ElectricCyan.copy(alpha = 0.30f)
            },
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // 4. Draw Synchronized Utterance Progress Playhead Line while speaking
        if (visualizerState.isSpeaking && visualizerState.progressFraction > 0.01f) {
            val playheadX = (horizontalPadding + usableWidth * visualizerState.progressFraction)
                .coerceIn(horizontalPadding, canvasWidth - horizontalPadding)
            drawLine(
                color = NeonEmerald.copy(alpha = 0.75f),
                start = Offset(playheadX, 6.dp.toPx()),
                end = Offset(playheadX, canvasHeight - 6.dp.toPx()),
                strokeWidth = 1.5.dp.toPx()
            )
        }
    }
}
