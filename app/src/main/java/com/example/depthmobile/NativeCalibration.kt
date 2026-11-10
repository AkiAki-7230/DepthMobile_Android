package com.example.depthmobile

import android.graphics.Bitmap

object NativeCalibration {
    init {
        System.loadLibrary("depth_contour")
    }

    external fun configure(
        referenceWidth: Int,
        referenceHeight: Int,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        distortion: FloatArray
    ): Boolean

    /** Undistorts the analyzer-owned bitmap in place. The CameraX preview remains untouched. */
    external fun undistortInPlace(bitmap: Bitmap): Boolean

    /** Returns a normalized x/y/z camera ray for an undistorted pixel. */
    external fun pixelRay(x: Float, y: Float, width: Int, height: Int): FloatArray
}
