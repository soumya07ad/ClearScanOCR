package com.example.clearscanocr.domain

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class EdgeDetectionResult(
    val corners: Array<Point>?,
    val message: String,
    val confidence: Float,
    val isStable: Boolean = false,
    val isBlurry: Boolean = false,
    val isWellLit: Boolean = false,
    val areaValid: Boolean = false
)

object OpenCvEdgeDetector {

    private const val TAG = "OpenCvEdgeDetector"

    private const val CANNY_LOW = 40.0
    private const val CANNY_HIGH = 120.0
    private const val DARK_THRESHOLD = 20.0           // Lowered from 45.0 (allow darker rooms)
    private const val BLUR_VARIANCE_THRESHOLD = 15.0  // Lowered from 75.0 (white paper has naturally low variance)
    private const val SMALL_RECT_FRACTION = 0.08      // Lowered from 0.12 (allow smaller rectangles)
    private const val LOCK_THRESHOLD = 35.0
    private const val SMOOTH_THRESHOLD = 85.0
    private const val SMOOTH_ALPHA = 0.45
    private const val MAX_NO_RECT_FRAMES = 15

    private var lastCorners: Array<Point>? = null
    private var noRectFrames = 0
    private val lastCornersRef = AtomicReference<Array<Point>?>(null)

    fun getLastCorners(): Array<Point>? = lastCornersRef.get()

    fun imageProxyToBitmap(rawBitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return rawBitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        if (rotated !== rawBitmap) rawBitmap.recycle()
        return rotated
    }

    fun processFrame(bitmap: Bitmap): EdgeDetectionResult {
        val src = bitmapToMat(bitmap)
        val imgW = src.cols()
        val imgH = src.rows()
        val imageArea = (imgW * imgH).toDouble()

        return try {
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            val brightness = Core.mean(gray).`val`[0]
            if (brightness < DARK_THRESHOLD) {
                gray.release()
                return EdgeDetectionResult(null, "💡 Increase lighting", 0.0f, isWellLit = false)
            }

            if (laplacianVariance(gray) < BLUR_VARIANCE_THRESHOLD) {
                gray.release()
                return EdgeDetectionResult(null, "✋ Hold steady", 0.1f, isBlurry = true)
            }

            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            gray.release()

            val binary = Mat()
            Imgproc.adaptiveThreshold(blurred, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 11, 2.0)
            
            val edges = Mat()
            Imgproc.Canny(blurred, edges, CANNY_LOW, CANNY_HIGH)
            Core.bitwise_or(binary, edges, binary)
            
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
            
            blurred.release()
            edges.release()
            kernel.release()

            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            binary.release()
            hierarchy.release()

            var largestC: MatOfPoint? = null
            var maxA = 0.0
            for (c in contours) {
                val a = Imgproc.contourArea(c)
                if (a > maxA && a > imageArea * 0.015) {
                    maxA = a
                    largestC = c
                }
            }

            var detectedCorners: Array<Point>? = null
            var isTooClose = false

            if (largestC != null) {
                val pts = largestC.toArray()
                val sorted = sortCorners(pts)
                detectedCorners = sorted

                val m = 3 // Margin reduced from 12px to 3px
                isTooClose = sorted[0].x < m || sorted[0].y < m || 
                             sorted[2].x > imgW - m || sorted[2].y > imgH - m ||
                             sorted[1].x > imgW - m || sorted[1].y < m ||
                             sorted[3].x < m || sorted[3].y > imgH - m
            }
            contours.forEach { it.release() }

            var currentValidCorners: PointArray? = null
            var guideMsg = "📄 Align document"
            
            if (detectedCorners != null) {
                if (isTooClose) guideMsg = "🔍 Move further away"
                else if (isValidRectangle(detectedCorners)) currentValidCorners = detectedCorners
                else if (maxA < imageArea * SMALL_RECT_FRACTION) guideMsg = "🔍 Move closer"
            }

            val stabilityResult = applyStability(currentValidCorners, imageArea)
            val stableCorners = stabilityResult.first
            val stabilityMsg = if (currentValidCorners != null) stabilityResult.second else guideMsg
            
            val readyFlag = stableCorners != null && (stabilityMsg == "✅ Ready to capture" || stabilityMsg == "✋ Hold steady")
            val rectArea = stableCorners?.let { polygonArea(it) } ?: 0.0
            val shapeScore = stableCorners?.let { shapeRegularity(it) } ?: 0f
            
            val confidence = ((rectArea / imageArea).toFloat() * 0.4f + shapeScore * 0.4f +
                    if (readyFlag) 0.2f else 0.0f).coerceIn(0f, 1f)

            EdgeDetectionResult(
                detectedCorners,
                stabilityMsg, 
                confidence,
                isStable = readyFlag && stabilityMsg == "✅ Ready to capture",
                isWellLit = true,
                areaValid = rectArea >= imageArea * SMALL_RECT_FRACTION
            )
        } catch (e: Exception) {
            Log.e(TAG, "Edge detection failed", e)
            EdgeDetectionResult(null, "⚠ Detection error", 0.0f)
        } finally {
            src.release()
        }
    }

