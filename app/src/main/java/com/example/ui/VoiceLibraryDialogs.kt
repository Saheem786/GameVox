package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.CharacterVoiceAssignment
import com.example.data.VoiceProfile

val PRESET_CHARACTER_TARGETS = listOf("Anon", "Jenny", "Alice", "Kaal", "Ira")

@Composable
fun RenameVoiceDialog(
    voice: VoiceProfile,
    onDismiss: () -> Unit,
    onConfirmRename: (String) -> Unit
) {
    var nameInput by remember(voice.id) { mutableStateOf(voice.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Rename Voice",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Voice ID (${voice.id}) stays stable so character bindings stay intact.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Voice Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_voice_dialog_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = nameInput.trim()
                    if (clean.isNotEmpty()) {
                        onConfirmRename(clean)
                    }
                },
                enabled = nameInput.trim().isNotEmpty(),
                modifier = Modifier.testTag("rename_voice_confirm_button")
            ) {
                Text("Save Name")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("rename_voice_cancel_button")
            ) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssignVoiceToCharacterDialog(
    voice: VoiceProfile,
    knownCharacters: List<CharacterVoiceAssignment>,
    onDismiss: () -> Unit,
    onAssignToCharacter: (String) -> Unit
) {
    var customCharacterInput by remember { mutableStateOf("") }
    val suggestedNames = remember(knownCharacters) {
        (PRESET_CHARACTER_TARGETS + knownCharacters.map { it.displayName })
            .distinctBy { it.lowercase() }
            .take(10)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Assign Voice to Character",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Assign \"${voice.name}\" to play whenever this speaker talks in live gameplay:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    suggestedNames.forEach { charName ->
                        FilterChip(
                            selected = customCharacterInput.equals(charName, ignoreCase = true),
                            onClick = { customCharacterInput = charName },
                            label = { Text(charName) },
                            modifier = Modifier.testTag("assign_char_chip_$charName")
                        )
                    }
                }
                OutlinedTextField(
                    value = customCharacterInput,
                    onValueChange = { customCharacterInput = it },
                    label = { Text("Character Name (e.g. Alice)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("assign_custom_char_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = customCharacterInput.trim()
                    if (target.isNotEmpty()) {
                        onAssignToCharacter(target)
                    }
                },
                enabled = customCharacterInput.trim().isNotEmpty(),
                modifier = Modifier.testTag("assign_char_confirm_button")
            ) {
                Text("Assign")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DeleteVoiceConfirmationDialog(
    voice: VoiceProfile,
    affectedCharacters: List<CharacterVoiceAssignment>,
    replacementCandidates: List<VoiceProfile>,
    onDismiss: () -> Unit,
    onConfirmDelete: (replacementVoiceId: String?) -> Unit
) {
    var selectedReplacementId by remember(voice.id, replacementCandidates) {
        mutableStateOf(
            replacementCandidates.firstOrNull { it.gender == voice.gender }?.id
                ?: replacementCandidates.firstOrNull()?.id
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (voice.isSystemProtected) "Default Voice Protected" else "Delete Voice?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (voice.isSystemProtected) {
                    Text(
                        text = "\"${voice.name}\" is a built-in default Supertonic system voice (${voice.voiceCategory.displayLabel}) and is protected so male/female character dubbing always has a reliable baseline.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = "Are you sure you want to delete \"${voice.name}\"?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (affectedCharacters.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Assigned characters: ${affectedCharacters.joinToString { it.displayName }}.\nDeleting this voice will reassign them to the replacement voice selected below:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(replacementCandidates, key = { it.id }) { candidate ->
                                val isChosen = selectedReplacementId == candidate.id
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isChosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedReplacementId = candidate.id }
                                        .testTag("replacement_voice_${candidate.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isChosen,
                                            onClick = { selectedReplacementId = candidate.id }
                                        )
                                        Column {
                                            Text(
                                                text = candidate.name,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "${candidate.voiceCategory.displayLabel} • SID #${candidate.engineVoiceIndex}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!voice.isSystemProtected) {
                Button(
                    onClick = { onConfirmDelete(selectedReplacementId) },
                    enabled = affectedCharacters.isEmpty() || selectedReplacementId != null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_voice_button")
                ) {
                    Text(if (affectedCharacters.isNotEmpty()) "Reassign & Delete" else "Delete")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_delete_voice_button")
            ) {
                Text(if (voice.isSystemProtected) "Close" else "Cancel")
            }
        }
    )
}
