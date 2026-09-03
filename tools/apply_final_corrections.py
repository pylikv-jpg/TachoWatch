from pathlib import Path
import re

p = Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s = p.read_text()

# 1) Qualified 45-minute break.
# It resets ONLY continuous work. Other work/readiness remain shift-cumulative.
qualified_helper = r'''
    private fun qualifiedBreak45():Boolean=
        currentActivity.contains("ОТДЫХ") && (breakMinutes>=45 || activityMinutes>=45)
'''
if 'private fun qualifiedBreak45()' not in s:
    anchor='    private fun updateShiftDriving()'
    if anchor not in s: raise SystemExit('updateShiftDriving anchor not found')
    s=s.replace(anchor,qualified_helper+'\n'+anchor,1)

m=re.search(r'private fun processCycle\(\)\{',s)
if not m: raise SystemExit('processCycle not found')
insert=m.end()
if 'if(qualifiedBreak45())workWindowMinutes=0;' not in s[insert:insert+250]:
    s=s[:insert]+'if(qualifiedBreak45())workWindowMinutes=0;'+s[insert:]
s=s.replace('workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;', 'workWindowMinutes=0;')
s=s.replace('workWindowMinutes=0;otherWorkWindowMinutes=0;', 'workWindowMinutes=0;')
s=s.replace('private fun activeWorkTotal():Int=workWindowMinutes+when{','private fun activeWorkTotal():Int=if(qualifiedBreak45())0 else workWindowMinutes+when{')
s=s.replace('private fun activeWorkTotal(): Int = workWindowMinutes + when {','private fun activeWorkTotal(): Int = if(qualifiedBreak45()) 0 else workWindowMinutes + when {')
s=re.sub(r'private fun workTotalForUi\(\):Int=([^\n]+)',lambda m:'private fun workTotalForUi():Int=if(qualifiedBreak45())0 else '+m.group(1) if 'qualifiedBreak45()' not in m.group(0) else m.group(0),s,count=1)

# 2) Large pictograms as separate 30sp TextViews.
activity_label = r'''
    private fun activityLabel(icon:String,title:String):LinearLayout=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        addView(TextView(this@DriverDashboardActivity).apply{text=icon;textSize=30f;setTextColor(TEXT);setPadding(0,0,dp(9),0)})
        addView(TextView(this@DriverDashboardActivity).apply{text=title;textSize=12.5f;setTextColor(MUTED);setTypeface(typeface,Typeface.BOLD)})
    }
'''
if 'private fun activityLabel(icon:String,title:String)' not in s:
    anchor='    private fun progressCard()'
    if anchor not in s: raise SystemExit('progressCard anchor not found')
    s=s.replace(anchor,activity_label+'\n'+anchor,1)
def repl_label(m): return f'activityLabel("{m.group(1)}","{m.group(2).strip()}")'
s=re.sub(r'label\("(🚗|⚒|✉|🛏|⏱|☕)\s*([^"\\]+)"\)',repl_label,s)
plain_map={'НЕПРЕРЫВНОЕ ВОЖДЕНИЕ':('🚗','НЕПРЕРЫВНОЕ ВОЖДЕНИЕ'),'ВОЖДЕНИЕ ЗА СМЕНУ':('🚗','ВОЖДЕНИЕ ЗА СМЕНУ'),'ОТДЫХ':('🛏','ОТДЫХ'),'НЕПРЕРЫВНАЯ РАБОТА':('⏱','НЕПРЕРЫВНАЯ РАБОТА'),'ДРУГАЯ РАБОТА':('⚒','ДРУГАЯ РАБОТА'),'ОЖИДАНИЕ / ГОТОВНОСТЬ':('✉','ОЖИДАНИЕ / ГОТОВНОСТЬ')}
for title,(icon,text) in plain_map.items(): s=s.replace(f'label("{title}")',f'activityLabel("{icon}","{text}")')

# 3) Active tabs.
if 'private lateinit var nowTab:Button' not in s:
    s=s.replace('private var nowTab:Button?=null; private var historyTab:Button?=null','private lateinit var nowTab:Button; private lateinit var historyTab:Button')
pattern=r'val tabs=LinearLayout\(this\)\.apply\{orientation=LinearLayout\.HORIZONTAL\};tabs\.addView\(tabButton\("Сейчас"\)\.apply\{setOnClickListener\{showNow\(\)\}\},LinearLayout\.LayoutParams\(0,dp\(42\),1f\)\);tabs\.addView\(hspace\(6\)\);tabs\.addView\(tabButton\("История"\)\.apply\{setOnClickListener\{showHistory\(\)\}\},LinearLayout\.LayoutParams\(0,dp\(42\),1f\)\)'
replacement='val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};nowTab=tabButton("Сейчас").apply{setOnClickListener{showNow()}};historyTab=tabButton("История").apply{setOnClickListener{showHistory()}};tabs.addView(nowTab,LinearLayout.LayoutParams(0,dp(42),1f));tabs.addView(hspace(6));tabs.addView(historyTab,LinearLayout.LayoutParams(0,dp(42),1f))'
s=re.sub(pattern,replacement,s,count=1)
s=s.replace(';if(t=="Сейчас")nowTab=this;if(t=="История")historyTab=this}', '}')
s=s.replace('nowTab?.let{','if(::nowTab.isInitialized) nowTab.let{').replace('historyTab?.let{','if(::historyTab.isInitialized) historyTab.let{')
s=s.replace('it.alpha=if(now)1.0f else 0.68f','it.alpha=if(now)1.0f else 0.60f').replace('it.alpha=if(!now)1.0f else 0.68f','it.alpha=if(!now)1.0f else 0.60f')
s=s.replace('private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE}','private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE;setTabState(true)}')
s=s.replace('private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE}','private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE;setTabState(false)}')

