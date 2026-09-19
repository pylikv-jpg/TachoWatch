package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousWorkCounterTest {

    @Test
    fun cardSeedOtherWorkPlusCurrentDrivingAreAdded() {
        val state = ContinuousWorkCounter.State(
            workMinutes = 20,
            otherWorkMinutes = 20,
            previousActivity = "—",
            previousSourceMinutes = 0
        )

        val result = ContinuousWorkCounter.update(
            state = state,
            currentActivity = "ВОЖДЕНИЕ",
            activityMinutes = 60,
            continuousDrivingMinutes = 60,
            qualifyingRestMinutes = 0
        )

        assertEquals(80, result.workMinutes)
    }

    @Test
    fun cardSeedPlusCurrentOpenOtherWorkAreAdded() {
        // Consistent checkpoint: 60 min DRIVING + 10 min completed OTHER WORK.
        // The open 15 min OTHER WORK segment from F927 must be added on top.
        val state = ContinuousWorkCounter.State(
            workMinutes = 70,
            otherWorkMinutes = 10,
            previousActivity = "—",
            previousSourceMinutes = 0
        )

        val result = ContinuousWorkCounter.update(
            state = state,
            currentActivity = "ДРУГАЯ РАБОТА",
            activityMinutes = 15,
            continuousDrivingMinutes = 60,
            qualifyingRestMinutes = 0
        )

        assertEquals(85, result.workMinutes)
        assertEquals(25, result.otherWorkMinutes)
    }

    @Test
    fun transitionIntoOtherWorkKeepsElapsedNewSegment() {
        val state = ContinuousWorkCounter.State(
            workMinutes = 60,
            otherWorkMinutes = 0,
            previousActivity = "ВОЖДЕНИЕ",
            previousSourceMinutes = 60
        )

        val result = ContinuousWorkCounter.update(
            state = state,
            currentActivity = "ДРУГАЯ РАБОТА",
            activityMinutes = 7,
            continuousDrivingMinutes = 60,
            qualifyingRestMinutes = 0
        )

        assertEquals(67, result.workMinutes)
        assertEquals(7, result.otherWorkMinutes)
    }

    @Test
    fun driveWorkDriveUsesF923ForDrivingAndIgnoresStaleDrivingF927() {
        var state = ContinuousWorkCounter.State(
            workMinutes = 0,
            otherWorkMinutes = 0,
            previousActivity = "—",
            previousSourceMinutes = 0
        )

        state = ContinuousWorkCounter.update(
            state = state,
            currentActivity = "ВОЖДЕНИЕ",
            activityMinutes = 60,
            continuousDrivingMinutes = 60,
            qualifyingRestMinutes = 0
        )
        state = ContinuousWorkCounter.update(
            state = state,
            currentActivity = "ДРУГАЯ РАБОТА",
            activityMinutes = 10,
            continuousDrivingMinutes = 60,
            qualifyingRestMinutes = 0
        )
        state = ContinuousWorkCounter.update(
            state = state,
            currentActivity = "ВОЖДЕНИЕ",
            // Simulate the real failure mode: F927 still carries a large value when
            // the activity has already switched back to DRIVING.
            activityMinutes = 35,
            continuousDrivingMinutes = 65,
            qualifyingRestMinutes = 0
        )

        assertEquals(75, state.workMinutes)
        assertEquals(10, state.otherWorkMinutes)
    }

    @Test
    fun otherWorkContinuesFromF927WithoutChangingDrivingContribution() {
        var state = ContinuousWorkCounter.State(
            workMinutes = 60,
            otherWorkMinutes = 0,
            previousActivity = "ВОЖДЕНИЕ",
            previousSourceMinutes = 60
        )

        state = ContinuousWorkCounter.update(
            state = state,
            currentActivity = "ДРУГАЯ РАБОТА",
            activityMinutes = 5,
            continuousDrivingMinutes = 60,
            qualifyingRestMinutes = 0
        )
        assertEquals(65, state.workMinutes)
        assertEquals(5, state.otherWorkMinutes)

        state = ContinuousWorkCounter.update(
            state = state,
            currentActivity = "ДРУГАЯ РАБОТА",
            activityMinutes = 7,
            continuousDrivingMinutes = 60,
            qualifyingRestMinutes = 0
        )
        assertEquals(67, state.workMinutes)
        assertEquals(7, state.otherWorkMinutes)
    }

    @Test
    fun fortyFiveMinuteBreakResetsContinuousWork() {
        val state = ContinuousWorkCounter.State(
            workMinutes = 180,
            otherWorkMinutes = 30,
            previousActivity = "ДРУГАЯ РАБОТА",
            previousSourceMinutes = 30
        )

        val result = ContinuousWorkCounter.update(
            state = state,
            currentActivity = "ОТДЫХ / ПЕРЕРЫВ",
            activityMinutes = 45,
            continuousDrivingMinutes = 0,
            qualifyingRestMinutes = 45
        )

        assertEquals(0, result.workMinutes)
    }
}
