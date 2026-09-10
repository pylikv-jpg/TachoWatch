package com.pylikv.tachowatch

/** Seed once from a fresh card, then count independent F938 deltas, never F923 resets. */
class ShiftDrivingCounter {
    var minutes:Int?=null
        private set
    private var previous:Int?=null
    private var week:String?=null
    fun seed(value:Int?){minutes=value;previous=null;week=null}
    fun update(twoWeeks:Int?,weekKey:String,restMinutes:Int){
        if(restMinutes>=540){minutes=0;previous=twoWeeks;week=weekKey;return}
        if(twoWeeks==null)return
        val before=previous
        if(twoWeeks!=null&&before!=null&&minutes!=null){
            // Weekly rollover or corrected data needs a fresh card, not an invented delta.
            if(week!=weekKey||twoWeeks<before)minutes=null
            else minutes=minutes!!+twoWeeks-before
        }
        previous=twoWeeks;week=weekKey
    }
}
