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
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.example.data.TargetLanguage
import com.example.data.TranslatorStateRepository
import java.util.Locale
import kotlin.math.abs

/**
 * Clean, Minimalist WindowManager Overlay Controller:
 * 1. **Floating Icon (`rootOverlayView`)**:
 *    - Uses [GestureDetector] + `OnTouchListener`.
 *    - **Double Tap (`onDoubleTap`)**: Toggles [isBoxVisible]. When `true`, adds both the
 *      Scan Box and the Hindi-Only Translation Box via `WindowManager.addView()`. When `false`,
 *      removes both via `WindowManager.removeView()`.
 *    - **Single Tap**: Does nothing.
 *    - **Long Press**: Opens the guarded Settings sheet.
 * 2. **Clean Scan Box (`targetBoxView`) with Corner Resize Handle**:
 *    - Configured with `FLAG_NOT_FOCUSABLE`, `FLAG_NOT_TOUCH_MODAL`, `FLAG_LAYOUT_IN_SCREEN`,
 *      `FLAG_LAYOUT_NO_LIMITS`, and `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` so it can be
 *      dragged anywhere on the entire screen (left, right, top, bottom) in both Portrait and
 *      Landscape games without any half-screen restriction.
 *    - Starts centered (`(screenW - width) / 2`, `(screenH - height) / 2`).
 *    - Includes a dedicated 48dp corner resize handle (`CornerResizeGripView`) in the bottom-right
 *      corner that dynamically updates `boxParams.width` and `boxParams.height` via
 *      `WindowManager.updateViewLayout()`.
 * 3. **Hindi-Only Translation Box (`translationBoxView`)**:
 *    - Displays **only** the translated Hindi text (no English text, no status messages).
 */
