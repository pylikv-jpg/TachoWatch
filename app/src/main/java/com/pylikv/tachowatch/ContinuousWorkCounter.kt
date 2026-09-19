package com.pylikv.tachowatch

/**
 * Independent continuous-work counter.
 * Counts only DRIVING + OTHER WORK from the common live DTCO activity source.
 * AVAILABILITY is excluded. A confirmed 45-minute break starts a new work window.
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
        if (qualifyingRestMinutes >= 45) {
            return State(0, 0, currentActivity, source(currentActivity, activityMinutes, continuousDrivingMinutes))
        }

        val now = source(currentActivity, activityMinutes, continuousDrivingMinutes)
        if (state.previousActivity == "—") {
            // After a fresh card reconciliation, state.workMinutes contains only completed
            // card periods. The current live F927/F923 segment is still open on the card and
            // therefore is NOT part of that seed. Add it; using max(seed, live) drops earlier
            // OTHER WORK whenever the live driving segment becomes the larger value.
            return when {
                isDriving(currentActivity) -> State(state.workMinutes + now, state.otherWorkMinutes, currentActivity, now)
                isOtherWork(currentActivity) -> State(state.workMinutes + now, state.otherWorkMinutes + now, currentActivity, now)
                else -> State(state.workMinutes, state.otherWorkMinutes, currentActivity, now)
            }
        }

        if (state.previousActivity != currentActivity) {
            // F927/F923 can already contain elapsed time in the newly observed activity.
            // Seed that elapsed part so a transition between polling cycles cannot lose minutes.
            return when {
                isDriving(currentActivity) -> State(state.workMinutes + now, state.otherWorkMinutes, currentActivity, now)
                isOtherWork(currentActivity) -> State(state.workMinutes + now, state.otherWorkMinutes + now, currentActivity, now)
                else -> State(state.workMinutes, state.otherWorkMinutes, currentActivity, now)
            }
        }

        val delta = (now - state.previousSourceMinutes).coerceAtLeast(0)
        return when {
            isDriving(currentActivity) -> State(state.workMinutes + delta, state.otherWorkMinutes, currentActivity, now)
            isOtherWork(currentActivity) -> State(state.workMinutes + delta, state.otherWorkMinutes + delta, currentActivity, now)
            else -> State(state.workMinutes, state.otherWorkMinutes, currentActivity, now)
        }
    }

    private fun source(activity: String, activityMinutes: Int, continuousDrivingMinutes: Int) =
        if (isDriving(activity)) continuousDrivingMinutes else activityMinutes

    private fun isDriving(v: String) = v.contains("ВОЖДЕНИЕ", true)
    private fun isOtherWork(v: String) = v.contains("РАБОТА", true) && !isDriving(v)
}
