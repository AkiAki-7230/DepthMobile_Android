package com.example.depthmobile

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/** Emits rate-limited directional tones and danger haptics for walking assistance. */
class ObstacleAlerter(context: Context) {
    companion object {
        private const val CAUTION_INTERVAL_MS = 760L
        private const val DANGER_INTERVAL_MS = 330L
        private const val STATUS_INTERVAL_MS = 5_000L
        private const val CAUTION_TONE_MS = 90
        private const val DANGER_TONE_MS = 130
        private const val DANGER_VIBRATION_MS = 85L
    }

    private val toneGenerator = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    }.onFailure {
        Log.w("OBSTACLE_ALERT", "Unable to initialise ToneGenerator", it)
    }.getOrNull()
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private var lastToneAtMs = 0L
    private var lastStatusToneAtMs = 0L
    private var previousLevel = ObstacleAlert.Level.CLEAR
    private var previousGuidance = ObstacleAlert.Guidance.STRAIGHT
    private var previousStatus = ObstacleAlert.PerceptionStatus.READY
    private var released = false

    /** One audible chirp on launch makes muted media volume immediately diagnosable. */
    @Synchronized
    fun playStartupCheck() {
        if (released || toneGenerator == null) return
        val started = toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
        Log.d("OBSTACLE_ALERT", "startup sound check started=$started")
    }

    @Synchronized
    fun update(alert: ObstacleAlert) {
        if (released) return
        val now = SystemClock.elapsedRealtime()

        if (alert.perceptionStatus != ObstacleAlert.PerceptionStatus.READY) {
            val statusChanged = alert.perceptionStatus != previousStatus
            if (statusChanged || now - lastStatusToneAtMs >= STATUS_INTERVAL_MS) {
                toneGenerator?.startTone(ToneGenerator.TONE_SUP_ERROR, 260)
                lastStatusToneAtMs = now
                vibrate(longArrayOf(0L, 100L, 80L, 100L))
            }
        }
        previousStatus = alert.perceptionStatus
        if (!alert.isReliable) {
            previousLevel = ObstacleAlert.Level.CLEAR
            previousGuidance = ObstacleAlert.Guidance.STOP
            return
        }

        if (!alert.isDetected) {
            previousLevel = ObstacleAlert.Level.CLEAR
            previousGuidance = alert.guidance
            return
        }

        val interval = if (alert.level == ObstacleAlert.Level.DANGER) {
            DANGER_INTERVAL_MS
        } else {
            CAUTION_INTERVAL_MS
        }
        val enteredOrEscalated = previousLevel == ObstacleAlert.Level.CLEAR ||
            alert.level.ordinal > previousLevel.ordinal
        val directionChanged = alert.guidance != previousGuidance
        if (enteredOrEscalated || directionChanged || now - lastToneAtMs >= interval) {
            val duration = if (alert.level == ObstacleAlert.Level.DANGER) {
                DANGER_TONE_MS
            } else {
                CAUTION_TONE_MS
            }
            val tone = when (alert.guidance) {
                ObstacleAlert.Guidance.CHECK_LEFT -> ToneGenerator.TONE_DTMF_1
                ObstacleAlert.Guidance.CHECK_RIGHT -> ToneGenerator.TONE_DTMF_3
                ObstacleAlert.Guidance.STOP -> ToneGenerator.TONE_PROP_BEEP2
                ObstacleAlert.Guidance.STRAIGHT -> ToneGenerator.TONE_PROP_BEEP
            }
            val started = toneGenerator?.startTone(tone, duration) == true
            Log.d(
                "OBSTACLE_ALERT",
                "beep level=${alert.level} guidance=${alert.guidance} started=$started"
            )
            if (started) lastToneAtMs = now
            if (alert.level == ObstacleAlert.Level.DANGER) {
                vibrate(longArrayOf(0L, DANGER_VIBRATION_MS))
            }
        }
        previousLevel = alert.level
        previousGuidance = alert.guidance
    }

    private fun vibrate(pattern: LongArray) {
        val activeVibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            if (pattern.size == 2) {
                activeVibrator.vibrate(
                    VibrationEffect.createOneShot(pattern[1], VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                activeVibrator.vibrate(
                    VibrationEffect.createWaveform(pattern, -1)
                )
            }
        }.onFailure {
            Log.w("OBSTACLE_ALERT", "Unable to vibrate", it)
        }
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        toneGenerator?.release()
        vibrator?.cancel()
    }
}
