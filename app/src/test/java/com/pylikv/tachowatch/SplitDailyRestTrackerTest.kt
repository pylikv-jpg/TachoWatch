package com.pylikv.tachowatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitDailyRestTrackerTest {

    @Test
    fun completedThreeHourPartSurvivesReturnToWork() {
        var state = SplitDailyRestTracker.State()

        state = SplitDailyRestTracker.update(state, resting = true, restMinutes = 372)
        assertFalse(state.firstPartTaken)

        state = SplitDailyRestTracker.update(state, resting = false, restMinutes = 0)
        assertTrue(state.firstPartTaken)

        state = SplitDailyRestTracker.update(state, resting = false, restMinutes = 0)
        assertTrue(state.firstPartTaken)
    }

    @Test
    fun secondNineHourPartCompletesRegularSplitRest() {
        var state = SplitDailyRestTracker.State()

        state = SplitDailyRestTracker.update(state, resting = true, restMinutes = 180)
        state = SplitDailyRestTracker.update(state, resting = false, restMinutes = 0)
        assertTrue(state.firstPartTaken)

        state = SplitDailyRestTracker.update(state, resting = true, restMinutes = 539)
        assertTrue(state.firstPartTaken)
        assertFalse(state.completedAsSplit)

        state = SplitDailyRestTracker.update(state, resting = true, restMinutes = 540)
        assertTrue(state.firstPartTaken)
        assertTrue(state.completedAsSplit)

        state = SplitDailyRestTracker.update(state, resting = false, restMinutes = 0)
        assertFalse(state.firstPartTaken)
        assertFalse(state.completedAsSplit)
    }

    @Test
    fun continuousNineHourRestDoesNotCreateSplitCredit() {
        var state = SplitDailyRestTracker.State()

        state = SplitDailyRestTracker.update(state, resting = true, restMinutes = 372)
        assertFalse(state.firstPartTaken)

        state = SplitDailyRestTracker.update(state, resting = true, restMinutes = 540)
        assertFalse(state.firstPartTaken)
        assertFalse(state.completedAsSplit)

        state = SplitDailyRestTracker.update(state, resting = false, restMinutes = 0)
        assertFalse(state.firstPartTaken)
    }

    @Test
    fun shortRestDoesNotCreateThreeHourCredit() {
        var state = SplitDailyRestTracker.State()

        state = SplitDailyRestTracker.update(state, resting = true, restMinutes = 179)
        state = SplitDailyRestTracker.update(state, resting = false, restMinutes = 0)

        assertFalse(state.firstPartTaken)
    }

    @Test
    fun cardRecoveryRestoresOnlyClosedThreeToNineHourFirstPart() {
        assertFalse(SplitDailyRestTracker.recoverFirstPartFromClosedRest(listOf(45, 179)))
        assertTrue(SplitDailyRestTracker.recoverFirstPartFromClosedRest(listOf(45, 180)))
        assertTrue(SplitDailyRestTracker.recoverFirstPartFromClosedRest(listOf(539)))
        assertFalse(SplitDailyRestTracker.recoverFirstPartFromClosedRest(listOf(540)))
        assertFalse(SplitDailyRestTracker.recoverFirstPartFromClosedRest(listOf(660)))
    }
}
