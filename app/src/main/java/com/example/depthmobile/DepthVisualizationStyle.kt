package com.example.depthmobile

import java.util.Locale

/** Parameters for the depth-contour presentation layer. */
data class DepthVisualizationStyle(
    val contourLevels: Int = 5,
    val lineOpacity: Int = 180,
    val lineThicknessPx: Int = 1,
    val labelOpacity: Int = 220,
    val labelTextSizeSp: Float = 12f,
    val showDepthLabels: Boolean = false,
    /** Upscales only contour rendering; model inference stays at its native resolution. */
    val contourRenderScale: Int = 1,
    /** Uses a few stable RGB boundaries to recover small objects lost in the coarse depth map. */
    val showStructuralDetails: Boolean = false,
    val structuralDetailOpacity: Int = 120,
    val maximumStructuralDetails: Int = 2
) {
    init {
        require(contourLevels in 3..24)
        require(lineOpacity in 0..255)
        require(lineThicknessPx in 1..3)
        require(labelOpacity in 0..255)
        require(labelTextSizeSp in 8f..32f)
        require(contourRenderScale in 1..4)
        require(structuralDetailOpacity in 0..255)
        require(maximumStructuralDetails in 0..12)
    }

    /** YOLO26n-depth returns calibrated metric depth values. */
    fun formatLabel(depthMeters: Float): String =
        if (depthMeters.isFinite() && depthMeters > 0f) {
            String.format(
                Locale.US,
                if (depthMeters < 10f) "≈%.2f 米" else "≈%.1f 米",
                depthMeters
            )
        } else {
            "-- 米"
        }
}
