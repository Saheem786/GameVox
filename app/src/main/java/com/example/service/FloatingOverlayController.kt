package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.example.data.DubbingDeliveryMode
import com.example.data.SpeakerGender
import com.example.data.TargetLanguage
import com.example.data.TranslationMode
import com.example.data.TranslationStyle
import com.example.data.TranslatorStateRepository
import com.example.data.VoiceProfile
import com.example.data.VoiceProfileRepository
import java.util.Locale
import kotlin.math.abs

/**
 * Compact Floating Overlay Controller for GameVox:
 *
 * 1. **Compact Draggable Floating Bubble (`rootOverlayView`)**:
 *    - **Single Tap (`onSingleTapConfirmed`)**: Strictly toggles Live Scanning & Dubbing ON/OFF cleanly
 *      without restarting an active MediaProjection session, showing a brief `"Scanning ON"` / `"Scanning OFF"` badge.
 *    - **Double Tap (`onDoubleTap`)**: Strictly toggles GameVox Hindi Subtitle Overlay visibility
 *      (`isSubtitleVisible`) while OCR, speaker detection, and dubbing continue uninterrupted.
 *    - **Long Press (`onLongPress`)**: Opens quick options sheet (Quick character/voice, settings, stop).
 *    - **Drag**: Dragging beyond `scaledTouchSlop` moves the bubble without firing tap, double-tap, or long-press.
 * 2. **Draggable & Resizable Subtitle Detection Region (`targetBoxView`)**.
 * 3. **Clean Hindi Translation Caption Box (`translationBoxView`)**: Detached & non-touch-blocking when hidden.
 */
