package com.pylikv.tachowatch

/**
 * Independent continuous-work counter.
 * Counts DRIVING from authoritative F923 and OTHER WORK from F927.
 * AVAILABILITY is excluded. A confirmed 45-minute break starts a new work window.
 *
 * Important: F927 must never be used as a driving delta. On some activity transitions
 * it can momentarily contain a value unrelated to the new driving segment; adding it
 * there caused large false jumps when returning from OTHER WORK to DRIVING.
 */
object ContinuousWorkCounter {
    data class State(
        val workMinutes: Int,
        val otherWorkMinutes: Int,
        val previousActivity: String,
        val previousSourceMinutes: Int
    )

    fun update(
        state: State,
        currentActivity: String,
        activityMinutes: Int,
        continuousDrivingMinutes: Int,
        qualifyingRestMinutes: Int
    ): State {
        val now = activityMinutes.coerceAtLeast(0)

        if (qualifyingRestMinutes >= 45) {
            return State(0, 0, currentActivity, now)
        }

        // Driving is reconstructed only from F923. Preserve the already accepted driving
        // contribution if a transient live sample is smaller; the contribution may decrease
        // only through the explicit qualifying-break reset above.
        val previousDrivingMinutes =
            (state.workMinutes - state.otherWorkMinutes).coerceAtLeast(0)
        val drivingMinutes = maxOf(
            previousDrivingMinutes,
            continuousDrivingMinutes.coerceAtLeast(0)
        )

        // F927 belongs only to the current selected activity. For OTHER WORK it is safe to
        // add the open segment on first reconciliation / entry, then only positive deltas.
        // For DRIVING we deliberately ignore F927 completely.
        val nextOtherWorkMinutes = when {
            state.previousActivity == "—" && isOtherWork(currentActivity) ->
                state.otherWorkMinutes + now

            state.previousActivity == currentActivity && isOtherWork(currentActivity) ->
                state.otherWorkMinutes +
                    (now - state.previousSourceMinutes).coerceAtLeast(0)

            state.previousActivity != currentActivity && isOtherWork(currentActivity) ->
                state.otherWorkMinutes + now

            else -> state.otherWorkMinutes
        }

        return State(
            workMinutes = drivingMinutes + nextOtherWorkMinutes,
            otherWorkMinutes = nextOtherWorkMinutes,
            previousActivity = currentActivity,
            previousSourceMinutes = now
        )
    }

    private fun isOtherWork(v: String) =
        v.contains("РАБОТА", true) && !v.contains("ВОЖДЕНИЕ", true)
}
