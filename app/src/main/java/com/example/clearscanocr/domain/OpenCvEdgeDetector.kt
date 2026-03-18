package com.example.clearscanocr.domain

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble // Added for meanStdDev
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ── Data classes ────────────────────────────────────────────────────

/**
 * Result emitted by [OpenCvEdgeDetector.processFrame].
 *
 * @property bitmap   Overlay bitmap with rectangle drawn, or `null`.
 * @property message  User-facing guidance string.
 * @property confidence Detection confidence in 0.0–1.0.
 */
data class EdgeDetectionResult(
    val bitmap: Bitmap?,
    val message: String,
    val confidence: Float,
    val isStable: Boolean = false,
    val isBlurry: Boolean = false,
    val isWellLit: Boolean = false,
    val areaValid: Boolean = false
)

// ── Detector ────────────────────────────────────────────────────────

/**
 * Real-time document edge detector with rectangle stability,
 * validation, blur / brightness analysis, and guidance messaging.
 *
 * All public methods are safe to call from a background thread.
 */
object OpenCvEdgeDetector {

    private const val TAG = "OpenCvEdgeDetector"

    // ── Tuning constants ────────────────────────────────────────────

    /** Gaussian blur kernel size. */
    private const val BLUR_KERNEL = 5.0

    /** Canny thresholds. */
    private const val CANNY_LOW = 50.0
    private const val CANNY_HIGH = 150.0

    /** Minimum contour area as fraction of image area. */
    private const val MIN_AREA_FRACTION = 0.05

    /** Polygon approximation epsilon factor. */
    private const val APPROX_EPSILON_FACTOR = 0.02

    /** Acceptable aspect ratio range for rectangles. */
    private const val MIN_ASPECT_RATIO = 0.5
    private const val MAX_ASPECT_RATIO = 2.0

    /** Small angle deviation (degrees) allowed per corner. */
    private const val MIN_CORNER_ANGLE = 60.0
    private const val MAX_CORNER_ANGLE = 120.0

    /** Stability thresholds (px, applied per-corner). */
    private const val LOCK_THRESHOLD = 10.0   // < 10 px → reuse previous
    private const val SMOOTH_THRESHOLD = 30.0 // 10–30 px → interpolate

    /** Smoothing weight for new frame during interpolation. */
    private const val SMOOTH_ALPHA = 0.4

    /** Brightness / blur thresholds. */
    private const val DARK_THRESHOLD = 60.0
    private const val BLUR_VARIANCE_THRESHOLD = 100.0

    /** Rectangle deemed "too small" if < this fraction of area. */
    private const val SMALL_RECT_FRACTION = 0.15

    /** Green contour drawing parameters. */
    private val CONTOUR_COLOR = Scalar(0.0, 255.0, 0.0, 255.0)
    private const val CONTOUR_THICKNESS = 4

    // ── Mutable state (accessed only from analysis thread) ──────────

    /** Previously accepted sorted corners (TL, TR, BR, BL). */
    private var lastCorners: Array<Point>? = null

    /** Number of consecutive frames with no rectangle detected. */
    private var noRectFrames = 0

    /** Max frames to retain the previous rectangle when nothing is detected. */
    private const val MAX_NO_RECT_FRAMES = 5

    /**
     * Latest stable corners exposed atomically for capture-thread access.
     * Set to a snapshot whenever lastCorners changes.
     */
    private val lastCornersRef = AtomicReference<Array<Point>?>(null)

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Returns a snapshot of the latest stable sorted corners,
     * or `null` if no rectangle has been detected yet.
     * Safe to call from any thread.
     */
    fun getLastCorners(): Array<Point>? = lastCornersRef.get()

    /**
     * Rotate a raw camera bitmap to match screen orientation.
     */
    fun imageProxyToBitmap(rawBitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return rawBitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(
            rawBitmap, 0, 0,
            rawBitmap.width, rawBitmap.height,
            matrix, true
        )
        if (rotated !== rawBitmap) rawBitmap.recycle()
        return rotated
    }

