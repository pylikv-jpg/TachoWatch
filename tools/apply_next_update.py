from pathlib import Path
import re

p = Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s = p.read_text()

# Tabs: retain references and show which screen is active.
if 'private var nowTab:Button?=null' not in s:
    s = s.replace('private lateinit var driver:TextView', 'private lateinit var driver:TextView; private var nowTab:Button?=null; private var historyTab:Button?=null', 1)

s = s.replace(
    'private fun tabButton(t:String)=Button(this).apply{text=t;isAllCaps=false;textSize=14f;setTextColor(TEXT);background=rounded(CARD,dp(11).toFloat(),BORDER)}',
    'private fun tabButton(t:String)=Button(this).apply{text=t;isAllCaps=false;textSize=14f;setTextColor(TEXT);background=rounded(CARD,dp(11).toFloat(),BORDER);if(t=="Сейчас")nowTab=this;if(t=="История")historyTab=this}'
)

# Make the dashboard pictograms / activity headers visibly larger.
s = s.replace('private fun label(t:String)=TextView(this).apply{text=t;textSize=11.5f;', 'private fun label(t:String)=TextView(this).apply{text=t;textSize=16.5f;')
s = s.replace('private fun label(t:String)=TextView(this).apply{text=t;textSize=12f;', 'private fun label(t:String)=TextView(this).apply{text=t;textSize=16.5f;')
s = s.replace('private fun label(t:String):TextView=TextView(this).apply{text=t;textSize=12f;', 'private fun label(t:String):TextView=TextView(this).apply{text=t;textSize=16.5f;')

helper = r'''
    private fun setTabState(now:Boolean){
        nowTab?.let{
            it.background=rounded(if(now) GREEN else CARD,dp(11).toFloat(),if(now) GREEN else BORDER)
            it.setTextColor(if(now) TEXT else MUTED)
            it.alpha=if(now)1.0f else 0.68f
        }
        historyTab?.let{
            it.background=rounded(if(!now) GREEN else CARD,dp(11).toFloat(),if(!now) GREEN else BORDER)
            it.setTextColor(if(!now) TEXT else MUTED)
            it.alpha=if(!now)1.0f else 0.68f
        }
    }

    private fun updateActiveVisualState(){
        val drive=currentActivity.contains("ВОЖДЕНИЕ")
        val work=currentActivity.contains("РАБОТА") && !drive
        val ready=currentActivity.contains("ГОТОВНОСТЬ") || currentActivity.contains("ОЖИДАНИЕ")
        val rest=currentActivity.contains("ОТДЫХ")
        fun a(v:View,on:Boolean){v.alpha=if(on)1.0f else 0.46f}
        if(::continuousFrame.isInitialized)a(continuousFrame,drive)
        if(::shiftDrivingFrame.isInitialized)a(shiftDrivingFrame,drive)
        if(::work6Frame.isInitialized)a(work6Frame,drive||work)
        if(::otherWorkFrame.isInitialized)a(otherWorkFrame,work)
        if(::availabilityFrame.isInitialized)a(availabilityFrame,ready)
        if(::weekFrame.isInitialized)weekFrame.alpha=0.70f
        if(::twoWeekFrame.isInitialized)twoWeekFrame.alpha=0.70f
        // Rest card is present in build 158 under a separate field; use reflection so this stays compatible.
        try{
            val f=this::class.java.declaredFields.firstOrNull{it.name=="restFrame"}
            f?.isAccessible=true
            (f?.get(this) as? View)?.alpha=if(rest)1.0f else 0.46f
        }catch(_:Throwable){}
    }
'''
if 'private fun setTabState(now:Boolean)' not in s:
    anchor='    private fun updateShiftDriving()'
    if anchor in s:
        s=s.replace(anchor,helper+'\n'+anchor,1)
    else:
        raise SystemExit('updateShiftDriving anchor not found')

# Active tab highlight and initial state.
s=s.replace('private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE}', 'private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE;setTabState(true)}')
s=s.replace('private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE};', 'private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE;setTabState(true)};')
s=s.replace('private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE}', 'private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE;setTabState(false)}')
s=s.replace('private fun showHistory(){nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE}', 'private fun showHistory(){nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE;setTabState(false)}')
s=s.replace('setContentView(root);buildNow();buildHistoryView()', 'setContentView(root);buildNow();buildHistoryView();setTabState(true)',1)

