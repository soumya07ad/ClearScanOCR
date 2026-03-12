package com.example.clearscanocr.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Image preprocessing utilities to improve OCR accuracy.
 *
 * Handles rotation-aware cropping to match the on-screen guide
 * overlay, plus grayscale/contrast enhancement.
 */
object ImagePreprocessor {

    /** Contrast multiplier — 1.0 is neutral, >1.0 increases contrast. */
    private const val CONTRAST = 1.3f

    /** Brightness offset applied after contrast scaling. */
    private const val BRIGHTNESS = -30f

    /** Guide overlay occupies 85 % of screen width. */
    private const val GUIDE_WIDTH_FRACTION = 0.85f

    /** Guide overlay aspect ratio (width / height). */
    private const val GUIDE_ASPECT = 4f / 3f

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Crop the raw camera [source] bitmap to *exactly* the area that
     * the user sees inside the on-screen alignment guide.
     *
     * Steps:
     * 1. Rotate to screen orientation using [rotationDegrees].
     * 2. Simulate the FILL_CENTER crop PreviewView performs.
     * 3. Compute the guide rectangle within that visible area.
     * 4. Map back to original (rotated) bitmap coordinates.
     * 5. Return the cropped region.
     *
     * @param source          Raw bitmap from ImageProxy.toBitmap().
     * @param rotationDegrees Rotation from imageProxy.imageInfo.rotationDegrees.
     * @param viewWidth       Width of the PreviewView in pixels.
     * @param viewHeight      Height of the PreviewView in pixels.
     * @return A new cropped bitmap. Caller must recycle [source] separately.
     */
    fun cropToGuide(
        source: Bitmap,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int
    ): Bitmap {
        // 1. Rotate bitmap so it matches screen orientation
        val rotated = rotateBitmap(source, rotationDegrees)

        val imgW = rotated.width
        val imgH = rotated.height

        // 2. Compute FILL_CENTER visible region inside the rotated bitmap.
        //    FILL_CENTER scales the image so the *smaller* dimension fills
        //    the view exactly, then center-crops the larger dimension.
        val viewAspect = viewWidth.toFloat() / viewHeight
        val imgAspect = imgW.toFloat() / imgH

        val visibleLeft: Int
        val visibleTop: Int
        val visibleW: Int
        val visibleH: Int

        if (imgAspect > viewAspect) {
            // Image is wider than view → width is cropped
            visibleH = imgH
            visibleW = (imgH * viewAspect).toInt()
            visibleLeft = (imgW - visibleW) / 2
            visibleTop = 0
        } else {
            // Image is taller than view → height is cropped
            visibleW = imgW
            visibleH = (imgW / viewAspect).toInt()
            visibleLeft = 0
            visibleTop = (imgH - visibleH) / 2
        }

        // 3. Guide rectangle in "visible" coordinates
        val guideW = (visibleW * GUIDE_WIDTH_FRACTION).toInt()
        val guideH = (guideW / GUIDE_ASPECT).toInt()
        val guideLeft = (visibleW - guideW) / 2
        val guideTop = (visibleH - guideH) / 2

        // 4. Map back to rotated-bitmap coordinates
        val cropLeft = (visibleLeft + guideLeft).coerceAtLeast(0)
        val cropTop = (visibleTop + guideTop).coerceAtLeast(0)
        val cropW = guideW.coerceAtMost(imgW - cropLeft)
        val cropH = guideH.coerceAtMost(imgH - cropTop)

        val cropped = Bitmap.createBitmap(rotated, cropLeft, cropTop, cropW, cropH)

        // Recycle the intermediate rotated bitmap (unless it's the same object)
        if (rotated !== source) {
            rotated.recycle()
        }

        return cropped
    }

    /**
     * Convert the [source] bitmap to grayscale with enhanced contrast.
     *
     * Returns a new ARGB_8888 bitmap; the caller is responsible for
     * recycling [source] if it is no longer needed.
     */
    fun preprocess(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Grayscale matrix
        val grayscale = ColorMatrix().apply { setSaturation(0f) }

        // Contrast + brightness matrix
        val contrast = ColorMatrix(
            floatArrayOf(
                CONTRAST, 0f, 0f, 0f, BRIGHTNESS,
                0f, CONTRAST, 0f, 0f, BRIGHTNESS,
                0f, 0f, CONTRAST, 0f, BRIGHTNESS,
                0f, 0f, 0f, 1f, 0f
            )
        )

        // Chain: grayscale first, then contrast
        contrast.preConcat(grayscale)
        paint.colorFilter = ColorMatrixColorFilter(contrast)

        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    // ── Internal helpers ────────────────────────────────────────────

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
