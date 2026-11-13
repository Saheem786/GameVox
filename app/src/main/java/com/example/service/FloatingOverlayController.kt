package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
import com.example.data.TtsEngineStatus
import java.util.Locale
import kotlin.math.abs

/**
 * Manages:
 * 1. The WindowManager Draggable Circular Control Icon + Live Hindi Subtitle Debug Readout
 * 2. The Draggable & Resizable Transparent Target Bounding Box (`targetBoxOverlayView`) that the user
 *    can position and resize over any game subtitle area so ONLY text inside the box is extracted!
 * 3. Single guarded Settings BottomSheet dialog on Long Press.
 */
class FloatingOverlayController(
    private val context: Context,
    private val onSingleTapToggleCapture: () -> Unit,
    private val onTestHindiSpeech: () -> Unit,
    private val onInstallHindiVoiceData: () -> Unit,
    private val onRefreshTtsCheck: () -> Unit,
    private val onStopServiceRequested: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // 1. Floating Circular Control Icon + Debug TextView
    private var rootOverlayView: LinearLayout? = null
    private var circularBubbleView: FrameLayout? = null
    private var bubbleLabelView: TextView? = null
    private var bubbleStatusDot: View? = null
    private var debugTextView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    // 2. Draggable & Resizable Transparent Target Scan Box
    private var targetBoxView: FrameLayout? = null
    private var targetBoxBorderDrawable: GradientDrawable? = null
    private var targetBoxHeaderLabel: TextView? = null
    private var targetBoxParams: WindowManager.LayoutParams? = null

    // 3. Guarded Settings Sheet
    @Volatile
    private var bottomSheetOverlayView: FrameLayout? = null

    @Volatile
    private var isDialogOpeningOrShown: Boolean = false

    @Volatile
    private var lastTapElapsedMs: Long = 0L

    val isAttached: Boolean
        get() = rootOverlayView != null

    /**
     * Returns the current screen bounding box of the floating circular icon + debug TextView.
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
     * and AccessibilityNodeInfo ONLY read text inside this box!
     */
    fun getTargetBoxScreenBounds(): Rect? {
        val box = targetBoxView ?: return TranslatorStateRepository.state.value.targetBoxScreenRect
        val loc = IntArray(2)
        box.getLocationOnScreen(loc)
        val w = box.width
        val h = box.height
        if (w <= 0 || h <= 0) {
            return TranslatorStateRepository.state.value.targetBoxScreenRect
        }
        val rect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
        TranslatorStateRepository.updateTargetBoxScreenRect(rect)
        return rect
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showFloatingOverlay(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (rootOverlayView != null) {
            if (TranslatorStateRepository.state.value.showTargetBoxOverlay && targetBoxView == null) {
                showDraggableTargetBox()
            }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(12)
            y = dpToPx(110)
        }
        overlayParams = params

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        }

        // Circular Floating Icon (58dp x 58dp)
        val bubbleSize = dpToPx(58)
        val bubbleFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize)
            background = createBubbleDrawable(isScanning = false)
            elevation = dpToPx(10).toFloat()
        }

        val currentLang = TranslatorStateRepository.state.value.targetLanguage
        val glyphText = TextView(context).apply {
            text = currentLang.glyphBadge
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        bubbleFrame.addView(glyphText)

        val dotSize = dpToPx(14)
        val statusDot = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = dpToPx(3)
                marginEnd = dpToPx(3)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFB300"))
                setStroke(dpToPx(2), Color.parseColor("#090D16"))
            }
        }
        bubbleFrame.addView(statusDot)

        // Small Debug TextView directly below the floating icon
        val debugTv = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
            }
            maxWidth = dpToPx(220)
            minWidth = dpToPx(130)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            setTextColor(Color.parseColor("#E2F1FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.argb(225, 10, 15, 26))
                setStroke(dpToPx(1), Color.parseColor("#00E5FF"))
            }
            text = "Drag Cyan Box over text\nTap Icon to Read & Speak"
        }

        container.addView(bubbleFrame)
        container.addView(debugTv)

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val longPressTimeout = 550L

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var longPressTriggered = false

        val longPressRunnable = Runnable {
            if (!isDragging && !longPressTriggered && !isDialogOpeningOrShown) {
                longPressTriggered = true
                bubbleFrame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showOverlaySettingsBottomSheet()
            }
        }

        bubbleFrame.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressTriggered = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
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
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!isDragging && !longPressTriggered) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTapElapsedMs > 400L) {
                            lastTapElapsedMs = now
                            bubbleFrame.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onSingleTapToggleCapture()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        val added = try {
            windowManager.addView(container, params)
            rootOverlayView = container
            circularBubbleView = bubbleFrame
            bubbleLabelView = glyphText
            bubbleStatusDot = statusDot
            debugTextView = debugTv
            true
        } catch (e: Exception) {
            false
        }

        if (added && TranslatorStateRepository.state.value.showTargetBoxOverlay) {
            showDraggableTargetBox()
        }

        return added
    }

    /**
     * Creates and attaches the Draggable & Resizable Transparent Square/Rectangular Bounding Box
     * (`WindowManager` overlay View) that the user can drag over any part of a running game
     * and resize from the bottom-right corner handle.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showDraggableTargetBox() {
        mainHandler.post {
            if (targetBoxView != null) return@post
            if (!Settings.canDrawOverlays(context)) return@post

            val metrics = context.resources.displayMetrics
            val screenW = metrics.widthPixels.coerceAtLeast(360)
            val screenH = metrics.heightPixels.coerceAtLeast(640)

            val defaultBoxWidth = (screenW * 0.78f).toInt().coerceIn(dpToPx(200), dpToPx(380))
            val defaultBoxHeight = dpToPx(150)
            val defaultX = ((screenW - defaultBoxWidth) / 2).coerceAtLeast(dpToPx(16))
            val defaultY = (screenH * 0.58f).toInt().coerceIn(dpToPx(120), screenH - defaultBoxHeight - dpToPx(60))

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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = defaultX
                y = defaultY
            }
            targetBoxParams = boxParams

            val isScanning = TranslatorStateRepository.state.value.isCapturingOcr
            val borderDrawable = createTargetBoxDrawable(isScanning)
            targetBoxBorderDrawable = borderDrawable

            val boxFrame = FrameLayout(context).apply {
                background = borderDrawable
            }

            // Top Drag Handle Pill inside the bounding box
            val headerBar = TextView(context).apply {
                text = if (isScanning) {
                    "✥ SCANNING THIS BOX ONLY • Drag to Move"
                } else {
                    "✥ DRAG SCAN BOX OVER GAME TEXT"
                }
                setTextColor(Color.parseColor("#090D16"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dpToPx(10), dpToPx(3), dpToPx(10), dpToPx(3))
                background = GradientDrawable().apply {
                    cornerRadii = floatArrayOf(
                        dpToPx(8).toFloat(), dpToPx(8).toFloat(),
                        dpToPx(8).toFloat(), dpToPx(8).toFloat(),
                        dpToPx(6).toFloat(), dpToPx(6).toFloat(),
                        dpToPx(6).toFloat(), dpToPx(6).toFloat()
                    )
                    setColor(
                        if (isScanning) Color.parseColor("#00E676") else Color.parseColor("#00E5FF")
                    )
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    topMargin = dpToPx(4)
                    marginStart = dpToPx(6)
                }
            }
            targetBoxHeaderLabel = headerBar
            boxFrame.addView(headerBar)

            // Bottom-Right Corner Resize Grip ("◢ RESIZE")
            val resizeGripSize = dpToPx(38)
            val resizeHandle = TextView(context).apply {
                text = "◢"
                setTextColor(Color.parseColor("#090D16"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadii = floatArrayOf(
                        dpToPx(10).toFloat(), dpToPx(10).toFloat(),
                        0f, 0f,
                        dpToPx(10).toFloat(), dpToPx(10).toFloat(),
                        0f, 0f
                    )
                    setColor(Color.parseColor("#FFB300"))
                }
                layoutParams = FrameLayout.LayoutParams(resizeGripSize, resizeGripSize).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                }
            }
            boxFrame.addView(resizeHandle)

            // Drag listener on the bounding box to move it anywhere across the game screen
            var dragStartX = 0
            var dragStartY = 0
            var touchStartX = 0f
            var touchStartY = 0f

            boxFrame.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartX = boxParams.x
                        dragStartY = boxParams.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchStartX).toInt()
                        val dy = (event.rawY - touchStartY).toInt()
                        boxParams.x = (dragStartX + dx).coerceIn(0, (screenW - boxParams.width).coerceAtLeast(0))
                        boxParams.y = (dragStartY + dy).coerceIn(0, (screenH - boxParams.height).coerceAtLeast(0))
                        try {
                            windowManager.updateViewLayout(boxFrame, boxParams)
                            updateStoredTargetBoxRect(boxFrame, boxParams)
                        } catch (_: Exception) {
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        updateStoredTargetBoxRect(boxFrame, boxParams)
                        true
                    }
                    else -> false
                }
            }

            // Resize listener on the bottom-right corner handle to resize width & height dynamically
            var initialW = 0
            var initialH = 0
            var resizeTouchX = 0f
            var resizeTouchY = 0f
            val minW = dpToPx(130)
            val minH = dpToPx(75)

            resizeHandle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialW = boxParams.width
                        initialH = boxParams.height
                        resizeTouchX = event.rawX
                        resizeTouchY = event.rawY
                        resizeHandle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dw = (event.rawX - resizeTouchX).toInt()
                        val dh = (event.rawY - resizeTouchY).toInt()
                        val maxW = (screenW - boxParams.x).coerceAtLeast(minW)
                        val maxH = (screenH - boxParams.y).coerceAtLeast(minH)
                        boxParams.width = (initialW + dw).coerceIn(minW, maxW)
                        boxParams.height = (initialH + dh).coerceIn(minH, maxH)
                        try {
                            windowManager.updateViewLayout(boxFrame, boxParams)
                            updateStoredTargetBoxRect(boxFrame, boxParams)
                        } catch (_: Exception) {
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        updateStoredTargetBoxRect(boxFrame, boxParams)
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

    fun hideDraggableTargetBox() {
        mainHandler.post {
            val box = targetBoxView ?: return@post
            try {
                windowManager.removeView(box)
            } catch (_: Exception) {
            } finally {
                targetBoxView = null
                targetBoxHeaderLabel = null
                targetBoxBorderDrawable = null
                TranslatorStateRepository.updateTargetBoxScreenRect(null)
            }
        }
    }

    private fun updateStoredTargetBoxRect(boxView: View, params: WindowManager.LayoutParams) {
        val loc = IntArray(2)
        boxView.getLocationOnScreen(loc)
        val left = if (loc[0] > 0 || loc[1] > 0) loc[0] else params.x
        val top = if (loc[0] > 0 || loc[1] > 0) loc[1] else params.y
        val width = if (boxView.width > 0) boxView.width else params.width
        val height = if (boxView.height > 0) boxView.height else params.height
        TranslatorStateRepository.updateTargetBoxScreenRect(
            Rect(left, top, left + width, top + height)
        )
    }

    fun updateScanningState(isScanning: Boolean) {
        mainHandler.post {
            val lang = TranslatorStateRepository.state.value.targetLanguage
            circularBubbleView?.background = createBubbleDrawable(isScanning)
            bubbleLabelView?.text = if (isScanning) "REC" else lang.glyphBadge
            bubbleStatusDot?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    if (isScanning) Color.parseColor("#00E676") else Color.parseColor("#FFB300")
                )
                setStroke(dpToPx(2), Color.parseColor("#090D16"))
            }

            targetBoxView?.background = createTargetBoxDrawable(isScanning)
            targetBoxHeaderLabel?.apply {
                text = if (isScanning) {
                    "✥ READING THIS BOX (${lang.displayName}) • Drag/Resize"
                } else {
                    "✥ DRAG SCAN BOX OVER GAME TEXT"
                }
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(
                        if (isScanning) Color.parseColor("#00E676") else Color.parseColor("#00E5FF")
                    )
                }
            }

            if (!isScanning) {
                val state = TranslatorStateRepository.state.value
                debugTextView?.text = "PAUSED • Tap Icon to Scan\nBox: ${state.lastExtractedEnglish.take(34)}"
            } else {
                debugTextView?.text = "SCANNING BOX (2s) • HD TTS\nReading inside cyan box..."
            }
        }
    }

    fun updateDebugReadout(englishText: String, hindiText: String, statusNote: String) {
        mainHandler.post {
            val cleanEng = if (englishText.isBlank()) "(no text in box)" else englishText.take(55)
            val cleanHin = if (hindiText.isBlank()) "..." else hindiText.take(55)
            debugTextView?.text = "[$statusNote]\nBOX: $cleanEng\nHI: $cleanHin"
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
                        if (!TranslatorStateRepository.state.value.isCapturingOcr) {
                            bubbleLabelView?.text = lang.glyphBadge
                        }
                        onRefreshTtsCheck()
                    }
                }
                langRow.addView(chipBtn)
            }
            langScroll.addView(langRow)
            sheetCard.addView(langScroll)

            // Toggle Draggable Target Scan Box
            val boxSwitch = Switch(context).apply {
                text = "Show Draggable & Resizable Scan Box"
                setTextColor(Color.WHITE)
                isChecked = currentState.showTargetBoxOverlay
                setPadding(0, dpToPx(4), 0, dpToPx(6))
                setOnCheckedChangeListener { _, checked ->
                    TranslatorStateRepository.updateShowTargetBoxOverlay(context, checked)
                    if (checked) showDraggableTargetBox() else hideDraggableTargetBox()
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

            val installVoiceBtn = Button(context).apply {
                text = "Download Voice Pack"
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
                    dismissOverlaySettingsBottomSheet()
                    onInstallHindiVoiceData()
                }
            }

            actionsRow.addView(testBtn)
            actionsRow.addView(installVoiceBtn)
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
                debugTextView = null
            }
        }
    }

    private fun createBubbleDrawable(isScanning: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = if (isScanning) {
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
                dpToPx(3),
                if (isScanning) Color.parseColor("#00E676") else Color.parseColor("#00E5FF")
            )
        }
    }

    private fun createTargetBoxDrawable(isScanning: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            // Transparent fill so game subtitles underneath are 100% readable
            setColor(
                if (isScanning) Color.argb(24, 0, 230, 118)
                else Color.argb(24, 0, 229, 255)
            )
            setStroke(
                dpToPx(2),
                if (isScanning) Color.parseColor("#00E676") else Color.parseColor("#00E5FF")
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
