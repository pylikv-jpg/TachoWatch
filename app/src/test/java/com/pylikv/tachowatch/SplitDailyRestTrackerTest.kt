package com.pylikv.tachowatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitDailyRestTrackerTest {

    @Test
    fun currentRestNeverCreditsItselfAtThreeHoursOrFiveHoursFiftySeven() {
        var state = SplitDailyRestTracker.RecoveryState()
        state = SplitDailyRestTracker.updateRecoveryState(state, "WORK", 60)
        for (minutes in listOf(179, 1, 177)) {
            state = SplitDailyRestTracker.updateRecoveryState(state, "REST", minutes)
            assertFalse(state.firstPartTaken)
        }
        state = SplitDailyRestTracker.updateRecoveryState(state, "WORK", 1)
        assertTrue(state.firstPartTaken)
    }

    @Test
    fun ongoingThreeHourRestMustNotCreditItselfAsAnEarlierFirstPart() {
        assertFalse(SplitDailyRestTracker.recoverFirstPartBeforeTrailingRest(
            listOf("WORK" to 60, "REST" to 180)
        ))
    }

    @Test
    fun ongoingRestChunksAcrossMidnightMustNotBecomeASeparateFirstPart() {
        assertFalse(SplitDailyRestTracker.recoverFirstPartBeforeTrailingRest(
            listOf("WORK" to 60, "REST" to 120, "REST" to 90)
        ))
    }

    @Test
    fun realEarlierThreeHourPartSurvivesWhileSecondRestContinues() {
        assertTrue(SplitDailyRestTracker.recoverFirstPartBeforeTrailingRest(
            listOf("WORK" to 60, "REST" to 180, "WORK" to 30, "REST" to 210)
        ))
    }

    @Test
    fun firstPartBeforeAnOpenSecondRestIsPreserved() {
        assertTrue(SplitDailyRestTracker.recoverFirstPartBeforeTrailingRest(
            listOf("WORK" to 60, "REST" to 180, "WORK" to 30)
        ))
    }

    @Test
    fun overnightTailWithoutShiftActivityDoesNotCreateCredit() {
        assertFalse(SplitDailyRestTracker.recoverFirstPartBeforeTrailingRest(
            listOf("REST" to 210)
        ))
    }

    @Test
    fun overnightRestTailBeforeNewShiftIsNotAThreeHourPart() {
        // The card splits the 13h24 rest at midnight. Today's tail is 3h46,
        // followed by the new shift, so the tail must not qualify on its own.
        assertFalse(SplitDailyRestTracker.recoverFirstPartFromClosedActivities(
            listOf("REST" to 226, "WORK" to 5)
        ))
        assertFalse(SplitDailyRestTracker.recoverFirstPartFromClosedActivities(
            listOf("REST" to 226) // first WORK is still OPEN on the card
        ))
    }

    @Test
    fun threeHourPartInsideNewShiftIsStillRecovered() {
        assertTrue(SplitDailyRestTracker.recoverFirstPartFromClosedActivities(
            listOf("REST" to 226, "WORK" to 10, "REST" to 372)
        ))
    }

    @Test
    fun dailyRestSplitAtMidnightClosesPreviousShiftCredit() {
        assertFalse(SplitDailyRestTracker.recoverFirstPartFromClosedActivities(
            listOf("WORK" to 10, "REST" to 180, "WORK" to 60,
                "REST" to 300, "REST" to 360, "WORK" to 5)
        ))
    }

    @Test
    fun firstPartCrossingMidnightWithinOneShiftIsCombined() {
        assertTrue(SplitDailyRestTracker.recoverFirstPartFromClosedActivities(
            listOf("WORK" to 10, "REST" to 90, "REST" to 100, "WORK" to 5)
        ))
    }

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

    @Test
    fun recoveryStateCarriesFirstPartAcrossMidnightChunks() {
        var state = SplitDailyRestTracker.RecoveryState()
        state = SplitDailyRestTracker.updateRecoveryState(state, "WORK", 10)
        state = SplitDailyRestTracker.updateRecoveryState(state, "REST", 90)

        // Next calendar day continues the same uninterrupted REST.
        state = SplitDailyRestTracker.updateRecoveryState(state, "REST", 100)
        state = SplitDailyRestTracker.updateRecoveryState(state, "WORK", 5)

        assertTrue(state.firstPartTaken)
    }

    @Test
    fun recoveryStateClearsSplitCreditWhenContinuousRestReachesNineHours() {
        var state = SplitDailyRestTracker.RecoveryState()
        state = SplitDailyRestTracker.updateRecoveryState(state, "WORK", 10)
        state = SplitDailyRestTracker.updateRecoveryState(state, "REST", 180)
        assertFalse(state.firstPartTaken)

        state = SplitDailyRestTracker.updateRecoveryState(state, "REST", 360)
        assertFalse(state.firstPartTaken)
        assertFalse(state.shiftHasActivity)
    }
}
