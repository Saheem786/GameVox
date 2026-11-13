package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Result of running [ImageProcessor] region cropping + grayscale + color-inversion preprocessing
 * followed by Google ML Kit Text Recognition on a captured game screen or overlay bitmap.
 */
data class OcrExtractionResult(
    val extractedText: String,
    val recognizedBlocks: List<DetectedOverlayBlock>,
    val croppedOriginalBitmap: Bitmap,
    val processedBitmap: Bitmap,
    val usedColorInversion: Boolean
)

data class DetectedOverlayBlock(
    val text: String,
    val boundingBox: Rect?,
    val isSubtitleRegion: Boolean
)

/**
 * Processes game screen bitmaps by:
 * 1. Cropping strictly to the user's Draggable & Resizable Target Bounding Box ([targetRegionScreenRect])
 *    so only text inside the square/rectangular overlay box is read.
 * 2. Applying Grayscale and Color-Inversion transformations via Android's [ColorMatrix].
 * 3. Integrating with Google ML Kit [TextRecognition] to extract text from the target region.
 */
class ImageProcessor(
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
) {

    companion object {
        private const val TAG = "ImageProcessor"

        private val COLOR_INVERSION_MATRIX = floatArrayOf(
            -1f,  0f,  0f, 0f, 255f,
             0f, -1f,  0f, 0f, 255f,
             0f,  0f, -1f, 0f, 255f,
             0f,  0f,  0f, 1f,   0f
        )

        private val CONTRAST_BOOST_MATRIX = floatArrayOf(
            1.25f, 0f,    0f,    0f, -20f,
            0f,    1.25f, 0f,    0f, -20f,
            0f,    0f,    1.25f, 0f, -20f,
            0f,    0f,    0f,    1f,   0f
        )
    }

    /**
     * Crops [fullScreenBitmap] strictly to [targetScreenRect] (mapped from real device screen
     * dimensions [rawScreenWidth] x [rawScreenHeight] to the bitmap's dimensions).
     * Insets by 4px on each edge so the overlay box's colored border is excluded from OCR.
     */
    fun cropToTargetRegion(
        fullScreenBitmap: Bitmap,
        targetScreenRect: Rect?,
        rawScreenWidth: Int,
        rawScreenHeight: Int
    ): Bitmap {
        val safeBitmap = ensureSoftwareArgbBitmap(fullScreenBitmap)
        if (targetScreenRect == null || targetScreenRect.isEmpty || rawScreenWidth <= 0 || rawScreenHeight <= 0) {
            return safeBitmap
        }

        val scaleX = safeBitmap.width.toFloat() / rawScreenWidth.toFloat()
        val scaleY = safeBitmap.height.toFloat() / rawScreenHeight.toFloat()

        // Inset slightly so the bounding box's 2dp cyan border/header isn't read by OCR
        val insetPx = 6
        val left = ((targetScreenRect.left + insetPx) * scaleX).toInt().coerceIn(0, safeBitmap.width - 1)
        val top = ((targetScreenRect.top + insetPx) * scaleY).toInt().coerceIn(0, safeBitmap.height - 1)
        val right = ((targetScreenRect.right - insetPx) * scaleX).toInt().coerceIn(left + 1, safeBitmap.width)
        val bottom = ((targetScreenRect.bottom - insetPx) * scaleY).toInt().coerceIn(top + 1, safeBitmap.height)

        val cropWidth = (right - left).coerceAtLeast(1)
        val cropHeight = (bottom - top).coerceAtLeast(1)

        if (cropWidth < 16 || cropHeight < 16) {
            return safeBitmap
        }

        return try {
            Bitmap.createBitmap(safeBitmap, left, top, cropWidth, cropHeight)
        } catch (e: Exception) {
            Log.w(TAG, "Fallback to full bitmap on crop bounds exception", e)
            safeBitmap
        }
    }

    /**
     * Converts [source] into a single-channel luminance grayscale [Bitmap] using [ColorMatrix.setSaturation].
     */
    fun applyGrayscale(source: Bitmap): Bitmap {
        val safeBitmap = ensureSoftwareArgbBitmap(source)
        val output = Bitmap.createBitmap(
            safeBitmap.width.coerceAtLeast(1),
            safeBitmap.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val grayscaleMatrix = ColorMatrix().apply {
            setSaturation(0f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(grayscaleMatrix)
        }
        Canvas(output).drawBitmap(safeBitmap, 0f, 0f, paint)
        return output
    }

    /**
     * Inverts the RGB colors of [source] using [COLOR_INVERSION_MATRIX].
     */
    fun applyColorInversion(source: Bitmap): Bitmap {
        val safeBitmap = ensureSoftwareArgbBitmap(source)
        val output = Bitmap.createBitmap(
            safeBitmap.width.coerceAtLeast(1),
            safeBitmap.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val invertMatrix = ColorMatrix(COLOR_INVERSION_MATRIX)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(invertMatrix)
        }
        Canvas(output).drawBitmap(safeBitmap, 0f, 0f, paint)
        return output
    }

    /**
     * Combined preprocessing pipeline that applies:
     * 1. Grayscale conversion (`setSaturation(0f)`)
     * 2. Color-inversion (`COLOR_INVERSION_MATRIX`)
     * 3. Subtle contrast boost (`CONTRAST_BOOST_MATRIX`)
     * 4. Optional exclusion masking for floating icons
     */
    fun preprocessForOcr(
        source: Bitmap,
        enableGrayscale: Boolean = true,
        enableColorInversion: Boolean = true,
        excludeOverlayRect: Rect? = null
    ): Bitmap {
        val safeBitmap = ensureSoftwareArgbBitmap(source)
        val width = safeBitmap.width.coerceAtLeast(1)
        val height = safeBitmap.height.coerceAtLeast(1)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val combinedMatrix = ColorMatrix()
        if (enableGrayscale) {
            combinedMatrix.setSaturation(0f)
        }
        if (enableColorInversion) {
            combinedMatrix.postConcat(ColorMatrix(COLOR_INVERSION_MATRIX))
        }
        if (enableGrayscale || enableColorInversion) {
            combinedMatrix.postConcat(ColorMatrix(CONTRAST_BOOST_MATRIX))
        }

        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(combinedMatrix)
        }
        canvas.drawBitmap(safeBitmap, 0f, 0f, paint)

        if (excludeOverlayRect != null && !excludeOverlayRect.isEmpty) {
            val maskPaint = Paint().apply {
                color = if (enableColorInversion) Color.WHITE else Color.BLACK
                style = Paint.Style.FILL
            }
            canvas.drawRect(excludeOverlayRect, maskPaint)
        }

        return output
    }

    /**
     * Runs the complete [ImageProcessor] + ML Kit [TextRecognizer] pipeline:
     * 1. If [targetRegionScreenRect] is provided, crops [source] strictly to that bounding box!
     * 2. Preprocesses the cropped region with grayscale + color-inversion.
     * 3. Extracts text blocks using ML Kit [TextRecognizer].
     * 4. Automatically falls back to grayscale-only OCR if no text was found with inversion.
     */
    suspend fun extractTextFromOverlayBitmap(
        source: Bitmap,
        enableColorInversion: Boolean = true,
        excludeOverlayRect: Rect? = null,
        targetRegionScreenRect: Rect? = null,
        rawScreenWidth: Int = 0,
        rawScreenHeight: Int = 0
    ): OcrExtractionResult = withContext(Dispatchers.Default) {
        val regionBitmap = if (targetRegionScreenRect != null && !targetRegionScreenRect.isEmpty && rawScreenWidth > 0 && rawScreenHeight > 0) {
            cropToTargetRegion(source, targetRegionScreenRect, rawScreenWidth, rawScreenHeight)
        } else {
            ensureSoftwareArgbBitmap(source)
        }

        // Only apply excludeOverlayRect if we didn't already crop to the target bounding box
        val maskRect = if (targetRegionScreenRect == null) excludeOverlayRect else null

        val primaryProcessed = preprocessForOcr(
            source = regionBitmap,
            enableGrayscale = true,
            enableColorInversion = enableColorInversion,
            excludeOverlayRect = maskRect
        )

        val primaryBlocks = runMlKitRecognition(primaryProcessed, primaryProcessed.height)
        val primaryText = formatOverlayBlocks(primaryBlocks, isCroppedBox = targetRegionScreenRect != null)

        if (primaryText.isNotBlank() || !enableColorInversion) {
            return@withContext OcrExtractionResult(
                extractedText = primaryText,
                recognizedBlocks = primaryBlocks,
                croppedOriginalBitmap = regionBitmap,
                processedBitmap = primaryProcessed,
                usedColorInversion = enableColorInversion
            )
        }

        val grayscaleOnlyBitmap = preprocessForOcr(
            source = regionBitmap,
            enableGrayscale = true,
            enableColorInversion = false,
            excludeOverlayRect = maskRect
        )
        val fallbackBlocks = runMlKitRecognition(grayscaleOnlyBitmap, grayscaleOnlyBitmap.height)
        val fallbackText = formatOverlayBlocks(fallbackBlocks, isCroppedBox = targetRegionScreenRect != null)

        if (fallbackText.isNotBlank()) {
            OcrExtractionResult(
                extractedText = fallbackText,
                recognizedBlocks = fallbackBlocks,
                croppedOriginalBitmap = regionBitmap,
                processedBitmap = grayscaleOnlyBitmap,
                usedColorInversion = false
            )
        } else {
            OcrExtractionResult(
                extractedText = "",
                recognizedBlocks = emptyList(),
                croppedOriginalBitmap = regionBitmap,
                processedBitmap = primaryProcessed,
                usedColorInversion = enableColorInversion
            )
        }
    }

    private suspend fun runMlKitRecognition(
        processedBitmap: Bitmap,
        frameHeight: Int
    ): List<DetectedOverlayBlock> {
        return try {
            val inputImage = InputImage.fromBitmap(processedBitmap, 0)
            val visionText: Text = recognizer.process(inputImage).await()
            parseDetectedOverlayBlocks(visionText, frameHeight)
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit TextRecognition failed on processed bitmap", e)
            emptyList()
        }
    }

    private fun parseDetectedOverlayBlocks(
        visionText: Text,
        frameHeight: Int
    ): List<DetectedOverlayBlock> {
        if (visionText.textBlocks.isEmpty()) return emptyList()

        val subtitleZoneTopThreshold = (frameHeight * 0.15f).toInt()

        return visionText.textBlocks
            .sortedWith(
                compareBy<Text.TextBlock> { it.boundingBox?.top ?: 0 }
                    .thenBy { it.boundingBox?.left ?: 0 }
            )
            .mapNotNull { block ->
                val cleanedBlockText = block.lines
                    .map { it.text.trim() }
                    .filter { it.length >= 2 }
                    .joinToString("\n")
                    .trim()

                if (cleanedBlockText.length < 2) {
                    null
                } else {
                    val box = block.boundingBox
                    val isSubtitleOverlay = box == null || box.centerY() >= subtitleZoneTopThreshold
                    DetectedOverlayBlock(
                        text = cleanedBlockText,
                        boundingBox = box,
                        isSubtitleRegion = isSubtitleOverlay
                    )
                }
            }
    }

    private fun formatOverlayBlocks(
        blocks: List<DetectedOverlayBlock>,
        isCroppedBox: Boolean
    ): String {
        if (blocks.isEmpty()) return ""
        // When cropped to the user's draggable bounding box, include ALL text inside the box!
        val targetBlocks = if (isCroppedBox) {
            blocks
        } else {
            val subtitleBlocks = blocks.filter { it.isSubtitleRegion }
            subtitleBlocks.ifEmpty { blocks }
        }
        return targetBlocks
            .joinToString("\n") { it.text }
            .trim()
    }

    private fun ensureSoftwareArgbBitmap(source: Bitmap): Bitmap {
        return if (source.config == Bitmap.Config.ARGB_8888 && !source.isRecycled) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        }
    }

    fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {
        }
    }
}
