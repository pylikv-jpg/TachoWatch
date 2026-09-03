from pathlib import Path
import re

p = Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s = p.read_text()

# --- Tabs: robust declarations and automatic binding from tabButton() ---
if 'private var nowTab:Button?=null' not in s:
    s = s.replace(
        'private lateinit var driver:TextView',
        'private lateinit var driver:TextView; private var nowTab:Button?=null; private var historyTab:Button?=null',
        1
    )

s = s.replace(
    'private fun tabButton(t:String)=Button(this).apply{text=t;isAllCaps=false;textSize=14f;setTextColor(TEXT);background=rounded(CARD,dp(11).toFloat(),BORDER)}',
    'private fun tabButton(t:String)=Button(this).apply{text=t;isAllCaps=false;textSize=14f;setTextColor(TEXT);background=rounded(CARD,dp(11).toFloat(),BORDER);if(t=="Сейчас")nowTab=this;if(t=="История")historyTab=this}'
)

# Larger pictograms / labels.
s = s.replace('private fun label(t:String)=TextView(this).apply{text=t;textSize=11.5f;', 'private fun label(t:String)=TextView(this).apply{text=t;textSize=15f;')
s = s.replace('private fun label(t:String)=TextView(this).apply{text=t;textSize=12f;', 'private fun label(t:String)=TextView(this).apply{text=t;textSize=15f;')
s = s.replace('private fun label(t:String):TextView=TextView(this).apply{text=t;textSize=12f;', 'private fun label(t:String):TextView=TextView(this).apply{text=t;textSize=15f;')

helper = r'''
    private fun setTabState(now:Boolean){
        nowTab?.let{
            it.background=rounded(if(now) GREEN else CARD,dp(11).toFloat(),if(now) GREEN else BORDER)
            it.setTextColor(if(now) TEXT else MUTED)
            it.alpha=if(now)1.0f else 0.70f
        }
        historyTab?.let{
            it.background=rounded(if(!now) GREEN else CARD,dp(11).toFloat(),if(!now) GREEN else BORDER)
            it.setTextColor(if(!now) TEXT else MUTED)
            it.alpha=if(!now)1.0f else 0.70f
        }
    }

    private fun updateActiveVisualState(){
        val drive=currentActivity.contains("ВОЖДЕНИЕ")
        val work=currentActivity.contains("РАБОТА") && !drive
        val ready=currentActivity.contains("ГОТОВНОСТЬ") || currentActivity.contains("ОЖИДАНИЕ")
        fun a(v:View,on:Boolean){v.alpha=if(on)1.0f else 0.48f}
        if(::continuousFrame.isInitialized)a(continuousFrame,drive)
        if(::shiftDrivingFrame.isInitialized)a(shiftDrivingFrame,drive)
        if(::work6Frame.isInitialized)a(work6Frame,drive||work)
        if(::otherWorkFrame.isInitialized)a(otherWorkFrame,work)
        if(::availabilityFrame.isInitialized)a(availabilityFrame,ready)
        if(::weekFrame.isInitialized)weekFrame.alpha=0.72f
        if(::twoWeekFrame.isInitialized)twoWeekFrame.alpha=0.72f
    }
'''
if 'private fun setTabState(now:Boolean)' not in s:
    anchor = '    private fun updateShiftDriving()'
    if anchor in s:
        s = s.replace(anchor, helper + '\n' + anchor, 1)
    else:
        m = re.search(r'\bprivate\s+fun\s+updateShiftDriving\s*\(', s)
        if m:
            s = s[:m.start()] + helper + '\n    ' + s[m.start():]

# Active tabs.
s = s.replace(
    'private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE}',
    'private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE;setTabState(true)}'
)
s = s.replace(
    'private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE};',
    'private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE;setTabState(true)};'
)
s = s.replace(
    'private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE}',
    'private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE;setTabState(false)}'
)
s = s.replace(
    'private fun showHistory(){nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE}',
    'private fun showHistory(){nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE;setTabState(false)}'
)

s = s.replace('persistCounters();updateShiftDriving()}', 'persistCounters();updateShiftDriving();updateActiveVisualState()}')

