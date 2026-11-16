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
    val usedColorInversion: Boolean,
    val skippedUnchangedFrame: Boolean = false,
    val ocrLatencyMs: Long = 0L,
    val roiWidth: Int = 0,
    val roiHeight: Int = 0,
    val preprocessPassUsed: String = ""
)

data class DetectedOverlayBlock(
    val text: String,
    val boundingBox: Rect?,
    val isSubtitleRegion: Boolean
)

/**
 * Processes game screen bitmaps by:
 * 1. Cropping strictly to the user's Draggable & Resizable Target Bounding Box ([targetRegionScreenRect])
 *    at high native resolution BEFORE any scaling or OCR preprocessing.
 * 2. Upscaling small cropped subtitle strips so tiny 12–16sp game subtitles remain crisp.
 * 3. Running multi-pass OCR preprocessing:
 *    - Pass 1: Inverted contrast-boosted grayscale (white/light subtitles on dark backdrop).
 *    - Pass 2: Bright White & Yellow Subtitle Isolation (cuts out moving foliage/UI backgrounds & outlines).
 *    - Pass 3: Natural contrast-boosted grayscale (dark subtitles on light dialogue cards).
 * 4. Guarding frame-diff skip logic with dense 32x12 luma grid + forced periodic OCR so text never gets dropped.
 */
