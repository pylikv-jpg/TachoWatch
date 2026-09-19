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
}
