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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
import com.example.ui.HomeLiveDubbingScreen
import com.example.ui.SettingsScreen
import com.example.ui.VoiceGenerateScreen
import com.example.ui.VoiceLibraryScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

enum class GameVoxMainTab(
    val label: String,
    val icon: ImageVector,
    val testTag: String
) {
    HOME("Home", Icons.Default.Home, "nav_tab_home"),
    VOICE_LIBRARY("Library", Icons.Default.LibraryMusic, "nav_tab_voice_library"),
    VOICE_GENERATE("Generate", Icons.Default.AutoFixHigh, "nav_tab_voice_generate"),
    SETTINGS("Settings", Icons.Default.Settings, "nav_tab_settings")
}

private enum class SecondaryRoute {
    NONE,
    DIAGNOSIS,
    AB_VOICE_TESTING
}

class MainActivity : ComponentActivity() {

    private lateinit var localPipeline: OcrTranslationPipeline

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        localPipeline.ttsManager.prewarmNeuralEngineIfReady()

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
    val activeCharacter by VoiceProfileRepository.activeCharacterAssignment.collectAsStateWithLifecycle()

    var currentTab by remember { mutableStateOf(GameVoxMainTab.HOME) }
    var secondaryRoute by remember { mutableStateOf(SecondaryRoute.NONE) }
    var showAiConsentDialog by remember { mutableStateOf(false) }

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityServiceEnabled(context)) }

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
    ) { hasOverlayPermission = Settings.canDrawOverlays(context) }

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
            coroutineScope.launch { snackbarHostState.showSnackbar("Live subtitle dubbing started") }
        } else {
            TranslatorStateRepository.updateCaptureTelemetry(
                hasActiveProjection = false,
                statusMessage = "Screen capture permission is required to read game subtitles"
            )
            coroutineScope.launch { snackbarHostState.showSnackbar("Screen capture permission is required.") }
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
        if (!localPipeline.ttsManager.handleCheckTtsDataResult(result.resultCode, result.data)) {
            localPipeline.promptInstallHindiTtsData(context, openSystemInstaller = true)
        }
    }

    val kokoroModelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val msg = KokoroMediaPipeTtsEngine.getInstance(context).importModelFromUri(uri).fold(
                    onSuccess = { file -> "Imported ${file.name} for MediaPipe Kokoro-82M" },
                    onFailure = { err -> err.localizedMessage ?: "Failed to import .litertlm file" }
                )
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    DisposableEffect(localPipeline) {
        val voiceListener = object : ForegroundTtsManager.VoiceDataDownloadListener {
            override fun onHighQualityNetworkVoiceAvailable(language: TargetLanguage, voiceName: String, quality: Int) = Unit
            override fun onVoiceDataDownloadStarted(language: TargetLanguage) = Unit
            override fun onVoiceDataMissing(language: TargetLanguage, missingVoiceNames: List<String>, reason: String) {
                coroutineScope.launch {
                    val res = snackbarHostState.showSnackbar(
                        message = "${language.displayName} HD voice data missing",
                        actionLabel = "Download",
                        duration = SnackbarDuration.Short
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        try {
                            val pkg = state.activeTtsEnginePackage.ifBlank { state.selectedTtsEnginePackage }
                            checkTtsDataLauncher.launch(Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA).apply {
                                if (pkg.isNotBlank()) setPackage(pkg)
                            })
                        } catch (_: Exception) {
                            localPipeline.ttsManager.openSystemTtsSettings(context)
                        }
                    }
                }
            }
            override fun onVoiceDataInstalled(language: TargetLanguage, installedVoiceName: String) {
                coroutineScope.launch { snackbarHostState.showSnackbar("${language.displayName} voice ready") }
            }
        }
        localPipeline.ttsManager.addVoiceDataDownloadListener(voiceListener)
        onDispose { localPipeline.ttsManager.removeVoiceDataDownloadListener(voiceListener) }
    }

    if (secondaryRoute != SecondaryRoute.NONE) {
        BackHandler { secondaryRoute = SecondaryRoute.NONE }
    } else if (currentTab != GameVoxMainTab.HOME) {
        BackHandler { currentTab = GameVoxMainTab.HOME }
    }

    when (secondaryRoute) {
        SecondaryRoute.DIAGNOSIS -> {
            DiagnosisScreen(
                state = state,
                femaleVoicesCount = localPipeline.ttsManager.femaleVoices.size,
                maleVoicesCount = localPipeline.ttsManager.maleVoices.size,
                onNavigateBack = { secondaryRoute = SecondaryRoute.NONE },
                onTestVoice = {
                    localPipeline.refreshTtsConfiguration()
                    localPipeline.speakHindiNonBlocking(state.targetLanguage.sampleGreeting, forceSpeakEvenIfRepeat = true)
                },
                onRunVoiceBenchmark = { localPipeline.ttsManager.runVoiceBenchmark() },
                onClearQueueAndCache = { localPipeline.resetAntiRepeatMemory() },
                onOpenAbVoiceTesting = { secondaryRoute = SecondaryRoute.AB_VOICE_TESTING },
                onTestServerConnection = { localPipeline.ttsManager.testLocalChatterboxServerConnection() }
            )
            return
        }
        SecondaryRoute.AB_VOICE_TESTING -> {
            AbVoiceTestingScreen(state = state, onNavigateBack = { secondaryRoute = SecondaryRoute.NONE })
            return
        }
        SecondaryRoute.NONE -> Unit
    }

    if (showAiConsentDialog) {
        AlertDialog(
            onDismissRequest = { showAiConsentDialog = false },
            title = { Text("Context-Aware AI (Gemini)", fontWeight = FontWeight.Bold) },
            text = {
                Text("Only cropped English subtitle text and up to 4 recent dialogue lines are sent to Gemini for modern Hindi dubbing. Free On-Device (ML Kit) works offline anytime.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAiConsentDialog = false
                        TranslatorStateRepository.updateTranslationMode(context, TranslationMode.CONTEXT_AWARE_AI, true)
                    },
                    modifier = Modifier.testTag("home_confirm_ai_consent")
                ) { Text("Allow AI Mode") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAiConsentDialog = false },
                    modifier = Modifier.testTag("home_cancel_ai_consent")
                ) { Text("Stay Offline") }
            }
        )
    }

    val activeVoice = remember(voices, selectedVoiceId) {
        voices.find { it.id == selectedVoiceId } ?: voices.firstOrNull()
    }
    val activeVoiceSummary = remember(activeVoice) {
        val raw = activeVoice?.name?.trim().orEmpty().ifBlank { "Jenny" }
        val gender = if (activeVoice?.gender == SpeakerGender.MALE_18) "Male" else "Female"
        "$raw ($gender)"
    }

    @Composable
    fun ActiveTabContent() {
        when (currentTab) {
            GameVoxMainTab.HOME -> HomeLiveDubbingScreen(
                state = state,
                activeCharacter = activeCharacter,
                activeVoice = activeVoice,
                hasOverlayPermission = hasOverlayPermission,
                onToggleStartStopService = {
                    if (state.isServiceRunning) {
                        context.startService(Intent(context, ScreenTranslatorService::class.java).apply {
                            action = ScreenTranslatorService.ACTION_STOP_SERVICE
                        })
                    } else if (!Settings.canDrawOverlays(context)) {
                        overlaySettingsLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    } else {
                        val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                        if (pm != null) {
                            mediaProjectionLauncher.launch(pm.createScreenCaptureIntent())
                        } else {
                            coroutineScope.launch { snackbarHostState.showSnackbar("Screen capture not supported on this device") }
                        }
                    }
                },
                onToggleFloatingScanning = {
                    if (state.isServiceRunning) {
                        context.startService(Intent(context, ScreenTranslatorService::class.java).apply {
                            action = ScreenTranslatorService.ACTION_TOGGLE_SCANNING
                        })
                    }
                },
                onToggleSubtitleVisibility = { visible ->
                    TranslatorStateRepository.setSubtitleVisible(context, visible)
                    if (state.isServiceRunning) {
                        context.startService(Intent(context, ScreenTranslatorService::class.java).apply {
                            action = ScreenTranslatorService.ACTION_UPDATE_SUBTITLE_VISIBILITY
                        })
                    }
                },
                onSelectQuickCharacter = { charName ->
                    val assign = VoiceProfileRepository.selectActiveCharacterForGame(context, charName)
                    TranslatorStateRepository.updateDiagnosisMetrics(
                        activeVoiceName = "${assign.displayName} (Sid #${assign.speakerId})"
                    )
                },
                onTargetLanguageChange = { lang ->
                    TranslatorStateRepository.updateTargetLanguage(context, lang)
                    localPipeline.resetAntiRepeatMemory()
                    localPipeline.ensureTranslationModelDownloaded(downloadIfMissing = true)
                    localPipeline.refreshTtsConfiguration()
                },
                onToggleTranslationMode = {
                    if (state.translationMode == TranslationMode.ON_DEVICE_MLKIT) {
                        if (state.aiPrivacyConsentAccepted) {
                            TranslatorStateRepository.updateTranslationMode(context, TranslationMode.CONTEXT_AWARE_AI, true)
                        } else {
                            showAiConsentDialog = true
                        }
                    } else {
                        TranslatorStateRepository.updateTranslationMode(context, TranslationMode.ON_DEVICE_MLKIT, state.aiPrivacyConsentAccepted)
                    }
                },
                onTranslationStyleChange = { style ->
                    TranslatorStateRepository.updateTranslationStyle(context, style)
                    localPipeline.resetAntiRepeatMemory()
                },
                onDeliveryModeChange = { mode -> TranslatorStateRepository.updateDubbingDeliveryMode(context, mode) },
                onSpeechRateChange = { rate ->
                    TranslatorStateRepository.updateTtsSpeechRate(context, rate)
                    localPipeline.refreshTtsConfiguration()
                },
                onRequestOverlayPermission = {
                    overlaySettingsLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                },
                onOpenVoiceLibrary = { currentTab = GameVoxMainTab.VOICE_LIBRARY },
                onOpenDiagnosis = { secondaryRoute = SecondaryRoute.DIAGNOSIS }
            )

            GameVoxMainTab.VOICE_LIBRARY -> VoiceLibraryScreen(
                snackbarHostState = snackbarHostState,
                onAddVoiceClick = { currentTab = GameVoxMainTab.VOICE_GENERATE },
                onPlayVoicePreview = { profile -> localPipeline.previewVoiceProfile(profile) },
                onSelectVoice = { profile ->
                    VoiceProfileRepository.selectVoice(context, profile)
                    TranslatorStateRepository.updateTtsPitch(context, profile.pitch)
                    TranslatorStateRepository.updateTtsSpeechRate(context, profile.speechRate)
                    localPipeline.refreshTtsConfiguration()
                },
                onOpenAbVoiceTesting = { secondaryRoute = SecondaryRoute.AB_VOICE_TESTING }
            )

            GameVoxMainTab.VOICE_GENERATE -> VoiceGenerateScreen(
                snackbarHostState = snackbarHostState,
                onSavedToLibrary = { currentTab = GameVoxMainTab.VOICE_LIBRARY }
            )

            GameVoxMainTab.SETTINGS -> SettingsScreen(
                state = state,
                activeVoiceSummary = activeVoiceSummary,
                isAccessibilityEnabled = isAccessibilityEnabled,
                showTopBar = false,
                onNavigateBack = { currentTab = GameVoxMainTab.HOME },
                onOpenVoiceManager = { currentTab = GameVoxMainTab.VOICE_LIBRARY },
                onTargetLanguageChange = { lang ->
                    TranslatorStateRepository.updateTargetLanguage(context, lang)
                    localPipeline.resetAntiRepeatMemory()
                    localPipeline.ensureTranslationModelDownloaded(downloadIfMissing = true)
                    localPipeline.refreshTtsConfiguration()
                },
                onTranslationModeChange = { mode, consent ->
                    TranslatorStateRepository.updateTranslationMode(context, mode, consent)
                    localPipeline.resetAntiRepeatMemory()
                },
                onTranslationStyleChange = { style ->
                    TranslatorStateRepository.updateTranslationStyle(context, style)
                    localPipeline.resetAntiRepeatMemory()
                },
                onDubbingDeliveryModeChange = { mode -> TranslatorStateRepository.updateDubbingDeliveryMode(context, mode) },
                onDownloadOfflinePack = { localPipeline.ensureTranslationModelDownloaded(downloadIfMissing = true) },
                onSelectAndroidTtsEngine = { pkg -> localPipeline.ttsManager.selectAndroidTtsEngine(pkg) },
                onRefreshTtsEngines = { localPipeline.ttsManager.refreshInstalledEnginesList() },
                onOpenSystemTtsSettings = { localPipeline.ttsManager.openSystemTtsSettings(context) },
                onSelectFreeVoiceBackend = { backend ->
                    TranslatorStateRepository.updateFreeVoiceBackend(context, backend)
                    if (backend == FreeVoiceBackend.MEDIAPIPE_KOKORO) {
                        val d = KokoroMediaPipeTtsEngine.getInstance(context).discoverModelFile(state.kokoroModelPath)
                        TranslatorStateRepository.updateKokoroStatusMessage(d.statusSummary)
                    }
                },
                onImportKokoroModel = { kokoroModelPickerLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                onToggleKokoroGpu = { gpu -> TranslatorStateRepository.updateKokoroConfig(context, state.kokoroModelPath, gpu) },
                onClearKokoroModel = { coroutineScope.launch { KokoroMediaPipeTtsEngine.getInstance(context).clearImportedModel() } },
                onHighQualityVoiceChange = { hq ->
                    TranslatorStateRepository.updateUseHighQualityNetworkVoice(context, hq)
                    localPipeline.refreshTtsConfiguration()
                },
                onPitchChange = { p -> TranslatorStateRepository.updateTtsPitch(context, p); localPipeline.refreshTtsConfiguration() },
                onSpeechRateChange = { r -> TranslatorStateRepository.updateTtsSpeechRate(context, r); localPipeline.refreshTtsConfiguration() },
                onVolumeChange = { v -> TranslatorStateRepository.updateTtsVolume(context, v) },
                onShowTargetBoxChange = { show ->
                    TranslatorStateRepository.updateShowTargetBoxOverlay(context, show)
                    if (state.isServiceRunning) {
                        context.startService(Intent(context, ScreenTranslatorService::class.java).apply {
                            action = ScreenTranslatorService.ACTION_TOGGLE_TARGET_BOX
                        })
                    }
                },
                onShowSubtitleCaptionsChange = { show ->
                    TranslatorStateRepository.setSubtitleVisible(context, show)
                    if (state.isServiceRunning) {
                        context.startService(Intent(context, ScreenTranslatorService::class.java).apply {
                            action = ScreenTranslatorService.ACTION_UPDATE_SUBTITLE_VISIBILITY
                        })
                    }
                },
                onAddGlossaryEntry = { t, s -> TranslatorStateRepository.upsertGlossaryEntry(context, t, s) },
                onRemoveGlossaryEntry = { t -> TranslatorStateRepository.removeGlossaryEntry(context, t) },
                onResetGlossaryDefaults = { TranslatorStateRepository.resetGlossaryToDefaults(context) },
                onTestHindiTts = {
                    localPipeline.refreshTtsConfiguration()
                    localPipeline.speakHindiNonBlocking(state.targetLanguage.sampleGreeting, forceSpeakEvenIfRepeat = true)
                },
                onEnableAccessibility = { accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onInstallVoiceData = { localPipeline.promptInstallHindiTtsData(context, openSystemInstaller = true) },
                onOpenDiagnosis = { secondaryRoute = SecondaryRoute.DIAGNOSIS }
            )
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

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
                            text = when (currentTab) {
                                GameVoxMainTab.HOME -> "GameVox"
                                GameVoxMainTab.VOICE_LIBRARY -> "Voice Library"
                                GameVoxMainTab.VOICE_GENERATE -> "Voice Generate"
                                GameVoxMainTab.SETTINGS -> "Settings"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { secondaryRoute = SecondaryRoute.DIAGNOSIS },
                            modifier = Modifier.testTag("open_diagnosis_button")
                        ) {
                            Icon(imageVector = Icons.Default.BugReport, contentDescription = "Live Diagnosis")
                        }
                    }
                )
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar {
                        GameVoxMainTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = currentTab == tab,
                                onClick = { currentTab = tab },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            if (useNavigationRail) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavigationRail {
                        GameVoxMainTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = currentTab == tab,
                                onClick = { currentTab = tab },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) { ActiveTabContent() }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) { ActiveTabContent() }
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
