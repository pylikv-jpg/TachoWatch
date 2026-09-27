package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftDrivingCounterTest {

    @Test
    fun openDrivingCheckpointKeepsFullShiftInsteadOfOnlyNextDelta() {
        val checkpoint = ShiftDrivingCounter.reconcileCheckpoint(
            cardShiftDrivingMinutes = 0,
            cardContinuousDrivingMinutes = 0,
            liveContinuousDrivingMinutes = 161,
            dailyRestCompleted = false
        )

        assertEquals(161, checkpoint.totalMinutes)
        assertEquals(161, checkpoint.previousContinuousMinutes)

        val next = ShiftDrivingCounter.update(
            initialized = checkpoint.initialized,
            totalMinutes = checkpoint.totalMinutes,
            previousContinuousMinutes = checkpoint.previousContinuousMinutes,
            currentContinuousMinutes = 166,
            dailyRestCompleted = false
        )

        assertEquals(166, next.totalMinutes)
    }

    @Test
    fun priorCardDrivingIsNotDoubleCountedWhenOpenSegmentIsMerged() {
        val checkpoint = ShiftDrivingCounter.reconcileCheckpoint(
            cardShiftDrivingMinutes = 120,
            cardContinuousDrivingMinutes = 120,
            liveContinuousDrivingMinutes = 150,
            dailyRestCompleted = false
        )

        assertEquals(150, checkpoint.totalMinutes)
        assertEquals(150, checkpoint.previousContinuousMinutes)
    }

    @Test
    fun cardAndLiveSameContinuousValueDoNotDuplicateDriving() {
        val checkpoint = ShiftDrivingCounter.reconcileCheckpoint(
            cardShiftDrivingMinutes = 150,
            cardContinuousDrivingMinutes = 150,
            liveContinuousDrivingMinutes = 150,
            dailyRestCompleted = false
        )

        assertEquals(150, checkpoint.totalMinutes)
    }

    @Test
    fun confirmedDailyRestStillResetsShiftDriving() {
        val checkpoint = ShiftDrivingCounter.reconcileCheckpoint(
            cardShiftDrivingMinutes = 480,
            cardContinuousDrivingMinutes = 120,
            liveContinuousDrivingMinutes = 120,
            dailyRestCompleted = true
        )

        assertEquals(0, checkpoint.totalMinutes)
        assertEquals(0, checkpoint.previousContinuousMinutes)
    }

    @Test
    fun transientZeroDoesNotDoubleShiftDriving() {
        var state = ShiftDrivingCounter.update(
            initialized = true,
            totalMinutes = 200,
            previousContinuousMinutes = 200,
            currentContinuousMinutes = 0,
            dailyRestCompleted = false
        )

        assertEquals(200, state.totalMinutes)
        assertEquals(200, state.previousContinuousMinutes)
        assertEquals(0, state.resetCandidateMinutes)

        state = ShiftDrivingCounter.update(
            initialized = state.initialized,
            totalMinutes = state.totalMinutes,
            previousContinuousMinutes = state.previousContinuousMinutes,
            currentContinuousMinutes = 200,
            dailyRestCompleted = false,
            resetCandidateMinutes = state.resetCandidateMinutes
        )

        assertEquals(200, state.totalMinutes)
        assertEquals(200, state.previousContinuousMinutes)
        assertEquals(-1, state.resetCandidateMinutes)
    }

    @Test
    fun smallGrowingValueConfirmsMissedF923ResetWithoutDoubleCounting() {
        var state = ShiftDrivingCounter.update(
            initialized = true,
            totalMinutes = 200,
            previousContinuousMinutes = 200,
            currentContinuousMinutes = 0,
            dailyRestCompleted = false
        )
        state = ShiftDrivingCounter.update(
            initialized = state.initialized,
            totalMinutes = state.totalMinutes,
            previousContinuousMinutes = state.previousContinuousMinutes,
            currentContinuousMinutes = 1,
            dailyRestCompleted = false,
            resetCandidateMinutes = state.resetCandidateMinutes
        )
        assertEquals(200, state.totalMinutes)

        state = ShiftDrivingCounter.update(
            initialized = state.initialized,
            totalMinutes = state.totalMinutes,
            previousContinuousMinutes = state.previousContinuousMinutes,
            currentContinuousMinutes = 2,
            dailyRestCompleted = false,
            resetCandidateMinutes = state.resetCandidateMinutes
        )

        assertEquals(202, state.totalMinutes)
        assertEquals(2, state.previousContinuousMinutes)
        assertEquals(-1, state.resetCandidateMinutes)
    }

    @Test
    fun observedFortyFiveMinuteBreakAnchorsNewF923CycleImmediately() {
        var state = ShiftDrivingCounter.update(
            initialized = true,
            totalMinutes = 200,
            previousContinuousMinutes = 200,
            currentContinuousMinutes = 200,
            dailyRestCompleted = false,
            qualifyingRestMinutes = 45
        )
        assertEquals(200, state.totalMinutes)
        assertEquals(0, state.previousContinuousMinutes)

        state = ShiftDrivingCounter.update(
            initialized = state.initialized,
            totalMinutes = state.totalMinutes,
            previousContinuousMinutes = state.previousContinuousMinutes,
            currentContinuousMinutes = 1,
            dailyRestCompleted = false
        )
        assertEquals(201, state.totalMinutes)
    }
}
