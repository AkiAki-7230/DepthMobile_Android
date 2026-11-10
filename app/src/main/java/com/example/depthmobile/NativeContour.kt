package com.example.depthmobile

import android.graphics.Bitmap

object NativeContour {


    init {

        System.loadLibrary(
            "depth_contour"
        )

    }


    external fun generateContour(

        depth: FloatArray,

        width: Int,

        height: Int,

        levels: Int,

        lineOpacity: Int,

        lineThicknessPx: Int,

        renderScale: Int,

        guideBitmap: Bitmap,

        structuralDetailOpacity: Int,

        maximumStructuralDetails: Int

    ): ByteArray


    /** Returns x, y, normalized colour depth, and metric depth for each contour label. */
    external fun generateContourLabels(
        depth: FloatArray,
        width: Int,
        height: Int,
        levels: Int,
        renderScale: Int
    ): FloatArray


}
