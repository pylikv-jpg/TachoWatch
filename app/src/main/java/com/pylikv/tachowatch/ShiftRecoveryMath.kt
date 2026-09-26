package com.pylikv.tachowatch

/**
 * Pure recovery math used when a fresh card download replaces the persisted live state.
 *
 * HistoryData omits the still-open card activity. Therefore the card seed can be missing
 * the current open WORK or DRIVING segment. Merge only the missing open portion so the
 * 6-hour continuous-work counter survives a card read without losing or duplicating time.
 */
object ShiftRecoveryMath {

    fun mergeContinuousWorkMinutes(
        cardContinuousWorkMinutes: Int,
        cardContinuousDrivingMinutes: Int,
        liveActivity: String,
        liveActivityMinutes: Int,
        liveContinuousDrivingMinutes: Int
    ): Int {
        val cardWork = cardContinuousWorkMinutes.coerceAtLeast(0)
        val cardDriving = cardContinuousDrivingMinutes.coerceAtLeast(0)
        val liveActivityMin = liveActivityMinutes.coerceAtLeast(0)
        val liveContinuous = liveContinuousDrivingMinutes.coerceAtLeast(0)

        val openOtherWork = if (isOtherWork(liveActivity)) liveActivityMin else 0

        // F923 represents the current continuous-driving cycle. The card seed already
        // contains closed driving periods from that same work window, so merge only the
        // positive remainder that is still open and therefore absent from HistoryData.
        val openDriving = if (isDriving(liveActivity)) {
            (liveContinuous - cardDriving).coerceAtLeast(0)
        } else {
            0
        }

        return cardWork + openOtherWork + openDriving
    }

    /**
     * After a confirmed >=9h daily-rest boundary some DTCO units can keep the
     * previous shift's absolute F923 value until the next driving cycle starts.
     * At that boundary the current activity is authoritative: OTHER WORK/REST
     * means zero new-shift driving; while DRIVING, F927 is the duration of the
     * current open driving activity and is safer than the stale absolute F923.
     */
    fun liveContinuousCheckpoint(
        confirmedDailyRestBoundary: Boolean,
        liveActivity: String,
        liveActivityMinutes: Int,
        rawContinuousDrivingMinutes: Int
    ): Int {
        val raw = rawContinuousDrivingMinutes.coerceAtLeast(0)
        if (!confirmedDailyRestBoundary) return raw
        return if (isDriving(liveActivity)) liveActivityMinutes.coerceAtLeast(0) else 0
    }

    private fun isDriving(v: String) = v.contains("ВОЖДЕНИЕ", true)

    private fun isOtherWork(v: String) =
        v.contains("РАБОТА", true) && !isDriving(v)
}
