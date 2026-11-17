package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.DubbingDeliveryMode
import com.example.data.TargetLanguage
import com.example.data.TranslatorStateRepository
import com.example.engine.ImageProcessor
import com.example.engine.OcrTranslationPipeline
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service managing real-time game screen subtitle OCR & modern Hindi dubbing:
 * 1. Compact draggable floating control + bottom-anchored resizable subtitle detection region.
 * 2. MediaProjection VirtualDisplay created AT MOST ONCE per MediaProjection instance (Android 14+ safe).
 *    On rotation/display geometry change, calls `VirtualDisplay.resize(...)` + `setSurface(newReader.surface)`
 *    and safely closes the previous ImageReader; falls back cleanly to requesting fresh consent if impossible.
 * 3. Full-resolution screen capture (up to 2560px longest side) cropped cleanly to the subtitle ROI before OCR,
 *    with single-frame-in-flight protection, adaptive polling interval, and deterministic Bitmap recycling.
 * 4. Protected screen (`FLAG_SECURE`) detection & immediate queue/TTS flush on pause or stop.
 */
class ScreenTranslatorService : Service() {

    companion object {
        private const val TAG = "ScreenTranslatorService"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "gamevox_hindi_capture_channel"
        private const val MAX_CAPTURE_DIMENSION_PX = 2560

        const val ACTION_START_WITH_PROJECTION = "com.example.action.START_WITH_PROJECTION"
        const val ACTION_TOGGLE_DUBBING = "com.example.action.TOGGLE_DUBBING"
        const val ACTION_TOGGLE_SCANNING = "com.example.action.TOGGLE_SCANNING"
        const val ACTION_TOGGLE_TARGET_BOX = "com.example.action.TOGGLE_TARGET_BOX"
        const val ACTION_UPDATE_SUBTITLE_VISIBILITY = "com.example.action.UPDATE_SUBTITLE_VISIBILITY"
        const val ACTION_INIT_TTS = "com.example.action.INIT_TTS"
        const val ACTION_DOWNLOAD_VOICE_DATA = "com.example.action.DOWNLOAD_VOICE_DATA"
        const val ACTION_TEST_TTS = "com.example.action.TEST_TTS"
        const val ACTION_REFRESH_TTS = "com.example.action.REFRESH_TTS"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SERVICE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_OPEN_SYSTEM_TTS_INSTALLER = "extra_open_system_tts_installer"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var captureLoopJob: Job? = null
    private val isFrameInFlight = AtomicBoolean(false)

    private lateinit var pipeline: OcrTranslationPipeline
    private lateinit var ttsManager: ForegroundTtsManager
    private var overlayController: FloatingOverlayController? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var displayManager: DisplayManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val imageReaderHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var hasCreatedVirtualDisplayForProjection: Boolean = false

    private var rawScreenWidth: Int = 1080
    private var rawScreenHeight: Int = 2400
    private var captureWidth: Int = 1080
    private var captureHeight: Int = 2400
    private var lastDisplayRotation: Int = Surface.ROTATION_0
    private var screenDensityDpi: Int = DisplayMetrics.DENSITY_HIGH
    private var consecutiveProtectedBlackFrames: Int = 0
    private var isDisplayListenerRegistered: Boolean = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val prevW = rawScreenWidth
            val prevH = rawScreenHeight
            val prevRot = lastDisplayRotation
            computeCaptureMetrics()
            if (prevW != rawScreenWidth || prevH != rawScreenHeight || prevRot != lastDisplayRotation) {
                handleDisplayGeometryChanged(alreadyComputedMetrics = true)
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system/user — clearing session")
            serviceScope.launch(Dispatchers.Main) {
                hasCreatedVirtualDisplayForProjection = false
                teardownVirtualDisplay()
                mediaProjection = null
                consecutiveProtectedBlackFrames = 0
                stopCaptureLoop()
                pipeline.stopAndClearQueue()
                TranslatorStateRepository.setCapturingOcr(false)
                TranslatorStateRepository.updateCaptureTelemetry(
                    hasActiveProjection = false,
                    isProtectedScreen = false,
                    statusMessage = "Capture ended — tap floating icon to grant fresh permission"
                )
                overlayController?.updateScanningState(false)
                updateNotification(isScanning = false)
            }
        }
    }

