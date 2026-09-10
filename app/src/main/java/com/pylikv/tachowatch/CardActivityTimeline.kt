package com.pylikv.tachowatch

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Activity timestamps, independent of country-entry timestamps and old app counters. */
object CardActivityTimeline {
    data class Period(val start:Long,val end:Long,val kind:String){val minutes:Int get()=((end-start)/60000).toInt()}
    data class Snapshot(val periods:List<Period>,val latestDate:String?,val capturedAt:Long){
        val shiftStart:Long? get(){
            val boundary=periods.indexOfLast{it.kind=="REST"&&it.minutes>=540}
            return if(boundary>=0)periods.getOrNull(boundary+1)?.start else null
        }
        val lastWeeklyRestEnd:Long? get()=periods.lastOrNull{it.kind=="REST"&&it.minutes>=1440&&it.end<capturedAt}?.end
        val shiftDriving:Int? get(){
            val today=Instant.ofEpochMilli(capturedAt).atOffset(ZoneOffset.UTC).toLocalDate().toString()
            if(latestDate!=today)return null
            val boundary=periods.indexOfLast{it.kind=="REST"&&it.minutes>=540}
            if(boundary<0)return null // Do not invent the unseen beginning of a shift.
            return periods.drop(boundary+1).filter{it.kind=="DRIVING"}.sumOf{it.minutes}
        }
    }
    fun parse(text:String,capturedAt:Long):Snapshot{
        val days=linkedMapOf<String,List<Pair<Int,String>>>()
        val pattern=Regex("(?ms)^DAY#\\d+[^\\n]*date=(\\d{4}-\\d{2}-\\d{2})[^\\n]*\\n(.*?)(?=^DAY#|^STATUS=)")
        pattern.findAll(text).forEach{m->
            val rows=m.groupValues[2].lines().mapNotNull{line->
                val r=Regex("^\\s*(\\d{2}):(\\d{2}) (REST|AVAILABILITY|WORK|DRIVING)\\b").find(line)?:return@mapNotNull null
                Pair(r.groupValues[1].toInt()*60+r.groupValues[2].toInt(),r.groupValues[3])
            }.distinctBy{it.first}.sortedBy{it.first}
            val date=m.groupValues[1]
            if(rows.size>=(days[date]?.size?:0))days[date]=rows
        }
        val periods=mutableListOf<Period>()
        days.toSortedMap().forEach{(date,rows)->
            val midnight=LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            rows.forEachIndexed{i,row->
                val start=midnight+row.first*60000L
                val end=minOf(capturedAt,midnight+(rows.getOrNull(i+1)?.first?:1440)*60000L)
                if(end>start){
                    val previous=periods.lastOrNull()
                    if(previous!=null&&previous.end==start&&previous.kind==row.second)periods[periods.lastIndex]=previous.copy(end=end)
                    else periods+=Period(start,end,row.second)
                }
            }
        }
        return Snapshot(periods,days.keys.maxOrNull(),capturedAt)
    }
}
