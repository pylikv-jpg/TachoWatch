package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Test

class IndependentCounterEngineTest {
    private fun e(k: IndependentCounterEngine.Kind, m: Int) = IndependentCounterEngine.Event(k, m)

    @Test fun sep17ShiftDrivingStays749AcrossBreaks() {
        val K = IndependentCounterEngine.Kind
        val s = IndependentCounterEngine.fromHistory(listOf(
            e(K.WORK,8), e(K.DRIVING,258), e(K.REST,51), e(K.DRIVING,178),
            e(K.REST,123), e(K.DRIVING,1), e(K.REST,9), e(K.DRIVING,4),
            e(K.AVAILABILITY,13), e(K.WORK,8), e(K.DRIVING,1), e(K.WORK,6),
            e(K.REST,35), e(K.WORK,1), e(K.DRIVING,27)
        ))
        assertEquals(469, s.shiftDriving) // 7:49
        assertEquals(23, s.otherWork)
        assertEquals(13, s.availability)
        assertEquals(48, s.continuousWork) // only DRIVING+WORK after last >=45 rest
    }

    @Test fun fortyFiveResetsContinuousCountersButNeverShiftDriving() {
        val K = IndependentCounterEngine.Kind
        val s = IndependentCounterEngine.fromHistory(listOf(e(K.DRIVING,240),e(K.REST,45),e(K.DRIVING,30)))
        assertEquals(270, s.shiftDriving)
        assertEquals(30, s.continuousDriving)
        assertEquals(30, s.continuousWork)
    }

    @Test fun dailyRestStartsNewShiftAndResetsWorkTotals() {
        val K = IndependentCounterEngine.Kind
        val s = IndependentCounterEngine.fromHistory(listOf(e(K.DRIVING,480),e(K.WORK,12),e(K.REST,540),e(K.DRIVING,60)))
        assertEquals(60, s.shiftDriving)
        assertEquals(60, s.continuousDriving)
        assertEquals(60, s.continuousWork)
        assertEquals(0, s.otherWork)
    }
}