    fun reset() {
        lastCorners = null
        lastCornersRef.set(null)
        noRectFrames = 0
    }

    fun warpAndEnhance(capturedBitmap: Bitmap, analyzerW: Int, analyzerH: Int, corners: Array<Point>): Bitmap? {
        if (corners.size != 4) return null
        val scaleX = capturedBitmap.width.toDouble() / analyzerW
        val scaleY = capturedBitmap.height.toDouble() / analyzerH
        val scaled = Array(4) { i -> Point(corners[i].x * scaleX, corners[i].y * scaleY) }
        
        val outW = max(distance(scaled[0], scaled[1]), distance(scaled[3], scaled[2])).roundToInt().coerceAtLeast(1)
        val outH = max(distance(scaled[0], scaled[3]), distance(scaled[1], scaled[2])).roundToInt().coerceAtLeast(1)

        val srcMat = MatOfPoint2f(*scaled)
        val dstMat = MatOfPoint2f(Point(0.0, 0.0), Point(outW.toDouble(), 0.0), Point(outW.toDouble(), outH.toDouble()), Point(0.0, outH.toDouble()))
        val src = bitmapToMat(capturedBitmap)
        return try {
            val M = Imgproc.getPerspectiveTransform(srcMat, dstMat)
            val warped = Mat()
            Imgproc.warpPerspective(src, warped, M, Size(outW.toDouble(), outH.toDouble()))
            val gray = Mat()
            Imgproc.cvtColor(warped, gray, Imgproc.COLOR_RGBA2GRAY)
            val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val threshed = Mat()
            Imgproc.adaptiveThreshold(gray, threshed, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 21, 10.0)
            val threshedRgba = Mat()
            Imgproc.cvtColor(threshed, threshedRgba, Imgproc.COLOR_GRAY2RGBA)
            Utils.matToBitmap(threshedRgba, result)
            listOf(warped, gray, threshed, threshedRgba, M).forEach { it.release() }
            result
        } catch (e: Exception) { null } finally { src.release() }
    }

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat(bitmap.height, bitmap.width, CvType.CV_8UC4)
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    private fun laplacianVariance(gray: Mat): Double {
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = MatOfDouble(); val stddev = MatOfDouble()
        Core.meanStdDev(lap, mean, stddev)
        val variance = stddev[0, 0][0].let { it * it }
        listOf(lap, mean, stddev).forEach { it.release() }
        return variance
    }

    private fun sortCorners(pts: Array<Point>): Array<Point> {
        val tl = pts.minByOrNull { it.x + it.y }!!
        val br = pts.maxByOrNull { it.x + it.y }!!
        val tr = pts.minByOrNull { it.y - it.x }!!
        val bl = pts.maxByOrNull { it.y - it.x }!!
        return arrayOf(tl, tr, br, bl)
    }

