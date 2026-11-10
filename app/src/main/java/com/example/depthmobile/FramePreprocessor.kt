package com.example.depthmobile

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy

/**
 * 把 YUV_420_888 相机帧转换为方向正确的 ARGB 位图，并跨帧复用位图与像素缓冲。
 *
 * 实时分析与标定分析共用这一份实现，确保两条链路送给 NCNN 模型的是完全相同的
 * 几何与颜色，避免标定数据与生产数据出现系统性偏差。
 */
class FramePreprocessor {

    private var reusableBitmap: Bitmap? = null
    private var argbPixels = IntArray(0)

    /** 最近一帧的平均亮度（0..255），用于低光判定。 */
    var lastMeanLuma: Float = 255f
        private set

    fun toUprightBitmap(image: ImageProxy): Bitmap? {
        val sourceWidth = image.width
        val sourceHeight = image.height
        if (sourceWidth <= 0 || sourceHeight <= 0 || image.planes.size < 3) return null

        val rotation = image.imageInfo.rotationDegrees
        val outputWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
        val outputHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
        val pixelCount = outputWidth * outputHeight

        if (argbPixels.size != pixelCount) {
            argbPixels = IntArray(pixelCount)
        }

        val bitmap = reusableBitmap
            ?.takeIf { it.width == outputWidth && it.height == outputHeight }
            ?: Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888).also {
                reusableBitmap = it
            }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yStart = yBuffer.position()
        val uStart = uBuffer.position()
        val vStart = vBuffer.position()
        var lumaSampleSum = 0L
        var lumaSampleCount = 0

        for (y in 0 until sourceHeight) {
            val yRow = yStart + y * yPlane.rowStride
            val chromaRow = (y shr 1) * uPlane.rowStride
            for (x in 0 until sourceWidth) {
                val luma = yBuffer.get(yRow + x * yPlane.pixelStride).toInt() and 0xff
                if ((x and 3) == 0 && (y and 3) == 0) {
                    lumaSampleSum += luma
                    lumaSampleCount++
                }
                val chromaColumn = (x shr 1) * uPlane.pixelStride
                val u = (uBuffer.get(uStart + chromaRow + chromaColumn).toInt() and 0xff) - 128
                val v = (vBuffer.get(vStart + (y shr 1) * vPlane.rowStride + (x shr 1) * vPlane.pixelStride).toInt() and 0xff) - 128

                // ITU-R BT.601 limited-range YUV -> RGB.
                val c = (luma - 16).coerceAtLeast(0)
                val red = ((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)
                val green = ((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val blue = ((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)

                val destination = when (rotation) {
                    90 -> x * outputWidth + outputWidth - 1 - y
                    180 -> (outputHeight - 1 - y) * outputWidth + outputWidth - 1 - x
                    270 -> (sourceWidth - 1 - x) * outputWidth + y
                    else -> y * outputWidth + x
                }
                argbPixels[destination] = Color.rgb(red, green, blue)
            }
        }

        if (lumaSampleCount > 0) {
            lastMeanLuma = lumaSampleSum.toFloat() / lumaSampleCount
        }
        bitmap.setPixels(argbPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
        return bitmap
    }
}
