package com.pylikv.tachowatch

/**
 * Independent shift-driving counter.
 * Input is only the DTCO continuous-driving value (F923) plus the daily-rest boundary.
 * It does not read or modify continuous-work state.
 */
object ShiftDrivingCounter {
    private const val RESET_ARMED_BY_BREAK = -2
    data class State(
        val initialized: Boolean,
        val totalMinutes: Int,
        val previousContinuousMinutes: Int,
        val resetCandidateMinutes: Int = -1
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
            previousContinuousMinutes = live,
            resetCandidateMinutes = -1
        )
    }

    fun update(
        initialized: Boolean,
        totalMinutes: Int,
        previousContinuousMinutes: Int,
        currentContinuousMinutes: Int,
        dailyRestCompleted: Boolean,
        resetCandidateMinutes: Int = -1,
        qualifyingRestMinutes: Int = 0
    ): State {
        val current = currentContinuousMinutes.coerceAtLeast(0)
        val previous = previousContinuousMinutes.coerceAtLeast(0)

        if (dailyRestCompleted) {
            return State(true, 0, 0, -1)
        }
        if (!initialized) {
            return State(true, totalMinutes, current, -1)
        }

        // A confirmed 45-minute break is the authoritative F923 cycle boundary.
        // Keep the old F923 checkpoint only as a guard while the tachograph may still
        // expose that stale value; accept the first real lower value as the new cycle.
        if (qualifyingRestMinutes >= 45) {
            return State(true, totalMinutes, previous, RESET_ARMED_BY_BREAK)
        }

        if (resetCandidateMinutes == RESET_ARMED_BY_BREAK) {
            return when {
                previous == 0 -> State(true, totalMinutes + current, current, -1)
                current < previous -> State(true, totalMinutes + current, current, -1)
                else -> State(true, totalMinutes, previous, RESET_ARMED_BY_BREAK)
            }
        }

        if (current >= previous) {
            val delta = current - previous
            return State(true, totalMinutes + delta, current, -1)
        }

        // Without an observed qualifying break, a sudden F923 decrease can be a
        // one-cycle DTCO/reconnect glitch. Keep the old checkpoint and require the
        // small new value to grow by at least two minutes before accepting it as a
        // genuine new continuous-driving cycle. This prevents 200 -> 0 -> 200 from
        // becoming 400 while still recovering correctly after a missed break.
        if (current > 30) {
            return State(true, totalMinutes, previous, -1)
        }

        val candidate = resetCandidateMinutes
        if (candidate < 0 || current < candidate) {
            return State(true, totalMinutes, previous, current)
        }

        if (current - candidate >= 2) {
            return State(true, totalMinutes + current, current, -1)
        }

        return State(true, totalMinutes, previous, candidate)
    }
}
