from pathlib import Path
import re

p=Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s=p.read_text()

# Preserve the last known-good #172 continuous-work logic.
helper='''
    private fun qualifiedBreak45():Boolean=
        currentActivity.contains("ОТДЫХ") && maxOf(breakMinutes,activityMinutes)>=45
'''
if 'private fun qualifiedBreak45()' not in s:
    for anchor in ['    private fun updateShiftDriving()','    private fun buildNow()']:
        if anchor in s:
            s=s.replace(anchor,helper+'\n'+anchor,1)
            break

s=s.replace('workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;','workWindowMinutes=0;')
s=s.replace('workWindowMinutes=0;otherWorkWindowMinutes=0;','workWindowMinutes=0;')

reset_code='if(qualifiedBreak45()){workWindowMinutes=0;prefs.edit().putBoolean("work45_reset_latched",true).apply();}'
for pat in [r'(private\s+fun\s+processCycle\s*\(\s*\)\s*\{)',r'(private\s+fun\s+processWorkWindow\s*\(\s*\)\s*\{)']:
    m=re.search(pat,s)
    if m:
        pos=m.end()
        if 'work45_reset_latched' not in s[pos:pos+500]:
            s=s[:pos]+reset_code+s[pos:]
        break

m=re.search(r'(private\s+fun\s+syncWorkWindowToCurrentShift\s*\(\s*\)\s*\{)',s)
if m:
    pos=m.end()
    guard='if(prefs.getBoolean("work45_reset_latched",false))return;'
    if guard not in s[pos:pos+350]:
        s=s[:pos]+guard+s[pos:]

def patch_expr(name,text):
    pat=rf'private\s+fun\s+{name}\s*\(\s*\)\s*:\s*Int\s*=\s*([^\n]+)'
    def repl(m):
        expr=m.group(1).strip()
        if expr.startswith('if(qualifiedBreak45())'):
            return m.group(0)
        return f'private fun {name}():Int=if(qualifiedBreak45())0 else '+expr
    return re.sub(pat,repl,text,count=1)

s=patch_expr('activeWorkTotal',s)
s=patch_expr('workTotalForUi',s)
if 'if(qualifiedBreak45())0 else' not in s:
    s=s.replace('val work=workTotalForUi()','val work=if(qualifiedBreak45())0 else workTotalForUi()',1)
    s=s.replace('val wt=workTotalForUi()','val wt=if(qualifiedBreak45())0 else workTotalForUi()',1)

for name in ['onShiftClosedDetected','resetShiftCounters','clearShiftCounters']:
    m=re.search(rf'(private\s+fun\s+{name}\s*\([^)]*\)\s*\{{)',s)
    if m:
        pos=m.end()
        clear='prefs.edit().remove("work45_reset_latched").apply();'
        if clear not in s[pos:pos+300]:
            s=s[:pos]+clear+s[pos:]
        break

# 144-hour working week. Start at the first shift after the latest weekly rest >=24h.
week_code='''
    private fun workingWeekStartMs():Long?{
        val days=history?.days?:return null
        val fmt=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.US).apply{isLenient=false}
        var previousEnd:Long?=null
        var latestStartAfterWeeklyRest:Long?=null
        for(day in days){
            val startText=day.startTime
            if(startText!=null){
                val start=runCatching{fmt.parse("${day.date} $startText")?.time}.getOrNull()
                if(start!=null && previousEnd!=null && start-previousEnd!!>=24L*60L*60L*1000L){
                    latestStartAfterWeeklyRest=start
                }
            }
            val endText=day.endTime
            if(endText!=null){
                var end=runCatching{fmt.parse("${day.date} $endText")?.time}.getOrNull()
                val start=startText?.let{runCatching{fmt.parse("${day.date} $it")?.time}.getOrNull()}
                if(end!=null && start!=null && end<start) end+=24L*60L*60L*1000L
                if(end!=null) previousEnd=end
            }
        }
        return latestStartAfterWeeklyRest
    }
    private fun workingWeekElapsedMinutes():Int?{
        val start=workingWeekStartMs()?:return null
        return (((System.currentTimeMillis()-start)/60000L).coerceAtLeast(0L)).toInt()
    }
    private fun workingWeekCard():LinearLayout{
        val elapsed=workingWeekElapsedMinutes()
        val limit=144*60
        val remaining=if(elapsed==null)null else (limit-elapsed).coerceAtLeast(0)
        val bg=when{elapsed==null->CARD;elapsed>=143*60->RED;elapsed>=140*60->YELLOW;else->GREEN}
        return card().apply{
            background=rounded(bg,dp(14).toFloat(),bg)
            addView(label("📅  РАБОЧАЯ НЕДЕЛЯ"))
            addView(value(if(remaining==null)"Нет данных недельного отдыха" else HistoryData.fmt(remaining),24f))
            addView(sub(if(elapsed==null)"Ищу последний недельный отдых не менее 24:00" else "Осталось из 144:00 • прошло ${HistoryData.fmt(elapsed)}"))
        }
    }
'''

