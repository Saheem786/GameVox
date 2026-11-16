package com.example

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
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
import com.example.data.TranslationMode
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceProfileRepository
import com.example.engine.ContextAwareAiTranslator
import com.example.engine.OcrTranslationPipeline
import com.example.firebase.configureAppCheck
import com.example.service.ForegroundTtsManager
import com.example.service.FreeVoiceBackend
import com.example.service.GameVoxAccessibilityService
import com.example.service.KokoroMediaPipeTtsEngine
import com.example.service.ScreenTranslatorService
import com.example.ui.AbVoiceTestingScreen
import com.example.ui.DiagnosisScreen
import com.example.ui.SettingsScreen
import com.example.ui.VoiceManagerScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

private enum class AppScreen {
    HOME,
    SETTINGS,
    VOICE_MANAGER,
    DIAGNOSIS,
    AB_VOICE_TESTING
}

class MainActivity : ComponentActivity() {

    private lateinit var localPipeline: OcrTranslationPipeline

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must run immediately before enableEdgeToEdge(), ViewModels, or setContent
        configureAppCheck(this, intent)
        AppContextHolder.set(applicationContext)
        enableEdgeToEdge()

        TranslatorStateRepository.loadPreferences(applicationContext)
        lifecycleScope.launch {
            TranslatorStateRepository.loadCloudTtsFromDataStore(applicationContext)
        }
        ContextAwareAiTranslator.isFirebaseAiAvailable(applicationContext)
        VoiceProfileRepository.initialize(applicationContext)
        localPipeline = OcrTranslationPipeline.getInstance(applicationContext)
        localPipeline.ensureTranslationModelDownloaded(downloadIfMissing = true)

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
    var diagnosisOrigin by remember { mutableStateOf(AppScreen.HOME) }
    var abTestingOrigin by remember { mutableStateOf(AppScreen.HOME) }
    var showAiConsentDialog by remember { mutableStateOf(false) }

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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            val serviceIntent = Intent(context, ScreenTranslatorService::class.java).apply {
                action = ScreenTranslatorService.ACTION_START_WITH_PROJECTION
                putExtra(ScreenTranslatorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenTranslatorService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Live subtitle dubbing started")
            }
        } else {
            TranslatorStateRepository.updateCaptureTelemetry(
                hasActiveProjection = false,
                statusMessage = "Screen capture permission is required to read game subtitles"
            )
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Screen capture permission is required to read game subtitles.")
            }
        }
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

    val kokoroModelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val res = KokoroMediaPipeTtsEngine.getInstance(context).importModelFromUri(uri)
                val msg = res.fold(
                    onSuccess = { file -> "Imported ${file.name} for MediaPipe Kokoro-82M" },
                    onFailure = { err -> err.localizedMessage ?: "Failed to import .litertlm file" }
                )
                snackbarHostState.showSnackbar(msg)
            }
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
                            val targetPkg = state.activeTtsEnginePackage
                                .ifBlank { state.selectedTtsEnginePackage }
                            val checkIntent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA).apply {
                                if (targetPkg.isNotBlank()) {
                                    setPackage(targetPkg)
                                }
                            }
                            checkTtsDataLauncher.launch(checkIntent)
                        } catch (_: Exception) {
                            localPipeline.ttsManager.openSystemTtsSettings(context)
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
                },
                onOpenAbVoiceTesting = {
                    abTestingOrigin = AppScreen.VOICE_MANAGER
                    currentScreen = AppScreen.AB_VOICE_TESTING
                }
            )
            return
        }

        AppScreen.AB_VOICE_TESTING -> {
            AbVoiceTestingScreen(
                state = state,
                onNavigateBack = { currentScreen = abTestingOrigin }
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
                    localPipeline.ensureTranslationModelDownloaded(downloadIfMissing = true)
                    localPipeline.refreshTtsConfiguration()
                },
                onTranslationModeChange = { mode, aiConsent ->
                    TranslatorStateRepository.updateTranslationMode(context, mode, aiConsent)
                    localPipeline.resetAntiRepeatMemory()
                },
                onDownloadOfflinePack = {
                    localPipeline.ensureTranslationModelDownloaded(downloadIfMissing = true)
                },
                onSelectAndroidTtsEngine = { enginePkg ->
                    localPipeline.ttsManager.selectAndroidTtsEngine(enginePkg)
                },
                onRefreshTtsEngines = {
                    localPipeline.ttsManager.refreshInstalledEnginesList()
                },
                onOpenSystemTtsSettings = {
                    localPipeline.ttsManager.openSystemTtsSettings(context)
                },
                onSelectFreeVoiceBackend = { backend ->
                    TranslatorStateRepository.updateFreeVoiceBackend(context, backend)
                    if (backend == FreeVoiceBackend.MEDIAPIPE_KOKORO) {
                        val discovery = KokoroMediaPipeTtsEngine.getInstance(context)
                            .discoverModelFile(state.kokoroModelPath)
                        TranslatorStateRepository.updateKokoroStatusMessage(discovery.statusSummary)
                    }
                },
                onImportKokoroModel = {
                    kokoroModelPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                },
                onToggleKokoroGpu = { useGpu ->
                    TranslatorStateRepository.updateKokoroConfig(
                        context = context,
                        modelPath = state.kokoroModelPath,
                        useGpu = useGpu
                    )
                },
                onClearKokoroModel = {
                    coroutineScope.launch {
                        KokoroMediaPipeTtsEngine.getInstance(context).clearImportedModel()
                    }
                },
                onHighQualityVoiceChange = { enabled ->
                    TranslatorStateRepository.updateUseHighQualityNetworkVoice(context, enabled)
                    localPipeline.refreshTtsConfiguration()
                },
                onPitchChange = { newPitch ->
                    TranslatorStateRepository.updateTtsPitch(context, newPitch)
                    localPipeline.refreshTtsConfiguration()
                },
                onSpeechRateChange = { newRate ->
                    TranslatorStateRepository.updateTtsSpeechRate(context, newRate)
                    localPipeline.refreshTtsConfiguration()
                },
                onVolumeChange = { newVolume ->
                    TranslatorStateRepository.updateTtsVolume(context, newVolume)
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
                onShowSubtitleCaptionsChange = { showCaptions ->
                    TranslatorStateRepository.setSubtitleVisible(context, showCaptions)
                    if (state.isServiceRunning) {
                        val intent = Intent(context, ScreenTranslatorService::class.java).apply {
                            action = ScreenTranslatorService.ACTION_UPDATE_SUBTITLE_VISIBILITY
                        }
                        context.startService(intent)
                    }
                },
                onAddGlossaryEntry = { term, spoken ->
                    TranslatorStateRepository.upsertGlossaryEntry(context, term, spoken)
                },
                onRemoveGlossaryEntry = { term ->
                    TranslatorStateRepository.removeGlossaryEntry(context, term)
                },
                onResetGlossaryDefaults = {
                    TranslatorStateRepository.resetGlossaryToDefaults(context)
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
                },
                onOpenDiagnosis = {
                    diagnosisOrigin = AppScreen.SETTINGS
                    currentScreen = AppScreen.DIAGNOSIS
                }
            )
            return
        }

        AppScreen.DIAGNOSIS -> {
            DiagnosisScreen(
                state = state,
                femaleVoicesCount = localPipeline.ttsManager.femaleVoices.size,
                maleVoicesCount = localPipeline.ttsManager.maleVoices.size,
                onNavigateBack = { currentScreen = diagnosisOrigin },
                onTestVoice = {
                    localPipeline.refreshTtsConfiguration()
                    localPipeline.speakHindiNonBlocking(
                        state.targetLanguage.sampleGreeting,
                        forceSpeakEvenIfRepeat = true
                    )
                },
                onRunVoiceBenchmark = {
                    localPipeline.ttsManager.runVoiceBenchmark()
                },
                onClearQueueAndCache = {
                    localPipeline.resetAntiRepeatMemory()
                },
                onOpenAbVoiceTesting = {
                    abTestingOrigin = AppScreen.DIAGNOSIS
                    currentScreen = AppScreen.AB_VOICE_TESTING
                },
                onTestServerConnection = {
                    localPipeline.ttsManager.testLocalChatterboxServerConnection()
                }
            )
            return
        }

        AppScreen.HOME -> Unit
    }

    if (showAiConsentDialog) {
        AlertDialog(
            onDismissRequest = { showAiConsentDialog = false },
            title = {
                Text(
                    text = "Context-Aware AI (Gemini)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Only cropped English subtitle text and up to 4 recent dialogue lines are sent to Gemini for modern Hindi dubbing. Requires internet and cloud quota. Free On-Device (ML Kit) works offline anytime.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAiConsentDialog = false
                        TranslatorStateRepository.updateTranslationMode(
                            context,
                            TranslationMode.CONTEXT_AWARE_AI,
                            aiPrivacyConsentAccepted = true
                        )
                    },
                    modifier = Modifier.testTag("home_confirm_ai_consent")
                ) {
                    Text("Allow AI Mode")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAiConsentDialog = false },
                    modifier = Modifier.testTag("home_cancel_ai_consent")
                ) {
                    Text("Stay Offline")
                }
            }
        )
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
                        onClick = {
                            diagnosisOrigin = AppScreen.HOME
                            currentScreen = AppScreen.DIAGNOSIS
                        },
                        modifier = Modifier.testTag("open_diagnosis_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Live Diagnosis",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
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
                    color = when {
                        state.isProtectedScreenDetected -> MaterialTheme.colorScheme.errorContainer
                        isRunning -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
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
                                    when {
                                        state.isProtectedScreenDetected -> MaterialTheme.colorScheme.error
                                        isRunning && state.isCapturingOcr -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                        )
                        Text(
                            text = when {
                                state.isProtectedScreenDetected -> "Protected Screen (FLAG_SECURE)"
                                isRunning && state.isCapturingOcr -> "Live Subtitle Dubbing Active"
                                isRunning -> "Floating Controls Paused"
                                else -> "Ready to Dub Game Subtitles"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                state.isProtectedScreenDetected -> MaterialTheme.colorScheme.onErrorContainer
                                isRunning -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (state.isProtectedScreenDetected) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("protected_screen_warning_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = "Protected screen warning",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = state.captureStatusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Large Center Start / Stop Button (requests fresh MediaProjection consent)
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
                                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                    as? MediaProjectionManager
                                if (projectionManager != null) {
                                    mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Screen capture not supported on this device")
                                    }
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
                                contentDescription = if (isRunning) "Stop Dubbing Service" else "Start Live Subtitle Dubbing",
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

                Spacer(modifier = Modifier.height(28.dp))

                // Quick Translation Mode Card (tapping toggles or opens consent)
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
                            if (state.translationMode == TranslationMode.ON_DEVICE_MLKIT) {
                                if (state.aiPrivacyConsentAccepted) {
                                    TranslatorStateRepository.updateTranslationMode(
                                        context,
                                        TranslationMode.CONTEXT_AWARE_AI,
                                        aiPrivacyConsentAccepted = true
                                    )
                                } else {
                                    showAiConsentDialog = true
                                }
                            } else {
                                TranslatorStateRepository.updateTranslationMode(
                                    context,
                                    TranslationMode.ON_DEVICE_MLKIT,
                                    state.aiPrivacyConsentAccepted
                                )
                            }
                        }
                        .testTag("home_translation_mode_card")
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
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (state.translationMode == TranslationMode.CONTEXT_AWARE_AI) {
                                        Icons.Default.AutoAwesome
                                    } else {
                                        Icons.Default.OfflinePin
                                    },
                                    contentDescription = state.translationMode.displayName,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.translationMode.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when {
                                        state.translationMode == TranslationMode.ON_DEVICE_MLKIT &&
                                            (state.translationSourceLabel == "Offline Phrasebook (Limited)" ||
                                                state.modelStatus == com.example.data.ModelDownloadStatus.OFFLINE_FALLBACK) ->
                                            "Limited Local Phrasebook • Download ~30 MB ML Kit pack"
                                        state.translationMode == TranslationMode.ON_DEVICE_MLKIT ->
                                            state.mlKitModelStatusText
                                        state.firebaseAiStatusText.contains("Configured", ignoreCase = true) ->
                                            "Online • 4-line scene context active"
                                        else ->
                                            "AI Unavailable (${state.firebaseAiStatusText}) • Using Offline"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "Switch",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

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
                                    text = "${state.activeTtsEngineLabel} • ${state.targetLanguage.displayName} • ${String.format(java.util.Locale.US, "%.1fx", state.ttsSpeechRate)}",
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

                Spacer(modifier = Modifier.height(12.dp))

                // Compose-based UI component for instant mode switching on the main screen
                com.example.ui.DubbingModeSwitchCard(
                    state = state,
                    onOpenAbLab = {
                        abTestingOrigin = AppScreen.HOME
                        currentScreen = AppScreen.AB_VOICE_TESTING
                    }
                )

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