# Reset continuous-work window as soon as a real 45-minute rest is reached.
old_cycle='private fun processCycle(){initializeShiftCounterFromCard();if(previousContinuousMinutes>0&&continuousMinutes<previousContinuousMinutes&&breakMinutes>=45)shiftCompletedMinutes+=previousContinuousMinutes;previousContinuousMinutes=continuousMinutes;processWorkWindow();persistCounters();updateShiftDriving()}'
new_cycle='private fun processCycle(){initializeShiftCounterFromCard();if(previousContinuousMinutes>0&&continuousMinutes<previousContinuousMinutes&&breakMinutes>=45)shiftCompletedMinutes+=previousContinuousMinutes;previousContinuousMinutes=continuousMinutes;if(currentActivity.contains("ОТДЫХ")&&maxOf(breakMinutes,activityMinutes)>=45){workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;previousActivity=currentActivity;previousActivityDuration=activityMinutes}else processWorkWindow();persistCounters();updateShiftDriving();updateActiveVisualState()}'
if old_cycle in s:
    s=s.replace(old_cycle,new_cycle,1)
else:
    s=s.replace('if(currentActivity.contains("ОТДЫХ")&&breakMinutes>=45){workWindowMinutes=0;', 'if(currentActivity.contains("ОТДЫХ")&&maxOf(breakMinutes,activityMinutes)>=45){workWindowMinutes=0;')
    s=s.replace('persistCounters();updateShiftDriving()}', 'persistCounters();updateShiftDriving();updateActiveVisualState()}')

# Ordinary reconnect must not trigger a new full card read.
s=s.replace('.putBoolean(FIRST_READ,false)','').replace('.putBoolean(FIRST_READ, false)','')

# Auto reconnect to the last saved DTCO.
reconnect_helper=r'''
    private fun scheduleReconnect(){
        if(isFinishing||isDestroyed)return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable,3000)
    }
    private val reconnectRunnable=Runnable{
        if(isFinishing||isDestroyed)return@Runnable
        val address=prefs.getString(SELECTED_DTCO,null)
        if(address!=null){
            runCatching{adapter.getRemoteDevice(address)}.getOrNull()?.let{
                dtco=it
                status.text="Повторное подключение к DTCO…"
                live.connect(it)
            }
        }
    }
'''
if 'private fun scheduleReconnect()' not in s:
    anchor='    private val permissionLauncher='
    if anchor in s:s=s.replace(anchor,reconnect_helper+'\n'+anchor,1)
for text in ['DTCO отключён','Соединение потеряно','Отключено','Связь потеряна']:
    s=s.replace(f'status.text="{text}"',f'status.text="{text}";scheduleReconnect()')