    private val serviceVoiceDataListener = object : ForegroundTtsManager.VoiceDataDownloadListener {
        override fun onHighQualityNetworkVoiceAvailable(
            language: TargetLanguage,
            voiceName: String,
            quality: Int
        ) {
            updateNotification(TranslatorStateRepository.state.value.isCapturingOcr)
        }

        override fun onVoiceDataMissing(
            language: TargetLanguage,
            missingVoiceNames: List<String>,
            reason: String
        ) {
            updateNotification(TranslatorStateRepository.state.value.isCapturingOcr)
            ttsManager.startAsyncLanguageDataDownloadWatcher(
                targetLang = language,
                speakConfirmationWhenReady = false
            )
        }

        override fun onVoiceDataDownloadStarted(language: TargetLanguage) {
            updateNotification(TranslatorStateRepository.state.value.isCapturingOcr)
        }

        override fun onVoiceDataInstalled(
            language: TargetLanguage,
            installedVoiceName: String
        ) {
            updateNotification(TranslatorStateRepository.state.value.isCapturingOcr)
        }
    }

    override fun onCreate() {
        super.onCreate()
        com.example.AppContextHolder.set(applicationContext)
        TranslatorStateRepository.loadPreferences(applicationContext)
        serviceScope.launch {
            TranslatorStateRepository.loadCloudTtsFromDataStore(applicationContext)
        }
        createNotificationChannel()

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        computeCaptureMetrics()
        try {
            displayManager?.registerDisplayListener(displayListener, imageReaderHandler)
            isDisplayListenerRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register DisplayListener", e)
        }

        ttsManager = ForegroundTtsManager.getInstance(applicationContext)
        ttsManager.addVoiceDataDownloadListener(serviceVoiceDataListener)
        ttsManager.registerTtsDataBroadcastReceiver()
        ttsManager.initializeAsync()
        ttsManager.prewarmNeuralEngineIfReady()

        pipeline = OcrTranslationPipeline.getInstance(applicationContext)
        pipeline.ensureTranslationModelDownloaded(downloadIfMissing = true)

        overlayController = FloatingOverlayController(
            context = applicationContext,
            onStartStopDubbingToggled = {
                handleSingleTapStartStopDubbing()
            },
            onSubtitleVisibilityToggled = { visible ->
                TranslatorStateRepository.setSubtitleVisible(applicationContext, visible)
            },
            onBoxVisibilityToggled = { visible ->
                overlayController?.updateScanningState(TranslatorStateRepository.state.value.isCapturingOcr)
                if (visible) {
                    pipeline.resetAntiRepeatMemory()
                }
            },
            onVoiceMuteToggled = { muted ->
                if (muted) {
                    pipeline.stopAndClearQueue()
                } else {
                    val latestHindi = TranslatorStateRepository.state.value.lastTranslatedHindi
                    if (latestHindi.isNotBlank() && latestHindi != "सबटायटल बॉक्स को गेम टेक्स्ट पर रखें...") {
                        pipeline.speakHindiNonBlocking(latestHindi, forceSpeakEvenIfRepeat = true)
                    }
                }
                updateNotification(TranslatorStateRepository.state.value.isCapturingOcr)
            },
            onSnapTargetBoxBottom = {
                pipeline.resetAntiRepeatMemory()
            },
            onTestHindiSpeech = {
                val greeting = TranslatorStateRepository.state.value.targetLanguage.sampleGreeting
                pipeline.speakHindiNonBlocking(greeting, forceSpeakEvenIfRepeat = true)
            },
            onInstallHindiVoiceData = {
                ttsManager.requestAsyncLanguageDataDownload(
                    context = applicationContext,
                    openSystemInstaller = true
                )
            },
            onRefreshTtsCheck = {
                ttsManager.refreshVoiceConfiguration()
            },
            onStopServiceRequested = {
                stopTranslatorServiceCleanly()
            }
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleDisplayGeometryChanged(alreadyComputedMetrics = false)
    }

    private fun handleDisplayGeometryChanged(alreadyComputedMetrics: Boolean) {
        if (!alreadyComputedMetrics) {
            computeCaptureMetrics()
        }
        overlayController?.onScreenOrientationChanged()
        pipeline.imageProcessor.resetFrameDiffCache()
        if (mediaProjection != null) {
            resizeOrRefreshVirtualDisplay()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_TOGGLE_DUBBING

        when (action) {
            ACTION_STOP_SERVICE -> {
                stopTranslatorServiceCleanly()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_DUBBING -> {
                handleSingleTapStartStopDubbing()
                return START_STICKY
            }

            ACTION_TOGGLE_SCANNING -> {
                handleSingleTapStartStopDubbing()
                return START_STICKY
            }

            ACTION_TOGGLE_TARGET_BOX -> {
                val showBox = TranslatorStateRepository.state.value.showTargetBoxOverlay
                overlayController?.setScanBoxVisibility(showBox, notifyCallback = true)
                return START_STICKY
            }

            ACTION_UPDATE_SUBTITLE_VISIBILITY -> {
                val showCaptions = TranslatorStateRepository.state.value.isSubtitleVisible
                overlayController?.setSubtitleCaptionsVisibility(showCaptions)
                return START_STICKY
            }

            ACTION_INIT_TTS -> {
                ttsManager.initializeAsync(forceRebind = true)
                return START_STICKY
            }

            ACTION_DOWNLOAD_VOICE_DATA -> {
                val openInstaller = intent?.getBooleanExtra(EXTRA_OPEN_SYSTEM_TTS_INSTALLER, true) ?: true
                ttsManager.requestAsyncLanguageDataDownload(
                    context = applicationContext,
                    openSystemInstaller = openInstaller
                )
                return START_STICKY
            }

            ACTION_TEST_TTS -> {
                ttsManager.refreshVoiceConfiguration()
                val greeting = TranslatorStateRepository.state.value.targetLanguage.sampleGreeting
                pipeline.speakHindiNonBlocking(greeting, forceSpeakEvenIfRepeat = true)
                return START_STICKY
            }

            ACTION_REFRESH_TTS -> {
                ttsManager.refreshVoiceConfiguration()
                return START_STICKY
            }

            ACTION_START_WITH_PROJECTION -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    ?: Activity.RESULT_CANCELED
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    TranslatorStateRepository.updateCaptureTelemetry(
                        hasActiveProjection = false,
                        isProtectedScreen = false,
                        statusMessage = "Screen capture permission required"
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                val promoted = promoteToForeground(isScanning = true)
                if (!promoted) {
                    TranslatorStateRepository.updateCaptureTelemetry(
                        hasActiveProjection = false,
                        statusMessage = "Foreground MediaProjection permission blocked by OS"
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                TranslatorStateRepository.updateShowTargetBoxOverlay(applicationContext, true)
                overlayController?.showFloatingOverlay()

                val projectionReady = setupMediaProjection(resultCode, resultData)
                TranslatorStateRepository.setServiceRunning(
                    running = true,
                    isOverlayDemo = false,
                    hasActiveProjection = projectionReady
                )
                handleDubbingStateChanged(projectionReady)
                return START_STICKY
            }
        }

        return START_STICKY
    }

    private fun handleSingleTapStartStopDubbing() {
        val state = TranslatorStateRepository.state.value
        if (state.isCapturingOcr) {
            handleDubbingStateChanged(false)
            TranslatorStateRepository.updateCaptureTelemetry(
                hasActiveProjection = mediaProjection != null,
                isProtectedScreen = false,
                statusMessage = "Scanning OFF — tap floating icon to resume"
            )
            overlayController?.showGestureFeedbackBadge("Scanning OFF")
            return
        }

        if (mediaProjection != null && virtualDisplay != null) {
            handleDubbingStateChanged(true)
            TranslatorStateRepository.updateCaptureTelemetry(
                hasActiveProjection = true,
                isProtectedScreen = false,
                statusMessage = "Scanning ON — Live OCR dubbing active"
            )
            overlayController?.showGestureFeedbackBadge("Scanning ON")
        } else {
            // Fresh per-session MediaProjection consent required before scanning
            requestFreshMediaProjectionConsent()
        }
    }

    private fun requestFreshMediaProjectionConsent() {
        TranslatorStateRepository.updateCaptureTelemetry(
            hasActiveProjection = false,
            statusMessage = "Requesting fresh screen capture permission..."
        )
        val consentIntent = Intent(applicationContext, MediaProjectionConsentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        try {
            startActivity(consentIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to launch MediaProjectionConsentActivity", e)
        }
    }

    private fun handleDubbingStateChanged(active: Boolean) {
        consecutiveProtectedBlackFrames = 0
        TranslatorStateRepository.setCapturingOcr(active)
        overlayController?.updateScanningState(active)
        updateNotification(active)

        if (active) {
            pipeline.resetAntiRepeatMemory()
            overlayController?.resetSubtitleCaptionSequence(pipeline.currentEpoch())
            startCaptureLoop()
        } else {
            stopCaptureLoop()
            pipeline.stopAndClearQueue()
        }
    }

    private fun startCaptureLoop() {
        captureLoopJob?.cancel()
        isFrameInFlight.set(false)
        captureLoopJob = serviceScope.launch {
            while (isActive && TranslatorStateRepository.state.value.isCapturingOcr) {
                val stateNow = TranslatorStateRepository.state.value
                val baseIntervalMs = stateNow.captureIntervalMs.coerceIn(700L, 5000L)
                var nextDelayMs = baseIntervalMs

                if (!isFrameInFlight.compareAndSet(false, true)) {
                    delay(120L)
                    continue
                }

                try {
                    val a11yService = GameVoxAccessibilityService.instance
                    val overlayRect = overlayController?.getOverlayScreenBounds()
                    val excludeRects = overlayController?.getAllOverlayExclusionBounds()
                        .orEmpty()
                        .ifEmpty { listOfNotNull(overlayRect) }
                    val targetBoxRect = overlayController?.getTargetBoxScreenBounds()
                        ?: stateNow.targetBoxScreenRect

                    val captureStartMs = System.currentTimeMillis()
                    val capturedBitmap = acquireLatestScreenBitmap()
                        ?: a11yService?.captureScreenBitmapViaAccessibility()
                    val captureLatencyMs = System.currentTimeMillis() - captureStartMs

                    if (capturedBitmap != null) {
                        try {
                            val isAllBlackFrame = ImageProcessor.isLikelyProtectedOrBlackFrame(capturedBitmap)
                            if (isAllBlackFrame) {
                                consecutiveProtectedBlackFrames++
                                if (consecutiveProtectedBlackFrames >= 4) {
                                    TranslatorStateRepository.updateCaptureTelemetry(
                                        hasActiveProjection = mediaProjection != null,
                                        isProtectedScreen = true,
                                        statusMessage = "Protected screen blocked capture (FLAG_SECURE)"
                                    )
                                    overlayController?.updateProtectedScreenBadge(
                                        protected = true,
                                        message = "Protected screen (FLAG_SECURE) blocked capture"
                                    )
                                }
                                nextDelayMs = (baseIntervalMs * 1.4f).toLong().coerceAtMost(2200L)
                            } else {
                                if (consecutiveProtectedBlackFrames > 0 || TranslatorStateRepository.state.value.isProtectedScreenDetected) {
                                    consecutiveProtectedBlackFrames = 0
                                    TranslatorStateRepository.updateCaptureTelemetry(
                                        hasActiveProjection = mediaProjection != null,
                                        isProtectedScreen = false,
                                        statusMessage = "Live OCR dubbing active"
                                    )
                                    overlayController?.updateProtectedScreenBadge(protected = false)
                                }

                                val (liveW, liveH) = overlayController?.getLiveScreenSize()
                                    ?: (rawScreenWidth to rawScreenHeight)

                                val (_, translated) = pipeline.processCapturedFrame(
                                    rawBitmap = capturedBitmap,
                                    excludeOverlayRect = overlayRect,
                                    excludeOverlayRects = excludeRects,
                                    targetRegionScreenRect = targetBoxRect,
                                    rawScreenWidth = liveW,
                                    rawScreenHeight = liveH,
                                    skipIfRegionUnchanged = true,
                                    captureLatencyMs = captureLatencyMs,
                                    frameTimestampMs = captureStartMs,
                                    enforceActiveSession = true,
                                    onAlignedDebugTextUpdate = { ev, hin, note ->
                                        overlayController?.updateDebugReadout(
                                            englishText = ev.spokenDialogue,
                                            hindiText = hin,
                                            statusNote = note,
                                            sequenceId = ev.sequenceId,
                                            sessionEpoch = ev.sessionEpoch
                                        )
                                    }
                                )

                                // Adaptive cadence: sub-300ms scan in Live Mode while awaiting next subtitle; paced while speaking
                                nextDelayMs = when {
                                    stateNow.dubbingDeliveryMode == DubbingDeliveryMode.LIVE_MODE &&
                                        !pipeline.isTtsSpeakingNow() && translated.isBlank() ->
                                        stateNow.adaptiveScanIntervalMs.coerceIn(220L, 450L)
                                    pipeline.isTtsSpeakingNow() || translated.isNotBlank() ->
                                        baseIntervalMs.coerceIn(550L, 950L)
                                    else ->
                                        stateNow.adaptiveScanIntervalMs.coerceIn(250L, 600L)
                                }
                            }
                        } finally {
                            if (!capturedBitmap.isRecycled) {
                                capturedBitmap.recycle()
                            }
                        }
                    } else {
                        // Fall back to Accessibility node text ONLY for apps that expose standard UI nodes
                        val nodeText = a11yService?.extractVisibleGameText(targetBoxRect).orEmpty()
                        if (nodeText.isNotBlank()) {
                            pipeline.processExtractedRawText(
                                rawEnglishText = nodeText,
                                enforceActiveSession = true,
                                frameTimestampMs = System.currentTimeMillis(),
                                onAlignedDebugTextUpdate = { ev, hin, note ->
                                    overlayController?.updateDebugReadout(
                                        englishText = ev.spokenDialogue,
                                        hindiText = hin,
                                        statusNote = note,
                                        sequenceId = ev.sequenceId,
                                        sessionEpoch = ev.sessionEpoch
                                    )
                                }
                            )
                        }
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (sec: SecurityException) {
                    Log.w(TAG, "SecurityException in screen capture loop", sec)
                    TranslatorStateRepository.updateCaptureTelemetry(
                        hasActiveProjection = false,
                        isProtectedScreen = true,
                        statusMessage = "Protected screen or revoked capture permission"
                    )
                    TranslatorStateRepository.updateDiagnosisMetrics(
                        lastPipelineError = "SecurityException: ${sec.localizedMessage ?: "revoked"}"
                    )
                    overlayController?.updateProtectedScreenBadge(
                        protected = true,
                        message = "Protected screen or revoked permission"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error in screen capture OCR loop", e)
                    TranslatorStateRepository.updateDiagnosisMetrics(
                        lastPipelineError = "Loop error: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    )
                } finally {
                    isFrameInFlight.set(false)
                }

                delay(nextDelayMs)
            }
        }
    }

    private fun stopCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = null
        isFrameInFlight.set(false)
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent): Boolean {
        return try {
            teardownVirtualDisplay()
            try {
                mediaProjection?.unregisterCallback(projectionCallback)
                mediaProjection?.stop()
            } catch (_: Exception) {
            }
            mediaProjection = null
            hasCreatedVirtualDisplayForProjection = false

            val projection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                Log.w(TAG, "getMediaProjection returned null")
                TranslatorStateRepository.updateCaptureTelemetry(
                    hasActiveProjection = false,
                    statusMessage = "Unable to start MediaProjection session"
                )
                return false
            }

            projection.registerCallback(projectionCallback, imageReaderHandler)
            mediaProjection = projection
            createVirtualDisplayOnce(projection)
            TranslatorStateRepository.updateCaptureTelemetry(
                hasActiveProjection = true,
                isProtectedScreen = false,
                statusMessage = "Live OCR dubbing active ($captureWidth x $captureHeight)"
            )
            true
        } catch (sec: SecurityException) {
            Log.e(TAG, "SecurityException starting MediaProjection", sec)
            hasCreatedVirtualDisplayForProjection = false
            TranslatorStateRepository.updateCaptureTelemetry(
                hasActiveProjection = false,
                isProtectedScreen = true,
                statusMessage = "Screen capture permission denied by system"
            )
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup MediaProjection VirtualDisplay", e)
            hasCreatedVirtualDisplayForProjection = false
            TranslatorStateRepository.updateCaptureTelemetry(
                hasActiveProjection = false,
                statusMessage = "Capture initialization error: ${e.message ?: "unknown"}"
            )
            false
        }
    }

    /**
     * Android 14+ MediaProjection allows `createVirtualDisplay()` AT MOST ONCE per MediaProjection instance.
     * On orientation/density changes, resize the EXISTING [VirtualDisplay] and swap a new [ImageReader.getSurface]
     * via [VirtualDisplay.setSurface], closing the old [ImageReader] cleanly. If resizing is impossible,
     * tear down the exhausted projection and request fresh user consent instead of calling createVirtualDisplay twice.
     */
    private fun resizeOrRefreshVirtualDisplay() {
        val vd = virtualDisplay
        val projection = mediaProjection
        if (vd == null || projection == null) {
            if (projection != null && hasCreatedVirtualDisplayForProjection) {
                handleExhaustedProjectionAfterRotation()
            }
            return
        }

        var newReader: ImageReader? = null
        try {
            val w = captureWidth.coerceAtLeast(320)
            val h = captureHeight.coerceAtLeast(320)
            newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            vd.resize(w, h, screenDensityDpi)
            vd.setSurface(newReader.surface)

            val oldReader = imageReader
            imageReader = newReader
            try {
                oldReader?.close()
            } catch (_: Exception) {
            }
            Log.i(TAG, "Resized existing VirtualDisplay cleanly to ${w}x${h}")
            TranslatorStateRepository.updateCaptureTelemetry(
                hasActiveProjection = true,
                isProtectedScreen = false,
                statusMessage = "Capture resized (${w}x${h})"
            )
        } catch (e: Exception) {
            Log.w(TAG, "VirtualDisplay resize/setSurface failed; tearing down and requesting fresh consent", e)
            try {
                newReader?.close()
            } catch (_: Exception) {
            }
            TranslatorStateRepository.updateDiagnosisMetrics(
                lastPipelineError = "Display resize requires fresh permission: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
            handleExhaustedProjectionAfterRotation()
        }
    }

    private fun handleExhaustedProjectionAfterRotation() {
        val wasScanning = TranslatorStateRepository.state.value.isCapturingOcr
        stopCaptureLoop()
        pipeline.stopAndClearQueue()
        teardownVirtualDisplay()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        hasCreatedVirtualDisplayForProjection = false
        TranslatorStateRepository.setCapturingOcr(false)
        TranslatorStateRepository.updateCaptureTelemetry(
            hasActiveProjection = false,
            isProtectedScreen = false,
            statusMessage = "Screen rotated — fresh capture permission needed"
        )
        overlayController?.updateScanningState(false)
        updateNotification(isScanning = false)
        if (wasScanning) {
            requestFreshMediaProjectionConsent()
        }
    }

    private fun createVirtualDisplayOnce(projection: MediaProjection) {
        check(!hasCreatedVirtualDisplayForProjection) {
            "createVirtualDisplay() must not be called twice on the same MediaProjection instance"
        }
        val w = captureWidth.coerceAtLeast(320)
        val h = captureHeight.coerceAtLeast(320)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "GameVoxHindiScreenCapture",
            w,
            h,
            screenDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            imageReaderHandler
        )
        hasCreatedVirtualDisplayForProjection = true
    }

    private fun acquireLatestScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: return null
            ImageProcessor.extractBitmapFromMediaImage(image)
        } catch (sec: SecurityException) {
            throw sec
        } catch (_: Exception) {
            null
        } finally {
            try {
                image?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun computeCaptureMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val dm = DisplayMetrics()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            screenDensityDpi = dm.densityDpi
            var w = dm.widthPixels.coerceAtLeast(360)
            var h = dm.heightPixels.coerceAtLeast(360)
            val rot = display.rotation
            lastDisplayRotation = rot
            val isLandscape = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
            if (isLandscape && w < h) {
                val tmp = w; w = h; h = tmp
            } else if (!isLandscape && w > h) {
                val tmp = w; w = h; h = tmp
            }
            rawScreenWidth = w
            rawScreenHeight = h
        } else {
            val metrics = resources.displayMetrics
            screenDensityDpi = metrics.densityDpi
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                rawScreenWidth = bounds.width().coerceAtLeast(360)
                rawScreenHeight = bounds.height().coerceAtLeast(360)
            } else {
                rawScreenWidth = metrics.widthPixels.coerceAtLeast(360)
                rawScreenHeight = metrics.heightPixels.coerceAtLeast(360)
            }
        }

        // Preserve full native fidelity on phones/tablets up to 2560px so small subtitles remain sharp before ROI crop
        if (rawScreenWidth > MAX_CAPTURE_DIMENSION_PX || rawScreenHeight > MAX_CAPTURE_DIMENSION_PX) {
            val longest = maxOf(rawScreenWidth, rawScreenHeight)
            val scale = MAX_CAPTURE_DIMENSION_PX.toFloat() / longest.toFloat()
            captureWidth = (rawScreenWidth * scale).toInt().coerceAtLeast(320)
            captureHeight = (rawScreenHeight * scale).toInt().coerceAtLeast(320)
        } else {
            captureWidth = rawScreenWidth
            captureHeight = rawScreenHeight
        }
    }

    private fun promoteToForeground(isScanning: Boolean): Boolean {
        val notification = buildForegroundNotification(isScanning)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (sec: SecurityException) {
            Log.e(TAG, "Failed startForeground with MEDIA_PROJECTION type", sec)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed startForeground", e)
            false
        }
    }

    private fun updateNotification(isScanning: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildForegroundNotification(isScanning))
    }

    private fun buildForegroundNotification(isScanning: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, ScreenTranslatorService::class.java).apply {
            action = ACTION_TOGGLE_DUBBING
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            101,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenTranslatorService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isScanning) {
            getString(R.string.notification_content_scanning)
        } else {
            getString(R.string.notification_content_paused)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_media_play,
                if (isScanning) "Pause Dubbing" else "Resume Dubbing",
                togglePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent status for GameVox live subtitle OCR & Hindi TTS"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun teardownVirtualDisplay() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {
        }
    }

    private fun stopTranslatorServiceCleanly() {
        stopCaptureLoop()
        pipeline.stopAndClearQueue()
        overlayController?.destroyOverlay()
        teardownVirtualDisplay()
        hasCreatedVirtualDisplayForProjection = false
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {
        }
        TranslatorStateRepository.setServiceRunning(
            running = false,
            isOverlayDemo = false,
            hasActiveProjection = false
        )
        TranslatorStateRepository.updateCaptureTelemetry(
            hasActiveProjection = false,
            isProtectedScreen = false,
            statusMessage = "Ready — tap Start to capture subtitles"
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCaptureLoop()
        if (::pipeline.isInitialized) {
            pipeline.stopAndClearQueue()
        }
        if (isDisplayListenerRegistered) {
            try {
                displayManager?.unregisterDisplayListener(displayListener)
            } catch (_: Exception) {
            }
            isDisplayListenerRegistered = false
        }
        if (::ttsManager.isInitialized) {
            ttsManager.removeVoiceDataDownloadListener(serviceVoiceDataListener)
        }
        overlayController?.destroyOverlay()
        teardownVirtualDisplay()
        hasCreatedVirtualDisplayForProjection = false
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        TranslatorStateRepository.setServiceRunning(
            running = false,
            isOverlayDemo = false,
            hasActiveProjection = false
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
