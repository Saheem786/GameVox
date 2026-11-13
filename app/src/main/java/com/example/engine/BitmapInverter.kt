package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Handles preprocessing of captured game screen bitmaps before passing them to Google ML Kit OCR.
 *
 * CRITICAL REQUIREMENT:
 * Game subtitles are typically white or bright text rendered on dark or shadowed backgrounds.
 * Applying a negative/inversion ColorMatrix converts white text (255, 255, 255) into sharp
 * black text (0, 0, 0) on a light background, dramatically improving ML Kit Text Recognition accuracy.
 */
object BitmapInverter {

    private val INVERT_MATRIX_VALUES = floatArrayOf(
        -1f,  0f,  0f, 0f, 255f,
         0f, -1f,  0f, 0f, 255f,
         0f,  0f, -1f, 0f, 255f,
         0f,  0f,  0f, 1f,   0f
    )

    /**
     * Inverts the RGB channels of [source] using Android's [ColorMatrix] and [ColorMatrixColorFilter].
     * Optionally masks out [excludeScreenRect] (e.g., our own Floating Overlay + Debug TextView)
     * prior to inversion so the OCR engine never reads its own floating debug text.
     */
    fun invertBitmap(
        source: Bitmap,
        excludeScreenRect: Rect? = null
    ): Bitmap {
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(output)
        val colorMatrix = ColorMatrix(INVERT_MATRIX_VALUES)
        val invertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        canvas.drawBitmap(source, 0f, 0f, invertPaint)

        // If our floating overlay bounds are provided, fill that region with solid white
        // on the inverted bitmap (which corresponds to empty paper background for OCR)
        // so ML Kit ignores our own floating Debug TextView.
        if (excludeScreenRect != null && !excludeScreenRect.isEmpty) {
            val maskPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawRect(excludeScreenRect, maskPaint)
        }

        return output
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
     * at the bottom) so the user can test the complete ColorMatrix Inversion + ML Kit OCR +
     * Hindi Translation + Hindi TTS pipeline directly inside the app or emulator.
     */
    fun createSampleGameSubtitleBitmap(
        speakerName: String,
        subtitleEnglish: String,
        width: Int = 960,
        height: Int = 420
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Dark atmospheric game scene backdrop
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

        // Subtle dark HUD subtitle box at bottom (like RPG / Action games)
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

        // Draw crisp white game dialogue text on the dark subtitle box
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val fullText = if (speakerName.isNotBlank()) {
            "$speakerName: $subtitleEnglish"
        } else {
            subtitleEnglish
        }

        // Word-wrap the subtitle lines inside the dark game dialogue box
        val maxLineWidth = boxRect.width() - 80f
        val words = fullText.split(" ")
        val lines = mutableListOf<String>()
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
