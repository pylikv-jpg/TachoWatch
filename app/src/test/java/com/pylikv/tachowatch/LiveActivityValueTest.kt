package com.pylikv.tachowatch

import org.junit.Assert.*
import org.junit.Test

class LiveActivityValueTest {
    @Test fun rejectsNegativeMissingAndUnknownActivity() {
        for (line in listOf("", "F903=NRC 7F 22 31", "F903= | —",
            "F903=04 | КОД 4", "F903=FF | КОД 7")) {
            assertNull(line, LiveActivityValue.fromCycle(line))
        }
    }

    @Test fun latestFailureDoesNotReuseEarlierRest() {
        assertNull(LiveActivityValue.fromCycle(
            "F903=00 | ОТДЫХ / ПЕРЕРЫВ\nF903=NRC 7F 22 31"))
    }

    @Test fun acceptsAllKnownActivities() {
        for ((raw, name) in listOf("00" to "ОТДЫХ / ПЕРЕРЫВ", "01" to "ГОТОВНОСТЬ",
            "02" to "ДРУГАЯ РАБОТА", "03" to "ВОЖДЕНИЕ")) {
            assertEquals(name, LiveActivityValue.fromCycle("F903=$raw | $name"))
        }
    }

    private fun applyCycle(state: SplitDailyRestTracker.State, line: String, minutes: Int):
        SplitDailyRestTracker.State {
        val activity = LiveActivityValue.fromCycle(line) ?: return state
        return SplitDailyRestTracker.update(state, activity == "ОТДЫХ / ПЕРЕРЫВ", minutes)
    }

    @Test fun nrcDuringLongContinuousRestDoesNotCreditThreeHours() {
        var state = SplitDailyRestTracker.State()
        state = applyCycle(state, "F903=00 | ОТДЫХ / ПЕРЕРЫВ", 179)
        state = applyCycle(state, "F903=00 | ОТДЫХ / ПЕРЕРЫВ", 180)
        assertFalse(state.firstPartTaken)
        state = applyCycle(state, "F903=00 | ОТДЫХ / ПЕРЕРЫВ", 287)
        val beforeFailure = state
        state = applyCycle(state, "F903=NRC 7F 22 31", 288)
        assertEquals(beforeFailure, state)
        state = applyCycle(state, "F903=00 | ОТДЫХ / ПЕРЕРЫВ", 289)
        assertFalse(state.firstPartTaken)
        assertEquals(289, state.previousRestMinutes)
    }

    @Test fun genuineActivityAfterThreeHoursStillCreditsFirstPart() {
        var state = applyCycle(SplitDailyRestTracker.State(), "F903=00 | ОТДЫХ / ПЕРЕРЫВ", 207)
        state = applyCycle(state, "F903=02 | ДРУГАЯ РАБОТА", 0)
        assertTrue(state.firstPartTaken)
        assertFalse(state.previousResting)
    }
}
