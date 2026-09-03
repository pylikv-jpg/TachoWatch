from pathlib import Path
import re

p = Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s = p.read_text()

# Keep references to tabs so the active section can be highlighted.
s = s.replace(
    'private lateinit var status:TextView; private lateinit var nowRoot:LinearLayout; private lateinit var historyRoot:LinearLayout; private lateinit var driver:TextView',
    'private lateinit var status:TextView; private lateinit var nowRoot:LinearLayout; private lateinit var historyRoot:LinearLayout; private lateinit var driver:TextView; private lateinit var nowTab:Button; private lateinit var historyTab:Button'
)
s = s.replace(
    'private lateinit var status:TextView; private lateinit var connectButton:Button; private lateinit var nowRoot:LinearLayout; private lateinit var historyRoot:LinearLayout; private lateinit var driver:TextView',
    'private lateinit var status:TextView; private lateinit var connectButton:Button; private lateinit var nowRoot:LinearLayout; private lateinit var historyRoot:LinearLayout; private lateinit var driver:TextView; private lateinit var nowTab:Button; private lateinit var historyTab:Button'
)

# Bind tab buttons in either old or menu-based UI.
s = re.sub(
    r'val tabs=LinearLayout\(this\)\.apply\{orientation=LinearLayout\.HORIZONTAL\};tabs\.addView\(tabButton\("Сейчас"\)\.apply\{setOnClickListener\{showNow\(\)\}\},LinearLayout\.LayoutParams\(0,dp\(42\),1f\)\);tabs\.addView\(hspace\(6\)\);tabs\.addView\(tabButton\("История"\)\.apply\{setOnClickListener\{showHistory\(\)\}\},LinearLayout\.LayoutParams\(0,dp\(42\),1f\)\)',
    'val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};nowTab=tabButton("Сейчас").apply{setOnClickListener{showNow()}};historyTab=tabButton("История").apply{setOnClickListener{showHistory()}};tabs.addView(nowTab,LinearLayout.LayoutParams(0,dp(42),1f));tabs.addView(hspace(6));tabs.addView(historyTab,LinearLayout.LayoutParams(0,dp(42),1f))',
    s
)

# Larger pictograms / labels for the primary activity rows.
s = s.replace('private fun label(t:String)=TextView(this).apply{text=t;textSize=12f;', 'private fun label(t:String)=TextView(this).apply{text=t;textSize=15f;')
s = s.replace('private fun label(t:String):TextView=TextView(this).apply{text=t;textSize=12f;', 'private fun label(t:String):TextView=TextView(this).apply{text=t;textSize=15f;')

# Active tab + active activity-card visual state.
helper = r'''
    private fun setTabState(now:Boolean){
        if(::nowTab.isInitialized){
            nowTab.background=roundRect(if(now) GREEN else CARD,dp(10),if(now) GREEN else BORDER,1)
            nowTab.setTextColor(if(now) TEXT else MUTED)
        }
        if(::historyTab.isInitialized){
            historyTab.background=roundRect(if(!now) GREEN else CARD,dp(10),if(!now) GREEN else BORDER,1)
            historyTab.setTextColor(if(!now) TEXT else MUTED)
        }
    }

    private fun updateActiveVisualState(){
        val drive=currentActivity.contains("ВОЖДЕНИЕ")
        val work=currentActivity.contains("РАБОТА") && !drive
        val ready=currentActivity.contains("ГОТОВНОСТЬ") || currentActivity.contains("ОЖИДАНИЕ")
        val rest=currentActivity.contains("ОТДЫХ")
        fun a(v:View,on:Boolean){v.alpha=if(on) 1.0f else 0.48f}
        if(::continuousFrame.isInitialized)a(continuousFrame,drive)
        if(::shiftDrivingFrame.isInitialized)a(shiftDrivingFrame,drive)
        if(::work6Frame.isInitialized)a(work6Frame,drive||work)
        if(::otherWorkFrame.isInitialized)a(otherWorkFrame,work)
        if(::availabilityFrame.isInitialized)a(availabilityFrame,ready)
        try{ val f=this::class.java.getDeclaredField("restFrame"); f.isAccessible=true; (f.get(this) as? View)?.alpha=if(rest)1.0f else 0.48f }catch(_:Throwable){}
        if(::weekFrame.isInitialized)weekFrame.alpha=0.72f
        if(::twoWeekFrame.isInitialized)twoWeekFrame.alpha=0.72f
    }
'''
if 'private fun setTabState(now:Boolean)' not in s:
    s = s.replace('    private fun updateShiftDriving()', helper + '\n    private fun updateShiftDriving()')

# Replace showNow/showHistory when they are compact one-liners.
s = re.sub(r'private fun showNow\(\)\{nowRoot\.visibility=View\.VISIBLE;historyRoot\.visibility=View\.GONE[^}]*\}', 'private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE;setTabState(true)}', s)
s = re.sub(r'private fun showHistory\(\)\{nowRoot\.visibility=View\.GONE;historyRoot\.visibility=View\.VISIBLE[^}]*\}', 'private fun showHistory(){nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE;setTabState(false)}', s)

# Make sure state refreshes after live-cycle processing.
s = s.replace('persistCounters();updateShiftDriving()}', 'persistCounters();updateShiftDriving();updateActiveVisualState()}')

