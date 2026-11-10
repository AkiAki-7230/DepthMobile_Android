package com.example.depthmobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 深度标定Activity
 * 引导用户在固定距离拍摄照片，收集深度数据用于标定
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvDistance: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnFinish: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var calibrationAnalyzer: CalibrationDepthAnalyzer? = null
    private var metricCalibration: DepthMetricCalibration = DepthMetricCalibration.IDENTITY

    private val distances = listOf(1.0f, 2.0f, 3.0f, 5.0f)
    private var currentStep = 0
    private val capturedData = mutableListOf<CalibrationSample>()

    data class CalibrationSample(
        val targetDistance: Float,
        val measuredDepth: Float,
        val centerX: Int,
        val centerY: Int,
        val timestamp: Long,
        val frameCount: Int,
        val relativeSpread: Float
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        previewView = findViewById(R.id.previewView)
        tvStatus = findViewById(R.id.tvStatus)
        tvDistance = findViewById(R.id.tvDistance)
        btnCapture = findViewById(R.id.btnCapture)
        btnFinish = findViewById(R.id.btnFinish)

        metricCalibration = AppDepthMetricCalibration.load(assets)

        // 进程被回收后可能只重建本页面，这里显式保证模型已加载，而不是依赖主界面。
        if (!NativeNcnn.isModelInitialized) {
            NativeNcnn.initialize(assets)
        }
        if (!NativeNcnn.isModelInitialized) {
            Toast.makeText(this, "深度模型加载失败，无法标定", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        updateUI()

        btnCapture.setOnClickListener {
            captureDepthData()
        }

        btnFinish.setOnClickListener {
            finishCalibration()
        }

        if (checkPermissions()) {
            startCamera()
        } else {
            requestPermissions()
        }
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
               PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
            finish()
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

                calibrationAnalyzer = CalibrationDepthAnalyzer()

                // 标定必须读取模型原始输出。若当前 APK 已带校正系数，先复位为 1.0，
                // 否则重新标定会把旧系数叠加进测量值，导致系数越标越偏。
                NativeNcnn.setDepthScaleCorrection(1.0f)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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
                        it.setAnalyzer(cameraExecutor, calibrationAnalyzer!!)
                    }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (error: Throwable) {
                Log.e(TAG, "相机启动失败", error)
                Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateUI() {
        if (currentStep < distances.size) {
            val distance = distances[currentStep]
            tvDistance.text = String.format("%.1fm", distance)
            tvStatus.text = "请将物体放置在${distance}米处，对准画面中心"
            btnCapture.isEnabled = true
            btnFinish.isEnabled = false
        } else {
            tvDistance.text = "完成"
            tvStatus.text = "已完成 ${capturedData.size} 个样本"
            btnCapture.isEnabled = false
            btnFinish.isEnabled = true
        }
    }

    private fun captureDepthData() {
        val analyzer = calibrationAnalyzer ?: return
        val reading = analyzer.readCenterDepth()

        if (reading == null) {
            Toast.makeText(this, "深度尚未稳定，请对准目标并保持约 1 秒", Toast.LENGTH_SHORT).show()
            return
        }
        if (!reading.isStable) {
            Toast.makeText(
                this,
                String.format(
                    "画面深度波动约 ±%.0f%%，请保持手机与目标静止后重试",
                    reading.relativeSpread * 100f
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val sample = CalibrationSample(
            targetDistance = distances[currentStep],
            measuredDepth = reading.depthMeters,
            centerX = 192,  // 384/2
            centerY = 192,  // 384/2
            timestamp = System.currentTimeMillis(),
            frameCount = reading.frameCount,
            relativeSpread = reading.relativeSpread
        )

        capturedData.add(sample)
        Log.d(
            TAG,
            "采集: 目标=${sample.targetDistance}m 测量=%.3fm 帧数=${sample.frameCount} 波动=%.1f%%"
                .format(sample.measuredDepth, sample.relativeSpread * 100f)
        )

        Toast.makeText(
            this,
            String.format(
                "已采集: %.2fm (目标 %.1fm, %d 帧)",
                reading.depthMeters,
                distances[currentStep],
                reading.frameCount
            ),
            Toast.LENGTH_SHORT
        ).show()

        analyzer.resetSamples()
        currentStep++
        updateUI()
    }

    private fun finishCalibration() {
        if (capturedData.size < distances.size) {
            Toast.makeText(this, "请完成全部 ${distances.size} 个距离的采集", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val jsonFile = saveCalibrationData()
            Toast.makeText(
                this,
                "标定数据已保存:\n${jsonFile.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "保存失败", e)
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveCalibrationData(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())

        val jsonObject = JSONObject().apply {
            put("device_manufacturer", android.os.Build.MANUFACTURER)
            put("device_model", android.os.Build.MODEL)
            put("timestamp", timestamp)
            put("sample_count", capturedData.size)
            // 采集期间原生层被强制复位为恒等校正；记录该值便于脚本校验与追溯。
            put("applied_correction_scale", 1.0f)

            val samplesArray = JSONArray()
            capturedData.forEach { sample ->
                samplesArray.put(JSONObject().apply {
                    put("target_distance_m", sample.targetDistance)
                    put("measured_depth_m", sample.measuredDepth)
                    put("center_x", sample.centerX)
                    put("center_y", sample.centerY)
                    put("timestamp_ms", sample.timestamp)
                    put("frame_count", sample.frameCount)
                    put("relative_spread", sample.relativeSpread)
                })
            }
            put("samples", samplesArray)
        }

        // 写入应用专属外部目录：无需存储权限，Android 10+ 分区存储下也可用，
        // 且仍可通过 adb pull 直接取出。
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val calibrationDir = File(baseDir, "DepthCalibration")
        calibrationDir.mkdirs()

        val file = File(calibrationDir, "calibration_$timestamp.json")
        file.writeText(jsonObject.toString(2))

        Log.d(TAG, "标定数据已保存: ${file.absolutePath}")
        return file
    }

    override fun onDestroy() {
        cameraExecutor.shutdownNow()
        // 离开标定页时恢复当前 APK 的校正系数，避免影响主界面的距离判断。
        NativeNcnn.setDepthScaleCorrection(metricCalibration.correctionScale)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CalibrationActivity"
        private const val PERMISSION_REQUEST_CODE = 101
    }
}
