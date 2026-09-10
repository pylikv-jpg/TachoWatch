package com.pylikv.tachowatch

/** Union of card and live rest intervals: overlapping readings are never added twice. */
object ShiftRestProgress {
 data class Result(val total:Int,val longest:Int)
 fun calculate(start:Long,periods:List<CardActivityTimeline.Period>):Result {
  val rests=periods.filter{it.kind=="REST"&&it.end>start}.map{maxOf(start,it.start) to it.end}.sortedBy{it.first}
  var begin=0L;var end=0L;var total=0L;var longest=0L
  for(p in rests){
   if(end==0L){begin=p.first;end=p.second}
   else if(p.first<=end){end=maxOf(end,p.second)}
   else {total+=end-begin;longest=maxOf(longest,end-begin);begin=p.first;end=p.second}
  }
  if(end>0){total+=end-begin;longest=maxOf(longest,end-begin)}
  return Result((total/60000).toInt(),(longest/60000).toInt())
 }
}
