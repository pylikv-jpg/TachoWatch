package com.pylikv.tachowatch

/** F927 can temporarily reset around ignition. Commit a new activity only after it settles. */
class WorkActivityCounter {
    var kind="—"; private set
    var duration=0; private set
    var completedWork=0; private set
    var completedOther=0; private set
    var completedAvailability=0; private set
    private var candidate:String?=null
    private var candidateAt=0L
    private var dropAt:Long?=null
    val work:Int get()=completedWork+if(kind=="WORK"||kind=="DRIVING")duration else 0
    val other:Int get()=completedOther+if(kind=="WORK")duration else 0
    val availability:Int get()=completedAvailability+if(kind=="AVAILABILITY")duration else 0
    fun seed(work:Int,other:Int,availability:Int,activity:String,minutes:Int){
        completedWork=work;completedOther=other;completedAvailability=availability
        kind=activity;duration=minutes;candidate=null;dropAt=null
    }
    fun update(activity:String,minutes:Int,now:Long,breakComplete:Boolean=false){
        val value=minutes.coerceAtLeast(0)
        // Only a measured current rest can reset work; no zero-duration ignition sample qualifies.
        if(breakComplete){seed(0,0,0,activity,value);return}
        if(kind=="—"){kind=activity;duration=value;return}
        if(activity==kind){
            candidate=null
            if(value>=duration){duration=value;dropAt=null;return}
            // Keep the previous duration while the tachograph restores its counter.
            val since=dropAt
            if(since==null){dropAt=now;return}
            if(now-since<60000)return
            finish();duration=value;dropAt=null
            return
        }
        dropAt=null
        if(candidate!=activity){candidate=activity;candidateAt=now;return}
        if(now-candidateAt<60000)return
        finish();kind=activity;duration=value;candidate=null
    }
    private fun finish(){
        when(kind){
            "WORK"->{completedWork+=duration;completedOther+=duration}
            "DRIVING"->completedWork+=duration
            "AVAILABILITY"->completedAvailability+=duration
        }
    }
}
