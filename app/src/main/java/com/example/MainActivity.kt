package com.example

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.data.SpeakerGender
import com.example.data.TargetLanguage
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceProfileRepository
import com.example.engine.OcrTranslationPipeline
import com.example.service.ForegroundTtsManager
import com.example.service.GameVoxAccessibilityService
import com.example.service.ScreenTranslatorService
import com.example.ui.SettingsScreen
import com.example.ui.VoiceManagerScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

private enum class AppScreen {
    HOME,
    SETTINGS,
    VOICE_MANAGER
}

class MainActivity : ComponentActivity() {

    private lateinit var localPipeline: OcrTranslationPipeline

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TranslatorStateRepository.loadPreferences(applicationContext)
        lifecycleScope.launch {
            TranslatorStateRepository.loadCloudTtsFromDataStore(applicationContext)
        }
        VoiceProfileRepository.initialize(applicationContext)
        localPipeline = OcrTranslationPipeline.getInstance(applicationContext)

        setContent {
            MyApplicationTheme {
                GameVoxApp(localPipeline = localPipeline)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::localPipeline.isInitialized) {
            localPipeline.refreshTtsConfiguration()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameVoxApp(
    localPipeline: OcrTranslationPipeline
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val state by TranslatorStateRepository.state.collectAsStateWithLifecycle()
    val voices by VoiceProfileRepository.voices.collectAsStateWithLifecycle()
    val selectedVoiceId by VoiceProfileRepository.selectedVoiceId.collectAsStateWithLifecycle()

    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
    var voiceManagerOrigin by remember { mutableStateOf(AppScreen.HOME) }

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var isAccessibilityEnabled by remember {
        mutableStateOf(checkAccessibilityServiceEnabled(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                isAccessibilityEnabled = checkAccessibilityServiceEnabled(context)
                TranslatorStateRepository.setAccessibilityConnected(isAccessibilityEnabled)
                localPipeline.refreshTtsConfiguration()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val overlaySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isAccessibilityEnabled = checkAccessibilityServiceEnabled(context)
        TranslatorStateRepository.setAccessibilityConnected(isAccessibilityEnabled)
    }

    val checkTtsDataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val isInstalled = localPipeline.ttsManager.handleCheckTtsDataResult(result.resultCode, result.data)
        if (!isInstalled) {
            localPipeline.promptInstallHindiTtsData(context, openSystemInstaller = true)
        }
    }

    DisposableEffect(localPipeline) {
        val voiceListener = object : ForegroundTtsManager.VoiceDataDownloadListener {
            override fun onHighQualityNetworkVoiceAvailable(
                language: TargetLanguage,
                voiceName: String,
                quality: Int
            ) {
            }

            override fun onVoiceDataMissing(
                language: TargetLanguage,
                missingVoiceNames: List<String>,
                reason: String
            ) {
                coroutineScope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "${language.displayName} HD voice data missing",
                        actionLabel = "Download",
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        try {
                            val checkIntent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA).apply {
                                setPackage(ForegroundTtsManager.GOOGLE_TTS_PACKAGE)
                            }
                            checkTtsDataLauncher.launch(checkIntent)
                        } catch (_: Exception) {
                            localPipeline.promptInstallHindiTtsData(context, openSystemInstaller = true)
                        }
                    }
                }
            }

            override fun onVoiceDataDownloadStarted(language: TargetLanguage) {}

            override fun onVoiceDataInstalled(
                language: TargetLanguage,
                installedVoiceName: String
            ) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("${language.displayName} voice ready")
                }
            }
        }
        localPipeline.ttsManager.addVoiceDataDownloadListener(voiceListener)
        onDispose {
            localPipeline.ttsManager.removeVoiceDataDownloadListener(voiceListener)
        }
    }

    val activeVoice = remember(voices, selectedVoiceId) {
        voices.find { it.id == selectedVoiceId } ?: voices.firstOrNull()
    }
    val activeVoiceNameClean = remember(activeVoice) {
        val raw = activeVoice?.name?.substringBefore(" •")?.trim()
        if (raw.isNullOrBlank()) "Jenny" else raw
    }
    val activeVoiceGenderLabel = remember(activeVoice) {
        if (activeVoice?.gender == SpeakerGender.MALE_18) "Male" else "Female"
    }
    val activeVoiceSummary = "$activeVoiceNameClean ($activeVoiceGenderLabel)"

    when (currentScreen) {
        AppScreen.VOICE_MANAGER -> {
            VoiceManagerScreen(
                onNavigateBack = { currentScreen = voiceManagerOrigin },
                onPlayVoicePreview = { voiceProfile ->
                    localPipeline.previewVoiceProfile(voiceProfile)
                },
                onSelectVoice = { voiceProfile ->
                    TranslatorStateRepository.updateTtsPitch(context, voiceProfile.pitch)
                    TranslatorStateRepository.updateTtsSpeechRate(context, voiceProfile.speechRate)
                    localPipeline.refreshTtsConfiguration()
                    localPipeline.previewVoiceProfile(voiceProfile)
                }
            )
            return
        }

        AppScreen.SETTINGS -> {
            SettingsScreen(
                state = state,
                activeVoiceSummary = activeVoiceSummary,
                isAccessibilityEnabled = isAccessibilityEnabled,
                onNavigateBack = { currentScreen = AppScreen.HOME },
                onOpenVoiceManager = {
                    voiceManagerOrigin = AppScreen.SETTINGS
                    currentScreen = AppScreen.VOICE_MANAGER
                },
                onTargetLanguageChange = { newLang ->
                    TranslatorStateRepository.updateTargetLanguage(context, newLang)
                    localPipeline.resetAntiRepeatMemory()
                    localPipeline.refreshTtsConfiguration()
                },
                onHighQualityVoiceChange = { enabled ->
                    TranslatorStateRepository.updateUseHighQualityNetworkVoice(context, enabled)
                    localPipeline.refreshTtsConfiguration()
                },
                onSsmlEnabledChange = { enabled ->
                    TranslatorStateRepository.updateSsmlEnabledOnDevice(context, enabled)
                },
                onCloudTtsProviderChange = { provider ->
                    TranslatorStateRepository.updateCloudTtsProvider(context, provider)
                    localPipeline.ttsManager.stopSpeaking()
                },
                onSaveCloudTtsApiConfig = { provider, apiKey ->
                    TranslatorStateRepository.saveCloudTtsConfig(context, provider, apiKey)
                    localPipeline.ttsManager.stopSpeaking()
                },
                onPitchChange = { newPitch ->
                    TranslatorStateRepository.updateTtsPitch(context, newPitch)
                    localPipeline.refreshTtsConfiguration()
                },
                onSpeechRateChange = { newRate ->
                    TranslatorStateRepository.updateTtsSpeechRate(context, newRate)
                    localPipeline.refreshTtsConfiguration()
                },
                onShowTargetBoxChange = { showBox ->
                    TranslatorStateRepository.updateShowTargetBoxOverlay(context, showBox)
                    if (state.isServiceRunning) {
                        val intent = Intent(context, ScreenTranslatorService::class.java).apply {
                            action = ScreenTranslatorService.ACTION_TOGGLE_TARGET_BOX
                        }
                        context.startService(intent)
                    }
                },
                onTestHindiTts = {
                    localPipeline.refreshTtsConfiguration()
                    localPipeline.speakHindiNonBlocking(
                        state.targetLanguage.sampleGreeting,
                        forceSpeakEvenIfRepeat = true
                    )
                },
                onEnableAccessibility = {
                    accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onInstallVoiceData = {
                    localPipeline.promptInstallHindiTtsData(context, openSystemInstaller = true)
                }
            )
            return
        }

        AppScreen.HOME -> Unit
    }

    val isRunning = state.isServiceRunning
    val primaryButtonColor by animateColorAsState(
        targetValue = if (isRunning) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(250),
        label = "startStopColor"
    )
    val outerHaloColor by animateColorAsState(
        targetValue = if (isRunning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = tween(250),
        label = "startStopHalo"
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Text(
                        text = "GameVox",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(
                        onClick = { currentScreen = AppScreen.SETTINGS },
                        modifier = Modifier.testTag("open_settings_sheet_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Status Pill
                Surface(
                    color = if (isRunning) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.testTag("overlay_status_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isRunning) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                        )
                        Text(
                            text = if (isRunning) "Floating Overlay Active" else "Ready to Start",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isRunning) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Large Center Start / Stop (Play / Pause) Button
                Box(
                    modifier = Modifier
                        .size(184.dp)
                        .clip(CircleShape)
                        .background(outerHaloColor.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        onClick = {
                            if (isRunning) {
                                val stopIntent = Intent(context, ScreenTranslatorService::class.java).apply {
                                    action = ScreenTranslatorService.ACTION_STOP_SERVICE
                                }
                                context.startService(stopIntent)
                            } else {
                                if (!Settings.canDrawOverlays(context)) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    overlaySettingsLauncher.launch(intent)
                                    return@Surface
                                }
                                val startIntent = Intent(context, ScreenTranslatorService::class.java).apply {
                                    action = ScreenTranslatorService.ACTION_START_DEMO_OVERLAY
                                }
                                ContextCompat.startForegroundService(context, startIntent)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Floating icon started")
                                }
                            }
                        },
                        shape = CircleShape,
                        color = primaryButtonColor,
                        contentColor = if (isRunning) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier
                            .size(144.dp)
                            .shadow(12.dp, CircleShape)
                            .testTag("toggle_overlay_demo_button")
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isRunning) "Stop Floating Overlay" else "Start Floating Overlay",
                                modifier = Modifier.size(52.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isRunning) "Stop" else "Start",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(36.dp))

                // Compact Active Voice Status Card (tapping opens Voice Manager)
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            voiceManagerOrigin = AppScreen.HOME
                            currentScreen = AppScreen.VOICE_MANAGER
                        }
                        .testTag("active_voice_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
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
                                    imageVector = if (activeVoice?.gender == SpeakerGender.MALE_18) {
                                        Icons.Default.Male
                                    } else {
                                        Icons.Default.Female
                                    },
                                    contentDescription = activeVoiceGenderLabel,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Active Voice: $activeVoiceSummary",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${state.cloudTtsProvider.displayLabel} • ${state.targetLanguage.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Manage Voices",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Minimal permission helper only when overlay permission is missing
                if (!hasOverlayPermission) {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlaySettingsLauncher.launch(intent)
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("grant_overlay_permission_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Allow Floating Overlay Permission")
                    }
                }
            }
        }
    }
}

private fun checkAccessibilityServiceEnabled(context: Context): Boolean {
    if (GameVoxAccessibilityService.instance != null) return true
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { info ->
        val resolveInfo = info.resolveInfo?.serviceInfo
        resolveInfo?.packageName == context.packageName &&
            resolveInfo?.name == GameVoxAccessibilityService::class.java.name
    }
}
