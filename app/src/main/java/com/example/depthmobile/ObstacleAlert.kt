package com.example.depthmobile

/** A conservative, screen-aligned walking-assistance result. */
data class ObstacleAlert(
    val level: Level,
    /** 0..1 relative nearness score. It must not be presented as an absolute distance. */
    val proximity: Float = 0f,
    val confidence: Float = 0f,
    val hazardDirection: Direction = Direction.CENTER,
    val guidance: Guidance = Guidance.STRAIGHT,
    val leftRisk: Float = 0f,
    val centerRisk: Float = 0f,
    val rightRisk: Float = 0f,
    val leftBlocked: Boolean = false,
    val centerBlocked: Boolean = false,
    val rightBlocked: Boolean = false,
    val perceptionStatus: PerceptionStatus = PerceptionStatus.READY
) {
    enum class Level {
        CLEAR,
        CAUTION,
        DANGER
    }

    enum class Direction {
        LEFT,
        CENTER,
        RIGHT,
        WIDE
    }

    /** Suggestions are intentionally advisory: the camera cannot verify a complete route. */
    enum class Guidance {
        STRAIGHT,
        CHECK_LEFT,
        CHECK_RIGHT,
        STOP
    }

    enum class PerceptionStatus {
        READY,
        LOW_LIGHT,
        DEPTH_UNAVAILABLE
    }

    val isDetected: Boolean
        get() = level != Level.CLEAR

    val isReliable: Boolean
        get() = perceptionStatus == PerceptionStatus.READY

    companion object {
        val CLEAR = ObstacleAlert(Level.CLEAR)

        fun unavailable() = ObstacleAlert(
            level = Level.CLEAR,
            guidance = Guidance.STOP,
            perceptionStatus = PerceptionStatus.DEPTH_UNAVAILABLE
        )
    }
}
