package com.example.depthmobile


import android.graphics.Bitmap
import android.content.res.AssetManager


object NativeNcnn {


    init {

        System.loadLibrary(
            "depth_contour"
        )

    }

    /** 当前进程内模型是否已成功加载。标定页依赖该状态，避免隐式依赖主界面。 */
    @Volatile
    var isModelInitialized: Boolean = false
        private set

    /** 幂等初始化：已加载时直接返回，不会重复占用内存。 */
    @Synchronized
    fun initialize(assetManager: AssetManager): String {
        if (isModelInitialized) return "YOLO26n-depth init OK - already loaded"
        return try {
            val status = initModel(assetManager)
            isModelInitialized = status.contains("init OK")
            status
        } catch (error: Throwable) {
            isModelInitialized = false
            "init failed: ${error.message}"
        }
    }


    external fun initModel(
        assetManager: AssetManager
    ):String


    /** Applies a residual global metric scale after the model's baked-in calibration. */
    external fun setDepthScaleCorrection(
        correctionScale: Float
    ):Boolean



    external fun runDepth(
        bitmap: Bitmap
    ):FloatArray


}
