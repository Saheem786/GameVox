package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SubtitleTranslationEntry
import com.example.data.TargetLanguage
import com.example.data.TranslatorAppState
import com.example.data.TranslatorStateRepository
import com.example.data.TtsEngineStatus
import com.example.data.VoiceProfileRepository
import com.example.engine.BitmapInverter
import com.example.engine.OcrTranslationPipeline
import com.example.service.ForegroundTtsManager
import com.example.service.GameVoxAccessibilityService
import com.example.service.ScreenTranslatorService
import com.example.ui.FloatingIconGestureOverlayCard
import com.example.ui.GAME_SCENE_PRESETS
import com.example.ui.GameSimulatorSection
import com.example.ui.SettingsBottomSheet
import com.example.ui.TtsAudioVisualizerCard
import com.example.ui.VoiceManagerScreen
import com.example.ui.VoiceManagerSection
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SaffronGold
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceVariant
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var localPipeline: OcrTranslationPipeline

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TranslatorStateRepository.loadPreferences(applicationContext)
        VoiceProfileRepository.initialize(applicationContext)
        localPipeline = OcrTranslationPipeline.getInstance(applicationContext)

        if (TranslatorStateRepository.state.value.originalFramePreview == null) {
            val initialPreset = GAME_SCENE_PRESETS.first()
            val rawSample = BitmapInverter.createSampleGameSubtitleBitmap(
                initialPreset.speaker,
                initialPreset.subtitleEnglish
            )
            val invSample = BitmapInverter.invertBitmap(rawSample)
            TranslatorStateRepository.updateFramePreviews(
                BitmapInverter.createPreviewThumbnail(rawSample),
                BitmapInverter.createPreviewThumbnail(invSample)
            )
        }

        setContent {
            MyApplicationTheme {
                GameVoxHindiDashboard(
                    localPipeline = localPipeline
                )
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
fun GameVoxHindiDashboard(
    localPipeline: OcrTranslationPipeline
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val state by TranslatorStateRepository.state.collectAsStateWithLifecycle()

    var showVoiceManagerScreen by remember { mutableStateOf(false) }

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var hasNotificationPermission by remember {
        mutableStateOf(checkNotificationPermission(context))
    }
    var isAccessibilityEnabled by remember {
        mutableStateOf(checkAccessibilityServiceEnabled(context))
    }

    // Re-check permissions cleanly whenever returning from Android System Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasNotificationPermission = checkNotificationPermission(context)
                isAccessibilityEnabled = checkAccessibilityServiceEnabled(context)
                TranslatorStateRepository.setAccessibilityConnected(isAccessibilityEnabled)
                localPipeline.refreshTtsConfiguration()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
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

    // Register VoiceDataDownloadListener to react when Hindi or target high-quality voice packs are missing or installed
    DisposableEffect(localPipeline) {
        val voiceListener = object : ForegroundTtsManager.VoiceDataDownloadListener {
            override fun onHighQualityNetworkVoiceAvailable(
                language: TargetLanguage,
                voiceName: String,
                quality: Int
            ) {
                // High-quality network voice pack is active
            }

            override fun onVoiceDataMissing(
                language: TargetLanguage,
                missingVoiceNames: List<String>,
                reason: String
            ) {
                coroutineScope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "${language.displayName} HD Voice Data missing. Download now?",
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

            override fun onVoiceDataDownloadStarted(language: TargetLanguage) {
                // Background voice pack download initiated
            }

            override fun onVoiceDataInstalled(
                language: TargetLanguage,
                installedVoiceName: String
            ) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        "${language.displayName} HD Voice Ready ($installedVoiceName)"
                    )
                }
            }
        }
        localPipeline.ttsManager.addVoiceDataDownloadListener(voiceListener)
        onDispose {
            localPipeline.ttsManager.removeVoiceDataDownloadListener(voiceListener)
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ScreenTranslatorService::class.java).apply {
                action = ScreenTranslatorService.ACTION_START_WITH_PROJECTION
                putExtra(ScreenTranslatorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenTranslatorService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Floating Translator & 2s Screen Capture Started!")
            }
        }
    }

    if (showVoiceManagerScreen) {
        VoiceManagerScreen(
            onNavigateBack = { showVoiceManagerScreen = false },
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

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = ObsidianBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ObsidianBg,
                    titleContentColor = Color.White
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(SlateCardElevated)
                                .border(1.5.dp, ElectricCyan, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "अ/A",
                                style = MaterialTheme.typography.labelLarge,
                                color = ElectricCyan
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "Accessibility • OCR • Hindi TTS",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showVoiceManagerScreen = true },
                        modifier = Modifier.testTag("open_voice_studio_top_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = "Open Voice Studio Screen",
                            tint = NeonEmerald
                        )
                    }
                    IconButton(
                        onClick = { TranslatorStateRepository.setShowSettingsBottomSheet(true) },
                        modifier = Modifier.testTag("open_settings_sheet_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.open_tts_settings),
                            tint = ElectricCyan
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { TranslatorStateRepository.setShowSettingsBottomSheet(true) },
                containerColor = ElectricCyan,
                contentColor = ObsidianBg,
                modifier = Modifier.testTag("fab_tts_settings")
            ) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = "Open Hindi TTS & OCR Settings"
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Hero Banner & Floating Overlay Controls (Never chains multiple dialogs!)
                item {
                    HeroControlBanner(
                        state = state,
                        hasOverlayPermission = hasOverlayPermission,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        onStartFloatingIcon = {
                            if (!hasOverlayPermission) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                overlaySettingsLauncher.launch(intent)
                                return@HeroControlBanner
                            }
                            val serviceIntent = Intent(context, ScreenTranslatorService::class.java).apply {
                                action = ScreenTranslatorService.ACTION_START_DEMO_OVERLAY
                            }
                            ContextCompat.startForegroundService(context, serviceIntent)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Floating Icon Active! Single-tap icon to read & speak game text.")
                            }
                        },
                        onLaunchFullScreenCapture = {
                            if (!hasOverlayPermission) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                overlaySettingsLauncher.launch(intent)
                                return@HeroControlBanner
                            }
                            // Only launch MediaProjection (1 single system dialog, no chained dialog loop!)
                            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                        },
                        onToggleScanning = {
                            if (state.isServiceRunning) {
                                val intent = Intent(context, ScreenTranslatorService::class.java).apply {
                                    action = ScreenTranslatorService.ACTION_TOGGLE_SCANNING
                                }
                                context.startService(intent)
                            } else {
                                TranslatorStateRepository.toggleCapturingOcr()
                            }
                        },
                        onStopService = {
                            val intent = Intent(context, ScreenTranslatorService::class.java).apply {
                                action = ScreenTranslatorService.ACTION_STOP_SERVICE
                            }
                            context.startService(intent)
                        }
                    )
                }

                // 1.05. Jetpack Compose Floating Icon Tap Controls (`Modifier.pointerInput` + `detectTapGestures`)
                // - Single Tap (`onTap`): Toggles ONLY the Green Scan Box (`showTargetBoxOverlay`)
                // - Double Tap (`onDoubleTap`): Toggles ONLY the Translated Captions Box (`isSubtitleVisible`)
                // - Long Press (`onLongPress`): Toggles Voice ON/OFF (`isVoiceMuted`)
                item {
                    FloatingIconGestureOverlayCard(
                        state = state,
                        onSingleTapScanBoxVisibilityChanged = { scanBoxVisible ->
                            TranslatorStateRepository.updateShowTargetBoxOverlay(context, scanBoxVisible)
                            if (state.isServiceRunning) {
                                val intent = Intent(context, ScreenTranslatorService::class.java).apply {
                                    action = ScreenTranslatorService.ACTION_TOGGLE_TARGET_BOX
                                }
                                context.startService(intent)
                            }
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (scanBoxVisible) "Green Scan Box Shown (Single Tap)"
                                    else "Green Scan Box Hidden (Single Tap)"
                                )
                            }
                        },
                        onDoubleTapSubtitleVisibilityChanged = { captionsVisible ->
                            TranslatorStateRepository.setSubtitleVisible(context, captionsVisible)
                            if (state.isServiceRunning) {
                                val intent = Intent(context, ScreenTranslatorService::class.java).apply {
                                    action = ScreenTranslatorService.ACTION_TOGGLE_SCANNING
                                }
                                context.startService(intent)
                            }
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (captionsVisible) "Translated Captions Box Shown (Double Tap)"
                                    else "Translated Captions Box Hidden (Double Tap) — Green Scan Box untouched"
                                )
                            }
                        },
                        onLongPressVoiceMuteChanged = { muted ->
                            TranslatorStateRepository.setVoiceMuted(context, muted)
                            if (muted) {
                                localPipeline.ttsManager.stopSpeaking()
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Voice Muted (Long Press)")
                                }
                            } else {
                                val currentHindi = state.lastTranslatedHindi
                                if (currentHindi.isNotBlank() && currentHindi != "स्कैन बॉक्स को गेम के टेक्स्ट पर ले जाएं...") {
                                    localPipeline.speakHindiNonBlocking(currentHindi, forceSpeakEvenIfRepeat = true)
                                }
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Voice Unmuted (Long Press)")
                                }
                            }
                        },
                        onTestCasualHindiTranslation = { sampleEng ->
                            localPipeline.resetAntiRepeatMemory()
                            coroutineScope.launch {
                                localPipeline.processExtractedRawText(sampleEng)
                            }
                        }
                    )
                }

                // 1a. Real-Time Canvas TTS Audio Visualizer (Synced with ForegroundTtsManager)
                item {
                    TtsAudioVisualizerCard(
                        ttsManager = localPipeline.ttsManager,
                        fallbackTextToSpeak = state.lastTranslatedHindi,
                        onSpeakSample = {
                            localPipeline.speakHindiNonBlocking(
                                hindiText = state.lastTranslatedHindi,
                                forceSpeakEvenIfRepeat = true
                            )
                        }
                    )
                }

                // 1b. Multi-Voice Manager UI (Voice List, Voice Cloning Recorder/Uploader & Multi-Speaker Assignments)
                item {
                    VoiceManagerSection(
                        onPlayVoicePreview = { voiceProfile ->
                            localPipeline.previewVoiceProfile(voiceProfile)
                        },
                        onSelectVoice = { voiceProfile ->
                            TranslatorStateRepository.updateTtsPitch(context, voiceProfile.pitch)
                            TranslatorStateRepository.updateTtsSpeechRate(context, voiceProfile.speechRate)
                            localPipeline.refreshTtsConfiguration()
                            localPipeline.previewVoiceProfile(voiceProfile)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Selected voice: ${voiceProfile.name}")
                            }
                        },
                        onOpenFullScreen = {
                            showVoiceManagerScreen = true
                        }
                    )
                }

                // 2. Permissions & AccessibilityService Readiness Checklist
                item {
                    PermissionsAndEngineStatusCard(
                        state = state,
                        hasOverlayPermission = hasOverlayPermission,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        hasNotificationPermission = hasNotificationPermission,
                        onGrantOverlay = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlaySettingsLauncher.launch(intent)
                        },
                        onEnableAccessibility = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            accessibilitySettingsLauncher.launch(intent)
                        },
                        onGrantNotification = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onInstallHindiVoice = {
                            localPipeline.promptInstallHindiTtsData(context)
                        },
                        onOpenSettingsSheet = {
                            TranslatorStateRepository.setShowSettingsBottomSheet(true)
                        }
                    )
                }

                // 3. Live OCR Debug Readout & Hindi Translation Card
                item {
                    LiveOcrDebugMonitorCard(
                        state = state,
                        onSpeakCurrentHindi = {
                            localPipeline.speakHindiNonBlocking(
                                state.lastTranslatedHindi,
                                forceSpeakEvenIfRepeat = true
                            )
                        },
                        onOpenSettingsSheet = {
                            TranslatorStateRepository.setShowSettingsBottomSheet(true)
                        }
                    )
                }

                // 4. Built-in Game Scene Subtitle Simulator & ColorMatrix Inversion Inspector
                item {
                    GameSimulatorSection(
                        state = state,
                        onSimulateFrameScan = { speaker, subtitle ->
                            // Reset anti-repeat when user clicks Scan & Speak in simulator so it always speaks
                            localPipeline.resetAntiRepeatMemory()
                            if (state.isServiceRunning) {
                                val intent = Intent(context, ScreenTranslatorService::class.java).apply {
                                    action = ScreenTranslatorService.ACTION_PROCESS_SIMULATED_FRAME
                                    putExtra(ScreenTranslatorService.EXTRA_SIMULATED_SPEAKER, speaker)
                                    putExtra(ScreenTranslatorService.EXTRA_SIMULATED_SUBTITLE, subtitle)
                                }
                                context.startService(intent)
                            } else {
                                coroutineScope.launch {
                                    val frame = BitmapInverter.createSampleGameSubtitleBitmap(speaker, subtitle)
                                    localPipeline.processCapturedFrame(frame)
                                }
                            }
                        }
                    )
                }

                // 5. Recent Subtitle Translation & Anti-Repeat Telemetry Log
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Subtitle OCR & Anti-Repeat Log (${state.history.size})",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (state.history.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    TranslatorStateRepository.clearHistory()
                                    localPipeline.resetAntiRepeatMemory()
                                },
                                modifier = Modifier.testTag("clear_history_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = "Clear translation log",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (state.history.isEmpty()) {
                    item {
                        Surface(
                            color = SlateSurface,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No subtitles captured yet.",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Tap 'Scan & Speak Hindi' above or launch the Floating Icon over any game.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(state.history, key = { it.id }) { entry ->
                        SubtitleHistoryItemCard(
                            entry = entry,
                            onReplayHindi = {
                                localPipeline.speakHindiNonBlocking(
                                    entry.translatedHindi,
                                    forceSpeakEvenIfRepeat = true
                                )
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    // Controlled single-dialog Jetpack Compose state — only 1 dialog can ever open, and Dismiss closes it immediately
    if (state.showSettingsBottomSheet) {
        SettingsBottomSheet(
            state = state,
            onDismiss = { TranslatorStateRepository.setShowSettingsBottomSheet(false) },
            onTargetLanguageChange = { newLang ->
                TranslatorStateRepository.updateTargetLanguage(context, newLang)
                localPipeline.resetAntiRepeatMemory()
                localPipeline.refreshTtsConfiguration()
                coroutineScope.launch {
                    val currentEng = state.lastExtractedEnglish
                    if (currentEng.isNotBlank() && currentEng != "Waiting for game subtitles...") {
                        localPipeline.processExtractedRawText(currentEng)
                    }
                }
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
            onShowTargetBoxChange = { showBox ->
                TranslatorStateRepository.updateShowTargetBoxOverlay(context, showBox)
                if (state.isServiceRunning) {
                    val intent = Intent(context, ScreenTranslatorService::class.java).apply {
                        action = ScreenTranslatorService.ACTION_TOGGLE_TARGET_BOX
                    }
                    context.startService(intent)
                }
            },
            onPitchChange = { newPitch ->
                TranslatorStateRepository.updateTtsPitch(context, newPitch)
                localPipeline.refreshTtsConfiguration()
            },
            onSpeechRateChange = { newRate ->
                TranslatorStateRepository.updateTtsSpeechRate(context, newRate)
                localPipeline.refreshTtsConfiguration()
            },
            onInvertColorsChange = { enabled ->
                TranslatorStateRepository.updateInvertColors(context, enabled)
            },
            onIntervalChange = { intervalMs ->
                TranslatorStateRepository.updateCaptureInterval(context, intervalMs)
            },
            onTestHindiTts = {
                localPipeline.refreshTtsConfiguration()
                localPipeline.speakHindiNonBlocking(
                    state.targetLanguage.sampleGreeting,
                    forceSpeakEvenIfRepeat = true
                )
            },
            onRefreshTtsStatus = {
                if (state.isServiceRunning) {
                    val intent = Intent(context, ScreenTranslatorService::class.java).apply {
                        action = ScreenTranslatorService.ACTION_REFRESH_TTS
                    }
                    context.startService(intent)
                } else {
                    localPipeline.refreshTtsConfiguration()
                }
            },
            onInstallHindiVoiceData = {
                TranslatorStateRepository.setShowSettingsBottomSheet(false)
                if (state.isServiceRunning) {
                    val intent = Intent(context, ScreenTranslatorService::class.java).apply {
                        action = ScreenTranslatorService.ACTION_DOWNLOAD_VOICE_DATA
                        putExtra(ScreenTranslatorService.EXTRA_OPEN_SYSTEM_TTS_INSTALLER, true)
                    }
                    context.startService(intent)
                } else {
                    localPipeline.promptInstallHindiTtsData(context, openSystemInstaller = true)
                }
            },
            onResetAntiRepeat = {
                localPipeline.resetAntiRepeatMemory()
            }
        )
    }
}

@Composable
private fun HeroControlBanner(
    state: TranslatorAppState,
    hasOverlayPermission: Boolean,
    isAccessibilityEnabled: Boolean,
    onStartFloatingIcon: () -> Unit,
    onLaunchFullScreenCapture: () -> Unit,
    onToggleScanning: () -> Unit,
    onStopService: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = if (state.isCapturingOcr) NeonEmerald else ElectricCyan.copy(alpha = 0.5f),
                shape = RoundedCornerShape(22.dp)
            )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_hero_banner_1794555841728),
                    contentDescription = "GameVox Hindi HUD Banner",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    ObsidianBg.copy(alpha = 0.7f),
                                    SlateSurface
                                )
                            )
                        )
                )

                Surface(
                    color = if (state.isCapturingOcr) NeonEmerald else SlateCardElevated,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.isCapturingOcr) ObsidianBg else SaffronGold)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                state.isCapturingOcr -> "SCANNING BOX EVERY ${state.captureIntervalMs / 1000.0}s"
                                state.isServiceRunning -> "SCAN BOX & ICON READY"
                                else -> "STANDBY"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isCapturingOcr) ObsidianBg else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.hero_subtitle),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        text = "1-Tap: Green Scan Box • 2-Tap: Translated Captions • Hold: Voice Mute",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ElectricCyan
                    )
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!state.isServiceRunning) {
                    Button(
                        onClick = onStartFloatingIcon,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("toggle_overlay_demo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BubbleChart,
                            contentDescription = null,
                            tint = ObsidianBg
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (!hasOverlayPermission) {
                                "1. Grant Overlay Permission"
                            } else if (isAccessibilityEnabled) {
                                "Launch Floating Icon + Draggable Scan Box"
                            } else {
                                "Launch Floating Icon + Draggable Scan Box"
                            },
                            color = ObsidianBg,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    OutlinedButton(
                        onClick = onLaunchFullScreenCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("launch_projection_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenShare,
                            contentDescription = null,
                            tint = SaffronGold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start with MediaProjection Screen Capture",
                            color = SaffronGold
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onToggleScanning,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isCapturingOcr) SaffronGold else NeonEmerald
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("toggle_scanning_button")
                        ) {
                            Icon(
                                imageVector = if (state.isCapturingOcr) {
                                    Icons.Default.PauseCircle
                                } else {
                                    Icons.Default.PlayCircle
                                },
                                contentDescription = null,
                                tint = ObsidianBg
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (state.showTargetBoxOverlay) "Hide Scan Box" else "Show Scan Box",
                                color = ObsidianBg,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onStopService,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("stop_service_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.StopCircle,
                                contentDescription = null,
                                tint = CrimsonAlert
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop Overlay", color = CrimsonAlert)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionsAndEngineStatusCard(
    state: TranslatorAppState,
    hasOverlayPermission: Boolean,
    isAccessibilityEnabled: Boolean,
    hasNotificationPermission: Boolean,
    onGrantOverlay: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onGrantNotification: () -> Unit,
    onInstallHindiVoice: () -> Unit,
    onOpenSettingsSheet: () -> Unit
) {
    Surface(
        color = SlateSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Permissions, Draggable Scan Box & HD Hindi TTS",
                style = MaterialTheme.typography.titleMedium,
                color = ElectricCyan
            )

            // Row 1: AccessibilityService for reading text inside the target bounding box
            PermissionStatusRow(
                icon = Icons.Default.AccessibilityNew,
                title = "Game Screen Reader (AccessibilityService)",
                subtitle = if (isAccessibilityEnabled) {
                    "Active — Extracts game text strictly inside the Draggable Scan Box"
                } else {
                    "Enable 'GameVox Hindi' in Accessibility Settings to read other games"
                },
                isReady = isAccessibilityEnabled,
                actionLabel = if (isAccessibilityEnabled) "Enabled" else "Enable",
                onActionClick = onEnableAccessibility,
                testTag = "enable_accessibility_button"
            )

            // Row 2: SYSTEM_ALERT_WINDOW (Floating Overlay + Draggable Target Scan Box)
            val boxRect = state.targetBoxScreenRect
            val boxSubtitle = if (hasOverlayPermission) {
                if (boxRect != null && boxRect.width() > 0) {
                    "Granted — Draggable Scan Box active (${boxRect.width()}×${boxRect.height()} px)"
                } else {
                    "Granted — Floating Icon + Resizable Target Scan Box ready"
                }
            } else {
                "Required to draw the Floating Icon and Draggable Target Scan Box over games"
            }

            PermissionStatusRow(
                icon = Icons.Default.Layers,
                title = "Overlay & Resizable Scan Box (SYSTEM_ALERT_WINDOW)",
                subtitle = boxSubtitle,
                isReady = hasOverlayPermission,
                actionLabel = if (hasOverlayPermission) "Ready" else "Grant",
                onActionClick = onGrantOverlay,
                testTag = "grant_overlay_permission_button"
            )

            // Row 3: POST_NOTIFICATIONS (Android 13+)
            PermissionStatusRow(
                icon = Icons.Default.NotificationsActive,
                title = "Foreground Service Notification (Android 13+)",
                subtitle = if (hasNotificationPermission) {
                    "Granted — Persistent service notification active"
                } else {
                    "Optional — Control Pause/Stop from notification shade"
                },
                isReady = hasNotificationPermission,
                actionLabel = if (hasNotificationPermission) "Ready" else "Allow",
                onActionClick = onGrantNotification,
                testTag = "grant_notification_permission_button"
            )

            // Row 4: High-Quality Neural / Network Hindi TTS (hi-IN)
            val isTtsWorking = state.ttsStatus == TtsEngineStatus.READY ||
                state.ttsStatus == TtsEngineStatus.FALLBACK_VOICE_READY

            PermissionStatusRow(
                icon = Icons.Default.RecordVoiceOver,
                title = "HD Neural Voice (${state.activeVoiceName})",
                subtitle = "${state.ttsStatus.label} • Pitch ${state.ttsPitch}x • Rate ${state.ttsSpeechRate}x",
                isReady = isTtsWorking,
                actionLabel = if (state.ttsStatus == TtsEngineStatus.MISSING_HINDI_DATA) {
                    "Install Data"
                } else {
                    "Tune"
                },
                onActionClick = if (state.ttsStatus == TtsEngineStatus.MISSING_HINDI_DATA) {
                    onInstallHindiVoice
                } else {
                    onOpenSettingsSheet
                },
                testTag = "tts_status_action_button"
            )
        }
    }
}

@Composable
private fun PermissionStatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isReady: Boolean,
    actionLabel: String,
    onActionClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SlateSurfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                contentDescription = null,
                tint = if (isReady) NeonEmerald else SaffronGold,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = onActionClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag(testTag)
        ) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (isReady) NeonEmerald else ElectricCyan
            )
        }
    }
}