    /**
     * Full frame-processing pipeline.
     *
     * Returns an [EdgeDetectionResult] containing the overlay bitmap
     * (or `null`) and a guidance message.
     */
    fun processFrame(bitmap: Bitmap): EdgeDetectionResult {
        val src = bitmapToMat(bitmap)

        return try {
            // ── Frame quality analysis ──────────────────────────────
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            val brightness = Core.mean(gray).`val`[0]
            val isWellLit = brightness >= DARK_THRESHOLD
            if (!isWellLit) {
                gray.release()
                return EdgeDetectionResult(null, "💡 Increase lighting", 0.0f, isWellLit = false)
            }

            val blurScore = laplacianVariance(gray)
            val isBlurry = blurScore < BLUR_VARIANCE_THRESHOLD
            if (isBlurry) {
                gray.release()
                return EdgeDetectionResult(null, "✋ Hold steady", 0.1f, isWellLit = true, isBlurry = true)
            }

            // ── Edge detection ──────────────────────────────────────
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(BLUR_KERNEL, BLUR_KERNEL), 0.0)
            gray.release()

            val edges = Mat()
            Imgproc.Canny(blurred, edges, CANNY_LOW, CANNY_HIGH)
            blurred.release()

            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                edges, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            edges.release()
            hierarchy.release()

            val imageArea = (src.rows() * src.cols()).toDouble()
            val currentCorners = findLargestRectangle(contours, imageArea)
            contours.forEach { it.release() }

            // ── Stability logic ─────────────────────────────────────
            val stabilityResult = applyStability(currentCorners, imageArea)
            val stableCorners = stabilityResult.first
            val stabilityMsg = stabilityResult.second
            // isStable is true if we are in LOCK or SMOOTH range (not LARGE movement)
            val isStable = stableCorners != null && stabilityMsg != "✋ Hold steady"

            if (stableCorners == null) {
                return EdgeDetectionResult(
                    null, stabilityMsg, 0.0f,
                    isWellLit = true, isBlurry = false, isStable = false
                )
            }

            // ── Confidence score ────────────────────────────────────
            val rectArea = polygonArea(stableCorners)
            val areaValid = rectArea >= imageArea * SMALL_RECT_FRACTION
            val areaScore = (rectArea / imageArea).toFloat().coerceIn(0f, 1f)
            val shapeScore = shapeRegularity(stableCorners)
            val confidence = (areaScore * 0.5f + shapeScore * 0.3f +
                    if (stabilityMsg == "✅ Ready to capture") 0.2f else 0.0f)
                .coerceIn(0f, 1f)

            // ── Draw rectangle ──────────────────────────────────────
            val drawContour = MatOfPoint(*stableCorners)
            Imgproc.drawContours(
                src, listOf(drawContour), 0,
                CONTOUR_COLOR, CONTOUR_THICKNESS
            )
            drawContour.release()

            val result = Bitmap.createBitmap(
                src.cols(), src.rows(), Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(src, result)

            EdgeDetectionResult(
                result, stabilityMsg, confidence,
                isStable = isStable,
                isBlurry = false,
                isWellLit = true,
                areaValid = areaValid
            )
        } catch (e: Exception) {
            Log.e(TAG, "Edge detection failed", e)
            EdgeDetectionResult(null, "⚠ Detection error", 0.0f, isWellLit = false)
        } finally {
            src.release()
        }
    }

    /**
     * Reset internal state (call when camera is unbound).
     */
    fun reset() {
        lastCorners = null
        lastCornersRef.set(null)
        noRectFrames = 0
    }

