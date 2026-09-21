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
        val previousSourceMinutes: Int,
        val previousContinuousDrivingMinutes: Int = 0
    )

    fun update(
        state: State,
        currentActivity: String,
        activityMinutes: Int,
        continuousDrivingMinutes: Int,
        qualifyingRestMinutes: Int,
        currentShiftDrivingMinutes: Int? = null
    ): State {
        val now = activityMinutes.coerceAtLeast(0)
        val currentContinuous = continuousDrivingMinutes.coerceAtLeast(0)

        if (qualifyingRestMinutes >= 45) {
            // Anchor F923 at the value seen at the qualifying break. Some DTCO units keep
            // the previous F923 value until driving starts again; anchoring prevents that
            // stale value from being re-added to the new 6-hour work window.
            return State(0, 0, currentActivity, now, currentContinuous)
        }

        // Count DRIVING only while the tachograph reports DRIVING, and only as a delta of
        // F923. Never copy the absolute F923 value into the work counter: after a daily or
        // 45-minute rest it may still contain the previous driving cycle until motion starts.
        val drivingDelta = when {
            state.previousActivity == "—" && isDriving(currentActivity) -> {
                // First reconciliation after a card read/restart. On supported DTCOs the
                // current-shift timer caps the open F923 segment and rejects a stale value
                // carried from the previous shift.
                val cap = currentShiftDrivingMinutes?.coerceAtLeast(0)
                if (cap != null) minOf(currentContinuous, cap) else currentContinuous
            }

            // F903 can switch away from DRIVING one live cycle before the last completed
            // minute appears in F923. Count that final positive F923 delta as driving.
            (isDriving(currentActivity) || isDriving(state.previousActivity)) &&
                currentContinuous >= state.previousContinuousDrivingMinutes ->
                currentContinuous - state.previousContinuousDrivingMinutes

            isDriving(currentActivity) ->
                // F923 really reset to a new continuous-driving cycle.
                currentContinuous

            else -> 0
        }.coerceAtLeast(0)

        // F927 belongs to the currently selected activity. Count it only for OTHER WORK.
        // On entry/reconciliation add the open segment, then only positive deltas.
        val otherWorkDelta = when {
            state.previousActivity == "—" && isOtherWork(currentActivity) -> now

            state.previousActivity == currentActivity && isOtherWork(currentActivity) ->
                (now - state.previousSourceMinutes).coerceAtLeast(0)

            state.previousActivity != currentActivity && isOtherWork(currentActivity) -> now

            else -> 0
        }

        val nextOtherWorkMinutes = state.otherWorkMinutes + otherWorkDelta

        // Keep the driving part of the 6h work window self-consistent with F923.
        // Delta accumulation can lose the first minute after a service/card-read handoff
        // when the persisted F923 checkpoint is already one minute ahead of workMinutes.
        //
        // Do NOT blindly copy absolute F923: immediately after a qualifying break some
        // tachographs can briefly expose the previous driving-cycle value. Prefer the
        // direct shift-driving timer as a plausibility check; without it, only repair
        // clearly new/small cycles or a detected F923 reset.
        val accumulatedDrivingMinutes =
            (state.workMinutes - state.otherWorkMinutes).coerceAtLeast(0) + drivingDelta

        val continuousDrivingFloor = run {
            val shiftDriving = currentShiftDrivingMinutes?.coerceAtLeast(0)
            when {
                isDriving(currentActivity) && shiftDriving != null ->
                    if (currentContinuous <= shiftDriving + 1) currentContinuous else 0

                isDriving(currentActivity) && state.previousActivity == "—" ->
                    currentContinuous

                isDriving(currentActivity) &&
                    currentContinuous < state.previousContinuousDrivingMinutes ->
                    currentContinuous

                isDriving(currentActivity) &&
                    state.previousContinuousDrivingMinutes <= 5 &&
                    currentContinuous > state.previousContinuousDrivingMinutes &&
                    currentContinuous <= 15 ->
                    currentContinuous

                // Repair an already-missed final driving minute after the activity has
                // changed away from DRIVING. Require an existing driving contribution and
                // an exact one-minute mismatch so stale pre-break F923 cannot repopulate a
                // freshly reset work window.
                accumulatedDrivingMinutes > 0 &&
                    currentContinuous == state.previousContinuousDrivingMinutes &&
                    currentContinuous == accumulatedDrivingMinutes + 1 ->
                    currentContinuous

                else -> 0
            }
        }

        val reconciledDrivingMinutes =
            maxOf(accumulatedDrivingMinutes, continuousDrivingFloor)

        return State(
            workMinutes = reconciledDrivingMinutes + nextOtherWorkMinutes,
            otherWorkMinutes = nextOtherWorkMinutes,
            previousActivity = currentActivity,
            previousSourceMinutes = now,
            previousContinuousDrivingMinutes = currentContinuous
        )
    }

    private fun isDriving(v: String) = v.contains("ВОЖДЕНИЕ", true)

    private fun isOtherWork(v: String) =
        v.contains("РАБОТА", true) && !isDriving(v)
}
