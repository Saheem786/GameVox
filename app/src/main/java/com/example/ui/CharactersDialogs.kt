package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.data.CharacterVoiceAssignment
import com.example.data.SpeakerGender
import com.example.data.VoiceProfile
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddOrEditCharacterDialog(
    initialCharacter: CharacterVoiceAssignment?,
    availableVoices: List<VoiceProfile>,
    activeGameNamespace: String,
    onDismiss: () -> Unit,
    onSave: (
        displayName: String,
        speakerId: Int,
        presentation: String,
        voiceId: String?,
        speed: Float,
        pitch: Float,
        voiceEngine: String
    ) -> Unit
) {
    val isEditing = initialCharacter != null
    var nameInput by remember(initialCharacter) {
        mutableStateOf(initialCharacter?.displayName ?: "")
    }
    var presentation by remember(initialCharacter) {
        mutableStateOf(initialCharacter?.voicePresentation ?: "female")
    }
    var selectedSid by remember(initialCharacter) {
        mutableIntStateOf(initialCharacter?.speakerId ?: if (presentation == "female") 1 else 5)
    }
    var selectedVoiceId by remember(initialCharacter) {
        mutableStateOf(initialCharacter?.assignedVoiceId?.takeIf { it.isNotBlank() })
    }
    var speed by remember(initialCharacter) {
        mutableFloatStateOf(initialCharacter?.speed ?: 1.0f)
    }
    var pitch by remember(initialCharacter) {
        mutableFloatStateOf(initialCharacter?.pitch ?: 1.0f)
    }
    var voiceEngine by remember(initialCharacter) {
        mutableStateOf(initialCharacter?.voiceEngine ?: "auto")
    }
    val visibleVoices = remember(availableVoices) {
        availableVoices.filter { !it.isHidden }.take(8)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .widthIn(max = 520.dp),
        title = {
            Text(
                text = if (isEditing) "Edit ${initialCharacter?.displayName}" else "Add Character ($activeGameNamespace)",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "name_input") {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Character Name (as shown in subtitles)") },
                        placeholder = { Text("e.g. Alice, Kaal, Elena") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("character_dialog_name_input")
                    )
                }

                item(key = "presentation") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Character Presentation", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("female" to "Female", "male" to "Male", "neutral" to "Neutral").forEach { (code, label) ->
                                FilterChip(
                                    selected = presentation == code,
                                    onClick = {
                                        presentation = code
                                        if (selectedVoiceId == null) {
                                            selectedSid = when (code) {
                                                "female" -> 1
                                                "male" -> 5
                                                else -> 2
                                            }
                                        }
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("character_dialog_gender_$code")
                                )
                            }
                        }
                    }
                }

                item(key = "voice_assign") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Assign Voice Library Profile", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = selectedVoiceId == null,
                                onClick = { selectedVoiceId = null },
                                label = { Text("Custom SID #$selectedSid") }
                            )
                            visibleVoices.forEach { voice ->
                                FilterChip(
                                    selected = selectedVoiceId == voice.id,
                                    onClick = {
                                        selectedVoiceId = voice.id
                                        selectedSid = voice.engineVoiceIndex.coerceIn(0, 9)
                                        presentation = if (voice.gender == SpeakerGender.FEMALE_18) "female" else "male"
                                        speed = voice.speechRate
                                        pitch = voice.pitch
                                    },
                                    label = { Text(voice.name.substringBefore(" (")) },
                                    modifier = Modifier.testTag("character_dialog_voice_${voice.id}")
                                )
                            }
                        }
                    }
                }

                item(key = "sid_slots") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Supertonic Speaker Slot (SID #$selectedSid)", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            (0..9).forEach { sid ->
                                FilterChip(
                                    selected = selectedSid == sid,
                                    onClick = {
                                        selectedSid = sid
                                        selectedVoiceId = null
                                    },
                                    label = { Text(if (sid in 0..4) "F$sid" else "M$sid") },
                                    modifier = Modifier.testTag("character_dialog_sid_$sid")
                                )
                            }
                        }
                    }
                }

                item(key = "sliders") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = String.format(Locale.US, "Speed: %.2fx   Pitch: %.2fx", speed, pitch),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = speed,
                            onValueChange = { speed = it },
                            valueRange = 0.7f..1.4f
                        )
                        Slider(
                            value = pitch,
                            onValueChange = { pitch = it },
                            valueRange = 0.7f..1.4f
                        )
                    }
                }

                item(key = "engine_override") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Engine Override", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "auto" to "Follow Settings",
                                "sherpa_supertonic_3" to "Supertonic 3",
                                "chatterbox_local_server" to "Chatterbox"
                            ).forEach { (engCode, engLabel) ->
                                FilterChip(
                                    selected = voiceEngine == engCode,
                                    onClick = { voiceEngine = engCode },
                                    label = { Text(engLabel) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = nameInput.trim()
                    if (cleanName.isNotEmpty()) {
                        onSave(cleanName, selectedSid, presentation, selectedVoiceId, speed, pitch, voiceEngine)
                    }
                },
                enabled = nameInput.isNotBlank(),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("character_dialog_save_button")
            ) {
                Text(if (isEditing) "Save Changes" else "Save Character")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FixOcrCharacterNameDialog(
    knownCharacters: List<CharacterVoiceAssignment>,
    activeGameNamespace: String,
    initialTypo: String = "",
    onDismiss: () -> Unit,
    onSaveCorrection: (misreadOcrName: String, canonicalCharacterName: String) -> Unit
) {
    var misreadInput by remember(initialTypo) { mutableStateOf(initialTypo) }
    var targetCanonical by remember(knownCharacters) {
        mutableStateOf(
            knownCharacters.firstOrNull { !it.isUnknownOrNeutral }?.displayName ?: "Anon"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .widthIn(max = 520.dp),
        title = {
            Text("Fix OCR Character Name ($activeGameNamespace)", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "If subtitle OCR misreads a name (e.g. 'An0n', 'Jeny', 'Al1ce'), map it to the real character profile. Saved across app restarts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = misreadInput,
                    onValueChange = { misreadInput = it },
                    label = { Text("Misread OCR Name") },
                    placeholder = { Text("e.g. An0n or Jeny") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ocr_fix_typo_input")
                )
                Text("Route to Canonical Character:", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    knownCharacters.filter { !it.isUnknownOrNeutral }.take(10).forEach { charAssign ->
                        FilterChip(
                            selected = targetCanonical.equals(charAssign.displayName, ignoreCase = true) ||
                                targetCanonical.equals(charAssign.characterName, ignoreCase = true),
                            onClick = { targetCanonical = charAssign.displayName },
                            label = { Text(charAssign.displayName) },
                            modifier = Modifier.testTag("ocr_fix_target_${charAssign.characterName}")
                        )
                    }
                }
                OutlinedTextField(
                    value = targetCanonical,
                    onValueChange = { targetCanonical = it },
                    label = { Text("Or Type Canonical Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ocr_fix_canonical_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (misreadInput.isNotBlank() && targetCanonical.isNotBlank()) {
                        onSaveCorrection(misreadInput.trim(), targetCanonical.trim())
                    }
                },
                enabled = misreadInput.isNotBlank() && targetCanonical.isNotBlank(),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("ocr_fix_save_button")
            ) {
                Text("Save Mapping")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text("Cancel")
            }
        }
    )
}
