package com.pylikv.tachowatch

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TlvInventory {
    data class Entry(val index:Int,val offset:Int,val fid:Int,val suffix:Int,val length:Int)
    data class Result(val file:File,val bytes:Int,val entries:List<Entry>,val parsedBytes:Int,val error:String?=null)
    private data class ActivityChange(val raw:Int,val minute:Int,val activity:Int,val slot:Int,val crew:Boolean,val cardInserted:Boolean)
    private data class DailyRecord(val offset:Int,val previousLength:Int,val recordLength:Int,val dateSeconds:Long,val presenceCounter:Int,val distanceKm:Int,val changes:List<ActivityChange>)

    fun findLatestDdd(dir:File?):File?=dir?.takeIf{it.exists()}?.listFiles()?.filter{it.isFile&&it.name.lowercase(Locale.US).endsWith(".ddd")}?.maxByOrNull{it.lastModified()}

    fun parse(file:File):Result{
        val data=try{file.readBytes()}catch(e:Throwable){return Result(file,0,emptyList(),0,"Не удалось прочитать файл: ${e.message}")}
        val out=ArrayList<Entry>();var p=0;var index=1
        while(p+5<=data.size){val fid=(u(data[p]) shl 8) or u(data[p+1]);val suffix=u(data[p+2]);val len=(u(data[p+3]) shl 8) or u(data[p+4]);val end=p+5+len
            if(end>data.size)return Result(file,data.size,out,p,"Неполная TLV-запись #$index: offset=$p FID=${hex4(fid)} suffix=${hex2(suffix)} len=$len, осталось=${data.size-p}")
            out+=Entry(index,p,fid,suffix,len);p=end;index++}
        return Result(file,data.size,out,p,if(p!=data.size)"После TLV осталось ${data.size-p} байт" else null)
    }

    fun render(result:Result):String{
        val sb=StringBuilder();sb.appendLine("===== TEST-12 TLV INVENTORY =====");sb.appendLine("File=${result.file.name}");sb.appendLine("Bytes=${result.bytes}");sb.appendLine("TLVRecords=${result.entries.size}");sb.appendLine("ParsedBytes=${result.parsedBytes}");sb.appendLine("ParseStatus=${if(result.error==null&&result.parsedBytes==result.bytes)"OK" else "CHECK"}");result.error?.let{sb.appendLine("ParseError=$it")};sb.appendLine("--------------------------------")
        result.entries.forEach{e->sb.appendLine(String.format(Locale.US,"#%02d offset=%06d FID=%04X suffix=%02X len=%d",e.index,e.offset,e.fid,e.suffix,e.length))}
        sb.appendLine("--------------------------------");val grouped=result.entries.groupBy{Pair(it.fid,it.suffix)};sb.appendLine("UniqueTags=${grouped.size}");grouped.toSortedMap(compareBy<Pair<Int,Int>>({it.first},{it.second})).forEach{(k,list)->sb.appendLine(String.format(Locale.US,"TAG FID=%04X suffix=%02X count=%d dataBytes=%d",k.first,k.second,list.size,list.sumOf{it.length}))};sb.appendLine("===== END TEST-12 INVENTORY =====");sb.appendLine();sb.append(renderDriverActivity(result));return sb.toString()
    }

    private fun renderDriverActivity(result:Result):String{
        val sb=StringBuilder();sb.appendLine("===== TEST-13 CARD ACTIVITY DECODER =====");sb.appendLine("Source=${result.file.name}")
        val allData=try{result.file.readBytes()}catch(e:Throwable){sb.appendLine("ERROR=Cannot read DDD: ${e.message}");sb.appendLine("===== END TEST-13 =====");return sb.toString()}
        val entries=result.entries.filter{it.fid==0x0504&&(it.suffix==0x00||it.suffix==0x02)}
        if(entries.isEmpty()){sb.appendLine("ERROR=FID 0504 suffix 00/02 not found");sb.appendLine("===== END TEST-13 =====");return sb.toString()}
        entries.forEach{entry->
            sb.appendLine("--------------------------------");sb.appendLine("FID=0504 suffix=${hex2(entry.suffix)} TLVoffset=${entry.offset} dataLen=${entry.length}")
            val start=entry.offset+5;val end=start+entry.length;if(start<0||end>allData.size||entry.length<16){sb.appendLine("STATUS=INVALID_TLV_RANGE");return@forEach}
            val payload=allData.copyOfRange(start,end);val oldest=be16(payload,0);val newest=be16(payload,2);val buffer=payload.copyOfRange(4,payload.size)
            sb.appendLine("OldestPointer=$oldest");sb.appendLine("NewestPointer=$newest");sb.appendLine("ActivityBufferBytes=${buffer.size}");sb.appendLine("HeaderHex=${hexDump(payload,0,minOf(payload.size,32))}")
            if(oldest !in buffer.indices||newest !in buffer.indices){sb.appendLine("STATUS=POINTER_OUT_OF_RANGE");return@forEach}
            val records=decodeCircularRecords(buffer,oldest,newest);if(records.isEmpty()){sb.appendLine("STATUS=NO_VALID_DAILY_RECORDS");return@forEach}
            val shown=minOf(56,records.size);sb.appendLine("DecodedDailyRecords=${records.size}");sb.appendLine("ShowingNewest=$shown");sb.appendLine()
            records.takeLast(56).forEachIndexed{idx,r->
                val absoluteNo=records.size-shown+idx+1;sb.appendLine("DAY#$absoluteNo offset=${r.offset} date=${formatDate(r.dateSeconds)} prevLen=${r.previousLength} len=${r.recordLength} presence=${r.presenceCounter} distanceKm=${r.distanceKm} changes=${r.changes.size}")
                if(r.changes.isEmpty())sb.appendLine("  (no activity changes)") else r.changes.forEachIndexed{ci,ch->val next=r.changes.getOrNull(ci+1)?.minute;val dur=if(next!=null&&next>=ch.minute)" duration=${formatMinutes(next-ch.minute)}" else " duration=OPEN";sb.appendLine("  ${formatClock(ch.minute)} ${activityName(ch.activity)} slot=${ch.slot} ${if(ch.crew)"CREW" else "SINGLE"} card=${if(ch.cardInserted)"IN" else "OUT"} raw=${hex4(ch.raw)}$dur")};sb.appendLine()
            };sb.appendLine("STATUS=OK")
        }
        sb.appendLine("Legend: REST=отдых/перерыв, AVAILABILITY=готовность, WORK=другая работа, DRIVING=вождение");sb.appendLine("NOTE=duration for the last change of each day is OPEN intentionally; TEST-13 does not invent an end time.");sb.appendLine("===== END TEST-13 =====");return sb.toString()
    }

    private fun decodeCircularRecords(buffer:ByteArray,oldest:Int,newest:Int):List<DailyRecord>{val out=ArrayList<DailyRecord>();var offset=oldest;val visited=HashSet<Int>();repeat(500){if(offset !in buffer.indices||!visited.add(offset))return out;val header=readCyclic(buffer,offset,12)?:return out;val prev=be16(header,0);val len=be16(header,2);if(len<12||len>buffer.size||len%2!=0)return out;val raw=readCyclic(buffer,offset,len)?:return out;val date=be32(raw,4);if(date==0L)return out;val changes=ArrayList<ActivityChange>();var p=12;var last=-1;while(p+1<raw.size){val v=be16(raw,p);val minute=v and 0x07FF;if(minute !in 0..1439||last>minute)break;changes+=ActivityChange(v,minute,(v ushr 11) and 3,if(((v ushr 15) and 1)==0)1 else 2,((v ushr 14) and 1)!=0,((v ushr 13) and 1)==0);last=minute;p+=2};out+=DailyRecord(offset,prev,len,date,be16(raw,8),be16(raw,10),changes);if(offset==newest)return out;offset=(offset+len)%buffer.size};return out}
    private fun readCyclic(b:ByteArray,o:Int,l:Int):ByteArray?{if(b.isEmpty()||o !in b.indices||l<0||l>b.size)return null;return ByteArray(l){i->b[(o+i)%b.size]}}
    private fun formatDate(s:Long)=try{SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date(s*1000L))}catch(_:Throwable){"INVALID($s)"}
    private fun formatClock(m:Int)=String.format(Locale.US,"%02d:%02d",m/60,m%60);private fun formatMinutes(m:Int)=String.format(Locale.US,"%d:%02d",m/60,m%60)
    private fun activityName(a:Int)=when(a){0->"REST";1->"AVAILABILITY";2->"WORK";3->"DRIVING";else->"UNKNOWN"}
    private fun hexDump(d:ByteArray,s:Int,l:Int)=if(s !in 0..d.size||l<=0)"" else (s until minOf(d.size,s+l)).joinToString(" "){hex2(u(d[it]))}
    private fun be16(d:ByteArray,o:Int)=if(o<0||o+1>=d.size)0 else (u(d[o]) shl 8) or u(d[o+1]);private fun be32(d:ByteArray,o:Int)=if(o<0||o+3>=d.size)0L else (u(d[o]).toLong() shl 24) or (u(d[o+1]).toLong() shl 16) or (u(d[o+2]).toLong() shl 8) or u(d[o+3]).toLong()
    private fun u(b:Byte)=b.toInt() and 0xFF;private fun hex2(v:Int)=String.format(Locale.US,"%02X",v and 0xFF);private fun hex4(v:Int)=String.format(Locale.US,"%04X",v and 0xFFFF)
}
