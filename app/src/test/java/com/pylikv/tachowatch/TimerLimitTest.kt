package com.pylikv.tachowatch
import org.junit.Assert.*
import org.junit.Test
class TimerLimitTest {
 @Test fun warningsAtFifteenMinutes(){
  assertEquals(TimerLimit.Level.NORMAL,TimerLimit.level(254,270,270))
  assertEquals(TimerLimit.Level.NEAR,TimerLimit.level(255,270,270))
  assertEquals(TimerLimit.Level.NEAR,TimerLimit.level(525,540,600))
  assertEquals(TimerLimit.Level.EXTENDED,TimerLimit.level(585,540,600))
  assertEquals(TimerLimit.Level.NEAR,TimerLimit.level(765,780,900))
  assertEquals(TimerLimit.Level.EXTENDED,TimerLimit.level(885,780,900))
 }
 @Test fun overrunIsDistinctFromReachingLimit(){
  assertEquals(TimerLimit.Level.LIMIT,TimerLimit.level(540,540,540))
  assertEquals(TimerLimit.Level.OVER,TimerLimit.level(541,540,540))
  assertEquals(TimerLimit.Level.NEAR,TimerLimit.level(541,540,600))
  assertEquals(TimerLimit.Level.OVER,TimerLimit.level(901,780,900))
 }
 @Test fun shiftAlwaysBoundsRemaining(){
  assertEquals(40,TimerLimit.remaining(480,600,40))
  assertEquals(0,TimerLimit.remaining(480,600,-5))
  assertEquals(0,TimerLimit.remaining(601,600,40))
  assertNull(TimerLimit.remaining(480,600,null))
 }
}
