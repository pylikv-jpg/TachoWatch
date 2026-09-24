package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun redactsDriverNameDidButKeepsOtherRawDidData() {
        assertEquals(
            "F931=<REDACTED_DRIVER_NAME>",
            DiagnosticSanitizer.sanitizeLine("F931=4A 4F 48 4E | JOHN DRIVER")
        )

        val did = DiagnosticSanitizer.sanitizeLine("F923=00 A7 | 167 мин = 2:47")
        assertTrue(did.contains("F923=00 A7"))
        assertTrue(did.contains("167 мин"))
    }
}
