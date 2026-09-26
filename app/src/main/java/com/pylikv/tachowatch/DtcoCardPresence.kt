package com.pylikv.tachowatch

/** Live driver-card presence derived only from the DTCO timer sentinel pattern.
 * Never infer insertion/removal from midnight activity-record boundaries.
 */
object DtcoCardPresence {
    private val requiredTimers = listOf("F923", "F925", "F927", "F938")

    fun isRemovalSignal(cycleBlock: String): Boolean =
        requiredTimers.all { rawTwoByte(cycleBlock, it) == 0xFFFF }

    fun hasUsableDriverTimers(cycleBlock: String): Boolean =
        listOf("F923", "F925", "F927").all { did ->
            rawTwoByte(cycleBlock, did)?.let { it < 0xFFFE } == true
        }

    private fun rawTwoByte(block: String, did: String): Int? {
        val line = block.lineSequence().lastOrNull { it.startsWith("$did=") } ?: return null
        val raw = line.substringAfter('=').substringBefore(" | ").trim()
        val parts = raw.split(Regex("\\s+"))
        if (parts.size < 2) return null
        val hi = parts[0].toIntOrNull(16) ?: return null
        val lo = parts[1].toIntOrNull(16) ?: return null
        return (hi shl 8) or lo
    }
}
