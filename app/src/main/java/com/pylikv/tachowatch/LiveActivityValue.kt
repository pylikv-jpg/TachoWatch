package com.pylikv.tachowatch

/** A rejected/missing F903 response is unknown activity, never a REST exit. */
object LiveActivityValue {
    fun fromCycle(block: String): String? {
        val line = block.lineSequence().filter { it.startsWith("F903=") }.lastOrNull()
            ?: return null
        if (!line.contains(" | ")) return null
        val value = line.substringAfter(" | ").trim()
        return value.takeIf {
            it == "ОТДЫХ / ПЕРЕРЫВ" || it == "ГОТОВНОСТЬ" ||
                it == "ДРУГАЯ РАБОТА" || it == "ВОЖДЕНИЕ"
        }
    }
}
