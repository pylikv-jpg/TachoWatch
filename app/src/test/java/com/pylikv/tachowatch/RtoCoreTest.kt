package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtoCoreTest {
    private val t = 1788537600000L // fixed UTC instant in Sep 2026

    private fun sample(a: RtoCore.Activity, activity: Int, continuous: Int) =
        RtoCore.Sample(a, activity, continuous, t)

    @Test
    fun fortyFiveMinuteBreakKeepsDailyDriving() {
        var state = RtoCore.initialState(sample(RtoCore.Activity.DRIVING, 240, 240), cardDailyDrivingMinutes = 240)
        var r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 0, 240)); state = r.state
        r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 45, 0)); state = r.state
        assertEquals(240, r.snapshot.dailyDrivingMinutes)
        assertEquals(0, r.snapshot.continuousDrivingMinutes)

        r = RtoCore.reduce(state, sample(RtoCore.Activity.DRIVING, 130, 130))
        assertEquals(370, r.snapshot.dailyDrivingMinutes)
        assertEquals(130, r.snapshot.continuousDrivingMinutes)
    }

    @Test
    fun nineHourDailyRestResetsDailyDrivingAndCountsReducedWhenItEnds() {
        var state = RtoCore.initialState(sample(RtoCore.Activity.DRIVING, 550, 550), cardDailyDrivingMinutes = 550)
        var r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 0, 550)); state = r.state
        r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 540, 0)); state = r.state
        assertEquals(0, r.snapshot.dailyDrivingMinutes)
        assertTrue(r.snapshot.dailyRestActive)
        assertEquals(1, r.snapshot.tenHourDrivingUsesThisWeek)

        r = RtoCore.reduce(state, sample(RtoCore.Activity.OTHER_WORK, 1, 0))
        assertEquals(1, r.snapshot.reducedDailyRestsUsed)
        assertFalse(r.snapshot.dailyRestActive)
        assertEquals(0, r.snapshot.workBreakCreditedMinutes)
    }

    @Test
    fun elevenHourDailyRestIsRegular() {
        var state = RtoCore.initialState(sample(RtoCore.Activity.DRIVING, 300, 300), cardDailyDrivingMinutes = 300)
        var r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 0, 300)); state = r.state
        r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 660, 0)); state = r.state
        r = RtoCore.reduce(state, sample(RtoCore.Activity.DRIVING, 1, 1))
        assertEquals(0, r.snapshot.reducedDailyRestsUsed)
    }

    @Test
    fun splitThreePlusNineIsRegularDailyRest() {
        var state = RtoCore.initialState(sample(RtoCore.Activity.DRIVING, 120, 120), cardDailyDrivingMinutes = 120)
        var r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 180, 120)); state = r.state
        r = RtoCore.reduce(state, sample(RtoCore.Activity.OTHER_WORK, 10, 120)); state = r.state
        assertTrue(state.splitThreeHourPartTaken)

        r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 0, 120)); state = r.state
        r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 540, 0)); state = r.state
        r = RtoCore.reduce(state, sample(RtoCore.Activity.DRIVING, 1, 1))
        assertEquals(0, r.snapshot.reducedDailyRestsUsed)
    }

    @Test
    fun weeklyRestResetsReducedDailyRestAllowance() {
        var state = RtoCore.State(
            initialized = true,
            previousActivity = RtoCore.Activity.REST,
            previousActivityMinutes = 600,
            previousContinuousDrivingMinutes = 0,
            reducedDailyRestsUsed = 3,
            isoWeekKey = RtoCore.isoWeekKey(t),
            dailyRestLatched = true
        )
        val r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 1440, 0))
        assertEquals(0, r.snapshot.reducedDailyRestsUsed)
        assertEquals(3, r.snapshot.reducedDailyRestsRemaining)
        assertTrue(r.snapshot.weeklyRestActive)
    }

    @Test
    fun continuousWorkCombinesDrivingAndOtherWorkButNotAvailability() {
        var state = RtoCore.initialState(
            sample(RtoCore.Activity.DRIVING, 120, 120),
            cardDailyDrivingMinutes = 120,
            cardDailyWorkMinutes = 120
        )
        var r = RtoCore.reduce(state, sample(RtoCore.Activity.OTHER_WORK, 60, 120)); state = r.state
        assertEquals(180, r.snapshot.continuousWorkMinutes)
        assertEquals(60, r.snapshot.otherWorkDailyMinutes)

        r = RtoCore.reduce(state, sample(RtoCore.Activity.AVAILABILITY, 60, 120)); state = r.state
        assertEquals(180, r.snapshot.continuousWorkMinutes)
        assertEquals(180, r.snapshot.dailyWorkMinutes)

        r = RtoCore.reduce(state, sample(RtoCore.Activity.OTHER_WORK, 30, 120))
        assertEquals(210, r.snapshot.continuousWorkMinutes)
        assertEquals(210, r.snapshot.dailyWorkMinutes)
        assertEquals(90, r.snapshot.otherWorkDailyMinutes)
    }

    @Test
    fun fortyFiveMinuteWorkBreakInterruptsSixHourClock() {
        var state = RtoCore.initialState(
            sample(RtoCore.Activity.DRIVING, 300, 300),
            cardDailyDrivingMinutes = 300,
            cardDailyWorkMinutes = 300
        )
        var r = RtoCore.reduce(state, sample(RtoCore.Activity.OTHER_WORK, 50, 300)); state = r.state
        assertEquals(350, r.snapshot.continuousWorkMinutes)

        r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 15, 300)); state = r.state
        assertEquals(350, r.snapshot.continuousWorkMinutes)

        r = RtoCore.reduce(state, sample(RtoCore.Activity.REST, 45, 0)); state = r.state
        assertEquals(0, r.snapshot.continuousWorkMinutes)

        r = RtoCore.reduce(state, sample(RtoCore.Activity.DRIVING, 20, 20))
        assertEquals(20, r.snapshot.continuousWorkMinutes)
    }

    @Test
    fun workBreakRequirementIsThirtyThenFortyFiveMinutes() {
        var state = RtoCore.initialState(
            sample(RtoCore.Activity.OTHER_WORK, 400, 0),
            cardDailyWorkMinutes = 400,
            cardOtherWorkMinutes = 400
        )
        var r = RtoCore.reduce(state, sample(RtoCore.Activity.OTHER_WORK, 400, 0)); state = r.state
        assertEquals(30, r.snapshot.workBreakRequiredMinutes)

        r = RtoCore.reduce(state, sample(RtoCore.Activity.OTHER_WORK, 550, 0))
        assertEquals(45, r.snapshot.workBreakRequiredMinutes)
    }
}
