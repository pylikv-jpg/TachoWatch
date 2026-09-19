package com.pylikv.tachowatch

/**
 * Independent shift-driving counter.
 * Input is only the DTCO continuous-driving value (F923) plus the daily-rest boundary.
 * It does not read or modify continuous-work state.
 */
object ShiftDrivingCounter {
    data class State(
        val initialized: Boolean,
        val totalMinutes: Int,
        val previousContinuousMinutes: Int
    )

    fun reconcileCheckpoint(
        cardShiftDrivingMinutes: Int,
        cardContinuousDrivingMinutes: Int,
        liveContinuousDrivingMinutes: Int,
        dailyRestCompleted: Boolean
    ): State {
        if (dailyRestCompleted) return State(true, 0, 0)

        val live = liveContinuousDrivingMinutes.coerceAtLeast(0)
        val cardContinuous = cardContinuousDrivingMinutes.coerceAtLeast(0)
        val openDrivingMissingFromCard = (live - cardContinuous).coerceAtLeast(0)

        return State(
            initialized = true,
            totalMinutes = cardShiftDrivingMinutes.coerceAtLeast(0) + openDrivingMissingFromCard,
            previousContinuousMinutes = live
        )
    }

    fun update(
        initialized: Boolean,
        totalMinutes: Int,
        previousContinuousMinutes: Int,
        currentContinuousMinutes: Int,
        dailyRestCompleted: Boolean
    ): State {
        if (dailyRestCompleted) {
            return State(true, 0, 0)
        }
        if (!initialized) {
            return State(true, totalMinutes, currentContinuousMinutes)
        }

        val delta = if (currentContinuousMinutes >= previousContinuousMinutes) {
            currentContinuousMinutes - previousContinuousMinutes
        } else {
            // F923 starts a new continuous-driving cycle after a qualifying break.
            // The old cycle was already integrated while it was growing.
            currentContinuousMinutes
        }
        return State(true, totalMinutes + delta.coerceAtLeast(0), currentContinuousMinutes)
    }
}