class ImageProcessor(
    private val recognizerProvider: () -> TextRecognizer = {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    },
    private val customBlockRecognitionOverride: (suspend (processedBitmap: Bitmap, frameHeight: Int, rotationDegrees: Int) -> List<DetectedOverlayBlock>)? = null
) {

    @Volatile
    private var hasInitializedRecognizer: Boolean = false

    private val recognizer: TextRecognizer by lazy {
        hasInitializedRecognizer = true
        recognizerProvider()
    }

    @Volatile
    private var previousRegionLumaSample: IntArray? = null

    @Volatile
    private var consecutiveSkippedFrames: Int = 0

    @Volatile
    private var lastForcedOcrTimestampMs: Long = 0L

    fun resetFrameDiffCache() {
        previousRegionLumaSample = null
        consecutiveSkippedFrames = 0
        lastForcedOcrTimestampMs = 0L
    }

    companion object {
        private const val TAG = "ImageProcessor"
        private const val SAMPLE_GRID_W = 32
        private const val SAMPLE_GRID_H = 12
        private const val MAX_CONSECUTIVE_SKIPS = 2
        private const val FORCED_OCR_INTERVAL_MS = 1800L

        private val COLOR_INVERSION_MATRIX = floatArrayOf(
            -1f,  0f,  0f, 0f, 255f,
             0f, -1f,  0f, 0f, 255f,
             0f,  0f, -1f, 0f, 255f,
             0f,  0f,  0f, 1f,   0f
        )

        private val CONTRAST_BOOST_MATRIX = floatArrayOf(
            1.35f, 0f,    0f,    0f, -24f,
            0f,    1.35f, 0f,    0f, -24f,
            0f,    0f,    1.35f, 0f, -24f,
            0f,    0f,    0f,    1f,   0f
        )

        /**
         * Samples an 8x8 luminance grid across the bitmap and returns true if nearly all
         * pixels are solid black (typical of Android MediaProjection blocking FLAG_SECURE windows).
         */
        fun isLikelyProtectedOrBlackFrame(bitmap: Bitmap): Boolean {
            if (bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) return false
            val stepX = (bitmap.width / 8).coerceAtLeast(1)
            val stepY = (bitmap.height / 8).coerceAtLeast(1)
            var maxLuma = 0
            var varianceAccumulator = 0
            var prevLuma = -1
            var y = stepY / 2
            while (y < bitmap.height) {
                var x = stepX / 2
                while (x < bitmap.width) {
                    val px = bitmap.getPixel(x, y)
                    val luma = ((Color.red(px) * 77 + Color.green(px) * 150 + Color.blue(px) * 29) shr 8)
                    if (luma > maxLuma) maxLuma = luma
                    if (prevLuma >= 0) {
                        varianceAccumulator += kotlin.math.abs(luma - prevLuma)
                    }
                    prevLuma = luma
                    x += stepX
                }
                y += stepY
            }
            return maxLuma <= 4 && varianceAccumulator <= 8
        }

        /**
         * Converts an acquired [android.media.Image] video frame (`PixelFormat.RGBA_8888`) from
         * [android.media.ImageReader] into a clean software `ARGB_8888` [Bitmap], stripping any
         * hardware `rowStride` padding bytes.
         */
        fun extractBitmapFromMediaImage(image: android.media.Image): Bitmap? {
            return try {
                val width = image.width
                val height = image.height
                if (width <= 0 || height <= 0) return null
                val planes = image.planes
                if (planes.isEmpty()) return null
                val buffer = planes[0].buffer ?: return null
                buffer.rewind()
                val pixelStride = planes[0].pixelStride.coerceAtLeast(4)
                val rowStride = planes[0].rowStride.coerceAtLeast(width * pixelStride)
                val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)

                val paddedWidth = width + rowPadding / pixelStride
                val fullBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                fullBitmap.copyPixelsFromBuffer(buffer)

                if (rowPadding == 0 || paddedWidth == width) {
                    fullBitmap
                } else {
                    val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
                    fullBitmap.recycle()
                    cropped
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract Bitmap from video frame Image", e)
                null
            }
        }
    }

    /**
     * Crops [fullScreenBitmap] strictly to [targetScreenRect] (mapped from real device screen
     * dimensions [rawScreenWidth] x [rawScreenHeight] to the bitmap's native pixel dimensions).
     * Insets by 6px on each edge so the overlay box's colored border is excluded from OCR.
     */
    fun cropToTargetRegion(
        fullScreenBitmap: Bitmap,
        targetScreenRect: Rect?,
        rawScreenWidth: Int,
        rawScreenHeight: Int
    ): Bitmap {
        val safeBitmap = ensureSoftwareArgbBitmap(fullScreenBitmap)
        if (targetScreenRect == null || targetScreenRect.isEmpty || rawScreenWidth <= 0 || rawScreenHeight <= 0) {
            return if (safeBitmap === fullScreenBitmap) {
                safeBitmap.copy(Bitmap.Config.ARGB_8888, false) ?: safeBitmap
            } else {
                safeBitmap
            }
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
            return if (safeBitmap === fullScreenBitmap) {
                safeBitmap.copy(Bitmap.Config.ARGB_8888, false) ?: safeBitmap
            } else {
                safeBitmap
            }
        }

        return try {
            val cropped = Bitmap.createBitmap(safeBitmap, left, top, cropWidth, cropHeight)
            if (safeBitmap !== fullScreenBitmap && !safeBitmap.isRecycled) {
                safeBitmap.recycle()
            }
            cropped
        } catch (e: Exception) {
            Log.w(TAG, "Fallback to full bitmap on crop bounds exception", e)
            safeBitmap
        }
    }

    /**
     * Upscales tiny cropped subtitle regions (e.g., width < 520 or height < 180) up to 2x with
     * bilinear filtering so small outlined game subtitles remain legible to ML Kit Text Recognition.
     */
    fun upscaleSmallRoiIfNeeded(regionBitmap: Bitmap): Bitmap {
        if (regionBitmap.isRecycled || regionBitmap.width <= 0 || regionBitmap.height <= 0) {
            return regionBitmap
        }
        val needUpscale = regionBitmap.width < 520 || regionBitmap.height < 180
        if (!needUpscale) return regionBitmap
        val scaleFactor = if (regionBitmap.height < 100) 2.0f else 1.6f
        val targetW = (regionBitmap.width * scaleFactor).toInt().coerceIn(regionBitmap.width, 1600)
        val targetH = (regionBitmap.height * scaleFactor).toInt().coerceIn(regionBitmap.height, 900)
        return try {
            Bitmap.createScaledBitmap(regionBitmap, targetW, targetH, true)
        } catch (_: Exception) {
            regionBitmap
        }
    }

    /**
     * Isolates bright white (`R,G,B > 180` with low channel spread) and golden/yellow (`R > 175, G > 145, B < 150`)
     * subtitle text pixels against busy textured game scenes, rendering them crisp black text on pure white.
     */
    fun createBrightYellowWhiteIsolationBitmap(source: Bitmap): Bitmap {
        val safeBitmap = ensureSoftwareArgbBitmap(source)
        val w = safeBitmap.width.coerceAtLeast(1)
        val h = safeBitmap.height.coerceAtLeast(1)
        val pixels = IntArray(w * h)
        safeBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = (r * 77 + g * 150 + b * 29) shr 8

            val isWhiteGlyph = luma >= 182 && kotlin.math.abs(r - g) <= 45 && kotlin.math.abs(g - b) <= 45
            val isYellowGlyph = r >= 175 && g >= 145 && b <= 145 && (r - b) >= 45
            pixels[i] = if (isWhiteGlyph || isYellowGlyph) Color.BLACK else Color.WHITE
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        if (safeBitmap !== source && !safeBitmap.isRecycled) {
            safeBitmap.recycle()
        }
        return out
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
        if (safeBitmap !== source && !safeBitmap.isRecycled) {
            safeBitmap.recycle()
        }
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
        if (safeBitmap !== source && !safeBitmap.isRecycled) {
            safeBitmap.recycle()
        }
        return output
    }

    /**
     * Combined preprocessing pipeline applying grayscale, optional color inversion,
     * contrast boost, and optional overlay exclusion masking.
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

        if (safeBitmap !== source && !safeBitmap.isRecycled) {
            safeBitmap.recycle()
        }
        return output
    }

    /**
     * Runs the complete ROI Crop -> Small-ROI Upscale -> Multi-Pass Preprocessing -> ML Kit [TextRecognizer] pipeline
     * on a captured video frame [source].
     * Safely recycles intermediate temporary bitmaps so only [OcrExtractionResult.croppedOriginalBitmap]
     * and [OcrExtractionResult.processedBitmap] are returned live to the caller.
     */
    suspend fun extractTextFromOverlayBitmap(
        source: Bitmap,
        enableColorInversion: Boolean = true,
        excludeOverlayRect: Rect? = null,
        targetRegionScreenRect: Rect? = null,
        rawScreenWidth: Int = 0,
        rawScreenHeight: Int = 0,
        skipIfRegionUnchanged: Boolean = true,
        rotationDegrees: Int = 0
    ): OcrExtractionResult = withContext(Dispatchers.Default) {
        val ocrStartMs = System.currentTimeMillis()
        val regionBitmap = if (targetRegionScreenRect != null && !targetRegionScreenRect.isEmpty && rawScreenWidth > 0 && rawScreenHeight > 0) {
            cropToTargetRegion(source, targetRegionScreenRect, rawScreenWidth, rawScreenHeight)
        } else {
            val safe = ensureSoftwareArgbBitmap(source)
            safe.copy(Bitmap.Config.ARGB_8888, false) ?: safe
        }

        val roiW = regionBitmap.width
        val roiH = regionBitmap.height

        // 32x12 luma check + forced periodic OCR so thin subtle subtitle swaps are never permanently skipped
        val currentSample = sampleLuminanceGrid(regionBitmap)
        val timeSinceForced = ocrStartMs - lastForcedOcrTimestampMs
        val mustForceOcr = consecutiveSkippedFrames >= MAX_CONSECUTIVE_SKIPS ||
            timeSinceForced >= FORCED_OCR_INTERVAL_MS

        if (skipIfRegionUnchanged && !mustForceOcr && !hasSignificantRegionChange(currentSample)) {
            consecutiveSkippedFrames++
            return@withContext OcrExtractionResult(
                extractedText = "",
                recognizedBlocks = emptyList(),
                croppedOriginalBitmap = regionBitmap,
                processedBitmap = regionBitmap,
                usedColorInversion = enableColorInversion,
                skippedUnchangedFrame = true,
                ocrLatencyMs = System.currentTimeMillis() - ocrStartMs,
                roiWidth = roiW,
                roiHeight = roiH,
                preprocessPassUsed = "Skipped (Unchanged)"
            )
        }
        consecutiveSkippedFrames = 0
        lastForcedOcrTimestampMs = ocrStartMs
        previousRegionLumaSample = currentSample

        val upscaledRoi = upscaleSmallRoiIfNeeded(regionBitmap)
        val maskRect = if (targetRegionScreenRect == null) excludeOverlayRect else null
        val safeRotation = when (rotationDegrees) {
            90, 180, 270 -> rotationDegrees
            else -> 0
        }

        // Pass 1: Inverted contrast-boosted grayscale (or natural if inversion disabled)
        val pass1Bitmap = preprocessForOcr(
            source = upscaledRoi,
            enableGrayscale = true,
            enableColorInversion = enableColorInversion,
            excludeOverlayRect = maskRect
        )
        val pass1Blocks = runMlKitRecognition(pass1Bitmap, pass1Bitmap.height, safeRotation)
        val pass1Text = formatOverlayBlocks(pass1Blocks, isCroppedBox = targetRegionScreenRect != null)

        if (pass1Text.isNotBlank()) {
            if (upscaledRoi !== regionBitmap && !upscaledRoi.isRecycled) upscaledRoi.recycle()
            return@withContext OcrExtractionResult(
                extractedText = pass1Text,
                recognizedBlocks = pass1Blocks,
                croppedOriginalBitmap = regionBitmap,
                processedBitmap = pass1Bitmap,
                usedColorInversion = enableColorInversion,
                ocrLatencyMs = System.currentTimeMillis() - ocrStartMs,
                roiWidth = roiW,
                roiHeight = roiH,
                preprocessPassUsed = if (enableColorInversion) "Pass 1 (Inverted Contrast)" else "Pass 1 (Grayscale)"
            )
        }

        // Pass 2: Bright White & Yellow Subtitle Isolation for busy/moving game backgrounds & outlines
        val pass2Bitmap = createBrightYellowWhiteIsolationBitmap(upscaledRoi)
        val pass2Blocks = runMlKitRecognition(pass2Bitmap, pass2Bitmap.height, safeRotation)
        val pass2Text = formatOverlayBlocks(pass2Blocks, isCroppedBox = targetRegionScreenRect != null)

        if (pass2Text.isNotBlank()) {
            if (pass1Bitmap !== pass2Bitmap && !pass1Bitmap.isRecycled) pass1Bitmap.recycle()
            if (upscaledRoi !== regionBitmap && !upscaledRoi.isRecycled) upscaledRoi.recycle()
            return@withContext OcrExtractionResult(
                extractedText = pass2Text,
                recognizedBlocks = pass2Blocks,
                croppedOriginalBitmap = regionBitmap,
                processedBitmap = pass2Bitmap,
                usedColorInversion = true,
                ocrLatencyMs = System.currentTimeMillis() - ocrStartMs,
                roiWidth = roiW,
                roiHeight = roiH,
                preprocessPassUsed = "Pass 2 (White/Yellow Isolated)"
            )
        }
        if (!pass2Bitmap.isRecycled) pass2Bitmap.recycle()

        // Pass 3: Natural non-inverted grayscale (handles dark subtitles on light cards)
        if (enableColorInversion) {
            val pass3Bitmap = preprocessForOcr(
                source = upscaledRoi,
                enableGrayscale = true,
                enableColorInversion = false,
                excludeOverlayRect = maskRect
            )
            val pass3Blocks = runMlKitRecognition(pass3Bitmap, pass3Bitmap.height, safeRotation)
            val pass3Text = formatOverlayBlocks(pass3Blocks, isCroppedBox = targetRegionScreenRect != null)

            if (pass3Text.isNotBlank()) {
                if (!pass1Bitmap.isRecycled) pass1Bitmap.recycle()
                if (upscaledRoi !== regionBitmap && !upscaledRoi.isRecycled) upscaledRoi.recycle()
                return@withContext OcrExtractionResult(
                    extractedText = pass3Text,
                    recognizedBlocks = pass3Blocks,
                    croppedOriginalBitmap = regionBitmap,
                    processedBitmap = pass3Bitmap,
                    usedColorInversion = false,
                    ocrLatencyMs = System.currentTimeMillis() - ocrStartMs,
                    roiWidth = roiW,
                    roiHeight = roiH,
                    preprocessPassUsed = "Pass 3 (Natural Grayscale)"
                )
            }
            if (!pass3Bitmap.isRecycled) pass3Bitmap.recycle()
        }

        if (upscaledRoi !== regionBitmap && !upscaledRoi.isRecycled) upscaledRoi.recycle()
        OcrExtractionResult(
            extractedText = "",
            recognizedBlocks = emptyList(),
            croppedOriginalBitmap = regionBitmap,
            processedBitmap = pass1Bitmap,
            usedColorInversion = enableColorInversion,
            ocrLatencyMs = System.currentTimeMillis() - ocrStartMs,
            roiWidth = roiW,
            roiHeight = roiH,
            preprocessPassUsed = "No Subtitle Found"
        )
    }

    private suspend fun runMlKitRecognition(
        processedBitmap: Bitmap,
        frameHeight: Int,
        rotationDegrees: Int = 0
    ): List<DetectedOverlayBlock> {
        val override = customBlockRecognitionOverride
        if (override != null) {
            return override(processedBitmap, frameHeight, rotationDegrees)
        }
        return try {
            val inputImage = InputImage.fromBitmap(processedBitmap, rotationDegrees)
            val visionText: Text = recognizer.process(inputImage).await()
            parseDetectedOverlayBlocks(visionText, frameHeight)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
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

        // Allow speaker headers near the top of the captured subtitle frame while still flagging bottom-85% blocks
        val subtitleZoneTopThreshold = (frameHeight * 0.08f).toInt()

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
                    val isKnownHeader = SpeakerHeuristicEngine.isKnownCharacterOrSpeakerHeader(cleanedBlockText)
                    val isSubtitleOverlay = isKnownHeader || box == null || box.centerY() >= subtitleZoneTopThreshold
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
        val targetBlocks = if (isCroppedBox) {
            blocks
        } else {
            val subtitleBlocks = blocks.filter { it.isSubtitleRegion }
            subtitleBlocks.ifEmpty { blocks }
        }
        val rawCombined = targetBlocks
            .joinToString("\n") { it.text }
            .trim()
        return SubtitleTextFilter.filterDialogueText(rawCombined)
    }

    private fun sampleLuminanceGrid(bitmap: Bitmap): IntArray {
        val sample = IntArray(SAMPLE_GRID_W * SAMPLE_GRID_H)
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return sample
        val stepX = (bitmap.width.toFloat() / SAMPLE_GRID_W).coerceAtLeast(1f)
        val stepY = (bitmap.height.toFloat() / SAMPLE_GRID_H).coerceAtLeast(1f)
        var idx = 0
        for (gy in 0 until SAMPLE_GRID_H) {
            val py = ((gy + 0.5f) * stepY).toInt().coerceIn(0, bitmap.height - 1)
            for (gx in 0 until SAMPLE_GRID_W) {
                val px = ((gx + 0.5f) * stepX).toInt().coerceIn(0, bitmap.width - 1)
                val color = bitmap.getPixel(px, py)
                sample[idx++] = ((Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) shr 8)
            }
        }
        return sample
    }

    private fun hasSignificantRegionChange(currentSample: IntArray): Boolean {
        val prev = previousRegionLumaSample ?: return true
        if (prev.size != currentSample.size) return true
        var changedCells = 0
        var totalDiff = 0
        for (i in currentSample.indices) {
            val diff = kotlin.math.abs(currentSample[i] - prev[i])
            totalDiff += diff
            if (diff >= 10) {
                changedCells++
            }
        }
        val avgDiff = totalDiff.toFloat() / currentSample.size.toFloat()
        return changedCells >= 2 || avgDiff >= 3.2f
    }

    private fun ensureSoftwareArgbBitmap(source: Bitmap): Bitmap {
        return if (source.config == Bitmap.Config.ARGB_8888 && !source.isRecycled) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        }
    }

    fun close() {
        if (!hasInitializedRecognizer) return
        try {
            recognizer.close()
        } catch (_: Exception) {
        }
    }
}