class FloatingOverlayController(
    private val context: Context,
    private val onBoxVisibilityToggled: (Boolean) -> Unit,
    private val onVoiceMuteToggled: (Boolean) -> Unit = {},
    private val onTestHindiSpeech: () -> Unit,
    private val onInstallHindiVoiceData: () -> Unit,
    private val onRefreshTtsCheck: () -> Unit,
    private val onStopServiceRequested: () -> Unit
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

    // 2. Clean Draggable & Resizable Scan Box with Corner Resize Handle
    private var targetBoxView: FrameLayout? = null
    private var targetBoxBorderDrawable: GradientDrawable? = null
    private var targetBoxResizeHandle: View? = null
    private var targetBoxParams: WindowManager.LayoutParams? = null
    private var lastKnownScreenWidth: Int = 0
    private var lastKnownScreenHeight: Int = 0

    // 3. Clean Hindi-Only Translation Box
    private var translationBoxView: TextView? = null
    private var translationBoxParams: WindowManager.LayoutParams? = null
    private var latestHindiOnlyText: String = ""

    // Boolean state toggled via Single Tap (onSingleTapConfirmed) — controls ONLY Green Scan Box visibility
    @Volatile
    var isBoxVisible: Boolean = true
        private set

    // Boolean state toggled via Double Tap (onDoubleTap) — controls ONLY Translated Captions Box (Hindi dark box) visibility
    @Volatile
    var isSubtitleVisible: Boolean = true
        private set

    // Boolean state toggled via Long Press (onLongPress) — controls Voice (TTS Mute/Unmute), default false (Voice ON)
    @Volatile
    var isVoiceMuted: Boolean = false
        private set

    // 4. Guarded Settings Sheet
    @Volatile
    private var bottomSheetOverlayView: FrameLayout? = null

    @Volatile
    private var isDialogOpeningOrShown: Boolean = false

    val isAttached: Boolean
        get() = rootOverlayView != null

    /**
     * Queries the live physical display width and height directly from [DisplayManager]
     * (`Display.DEFAULT_DISPLAY`), accounting for the active rotation (`ROTATION_90` / `ROTATION_270`)
     * so Landscape games always report the true landscape width and height.
     */
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

    /**
     * Builds the [WindowManager.LayoutParams] flags required so overlay views can be dragged
     * freely across the entire screen (including over system bars and display cutouts) without
     * blocking game touches outside the overlay.
     */
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

    /**
     * Returns the current screen bounding box of the floating circular icon.
     */
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

    /**
     * Returns the exact screen coordinates (`Rect(left, top, right, bottom)`) of the user's
     * Draggable & Resizable Target Scan Box so MediaProjection, Accessibility Screenshot OCR,
     * and AccessibilityNodeInfo ONLY read text inside this box.
     */
    fun getTargetBoxScreenBounds(): Rect? {
        if (!isBoxVisible) return null
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

    /**
     * Toggles ONLY [isVoiceMuted] (Voice ON/OFF) on Long Press of the floating icon:
     * - Default is `false` (Voice ON).
     * - When `true`: mutes/pauses TTS voice output immediately.
     * - When `false`: unmutes/resumes TTS voice output.
     */
    fun toggleVoiceMuteOnSingleTap() {
        setVoiceMutedState(!isVoiceMuted, notifyCallback = true)
    }

    fun setVoiceMutedState(muted: Boolean, notifyCallback: Boolean = false) {
        mainHandler.post {
            isVoiceMuted = muted
            TranslatorStateRepository.setVoiceMuted(context, muted)
            updateFloatingIconAppearance(isBoxVisible)
            if (notifyCallback) {
                onVoiceMuteToggled(muted)
            }
        }
    }

    /**
     * Single Tap (`onSingleTapConfirmed`):
     * Toggles ONLY [isBoxVisible] (Green Scan Box visibility).
     * - When `true`: shows ONLY the Green Scan Box (`showDraggableTargetBox()`) and resumes scanning.
     * - When `false`: hides ONLY the Green Scan Box (`hideDraggableTargetBox()`) and pauses scanning.
     * - Does NOT affect [isSubtitleVisible] (Translated Captions Box stays as-is)!
     */
    fun toggleScanBoxOnSingleTap() {
        setScanBoxVisibility(!isBoxVisible, notifyCallback = true)
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
            updateFloatingIconAppearance(visible)
            if (notifyCallback) {
                onBoxVisibilityToggled(visible)
            }
        }
    }

    /**
     * Double Tap (`onDoubleTap`):
     * Toggles ONLY [isSubtitleVisible] (Translated Captions dark Hindi box on top).
     * - When `true`: shows ONLY the Translated Captions Box (`showHindiTranslationBox()`).
     * - When `false`: hides ONLY the Translated Captions Box (`hideHindiTranslationBox()`).
     * - Does NOT affect [isBoxVisible] (Green Scan Box remains untouched)!
     */
    fun toggleSubtitleCaptionsOnDoubleTap() {
        setSubtitleCaptionsVisibility(!isSubtitleVisible)
    }

    fun setSubtitleCaptionsVisibility(visible: Boolean) {
        mainHandler.post {
            isSubtitleVisible = visible
            TranslatorStateRepository.setSubtitleVisible(context, visible)
            if (visible) {
                showHindiTranslationBox()
            } else {
                hideHindiTranslationBox()
            }
            updateFloatingIconAppearance(isBoxVisible)
        }
    }

    fun toggleBoxesVisibilityOnDoubleTap() {
        toggleSubtitleCaptionsOnDoubleTap()
    }

    fun setBoxesVisibility(visible: Boolean, notifyCallback: Boolean = false) {
        setScanBoxVisibility(visible, notifyCallback = notifyCallback)
    }

    /**
     * Called when the device changes orientation (e.g. launching a Landscape game) so the
     * Scan Box and Hindi Translation Box re-center cleanly on the new screen dimensions.
     */
    fun onScreenOrientationChanged() {
        mainHandler.post {
            val (liveW, liveH) = getLiveScreenSize()
            if (liveW == lastKnownScreenWidth && liveH == lastKnownScreenHeight) return@post
            lastKnownScreenWidth = liveW
            lastKnownScreenHeight = liveH

            val box = targetBoxView
            val params = targetBoxParams
            if (box != null && params != null) {
                val maxSpan = maxOf(liveW, liveH)
                params.width = params.width.coerceIn(dpToPx(130), maxSpan)
                params.height = params.height.coerceIn(dpToPx(70), maxSpan)
                params.x = (liveW - params.width) / 2
                params.y = (liveH - params.height) / 2
                try {
                    windowManager.updateViewLayout(box, params)
                    updateStoredTargetBoxRect(box, params)
                } catch (_: Exception) {
                }
            }

            val transView = translationBoxView
            val transParams = translationBoxParams
            if (transView != null && transParams != null) {
                val boxY = params?.y ?: (liveH / 2)
                transParams.x = ((liveW - transView.width.coerceAtLeast(dpToPx(200))) / 2).coerceAtLeast(dpToPx(16))
                transParams.y = (boxY - dpToPx(64)).coerceAtLeast(dpToPx(24))
                try {
                    windowManager.updateViewLayout(transView, transParams)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Centers the Draggable Target Scan Box in the exact middle of the current screen.
     */
    fun centerTargetBoxOnScreen() {
        mainHandler.post {
            val box = targetBoxView ?: return@post
            val params = targetBoxParams ?: return@post
            val (liveW, liveH) = getLiveScreenSize()
            params.x = (liveW - params.width) / 2
            params.y = (liveH - params.height) / 2
            try {
                windowManager.updateViewLayout(box, params)
                updateStoredTargetBoxRect(box, params)
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    fun showFloatingOverlay(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        isBoxVisible = TranslatorStateRepository.state.value.showTargetBoxOverlay
        isSubtitleVisible = TranslatorStateRepository.state.value.isSubtitleVisible
        isVoiceMuted = TranslatorStateRepository.state.value.isVoiceMuted

        if (rootOverlayView != null) {
            if (isBoxVisible) {
                showDraggableTargetBox()
            } else {
                hideDraggableTargetBox()
            }
            if (isSubtitleVisible) {
                showHindiTranslationBox()
            } else {
                hideHindiTranslationBox()
            }
            updateFloatingIconAppearance(isBoxVisible)
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

        // Clean Circular Floating Icon ONLY
        val bubbleSize = dpToPx(54)
        val bubbleFrame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
            background = createBubbleDrawable(isActive = isBoxVisible)
            elevation = dpToPx(10).toFloat()
        }

        val currentLang = TranslatorStateRepository.state.value.targetLanguage
        val glyphText = TextView(context).apply {
            text = if (isVoiceMuted) "🔇" else currentLang.glyphBadge
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        bubbleFrame.addView(glyphText)

        val dotSize = dpToPx(12)
        val statusDot = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = dpToPx(2)
                marginEnd = dpToPx(2)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    when {
                        isVoiceMuted -> Color.parseColor("#FF5252")
                        isBoxVisible -> Color.parseColor("#00E676")
                        else -> Color.parseColor("#FFB300")
                    }
                )
                setStroke(dpToPx(2), Color.parseColor("#090D16"))
            }
        }
        bubbleFrame.addView(statusDot)

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // Single Tap: Strictly hides/shows ONLY the Green Scan Box (targetBoxView).
                    // Does NOT hide or show the Translated Captions Box!
                    if (!isDragging) {
                        bubbleFrame.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        toggleScanBoxOnSingleTap()
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Double Tap: Strictly hides/shows ONLY the Translated Captions Box (translationBoxView).
                    // Does NOT affect the Green Scan Box!
                    if (!isDragging) {
                        bubbleFrame.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        toggleSubtitleCaptionsOnDoubleTap()
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    // Long Press: Toggles Voice ON/OFF (Mute/Unmute)
                    if (!isDragging) {
                        bubbleFrame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        toggleVoiceMuteOnSingleTap()
                    }
                }
            },
            mainHandler
        )

        bubbleFrame.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging && rootOverlayView != null) {
                        params.x = initialX + dx
                        params.y = initialY + dy
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

        val added = try {
            windowManager.addView(bubbleFrame, params)
            rootOverlayView = bubbleFrame
            circularBubbleView = bubbleFrame
            bubbleLabelView = glyphText
            bubbleStatusDot = statusDot
            true
        } catch (e: Exception) {
            false
        }

        if (added) {
            if (isBoxVisible) {
                showDraggableTargetBox()
            }
            if (isSubtitleVisible) {
                showHindiTranslationBox()
            }
        }

        return added
    }

    /**
     * Adds the Clean Draggable & Resizable Scan Box via [WindowManager.addView]:
     * - Sets `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`
     *   and `Gravity.TOP | Gravity.LEFT` so the Scan Box can be dragged anywhere on the screen
     *   without any boundary clamping.
     * - Includes a 48dp corner resize handle ([CornerResizeGripView]) in the bottom-right corner
     *   that updates `boxParams.width` and `boxParams.height` via [WindowManager.updateViewLayout].
     */
    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    fun showDraggableTargetBox() {
        mainHandler.post {
            if (targetBoxView != null) return@post
            if (!Settings.canDrawOverlays(context)) return@post

            val (screenW, screenH) = getLiveScreenSize()
            lastKnownScreenWidth = screenW
            lastKnownScreenHeight = screenH

            val minDimension = minOf(screenW, screenH)
            val defaultBoxWidth = (minDimension * 0.80f).toInt().coerceIn(dpToPx(240), dpToPx(500))
            val defaultBoxHeight = dpToPx(150)

            // Exact center of the screen
            val defaultX = (screenW - defaultBoxWidth) / 2
            val defaultY = (screenH - defaultBoxHeight) / 2

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val boxParams = WindowManager.LayoutParams(
                defaultBoxWidth,
                defaultBoxHeight,
                overlayType,
                createUnrestrictedOverlayFlags(),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = defaultX
                y = defaultY
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
            }

            // 48dp Corner Resize Handle in the bottom-right corner of the Scan Box
            val resizeGripSize = dpToPx(48)
            val resizeHandle = CornerResizeGripView(context).apply {
                contentDescription = "Resize Scan Box Corner Handle"
                layoutParams = FrameLayout.LayoutParams(resizeGripSize, resizeGripSize).apply {
                    gravity = Gravity.BOTTOM or Gravity.RIGHT
                }
            }
            targetBoxResizeHandle = resizeHandle
            boxFrame.addView(resizeHandle)

            val minW = dpToPx(110)
            val minH = dpToPx(60)
            val cornerHitZonePx = dpToPx(58)

            var activeMode = BoxTouchMode.NONE
            var startParamX = 0
            var startParamY = 0
            var startParamW = 0
            var startParamH = 0
            var startRawX = 0f
            var startRawY = 0f

            val handleResizeTouch: (MotionEvent) -> Boolean = { event ->
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

            resizeHandle.setOnTouchListener { _, event ->
                handleResizeTouch(event)
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
                            // Unrestricted full-screen drag enabled by FLAG_LAYOUT_NO_LIMITS
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

    /**
     * Custom-drawn 48dp corner resize handle placed in the bottom-right corner of the Scan Box.
     * Draws an L-shaped corner bracket and diagonal resize grip lines so the user can visually
     * see and grab the corner without any text clutter.
     */
    private inner class CornerResizeGripView(context: Context) : View(context) {
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(220, 0, 229, 255)
        }
        private val gripLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2).toFloat().coerceAtLeast(3f)
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#090D16")
        }
        private val cornerPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // Draw diagonal corner triangle badge in the bottom-right
            val triSize = minOf(w, h) * 0.72f
            cornerPath.reset()
            cornerPath.moveTo(w, h - triSize)
            cornerPath.lineTo(w, h)
            cornerPath.lineTo(w - triSize, h)
            cornerPath.close()
            canvas.drawPath(cornerPath, badgePaint)

            // Draw two diagonal resize grip lines inside the corner badge
            val inset1 = triSize * 0.26f
            val inset2 = triSize * 0.50f
            canvas.drawLine(w - inset1, h - dpToPx(3), w - dpToPx(3), h - inset1, gripLinePaint)
            canvas.drawLine(w - inset2, h - dpToPx(3), w - dpToPx(3), h - inset2, gripLinePaint)
        }
    }

    /**
     * Adds the Clean Hindi-Only Translation Box via [WindowManager.addView].
     * Displays ONLY the translated Hindi text (no English text, no status messages).
     * Can also be dragged anywhere on the screen.
     */
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

            val boxY = targetBoxParams?.y ?: (screenH / 2)
            val initialY = (boxY - dpToPx(66)).coerceAtLeast(dpToPx(32))
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
                maxWidth = (minOf(screenW, screenH) * 0.86f).toInt().coerceAtLeast(dpToPx(240))
                minWidth = dpToPx(120)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12).toFloat()
                    setColor(Color.argb(215, 10, 15, 26))
                    setStroke(dpToPx(1), Color.parseColor("#00E5FF"))
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
        mainHandler.post {
            val view = translationBoxView ?: return@post
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            } finally {
                translationBoxView = null
                translationBoxParams = null
            }
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
                TranslatorStateRepository.updateTargetBoxScreenRect(null)
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

    private fun updateFloatingIconAppearance(isActive: Boolean) {
        val state = TranslatorStateRepository.state.value
        val lang = state.targetLanguage
        isVoiceMuted = state.isVoiceMuted
        circularBubbleView?.background = createBubbleDrawable(isActive)
        bubbleLabelView?.text = if (isVoiceMuted) "🔇" else lang.glyphBadge
        bubbleStatusDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when {
                    isVoiceMuted -> Color.parseColor("#FF5252")
                    isActive -> Color.parseColor("#00E676")
                    else -> Color.parseColor("#FFB300")
                }
            )
            setStroke(dpToPx(2), Color.parseColor("#090D16"))
        }
    }

    fun updateScanningState(isScanning: Boolean) {
        mainHandler.post {
            updateFloatingIconAppearance(isScanning && isBoxVisible)
            targetBoxView?.background = createTargetBoxDrawable(isScanning)
        }
    }

    /**
     * Updates the Translation Box with ONLY the translated Hindi text.
     */
    fun updateDebugReadout(englishText: String, hindiText: String, statusNote: String) {
        mainHandler.post {
            val cleanHindi = hindiText.trim()
            if (cleanHindi.isEmpty()) return@post
            latestHindiOnlyText = cleanHindi
            translationBoxView?.apply {
                text = cleanHindi
                if (visibility != View.VISIBLE && isSubtitleVisible) {
                    visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * Displays a single guarded Settings BottomSheet over the active game screen.
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

            val sheetCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(24))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadii = floatArrayOf(
                        dpToPx(24).toFloat(), dpToPx(24).toFloat(),
                        dpToPx(24).toFloat(), dpToPx(24).toFloat(),
                        0f, 0f, 0f, 0f
                    )
                    setColor(Color.parseColor("#121826"))
                    setStroke(dpToPx(1), Color.parseColor("#00E5FF"))
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM
                }
            }

            val handleView = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(5)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dpToPx(12)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(3).toFloat()
                    setColor(Color.parseColor("#4A5B78"))
                }
            }
            sheetCard.addView(handleView)

            val titleTv = TextView(context).apply {
                text = "GameVox • Scan Box & HD Voice Settings"
                setTextColor(Color.parseColor("#00E5FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            sheetCard.addView(titleTv)

            val currentState = TranslatorStateRepository.state.value

            val langHeaderTv = TextView(context).apply {
                text = "Target Language: ${currentState.targetLanguage.displayName} (${currentState.targetLanguage.nativeName})"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(0, dpToPx(8), 0, dpToPx(6))
            }
            sheetCard.addView(langHeaderTv)

            val langScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, 0, 0, dpToPx(10))
            }
            val langRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            TargetLanguage.entries.forEach { lang ->
                val isSelected = lang == TranslatorStateRepository.state.value.targetLanguage
                val chipBtn = Button(context).apply {
                    text = "${lang.nativeName} • ${lang.displayName}"
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(
                        if (isSelected) Color.parseColor("#090D16") else Color.WHITE
                    )
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(16).toFloat()
                        setColor(
                            if (isSelected) Color.parseColor("#00E5FF") else Color.parseColor("#1C2539")
                        )
                        setStroke(dpToPx(1), Color.parseColor("#00E5FF"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dpToPx(34)
                    ).apply {
                        marginEnd = dpToPx(8)
                    }
                    setPadding(dpToPx(12), 0, dpToPx(12), 0)
                    setOnClickListener {
                        TranslatorStateRepository.updateTargetLanguage(context, lang)
                        langHeaderTv.text = "Target Language: ${lang.displayName} (${lang.nativeName})"
                        bubbleLabelView?.text = lang.glyphBadge
                        onRefreshTtsCheck()
                    }
                }
                langRow.addView(chipBtn)
            }
            langScroll.addView(langRow)
            sheetCard.addView(langScroll)

            // Toggle Draggable Target Scan Box & Translation Box (also toggled via Double Tap)
            val boxSwitch = Switch(context).apply {
                text = "Show Scan Box & Translation Box (Double-Tap Icon)"
                setTextColor(Color.WHITE)
                isChecked = isBoxVisible
                setPadding(0, dpToPx(4), 0, dpToPx(6))
                setOnCheckedChangeListener { _, checked ->
                    setBoxesVisibility(checked, notifyCallback = true)
                }
            }
            sheetCard.addView(boxSwitch)

            // Toggle High-Quality Network Voice
            val hqVoiceSwitch = Switch(context).apply {
                text = "High-Quality Network Voice (Non-Robotic)"
                setTextColor(Color.WHITE)
                isChecked = currentState.useHighQualityNetworkVoice
                setPadding(0, dpToPx(4), 0, dpToPx(8))
                setOnCheckedChangeListener { _, checked ->
                    TranslatorStateRepository.updateUseHighQualityNetworkVoice(context, checked)
                    onRefreshTtsCheck()
                }
            }
            sheetCard.addView(hqVoiceSwitch)

            val pitchLabel = TextView(context).apply {
                text = String.format(Locale.US, "TTS Pitch: %.2fx", currentState.ttsPitch)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            }
            sheetCard.addView(pitchLabel)

            val pitchSeek = SeekBar(context).apply {
                max = 140
                progress = ((currentState.ttsPitch - 0.4f) * 100f).toInt().coerceIn(0, 140)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val pitch = (0.4f + progress / 100f)
                        pitchLabel.text = String.format(Locale.US, "TTS Pitch: %.2fx", pitch)
                        if (fromUser) {
                            TranslatorStateRepository.updateTtsPitch(context, pitch)
                            onRefreshTtsCheck()
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            sheetCard.addView(pitchSeek)

            val rateLabel = TextView(context).apply {
                text = String.format(Locale.US, "TTS Speech Rate: %.2fx", currentState.ttsSpeechRate)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setPadding(0, dpToPx(6), 0, 0)
            }
            sheetCard.addView(rateLabel)

            val rateSeek = SeekBar(context).apply {
                max = 140
                progress = ((currentState.ttsSpeechRate - 0.4f) * 100f).toInt().coerceIn(0, 140)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val rate = (0.4f + progress / 100f)
                        rateLabel.text = String.format(Locale.US, "TTS Speech Rate: %.2fx", rate)
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

            val actionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dpToPx(12), 0, 0)
            }

            val testBtn = Button(context).apply {
                text = "Test HD Voice"
                isAllCaps = false
                setTextColor(Color.parseColor("#090D16"))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#00E5FF"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1f).apply {
                    marginEnd = dpToPx(6)
                }
                setOnClickListener {
                    onRefreshTtsCheck()
                    onTestHindiSpeech()
                }
            }

            val centerBoxBtn = Button(context).apply {
                text = "Center Scan Box"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#232E47"))
                    setStroke(dpToPx(1), Color.parseColor("#FFB300"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1f).apply {
                    marginStart = dpToPx(6)
                }
                setOnClickListener {
                    if (!isBoxVisible) {
                        setBoxesVisibility(true, notifyCallback = true)
                    }
                    centerTargetBoxOnScreen()
                    dismissOverlaySettingsBottomSheet()
                }
            }

            actionsRow.addView(testBtn)
            actionsRow.addView(centerBoxBtn)
            sheetCard.addView(actionsRow)

            val bottomRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dpToPx(10), 0, 0)
            }

            val closeBtn = Button(context).apply {
                text = "Close / Dismiss"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#1C2539"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1f).apply {
                    marginEnd = dpToPx(6)
                }
                setOnClickListener { dismissOverlaySettingsBottomSheet() }
            }

            val stopBtn = Button(context).apply {
                text = "Stop Overlay"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#B71C1C"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1f).apply {
                    marginStart = dpToPx(6)
                }
                setOnClickListener {
                    dismissOverlaySettingsBottomSheet()
                    onStopServiceRequested()
                }
            }

            bottomRow.addView(closeBtn)
            bottomRow.addView(stopBtn)
            sheetCard.addView(bottomRow)

            scrimRoot.addView(sheetCard)

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
        dismissOverlaySettingsBottomSheet()
        hideDraggableTargetBox()
        hideHindiTranslationBox()
        mainHandler.post {
            val root = rootOverlayView ?: return@post
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
            } finally {
                rootOverlayView = null
                circularBubbleView = null
                bubbleLabelView = null
                bubbleStatusDot = null
            }
        }
    }

    private fun createBubbleDrawable(isActive: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = if (isActive) {
                intArrayOf(
                    Color.parseColor("#004D40"),
                    Color.parseColor("#00251A")
                )
            } else {
                intArrayOf(
                    Color.parseColor("#1A2744"),
                    Color.parseColor("#0D1322")
                )
            }
            setStroke(
                dpToPx(2),
                if (isActive) Color.parseColor("#00E676") else Color.parseColor("#00E5FF")
            )
        }
    }

    private fun createTargetBoxDrawable(isScanning: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(10).toFloat()
            setColor(Color.argb(22, 0, 230, 118))
            setStroke(
                dpToPx(2),
                if (isScanning) Color.parseColor("#00E676") else Color.parseColor("#00C853")
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
