package com.example.depthmobile

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.depthmobile.view.DepthOverlayView
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Converts the camera stream into the depth-model input and produces the coloured
 * depth-contour overlay. This analyzer owns its working buffers, so no bitmap or
 * pixel array is allocated for every camera frame.
 */
class DepthAnalyzer(
    private val overlayView: DepthOverlayView,
    private val visualizationStyle: DepthVisualizationStyle = DepthVisualizationStyle(),
    private val obstacleAlerter: ObstacleAlerter,
    private val undistortCameraInput: Boolean = false
) : ImageAnalysis.Analyzer {

    companion object {
        private const val DEPTH_WIDTH = 384
        private const val DEPTH_HEIGHT = 384
        private const val SMOOTHING_TIME_CONSTANT_MS = 160f
        private const val MIN_SMOOTHING_ALPHA = 0.35f
        private const val MAX_SMOOTHING_ALPHA = 0.92f
        private const val FAST_CHANGE_ALPHA = 0.78f
        private const val FAST_CHANGE_RELATIVE_THRESHOLD = 0.12f
        private const val MIN_INFERENCE_INTERVAL_MS = 66L
        private const val LOW_LIGHT_LUMA = 32f
        private const val LOW_LIGHT_CONFIRM_FRAMES = 3
        private const val LIGHT_RECOVERY_FRAMES = 2
        private const val CONTOUR_REFRESH_EVERY_FRAMES = 2

        // 中央距离读数：取画面中部区域的中位数，并做滞回/平滑避免数字跳动。
        private const val CENTER_REGION_LEFT = 0.42f
        private const val CENTER_REGION_RIGHT = 0.58f
        private const val CENTER_REGION_TOP = 0.38f
        private const val CENTER_REGION_BOTTOM = 0.62f
        private const val CENTER_SMALL_CHANGE = 0.08f
        private const val CENTER_LARGE_CHANGE = 0.40f
        private const val CENTER_CHANGE_HOLD_MS = 600L
        private const val CENTER_SMALL_ALPHA = 0.18f
        private const val CENTER_LARGE_ALPHA = 0.6f
    }

    private val framePreprocessor = FramePreprocessor()
    private var smoothedDepth: FloatArray? = null
    private var lastInferenceAtMs = 0L
    private var lastSmoothedDepthAtMs = 0L
    private var lastCompletedFrameAtMs = 0L
    private var displayedDepthFps = 0f
    private var completedFrameCount = 0
    private var calibrationWarningLogged = false
    private var darkFrameCount = 0
    private var lightRecoveryCount = 0
    private var lowLight = false
    private var cachedContour = ByteArray(0)
    private var analyzedFrameIndex = 0
    private var displayedCenterDistance = Float.NaN
    private var centerDistanceChangedAtMs = 0L
    private val centerWindow = FloatArray(8192)
    private val obstacleDetector = ObstacleDetector(DEPTH_WIDTH, DEPTH_HEIGHT)

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastInferenceAtMs < MIN_INFERENCE_INTERVAL_MS) return
            lastInferenceAtMs = now
            val frameStartedAtMs = now

            val inputBitmap = framePreprocessor.toUprightBitmap(image) ?: run {
                publishUnavailable("Unable to convert camera frame")
                return
            }
            val perceptionStatus = updatePerceptionStatus()
            if (undistortCameraInput &&
                !NativeCalibration.undistortInPlace(inputBitmap) &&
                !calibrationWarningLogged
            ) {
                calibrationWarningLogged = true
                android.util.Log.w(
                    "CAMERA_CALIBRATION",
                    "Calibration skipped: analyzer dimensions or bitmap format do not match"
                )
            }
            val inferenceStartedAtMs = SystemClock.elapsedRealtime()
            val depth = NativeNcnn.runDepth(inputBitmap)
            val inferenceFinishedAtMs = SystemClock.elapsedRealtime()

            if (depth.size != DEPTH_WIDTH * DEPTH_HEIGHT) {
                android.util.Log.e("DEPTH_ANALYZER", "Unexpected depth size: ${depth.size}")
                publishUnavailable("Unexpected depth output")
                return
            }

            val stableDepth = smoothDepth(depth, inferenceFinishedAtMs)
            val obstacleAlert = obstacleDetector.analyze(stableDepth, perceptionStatus)
            obstacleAlerter.update(obstacleAlert)
            val renderWidth = DEPTH_WIDTH * visualizationStyle.contourRenderScale
            val renderHeight = DEPTH_HEIGHT * visualizationStyle.contourRenderScale
            val expectedContourBytes = renderWidth * renderHeight * 4
            val refreshContour = cachedContour.size != expectedContourBytes ||
                analyzedFrameIndex % CONTOUR_REFRESH_EVERY_FRAMES == 0
            val rgba = if (refreshContour) {
                NativeContour.generateContour(
                    stableDepth,
                    DEPTH_WIDTH,
                    DEPTH_HEIGHT,
                    visualizationStyle.contourLevels,
                    visualizationStyle.lineOpacity,
                    visualizationStyle.lineThicknessPx,
                    visualizationStyle.contourRenderScale,
                    inputBitmap,
                    if (visualizationStyle.showStructuralDetails) {
                        visualizationStyle.structuralDetailOpacity
                    } else {
                        0
                    },
                    visualizationStyle.maximumStructuralDetails
                ).also {
                    if (it.size == expectedContourBytes) cachedContour = it
                }
            } else {
                cachedContour
            }
            analyzedFrameIndex++

            if (rgba.size != expectedContourBytes) {
                android.util.Log.e("DEPTH_ANALYZER", "Unexpected contour buffer size: ${rgba.size}")
                return
            }

            val labels = if (visualizationStyle.showDepthLabels) {
                NativeContour.generateContourLabels(
                    stableDepth,
                    DEPTH_WIDTH,
                    DEPTH_HEIGHT,
                    visualizationStyle.contourLevels,
                    visualizationStyle.contourRenderScale
                )
            } else {
                FloatArray(0)
            }

            val frameFinishedAtMs = SystemClock.elapsedRealtime()
            val centerDistanceMeters = updateCenterDistance(stableDepth, frameFinishedAtMs)
            val depthFps = updateDepthFps(frameFinishedAtMs)
            completedFrameCount++
            if (completedFrameCount == 1 || completedFrameCount % 30 == 0) {
                android.util.Log.d(
                    "DEPTH_PERF",
                    "frame=$completedFrameCount inference=${inferenceFinishedAtMs - inferenceStartedAtMs}ms " +
                        "endToEnd=${frameFinishedAtMs - frameStartedAtMs}ms fps=%.1f".format(depthFps)
                )
            }

            overlayView.updateFrame(
                rgba,
                renderWidth,
                renderHeight,
                labels,
                visualizationStyle,
                depthFps,
                obstacleAlert,
                centerDistanceMeters
            )
        } catch (error: Throwable) {
            android.util.Log.e("DEPTH_ANALYZER", "Depth frame processing failed", error)
            publishUnavailable("Depth processing failed")
        } finally {
            image.close()
        }
    }

    private fun updatePerceptionStatus(): ObstacleAlert.PerceptionStatus {
        if (framePreprocessor.lastMeanLuma < LOW_LIGHT_LUMA) {
            darkFrameCount++
            lightRecoveryCount = 0
            if (darkFrameCount >= LOW_LIGHT_CONFIRM_FRAMES) lowLight = true
        } else {
            lightRecoveryCount++
            darkFrameCount = 0
            if (lightRecoveryCount >= LIGHT_RECOVERY_FRAMES) lowLight = false
        }
        return if (lowLight) {
            ObstacleAlert.PerceptionStatus.LOW_LIGHT
        } else {
            ObstacleAlert.PerceptionStatus.READY
        }
    }

    private fun publishUnavailable(reason: String) {
        android.util.Log.w("DEPTH_ANALYZER", reason)
        displayedCenterDistance = Float.NaN
        val unavailable = ObstacleAlert.unavailable()
        obstacleAlerter.update(unavailable)
        overlayView.updatePerceptionStatus(unavailable.perceptionStatus)
    }

    /**
     * 画面中央区域的中位数距离（米），并对读数做滞回与平滑：
     * 小幅变化缓慢跟随，突变需保持 600ms 才被接受，避免数字来回跳动。
     */
    private fun updateCenterDistance(depth: FloatArray, nowMs: Long): Float {
        val median = centerRegionMedian(depth)
        if (!median.isFinite() || median <= 0f) return displayedCenterDistance

        val previous = displayedCenterDistance
        if (!previous.isFinite() || previous <= 0f) {
            displayedCenterDistance = median
            centerDistanceChangedAtMs = nowMs
            return median
        }

        val relativeChange = abs(median - previous) / previous
        val alpha = when {
            relativeChange <= CENTER_SMALL_CHANGE -> CENTER_SMALL_ALPHA
            nowMs - centerDistanceChangedAtMs < CENTER_CHANGE_HOLD_MS &&
                relativeChange < CENTER_LARGE_CHANGE -> 0f
            else -> CENTER_LARGE_ALPHA
        }
        if (alpha <= 0f) return previous

        val smoothed = previous + (median - previous) * alpha
        displayedCenterDistance = smoothed
        if (alpha >= CENTER_LARGE_ALPHA) centerDistanceChangedAtMs = nowMs
        return smoothed
    }

    private fun centerRegionMedian(depth: FloatArray): Float {
        val left = (DEPTH_WIDTH * CENTER_REGION_LEFT).toInt()
        val right = (DEPTH_WIDTH * CENTER_REGION_RIGHT).toInt()
        val top = (DEPTH_HEIGHT * CENTER_REGION_TOP).toInt()
        val bottom = (DEPTH_HEIGHT * CENTER_REGION_BOTTOM).toInt()
        var count = 0
        for (y in top until bottom) {
            val row = y * DEPTH_WIDTH
            for (x in left until right) {
                val value = depth[row + x]
                if (value.isFinite() && value > 0f && count < centerWindow.size) {
                    centerWindow[count++] = value
                }
            }
        }
        if (count == 0) return Float.NaN
        Arrays.sort(centerWindow, 0, count)
        return centerWindow[count / 2]
    }

    private fun smoothDepth(current: FloatArray, completedAtMs: Long): FloatArray {
        val previous = smoothedDepth
        if (previous == null || previous.size != current.size) {
            lastSmoothedDepthAtMs = completedAtMs
            return current.copyOf().also { smoothedDepth = it }
        }

        val elapsedMs = (completedAtMs - lastSmoothedDepthAtMs).coerceAtLeast(1L)
        lastSmoothedDepthAtMs = completedAtMs
        val temporalAlpha = (1f - exp(-elapsedMs / SMOOTHING_TIME_CONSTANT_MS))
            .coerceIn(MIN_SMOOTHING_ALPHA, MAX_SMOOTHING_ALPHA)

        for (index in current.indices) {
            val sample = current[index]
            if (sample.isFinite()) {
                val oldSample = previous[index]
                if (!oldSample.isFinite()) {
                    previous[index] = sample
                    continue
                }
                val relativeChange = abs(sample - oldSample) / max(0.1f, abs(oldSample))
                val alpha = if (relativeChange > FAST_CHANGE_RELATIVE_THRESHOLD) {
                    max(temporalAlpha, FAST_CHANGE_ALPHA)
                } else {
                    temporalAlpha
                }
                previous[index] += (sample - oldSample) * alpha
            }
        }
        return previous
    }

    private fun updateDepthFps(completedAtMs: Long): Float {
        if (lastCompletedFrameAtMs > 0L) {
            val intervalMs = (completedAtMs - lastCompletedFrameAtMs).coerceAtLeast(1L)
            val instantaneousFps = 1000f / intervalMs
            displayedDepthFps = if (displayedDepthFps == 0f) {
                instantaneousFps
            } else {
                displayedDepthFps + (instantaneousFps - displayedDepthFps) * 0.22f
            }
        }
        lastCompletedFrameAtMs = completedAtMs
        return displayedDepthFps
    }
}
