package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Test

class IndependentCounterEngineTest {
    private fun e(k: IndependentCounterEngine.Kind, m: Int) = IndependentCounterEngine.Event(k, m)

    @Test fun sep17ShiftDrivingStays749AcrossBreaks() {
        val s = IndependentCounterEngine.fromHistory(listOf(
            e(IndependentCounterEngine.Kind.WORK,8), e(IndependentCounterEngine.Kind.DRIVING,258), e(IndependentCounterEngine.Kind.REST,51), e(IndependentCounterEngine.Kind.DRIVING,178),
            e(IndependentCounterEngine.Kind.REST,123), e(IndependentCounterEngine.Kind.DRIVING,1), e(IndependentCounterEngine.Kind.REST,9), e(IndependentCounterEngine.Kind.DRIVING,4),
            e(IndependentCounterEngine.Kind.AVAILABILITY,13), e(IndependentCounterEngine.Kind.WORK,8), e(IndependentCounterEngine.Kind.DRIVING,1), e(IndependentCounterEngine.Kind.WORK,6),
            e(IndependentCounterEngine.Kind.REST,35), e(IndependentCounterEngine.Kind.WORK,1), e(IndependentCounterEngine.Kind.DRIVING,27)
        ))
        assertEquals(469, s.shiftDriving) // 7:49
        assertEquals(23, s.otherWork)
        assertEquals(13, s.availability)
        assertEquals(48, s.continuousWork) // only DRIVING+WORK after last >=45 rest
    }

    @Test fun fortyFiveResetsContinuousCountersButNeverShiftDriving() {
        val s = IndependentCounterEngine.fromHistory(listOf(
            e(IndependentCounterEngine.Kind.DRIVING,240),
            e(IndependentCounterEngine.Kind.REST,45),
            e(IndependentCounterEngine.Kind.DRIVING,30)
        ))
        assertEquals(270, s.shiftDriving)
        assertEquals(30, s.continuousDriving)
        assertEquals(30, s.continuousWork)
    }

    @Test fun dailyRestStartsNewShiftAndResetsWorkTotals() {
        val s = IndependentCounterEngine.fromHistory(listOf(
            e(IndependentCounterEngine.Kind.DRIVING,480),
            e(IndependentCounterEngine.Kind.WORK,12),
            e(IndependentCounterEngine.Kind.REST,540),
            e(IndependentCounterEngine.Kind.DRIVING,60)
        ))
        assertEquals(60, s.shiftDriving)
        assertEquals(60, s.continuousDriving)
        assertEquals(60, s.continuousWork)
        assertEquals(0, s.otherWork)
    }
}
