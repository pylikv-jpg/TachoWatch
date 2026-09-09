package com.pylikv.tachowatch

/** F927 is this uninterrupted rest; F925 includes prior qualifying break parts. */
object BreakProgress {
    data class Result(val priorPart:Int,val total:Int,val remaining:Int,val complete:Boolean)
    fun calculate(actual:Int,cumulative:Int?):Result{
        val current=actual.coerceAtLeast(0)
        val prior=if(cumulative!=null)(cumulative-current).coerceAtLeast(0) else 0
        val firstPart=if(prior>=15)prior else 0
        // Even 30 + 15 is not 15 + 30: the second part must last at least 30 minutes.
        val target=if(firstPart>=15)30 else 45
        val remaining=(target-current).coerceAtLeast(0)
        return Result(firstPart,firstPart+current,remaining,remaining==0)
    }
}
