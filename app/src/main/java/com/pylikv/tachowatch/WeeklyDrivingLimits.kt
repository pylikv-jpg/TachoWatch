package com.pylikv.tachowatch

/** Remaining driving under both calendar-week limits; daily/break limits are separate. */
object WeeklyDrivingLimits {
    private const val WEEKLY_MINUTES = 56 * 60
    private const val TWO_WEEK_MINUTES = 90 * 60

    fun remainingThisWeek(previousWeekMinutes: Int, currentWeekMinutes: Int): Int {
        val previous = previousWeekMinutes.coerceAtLeast(0).toLong()
        val current = currentWeekMinutes.coerceAtLeast(0).toLong()
        val weeklyRemaining = WEEKLY_MINUTES - current
        val twoWeekRemaining = TWO_WEEK_MINUTES - previous - current
        return minOf(weeklyRemaining, twoWeekRemaining).coerceAtLeast(0L).toInt()
    }
}