    private fun isValidRectangle(pts: Array<Point>): Boolean {
        val aspect = ((distance(pts[0], pts[1]) + distance(pts[3], pts[2])) / 2.0) / 
                     (((distance(pts[0], pts[3]) + distance(pts[1], pts[2])) / 2.0).coerceAtLeast(1.0))
        if (aspect < 0.2 || aspect > 5.0) return false
        for (i in 0 until 4) {
            val angle = cornerAngle(pts[i], pts[(i + 1) % 4], pts[(i + 3) % 4])
            if (angle < 45.0 || angle > 135.0) return false
        }
        return true
    }

    private fun cornerAngle(p: Point, a: Point, b: Point): Double {
        val v1x = a.x - p.x; val v1y = a.y - p.y
        val v2x = b.x - p.x; val v2y = b.y - p.y
        val dot = v1x * v2x + v1y * v2y
        val mag1 = sqrt(v1x * v1x + v1y * v1y); val mag2 = sqrt(v2x * v2x + v2y * v2y)
        if (mag1 == 0.0 || mag2 == 0.0) return 0.0
        return Math.toDegrees(kotlin.math.acos((dot / (mag1 * mag2)).coerceIn(-1.0, 1.0)))
    }

    private fun distance(a: Point, b: Point): Double = sqrt((a.x - b.x).let { it * it } + (a.y - b.y).let { it * it })

    private fun applyStability(currentCorners: Array<Point>?, imageArea: Double): Pair<Array<Point>?, String> {
        if (currentCorners == null) {
            noRectFrames++
            if (lastCorners != null && noRectFrames <= MAX_NO_RECT_FRAMES) return lastCorners!! to "📄 Align document"
            lastCorners = null; lastCornersRef.set(null)
            return null to "📄 Align document"
        }
        noRectFrames = 0
        val rectArea = polygonArea(currentCorners)
        if (rectArea < imageArea * SMALL_RECT_FRACTION) {
            lastCorners = currentCorners; lastCornersRef.set(currentCorners.copyOf())
            return currentCorners to "🔍 Move closer"
        }
        if (lastCorners == null) {
            lastCorners = currentCorners; lastCornersRef.set(currentCorners.copyOf())
            return currentCorners to "✅ Ready to capture"
        }
        val maxMovement = (0 until 4).maxOf { distance(currentCorners[it], lastCorners!![it]) }
        return when {
            maxMovement < LOCK_THRESHOLD -> lastCorners!! to "✅ Ready to capture"
            maxMovement < SMOOTH_THRESHOLD -> {
                val smoothed = Array(4) { i -> Point(lastCorners!![i].x * (1 - SMOOTH_ALPHA) + currentCorners[i].x * SMOOTH_ALPHA, lastCorners!![i].y * (1 - SMOOTH_ALPHA) + currentCorners[i].y * SMOOTH_ALPHA) }
                lastCorners = smoothed; lastCornersRef.set(smoothed.copyOf())
                smoothed to "✅ Ready to capture"
            }
            else -> {
                lastCorners = currentCorners; lastCornersRef.set(currentCorners.copyOf())
                currentCorners to "✋ Hold steady"
            }
        }
    }

    private fun polygonArea(pts: Array<Point>): Double {
        var area = 0.0
        for (i in pts.indices) {
            val j = (i + 1) % pts.size
            area += pts[i].x * pts[j].y
            area -= pts[j].x * pts[i].y
        }
        return abs(area) / 2.0
    }

    private fun shapeRegularity(pts: Array<Point>): Float {
        var err = 0.0
        for (i in 0 until 4) err += abs(cornerAngle(pts[i], pts[(i + 1) % 4], pts[(i + 3) % 4]) - 90.0)
        return (1.0 - err / 120.0).toFloat().coerceIn(0f, 1f)
    }
}

private typealias PointArray = Array<Point>
