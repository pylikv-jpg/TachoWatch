package com.pylikv.tachowatch
import org.junit.Assert.*
import org.junit.Test
class ShiftRestProgressTest {
 private fun rest(a:Int,b:Int)=CardActivityTimeline.Period(a*60000L,b*60000L,"REST")
 @Test fun restoresThreeHoursFromCard(){
  val r=ShiftRestProgress.calculate(60000,listOf(rest(10,190)))
  assertEquals(180,r.total);assertEquals(180,r.longest)
 }
 @Test fun separatePausesDoNotBecomeThreeHourRest(){
  val r=ShiftRestProgress.calculate(60000,listOf(rest(10,100),rest(110,200)))
  assertEquals(180,r.total);assertEquals(90,r.longest)
 }
 @Test fun overlappingLiveAndCardAreNotDuplicated(){
  val r=ShiftRestProgress.calculate(60000,listOf(rest(10,190),rest(10,200)))
  assertEquals(190,r.total);assertEquals(190,r.longest)
 }
 @Test fun previousShiftRestExcluded(){
  assertEquals(0,ShiftRestProgress.calculate(200*60000L,listOf(rest(10,190))).total)
 }
}
