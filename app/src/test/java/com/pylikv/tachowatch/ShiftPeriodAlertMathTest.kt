package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftPeriodAlertMathTest {

    @Test
    fun fifteenHourPeriodWarnsAtBothThirteenAndFifteenHourMilestones() {
        val near13 = ShiftPeriodAlertMath.remaining(
            maximumDailyPeriodMinutes = 15 * 60,
            timeLeftUntilDailyRestMinutes = 2 * 60 + 30
        )
        assertEquals(30, near13.toThirteenHours)
        assertEquals(150, near13.toFifteenHours)
        assertTrue(ShiftPeriodAlertMath.shouldWarn(near13.toThirteenHours))
        assertFalse(ShiftPeriodAlertMath.shouldWarn(near13.toFifteenHours))

        val near15 = ShiftPeriodAlertMath.remaining(
            maximumDailyPeriodMinutes = 15 * 60,
            timeLeftUntilDailyRestMinutes = 30
        )
        assertEquals(-90, near15.toThirteenHours)
        assertEquals(30, near15.toFifteenHours)
        assertFalse(ShiftPeriodAlertMath.shouldWarn(near15.toThirteenHours))
        assertTrue(ShiftPeriodAlertMath.shouldWarn(near15.toFifteenHours))
    }

    @Test
    fun thirteenHourPeriodOnlyHasThirteenHourWarning() {
        val remaining = ShiftPeriodAlertMath.remaining(
            maximumDailyPeriodMinutes = 13 * 60,
            timeLeftUntilDailyRestMinutes = 30
        )

        assertEquals(30, remaining.toThirteenHours)
        assertNull(remaining.toFifteenHours)
        assertTrue(ShiftPeriodAlertMath.shouldWarn(remaining.toThirteenHours))
    }

    @Test
    fun invalidOrUnavailableDtcoValuesDoNotCreateShiftWarnings() {
        val invalid = ShiftPeriodAlertMath.remaining(
            maximumDailyPeriodMinutes = 12 * 60,
            timeLeftUntilDailyRestMinutes = 30
        )

        assertNull(invalid.toThirteenHours)
        assertNull(invalid.toFifteenHours)
        assertFalse(ShiftPeriodAlertMath.shouldWarn(invalid.toThirteenHours))
    }
}
