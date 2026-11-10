package com.example.depthmobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.depthmobile.view.DepthOverlayView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var depthOverlay: DepthOverlayView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val obstacleAlerter by lazy { ObstacleAlerter(this) }
    private var undistortCameraInput = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val metricCalibration = AppDepthMetricCalibration.load(assets)
        try {
            Log.d("NCNN", NativeNcnn.initialize(assets))
            val scaleConfigured = NativeNcnn.setDepthScaleCorrection(
                metricCalibration.correctionScale
            )
            if (!scaleConfigured) {
                NativeNcnn.setDepthScaleCorrection(1.0f)
            }
            Log.d(
                "DEPTH_CALIBRATION",
                "configured=$scaleConfigured scale=${metricCalibration.correctionScale} " +
                    "source=${metricCalibration.source} samples=${metricCalibration.sampleCount} " +
                    "heldOutDevices=${metricCalibration.heldOutDeviceCount}"
            )
        } catch (error: Throwable) {
            Log.e("NCNN", "Unable to initialise the depth model", error)
        }

        val calibration = AppCameraCalibration.rearCameraFor(Build.MANUFACTURER, Build.MODEL)
        undistortCameraInput = calibration?.let {
            NativeCalibration.configure(
                it.referenceWidth,
                it.referenceHeight,
                it.fx,
                it.fy,
                it.cx,
                it.cy,
                it.distortion
            )
        } ?: false
        Log.d(
            "CAMERA_CALIBRATION",
            "device=${Build.MANUFACTURER}/${Build.MODEL} matched=${calibration != null} " +
                "configured=$undistortCameraInput"
        )

        previewView = findViewById(R.id.previewView)
        depthOverlay = findViewById(R.id.depthOverlay)
        volumeControlStream = AudioManager.STREAM_MUSIC
        depthOverlay.postDelayed({ obstacleAlerter.playStartupCheck() }, 700L)

        // 标定按钮
        findViewById<Button>(R.id.btnCalibration).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder()
                    .setTargetRotation(previewView.display.rotation)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // YOLO26n-depth letterboxes this frame to 384x384 in native code. Requesting
                    // a nearby 4:3 analysis size avoids converting pixels the model cannot use.
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(384, 288),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    )
                    .setTargetRotation(previewView.display.rotation)
                    .build()
                    .also {
                        it.setAnalyzer(
                            cameraExecutor,
                            DepthAnalyzer(
                                overlayView = depthOverlay,
                                visualizationStyle = DepthVisualizationStyle(
                                    contourLevels = 5,
                                    lineOpacity = 180,
                                    lineThicknessPx = 1,
                                    labelOpacity = 220,
                                    labelTextSizeSp = 12f,
                                    showDepthLabels = true,
                                    contourRenderScale = 1,
                                    showStructuralDetails = false,
                                    structuralDetailOpacity = 120,
                                    maximumStructuralDetails = 2
                                ),
                                obstacleAlerter = obstacleAlerter,
                                undistortCameraInput = undistortCameraInput
                            )
                        )
                    }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (error: Throwable) {
                Log.e("CAMERA", "Unable to bind camera use cases", error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        cameraExecutor.shutdownNow()
        obstacleAlerter.release()
        super.onDestroy()
    }

    private companion object {
        const val CAMERA_PERMISSION_REQUEST = 100
    }
}