# History screen: 56 records, colored rest windows, split 3h+daily-rest notation,
# weekly driving totals and reduced-weekly-rest compensation.
new_history=r'''private fun buildHistoryView(){
        historyRoot.removeAllViews()
        val scroll=ScrollView(this)
        val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val chronological=history?.days?.takeLast(56).orEmpty()
        val days=chronological.asReversed()
        c.addView(sub("История карты: период 56 дней • записей ${days.size}"))
        if(days.isEmpty())c.addView(value("История появится после считывания карты",17f))

        data class Debt(val sourceDate:String,val need:Int,var paidDate:String?=null)
        val debts=mutableListOf<Debt>()
        val paidOn=mutableMapOf<String,MutableList<Debt>>()
        for(i in 0 until chronological.lastIndex){
            val older=chronological[i]
            val newer=chronological[i+1]
            val gap=HistoryData.gapMinutes(older,newer)?:continue
            if(gap in (24*60) until (45*60))debts+=Debt(older.date,45*60-gap)
            val extra=when{gap>=45*60->gap-45*60;gap>=9*60->gap-9*60;else->0}
            if(extra>0){
                val unpaid=debts.firstOrNull{it.paidDate==null&&it.sourceDate!=older.date&&extra>=it.need}
                if(unpaid!=null){unpaid.paidDate=older.date;paidOn.getOrPut(older.date){mutableListOf()}.add(unpaid)}
            }
        }

        fun weekTotalsAt(date:String):Pair<Int,Int>{
            val d=parseDateOnly(date)?:return 0 to 0
            val cal=isoCalendar(d);val w=cal.get(Calendar.WEEK_OF_YEAR);val y=cal.getWeekYear()
            val prev=isoCalendar(d).apply{add(Calendar.WEEK_OF_YEAR,-1)};val pw=prev.get(Calendar.WEEK_OF_YEAR);val py=prev.getWeekYear()
            var cur=0;var previous=0
            chronological.forEach{x->val xd=parseDateOnly(x.date)?:return@forEach;val xc=isoCalendar(xd);val xw=xc.get(Calendar.WEEK_OF_YEAR);val xy=xc.getWeekYear();if(xw==w&&xy==y)cur+=x.drivingMinutes;if(xw==pw&&xy==py)previous+=x.drivingMinutes}
            return cur to (cur+previous)
        }
        fun splitRestText(older:HistoryData.Day,gap:Int):String{
            val inside=older.longestRestMinutes
            if(inside<180)return HistoryData.fmt(gap)
            val prefix=if(inside%60==0)"${inside/60}" else HistoryData.fmt(inside)
            return "$prefix + ${HistoryData.fmt(gap)}"
        }

        days.forEachIndexed{i,day->
            val box=card()
            box.addView(TextView(this).apply{text=prettyDate(day.date);textSize=18f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD)})
            box.addView(sub("${flag(day.startCountry)} ${day.startCountry?:"—"}  Начало смены  ${day.startTime?:"—"}"))
            box.addView(value("🚗  Вождение  ${HistoryData.fmt(day.drivingMinutes)}",18f))
            box.addView(sub("⚒  Другая работа  ${HistoryData.fmt(day.workMinutes)}"))
            box.addView(sub("✉  Готовность  ${HistoryData.fmt(day.availabilityMinutes)}"))
            box.addView(sub("Продолжительность смены  ${day.shiftMinutes?.let(HistoryData::fmt)?:"—"}"))
            box.addView(sub("${flag(day.endCountry)} ${day.endCountry?:"—"}  Конец смены  ${day.endTime?:"—"}"))
            c.addView(box)

            if(i<days.lastIndex){
                val older=days[i+1]
                HistoryData.gapMinutes(older,day)?.let{gap->
                    val weekly=gap>=24*60
                    val restBox=card()
                    restBox.background=rounded(if(weekly)0xFF17302B.toInt() else 0xFF182634.toInt(),dp(14).toFloat(),if(weekly)GREEN else CYAN)
                    restBox.addView(TextView(this).apply{
                        text=if(weekly)"🛏  Недельный отдых  ${HistoryData.fmt(gap)}" else "🛏  Межсуточный отдых  ${splitRestText(older,gap)}"
                        textSize=17f;setTextColor(if(weekly)0xFF8FE0B0.toInt() else 0xFF8FDCEA.toInt());setTypeface(typeface,Typeface.BOLD)
                    })
                    if(weekly){
                        val totals=weekTotalsAt(older.date)
                        restBox.addView(sub("🚗  Вождение за неделю: ${HistoryData.fmt(totals.first)}"))
                        restBox.addView(sub("🚗  Вождение за две недели: ${HistoryData.fmt(totals.second)}"))
                        val debt=debts.firstOrNull{it.sourceDate==older.date}
                        when{gap>=45*60->restBox.addView(sub("✓ Компенсация не требуется"));debt?.paidDate!=null->restBox.addView(sub("✓ Компенсация ${HistoryData.fmt(debt.need)} выполнена ${debt.paidDate}"));debt!=null->restBox.addView(sub("⚠ Требуется компенсация ${HistoryData.fmt(debt.need)}"))}
                    }
                    paidOn[older.date].orEmpty().forEach{d->restBox.addView(sub("✓ Компенсация ${HistoryData.fmt(d.need)} за недельный отдых от ${d.sourceDate} выполнена"))}
                    c.addView(restBox)
                }
            }
        }
        scroll.addView(c)
        historyRoot.addView(scroll,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.MATCH_PARENT))
        setTabState(historyRoot.visibility==View.GONE)
    }

    '''
pattern=r'(?s)\bprivate\s+fun\s+buildHistoryView\s*\(\s*\)\s*\{.*?(?=\bprivate\s+fun\s+loadHistory\b)'
s,n=re.subn(pattern,new_history,s,count=1)
print('history function replaced',n)
if n!=1:raise SystemExit('Could not replace buildHistoryView; refusing partial update')
p.write_text(s)