# Do not mark the card unread merely because the user selected/reconnected to the same DTCO.
s = s.replace('.putBoolean(FIRST_READ,false)', '')
s = s.replace('.putBoolean(FIRST_READ, false)', '')

# Auto-reconnect: if live diagnostics disconnect, retry the previously saved DTCO periodically.
# Insert helper and hook common disconnect callbacks if present.
reconnect_helper = r'''
    private fun scheduleReconnect(){
        if(isFinishing||isDestroyed)return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable,3000)
    }
    private val reconnectRunnable=Runnable{
        if(isFinishing||isDestroyed)return@Runnable
        val address=prefs.getString(SELECTED_DTCO,null)
        if(address!=null){
            runCatching{adapter.getRemoteDevice(address)}.getOrNull()?.let{dtco=it;startLive(it)}
        }
    }
'''
if 'private fun scheduleReconnect()' not in s:
    anchor = '    private val permissionLauncher='
    s = s.replace(anchor, reconnect_helper + '\n' + anchor)

# Best-effort hook for listener status strings used by current implementation.
s = s.replace('status.text="DTCO отключён"', 'status.text="DTCO отключён";scheduleReconnect()')
s = s.replace('status.text="Соединение потеряно"', 'status.text="Соединение потеряно";scheduleReconnect()')
s = s.replace('status.text="Отключено"', 'status.text="Отключено";scheduleReconnect()')

# History rendering: weekly driving totals, compensation debt/status and more explicit rest rows.
new_history = r'''    private fun buildHistoryView(){
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
        val restAfter=mutableMapOf<String,Int>()
        for(i in 0 until chronological.lastIndex){
            val older=chronological[i]
            val newer=chronological[i+1]
            val gap=HistoryData.gapMinutes(older,newer)?:continue
            restAfter[older.date]=gap
            if(gap in (24*60) until (45*60)) debts+=Debt(older.date,45*60-gap)
            val eligibleExtra=when{
                gap>=45*60 -> gap-45*60
                gap>=9*60 -> gap-9*60
                else -> 0
            }
            if(eligibleExtra>0){
                val unpaid=debts.firstOrNull{it.paidDate==null && it.sourceDate!=older.date && eligibleExtra>=it.need}
                if(unpaid!=null){unpaid.paidDate=older.date;paidOn.getOrPut(older.date){mutableListOf()}.add(unpaid)}
            }
        }

        fun weekTotalsAt(date:String):Pair<Int,Int>{
            val d=parseDateOnly(date)?:return 0 to 0
            val cal=isoCalendar(d)
            val w=cal.get(Calendar.WEEK_OF_YEAR);val y=cal.getWeekYear()
            val prev=isoCalendar(d).apply{add(Calendar.WEEK_OF_YEAR,-1)}
            val pw=prev.get(Calendar.WEEK_OF_YEAR);val py=prev.getWeekYear()
            var cur=0;var previous=0
            chronological.forEach{x->val xd=parseDateOnly(x.date)?:return@forEach;val xc=isoCalendar(xd);val xw=xc.get(Calendar.WEEK_OF_YEAR);val xy=xc.getWeekYear();if(xw==w&&xy==y)cur+=x.drivingMinutes;if(xw==pw&&xy==py)previous+=x.drivingMinutes}
            return cur to (cur+previous)
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
                    restBox.addView(TextView(this).apply{text=if(weekly)"🛏  Недельный отдых  ${HistoryData.fmt(gap)}" else "🛏  Межсуточный отдых  ${HistoryData.fmt(gap)}";textSize=17f;setTextColor(if(weekly)CYAN else MUTED);setTypeface(typeface,Typeface.BOLD)})
                    if(weekly){
                        val totals=weekTotalsAt(older.date)
                        restBox.addView(sub("🚗  Вождение за неделю: ${HistoryData.fmt(totals.first)}"))
                        restBox.addView(sub("🚗  Вождение за две недели: ${HistoryData.fmt(totals.second)}"))
                        val debt=debts.firstOrNull{it.sourceDate==older.date}
                        when{
                            gap>=45*60 -> restBox.addView(sub("✓ Компенсация не требуется"))
                            debt?.paidDate!=null -> restBox.addView(sub("✓ Компенсация ${HistoryData.fmt(debt.need)} выполнена ${debt.paidDate}"))
                            debt!=null -> restBox.addView(sub("⚠ Требуется компенсация ${HistoryData.fmt(debt.need)}"))
                        }
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
s, n = re.subn(r'    private fun buildHistoryView\(\)\{.*?\n    private fun loadHistory', new_history + '    private fun loadHistory', s, count=1, flags=re.S)
print('history function replaced', n)

p.write_text(s)

# Places: keep all records and display event time in device/tachograph-oriented local zone rather than forcing UTC.
pp=Path('app/src/main/java/com/pylikv/tachowatch/PlacesDecoder.kt')
ps=pp.read_text().replace('val show = chronological.takeLast(30)','val show = chronological').replace('ShowingNewest=${show.size}','ShowingAll=${show.size}')
ps=ps.replace('sdf.timeZone = TimeZone.getTimeZone("UTC")','sdf.timeZone = TimeZone.getDefault()')
pp.write_text(ps)

# Bump version for install-over update.
gp=Path('app/build.gradle.kts')
g=gp.read_text()
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 112',g)
g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0-activity-highlight-rest-comp-reconnect"',g)
gp.write_text(g)
