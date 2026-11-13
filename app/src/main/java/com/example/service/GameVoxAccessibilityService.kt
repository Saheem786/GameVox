package com.example.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.TranslatorStateRepository
import com.example.engine.OcrTranslationPipeline
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * AccessibilityService that extracts text strictly from inside the user's Draggable & Resizable
 * Target Bounding Box ([targetRegionRect]) in two ways:
 * 1. Direct Accessibility Node Hierarchy traversal ([extractVisibleGameText]) filtering nodes
 *    whose screen bounds (`node.getBoundsInScreen`) intersect the user's Target Scan Box!
 * 2. Android 11+ (API 30+) [takeScreenshot] OCR capture cropped strictly to the Target Scan Box!
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

        val eventPkg = event.packageName?.toString().orEmpty()
        if (eventPkg == packageName) return

        val now = System.currentTimeMillis()
        if (now - lastAutoEventScanTimeMs < 1800L) return
        lastAutoEventScanTimeMs = now

        val targetBoxRect = state.targetBoxScreenRect
        val extractedNodeText = extractVisibleGameText(targetBoxRect)
        if (extractedNodeText.isNotBlank()) {
            a11yScope.launch {
                pipeline.processExtractedRawText(extractedNodeText)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = resources.displayMetrics
            val rawW = metrics.widthPixels
            val rawH = metrics.heightPixels
            a11yScope.launch {
                val screenBitmap = captureScreenBitmapViaAccessibility()
                if (screenBitmap != null) {
                    pipeline.processCapturedFrame(
                        rawBitmap = screenBitmap,
                        targetRegionScreenRect = targetBoxRect,
                        rawScreenWidth = rawW,
                        rawScreenHeight = rawH
                    )
                }
            }
        }
    }

    /**
     * Traverses the active window's [AccessibilityNodeInfo] tree and returns visible game/dialogue text
     * ONLY from nodes whose screen coordinates intersect [targetBoxRect] (when the Scan Box is active).
     */
    fun extractVisibleGameText(targetBoxRect: Rect? = TranslatorStateRepository.state.value.targetBoxScreenRect): String {
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
        collectNodeTextRecursive(rootNode, targetBoxRect, collectedLines, depth = 0)

        return collectedLines
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .takeLast(4)
            .joinToString(" ")
    }

    private fun collectNodeTextRecursive(
        node: AccessibilityNodeInfo?,
        targetBoxRect: Rect?,
        outList: MutableList<String>,
        depth: Int
    ) {
        if (node == null || depth > 18) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val candidate = if (text.length >= 2) text else if (desc.length >= 3) desc else ""

            if (candidate.isNotEmpty()) {
                val isInsideTargetBox = if (targetBoxRect != null && !targetBoxRect.isEmpty) {
                    val nodeBounds = Rect()
                    node.getBoundsInScreen(nodeBounds)
                    !nodeBounds.isEmpty && Rect.intersects(targetBoxRect, nodeBounds)
                } else {
                    true
                }

                if (isInsideTargetBox) {
                    outList.add(candidate)
                }
            }
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            collectNodeTextRecursive(node.getChild(i), targetBoxRect, outList, depth + 1)
        }
    }

    /**
     * Captures the screen using Android 11+ (API 30+) [takeScreenshot] without requiring
     * any MediaProjection consent dialog, then converts the HardwareBuffer to a software
     * ARGB_8888 [Bitmap] for target-box cropping, grayscale + color-inversion, and ML Kit OCR.
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
