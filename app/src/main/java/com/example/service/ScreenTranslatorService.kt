package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.TranslatorStateRepository
import com.example.engine.BitmapInverter
import com.example.engine.OcrTranslationPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service managing:
 * 1. WindowManager Draggable Floating Circular Icon + Live Debug TextView below it
 * 2. Continuous 2-second Game Subtitle Extraction via:
 *    - Priority A: [GameVoxAccessibilityService] node text extraction
 *    - Priority B: [GameVoxAccessibilityService] Android 11+ screenshot + ColorMatrix OCR
 *    - Priority C: [MediaProjection] VirtualDisplay + ColorMatrix OCR
 *    - Priority D: Built-in game dialogue simulator fallback
 * 3. Shared Singleton [OcrTranslationPipeline] for Hindi translation + Android TextToSpeech (hi-IN)
 */
class ScreenTranslatorService : Service() {

    companion object {
        private const val TAG = "ScreenTranslatorService"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "gamevox_hindi_capture_channel"

        const val ACTION_START_WITH_PROJECTION = "com.example.action.START_WITH_PROJECTION"
        const val ACTION_START_DEMO_OVERLAY = "com.example.action.START_DEMO_OVERLAY"
        const val ACTION_TOGGLE_SCANNING = "com.example.action.TOGGLE_SCANNING"
        const val ACTION_PROCESS_SIMULATED_FRAME = "com.example.action.PROCESS_SIMULATED_FRAME"
        const val ACTION_TEST_TTS = "com.example.action.TEST_TTS"
        const val ACTION_REFRESH_TTS = "com.example.action.REFRESH_TTS"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SERVICE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SIMULATED_SPEAKER = "extra_simulated_speaker"
        const val EXTRA_SIMULATED_SUBTITLE = "extra_simulated_subtitle"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var captureLoopJob: Job? = null

    private lateinit var pipeline: OcrTranslationPipeline
    private var overlayController: FloatingOverlayController? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val imageReaderHandler = Handler(Looper.getMainLooper())

    private var screenWidth: Int = 720
    private var screenHeight: Int = 1280
    private var screenDensityDpi: Int = DisplayMetrics.DENSITY_HIGH

    private var simulatedPresetIndex = 0
    private val builtInGameDialogues = listOf(
        "Cybernetic Guard" to "Alert! Target secured in Sector 7. Engage protocols.",
        "Ancient Dragon" to "You dare challenge the keeper of the eternal flame?",
        "Squad Leader" to "Hold your position! Reinforcements arriving in thirty seconds.",
        "Mystic Oracle" to "Seek the hidden temple beyond the crimson mountains before dawn.",
        "System Terminal" to "Security override complete. Main vault door unlocked."
    )

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            teardownVirtualDisplay()
        }
    }

    override fun onCreate() {
        super.onCreate()
        TranslatorStateRepository.loadPreferences(applicationContext)
        createNotificationChannel()
        computeCaptureMetrics()

        pipeline = OcrTranslationPipeline.getInstance(applicationContext)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        overlayController = FloatingOverlayController(
            context = applicationContext,
            onSingleTapToggleCapture = { handleToggleCapture() },
            onTestHindiSpeech = {
                pipeline.speakHindiNonBlocking(
                    "नमस्ते गेमर! गेमवॉक्स हिंदी सबटाइटल अनुवादक तैयार है।",
                    forceSpeakEvenIfRepeat = true
                )
            },
            onInstallHindiVoiceData = {
                pipeline.promptInstallHindiTtsData(applicationContext)
            },
            onRefreshTtsCheck = {
                pipeline.refreshTtsConfiguration()
            },
            onStopServiceRequested = {
                stopTranslatorServiceCleanly()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_DEMO_OVERLAY

        when (action) {
            ACTION_STOP_SERVICE -> {
                stopTranslatorServiceCleanly()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_SCANNING -> {
                handleToggleCapture()
                return START_STICKY
            }

            ACTION_TEST_TTS -> {
                pipeline.refreshTtsConfiguration()
                pipeline.speakHindiNonBlocking(
                    "नमस्ते गेमर! आपकी हिंदी आवाज की पिच और गति सेट हो गई है।",
                    forceSpeakEvenIfRepeat = true
                )
                return START_STICKY
            }

            ACTION_REFRESH_TTS -> {
                pipeline.refreshTtsConfiguration()
                return START_STICKY
            }

            ACTION_PROCESS_SIMULATED_FRAME -> {
                val speaker = intent?.getStringExtra(EXTRA_SIMULATED_SPEAKER).orEmpty()
                val subtitle = intent?.getStringExtra(EXTRA_SIMULATED_SUBTITLE).orEmpty()
                if (subtitle.isNotBlank()) {
                    serviceScope.launch {
                        val sampleBitmap = BitmapInverter.createSampleGameSubtitleBitmap(speaker, subtitle)
                        pipeline.processCapturedFrame(
                            rawBitmap = sampleBitmap,
                            excludeOverlayRect = null,
                            onDebugTextUpdate = { eng, hin, note ->
                                overlayController?.updateDebugReadout(eng, hin, note)
                            }
                        )
                    }
                }
                return START_STICKY
            }

            ACTION_START_WITH_PROJECTION -> {
                promoteToForeground(isScanning = true, withMediaProjectionType = true)

                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    ?: Activity.RESULT_CANCELED
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                overlayController?.showFloatingOverlay()

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    setupMediaProjection(resultCode, resultData)
                    TranslatorStateRepository.setServiceRunning(running = true, isOverlayDemo = false)
                    TranslatorStateRepository.setCapturingOcr(true)
                    overlayController?.updateScanningState(true)
                    startCaptureLoop()
                } else {
                    TranslatorStateRepository.setServiceRunning(running = true, isOverlayDemo = true)
                }
            }

            ACTION_START_DEMO_OVERLAY -> {
                promoteToForeground(isScanning = false, withMediaProjectionType = false)
                overlayController?.showFloatingOverlay()
                TranslatorStateRepository.setServiceRunning(running = true, isOverlayDemo = true)
                TranslatorStateRepository.setCapturingOcr(false)
                overlayController?.updateScanningState(false)
            }
        }

        return START_STICKY
    }

    private fun handleToggleCapture() {
        val nowScanning = TranslatorStateRepository.toggleCapturingOcr()
        overlayController?.updateScanningState(nowScanning)
        updateNotification(nowScanning)

        if (nowScanning) {
            // Reset anti-repeat when the user explicitly taps the floating icon to start scanning
            // so the very first subtitle on screen is immediately spoken out loud!
            pipeline.resetAntiRepeatMemory()
            startCaptureLoop()
        } else {
            stopCaptureLoop()
        }
    }

    private fun startCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = serviceScope.launch {
            while (isActive && TranslatorStateRepository.state.value.isCapturingOcr) {
                val intervalMs = TranslatorStateRepository.state.value.captureIntervalMs
                try {
                    val a11yService = GameVoxAccessibilityService.instance
                    val overlayRect = overlayController?.getOverlayScreenBounds()

                    // Priority 1: Direct AccessibilityNodeInfo text from active game window
                    val nodeText = a11yService?.extractVisibleGameText().orEmpty()
                    if (nodeText.isNotBlank()) {
                        pipeline.processExtractedRawText(
                            rawEnglishText = nodeText,
                            onDebugTextUpdate = { eng, hin, note ->
                                overlayController?.updateDebugReadout(eng, hin, note)
                            }
                        )
                    } else {
                        // Priority 2: MediaProjection frame OR Android 11+ Accessibility takeScreenshot
                        val capturedBitmap = acquireLatestScreenBitmap()
                            ?: a11yService?.captureScreenBitmapViaAccessibility()

                        if (capturedBitmap != null) {
                            pipeline.processCapturedFrame(
                                rawBitmap = capturedBitmap,
                                excludeOverlayRect = overlayRect,
                                onDebugTextUpdate = { eng, hin, note ->
                                    overlayController?.updateDebugReadout(eng, hin, note)
                                }
                            )
                        } else {
                            // Priority 3: Interactive Game Subtitle Simulator frame so tapping
                            // the floating icon always reads & speaks Hindi dialogue out loud!
                            val (speaker, line) = builtInGameDialogues[simulatedPresetIndex % builtInGameDialogues.size]
                            simulatedPresetIndex++
                            val simBitmap = BitmapInverter.createSampleGameSubtitleBitmap(speaker, line)
                            pipeline.processCapturedFrame(
                                rawBitmap = simBitmap,
                                excludeOverlayRect = null,
                                onDebugTextUpdate = { eng, hin, note ->
                                    overlayController?.updateDebugReadout(eng, hin, note)
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error in 2-second screen capture OCR loop", e)
                }
                delay(intervalMs)
            }
        }
    }

    private fun stopCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = null
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            teardownVirtualDisplay()
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()

            val projection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                Log.w(TAG, "getMediaProjection returned null")
                return
            }

            projection.registerCallback(projectionCallback, imageReaderHandler)
            mediaProjection = projection

            val reader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = reader

            virtualDisplay = projection.createVirtualDisplay(
                "GameVoxHindiScreenCapture",
                screenWidth,
                screenHeight,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                imageReaderHandler
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup MediaProjection VirtualDisplay", e)
        }
    }

    private fun acquireLatestScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: return null
            val width = image.width
            val height = image.height
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val paddedWidth = width + rowPadding / pixelStride
            val fullBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            fullBitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                fullBitmap
            } else {
                val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
                fullBitmap.recycle()
                cropped
            }
        } catch (e: Exception) {
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
        val metrics = resources.displayMetrics
        screenDensityDpi = metrics.densityDpi

        val rawW: Int
        val rawH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            rawW = bounds.width()
            rawH = bounds.height()
        } else {
            rawW = metrics.widthPixels
            rawH = metrics.heightPixels
        }

        val maxDim = 960
        if (rawW > maxDim) {
            val scale = maxDim.toFloat() / rawW.toFloat()
            screenWidth = maxDim
            screenHeight = (rawH * scale).toInt().coerceAtLeast(360)
        } else {
            screenWidth = rawW.coerceAtLeast(360)
            screenHeight = rawH.coerceAtLeast(360)
        }
    }

    private fun promoteToForeground(isScanning: Boolean, withMediaProjectionType: Boolean) {
        val notification = buildForegroundNotification(isScanning)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && withMediaProjectionType) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {
            }
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
            action = ACTION_TOGGLE_SCANNING
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
                if (isScanning) "Pause OCR" else "Start OCR",
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
                description = "Persistent status for GameVox Hindi floating subtitle OCR & TTS"
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
        overlayController?.destroyOverlay()
        teardownVirtualDisplay()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {
        }
        TranslatorStateRepository.setServiceRunning(running = false, isOverlayDemo = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCaptureLoop()
        overlayController?.destroyOverlay()
        teardownVirtualDisplay()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        TranslatorStateRepository.setServiceRunning(running = false, isOverlayDemo = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