class FloatingOverlayController(
    private val context: Context,
    private val onStartStopDubbingToggled: () -> Unit = {},
    private val onSubtitleVisibilityToggled: (Boolean) -> Unit = {},
    private val onBoxVisibilityToggled: (Boolean) -> Unit = {},
    private val onVoiceMuteToggled: (Boolean) -> Unit = {},
    private val onSnapTargetBoxBottom: () -> Unit = {},
    private val onTestHindiSpeech: () -> Unit = {},
    private val onInstallHindiVoiceData: () -> Unit = {},
    private val onRefreshTtsCheck: () -> Unit = {},
    private val onStopServiceRequested: () -> Unit = {}
) {
    private enum class BoxTouchMode {
        NONE,
        DRAG_MOVE,
        DRAG_RESIZE
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // 1. Minimal Circular Floating Icon
    private var rootOverlayView: FrameLayout? = null
    private var circularBubbleView: FrameLayout? = null
    private var bubbleLabelView: TextView? = null
    private var bubbleStatusDot: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    // 2. Draggable & Resizable Subtitle Detection Region
    private var targetBoxView: FrameLayout? = null
    private var targetBoxBorderDrawable: GradientDrawable? = null
    private var targetBoxResizeHandle: View? = null
    private var targetBoxParams: WindowManager.LayoutParams? = null
    private var lastKnownScreenWidth: Int = 0
    private var lastKnownScreenHeight: Int = 0

    // 3. Clean Hindi Translation Caption Box
    private var translationBoxView: TextView? = null
    private var translationBoxParams: WindowManager.LayoutParams? = null
    private var latestHindiOnlyText: String = ""

    // 4. Compact Non-Touchable Gesture Feedback Pill ("Scanning ON/OFF", "Hindi Subtitles Hidden/Shown")
    private var feedbackBannerView: TextView? = null
    private var feedbackBannerParams: WindowManager.LayoutParams? = null
    private val hideFeedbackRunnable = Runnable { hideGestureFeedbackBadge() }
    private var bubbleTouchListenerRef: View.OnTouchListener? = null

    @Volatile
    private var latestCaptionSequenceId: Long = 0L

    @Volatile
    private var latestCaptionSessionEpoch: Long = 0L

    @Volatile
    var isBoxVisible: Boolean = true
        private set

    @Volatile
    var isSubtitleVisible: Boolean = true
        private set

    @Volatile
    var isVoiceMuted: Boolean = false
        private set

    @Volatile
    private var bottomSheetOverlayView: FrameLayout? = null

    @Volatile
    private var isDialogOpeningOrShown: Boolean = false

    val isAttached: Boolean
        get() = rootOverlayView != null

    fun getLiveScreenSize(): Pair<Int, Int> {
        return try {
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(dm)
                var w = dm.widthPixels.coerceAtLeast(360)
                var h = dm.heightPixels.coerceAtLeast(360)
                val rotation = display.rotation
                val isLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
                if (isLandscape && w < h) {
                    val tmp = w
                    w = h
                    h = tmp
                } else if (!isLandscape && w > h) {
                    val tmp = w
                    w = h
                    h = tmp
                }
                w to h
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                bounds.width().coerceAtLeast(360) to bounds.height().coerceAtLeast(360)
            } else {
                val dm = context.resources.displayMetrics
                dm.widthPixels.coerceAtLeast(360) to dm.heightPixels.coerceAtLeast(360)
            }
        } catch (_: Exception) {
            val dm = context.resources.displayMetrics
            dm.widthPixels.coerceAtLeast(360) to dm.heightPixels.coerceAtLeast(360)
        }
    }

    private fun createUnrestrictedOverlayFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }

    private fun applyCutoutModeIfSupported(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun getOverlayScreenBounds(): Rect? {
        val view = rootOverlayView ?: return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        if (view.width <= 0 || view.height <= 0) return null
        return Rect(
            loc[0],
            loc[1],
            loc[0] + view.width,
            loc[1] + view.height
        )
    }

    fun getTranslationCaptionScreenBounds(): Rect? {
        val view = translationBoxView ?: return null
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(
            loc[0],
            loc[1],
            loc[0] + view.width,
            loc[1] + view.height
        )
    }

    fun getAllOverlayExclusionBounds(): List<Rect> {
        val rects = mutableListOf<Rect>()
        getOverlayScreenBounds()?.let { rects.add(it) }
        getTranslationCaptionScreenBounds()?.let { rects.add(it) }
        bottomSheetOverlayView?.let { sheet ->
            if (sheet.visibility == View.VISIBLE && sheet.width > 0 && sheet.height > 0) {
                val loc = IntArray(2)
                sheet.getLocationOnScreen(loc)
                rects.add(Rect(loc[0], loc[1], loc[0] + sheet.width, loc[1] + sheet.height))
            }
        }
        return rects
    }

    fun resetSubtitleCaptionSequence(sessionEpoch: Long) {
        latestCaptionSequenceId = 0L
        latestCaptionSessionEpoch = maxOf(sessionEpoch, latestCaptionSessionEpoch)
    }

    fun getTargetBoxScreenBounds(): Rect? {
        if (!isBoxVisible) return TranslatorStateRepository.state.value.targetBoxScreenRect
        val box = targetBoxView ?: return TranslatorStateRepository.state.value.targetBoxScreenRect
        val params = targetBoxParams
        val loc = IntArray(2)
        box.getLocationOnScreen(loc)
        val w = if (box.width > 0) box.width else (params?.width ?: 0)
        val h = if (box.height > 0) box.height else (params?.height ?: 0)
        if (w <= 0 || h <= 0) {
            return TranslatorStateRepository.state.value.targetBoxScreenRect
        }
        val left = if (loc[0] != 0 || loc[1] != 0) loc[0] else (params?.x ?: 0)
        val top = if (loc[0] != 0 || loc[1] != 0) loc[1] else (params?.y ?: 0)
        val rect = Rect(left, top, left + w, top + h)
        TranslatorStateRepository.updateTargetBoxScreenRect(rect)
        return rect
    }

    fun setVoiceMutedState(muted: Boolean, notifyCallback: Boolean = false) {
        mainHandler.post {
            isVoiceMuted = muted
            TranslatorStateRepository.setVoiceMuted(context, muted)
            updateFloatingIconAppearance(TranslatorStateRepository.state.value.isCapturingOcr)
            if (notifyCallback) {
                onVoiceMuteToggled(muted)
            }
        }
    }

    fun setScanBoxVisibility(visible: Boolean, notifyCallback: Boolean = false) {
        mainHandler.post {
            isBoxVisible = visible
            TranslatorStateRepository.updateShowTargetBoxOverlay(context, visible)
            if (visible) {
                showDraggableTargetBox()
            } else {
                hideDraggableTargetBox()
            }
            updateFloatingIconAppearance(TranslatorStateRepository.state.value.isCapturingOcr)
            if (notifyCallback) {
                onBoxVisibilityToggled(visible)
            }
        }
    }

    fun setSubtitleCaptionsVisibility(visible: Boolean) {
        isSubtitleVisible = visible
        TranslatorStateRepository.setSubtitleVisible(context, visible)
        val applyVisibility = {
            if (visible) {
                showHindiTranslationBox()
            } else {
                hideHindiTranslationBox()
            }
            updateFloatingIconAppearance(TranslatorStateRepository.state.value.isCapturingOcr)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyVisibility()
        } else {
            mainHandler.post(applyVisibility)
        }
    }

    fun showGestureFeedbackBadge(message: String) {
        TranslatorStateRepository.updateGestureFeedback(message)
        val displayAction = {
            if (Settings.canDrawOverlays(context)) {
                val (screenW, screenH) = getLiveScreenSize()
                val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val banner = feedbackBannerView ?: TextView(context).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(18).toFloat()
                        setColor(Color.argb(220, 15, 23, 42))
                        setStroke(dpToPx(1), Color.parseColor("#38BDF8"))
                    }
                    isClickable = false
                    isFocusable = false
                    maxWidth = (screenW * 0.8f).toInt().coerceAtLeast(dpToPx(200))
                }
                banner.text = message
                banner.visibility = View.VISIBLE
                val bParams = feedbackBannerParams ?: WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType,
                    createUnrestrictedOverlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (overlayParams?.y ?: dpToPx(96)).coerceIn(dpToPx(24), (screenH - dpToPx(80)).coerceAtLeast(dpToPx(24)))
                    applyCutoutModeIfSupported(this)
                }
                try {
                    if (feedbackBannerView == null) {
                        windowManager.addView(banner, bParams)
                        feedbackBannerView = banner
                        feedbackBannerParams = bParams
                    } else {
                        windowManager.updateViewLayout(banner, bParams)
                    }
                } catch (_: Exception) {
                }
            }
            mainHandler.removeCallbacks(hideFeedbackRunnable)
            mainHandler.postDelayed(hideFeedbackRunnable, 1800L)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            displayAction()
        } else {
            mainHandler.post { displayAction() }
        }
    }

    private fun hideGestureFeedbackBadge() {
        val view = feedbackBannerView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        } finally {
            feedbackBannerView = null
            feedbackBannerParams = null
        }
    }

    fun handleBubbleSingleTapConfirmed() {
        circularBubbleView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onStartStopDubbingToggled()
    }

    fun handleBubbleDoubleTap() {
        circularBubbleView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val newVisible = !isSubtitleVisible
        setSubtitleCaptionsVisibility(newVisible)
        onSubtitleVisibilityToggled(newVisible)
        showGestureFeedbackBadge(
            if (newVisible) "Hindi Subtitles Shown" else "Hindi Subtitles Hidden"
        )
    }

    fun dispatchBubbleTouchEventForTest(event: MotionEvent): Boolean {
        val view = circularBubbleView ?: return false
        return bubbleTouchListenerRef?.onTouch(view, event) ?: view.dispatchTouchEvent(event)
    }

    fun triggerBubbleSingleTapForTest() = handleBubbleSingleTapConfirmed()

    fun triggerBubbleDoubleTapForTest() = handleBubbleDoubleTap()

    fun isSubtitleViewAttachedAndVisible(): Boolean =
        translationBoxView != null && translationBoxView?.visibility == View.VISIBLE && isSubtitleVisible

    fun isSubtitleViewBlockingTouch(): Boolean {
        val view = translationBoxView ?: return false
        if (view.visibility != View.VISIBLE) return false
        val flags = translationBoxParams?.flags ?: return false
        return (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
    }

    fun getLastDisplayedSubtitleText(): String = latestHindiOnlyText

    fun getCurrentFeedbackBannerText(): String? = feedbackBannerView?.text?.toString()

    fun setBoxesVisibility(visible: Boolean, notifyCallback: Boolean = false) {
        setScanBoxVisibility(visible, notifyCallback = notifyCallback)
    }

    /**
     * Anchors the subtitle region near the bottom of the current screen cleanly for both
     * Portrait and Landscape game orientations.
     */
    fun snapTargetBoxToBottom() {
        mainHandler.post {
            val box = targetBoxView ?: return@post
            val params = targetBoxParams ?: return@post
            val (liveW, liveH) = getLiveScreenSize()
            val desiredW = (liveW * 0.86f).toInt().coerceIn(dpToPx(240), dpToPx(640))
            // Tall enough to capture both the character name header and multi-line subtitle body
            val desiredH = dpToPx(162).coerceAtMost((liveH * 0.38f).toInt())
            params.width = desiredW
            params.height = desiredH.coerceAtLeast(dpToPx(96))
            params.x = (liveW - params.width) / 2
            params.y = (liveH - params.height - dpToPx(64)).coerceAtLeast(dpToPx(24))
            try {
                windowManager.updateViewLayout(box, params)
                updateStoredTargetBoxRect(box, params)
            } catch (_: Exception) {
            }
            repositionTranslationBoxAboveRegion(liveW, liveH, params.y)
        }
    }

    fun centerTargetBoxOnScreen() {
        snapTargetBoxToBottom()
    }

    fun onScreenOrientationChanged() {
        mainHandler.post {
            val (liveW, liveH) = getLiveScreenSize()
            if (liveW == lastKnownScreenWidth && liveH == lastKnownScreenHeight) return@post
            lastKnownScreenWidth = liveW
            lastKnownScreenHeight = liveH

            val box = targetBoxView
            val params = targetBoxParams
            if (box != null && params != null) {
                val desiredW = (liveW * 0.86f).toInt().coerceIn(dpToPx(240), dpToPx(640))
                val desiredH = params.height.coerceIn(dpToPx(72), (liveH * 0.36f).toInt().coerceAtLeast(dpToPx(96)))
                params.width = desiredW
                params.height = desiredH
                params.x = (liveW - params.width) / 2
                params.y = (liveH - params.height - dpToPx(56)).coerceAtLeast(dpToPx(24))
                try {
                    windowManager.updateViewLayout(box, params)
                    updateStoredTargetBoxRect(box, params)
                } catch (_: Exception) {
                }
            }

            val root = rootOverlayView
            val rootParams = overlayParams
            if (root != null && rootParams != null) {
                val bubbleW = root.width.coerceAtLeast(dpToPx(48))
                val bubbleH = root.height.coerceAtLeast(dpToPx(48))
                rootParams.x = rootParams.x.coerceIn(0, (liveW - bubbleW).coerceAtLeast(0))
                rootParams.y = rootParams.y.coerceIn(dpToPx(16), (liveH - bubbleH - dpToPx(16)).coerceAtLeast(dpToPx(16)))
                try {
                    windowManager.updateViewLayout(root, rootParams)
                } catch (_: Exception) {
                }
            }

            repositionTranslationBoxAboveRegion(
                liveW = liveW,
                liveH = liveH,
                boxY = params?.y ?: (liveH - dpToPx(190))
            )
        }
    }

    private fun repositionTranslationBoxAboveRegion(liveW: Int, liveH: Int, boxY: Int) {
        val transView = translationBoxView ?: return
        val transParams = translationBoxParams ?: return
        val viewWidth = transView.width.coerceAtLeast(dpToPx(220))
        transParams.x = ((liveW - viewWidth) / 2).coerceAtLeast(dpToPx(16))
        transParams.y = (boxY - dpToPx(66)).coerceIn(dpToPx(24), (liveH - dpToPx(90)).coerceAtLeast(dpToPx(24)))
        try {
            windowManager.updateViewLayout(transView, transParams)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    fun showFloatingOverlay(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        val state = TranslatorStateRepository.state.value
        isBoxVisible = state.showTargetBoxOverlay
        isSubtitleVisible = state.isSubtitleVisible
        isVoiceMuted = state.isVoiceMuted

        if (rootOverlayView != null) {
            if (isBoxVisible) showDraggableTargetBox() else hideDraggableTargetBox()
            if (isSubtitleVisible) showHindiTranslationBox() else hideHindiTranslationBox()
            updateFloatingIconAppearance(state.isCapturingOcr)
            return true
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            createUnrestrictedOverlayFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dpToPx(14)
            y = dpToPx(110)
            applyCutoutModeIfSupported(this)
        }
        overlayParams = params

        val bubbleSize = dpToPx(48)
        val bubbleFrame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
            background = createBubbleDrawable(isActive = state.isCapturingOcr)
            elevation = dpToPx(8).toFloat()
            contentDescription = "GameVox Floating Control: Tap to start or stop dubbing, long press for options"
        }

        val glyphText = TextView(context).apply {
            text = resolveBubbleGlyph(state)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        bubbleFrame.addView(glyphText)

        val dotSize = dpToPx(10)
        val statusDot = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = dpToPx(2)
                marginEnd = dpToPx(2)
            }
            background = createStatusDotDrawable(state.isCapturingOcr, state.isProtectedScreenDetected)
        }
        bubbleFrame.addView(statusDot)

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var hasDraggedInTouchSequence = false

        // Uses OnDoubleTapListener (onSingleTapConfirmed + onDoubleTap) + drag guard so:
        // - Single tap waits cleanly for double-tap disambiguation and NEVER fires on double tap.
        // - Double tap strictly toggles Hindi subtitle visibility without touching scanning/dubbing.
        // - Dragging past touchSlop never triggers tap, double-tap, or long-press.
        val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!hasDraggedInTouchSequence) {
                    handleBubbleSingleTapConfirmed()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!hasDraggedInTouchSequence) {
                    handleBubbleDoubleTap()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!hasDraggedInTouchSequence) {
                    bubbleFrame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showOverlaySettingsBottomSheet()
                }
            }
        }
        val gestureDetector = GestureDetector(context, gestureListener, mainHandler).apply {
            setOnDoubleTapListener(gestureListener)
        }

        val bubbleTouchListener = View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    hasDraggedInTouchSequence = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                        hasDraggedInTouchSequence = true
                    }
                    if (isDragging && rootOverlayView != null) {
                        val (liveW, liveH) = getLiveScreenSize()
                        val bubbleW = bubbleFrame.width.coerceAtLeast(dpToPx(48))
                        val bubbleH = bubbleFrame.height.coerceAtLeast(dpToPx(48))
                        params.x = (initialX + dx).coerceIn(0, (liveW - bubbleW).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(0, (liveH - bubbleH).coerceAtLeast(0))
                        try {
                            windowManager.updateViewLayout(rootOverlayView, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
        bubbleTouchListenerRef = bubbleTouchListener
        bubbleFrame.setOnTouchListener(bubbleTouchListener)

        val added = try {
            windowManager.addView(bubbleFrame, params)
            rootOverlayView = bubbleFrame
            circularBubbleView = bubbleFrame
            bubbleLabelView = glyphText
            bubbleStatusDot = statusDot
            true
        } catch (_: Exception) {
            false
        }

        if (added) {
            if (isBoxVisible) showDraggableTargetBox()
            if (isSubtitleVisible) showHindiTranslationBox()
        }

        return added
    }

    /**
     * Adds the Draggable & Resizable Subtitle Detection Region initially near the bottom of the screen.
     */
    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    fun showDraggableTargetBox() {
        mainHandler.post {
            if (targetBoxView != null) return@post
            if (!Settings.canDrawOverlays(context)) return@post

            val (screenW, screenH) = getLiveScreenSize()
            lastKnownScreenWidth = screenW
            lastKnownScreenHeight = screenH

            val savedRect = TranslatorStateRepository.state.value.targetBoxScreenRect
            val defaultBoxWidth = (screenW * 0.86f).toInt().coerceIn(dpToPx(240), dpToPx(640))
            // Default ROI height includes the speaker name/header line above the dialogue text
            val defaultBoxHeight = dpToPx(162).coerceAtMost((screenH * 0.38f).toInt().coerceAtLeast(dpToPx(110)))

            val useSaved = savedRect != null &&
                savedRect.width() >= dpToPx(110) &&
                savedRect.height() >= dpToPx(60) &&
                savedRect.left >= 0 &&
                savedRect.top >= 0 &&
                savedRect.right <= screenW + dpToPx(24) &&
                savedRect.bottom <= screenH + dpToPx(24)

            val initialWidth = if (useSaved) savedRect!!.width() else defaultBoxWidth
            val initialHeight = if (useSaved) savedRect!!.height() else defaultBoxHeight
            val initialX = if (useSaved) savedRect!!.left else (screenW - initialWidth) / 2
            val initialY = if (useSaved) {
                savedRect!!.top
            } else {
                // Initially near the bottom of the screen where game dialogue subtitles sit
                (screenH - initialHeight - dpToPx(64)).coerceAtLeast(dpToPx(32))
            }

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val boxParams = WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                overlayType,
                createUnrestrictedOverlayFlags(),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = initialX
                y = initialY
                applyCutoutModeIfSupported(this)
            }
            targetBoxParams = boxParams

            val isScanning = TranslatorStateRepository.state.value.isCapturingOcr
            val borderDrawable = createTargetBoxDrawable(isScanning)
            targetBoxBorderDrawable = borderDrawable

            val boxFrame = FrameLayout(context).apply {
                background = borderDrawable
                clipChildren = false
                clipToPadding = false
                contentDescription = "Subtitle detection region, drag to move or drag corner to resize"
            }

            val resizeGripSize = dpToPx(48)
            val resizeHandle = CornerResizeGripView(context).apply {
                contentDescription = "Resize subtitle region corner handle"
                layoutParams = FrameLayout.LayoutParams(resizeGripSize, resizeGripSize).apply {
                    gravity = Gravity.BOTTOM or Gravity.RIGHT
                }
            }
            targetBoxResizeHandle = resizeHandle
            boxFrame.addView(resizeHandle)

            val minW = dpToPx(120)
            val minH = dpToPx(64)
            val cornerHitZonePx = dpToPx(56)

            var activeMode = BoxTouchMode.NONE
            var startParamX = 0
            var startParamY = 0
            var startParamW = 0
            var startParamH = 0
            var startRawX = 0f
            var startRawY = 0f

            resizeHandle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activeMode = BoxTouchMode.DRAG_RESIZE
                        startParamW = if (boxParams.width > 0) boxParams.width else boxFrame.width
                        startParamH = if (boxParams.height > 0) boxParams.height else boxFrame.height
                        startRawX = event.rawX
                        startRawY = event.rawY
                        resizeHandle.parent?.requestDisallowInterceptTouchEvent(true)
                        resizeHandle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val (liveW, liveH) = getLiveScreenSize()
                        val maxSpan = maxOf(liveW, liveH, 2560)
                        val dw = (event.rawX - startRawX).toInt()
                        val dh = (event.rawY - startRawY).toInt()
                        boxParams.width = (startParamW + dw).coerceIn(minW, maxSpan)
                        boxParams.height = (startParamH + dh).coerceIn(minH, maxSpan)
                        applyBoxLayoutUpdate(boxFrame, boxParams)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activeMode = BoxTouchMode.NONE
                        applyBoxLayoutUpdate(boxFrame, boxParams)
                        true
                    }
                    else -> false
                }
            }

            boxFrame.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val currentW = if (view.width > 0) view.width else boxParams.width
                        val currentH = if (view.height > 0) view.height else boxParams.height
                        val isBottomRightCorner =
                            event.x >= (currentW - cornerHitZonePx) &&
                                event.y >= (currentH - cornerHitZonePx)

                        startParamX = boxParams.x
                        startParamY = boxParams.y
                        startParamW = currentW
                        startParamH = currentH
                        startRawX = event.rawX
                        startRawY = event.rawY

                        if (isBottomRightCorner) {
                            activeMode = BoxTouchMode.DRAG_RESIZE
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        } else {
                            activeMode = BoxTouchMode.DRAG_MOVE
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startRawX).toInt()
                        val dy = (event.rawY - startRawY).toInt()

                        if (activeMode == BoxTouchMode.DRAG_RESIZE) {
                            val (liveW, liveH) = getLiveScreenSize()
                            val maxSpan = maxOf(liveW, liveH, 2560)
                            boxParams.width = (startParamW + dx).coerceIn(minW, maxSpan)
                            boxParams.height = (startParamH + dy).coerceIn(minH, maxSpan)
                        } else {
                            boxParams.x = startParamX + dx
                            boxParams.y = startParamY + dy
                        }
                        applyBoxLayoutUpdate(boxFrame, boxParams)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activeMode = BoxTouchMode.NONE
                        applyBoxLayoutUpdate(boxFrame, boxParams)
                        true
                    }
                    else -> false
                }
            }

            try {
                windowManager.addView(boxFrame, boxParams)
                targetBoxView = boxFrame
                boxFrame.post {
                    updateStoredTargetBoxRect(boxFrame, boxParams)
                }
            } catch (_: Exception) {
            }
        }
    }

    private inner class CornerResizeGripView(context: Context) : View(context) {
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(175, 16, 185, 129)
        }
        private val gripLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2).toFloat().coerceAtLeast(3f)
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val cornerPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val triSize = minOf(w, h) * 0.72f
            cornerPath.reset()
            cornerPath.moveTo(w, h - triSize)
            cornerPath.lineTo(w, h)
            cornerPath.lineTo(w - triSize, h)
            cornerPath.close()
            canvas.drawPath(cornerPath, badgePaint)

            val inset1 = triSize * 0.26f
            val inset2 = triSize * 0.50f
            canvas.drawLine(w - inset1, h - dpToPx(3), w - dpToPx(3), h - inset1, gripLinePaint)
            canvas.drawLine(w - inset2, h - dpToPx(3), w - dpToPx(3), h - inset2, gripLinePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    fun showHindiTranslationBox() {
        mainHandler.post {
            if (translationBoxView != null) return@post
            if (!Settings.canDrawOverlays(context)) return@post

            val (screenW, screenH) = getLiveScreenSize()
            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val boxY = targetBoxParams?.y ?: (screenH - dpToPx(194))
            val initialY = (boxY - dpToPx(66)).coerceAtLeast(dpToPx(28))
            val initialX = (screenW - dpToPx(260)) / 2

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                createUnrestrictedOverlayFlags(),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = initialX.coerceAtLeast(dpToPx(16))
                y = initialY
                applyCutoutModeIfSupported(this)
            }
            translationBoxParams = params

            val hindiTv = TextView(context).apply {
                maxWidth = (screenW * 0.88f).toInt().coerceAtLeast(dpToPx(240))
                minWidth = dpToPx(120)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(14).toFloat()
                    setColor(Color.argb(185, 17, 24, 39))
                    setStroke(dpToPx(1), Color.argb(55, 255, 255, 255))
                }
                text = latestHindiOnlyText
                visibility = if (latestHindiOnlyText.isBlank()) View.GONE else View.VISIBLE
            }

            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f

            hindiTv.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            windowManager.updateViewLayout(hindiTv, params)
                        } catch (_: Exception) {
                        }
                        true
                    }
                    else -> false
                }
            }

            try {
                windowManager.addView(hindiTv, params)
                translationBoxView = hindiTv
            } catch (_: Exception) {
            }
        }
    }

    fun hideHindiTranslationBox() {
        val doHide = {
            val view = translationBoxView
            if (view != null) {
                view.visibility = View.GONE
                translationBoxParams?.let { p ->
                    p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {
                } finally {
                    translationBoxView = null
                    translationBoxParams = null
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doHide()
        } else {
            mainHandler.post(doHide)
        }
    }

    private fun applyBoxLayoutUpdate(boxFrame: View, boxParams: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(boxFrame, boxParams)
            updateStoredTargetBoxRect(boxFrame, boxParams)
        } catch (_: Exception) {
        }
    }

    fun hideDraggableTargetBox() {
        mainHandler.post {
            val box = targetBoxView ?: return@post
            try {
                windowManager.removeView(box)
            } catch (_: Exception) {
            } finally {
                targetBoxView = null
                targetBoxResizeHandle = null
                targetBoxBorderDrawable = null
            }
        }
    }

    private fun updateStoredTargetBoxRect(boxView: View, params: WindowManager.LayoutParams) {
        val loc = IntArray(2)
        boxView.getLocationOnScreen(loc)
        val left = if (loc[0] != 0 || loc[1] != 0) loc[0] else params.x
        val top = if (loc[0] != 0 || loc[1] != 0) loc[1] else params.y
        val width = if (params.width > 0) params.width else boxView.width
        val height = if (params.height > 0) params.height else boxView.height
        TranslatorStateRepository.updateTargetBoxScreenRect(
            Rect(left, top, left + width, top + height)
        )
    }

    private fun resolveBubbleGlyph(state: com.example.data.TranslatorAppState): String {
        return when {
            state.isProtectedScreenDetected -> "!"
            state.isVoiceMuted -> "🔇"
            else -> state.targetLanguage.glyphBadge
        }
    }

    private fun createStatusDotDrawable(isActive: Boolean, isProtected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when {
                    isProtected || isVoiceMuted -> Color.parseColor("#EF4444")
                    isActive -> Color.parseColor("#10B981")
                    else -> Color.parseColor("#60A5FA")
                }
            )
            setStroke(dpToPx(2), Color.parseColor("#1E242B"))
        }
    }

    private fun updateFloatingIconAppearance(isActive: Boolean) {
        val state = TranslatorStateRepository.state.value
        isVoiceMuted = state.isVoiceMuted
        circularBubbleView?.background = createBubbleDrawable(isActive)
        bubbleLabelView?.text = resolveBubbleGlyph(state)
        bubbleStatusDot?.background = createStatusDotDrawable(isActive, state.isProtectedScreenDetected)
    }

    fun updateScanningState(isScanning: Boolean) {
        mainHandler.post {
            updateFloatingIconAppearance(isScanning)
            targetBoxView?.background = createTargetBoxDrawable(isScanning)
        }
    }

    fun updateProtectedScreenBadge(protected: Boolean, message: String? = null) {
        mainHandler.post {
            val state = TranslatorStateRepository.state.value
            updateFloatingIconAppearance(state.isCapturingOcr)
            if (protected && !message.isNullOrBlank()) {
                translationBoxView?.apply {
                    text = message
                    if (isSubtitleVisible) visibility = View.VISIBLE
                }
            }
        }
    }

    fun updateDebugReadout(
        englishText: String,
        hindiText: String,
        statusNote: String,
        sequenceId: Long = 0L,
        sessionEpoch: Long = 0L
    ) {
        val updateAction = {
            val isStale = sequenceId > 0L && (
                sessionEpoch < latestCaptionSessionEpoch ||
                    (sessionEpoch == latestCaptionSessionEpoch && sequenceId < latestCaptionSequenceId)
                )
            if (!isStale) {
                if (sequenceId > 0L) {
                    latestCaptionSequenceId = maxOf(sequenceId, latestCaptionSequenceId)
                    latestCaptionSessionEpoch = maxOf(sessionEpoch, latestCaptionSessionEpoch)
                }
                val cleanHindi = hindiText.trim()
                if (cleanHindi.isNotEmpty()) {
                    latestHindiOnlyText = cleanHindi
                    translationBoxView?.apply {
                        text = cleanHindi
                        visibility = if (isSubtitleVisible) View.VISIBLE else View.GONE
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateAction()
        } else {
            mainHandler.post(updateAction)
        }
    }

    /**
     * Compact options card triggered on Long Press of the floating bubble.
     */
    fun showOverlaySettingsBottomSheet() {
        if (isDialogOpeningOrShown || bottomSheetOverlayView != null) return
        isDialogOpeningOrShown = true

        mainHandler.post {
            if (bottomSheetOverlayView != null) {
                isDialogOpeningOrShown = true
                return@post
            }
            if (!Settings.canDrawOverlays(context)) {
                isDialogOpeningOrShown = false
                return@post
            }

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val sheetParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
            }

            val scrimRoot = FrameLayout(context).apply {
                setBackgroundColor(Color.argb(165, 0, 0, 0))
                setOnClickListener { dismissOverlaySettingsBottomSheet() }
            }

            val (_, liveH) = getLiveScreenSize()
            val scrollWrapper = ScrollView(context).apply {
                isVerticalScrollBarEnabled = true
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM
                }
            }

            val sheetCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                minimumHeight = (liveH * 0.25f).toInt()
                setPadding(dpToPx(18), dpToPx(14), dpToPx(18), dpToPx(20))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadii = floatArrayOf(
                        dpToPx(22).toFloat(), dpToPx(22).toFloat(),
                        dpToPx(22).toFloat(), dpToPx(22).toFloat(),
                        0f, 0f, 0f, 0f
                    )
                    setColor(Color.parseColor("#111827"))
                    setStroke(dpToPx(1), Color.parseColor("#38BDF8"))
                }
            }

            val handleView = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dpToPx(10)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(2).toFloat()
                    setColor(Color.parseColor("#475569"))
                }
            }
            sheetCard.addView(handleView)

            val currentState = TranslatorStateRepository.state.value

            val titleTv = TextView(context).apply {
                text = "GameVox • Quick Dubbing Options"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            sheetCard.addView(titleTv)

            val statusTv = TextView(context).apply {
                text = if (currentState.isProtectedScreenDetected) {
                    "⚠ Protected screen (FLAG_SECURE) blocked capture"
                } else {
                    currentState.captureStatusMessage
                }
                setTextColor(
                    if (currentState.isProtectedScreenDetected) Color.parseColor("#FCA5A5")
                    else Color.parseColor("#94A3B8")
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dpToPx(2), 0, dpToPx(4))
            }
            sheetCard.addView(statusTv)

            val routingDebugTv = TextView(context).apply {
                text = "Live Route: ${currentState.lastRoutingDebugSummary}"
                setTextColor(Color.parseColor("#A7F3D0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, 0, dpToPx(6))
            }
            sheetCard.addView(routingDebugTv)

            // Explicit Warning Banner when engine cannot provide separate voices or Chatterbox server failed
            val warningBannerTv = TextView(context).apply {
                val warnText = currentState.voiceEngineWarningMessage.ifBlank {
                    if (!currentState.supportsDistinctGenderVoices &&
                        currentState.freeVoiceBackend == FreeVoiceBackend.ANDROID_USER_SELECTED
                    ) {
                        "WARNING: ${currentState.activeTtsEngineLabel} only provides 1 voice (SYSTEM_FALLBACK_SINGLE_VOICE). Switch to Human Voice (Chatterbox) or Fast Offline (Supertonic 3) for separate Male & Female voices."
                    } else {
                        ""
                    }
                }
                visibility = if (warnText.isNotBlank()) View.VISIBLE else View.GONE
                text = warnText
                setTextColor(Color.parseColor("#FEF08A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#451A03"))
                    setStroke(dpToPx(1), Color.parseColor("#F59E0B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(8) }
            }
            sheetCard.addView(warningBannerTv)

            // P0: Active Character Selection (Saved Per Game) & Voice Override Quick Panel
            val activeGameNs = VoiceProfileRepository.getActiveGameNamespace()
            val currentManualOverride = VoiceProfileRepository.unlabeledOverrideCharName.value
            var activeCharAssignment = VoiceProfileRepository.activeCharacterAssignment.value
                ?: VoiceProfileRepository.getUnknownSpeakerAssignment(activeGameNs)
            var currentPreviewSid = activeCharAssignment.speakerId
            var charDisplayName = activeCharAssignment.displayName

            val voiceBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(12))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dpToPx(12)
                }
                layoutParams = lp
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(14).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dpToPx(1), Color.parseColor("#818CF8"))
                }
            }

            val voiceHeaderTv = TextView(context).apply {
                text = "Active Character & Voice (Saved for Game: $activeGameNs)"
                setTextColor(Color.parseColor("#A5B4FC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            voiceBox.addView(voiceHeaderTv)

            val charNameTv = TextView(context).apply {
                val genderTag = when {
                    activeCharAssignment.isUnknownOrNeutral -> "Unknown (Unverified Gender)"
                    activeCharAssignment.isFemale -> "Female"
                    else -> "Male"
                }
                text = "Active: $charDisplayName • $genderTag • SID #$currentPreviewSid • Ref: ${activeCharAssignment.referenceVoiceId}.wav"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, dpToPx(2), 0, dpToPx(6))
            }
            voiceBox.addView(charNameTv)

            val speakerLabels = listOf(
                "Sid 0: F1 Priya (Female)",
                "Sid 1: F2 Jenny (Female Lead)",
                "Sid 2: F3 Ira (Female AI)",
                "Sid 3: F4 Sarah (Female Cmdr)",
                "Sid 4: F5 Oracle (Female Mystic)",
                "Sid 5: M1 Anon (Male Lead)",
                "Sid 6: M2 Alex (Male Scout)",
                "Sid 7: M3 Kaal (Male Boss)",
                "Sid 8: M4 Vikram (Male Veteran)",
                "Sid 9: M5 Guard (Male Sentinel)"
            )

            lateinit var sidLabelTv: TextView
            lateinit var refreshGenderButtons: (SpeakerGender) -> Unit

            // Horizontal Quick Character Selector Chips (Saved Per Game)
            val charSelectScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, 0, 0, dpToPx(8))
            }
            val charSelectRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val quickCharacters = listOf(
                null to "Auto (Unknown if unlabeled)",
                "anon" to "Anon (Male • SID #5)",
                "jenny" to "Jenny (Female • SID #1)",
                "kaal" to "Kaal (Male • SID #7)",
                "ira" to "Ira (Female • SID #2)",
                "alex" to "Alex (Male • SID #6)",
                "sarah" to "Sarah (Female • SID #3)"
            )
            val charChipButtons = mutableListOf<Pair<String?, Button>>()

            fun refreshCharacterChipSelection(selectedCharNorm: String?) {
                val genderTag = when {
                    activeCharAssignment.isUnknownOrNeutral -> "Unknown"
                    activeCharAssignment.isFemale -> "Female"
                    else -> "Male"
                }
                charNameTv.text = "Active: ${activeCharAssignment.displayName} • $genderTag • SID #$currentPreviewSid • Ref: ${activeCharAssignment.referenceVoiceId}.wav"
                sidLabelTv.text = speakerLabels.getOrElse(currentPreviewSid) { "Sid $currentPreviewSid" }
                charChipButtons.forEach { (chipKey, btn) ->
                    val isSelected = chipKey == selectedCharNorm
                    btn.setTextColor(if (isSelected) Color.parseColor("#0F172A") else Color.WHITE)
                    btn.background = GradientDrawable().apply {
                        cornerRadius = dpToPx(12).toFloat()
                        setColor(if (isSelected) Color.parseColor("#10B981") else Color.parseColor("#334155"))
                    }
                }
            }

            quickCharacters.forEach { (charKey, label) ->
                val chipBtn = Button(context).apply {
                    text = label
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dpToPx(32)
                    ).apply { marginEnd = dpToPx(6) }
                    setPadding(dpToPx(10), 0, dpToPx(10), 0)
                    setOnClickListener {
                        val updatedChar = VoiceProfileRepository.selectActiveCharacterForGame(
                            context = context,
                            characterNameOrNull = charKey,
                            gameNamespace = activeGameNs
                        )
                        activeCharAssignment = updatedChar
                        currentPreviewSid = updatedChar.speakerId
                        charDisplayName = updatedChar.displayName
                        refreshCharacterChipSelection(charKey)
                        val newGender = when {
                            updatedChar.isUnknownOrNeutral -> SpeakerGender.UNKNOWN
                            updatedChar.isFemale -> SpeakerGender.FEMALE_18
                            else -> SpeakerGender.MALE_18
                        }
                        refreshGenderButtons(newGender)
                        onRefreshTtsCheck()
                    }
                }
                charChipButtons.add(charKey to chipBtn)
                charSelectRow.addView(chipBtn)
            }
            charSelectScroll.addView(charSelectRow)
            voiceBox.addView(charSelectScroll)

            val cyclerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dpToPx(10)
                }
                layoutParams = lp
            }

            sidLabelTv = TextView(context).apply {
                text = speakerLabels.getOrElse(currentPreviewSid) { "Sid $currentPreviewSid" }
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val prevSidBtn = Button(context).apply {
                text = "◀ Prev"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.parseColor("#334155"))
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(72), dpToPx(34))
                setOnClickListener {
                    currentPreviewSid = (currentPreviewSid - 1 + 10) % 10
                    sidLabelTv.text = speakerLabels.getOrElse(currentPreviewSid) { "Sid $currentPreviewSid" }
                }
            }

            val nextSidBtn = Button(context).apply {
                text = "Next ▶"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.parseColor("#334155"))
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(72), dpToPx(34))
                setOnClickListener {
                    currentPreviewSid = (currentPreviewSid + 1) % 10
                    sidLabelTv.text = speakerLabels.getOrElse(currentPreviewSid) { "Sid $currentPreviewSid" }
                }
            }

            cyclerRow.addView(prevSidBtn)
            cyclerRow.addView(sidLabelTv)
            cyclerRow.addView(nextSidBtn)
            voiceBox.addView(cyclerRow)

            val voiceActionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val previewBtn = Button(context).apply {
                text = "▶ Preview"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.parseColor("#4338CA"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(36), 1f).apply {
                    marginEnd = dpToPx(6)
                }
                setOnClickListener {
                    val isFemSid = currentPreviewSid in 0..4
                    val previewAssignment = activeCharAssignment.copy(
                        speakerId = currentPreviewSid,
                        referenceVoiceId = if (isFemSid) {
                            com.example.data.CharacterVoiceAssignment.DEFAULT_FEMALE_REF_ID
                        } else {
                            com.example.data.CharacterVoiceAssignment.DEFAULT_MALE_REF_ID
                        },
                        voicePresentation = if (isFemSid) "female" else "male"
                    )
                    ForegroundTtsManager.getInstance(context).speakNonBlocking(
                        text = "नमस्ते, मैं $charDisplayName की आवाज़ हूँ।",
                        characterAssignment = previewAssignment
                    )
                }
            }

            val saveOverrideBtn = Button(context).apply {
                text = "✓ Save for Game"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.parseColor("#059669"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(36), 1f).apply {
                    marginStart = dpToPx(6)
                }
                setOnClickListener {
                    val isFemSid = currentPreviewSid in 0..4
                    val targetCharName = activeCharAssignment.characterName.takeIf {
                        it != com.example.data.CharacterVoiceAssignment.UNKNOWN_SPEAKER_NAME
                    } ?: if (isFemSid) "jenny" else "anon"
                    val updated = VoiceProfileRepository.setUserOverrideForCharacter(
                        context = context,
                        characterName = targetCharName,
                        sid = currentPreviewSid,
                        displayName = if (activeCharAssignment.isUnknownOrNeutral) null else charDisplayName,
                        referenceVoiceId = if (isFemSid) {
                            com.example.data.CharacterVoiceAssignment.DEFAULT_FEMALE_REF_ID
                        } else {
                            com.example.data.CharacterVoiceAssignment.DEFAULT_MALE_REF_ID
                        },
                        voicePresentation = if (isFemSid) "female" else "male"
                    )
                    VoiceProfileRepository.selectActiveCharacterForGame(context, updated.characterName, activeGameNs)
                    activeCharAssignment = updated
                    charDisplayName = updated.displayName
                    refreshCharacterChipSelection(updated.characterName)
                    text = "Saved!"
                    postDelayed({ text = "✓ Save for Game" }, 2000L)
                }
            }

            voiceActionRow.addView(previewBtn)
            voiceActionRow.addView(saveOverrideBtn)
            voiceBox.addView(voiceActionRow)

            sheetCard.addView(voiceBox)

            // Subtitle Region controls + Snap Bottom
            val regionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(4), 0, dpToPx(4))
            }
            val boxSwitch = Switch(context).apply {
                text = "Subtitle Region"
                setTextColor(Color.WHITE)
                isChecked = isBoxVisible
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnCheckedChangeListener { _, checked ->
                    setScanBoxVisibility(checked, notifyCallback = true)
                }
            }
            val snapBottomBtn = Button(context).apply {
                text = "Snap Bottom"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dpToPx(1), Color.parseColor("#10B981"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(36)
                )
                setPadding(dpToPx(12), 0, dpToPx(12), 0)
                setOnClickListener {
                    if (!isBoxVisible) setScanBoxVisibility(true, notifyCallback = true)
                    onSnapTargetBoxBottom()
                    snapTargetBoxToBottom()
                }
            }
            regionRow.addView(boxSwitch)
            regionRow.addView(snapBottomBtn)
            sheetCard.addView(regionRow)

            // Hindi Captions Overlay switch + Mute Voice switch
            val captionsSwitch = Switch(context).apply {
                text = "Show Hindi Captions Overlay"
                setTextColor(Color.WHITE)
                isChecked = isSubtitleVisible
                setPadding(0, dpToPx(2), 0, dpToPx(2))
                setOnCheckedChangeListener { _, checked ->
                    setSubtitleCaptionsVisibility(checked)
                }
            }
            sheetCard.addView(captionsSwitch)

            val muteSwitch = Switch(context).apply {
                text = "Mute Voice Output"
                setTextColor(Color.WHITE)
                isChecked = isVoiceMuted
                setPadding(0, dpToPx(2), 0, dpToPx(8))
                setOnCheckedChangeListener { _, checked ->
                    setVoiceMutedState(checked, notifyCallback = true)
                }
            }
            sheetCard.addView(muteSwitch)

            // Translation Mode 2-Pill Toggle
            val modeLabel = TextView(context).apply {
                text = "Translation Mode (${currentState.translationMode.shortBadge})"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(0, dpToPx(2), 0, dpToPx(6))
            }
            sheetCard.addView(modeLabel)

            val modeNoteTv = TextView(context).apply {
                text = currentState.translationMode.costAndLimitNote
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, dpToPx(4), 0, dpToPx(8))
            }

            val modeRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            lateinit var offlineModeBtn: Button
            lateinit var aiModeBtn: Button

            fun refreshModeButtons(activeMode: TranslationMode) {
                modeLabel.text = "Translation Mode (${activeMode.shortBadge})"
                modeNoteTv.text = activeMode.costAndLimitNote
                offlineModeBtn.setTextColor(
                    if (activeMode == TranslationMode.ON_DEVICE_MLKIT) Color.parseColor("#0F172A") else Color.WHITE
                )
                offlineModeBtn.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(
                        if (activeMode == TranslationMode.ON_DEVICE_MLKIT) Color.parseColor("#38BDF8") else Color.parseColor("#1E293B")
                    )
                }
                aiModeBtn.setTextColor(
                    if (activeMode == TranslationMode.CONTEXT_AWARE_AI) Color.parseColor("#0F172A") else Color.WHITE
                )
                aiModeBtn.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(
                        if (activeMode == TranslationMode.CONTEXT_AWARE_AI) Color.parseColor("#38BDF8") else Color.parseColor("#1E293B")
                    )
                }
            }

            offlineModeBtn = Button(context).apply {
                text = "Free Offline"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply { marginEnd = dpToPx(6) }
                setOnClickListener {
                    TranslatorStateRepository.updateTranslationMode(context, TranslationMode.ON_DEVICE_MLKIT)
                    refreshModeButtons(TranslationMode.ON_DEVICE_MLKIT)
                }
            }
            aiModeBtn = Button(context).apply {
                text = "Context AI (Gemini)"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply { marginStart = dpToPx(6) }
                setOnClickListener {
                    TranslatorStateRepository.updateTranslationMode(
                        context = context,
                        mode = TranslationMode.CONTEXT_AWARE_AI,
                        aiPrivacyConsentAccepted = true
                    )
                    refreshModeButtons(TranslationMode.CONTEXT_AWARE_AI)
                }
            }
            refreshModeButtons(currentState.translationMode)
            modeRow.addView(offlineModeBtn)
            modeRow.addView(aiModeBtn)
            sheetCard.addView(modeRow)
            sheetCard.addView(modeNoteTv)

            // Translation Style & Queue Delivery Mode Quick Selectors
            val styleHeaderTv = TextView(context).apply {
                text = "Hindi Style (${currentState.translationStyle.shortBadge}) & Queue (${currentState.dubbingDeliveryMode.shortBadge})"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(0, dpToPx(2), 0, dpToPx(6))
            }
            sheetCard.addView(styleHeaderTv)

            val styleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                setPadding(0, 0, 0, dpToPx(6))
            }
            val styleButtons = mutableListOf<Pair<TranslationStyle, Button>>()
            fun refreshStyleButtons(selectedStyle: TranslationStyle) {
                val activeDelivery = TranslatorStateRepository.state.value.dubbingDeliveryMode
                styleHeaderTv.text = "Hindi Style (${selectedStyle.shortBadge}) & Queue (${activeDelivery.shortBadge})"
                styleButtons.forEach { (st, btn) ->
                    val isSel = st == selectedStyle
                    btn.setTextColor(if (isSel) Color.parseColor("#0F172A") else Color.WHITE)
                    btn.background = GradientDrawable().apply {
                        cornerRadius = dpToPx(10).toFloat()
                        setColor(if (isSel) Color.parseColor("#10B981") else Color.parseColor("#1E293B"))
                    }
                }
            }
            TranslationStyle.entries.forEachIndexed { idx, style ->
                val btn = Button(context).apply {
                    text = style.shortBadge
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    layoutParams = LinearLayout.LayoutParams(0, dpToPx(34), 1f).apply {
                        if (idx > 0) marginStart = dpToPx(4)
                        if (idx < TranslationStyle.entries.lastIndex) marginEnd = dpToPx(4)
                    }
                    setOnClickListener {
                        TranslatorStateRepository.updateTranslationStyle(context, style)
                        refreshStyleButtons(style)
                    }
                }
                styleButtons.add(style to btn)
                styleRow.addView(btn)
            }
            refreshStyleButtons(currentState.translationStyle)
            sheetCard.addView(styleRow)

            val deliveryRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, 0, 0, dpToPx(8))
            }
            lateinit var liveModeBtn: Button
            lateinit var storyModeBtn: Button
            fun refreshDeliveryButtons(selectedMode: DubbingDeliveryMode) {
                val activeStyle = TranslatorStateRepository.state.value.translationStyle
                styleHeaderTv.text = "Hindi Style (${activeStyle.shortBadge}) & Queue (${selectedMode.shortBadge})"
                liveModeBtn.setTextColor(if (selectedMode == DubbingDeliveryMode.LIVE_MODE) Color.parseColor("#0F172A") else Color.WHITE)
                liveModeBtn.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(if (selectedMode == DubbingDeliveryMode.LIVE_MODE) Color.parseColor("#38BDF8") else Color.parseColor("#1E293B"))
                }
                storyModeBtn.setTextColor(if (selectedMode == DubbingDeliveryMode.STORY_MODE) Color.parseColor("#0F172A") else Color.WHITE)
                storyModeBtn.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(if (selectedMode == DubbingDeliveryMode.STORY_MODE) Color.parseColor("#38BDF8") else Color.parseColor("#1E293B"))
                }
            }
            liveModeBtn = Button(context).apply {
                text = "Live Mode (Drop Old)"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(34), 1f).apply { marginEnd = dpToPx(6) }
                setOnClickListener {
                    TranslatorStateRepository.updateDubbingDeliveryMode(context, DubbingDeliveryMode.LIVE_MODE)
                    refreshDeliveryButtons(DubbingDeliveryMode.LIVE_MODE)
                }
            }
            storyModeBtn = Button(context).apply {
                text = "Story Mode (Ordered)"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(34), 1f).apply { marginStart = dpToPx(6) }
                setOnClickListener {
                    TranslatorStateRepository.updateDubbingDeliveryMode(context, DubbingDeliveryMode.STORY_MODE)
                    refreshDeliveryButtons(DubbingDeliveryMode.STORY_MODE)
                }
            }
            refreshDeliveryButtons(currentState.dubbingDeliveryMode)
            deliveryRow.addView(liveModeBtn)
            deliveryRow.addView(storyModeBtn)
            sheetCard.addView(deliveryRow)

            // Quick Female / Male Voice Switcher (Synchronized with Jenny Female SID #1 & Anon Male SID #5 for Live Gameplay)
            val currentGender = when {
                activeCharAssignment.isUnknownOrNeutral -> SpeakerGender.UNKNOWN
                activeCharAssignment.isFemale -> SpeakerGender.FEMALE_18
                else -> SpeakerGender.MALE_18
            }

            val voiceRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, 0, 0, dpToPx(8))
            }
            lateinit var femaleVoiceBtn: Button
            lateinit var maleVoiceBtn: Button

            refreshGenderButtons = { gender: SpeakerGender ->
                femaleVoiceBtn.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(
                        if (gender == SpeakerGender.FEMALE_18) Color.parseColor("#10B981") else Color.parseColor("#1E293B")
                    )
                }
                femaleVoiceBtn.setTextColor(
                    if (gender == SpeakerGender.FEMALE_18) Color.parseColor("#0F172A") else Color.WHITE
                )
                maleVoiceBtn.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(
                        if (gender == SpeakerGender.MALE_18) Color.parseColor("#10B981") else Color.parseColor("#1E293B")
                    )
                }
                maleVoiceBtn.setTextColor(
                    if (gender == SpeakerGender.MALE_18) Color.parseColor("#0F172A") else Color.WHITE
                )
            }

            femaleVoiceBtn = Button(context).apply {
                text = "Female Voice (Jenny • SID #1)"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply { marginEnd = dpToPx(6) }
                setOnClickListener {
                    val jennyChar = VoiceProfileRepository.selectActiveCharacterForGame(context, "jenny", activeGameNs)
                    activeCharAssignment = jennyChar
                    currentPreviewSid = jennyChar.speakerId
                    charDisplayName = jennyChar.displayName
                    refreshCharacterChipSelection("jenny")
                    refreshGenderButtons(SpeakerGender.FEMALE_18)
                    onRefreshTtsCheck()
                }
            }
            maleVoiceBtn = Button(context).apply {
                text = "Male Voice (Anon • SID #5)"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply { marginStart = dpToPx(6) }
                setOnClickListener {
                    val anonChar = VoiceProfileRepository.selectActiveCharacterForGame(context, "anon", activeGameNs)
                    activeCharAssignment = anonChar
                    currentPreviewSid = anonChar.speakerId
                    charDisplayName = anonChar.displayName
                    refreshCharacterChipSelection("anon")
                    refreshGenderButtons(SpeakerGender.MALE_18)
                    onRefreshTtsCheck()
                }
            }
            refreshCharacterChipSelection(currentManualOverride)
            refreshGenderButtons(currentGender)
            voiceRow.addView(femaleVoiceBtn)
            voiceRow.addView(maleVoiceBtn)
            sheetCard.addView(voiceRow)

            // Target Language Chips
            val langScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, 0, 0, dpToPx(8))
            }
            val langRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            TargetLanguage.entries.forEach { lang ->
                val isSelected = lang == TranslatorStateRepository.state.value.targetLanguage
                val chipBtn = Button(context).apply {
                    text = "${lang.nativeName} • ${lang.displayName}"
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(if (isSelected) Color.parseColor("#0F172A") else Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(14).toFloat()
                        setColor(if (isSelected) Color.parseColor("#38BDF8") else Color.parseColor("#1E293B"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dpToPx(32)
                    ).apply { marginEnd = dpToPx(6) }
                    setPadding(dpToPx(10), 0, dpToPx(10), 0)
                    setOnClickListener {
                        TranslatorStateRepository.updateTargetLanguage(context, lang)
                        bubbleLabelView?.text = resolveBubbleGlyph(TranslatorStateRepository.state.value)
                        onRefreshTtsCheck()
                    }
                }
                langRow.addView(chipBtn)
            }
            langScroll.addView(langRow)
            sheetCard.addView(langScroll)

            // Volume Slider (10% - 100%)
            val volumePct = (currentState.ttsVolume * 100).toInt().coerceIn(10, 100)
            val volumeLabel = TextView(context).apply {
                text = "Dubbing Volume: $volumePct%"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            sheetCard.addView(volumeLabel)

            val volumeSeek = SeekBar(context).apply {
                max = 90
                progress = (volumePct - 10).coerceIn(0, 90)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val pct = 10 + progress
                        volumeLabel.text = "Dubbing Volume: $pct%"
                        if (fromUser) {
                            TranslatorStateRepository.updateTtsVolume(context, pct / 100f)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            sheetCard.addView(volumeSeek)

            // Speech Speed Slider (0.4x - 1.8x)
            val rateLabel = TextView(context).apply {
                text = String.format(Locale.US, "Speech Speed: %.2fx", currentState.ttsSpeechRate)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dpToPx(4), 0, 0)
            }
            sheetCard.addView(rateLabel)

            val rateSeek = SeekBar(context).apply {
                max = 140
                progress = ((currentState.ttsSpeechRate - 0.4f) * 100f).toInt().coerceIn(0, 140)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val rate = (0.4f + progress / 100f)
                        rateLabel.text = String.format(Locale.US, "Speech Speed: %.2fx", rate)
                        if (fromUser) {
                            TranslatorStateRepository.updateTtsSpeechRate(context, rate)
                            onRefreshTtsCheck()
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            sheetCard.addView(rateSeek)

            // Bottom Action Buttons
            val actionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                setPadding(0, dpToPx(10), 0, 0)
            }

            val testBtn = Button(context).apply {
                text = "Test Voice"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#0F172A"))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#38BDF8"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply { marginEnd = dpToPx(4) }
                setOnClickListener {
                    onRefreshTtsCheck()
                    onTestHindiSpeech()
                }
            }

            val closeBtn = Button(context).apply {
                text = "Close"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply {
                    marginStart = dpToPx(4)
                    marginEnd = dpToPx(4)
                }
                setOnClickListener { dismissOverlaySettingsBottomSheet() }
            }

            val stopBtn = Button(context).apply {
                text = "Stop"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#B91C1C"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply { marginStart = dpToPx(4) }
                setOnClickListener {
                    dismissOverlaySettingsBottomSheet()
                    onStopServiceRequested()
                }
            }

            actionsRow.addView(testBtn)
            actionsRow.addView(closeBtn)
            actionsRow.addView(stopBtn)
            sheetCard.addView(actionsRow)

            scrollWrapper.addView(sheetCard)
            scrimRoot.addView(scrollWrapper)

            try {
                windowManager.addView(scrimRoot, sheetParams)
                bottomSheetOverlayView = scrimRoot
            } catch (_: Exception) {
                isDialogOpeningOrShown = false
            }
        }
    }

    fun dismissOverlaySettingsBottomSheet() {
        mainHandler.post {
            TranslatorStateRepository.setShowSettingsBottomSheet(false)
            val sheet = bottomSheetOverlayView
            if (sheet != null) {
                try {
                    windowManager.removeViewImmediate(sheet)
                } catch (_: Exception) {
                    try {
                        windowManager.removeView(sheet)
                    } catch (_: Exception) {
                    }
                } finally {
                    bottomSheetOverlayView = null
                }
            }
            isDialogOpeningOrShown = false
        }
    }

    fun destroyOverlay() {
        mainHandler.removeCallbacks(hideFeedbackRunnable)
        dismissOverlaySettingsBottomSheet()
        hideDraggableTargetBox()
        hideHindiTranslationBox()
        val doDestroy = {
            hideGestureFeedbackBadge()
            val root = rootOverlayView
            if (root != null) {
                try {
                    windowManager.removeView(root)
                } catch (_: Exception) {
                } finally {
                    rootOverlayView = null
                    circularBubbleView = null
                    bubbleLabelView = null
                    bubbleStatusDot = null
                    bubbleTouchListenerRef = null
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doDestroy()
        } else {
            mainHandler.post(doDestroy)
        }
    }

    private fun createBubbleDrawable(isActive: Boolean): GradientDrawable {
        val state = TranslatorStateRepository.state.value
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(230, 20, 26, 36))
            setStroke(
                dpToPx(2),
                when {
                    state.isProtectedScreenDetected || isVoiceMuted -> Color.parseColor("#EF4444")
                    isActive -> Color.parseColor("#10B981")
                    else -> Color.parseColor("#60A5FA")
                }
            )
        }
    }

    private fun createTargetBoxDrawable(isScanning: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(Color.argb(10, 16, 185, 129))
            setStroke(
                dpToPx(2),
                if (isScanning) Color.parseColor("#10B981") else Color.argb(165, 16, 185, 129)
            )
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
