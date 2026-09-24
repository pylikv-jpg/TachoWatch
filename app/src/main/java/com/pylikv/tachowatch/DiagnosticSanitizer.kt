package com.pylikv.tachowatch

object DiagnosticSanitizer {
    fun sanitizeLine(line: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith("F931=", ignoreCase = true)) {
            return "F931=<REDACTED_DRIVER_NAME>"
        }
        return line
    }
}
