package com.example.depthmobile.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.example.depthmobile.DepthVisualizationStyle
import com.example.depthmobile.ObstacleAlert
import java.nio.ByteBuffer
import java.util.Locale

/** Draws the colour-coded isodepth contours, legend and contour annotations over CameraX. */
class DepthOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class DepthLabel(
        val x: Float,
        val y: Float,
        val normalizedDepth: Float,
        val depthMeters: Float
    )
    private data class PendingFrame(
        val rgba: ByteArray,
        val width: Int,
        val height: Int,
        val labels: FloatArray,
        val style: DepthVisualizationStyle,
        val depthFps: Float,
        val obstacleAlert: ObstacleAlert,
        val centerDistanceMeters: Float
    )

    private val frameLock = Any()
    private var pendingFrame: PendingFrame? = null
    private var updateScheduled = false
    private var contourBitmap: Bitmap? = null
    private var depthLabels: List<DepthLabel> = emptyList()
    private var visualizationStyle = DepthVisualizationStyle()
    private var depthFps = 0f
    private var obstacleAlert = ObstacleAlert.CLEAR
    private var centerDistanceMeters = Float.NaN
    private var lastDepthFrameAtMs = 0L

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
    }
    private val dimPaint = Paint().apply { color = Color.argb(24, 0, 0, 0) }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val legendLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK)
    }
    private val annotationLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.25f)
    }
    private val annotationFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val annotationTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK)
    }
    private val performanceBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
    }
    private val performanceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val centerDistanceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val safetyZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val safetyZoneLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK)
    }
    private val obstacleBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val obstacleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val obstacleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(16f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val guidanceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(15f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val staleFrameCheck = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (lastDepthFrameAtMs > 0L && now - lastDepthFrameAtMs > 1_800L &&
                obstacleAlert.perceptionStatus != ObstacleAlert.PerceptionStatus.DEPTH_UNAVAILABLE
            ) {
                obstacleAlert = obstacleAlert.copy(
                    guidance = ObstacleAlert.Guidance.STOP,
                    perceptionStatus = ObstacleAlert.PerceptionStatus.DEPTH_UNAVAILABLE
                )
                invalidate()
            }
            postDelayed(this, 800L)
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(staleFrameCheck)
        postDelayed(staleFrameCheck, 800L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(staleFrameCheck)
        super.onDetachedFromWindow()
    }

    /** Called on CameraX's worker thread; only the newest result is committed on the UI thread. */
    fun updateFrame(
        rgba: ByteArray,
        width: Int,
        height: Int,
        labels: FloatArray,
        style: DepthVisualizationStyle,
        depthFps: Float,
        obstacleAlert: ObstacleAlert,
        centerDistanceMeters: Float
    ) {
        if (width <= 0 || height <= 0 || rgba.size != width * height * 4 || labels.size % 4 != 0) return

        synchronized(frameLock) {
            pendingFrame = PendingFrame(
                rgba, width, height, labels, style, depthFps, obstacleAlert, centerDistanceMeters
            )
            if (!updateScheduled) {
                updateScheduled = true
                post { commitNewestFrame() }
            }
        }
    }

    fun updatePerceptionStatus(status: ObstacleAlert.PerceptionStatus) {
        post {
            obstacleAlert = obstacleAlert.copy(
                guidance = if (status == ObstacleAlert.PerceptionStatus.READY) {
                    obstacleAlert.guidance
                } else {
                    ObstacleAlert.Guidance.STOP
                },
                perceptionStatus = status
            )
            if (status != ObstacleAlert.PerceptionStatus.READY) {
                centerDistanceMeters = Float.NaN
            }
            invalidate()
        }
    }

    private fun commitNewestFrame() {
        val frame = synchronized(frameLock) {
            updateScheduled = false
            pendingFrame.also { pendingFrame = null }
        } ?: return

        val bitmap = contourBitmap
            ?.takeIf { it.width == frame.width && it.height == frame.height }
            ?: Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also {
                it.setHasAlpha(true)
                contourBitmap = it
            }

        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.rgba))
        depthLabels = frame.labels.asSequence()
            .chunked(4)
            .map { DepthLabel(it[0], it[1], it[2], it[3]) }
            .toList()
        visualizationStyle = frame.style
        depthFps = frame.depthFps
        obstacleAlert = frame.obstacleAlert
        centerDistanceMeters = frame.centerDistanceMeters
        lastDepthFrameAtMs = SystemClock.elapsedRealtime()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = contourBitmap ?: return

        // Match PreviewView's default FILL_CENTER centre-crop behaviour.
        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        val destination = RectF(left, top, left + drawWidth, top + drawHeight)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
        drawWalkingZones(canvas, destination)
        if (visualizationStyle.showDepthLabels) {
            drawDepthLabels(canvas, destination, bitmap.width, bitmap.height)
        }
        drawDepthLegend(canvas)
        if (obstacleAlert.isReliable) {
            drawObstacleAlert(canvas)
        } else {
            drawPerceptionStatus(canvas)
        }
        drawGuidance(canvas)
        drawPerformanceBadge(canvas)
        drawCenterDistance(canvas)
    }

    /** 左上角的稳定米制读数，直接回答"前方大约多少米"。 */
    private fun drawCenterDistance(canvas: Canvas) {
        if (!centerDistanceMeters.isFinite() || centerDistanceMeters <= 0f) return

        val text = String.format(Locale.US, "前方 ≈ %.1f 米", centerDistanceMeters)
        val horizontalPadding = dp(10f)
        val verticalPadding = dp(7f)
        val textWidth = centerDistanceTextPaint.measureText(text)
        val textHeight = centerDistanceTextPaint.fontMetrics.run { bottom - top }
        val left = dp(12f)
        val top = dp(12f)
        val background = RectF(
            left,
            top,
            left + textWidth + horizontalPadding * 2f,
            top + textHeight + verticalPadding * 2f
        )
        canvas.drawRoundRect(background, dp(6f), dp(6f), performanceBackgroundPaint)
        canvas.drawText(
            text,
            background.left + horizontalPadding,
            background.top + verticalPadding - centerDistanceTextPaint.fontMetrics.top,
            centerDistanceTextPaint
        )
    }

    private fun drawDepthLabels(canvas: Canvas, destination: RectF, sourceWidth: Int, sourceHeight: Int) {
        if (depthLabels.isEmpty()) return

        annotationTextPaint.textSize = visualizationStyle.labelTextSizeSp *
            resources.displayMetrics.density * resources.configuration.fontScale
        annotationTextPaint.alpha = visualizationStyle.labelOpacity
        val margin = dp(8f)
        val pointRadius = dp(3f)

        depthLabels.forEach { label ->
            val pointX = destination.left + label.x / sourceWidth * destination.width()
            val pointY = destination.top + label.y / sourceHeight * destination.height()
            if (pointX !in 0f..width.toFloat() || pointY !in 0f..height.toFloat()) return@forEach

            var color = contourColor(label.normalizedDepth)
            annotationLinePaint.color = color
            annotationLinePaint.alpha = visualizationStyle.labelOpacity
            annotationFillPaint.color = color
            annotationFillPaint.alpha = visualizationStyle.labelOpacity

            val text = visualizationStyle.formatLabel(label.depthMeters)
            val textWidth = annotationTextPaint.measureText(text)
            val textHeight = annotationTextPaint.fontMetrics.run { bottom - top }
            val bubbleLeft = (pointX + dp(10f)).coerceIn(margin, width - textWidth - dp(16f))
            val bubbleBottom = (pointY - dp(7f)).coerceIn(textHeight + margin, height - margin)
            val bubble = RectF(
                bubbleLeft - dp(5f),
                bubbleBottom - textHeight - dp(4f),
                bubbleLeft + textWidth + dp(5f),
                bubbleBottom + dp(4f)
            )

            annotationFillPaint.color = Color.argb((visualizationStyle.labelOpacity * 0.72f).toInt(), 0, 0, 0)
            canvas.drawRoundRect(bubble, dp(4f), dp(4f), annotationFillPaint)
            annotationLinePaint.color = color
            canvas.drawLine(pointX, pointY, bubble.left, bubbleBottom - textHeight / 2f, annotationLinePaint)
            canvas.drawCircle(pointX, pointY, pointRadius, annotationFillPaint.apply { color = color })
            canvas.drawText(text, bubbleLeft, bubbleBottom - annotationTextPaint.fontMetrics.bottom, annotationTextPaint)
        }
    }

    private fun drawDepthLegend(canvas: Canvas) {
        val margin = dp(12f)
        val legendWidth = dp(14f)
        val legendHeight = dp(112f)
        val left = margin + dp(12f)
        val top = height - margin - legendHeight
        val background = RectF(left - dp(10f), top - dp(24f), left + legendWidth + dp(28f), top + legendHeight + dp(20f))

        legendPaint.color = Color.argb(142, 0, 0, 0)
        canvas.drawRoundRect(background, dp(8f), dp(8f), legendPaint)
        legendPaint.shader = LinearGradient(
            0f, top, 0f, top + legendHeight,
            intArrayOf(
                Color.rgb(244, 48, 20), Color.rgb(255, 179, 0), Color.rgb(228, 255, 0),
                Color.rgb(0, 231, 104), Color.rgb(0, 196, 255), Color.rgb(43, 55, 255),
                Color.rgb(94, 0, 172)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(left, top, left + legendWidth, top + legendHeight), dp(3f), dp(3f), legendPaint)
        legendPaint.shader = null

        canvas.drawText("近", left + legendWidth / 2f, top - dp(8f), legendLabelPaint)
        canvas.drawText("远", left + legendWidth / 2f, top + legendHeight + dp(17f), legendLabelPaint)
        canvas.save()
        canvas.rotate(-90f, left - dp(7f), top + legendHeight / 2f)
        canvas.drawText("深度", left - dp(7f), top + legendHeight / 2f + dp(4f), legendLabelPaint)
        canvas.restore()
    }

    private fun drawWalkingZones(canvas: Canvas, destination: RectF) {
        val centers = floatArrayOf(0.37f, 0.50f, 0.63f)
        val risks = floatArrayOf(
            obstacleAlert.leftRisk,
            obstacleAlert.centerRisk,
            obstacleAlert.rightRisk
        )
        val blocked = booleanArrayOf(
            obstacleAlert.leftBlocked,
            obstacleAlert.centerBlocked,
            obstacleAlert.rightBlocked
        )
        val labels = arrayOf("左", "前", "右")
        val top = destination.top + destination.height() * 0.30f
        val pulse = if ((SystemClock.uptimeMillis() % 800L) < 400L) 225 else 155

        centers.forEachIndexed { index, centerFraction ->
            val center = destination.left + destination.width() * centerFraction
            val topHalfWidth = destination.width() * 0.045f
            val bottom = destination.top + destination.height() *
                if (index == 1) 0.90f else 0.74f
            val bottomHalfWidth = destination.width() *
                if (index == 1) 0.105f else 0.075f
            val path = android.graphics.Path().apply {
                moveTo(center - topHalfWidth, top)
                lineTo(center + topHalfWidth, top)
                lineTo(center + bottomHalfWidth, bottom)
                lineTo(center - bottomHalfWidth, bottom)
                close()
            }
            val color = when {
                !obstacleAlert.isReliable -> Color.rgb(255, 193, 7)
                blocked[index] && risks[index] >= 0.84f -> Color.rgb(255, 59, 48)
                blocked[index] -> Color.rgb(255, 149, 0)
                else -> Color.rgb(80, 220, 135)
            }
            safetyZonePaint.color = color
            safetyZonePaint.alpha = if (blocked[index]) pulse else 62
            safetyZonePaint.strokeWidth = if (blocked[index]) dp(2.2f) else dp(1f)
            canvas.drawPath(path, safetyZonePaint)
            safetyZoneLabelPaint.alpha = if (blocked[index]) 235 else 115
            canvas.drawText(labels[index], center, top + dp(18f), safetyZoneLabelPaint)
        }

        if (obstacleAlert.isDetected) postInvalidateOnAnimation()
    }

    private fun drawObstacleAlert(canvas: Canvas) {
        if (!obstacleAlert.isDetected) return

        val danger = obstacleAlert.level == ObstacleAlert.Level.DANGER
        val color = if (danger) Color.rgb(255, 59, 48) else Color.rgb(255, 149, 0)
        val position = when (obstacleAlert.hazardDirection) {
            ObstacleAlert.Direction.LEFT -> "左侧障碍"
            ObstacleAlert.Direction.CENTER -> "前方障碍"
            ObstacleAlert.Direction.RIGHT -> "右侧障碍"
            ObstacleAlert.Direction.WIDE -> "多侧障碍"
        }
        val text = if (danger) "危险 · $position" else "注意 · $position"
        val horizontalPadding = dp(16f)
        val verticalPadding = dp(9f)
        val textWidth = obstacleTextPaint.measureText(text)
        val textHeight = obstacleTextPaint.fontMetrics.run { bottom - top }
        val centerX = width / 2f
        val top = dp(54f)
        val background = RectF(
            centerX - textWidth / 2f - horizontalPadding,
            top,
            centerX + textWidth / 2f + horizontalPadding,
            top + textHeight + verticalPadding * 2f
        )
        obstacleBackgroundPaint.color = Color.argb(if (danger) 220 else 205, 18, 18, 18)
        obstacleBorderPaint.color = color
        canvas.drawRoundRect(background, dp(9f), dp(9f), obstacleBackgroundPaint)
        canvas.drawRoundRect(background, dp(9f), dp(9f), obstacleBorderPaint)
        canvas.drawText(
            text,
            centerX,
            background.top + verticalPadding - obstacleTextPaint.fontMetrics.top,
            obstacleTextPaint
        )
        postInvalidateOnAnimation()
    }

    private fun drawPerceptionStatus(canvas: Canvas) {
        val text = when (obstacleAlert.perceptionStatus) {
            ObstacleAlert.PerceptionStatus.LOW_LIGHT -> "光线不足 · 请减速并观察"
            ObstacleAlert.PerceptionStatus.DEPTH_UNAVAILABLE -> "感知暂不可用 · 请停下确认"
            ObstacleAlert.PerceptionStatus.READY -> return
        }
        drawCenteredBanner(canvas, text, dp(54f), Color.rgb(255, 193, 7), 220)
    }

    private fun drawGuidance(canvas: Canvas) {
        val text = when {
            !obstacleAlert.isReliable -> "■ 暂停前进 · 辅助感知不可靠"
            obstacleAlert.guidance == ObstacleAlert.Guidance.CHECK_LEFT ->
                "← 左侧相对较空 · 请确认"
            obstacleAlert.guidance == ObstacleAlert.Guidance.CHECK_RIGHT ->
                "右侧相对较空 · 请确认 →"
            obstacleAlert.guidance == ObstacleAlert.Guidance.STOP ->
                "■ 请停下 · 两侧均需确认"
            obstacleAlert.isDetected -> "↑ 中间相对较空 · 保持观察"
            else -> "辅助避障运行中 · 请观察周围环境"
        }
        val color = when {
            !obstacleAlert.isReliable || obstacleAlert.guidance == ObstacleAlert.Guidance.STOP ->
                Color.rgb(255, 193, 7)
            obstacleAlert.isDetected -> Color.rgb(255, 149, 0)
            else -> Color.rgb(80, 220, 135)
        }
        drawCenteredBanner(canvas, text, height - dp(70f), color, if (obstacleAlert.isDetected) 205 else 155)
    }

    private fun drawCenteredBanner(
        canvas: Canvas,
        text: String,
        top: Float,
        color: Int,
        backgroundAlpha: Int
    ) {
        val horizontalPadding = dp(15f)
        val verticalPadding = dp(8f)
        val textWidth = guidanceTextPaint.measureText(text)
        val textHeight = guidanceTextPaint.fontMetrics.run { bottom - top }
        val centerX = width / 2f
        val background = RectF(
            centerX - textWidth / 2f - horizontalPadding,
            top,
            centerX + textWidth / 2f + horizontalPadding,
            top + textHeight + verticalPadding * 2f
        )
        obstacleBackgroundPaint.color = Color.argb(backgroundAlpha, 18, 18, 18)
        obstacleBorderPaint.color = color
        canvas.drawRoundRect(background, dp(9f), dp(9f), obstacleBackgroundPaint)
        canvas.drawRoundRect(background, dp(9f), dp(9f), obstacleBorderPaint)
        canvas.drawText(
            text,
            centerX,
            background.top + verticalPadding - guidanceTextPaint.fontMetrics.top,
            guidanceTextPaint
        )
    }

    private fun drawPerformanceBadge(canvas: Canvas) {
        if (depthFps <= 0f || !depthFps.isFinite()) return

        val text = String.format(Locale.US, "YOLO26  %.1f FPS", depthFps)
        val horizontalPadding = dp(9f)
        val verticalPadding = dp(6f)
        val textWidth = performanceTextPaint.measureText(text)
        val textHeight = performanceTextPaint.fontMetrics.run { bottom - top }
        val right = width - dp(12f)
        val top = dp(12f)
        val background = RectF(
            right - textWidth - horizontalPadding * 2f,
            top,
            right,
            top + textHeight + verticalPadding * 2f
        )
        canvas.drawRoundRect(background, dp(6f), dp(6f), performanceBackgroundPaint)
        canvas.drawText(
            text,
            background.left + horizontalPadding,
            background.top + verticalPadding - performanceTextPaint.fontMetrics.top,
            performanceTextPaint
        )
    }


    private fun contourColor(depth: Float): Int {
        val stops = arrayOf(
            0f to Color.rgb(94, 0, 172), 0.18f to Color.rgb(43, 55, 255),
            0.36f to Color.rgb(0, 196, 255), 0.54f to Color.rgb(0, 231, 104),
            0.70f to Color.rgb(228, 255, 0), 0.85f to Color.rgb(255, 179, 0),
            1f to Color.rgb(244, 48, 20)
        )
        val value = depth.coerceIn(0f, 1f)
        val upper = stops.firstOrNull { value <= it.first } ?: stops.last()
        val lower = stops.lastOrNull { it.first <= value } ?: stops.first()
        if (upper.first == lower.first) return upper.second
        val amount = (value - lower.first) / (upper.first - lower.first)
        return Color.rgb(
            (Color.red(lower.second) + (Color.red(upper.second) - Color.red(lower.second)) * amount).toInt(),
            (Color.green(lower.second) + (Color.green(upper.second) - Color.green(lower.second)) * amount).toInt(),
            (Color.blue(lower.second) + (Color.blue(upper.second) - Color.blue(lower.second)) * amount).toInt()
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
