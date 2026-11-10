package com.example.depthmobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCalibrationProfileTest {
    private val calibration = CameraCalibration(
        referenceWidth = 480,
        referenceHeight = 640,
        fx = 500f,
        fy = 500f,
        cx = 240f,
        cy = 320f,
        distortion = FloatArray(5)
    )

    @Test
    fun deviceMatchIsTrimmedAndCaseInsensitive() {
        val profile = CameraCalibrationProfile("Example", "Phone 1", calibration)

        assertTrue(profile.matches(" example ", "PHONE 1"))
        assertFalse(profile.matches("Example", "Phone 2"))
    }
}
