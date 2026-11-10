package com.example.depthmobile

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.Arrays

/**
 * 标定专用深度分析器。
 *
 * 与实时链路共用 [FramePreprocessor] 和原生 LetterBox 预处理：相机帧不做任何拉伸，
 * 直接交给 `NativeNcnn.runDepth`，因此标定读到的深度与生产链路一致。
 *
 * 每次采集不是只取单帧，而是在最近若干帧的中心区域中位数上再做统计，
 * 并给出相对离散度，供界面判断"是否已稳定"。
 */
class CalibrationDepthAnalyzer : ImageAnalysis.Analyzer {

    companion object {
        const val DEPTH_WIDTH = 384
        const val DEPTH_HEIGHT = 384
        private const val TAG = "CalibrationAnalyzer"
        private const val HALF_WINDOW = 10
        private const val MAX_FRAME_SAMPLES = 30
        private const val MIN_STABLE_FRAMES = 10
        private const val MAX_RELATIVE_SPREAD = 0.12f
        private val WINDOW_SIZE = (HALF_WINDOW * 2 + 1) * (HALF_WINDOW * 2 + 1)
    }

    /** 一次读取的统计结果。 */
    data class Reading(
        val depthMeters: Float,
        val relativeSpread: Float,
        val frameCount: Int
    ) {
        val isStable: Boolean
            get() = frameCount >= MIN_STABLE_FRAMES &&
                depthMeters > 0f &&
                relativeSpread <= MAX_RELATIVE_SPREAD
    }

    private val framePreprocessor = FramePreprocessor()
    private val sampleLock = Any()
    private val frameSamples = FloatArray(MAX_FRAME_SAMPLES)
    private var sampleCount = 0
    private var sampleWriteIndex = 0
    private val windowValues = FloatArray(WINDOW_SIZE)
    private var invalidOutputLogged = false

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val bitmap = framePreprocessor.toUprightBitmap(imageProxy) ?: return
            val depthMap = NativeNcnn.runDepth(bitmap)

            if (depthMap.size != DEPTH_WIDTH * DEPTH_HEIGHT) {
                if (!invalidOutputLogged) {
                    invalidOutputLogged = true
                    Log.e(TAG, "Unexpected depth size: ${depthMap.size}")
                }
                return
            }

            val centerDepth = centerMedian(depthMap)
            if (centerDepth > 0f) {
                synchronized(sampleLock) {
                    frameSamples[sampleWriteIndex] = centerDepth
                    sampleWriteIndex = (sampleWriteIndex + 1) % MAX_FRAME_SAMPLES
                    if (sampleCount < MAX_FRAME_SAMPLES) sampleCount++
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "深度分析失败", error)
        } finally {
            imageProxy.close()
        }
    }

    /** 读取当前滑动窗口的统计值；样本不足时返回 null。 */
    fun readCenterDepth(): Reading? {
        val values = synchronized(sampleLock) {
            if (sampleCount < MIN_STABLE_FRAMES) return null
            val count = sampleCount
            val snapshot = FloatArray(count)
            val start = if (count == MAX_FRAME_SAMPLES) sampleWriteIndex else 0
            for (i in 0 until count) {
                snapshot[i] = frameSamples[(start + i) % MAX_FRAME_SAMPLES]
            }
            snapshot
        }

        Arrays.sort(values)
        val median = percentile(values, 0.5f)
        val lower = percentile(values, 0.25f)
        val upper = percentile(values, 0.75f)
        val spread = (upper - lower) / median.coerceAtLeast(0.01f)

        return Reading(
            depthMeters = median,
            relativeSpread = spread,
            frameCount = values.size
        )
    }

    /** 采集完成后清空窗口，避免上一次的样本混入下一次。 */
    fun resetSamples() {
        synchronized(sampleLock) {
            sampleCount = 0
            sampleWriteIndex = 0
        }
    }

    /** 取中心 21×21 区域内有效深度的中位数（比均值更抗背景与边缘污染）。 */
    private fun centerMedian(depthMap: FloatArray): Float {
        val centerX = DEPTH_WIDTH / 2
        val centerY = DEPTH_HEIGHT / 2
        var count = 0
        for (dy in -HALF_WINDOW..HALF_WINDOW) {
            val y = centerY + dy
            if (y !in 0 until DEPTH_HEIGHT) continue
            for (dx in -HALF_WINDOW..HALF_WINDOW) {
                val x = centerX + dx
                if (x !in 0 until DEPTH_WIDTH) continue
                val value = depthMap[y * DEPTH_WIDTH + x]
                if (value.isFinite() && value > 0f) {
                    windowValues[count++] = value
                }
            }
        }
        if (count == 0) return 0f
        Arrays.sort(windowValues, 0, count)
        return windowValues[count / 2]
    }

    private fun percentile(sorted: FloatArray, ratio: Float): Float {
        val position = ratio.coerceIn(0f, 1f) * (sorted.size - 1)
        val left = position.toInt()
        val right = (left + 1).coerceAtMost(sorted.size - 1)
        val fraction = position - left
        return sorted[left] + (sorted[right] - sorted[left]) * fraction
    }
}
