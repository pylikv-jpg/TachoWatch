package com.pylikv.tachowatch

object ShiftPeriodAlertMath {
    data class Remaining(
        val toThirteenHours: Int?,
        val toFifteenHours: Int?
    )

    /**
     * F99C gives calendar minutes left until the driver must start a new daily rest.
     * F9A5 gives the maximum allowed daily period in hours.
     *
     * Derive elapsed daily-period time from those two direct DTCO values so the
     * warning remains independent from card-history day boundaries.
     */
    fun remaining(
        maximumDailyPeriodMinutes: Int?,
        timeLeftUntilDailyRestMinutes: Int?
    ): Remaining {
        val maximum = maximumDailyPeriodMinutes
            ?.takeIf { it in 13 * 60..15 * 60 }
            ?: return Remaining(null, null)
        val left = timeLeftUntilDailyRestMinutes
            ?.takeIf { it in 0..maximum }
            ?: return Remaining(null, null)

        val elapsed = maximum - left
        val to13 = 13 * 60 - elapsed
        val to15 = if (maximum >= 15 * 60) 15 * 60 - elapsed else null
        return Remaining(to13, to15)
    }

    fun shouldWarn(remainingMinutes: Int?): Boolean =
        remainingMinutes != null && remainingMinutes in 1..30
}
