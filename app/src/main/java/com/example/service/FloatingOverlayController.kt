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
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.example.data.TranslatorStateRepository
import com.example.data.TtsEngineStatus
import java.util.Locale
import kotlin.math.abs

/**
 * Manages the WindowManager floating circular icon, the live OCR Debug TextView positioned
 * directly below the floating icon, and the Long-Press Settings BottomSheet overlay that
 * works over any running game.
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

    private var rootOverlayView: LinearLayout? = null
    private var circularBubbleView: FrameLayout? = null
    private var bubbleLabelView: TextView? = null
    private var bubbleStatusDot: View? = null
    private var debugTextView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var bottomSheetOverlayView: FrameLayout? = null

    val isAttached: Boolean
        get() = rootOverlayView != null

    /**
     * Returns the current screen bounding box of the floating widget + debug TextView
     * so MediaProjection can mask it out and avoid OCR-reading its own debug text.
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

    @SuppressLint("ClickableViewAccessibility")
    fun showFloatingOverlay(): Boolean {
        if (rootOverlayView != null) return true
        if (!Settings.canDrawOverlays(context)) return false

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
            x = dpToPx(16)
            y = dpToPx(160)
        }
        overlayParams = params

        // Root vertical container holding the Circular Floating Icon and the Debug TextView below it
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        }

        // 1. Circular Floating Icon (60dp x 60dp)
        val bubbleSize = dpToPx(60)
        val bubbleFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize)
            background = createBubbleDrawable(isScanning = false)
            elevation = dpToPx(10).toFloat()
        }

        val glyphText = TextView(context).apply {
            text = "अ/A"
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

        // Small status dot in bottom-right of circular icon
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

        // 2. Small Debug TextView directly below the floating icon
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
            text = "IDLE • Tap to Scan\nHold for TTS Settings"
        }

        container.addView(bubbleFrame)
        container.addView(debugTv)

        // Gesture & Drag handling on the circular icon:
        // - Drag: Moves floating icon + debug TextView across screen
        // - Single Tap: Toggle OCR/Screen capture Start/Stop
        // - Long Press (Hold): Open BottomSheet dialog with settings
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val longPressTimeout = 500L

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var longPressTriggered = false

        val longPressRunnable = Runnable {
            if (!isDragging) {
                longPressTriggered = true
                bubbleFrame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showOverlaySettingsBottomSheet()
                TranslatorStateRepository.setShowSettingsBottomSheet(true)
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
                        bubbleFrame.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onSingleTapToggleCapture()
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

        return try {
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
    }

    fun updateScanningState(isScanning: Boolean) {
        mainHandler.post {
            circularBubbleView?.background = createBubbleDrawable(isScanning)
            bubbleLabelView?.text = if (isScanning) "REC" else "अ/A"
            bubbleStatusDot?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    if (isScanning) Color.parseColor("#00E676") else Color.parseColor("#FFB300")
                )
                setStroke(dpToPx(2), Color.parseColor("#090D16"))
            }
            if (!isScanning) {
                val state = TranslatorStateRepository.state.value
                debugTextView?.text = "PAUSED • Tap to Start\nOCR: ${state.lastExtractedEnglish.take(36)}"
            } else {
                debugTextView?.text = "SCANNING (2s) • Inverted OCR\nWaiting for subtitles..."
            }
        }
    }

    fun updateDebugReadout(englishText: String, hindiText: String, statusNote: String) {
        mainHandler.post {
            val cleanEng = if (englishText.isBlank()) "(no text)" else englishText.take(55)
            val cleanHin = if (hindiText.isBlank()) "..." else hindiText.take(55)
            debugTextView?.text = "[$statusNote]\nEN: $cleanEng\nHI: $cleanHin"
        }
    }

    /**
     * Displays a BottomSheet-style settings dialog directly over the screen using WindowManager
     * when the user long-presses (holds) the floating icon while playing a game.
     */
    fun showOverlaySettingsBottomSheet() {
        mainHandler.post {
            if (bottomSheetOverlayView != null) return@post
            if (!Settings.canDrawOverlays(context)) return@post

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

            // Drag handle pill
            val handleView = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(5)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dpToPx(14)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(3).toFloat()
                    setColor(Color.parseColor("#4A5B78"))
                }
            }
            sheetCard.addView(handleView)

            val titleTv = TextView(context).apply {
                text = "GameVox Hindi • Quick Settings"
                setTextColor(Color.parseColor("#00E5FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            sheetCard.addView(titleTv)

            val subtitleTv = TextView(context).apply {
                text = "Adjust Hindi TTS (hi-IN) Pitch, Speed & ColorMatrix Inversion"
                setTextColor(Color.parseColor("#9FB0C7"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dpToPx(2), 0, dpToPx(14))
            }
            sheetCard.addView(subtitleTv)

            val currentState = TranslatorStateRepository.state.value

            // TTS Pitch Slider (0.4f .. 1.8f, default 0.8f)
            val pitchLabel = TextView(context).apply {
                text = String.format(Locale.US, "TTS Pitch: %.2fx (Default 0.80x)", currentState.ttsPitch)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            sheetCard.addView(pitchLabel)

            val pitchSeek = SeekBar(context).apply {
                max = 140 // maps 0..140 -> 0.40f..1.80f
                progress = ((currentState.ttsPitch - 0.4f) * 100f).toInt().coerceIn(0, 140)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val pitch = (0.4f + progress / 100f)
                        pitchLabel.text = String.format(Locale.US, "TTS Pitch: %.2fx (Default 0.80x)", pitch)
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

            // TTS Speed / Speech Rate Slider (0.4f .. 1.8f, default 0.9f)
            val rateLabel = TextView(context).apply {
                text = String.format(Locale.US, "TTS Speech Rate: %.2fx (Default 0.90x)", currentState.ttsSpeechRate)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dpToPx(10), 0, 0)
            }
            sheetCard.addView(rateLabel)

            val rateSeek = SeekBar(context).apply {
                max = 140
                progress = ((currentState.ttsSpeechRate - 0.4f) * 100f).toInt().coerceIn(0, 140)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val rate = (0.4f + progress / 100f)
                        rateLabel.text = String.format(Locale.US, "TTS Speech Rate: %.2fx (Default 0.90x)", rate)
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

            // ColorMatrix Inversion Toggle
            val invertSwitch = Switch(context).apply {
                text = "Invert Bitmap Colors (White → Black Text OCR)"
                setTextColor(Color.WHITE)
                isChecked = currentState.invertColorsEnabled
                setPadding(0, dpToPx(12), 0, dpToPx(12))
                setOnCheckedChangeListener { _, checked ->
                    TranslatorStateRepository.updateInvertColors(context, checked)
                }
            }
            sheetCard.addView(invertSwitch)

            // Hindi TTS Status Readout
            val ttsStatusTv = TextView(context).apply {
                val status = TranslatorStateRepository.state.value.ttsStatus
                text = "Hindi TTS Status: ${status.label}"
                setTextColor(
                    if (status == TtsEngineStatus.READY) Color.parseColor("#00E676")
                    else Color.parseColor("#FFB300")
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dpToPx(4), 0, dpToPx(12))
            }
            sheetCard.addView(ttsStatusTv)

            // Action Buttons Row 1: Test Voice & Check/Install Hindi Data
            val actionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val testBtn = Button(context).apply {
                text = "Test Hindi TTS"
                isAllCaps = false
                setTextColor(Color.parseColor("#090D16"))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#00E5FF"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(44), 1f).apply {
                    marginEnd = dpToPx(6)
                }
                setOnClickListener {
                    onRefreshTtsCheck()
                    onTestHindiSpeech()
                }
            }

            val installVoiceBtn = Button(context).apply {
                text = "Download Hindi Voice"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#232E47"))
                    setStroke(dpToPx(1), Color.parseColor("#FFB300"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(44), 1f).apply {
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

            // Action Buttons Row 2: Close Sheet & Stop Service
            val bottomRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dpToPx(10), 0, 0)
            }

            val closeBtn = Button(context).apply {
                text = "Close Settings"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#1C2539"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(44), 1f).apply {
                    marginEnd = dpToPx(6)
                }
                setOnClickListener { dismissOverlaySettingsBottomSheet() }
            }

            val stopBtn = Button(context).apply {
                text = "Stop Floating Icon"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#B71C1C"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(44), 1f).apply {
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
            }
        }
    }

    fun dismissOverlaySettingsBottomSheet() {
        mainHandler.post {
            val sheet = bottomSheetOverlayView ?: return@post
            try {
                windowManager.removeView(sheet)
            } catch (_: Exception) {
            } finally {
                bottomSheetOverlayView = null
            }
        }
    }

    fun destroyOverlay() {
        dismissOverlaySettingsBottomSheet()
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

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
