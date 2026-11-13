package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Delegates grayscale + color-inversion bitmap preprocessing to [ImageProcessor]
 * and provides helper utilities for generating preview thumbnails and sample game subtitle overlays.
 */
object BitmapInverter {

    private val sharedImageProcessor = ImageProcessor()

    /**
     * Applies grayscale (`setSaturation(0f)`) and negative color-inversion (`ColorMatrix`)
     * to [source] using [ImageProcessor], masking out [excludeScreenRect] if present.
     */
    fun invertBitmap(
        source: Bitmap,
        excludeScreenRect: Rect? = null
    ): Bitmap {
        return sharedImageProcessor.preprocessForOcr(
            source = source,
            enableGrayscale = true,
            enableColorInversion = true,
            excludeOverlayRect = excludeScreenRect
        )
    }

    /**
     * Applies grayscale-only preprocessing to [source] via [ImageProcessor].
     */
    fun grayscaleBitmap(source: Bitmap): Bitmap {
        return sharedImageProcessor.applyGrayscale(source)
    }

    /**
     * Creates a scaled-down preview thumbnail so UI cards can display live Before & After
     * bitmaps without holding full-resolution screen buffers in memory.
     */
    fun createPreviewThumbnail(source: Bitmap, maxWidth: Int = 480): Bitmap {
        if (source.width <= maxWidth) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val ratio = maxWidth.toFloat() / source.width.toFloat()
        val targetHeight = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, targetHeight, true)
    }

    /**
     * Renders a realistic synthetic game frame (dark cinematic game scene with white subtitles
     * at the bottom) so the user can test the complete Grayscale + ColorMatrix Inversion +
     * ML Kit OCR + Hindi Translation + Hindi TTS pipeline directly inside the app.
     */
    fun createSampleGameSubtitleBitmap(
        speakerName: String,
        subtitleEnglish: String,
        width: Int = 960,
        height: Int = 420
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(
                    Color.rgb(12, 18, 30),
                    Color.rgb(8, 12, 22),
                    Color.rgb(4, 6, 12)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val boxRect = RectF(
            width * 0.06f,
            height * 0.22f,
            width * 0.94f,
            height * 0.88f
        )
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 10, 14, 20)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(boxRect, 20f, 20f, boxPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val maxLineWidth = boxRect.width() - 80f
        val lines = mutableListOf<String>()

        if (speakerName.isNotBlank()) {
            lines.add(speakerName.trim())
        }

        val words = subtitleEnglish.split(" ")
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (textPaint.measureText(testLine) <= maxLineWidth) {
                currentLine = StringBuilder(testLine)
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        val lineHeight = 52f
        val totalTextHeight = lines.size * lineHeight
        var y = boxRect.centerY() - (totalTextHeight / 2f) + 34f

        for (line in lines) {
            val lineWidth = textPaint.measureText(line)
            val x = boxRect.centerX() - (lineWidth / 2f)
            canvas.drawText(line, x, y, textPaint)
            y += lineHeight
        }

        return bitmap
    }
}
