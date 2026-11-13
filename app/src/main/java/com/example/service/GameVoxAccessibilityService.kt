package com.example.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.TranslatorStateRepository
import com.example.engine.OcrTranslationPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * AccessibilityService that extracts text from other running games and apps in two ways:
 * 1. Direct Accessibility Node Hierarchy traversal ([extractVisibleGameText]) for games/apps
 *    that expose text or dialogue views.
 * 2. Android 11+ (API 30+) [takeScreenshot] OCR capture ([captureAndOcrScreen]) for OpenGL/Unity/Unreal
 *    games that render subtitles directly onto a canvas surface — requiring ZERO MediaProjection dialogs!
 */
class GameVoxAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GameVoxA11yService"

        @Volatile
        var instance: GameVoxAccessibilityService? = null
            private set
    }

    private val a11yScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var pipeline: OcrTranslationPipeline

    @Volatile
    private var lastAutoEventScanTimeMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        pipeline = OcrTranslationPipeline.getInstance(applicationContext)
        TranslatorStateRepository.setAccessibilityConnected(true)
        Log.i(TAG, "GameVoxAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val state = TranslatorStateRepository.state.value
        if (!state.isCapturingOcr) return

        // Ignore events originating from our own package so we never read our own UI/overlay in a loop
        val eventPkg = event.packageName?.toString().orEmpty()
        if (eventPkg == packageName) return

        val now = System.currentTimeMillis()
        if (now - lastAutoEventScanTimeMs < 1800L) return
        lastAutoEventScanTimeMs = now

        val extractedNodeText = extractVisibleGameText()
        if (extractedNodeText.isNotBlank()) {
            a11yScope.launch {
                pipeline.processExtractedRawText(extractedNodeText)
            }
        }
    }

    /**
     * Traverses the active window's AccessibilityNodeInfo tree and returns visible game/dialogue text,
     * ignoring system status bar and our own app package.
     */
    fun extractVisibleGameText(): String {
        val rootNode = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return ""

        val pkg = rootNode.packageName?.toString().orEmpty()
        if (pkg == packageName || pkg == "com.android.systemui") {
            return ""
        }

        val collectedLines = mutableListOf<String>()
        collectNodeTextRecursive(rootNode, collectedLines, depth = 0)

        return collectedLines
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
            .takeLast(3)
            .joinToString(" ")
    }

    private fun collectNodeTextRecursive(
        node: AccessibilityNodeInfo?,
        outList: MutableList<String>,
        depth: Int
    ) {
        if (node == null || depth > 18) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.length >= 3) {
                outList.add(text)
            } else if (desc.length >= 4) {
                outList.add(desc)
            }
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            collectNodeTextRecursive(node.getChild(i), outList, depth + 1)
        }
    }

    /**
     * Captures the screen using Android 11+ (API 30+) [takeScreenshot] without requiring
     * any MediaProjection consent dialog, then converts the HardwareBuffer to a software
     * ARGB_8888 [Bitmap] for ColorMatrix inversion and ML Kit OCR.
     */
    suspend fun captureScreenBitmapViaAccessibility(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hwBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap?.recycle()
                                hwBuffer.close()
                                if (continuation.isActive) {
                                    continuation.resume(softBitmap)
                                }
                            } catch (e: Exception) {
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Accessibility takeScreenshot returned error code: $errorCode")
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "GameVoxAccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        TranslatorStateRepository.setAccessibilityConnected(false)
        a11yScope.cancel()
        super.onDestroy()
    }
}
