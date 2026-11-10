package com.example.depthmobile

import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DepthMetricCalibrationTest {
    @Test
    fun parsesValidProperties() {
        val properties = Properties().apply {
            setProperty("correction_scale", "1.075")
            setProperty("source", "multi_device_scale_only_cv")
            setProperty("sample_count", "240")
            setProperty("held_out_devices", "8")
        }

        val calibration = AppDepthMetricCalibration.parse(properties)

        assertEquals(1.075f, calibration.correctionScale, 0.000001f)
        assertEquals("multi_device_scale_only_cv", calibration.source)
        assertEquals(240, calibration.sampleCount)
        assertEquals(8, calibration.heldOutDeviceCount)
    }

    @Test
    fun rejectsUnsafeScale() {
        assertThrows(IllegalArgumentException::class.java) {
            DepthMetricCalibration(
                correctionScale = 8.0f,
                source = "invalid",
                sampleCount = 1,
                heldOutDeviceCount = 1
            )
        }
    }
}
