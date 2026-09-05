package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TachoStateEngineTest {
    private fun sample(
        activity: TachoStateEngine.Activity,
        activityMinutes: Int,
        continuousDriving: Int = 0,
        breakMinutes: Int = 0,
        twoWeek: Int = 0,
        now: Long = 0L
    ) = TachoStateEngine.RawSample(
        activity = activity,
        activityMinutes = activityMinutes,
        continuousDrivingMinutes = continuousDriving,
        breakMinutes = breakMinutes,
        twoWeekDrivingMinutes = twoWeek,
        nowMillis = now
    )

    @Test
    fun dailyRestThenSixMinutesOtherWorkStartsNewShiftFromZero() {
        var state = TachoStateEngine.State()

        state = TachoStateEngine.reduce(
            state,
            sample(TachoStateEngine.Activity.DRIVING, 40, continuousDriving = 340)
        ).state

        var result = TachoStateEngine.reduce(
            state,
            sample(TachoStateEngine.Activity.REST, 570, continuousDriving = 0, breakMinutes = 45)
        )
        state = result.state
        assertTrue(result.snapshot.dailyRestQualified)
        assertEquals(0, result.snapshot.shiftDrivingMinutes)
        assertEquals(0, result.snapshot.continuousWorkMinutes)

        result = TachoStateEngine.reduce(
            state,
            sample(TachoStateEngine.Activity.OTHER_WORK, 6, continuousDriving = 0)
        )

        assertTrue(result.snapshot.newShiftAfterDailyRest)
        assertFalse(result.snapshot.dailyRestQualified)
        assertEquals(0, result.snapshot.shiftDrivingMinutes)
        assertEquals(6, result.snapshot.continuousWorkMinutes)
        assertEquals(6, result.snapshot.otherWorkMinutes)
        assertEquals(0, result.snapshot.availabilityMinutes)
    }

    @Test
    fun fortyFiveMinuteBreakDoesNotResetShiftDriving() {
        var state = TachoStateEngine.State()
        var result = TachoStateEngine.reduce(
            state,
            sample(TachoStateEngine.Activity.DRIVING, 240, continuousDriving = 240)
        )
        state = result.state

        result = TachoStateEngine.reduce(
            state,
            sample(TachoStateEngine.Activity.REST, 45, continuousDriving = 0, breakMinutes = 45)
        )
        state = result.state

        assertEquals(240, result.snapshot.shiftDrivingMinutes)
        assertEquals(0, result.snapshot.continuousWorkMinutes)

        result = TachoStateEngine.reduce(
            state,
            sample(TachoStateEngine.Activity.DRIVING, 12, continuousDriving = 12)
        )

        assertEquals(252, result.snapshot.shiftDrivingMinutes)
        assertEquals(12, result.snapshot.continuousWorkMinutes)
    }

    @Test
    fun repeatedLiveCyclesDoNotDoubleCountOtherWork() {
        var state = TachoStateEngine.State()
        var result = TachoStateEngine.reduce(
            state,
            sample(TachoStateEngine.Activity.OTHER_WORK, 1)
        )
        state = result.state

        result = TachoStateEngine.reduce(state, sample(TachoStateEngine.Activity.OTHER_WORK, 2))
        state = result.state
        result = TachoStateEngine.reduce(state, sample(TachoStateEngine.Activity.OTHER_WORK, 3))
        state = result.state
        result = TachoStateEngine.reduce(state, sample(TachoStateEngine.Activity.OTHER_WORK, 6))

        assertEquals(6, result.snapshot.otherWorkMinutes)
        assertEquals(6, result.snapshot.continuousWorkMinutes)
    }

    @Test
    fun availabilityDoesNotIncreaseContinuousWork() {
        var state = TachoStateEngine.State()
        var result = TachoStateEngine.reduce(state, sample(TachoStateEngine.Activity.OTHER_WORK, 10))
        state = result.state
        result = TachoStateEngine.reduce(state, sample(TachoStateEngine.Activity.AVAILABILITY, 20))

        assertEquals(10, result.snapshot.continuousWorkMinutes)
        assertEquals(10, result.snapshot.otherWorkMinutes)
        assertEquals(20, result.snapshot.availabilityMinutes)
    }
}
