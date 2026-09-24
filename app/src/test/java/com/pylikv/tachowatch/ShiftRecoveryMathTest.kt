package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftRecoveryMathTest {

    @Test
    fun openDrivingMissingFromCardIsMergedIntoContinuousWork() {
        // Card contains 5 min OTHER WORK + 6 min closed DRIVING.
        // Live F923 is already at 11 min, so 5 open driving minutes are missing
        // from the card model and must survive the card-read pause.
        val result = ShiftRecoveryMath.mergeContinuousWorkMinutes(
            cardContinuousWorkMinutes = 11,
            cardContinuousDrivingMinutes = 6,
            liveActivity = "ВОЖДЕНИЕ",
            liveActivityMinutes = 5,
            liveContinuousDrivingMinutes = 11
        )

        assertEquals(16, result)
    }

    @Test
    fun alreadyClosedDrivingIsNotAddedTwice() {
        val result = ShiftRecoveryMath.mergeContinuousWorkMinutes(
            cardContinuousWorkMinutes = 16,
            cardContinuousDrivingMinutes = 11,
            liveActivity = "ВОЖДЕНИЕ",
            liveActivityMinutes = 5,
            liveContinuousDrivingMinutes = 11
        )

        assertEquals(16, result)
    }

    @Test
    fun openOtherWorkStillMergesAsBefore() {
        val result = ShiftRecoveryMath.mergeContinuousWorkMinutes(
            cardContinuousWorkMinutes = 10,
            cardContinuousDrivingMinutes = 10,
            liveActivity = "ДРУГАЯ РАБОТА",
            liveActivityMinutes = 5,
            liveContinuousDrivingMinutes = 10
        )

        assertEquals(15, result)
    }
}