# Replace HistoryData with a 56-day model that keeps pure-rest days and the longest in-shift rest.
history_code=r'''package com.pylikv.tachowatch

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object HistoryData {
    data class Day(
        val date:String,
        val drivingMinutes:Int,
        val workMinutes:Int,
        val availabilityMinutes:Int,
        val restMinutes:Int,
        val longestRestMinutes:Int,
        val startTime:String?,
        val startCountry:String?,
        val endTime:String?,
        val endCountry:String?
    ){
        val shiftMinutes:Int? get(){val s=startTime?.let(::clockMinutes)?:return null;val e=endTime?.let(::clockMinutes)?:return null;return if(e>=s)e-s else e+1440-s}
    }
    data class Model(val days:List<Day>,val previousWeekDrivingMinutes:Int,val currentWeekCardMinutes:Int)
    private data class Place(val date:String,val time:String,val type:String,val country:String)
    private data class Totals(var driving:Int=0,var work:Int=0,var availability:Int=0,var rest:Int=0,var longestRest:Int=0)

    fun load(result:TlvInventory.Result):Model{
        val activity=TlvInventory.render(result);val placesText=PlacesDecoder.render(result)
        val matches=Regex("(?ms)^DAY#\\d+.*?date=(\\d{4}-\\d{2}-\\d{2}).*?\\n(.*?)(?=^DAY#|^STATUS=)").findAll(activity).toList()
        val totals=linkedMapOf<String,Totals>()
        matches.takeLast(56).forEach{m->
            val t=totals.getOrPut(m.groupValues[1]){Totals()}
            m.groupValues[2].lines().forEach{line->
                val dur=Regex("duration=(\\d+):(\\d{2})").find(line)?:return@forEach
                val min=(dur.groupValues[1].toIntOrNull()?:0)*60+(dur.groupValues[2].toIntOrNull()?:0)
                when{line.contains(" DRIVING ")->t.driving+=min;line.contains(" WORK ")->t.work+=min;line.contains(" AVAILABILITY ")->t.availability+=min;line.contains(" REST ")->{t.rest+=min;t.longestRest=maxOf(t.longestRest,min)}}
            }
        }
        val places=Regex("(?m)^#\\d+ time=(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}):\\d{2} type=([^ ]+) country=([^ ]+)").findAll(placesText).map{Place(it.groupValues[1],it.groupValues[2],it.groupValues[3],it.groupValues[4].substringBefore('['))}.distinctBy{listOf(it.date,it.time,it.type,it.country)}.toList()
        val days=totals.map{(date,t)->val dp=places.filter{it.date==date};val start=dp.firstOrNull{it.type.startsWith("BEGIN_")};val end=dp.lastOrNull{it.type.startsWith("END_")};Day(date,t.driving,t.work,t.availability,t.rest,t.longestRest,start?.time,start?.country,end?.time,end?.country)}.filter{it.startTime!=null||it.endTime!=null||it.drivingMinutes>0||it.workMinutes>0||it.availabilityMinutes>0||it.restMinutes>0}.sortedBy{it.date}.takeLast(56)
        val now=Date();val cc=isoCalendar(now);val pc=isoCalendar(now).apply{add(Calendar.WEEK_OF_YEAR,-1)};var current=0;var previous=0
        days.forEach{d->val date=parseDate(d.date)?:return@forEach;val c=isoCalendar(date);if(c.get(Calendar.WEEK_OF_YEAR)==cc.get(Calendar.WEEK_OF_YEAR)&&c.getWeekYear()==cc.getWeekYear())current+=d.drivingMinutes;if(c.get(Calendar.WEEK_OF_YEAR)==pc.get(Calendar.WEEK_OF_YEAR)&&c.getWeekYear()==pc.getWeekYear())previous+=d.drivingMinutes}
        return Model(days,previous,current)
    }
    fun gapMinutes(a:Day,b:Day):Int?{val end=a.endTime?:return null;val start=b.startTime?:return null;val sdf=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).apply{timeZone=TimeZone.getDefault()};val t1=runCatching{sdf.parse("${a.date} $end")}.getOrNull()?:return null;val t2=runCatching{sdf.parse("${b.date} $start")}.getOrNull()?:return null;return((t2.time-t1.time)/60000L).toInt().takeIf{it>=0}}
    fun fmt(min:Int)=String.format(Locale.US,"%d:%02d",min/60,min%60)
    private fun isoCalendar(date:Date)=Calendar.getInstance(TimeZone.getDefault(),Locale.US).apply{firstDayOfWeek=Calendar.MONDAY;minimalDaysInFirstWeek=4;time=date}
    private fun clockMinutes(v:String)=v.substringBefore(':').toInt()*60+v.substringAfter(':').toInt()
    private fun parseDate(v:String)=runCatching{SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{timeZone=TimeZone.getDefault()}.parse(v)}.getOrNull()
}
'''
Path('app/src/main/java/com/pylikv/tachowatch/HistoryData.kt').write_text(history_code)

# Places: all records, no 30-record truncation, display in device/local time.
pp=Path('app/src/main/java/com/pylikv/tachowatch/PlacesDecoder.kt')
ps=pp.read_text().replace('val show = chronological.takeLast(30)','val show = chronological').replace('ShowingNewest=${show.size}','ShowingAll=${show.size}').replace('sdf.timeZone = TimeZone.getTimeZone("UTC")','sdf.timeZone = TimeZone.getDefault()')
pp.write_text(ps)

# Version bump.
gp=Path('app/build.gradle.kts');g=gp.read_text();g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 115',g);g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0-history56-split-rest-ui"',g);gp.write_text(g)
