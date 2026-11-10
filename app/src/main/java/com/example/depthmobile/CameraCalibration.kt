package com.example.depthmobile

import java.util.Locale

/** Intrinsics exported from the MATLAB cameraParameters object for the rear camera. */
data class CameraCalibration(
    val referenceWidth: Int,
    val referenceHeight: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    /** OpenCV order: k1, k2, p1, p2, k3. */
    val distortion: FloatArray
)

data class CameraCalibrationProfile(
    val manufacturer: String,
    val model: String,
    val calibration: CameraCalibration
) {
    init {
        require(manufacturer.isNotBlank()) { "Camera profile manufacturer must not be blank" }
        require(model.isNotBlank()) { "Camera profile model must not be blank" }
    }

    fun matches(deviceManufacturer: String, deviceModel: String): Boolean =
        manufacturer.normalizedDeviceName() == deviceManufacturer.normalizedDeviceName() &&
            model.normalizedDeviceName() == deviceModel.normalizedDeviceName()
}

object AppCameraCalibration {
    /*
     * These MATLAB parameters predate device-identity logging, so applying them to every phone is
     * unsafe. Fill both identity constants only after confirming the exact calibrated device.
     * Until then YOLO receives the original CameraX image, which is the correct cross-device
     * fallback for a model trained on mixed, ordinary camera imagery.
     */
    private const val LEGACY_CALIBRATION_MANUFACTURER = ""
    private const val LEGACY_CALIBRATION_MODEL = ""

    private val legacyRearCamera = CameraCalibration(
        referenceWidth = 480,
        referenceHeight = 640,
        fx = 540.311696842f,
        fy = 543.315530182f,
        cx = 251.823822778f,
        cy = 327.135696692f,
        distortion = floatArrayOf(
            -0.013592339f,
            1.003754450f,
            0.013639523f,
            0.012506331f,
            -2.484783596f
        )
    )

    private val rearCameraProfiles: List<CameraCalibrationProfile> =
        if (LEGACY_CALIBRATION_MANUFACTURER.isNotBlank() &&
            LEGACY_CALIBRATION_MODEL.isNotBlank()
        ) {
            listOf(
                CameraCalibrationProfile(
                    manufacturer = LEGACY_CALIBRATION_MANUFACTURER,
                    model = LEGACY_CALIBRATION_MODEL,
                    calibration = legacyRearCamera
                )
            )
        } else {
            emptyList()
        }

    fun rearCameraFor(manufacturer: String, model: String): CameraCalibration? =
        rearCameraProfiles.firstOrNull { it.matches(manufacturer, model) }?.calibration
}

private fun String.normalizedDeviceName(): String = trim().lowercase(Locale.US)
