package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyDrivingLimitsTest {
    @Test
    fun thirtyHoursLastWeekDoesNotAllowSixtyHoursThisWeek() {
        assertEquals(46 * 60 + 30, WeeklyDrivingLimits.remainingThisWeek(30 * 60, 9 * 60 + 30))
    }

    @Test
    fun fortyHoursLastWeekMakesTheNinetyHourLimitStricter() {
        assertEquals(40 * 60 + 30, WeeklyDrivingLimits.remainingThisWeek(40 * 60, 9 * 60 + 30))
    }

    @Test
    fun fiftySixHoursLastWeekLeavesOnlyThirtyFourThisWeek() {
        assertEquals(34 * 60, WeeklyDrivingLimits.remainingThisWeek(56 * 60, 0))
    }

    @Test
    fun exactlyFiftySixThisWeekLeavesZeroEvenBelowNinety() {
        assertEquals(0, WeeklyDrivingLimits.remainingThisWeek(30 * 60, 56 * 60))
    }

    @Test
    fun exactlyNinetyOverTwoWeeksLeavesZeroEvenBelowFiftySixThisWeek() {
        assertEquals(0, WeeklyDrivingLimits.remainingThisWeek(56 * 60, 34 * 60))
    }

    @Test
    fun weeklyOverrunNeverProducesNegativeAvailability() {
        assertEquals(0, WeeklyDrivingLimits.remainingThisWeek(0, 57 * 60))
    }

    @Test
    fun twoWeekOverrunNeverProducesNegativeAvailability() {
        assertEquals(0, WeeklyDrivingLimits.remainingThisWeek(56 * 60, 35 * 60))
    }

    @Test
    fun noPreviousDrivingStillCapsThisWeekAtFiftySix() {
        assertEquals(56 * 60, WeeklyDrivingLimits.remainingThisWeek(0, 0))
    }

    @Test
    fun limitingRuleSwitchesAtThirtyFourHoursLastWeek() {
        assertEquals(46 * 60 + 30, WeeklyDrivingLimits.remainingThisWeek(34 * 60, 9 * 60 + 30))
        assertEquals(46 * 60 + 29, WeeklyDrivingLimits.remainingThisWeek(34 * 60 + 1, 9 * 60 + 30))
    }

    @Test
    fun invalidNegativeDurationsNeverIncreaseTheMaximum() {
        assertEquals(56 * 60, WeeklyDrivingLimits.remainingThisWeek(-1, -1))
    }

    @Test
    fun oversizedDurationsCannotOverflowIntoPositiveAvailability() {
        assertEquals(0, WeeklyDrivingLimits.remainingThisWeek(Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
