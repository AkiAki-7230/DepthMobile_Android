package com.example.depthmobile

import android.content.res.AssetManager
import java.util.Properties

/**
 * Deployment-time residual scale for the metric depth already emitted by YOLO26-depth.
 *
 * The NCNN graph contains the model's baked-in log-depth calibration. This value is only the
 * additional global correction fitted on held-out, multi-device production data:
 *
 *     calibratedDepth = ncnnDepth * correctionScale
 */
data class DepthMetricCalibration(
    val correctionScale: Float,
    val source: String,
    val sampleCount: Int,
    val heldOutDeviceCount: Int
) {
    init {
        require(correctionScale.isFinite() && correctionScale in MIN_SCALE..MAX_SCALE) {
            "Depth correction scale must be finite and within $MIN_SCALE..$MAX_SCALE"
        }
        require(source.isNotBlank()) { "Depth calibration source must not be blank" }
        require(sampleCount >= 0) { "Depth calibration sample count must not be negative" }
        require(heldOutDeviceCount >= 0) {
            "Depth calibration held-out device count must not be negative"
        }
    }

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 4.0f

        val IDENTITY = DepthMetricCalibration(
            correctionScale = 1.0f,
            source = "identity",
            sampleCount = 0,
            heldOutDeviceCount = 0
        )
    }
}

object AppDepthMetricCalibration {
    const val ASSET_NAME = "depth_metric_calibration.properties"

    fun load(assetManager: AssetManager): DepthMetricCalibration = runCatching {
        val properties = Properties().apply {
            assetManager.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use(::load)
        }
        parse(properties)
    }.getOrElse {
        android.util.Log.w(
            "DEPTH_CALIBRATION",
            "Unable to load $ASSET_NAME; using identity correction",
            it
        )
        DepthMetricCalibration.IDENTITY
    }

    internal fun parse(properties: Properties): DepthMetricCalibration = DepthMetricCalibration(
        correctionScale = properties.required("correction_scale").toFloat(),
        source = properties.required("source"),
        sampleCount = properties.required("sample_count").toInt(),
        heldOutDeviceCount = properties.required("held_out_devices").toInt()
    )

    private fun Properties.required(key: String): String =
        getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Missing depth calibration property: $key")
}
