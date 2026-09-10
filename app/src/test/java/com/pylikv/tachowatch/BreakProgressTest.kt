package com.pylikv.tachowatch

import org.junit.Assert.*
import org.junit.Test

class BreakProgressTest {
    @Test fun fifteenThenTwentyOneNeedsNineMore(){
        val r=BreakProgress.calculate(21,36)
        assertEquals(15,r.priorPart);assertEquals(36,r.total);assertEquals(9,r.remaining);assertFalse(r.complete)
    }
    @Test fun standaloneTwentyOneNeedsTwentyFour(){assertEquals(24,BreakProgress.calculate(21,21).remaining)}
    @Test fun thirtyThenFifteenIsNotComplete(){
        val r=BreakProgress.calculate(15,45);assertEquals(15,r.remaining);assertFalse(r.complete)
    }
    @Test fun fifteenThenThirtyCompletes(){assertTrue(BreakProgress.calculate(30,45).complete)}
    @Test fun standaloneFortyFiveCompletes(){assertTrue(BreakProgress.calculate(45,45).complete)}
    @Test fun missingCumulativeDoesNotInventFirstPart(){assertEquals(24,BreakProgress.calculate(21,null).remaining)}
}
