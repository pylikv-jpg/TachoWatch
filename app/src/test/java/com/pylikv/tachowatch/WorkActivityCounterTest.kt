package com.pylikv.tachowatch
import org.junit.Assert.*
import org.junit.Test
class WorkActivityCounterTest {
 @Test fun ignitionRestZeroThenRestoredWorkDoesNotDuplicate(){
  val c=WorkActivityCounter()
  c.update("WORK",156,0)
  c.update("REST",0,5000)
  assertEquals(156,c.other)
  c.update("WORK",0,15000)
  assertEquals(156,c.other)
  c.update("WORK",156,25000)
  c.update("WORK",157,85000)
  assertEquals(157,c.other);assertEquals(157,c.work)
 }
 @Test fun realTransitionAccumulatesOnce(){
  val c=WorkActivityCounter()
  c.update("WORK",156,0)
  c.update("DRIVING",0,1000)
  c.update("DRIVING",1,61000)
  c.update("DRIVING",2,121000)
  assertEquals(156,c.other);assertEquals(158,c.work)
 }
 @Test fun actualRestResetsWork(){
  val c=WorkActivityCounter()
  c.update("WORK",156,0)
  c.update("REST",45,2700000,true)
  assertEquals(0,c.work);assertEquals(0,c.other)
 }
 @Test fun shortRealRestThenNewWorkIsNotLost(){
  val c=WorkActivityCounter()
  c.update("WORK",156,0)
  c.update("REST",0,1000);c.update("REST",1,61000)
  c.update("WORK",0,121000);c.update("WORK",1,181000)
  assertEquals(157,c.other)
 }
 @Test fun seededHistoryDoesNotCountCurrentTwice(){
  val c=WorkActivityCounter();c.seed(20,20,0,"WORK",156)
  c.update("WORK",157,1000)
  assertEquals(177,c.other)
 }
}