# Ordinary reconnect must not force another full card read.
s = s.replace('.putBoolean(FIRST_READ,false)', '')
s = s.replace('.putBoolean(FIRST_READ, false)', '')

# --- Auto reconnect to last saved DTCO ---
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
            runCatching{adapter.getRemoteDevice(address)}.getOrNull()?.let{
                dtco=it
                status.text="Повторное подключение к DTCO…"
                live.connect(it)
            }
        }
    }
'''
if 'private fun scheduleReconnect()' not in s:
    anchor = '    private val permissionLauncher='
    if anchor in s:
        s = s.replace(anchor, reconnect_helper + '\n' + anchor, 1)

for text in ['DTCO отключён','Соединение потеряно','Отключено','Связь потеряна']:
    old=f'status.text="{text}"'
    new=f'status.text="{text}";scheduleReconnect()'
    s=s.replace(old,new)

# --- History: weekly totals + reduced weekly rest compensation ---
new_history = r'''private fun buildHistoryView(){
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
            if(gap in (24*60) until (45*60)) debts+=Debt(older.date,45*60-gap)
            val extra=when{
                gap>=45*60 -> gap-45*60
                gap>=9*60 -> gap-9*60
                else -> 0
            }
            if(extra>0){
                val unpaid=debts.firstOrNull{it.paidDate==null && it.sourceDate!=older.date && extra>=it.need}
                if(unpaid!=null){
                    unpaid.paidDate=older.date
                    paidOn.getOrPut(older.date){mutableListOf()}.add(unpaid)
                }
            }
        }

        fun weekTotalsAt(date:String):Pair<Int,Int>{
            val d=parseDateOnly(date)?:return 0 to 0
            val cal=isoCalendar(d)
            val w=cal.get(Calendar.WEEK_OF_YEAR); val y=cal.getWeekYear()
            val prev=isoCalendar(d).apply{add(Calendar.WEEK_OF_YEAR,-1)}
            val pw=prev.get(Calendar.WEEK_OF_YEAR); val py=prev.getWeekYear()
            var cur=0; var previous=0
            chronological.forEach{x->
                val xd=parseDateOnly(x.date)?:return@forEach
                val xc=isoCalendar(xd)
                val xw=xc.get(Calendar.WEEK_OF_YEAR); val xy=xc.getWeekYear()
                if(xw==w&&xy==y)cur+=x.drivingMinutes
                if(xw==pw&&xy==py)previous+=x.drivingMinutes
            }
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
                    restBox.addView(TextView(this).apply{
                        text=if(weekly)"🛏  Недельный отдых  ${HistoryData.fmt(gap)}" else "🛏  Межсуточный отдых  ${HistoryData.fmt(gap)}"
                        textSize=17f
                        setTextColor(if(weekly)CYAN else MUTED)
                        setTypeface(typeface,Typeface.BOLD)
                    })
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
                    paidOn[older.date].orEmpty().forEach{d->
                        restBox.addView(sub("✓ Компенсация ${HistoryData.fmt(d.need)} за недельный отдых от ${d.sourceDate} выполнена"))
                    }
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
if n!=1:
    raise SystemExit('Could not replace buildHistoryView; refusing to build partial update')

p.write_text(s)

# Keep all place records. Display event time using the device/tachograph-oriented local zone instead of forcing UTC.
pp=Path('app/src/main/java/com/pylikv/tachowatch/PlacesDecoder.kt')
ps=pp.read_text()
ps=ps.replace('val show = chronological.takeLast(30)','val show = chronological')
ps=ps.replace('ShowingNewest=${show.size}','ShowingAll=${show.size}')
ps=ps.replace('sdf.timeZone = TimeZone.getTimeZone("UTC")','sdf.timeZone = TimeZone.getDefault()')
pp.write_text(ps)

# Bump version for install-over update.
gp=Path('app/build.gradle.kts')
g=gp.read_text()
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 114',g)
g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0-activity-highlight-rest-comp-reconnect-fix2"',g)
gp.write_text(g)
