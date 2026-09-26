package com.pylikv.tachowatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcoCardPresenceTest {
    @Test
    fun allDriverTimersFFFFMeansCardRemoved() {
        val block = """
F923=FF FF | —
F925=FF FF | —
F927=FF FF | —
F938=FF FF | —
""".trimIndent()
        assertTrue(DtcoCardPresence.isRemovalSignal(block))
    }

    @Test
    fun midnightStyleActivityBoundaryIsNotCardRemoval() {
        val block = """
F923=00 50 | 80 мин = 1:20
F925=00 00 | 0 мин = 0:00
F927=00 00 | 0 мин = 0:00
F938=12 AD | 4781 мин = 79:41
""".trimIndent()
        assertFalse(DtcoCardPresence.isRemovalSignal(block))
        assertTrue(DtcoCardPresence.hasUsableDriverTimers(block))
    }

    @Test
    fun oneUnavailableTimerIsNotAcceptedAsUsable() {
        val block = """
F923=FF FE | —
F925=00 00 | 0 мин = 0:00
F927=00 00 | 0 мин = 0:00
F938=12 AD | 4781 мин = 79:41
""".trimIndent()
        assertFalse(DtcoCardPresence.hasUsableDriverTimers(block))
    }
}
