package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.service.SupertonicModelManager
import com.example.service.SupertonicModelState
import java.util.Locale

const val VOICE_PREVIEW_HINDI_SAMPLE = "नमस्ते गेमर! यह गेमवॉक्स का न्यूरल हिन्दी वॉइस प्रीव्यू है।"

data class SupertonicVoiceSpec(
    val sid: Int,
    val code: String,
    val gender: String,
    val title: String,
    val description: String,
    val defaultCharacters: String
)

val SUPERTONIC_VOICE_SPECS = listOf(
    SupertonicVoiceSpec(0, "F1", "Female", "F1 • Gentle / Princess (Priya)", "Soft, gentle, clear female timbre with melodic intonation", "Priya, Zelda"),
    SupertonicVoiceSpec(1, "F2", "Female", "F2 • Bright / Companion (Jenny)", "Youthful, bright, warm female tone suited for companion dialogue", "Jenny, Ananya"),
    SupertonicVoiceSpec(2, "F3", "Female", "F3 • Composed / Direct (Ira AI)", "Crisp, articulated, composed female cadence for system AI & merchants", "Ira-AI, Elena"),
    SupertonicVoiceSpec(3, "F4", "Female", "F4 • Tactical / Commander (Sarah)", "Resolute, tactical female tone with confident diction", "Sarah, Maya, Kavya"),
    SupertonicVoiceSpec(4, "F5", "Female", "F5 • Ethereal / Arcane (Oracle)", "Mystic, resonant, serene female timbre with calm pacing", "Oracle, Pooja"),
    SupertonicVoiceSpec(5, "M1", "Male", "M1 • Young / Lead (Anon)", "Natural young adult male lead voice with heroic cadence", "Anon, Aarav, Leon"),
    SupertonicVoiceSpec(6, "M2", "Male", "M2 • Energetic / Scout (Alex)", "Agile, energetic, sharp male tone for scouts and marksmen", "Alex, Rohan"),
    SupertonicVoiceSpec(7, "M3", "Male", "M3 • Deep / Villain (Kaal)", "Deep, resonant, low-register male voice suited for villains and bosses", "Kaal, Ragnar, Shadow"),
    SupertonicVoiceSpec(8, "M4", "Male", "M4 • Mature / Veteran (Vikram)", "Gravelly, mature, seasoned battle veteran male timbre", "Vikram, Kabir"),
    SupertonicVoiceSpec(9, "M5", "Male", "M5 • Commanding / Armored (Guard)", "Heavy, commanding, mechanical/armored male sentinel presence", "Guard, Dante, Announcer")
)

@Composable
fun SupertonicModelReadinessCard(
    modelState: SupertonicModelState,
    integrityReport: SupertonicModelManager.ModelIntegrityReport,
    actualBackend: String,
    onDownloadAndVerify: () -> Unit,
    onVerifyAndInit: () -> Unit,
    onTestDualCharacters: (() -> Unit)? = null,
    onUninstallModel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isReady = modelState is SupertonicModelState.Ready && modelState.nativeInferenceVerified
    val isSingleVoiceFallback = actualBackend == "SYSTEM_FALLBACK_SINGLE_VOICE" ||
        (!isReady && modelState !is SupertonicModelState.Downloading &&
            modelState !is SupertonicModelState.VerifyingSha256 &&
            modelState !is SupertonicModelState.InitializingNative)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isReady) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            } else if (modelState is SupertonicModelState.Error) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("supertonic_model_readiness_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = if (isReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isReady) {
                            "Supertonic 3 Hindi Neural Engine READY"
                        } else {
                            "Supertonic 3 Hindi Neural Bundle (~145 MB)"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when (modelState) {
                            is SupertonicModelState.Ready -> modelState.readinessSummary.ifBlank {
                                "7/7 files verified & native multi-speaker inference active"
                            }
                            is SupertonicModelState.Downloading ->
                                "Downloading ${modelState.currentFileName} (${modelState.currentFileIndex}/${modelState.totalFilesCount}) • ${(modelState.progressFraction * 100).toInt()}%"
                            is SupertonicModelState.VerifyingSha256 ->
                                "Verifying SHA-256: ${modelState.currentFileName} (${modelState.verifiedFilesCount}/${modelState.totalFilesCount})"
                            is SupertonicModelState.InitializingNative ->
                                modelState.stepDescription
                            is SupertonicModelState.Error -> modelState.errorMessage
                            else ->
                                "Files verified: ${integrityReport.verifiedFilesCount}/${integrityReport.totalFilesCount} • Download once for distinct Male & Female neural Hindi voices."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isSingleVoiceFallback && actualBackend == "SYSTEM_FALLBACK_SINGLE_VOICE") {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("system_single_voice_warning")
                ) {
                    Text(
                        text = "Warning: SYSTEM_FALLBACK_SINGLE_VOICE active. Download & verify Supertonic 3 below so Male (SID #5) and Female (SID #1) sound completely distinct.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            when (modelState) {
                is SupertonicModelState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { modelState.progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is SupertonicModelState.VerifyingSha256,
                is SupertonicModelState.InitializingNative,
                is SupertonicModelState.Extracting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isReady && modelState !is SupertonicModelState.Downloading) {
                    Button(
                        onClick = if (integrityReport.allValid) onVerifyAndInit else onDownloadAndVerify,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("supertonic_download_verify_button")
                    ) {
                        Icon(
                            imageVector = if (integrityReport.allValid) Icons.Default.VerifiedUser else Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (integrityReport.allValid) "Verify & Init Native" else "Download & Verify (145 MB)",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (isReady && onTestDualCharacters != null) {
                    OutlinedButton(
                        onClick = onTestDualCharacters,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("supertonic_test_dual_voices_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test Anon & Jenny")
                    }
                }
                if (integrityReport.verifiedFilesCount > 0 && onUninstallModel != null) {
                    OutlinedButton(
                        onClick = onUninstallModel,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("supertonic_uninstall_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Remove Supertonic bundle",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