@Composable
private fun LiveOcrDebugMonitorCard(
    state: TranslatorAppState,
    onSpeakCurrentHindi: () -> Unit,
    onOpenSettingsSheet: () -> Unit
) {
    Surface(
        color = SlateSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ElectricCyan.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Hindi Translation Box",
                    style = MaterialTheme.typography.titleMedium,
                    color = ElectricCyan
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianBg)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.lastTranslatedHindi,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.testTag("debug_translated_hindi_text")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSpeakCurrentHindi,
                    colors = ButtonDefaults.buttonColors(containerColor = SaffronGold),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("speak_current_hindi_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Speak Hindi Out Loud",
                        tint = ObsidianBg,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Speak Hindi Now",
                        color = ObsidianBg,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onOpenSettingsSheet,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("card_open_settings_button")
                ) {
                    Text("Pitch ${state.ttsPitch}x / Speed ${state.ttsSpeechRate}x")
                }
            }
        }
    }
}

@Composable
private fun SubtitleHistoryItemCard(
    entry: SubtitleTranslationEntry,
    onReplayHindi: () -> Unit
) {
    Surface(
        color = SlateSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = entry.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = if (entry.wasSpoken) {
                            NeonEmerald.copy(alpha = 0.18f)
                        } else {
                            SaffronGold.copy(alpha = 0.18f)
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = entry.skippedReason ?: "Spoken (hi-IN)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.wasSpoken) NeonEmerald else SaffronGold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = entry.extractedEnglish,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.translatedHindi,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            IconButton(
                onClick = onReplayHindi,
                modifier = Modifier.testTag("replay_history_item_${entry.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Replay Hindi audio",
                    tint = ElectricCyan
                )
            }
        }
    }
}

private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
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