    /**
     * Apply perspective warp and image enhancement to a captured bitmap.
     *
     * Takes the [corners] detected in the analyzer frame (at [analyzerWidth] × [analyzerHeight])
     * and scales them to the [capturedBitmap]'s own resolution before warping.
     * Returns a flat, grayscale, adaptive-threshold enhanced [Bitmap] ready for OCR,
     * or `null` if the warp cannot be applied.
     *
     * @param capturedBitmap  High-quality frame from the capture path.
     * @param analyzerWidth   Width of the (rotated) analyzer frame the corners were detected in.
     * @param analyzerHeight  Height of the (rotated) analyzer frame.
     * @param corners         Sorted corners (TL, TR, BR, BL) in analyzer-frame coordinates.
     */
    fun warpAndEnhance(
        capturedBitmap: Bitmap,
        analyzerWidth: Int,
        analyzerHeight: Int,
        corners: Array<Point>
    ): Bitmap? {
        if (corners.size != 4) return null

        // ── 1. Scale corners from analyzer resolution to capture resolution ──
        val scaleX = capturedBitmap.width.toDouble() / analyzerWidth
        val scaleY = capturedBitmap.height.toDouble() / analyzerHeight

        val scaledCorners = Array(4) { i ->
            // Re-sort after scaling to ensure correct ordering on the new resolution
            Point(corners[i].x * scaleX, corners[i].y * scaleY)
        }
        // Re-sort after scaling (rounding shouldn't change order, but be safe)
        val tl = scaledCorners[0]
        val tr = scaledCorners[1]
        val br = scaledCorners[2]
        val bl = scaledCorners[3]

        // ── 2. Compute output dimensions using max edge distances ────────────
        val widthTop  = distance(tl, tr)
        val widthBot  = distance(bl, br)
        val heightLeft = distance(tl, bl)
        val heightRight = distance(tr, br)

        val outW = max(widthTop, widthBot).roundToInt().coerceAtLeast(1)
        val outH = max(heightLeft, heightRight).roundToInt().coerceAtLeast(1)

        // ── 3. Build source and destination point matrices ───────────────────
        val srcMat = MatOfPoint2f(
            tl, tr, br, bl
        )
        val dstMat = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW.toDouble(), 0.0),
            Point(outW.toDouble(), outH.toDouble()),
            Point(0.0, outH.toDouble())
        )

        val src = bitmapToMat(capturedBitmap)
        return try {
            // ── 4. Perspective transform ─────────────────────────────────────
            val M = Imgproc.getPerspectiveTransform(srcMat, dstMat)
            val warped = Mat()
            Imgproc.warpPerspective(src, warped, M, Size(outW.toDouble(), outH.toDouble()))
            M.release()

            // ── 5. Grayscale conversion ───────────────────────────────────────
            val gray = Mat()
            Imgproc.cvtColor(warped, gray, Imgproc.COLOR_RGBA2GRAY)
            warped.release()

            // ── 6. Light Gaussian blur before threshold (reduce noise, preserve text) ──
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
            gray.release()

            // ── 7. Adaptive threshold ─────────────────────────────────────────
            // GAUSSIAN_C is softer and preserves subtle text better than MEAN_C
            val threshed = Mat()
            Imgproc.adaptiveThreshold(
                blurred, threshed,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                21,  // block size (must be odd; larger = more context per pixel)
                10.0 // constant subtracted from mean (tune: higher = lighter background)
            )
            blurred.release()

            // ── 8. Convert back to ARGB_8888 bitmap ───────────────────────────
            val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            // adaptiveThreshold outputs 8-bit single channel; convert to RGBA for Utils
            val threshedRgba = Mat()
            Imgproc.cvtColor(threshed, threshedRgba, Imgproc.COLOR_GRAY2RGBA)
            threshed.release()
            Utils.matToBitmap(threshedRgba, result)
            threshedRgba.release()

            result
        } catch (e: Exception) {
            Log.e(TAG, "warpAndEnhance failed", e)
            null
        } finally {
            src.release()
            srcMat.release()
            dstMat.release()
        }
    }

    // ── Internal helpers ────────────────────────────────────────────

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat(bitmap.height, bitmap.width, CvType.CV_8UC4)
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    /**
     * Laplacian variance — a low value means image is blurry.
     * Operates on an already-grayscale [gray] Mat.
     */
    private fun laplacianVariance(gray: Mat): Double {
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        Core.meanStdDev(lap, mean, stddev)
        val variance = stddev[0, 0][0].let { it * it }
        lap.release()
        mean.release()
        stddev.release()
        return variance
    }

    /**
     * Find the largest valid 4-sided contour.
     *
     * Applies area, aspect-ratio, and corner-angle validation.
     * Returns sorted corners (TL, TR, BR, BL) or `null`.
     */
    private fun findLargestRectangle(
        contours: List<MatOfPoint>,
        imageArea: Double
    ): Array<Point>? {
        val minArea = imageArea * MIN_AREA_FRACTION
        var bestCorners: Array<Point>? = null
        var bestArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minArea) continue

            val contour2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(
                contour2f, approx,
                APPROX_EPSILON_FACTOR * perimeter, true
            )
            contour2f.release()

            if (approx.rows() == 4 && area > bestArea) {
                val pts = sortCorners(approx.toArray())
                approx.release()

                if (isValidRectangle(pts)) {
                    bestCorners = pts
                    bestArea = area
                }
            } else {
                approx.release()
            }
        }

        return bestCorners
    }

    /**
     * Sort 4 points into consistent order: TL, TR, BR, BL.
     *
     * Uses the sum (x+y) and diff (y-x) heuristic.
     */
    private fun sortCorners(pts: Array<Point>): Array<Point> {
        val sorted = arrayOfNulls<Point>(4)
        val sums = pts.map { it.x + it.y }
        val diffs = pts.map { it.y - it.x }

        sorted[0] = pts[sums.indexOf(sums.min())] // TL — smallest sum
        sorted[2] = pts[sums.indexOf(sums.max())] // BR — largest sum
        sorted[1] = pts[diffs.indexOf(diffs.min())] // TR — smallest diff
        sorted[3] = pts[diffs.indexOf(diffs.max())] // BL — largest diff

        @Suppress("UNCHECKED_CAST")
        return sorted as Array<Point>
    }

    /**
     * Validate a sorted rectangle for aspect ratio and corner angles.
     */
    private fun isValidRectangle(pts: Array<Point>): Boolean {
        // Aspect ratio check
        val topWidth = distance(pts[0], pts[1])
        val bottomWidth = distance(pts[3], pts[2])
        val leftHeight = distance(pts[0], pts[3])
        val rightHeight = distance(pts[1], pts[2])
        val avgWidth = (topWidth + bottomWidth) / 2.0
        val avgHeight = (leftHeight + rightHeight) / 2.0
        if (avgHeight == 0.0) return false
        val aspect = avgWidth / avgHeight
        if (aspect < MIN_ASPECT_RATIO || aspect > MAX_ASPECT_RATIO) return false

        // Corner angle check
        for (i in 0 until 4) {
            val angle = cornerAngle(
                pts[i],
                pts[(i + 1) % 4],
                pts[(i + 3) % 4]
            )
            if (angle < MIN_CORNER_ANGLE || angle > MAX_CORNER_ANGLE) return false
        }

        return true
    }

    /**
     * Angle at vertex [p] between rays [p→a] and [p→b] in degrees.
     */
    private fun cornerAngle(p: Point, a: Point, b: Point): Double {
        val v1x = a.x - p.x;  val v1y = a.y - p.y
        val v2x = b.x - p.x;  val v2y = b.y - p.y
        val dot = v1x * v2x + v1y * v2y
        val mag1 = sqrt(v1x * v1x + v1y * v1y)
        val mag2 = sqrt(v2x * v2x + v2y * v2y)
        if (mag1 == 0.0 || mag2 == 0.0) return 0.0
        val cosAngle = (dot / (mag1 * mag2)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(cosAngle))
    }

    /**
     * Euclidean distance between two points.
     */
    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Apply stability logic against [lastCorners].
     *
     * Returns the final corners to use and an appropriate guidance message.
     */
    private fun applyStability(
        currentCorners: Array<Point>?,
        imageArea: Double
    ): Pair<Array<Point>?, String> {

        // No rectangle detected this frame
        if (currentCorners == null) {
            noRectFrames++
            if (lastCorners != null && noRectFrames <= MAX_NO_RECT_FRAMES) {
                // Briefly hold previous rectangle
                return lastCorners!! to "📄 Align document"
            }
            lastCorners = null
            lastCornersRef.set(null)
            return null to "📄 Align document"
        }

        noRectFrames = 0

        // Area-based guidance
        val rectArea = polygonArea(currentCorners)
        if (rectArea < imageArea * SMALL_RECT_FRACTION) {
            lastCorners = currentCorners
            lastCornersRef.set(currentCorners.copyOf())
            return currentCorners to "🔍 Move closer"
        }

        // First detection — nothing to compare against
        if (lastCorners == null) {
            lastCorners = currentCorners
            lastCornersRef.set(currentCorners.copyOf())
            return currentCorners to "✅ Ready to capture"
        }

        // Per-corner maximum movement
        val maxMovement = (0 until 4).maxOf {
            distance(currentCorners[it], lastCorners!![it])
        }

        return when {
            // Lock — near-zero movement, reuse previous to eliminate jitter
            maxMovement < LOCK_THRESHOLD -> {
                // lastCornersRef already has this value; no need to update
                lastCorners!! to "✅ Ready to capture"
            }
            // Smooth — interpolate toward new position
            maxMovement < SMOOTH_THRESHOLD -> {
                val smoothed = Array(4) { i ->
                    Point(
                        lastCorners!![i].x * (1 - SMOOTH_ALPHA) + currentCorners[i].x * SMOOTH_ALPHA,
                        lastCorners!![i].y * (1 - SMOOTH_ALPHA) + currentCorners[i].y * SMOOTH_ALPHA
                    )
                }
                lastCorners = smoothed
                lastCornersRef.set(smoothed.copyOf())
                smoothed to "✅ Ready to capture"
            }
            // Large jump — accept immediately (user repositioned document)
            else -> {
                lastCorners = currentCorners
                lastCornersRef.set(currentCorners.copyOf())
                currentCorners to "✋ Hold steady"
            }
        }
    }

    /**
     * Shoelace formula for the area of a polygon given ordered vertices.
     */
    private fun polygonArea(pts: Array<Point>): Double {
        var area = 0.0
        val n = pts.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += pts[i].x * pts[j].y
            area -= pts[j].x * pts[i].y
        }
        return abs(area) / 2.0
    }

    /**
     * Shape regularity score in 0.0–1.0.
     *
     * A perfect rectangle yields 1.0. More distortion → lower score.
     */
    private fun shapeRegularity(pts: Array<Point>): Float {
        var totalAngleError = 0.0
        for (i in 0 until 4) {
            val angle = cornerAngle(
                pts[i], pts[(i + 1) % 4], pts[(i + 3) % 4]
            )
            totalAngleError += abs(angle - 90.0)
        }
        // Max possible error = 4 × 30° = 120° (since we clamp at 60–120)
        return (1.0 - totalAngleError / 120.0).toFloat().coerceIn(0f, 1f)
    }
}
