package com.example.depthmobile

import android.util.Log
import java.util.Arrays

/**
 * Hallway-aware relative-depth obstacle detector. It uses narrow perspective corridors and a
 * same-height foreground comparison, so side walls and the floor do not dominate the decision.
 */
class ObstacleDetector(
    private val width: Int,
    private val height: Int
) {
    companion object {
        private const val SAMPLE_STEP = 3
        private const val FRAME_SAMPLE_STEP = 4
        private const val ZONE_TOP = 0.30f
        private const val ZONE_BOTTOM = 0.74f
        private const val TOP_HALF_WIDTH = 0.045f
        private const val BOTTOM_HALF_WIDTH = 0.075f
        private const val ENTER_NEARNESS = 0.67f
        private const val EXIT_NEARNESS = 0.56f
        private const val DANGER_NEARNESS = 0.86f
        private const val MIN_NEAR_FRACTION = 0.11f
        private const val NEAR_PERCENTILE = 0.82f
        private const val LOW_PERCENTILE = 0.02f
        private const val HIGH_PERCENTILE = 0.98f
        private const val CONFIRM_FRAMES = 2
        private const val RELEASE_FRAMES = 3
        private const val SMOOTHING = 0.42f
        private const val LOW_OBSTACLE_TOP = 0.34f
        private const val LOW_OBSTACLE_BOTTOM = 0.90f
        private const val LOW_HALF_WIDTH_TOP = 0.055f
        private const val LOW_HALF_WIDTH_BOTTOM = 0.110f
        private const val FOREGROUND_NEARNESS_GAP = 0.10f
        private const val MIN_FOREGROUND_FRACTION = 0.045f
        private const val WALL_TOP = 0.30f
        private const val WALL_BOTTOM = 0.86f
        private const val WALL_LEFT = 0.25f
        private const val WALL_RIGHT = 0.75f
        private const val WALL_MAX_DISTANCE_METERS = 2.40f
        private const val WALL_DANGER_DISTANCE_METERS = 1.25f
        private const val WALL_MIN_DISTANCE_METERS = 0.35f
        private const val WALL_MAX_RELATIVE_SPREAD = 0.30f
        private const val WALL_CONFIRM_FRAMES = 4
        private const val MIN_VALID_FRAME_SAMPLES = 256
    }

    private data class ZoneState(
        var blocked: Boolean = false,
        var confirmCount: Int = 0,
        var releaseCount: Int = 0,
        var nearness: Float = 0f,
        var occupiedFraction: Float = 0f
    )

    private val zoneCenters = floatArrayOf(0.37f, 0.50f, 0.63f)
    private val zoneStates = Array(3) { ZoneState() }
    private val zoneBuffer = FloatArray(width * height / 4)
    private val frameBuffer = FloatArray(
        (width / FRAME_SAMPLE_STEP + 2) * (height / FRAME_SAMPLE_STEP + 2)
    )
    private val referenceOffsets = floatArrayOf(0.18f, 0.27f)
    private var lowObstacleBlocked = false
    private var lowConfirmCount = 0
    private var lowReleaseCount = 0
    private var lowObstacleRisk = 0f
    private var lowObstacleFraction = 0f
    private var frontalWallBlocked = false
    private var wallConfirmCount = 0
    private var wallReleaseCount = 0
    private var frontalWallRisk = 0f
    private var frontalWallDistanceMeters = Float.NaN
    private var frontalWallRelativeSpread = Float.POSITIVE_INFINITY
    private var frameCount = 0

    fun analyze(
        depth: FloatArray,
        perceptionStatus: ObstacleAlert.PerceptionStatus = ObstacleAlert.PerceptionStatus.READY
    ): ObstacleAlert {
        if (depth.size != width * height) return ObstacleAlert.unavailable()

        val range = robustRange(depth) ?: return ObstacleAlert.unavailable()
        for (zoneIndex in zoneCenters.indices) {
            analyzeZone(depth, range.first, range.second, zoneCenters[zoneIndex], zoneStates[zoneIndex])
        }
        analyzeLowCentralObstacle(depth, range.first, range.second)
        analyzeFrontalWall(depth)

        val left = zoneStates[0]
        val center = zoneStates[1]
        val right = zoneStates[2]
        // Absolute per-frame nearness always contains a "nearest" surface in an empty corridor.
        // It is therefore diagnostic only. A user-facing alert requires either a foreground
        // protrusion or the flat vertical profile of a frontal wall.
        val centralPathBlocked = lowObstacleBlocked || frontalWallBlocked
        val centerRisk = maxOf(lowObstacleRisk, frontalWallRisk)
        val level = when {
            !centralPathBlocked -> ObstacleAlert.Level.CLEAR
            centerRisk >= DANGER_NEARNESS -> ObstacleAlert.Level.DANGER
            else -> ObstacleAlert.Level.CAUTION
        }
        val guidance = guidance(left, centralPathBlocked, right)

        frameCount++
        if (frameCount == 1 || frameCount % 20 == 0) {
            Log.d(
                "OBSTACLE_DEPTH",
                (
                    "raw=%.3f..%.3f near=%.2f/%.2f/%.2f path=%s low=%s(%.2f) " +
                        "wall=%s(%.2fm spread=%.2f) guidance=%s"
                    ).format(
                    range.first,
                    range.second,
                    left.nearness,
                    center.nearness,
                    right.nearness,
                    centralPathBlocked,
                    lowObstacleBlocked,
                    lowObstacleFraction,
                    frontalWallBlocked,
                    frontalWallDistanceMeters,
                    frontalWallRelativeSpread,
                    guidance
                )
            )
        }

        return ObstacleAlert(
            level = level,
            proximity = centerRisk,
            confidence = maxOf(center.occupiedFraction, lowObstacleFraction).coerceIn(0f, 1f),
            hazardDirection = if (centralPathBlocked && left.blocked && right.blocked) {
                ObstacleAlert.Direction.WIDE
            } else {
                ObstacleAlert.Direction.CENTER
            },
            guidance = if (perceptionStatus == ObstacleAlert.PerceptionStatus.READY) {
                guidance
            } else {
                ObstacleAlert.Guidance.STOP
            },
            leftRisk = if (centralPathBlocked) left.nearness else 0f,
            centerRisk = centerRisk,
            rightRisk = if (centralPathBlocked) right.nearness else 0f,
            leftBlocked = centralPathBlocked && left.blocked,
            centerBlocked = centralPathBlocked,
            rightBlocked = centralPathBlocked && right.blocked,
            perceptionStatus = perceptionStatus
        )
    }

    private fun robustRange(depth: FloatArray): Pair<Float, Float>? {
        var count = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val value = depth[y * width + x]
                if (value.isFinite()) frameBuffer[count++] = value
                x += FRAME_SAMPLE_STEP
            }
            y += FRAME_SAMPLE_STEP
        }
        if (count < MIN_VALID_FRAME_SAMPLES) return null
        Arrays.sort(frameBuffer, 0, count)
        val low = percentile(frameBuffer, count, LOW_PERCENTILE)
        val high = percentile(frameBuffer, count, HIGH_PERCENTILE)
        return if (low.isFinite() && high.isFinite() && high - low >= 1e-5f) {
            low to high
        } else {
            null
        }
    }

    private fun analyzeZone(
        depth: FloatArray,
        low: Float,
        high: Float,
        centerX: Float,
        state: ZoneState
    ) {
        val top = (height * ZONE_TOP).toInt()
        val bottom = (height * ZONE_BOTTOM).toInt()
        val threshold = if (state.blocked) EXIT_NEARNESS else ENTER_NEARNESS
        var sampleCount = 0
        var nearCount = 0

        var y = top
        while (y < bottom) {
            val progress = (y - top).toFloat() / (bottom - top).coerceAtLeast(1)
            val halfWidth = TOP_HALF_WIDTH + (BOTTOM_HALF_WIDTH - TOP_HALF_WIDTH) * progress
            val left = (width * (centerX - halfWidth)).toInt().coerceAtLeast(0)
            val right = (width * (centerX + halfWidth)).toInt().coerceAtMost(width)
            var x = left
            while (x < right) {
                val value = depth[y * width + x]
                if (value.isFinite()) {
                    val nearness = nearness(value, low, high)
                    zoneBuffer[sampleCount++] = nearness
                    if (nearness >= threshold) nearCount++
                }
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }

        if (sampleCount < 64) {
            updateZoneState(state, false, 0f, 0f)
            return
        }
        Arrays.sort(zoneBuffer, 0, sampleCount)
        val nearValue = percentile(zoneBuffer, sampleCount, NEAR_PERCENTILE)
        val nearFraction = nearCount.toFloat() / sampleCount
        val candidate = nearValue >= threshold && nearFraction >= MIN_NEAR_FRACTION
        updateZoneState(state, candidate, nearValue, nearFraction)
    }

    private fun updateZoneState(
        state: ZoneState,
        candidate: Boolean,
        nearness: Float,
        occupiedFraction: Float
    ) {
        if (candidate) {
            state.confirmCount++
            state.releaseCount = 0
            if (state.confirmCount >= CONFIRM_FRAMES) state.blocked = true
        } else {
            state.releaseCount++
            state.confirmCount = 0
            if (state.releaseCount >= RELEASE_FRAMES) state.blocked = false
        }
        state.nearness += (nearness - state.nearness) * SMOOTHING
        state.occupiedFraction = occupiedFraction
    }

    private fun analyzeLowCentralObstacle(depth: FloatArray, low: Float, high: Float) {
        val top = (height * LOW_OBSTACLE_TOP).toInt()
        val bottom = (height * LOW_OBSTACLE_BOTTOM).toInt()
        var validCount = 0
        var foregroundCount = 0
        var strengthSum = 0f

        var y = top
        while (y < bottom) {
            val progress = (y - top).toFloat() / (bottom - top).coerceAtLeast(1)
            val halfWidth = LOW_HALF_WIDTH_TOP +
                (LOW_HALF_WIDTH_BOTTOM - LOW_HALF_WIDTH_TOP) * progress
            val centerLeft = (width * (0.5f - halfWidth)).toInt()
            val centerRight = (width * (0.5f + halfWidth)).toInt()
            var x = centerLeft
            while (x < centerRight) {
                val centerDepth = depth[y * width + x]
                if (centerDepth.isFinite()) {
                    val centerNear = nearness(centerDepth, low, high)
                    var referenceNearSum = 0f
                    var referenceCount = 0
                    for (offsetFraction in referenceOffsets) {
                        val offset = (width * offsetFraction).toInt()
                        val leftDepth = depth[y * width + (x - offset).coerceAtLeast(0)]
                        val rightDepth = depth[y * width + (x + offset).coerceAtMost(width - 1)]
                        if (leftDepth.isFinite()) {
                            referenceNearSum += nearness(leftDepth, low, high)
                            referenceCount++
                        }
                        if (rightDepth.isFinite()) {
                            referenceNearSum += nearness(rightDepth, low, high)
                            referenceCount++
                        }
                    }
                    if (referenceCount == 0) {
                        x += SAMPLE_STEP
                        continue
                    }
                    // A single reflective/far side pixel previously became the reference for the
                    // whole comparison and made an empty tiled corridor look like a protrusion.
                    // Averaging the symmetric samples keeps a real central obstacle prominent while
                    // rejecting isolated glass, grout-line, and depth-edge errors.
                    val referenceNear = referenceNearSum / referenceCount
                    val gap = centerNear - referenceNear
                    validCount++
                    if (gap >= FOREGROUND_NEARNESS_GAP) {
                        foregroundCount++
                        strengthSum += gap.coerceIn(0f, 1f)
                    }
                }
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }

        val fraction = if (validCount > 0) foregroundCount.toFloat() / validCount else 0f
        val strength = if (foregroundCount > 0) strengthSum / foregroundCount else 0f
        val candidate = validCount >= 96 && fraction >= MIN_FOREGROUND_FRACTION
        if (candidate) {
            lowConfirmCount++
            lowReleaseCount = 0
            if (lowConfirmCount >= CONFIRM_FRAMES) lowObstacleBlocked = true
        } else {
            lowReleaseCount++
            lowConfirmCount = 0
            if (lowReleaseCount >= RELEASE_FRAMES) lowObstacleBlocked = false
        }
        lowObstacleFraction = fraction
        val risk = if (candidate || lowObstacleBlocked) {
            (fraction / 0.18f * 0.70f + strength * 0.30f).coerceIn(0f, 1f)
        } else {
            0f
        }
        lowObstacleRisk += (risk - lowObstacleRisk) * SMOOTHING
    }

    /**
     * Detects only a nearby surface that fills a broad part of the walking view. The old detector
     * inspected a very narrow centre strip and normalised every frame independently; consequently a
     * small, distant end wall was always the "nearest flat surface" and caused a permanent alarm.
     *
     * YOLO26-depth emits metres. Using the raw robust median supplies the missing near/far gate,
     * while the broad-region spread rejects corridors that contain a distant wall, floor and side
     * openings at different depths. Low central objects remain handled by
     * [analyzeLowCentralObstacle], independently of this wall test.
     */
    private fun analyzeFrontalWall(depth: FloatArray) {
        val top = (height * WALL_TOP).toInt()
        val bottom = (height * WALL_BOTTOM).toInt()
        val left = (width * WALL_LEFT).toInt()
        val right = (width * WALL_RIGHT).toInt()
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val value = depth[y * width + x]
                if (value.isFinite() && value >= WALL_MIN_DISTANCE_METERS) {
                    zoneBuffer[count++] = value
                }
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }

        var candidate = false
        var risk = 0f
        frontalWallDistanceMeters = Float.NaN
        frontalWallRelativeSpread = Float.POSITIVE_INFINITY
        if (count >= 512) {
            Arrays.sort(zoneBuffer, 0, count)
            val lower = percentile(zoneBuffer, count, 0.10f)
            val median = percentile(zoneBuffer, count, 0.50f)
            val upper = percentile(zoneBuffer, count, 0.90f)
            val relativeSpread = (upper - lower) / median.coerceAtLeast(0.10f)
            frontalWallDistanceMeters = median
            frontalWallRelativeSpread = relativeSpread
            candidate = median <= WALL_MAX_DISTANCE_METERS &&
                relativeSpread <= WALL_MAX_RELATIVE_SPREAD
            if (candidate || frontalWallBlocked) {
                val distanceRisk = (
                    (WALL_MAX_DISTANCE_METERS - median) /
                        (WALL_MAX_DISTANCE_METERS - WALL_DANGER_DISTANCE_METERS)
                    ).coerceIn(0f, 1f)
                val flatness = (
                    1f - relativeSpread / WALL_MAX_RELATIVE_SPREAD
                    ).coerceIn(0f, 1f)
                risk = (0.58f + distanceRisk * 0.34f + flatness * 0.08f).coerceIn(0f, 1f)
            }
        }

        if (candidate) {
            wallConfirmCount++
            wallReleaseCount = 0
            if (wallConfirmCount >= WALL_CONFIRM_FRAMES) frontalWallBlocked = true
        } else {
            wallReleaseCount++
            wallConfirmCount = 0
            if (wallReleaseCount >= RELEASE_FRAMES) frontalWallBlocked = false
        }
        frontalWallRisk += (risk - frontalWallRisk) * SMOOTHING
    }

    private fun guidance(
        left: ZoneState,
        centralPathBlocked: Boolean,
        right: ZoneState
    ): ObstacleAlert.Guidance {
        if (!centralPathBlocked) return ObstacleAlert.Guidance.STRAIGHT
        val leftAvailable = !left.blocked && left.nearness < EXIT_NEARNESS
        val rightAvailable = !right.blocked && right.nearness < EXIT_NEARNESS
        return when {
            leftAvailable && rightAvailable && left.nearness <= right.nearness ->
                ObstacleAlert.Guidance.CHECK_LEFT
            leftAvailable && rightAvailable -> ObstacleAlert.Guidance.CHECK_RIGHT
            leftAvailable -> ObstacleAlert.Guidance.CHECK_LEFT
            rightAvailable -> ObstacleAlert.Guidance.CHECK_RIGHT
            else -> ObstacleAlert.Guidance.STOP
        }
    }

    private fun nearness(value: Float, low: Float, high: Float): Float =
        (1f - (value - low) / (high - low)).coerceIn(0f, 1f)

    private fun percentile(values: FloatArray, count: Int, ratio: Float): Float {
        val position = ratio.coerceIn(0f, 1f) * (count - 1)
        val left = position.toInt()
        val right = (left + 1).coerceAtMost(count - 1)
        val fraction = position - left
        return values[left] + (values[right] - values[left]) * fraction
    }
}