for old_start in ['    private fun workingWeekStartMs()','    private fun workingWeekElapsedMinutes()']:
    start=s.find(old_start)
    if start!=-1:
        end=s.find('    private fun buildNow()',start)
        if end!=-1:
            s=s[:start]+week_code+'\n'+s[end:]
        break
else:
    anchor='    private fun buildNow()'
    if anchor not in s:
        raise SystemExit('buildNow anchor not found')
    s=s.replace(anchor,week_code+'\n'+anchor,1)

if 'c.addView(workingWeekCard())' not in s:
    if 'c.addView(twoWeekFrame)' not in s:
        raise SystemExit('twoWeekFrame anchor not found')
    s=s.replace('c.addView(twoWeekFrame)','c.addView(twoWeekFrame);c.addView(space(7));c.addView(workingWeekCard())',1)

# Active tabs without rewriting navigation functions.
# apply_next_update.py already creates nowTab/historyTab and setTabState().
# Add a non-consuming touch listener: ACTION_UP changes the highlight,
# while the existing click listener continues to switch screens normally.
old_tab='private fun tabButton(t:String)=Button(this).apply{text=t;isAllCaps=false;textSize=14f;setTextColor(TEXT);background=rounded(CARD,dp(11).toFloat(),BORDER);if(t=="Сейчас")nowTab=this;if(t=="История")historyTab=this}'
new_tab='private fun tabButton(t:String)=Button(this).apply{text=t;isAllCaps=false;textSize=14f;setTextColor(TEXT);background=rounded(CARD,dp(11).toFloat(),BORDER);if(t=="Сейчас")nowTab=this;if(t=="История")historyTab=this;setOnTouchListener{_,e->if(e.action==android.view.MotionEvent.ACTION_UP)setTabState(t=="Сейчас");false};if(t=="Сейчас")post{setTabState(true)}}'
if old_tab in s:
    s=s.replace(old_tab,new_tab,1)
elif 'setOnTouchListener{_,e->if(e.action==android.view.MotionEvent.ACTION_UP)setTabState(t=="Сейчас")' not in s:
    raise SystemExit('tabButton anchor not found after apply_next_update')

p.write_text(s)

gp=Path('app/build.gradle.kts')
g=gp.read_text()
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 127',g)
g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0-build127-week-tabs-safe-touch"',g)
gp.write_text(g)

final=p.read_text()
checks={
    'work45':'qualifiedBreak45',
    'week':'private fun workingWeekStartMs()',
    'weekLabel':'РАБОЧАЯ НЕДЕЛЯ',
    'yellow':'elapsed>=140*60',
    'red':'elapsed>=143*60',
    'tabState':'private fun setTabState(now:Boolean)',
    'touchTabs':'android.view.MotionEvent.ACTION_UP'
}
missing=[k for k,v in checks.items() if v not in final]
if missing:
    raise SystemExit('Final patch missing: '+','.join(missing))
if 'activityLabel("📅"' in final:
    raise SystemExit('Unsafe activityLabel reference remains')
print('Final patch applied safely: work45 + week144 + touch-based active tabs')