# 4) Split daily rest: any in-shift rest >=3h displays exactly 3:00.
s=re.sub(r'fun splitRestText\(older:HistoryData\.Day,gap:Int\):String\{.*?\n\s*\}','fun splitRestText(older:HistoryData.Day,gap:Int):String{\n            val inside=older.longestRestMinutes\n            if(inside<180)return HistoryData.fmt(gap)\n            return "3:00 + ${HistoryData.fmt(gap)}"\n        }',s,count=1,flags=re.S)

# 5) Rest milestones.
s=s.replace('0:45 • 3:00 • 9:00 • 11:00','0:45 • 3:00 • 9:00 • 11:00 • 24:00 • 45:00').replace('45 мин • 3:00 • 9:00 • 11:00','45 мин • 3:00 • 9:00 • 11:00 • 24:00 • 45:00')

# 6) Working-week 144-hour countdown from the END of the last weekly rest.
# We derive the last weekly-rest gap from card history. A weekly rest is >=24h.
week_helper = r'''
    private fun workingWeekElapsedMinutes():Int?{
        val h=history?:return null
        val d=h.days
        if(d.size<2)return null
        for(i in d.lastIndex downTo 1){
            val older=d[i-1];val newer=d[i]
            val gap=HistoryData.gapMinutes(older,newer)?:continue
            if(gap>=24*60){
                val end=newer.startTime?:continue
                val endDate=newer.date
                val sdf=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.US)
                val start=sdf.parse("$endDate $end")?.time?:continue
                return (((System.currentTimeMillis()-start)/60000L).coerceAtLeast(0L)).toInt()
            }
        }
        return null
    }

    private fun workingWeekCard():LinearLayout{
        val elapsed=workingWeekElapsedMinutes()
        val limit=144*60
        val remaining=(limit-(elapsed?:0)).coerceAtLeast(0)
        val bg=when{elapsed==null->CARD;elapsed>=142*60->RED;elapsed>=140*60->YELLOW;else->GREEN}
        return card().apply{
            background=rounded(bg,dp(14).toFloat(),bg)
            addView(activityLabel("📅","РАБОЧАЯ НЕДЕЛЯ"))
            addView(value(if(elapsed==null)"Нет данных недельного отдыха" else "Осталось ${HistoryData.fmt(remaining)} из 144:00",20f))
            addView(sub(if(elapsed==null)"Отсчёт начнётся после определения недельного отдыха" else "Прошло ${HistoryData.fmt(elapsed)} • 140:00 жёлтый • 142:00 красный"))
        }
    }
'''
if 'private fun workingWeekElapsedMinutes()' not in s:
    anchor='    private fun buildNow()'
    if anchor not in s: raise SystemExit('buildNow anchor not found')
    s=s.replace(anchor,week_helper+'\n'+anchor,1)
# Insert after two-week card when possible; otherwise append to nowRoot at end of buildNow construction.
inserted=False
for needle in ['nowRoot.addView(twoWeekFrame)','c.addView(twoWeekFrame)','col.addView(twoWeekFrame)']:
    if needle in s:
        s=s.replace(needle,needle+';'+needle.split('.addView')[0]+'.addView(workingWeekCard())',1);inserted=True;break
if not inserted:
    # Put it before the end of buildNow by anchoring the next function.
    m=re.search(r'private fun buildNow\(\)\{(.*?)(?=\n\s*private fun )',s,re.S)
    if not m: raise SystemExit('cannot locate buildNow body for working week card')
    body=m.group(0)
    pos=m.end()-len(m.group(0))+len(body)
    # insert immediately before trailing whitespace; compact buildNow normally closes with }
    close=s.rfind('}',m.start(),m.end())
    if close<0: raise SystemExit('buildNow closing brace not found')
    s=s[:close]+';nowRoot.addView(workingWeekCard())'+s[close:]

# Version bump.
gp=Path('app/build.gradle.kts');g=gp.read_text();g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 118',g);g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0-working-week-144"',g);gp.write_text(g)
p.write_text(s)

final=p.read_text()
checks={'qualified45':'private fun qualifiedBreak45()','processReset':'if(qualifiedBreak45())workWindowMinutes=0;','split3':'return "3:00 + ${HistoryData.fmt(gap)}"','tabState':'private fun setTabState(now:Boolean)','nowTab':'nowTab=tabButton("Сейчас")','historyTab':'historyTab=tabButton("История")','largeIconHelper':'textSize=30f','workingWeek':'private fun workingWeekElapsedMinutes()','workingWeekCard':'РАБОЧАЯ НЕДЕЛЯ','yellow140':'elapsed>=140*60','red142':'elapsed>=142*60'}
missing=[k for k,v in checks.items() if v not in final]
if missing: raise SystemExit('Final correction missing: '+','.join(missing))
if 'workWindowMinutes=0;otherWorkWindowMinutes=0' in final: raise SystemExit('Other-work counter would be reset by 45-minute break; refusing build')
if final.count('activityLabel("') < 4: raise SystemExit('Large pictograms were not applied; refusing build')
print('Final corrections plus 144h working-week countdown applied successfully')
