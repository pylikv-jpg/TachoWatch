package com.pylikv.tachowatch

/** Display limits never clamp the measured counter. */
object TimerLimit {
    enum class Level { NORMAL, NEAR, EXTENDED, LIMIT, OVER }
    fun level(actual:Int, normal:Int, maximum:Int):Level = when {
        actual>maximum -> Level.OVER
        actual==maximum -> Level.LIMIT
        maximum>normal && actual>=maximum-15 -> Level.EXTENDED
        actual>=normal-15 -> Level.NEAR
        else -> Level.NORMAL
    }
    fun remaining(actual:Int,maximum:Int,shiftRemaining:Int?):Int? =
        shiftRemaining?.let { minOf(maximum-actual,it).coerceAtLeast(0) }
}
