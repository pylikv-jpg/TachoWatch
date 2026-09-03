from pathlib import Path
import re

p=Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s=p.read_text()

# Keep the already-agreed 45-minute continuous-work reset intact.
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

# WORKING WEEK, 144:00 -> 0:00.
# Find the latest weekly rest by comparing real shift end -> next real shift start.
# Pure-rest history rows between them are deliberately skipped.
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
                if(start!=null && previousEnd!=null){
                    val gap=start-previousEnd!!
                    if(gap>=24L*60L*60L*1000L) latestStartAfterWeeklyRest=start
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
        val bg=when{
            elapsed==null->CARD
            elapsed>=143*60->RED
            elapsed>=140*60->YELLOW
            else->GREEN
        }
        return card().apply{
            background=rounded(bg,dp(14).toFloat(),bg)
            addView(activityLabel("📅","РАБОЧАЯ НЕДЕЛЯ"))
            addView(value(if(remaining==null)"Нет данных недельного отдыха" else HistoryData.fmt(remaining),24f))
            addView(sub(if(elapsed==null)
                "Ищу последний недельный отдых не менее 24:00"
                else "Осталось из 144:00 • прошло ${HistoryData.fmt(elapsed)}"))
        }
    }
'''

# Replace the previous working-week implementation as one block.
start=s.find('    private fun workingWeekElapsedMinutes()')
end=s.find('    private fun buildNow()',start) if start!=-1 else -1
if start!=-1 and end!=-1:
    s=s[:start]+week_code+'\n'+s[end:]
elif 'private fun workingWeekStartMs()' not in s:
    anchor='    private fun buildNow()'
    if anchor not in s:
        raise SystemExit('buildNow anchor not found')
    s=s.replace(anchor,week_code+'\n'+anchor,1)

# Ensure there is exactly one card on the main screen after the two-week card.
if 'c.addView(workingWeekCard())' not in s:
    if 'c.addView(twoWeekFrame)' in s:
        s=s.replace('c.addView(twoWeekFrame)','c.addView(twoWeekFrame);c.addView(space(7));c.addView(workingWeekCard())',1)
    else:
        raise SystemExit('two-week card insertion anchor not found')

p.write_text(s)

gp=Path('app/build.gradle.kts')
g=gp.read_text()
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 123',g)
g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0-build123-week144-anchor"',g)
gp.write_text(g)

final=p.read_text()
checks={
    'weekStart':'private fun workingWeekStartMs()',
    'countdown':'val remaining=if(elapsed==null)null else (limit-elapsed).coerceAtLeast(0)',
    'yellow140':'elapsed>=140*60',
    'red143':'elapsed>=143*60',
    'weekLabel':'РАБОЧАЯ НЕДЕЛЯ'
}
missing=[k for k,v in checks.items() if v not in final]
if missing:
    raise SystemExit('Working-week correction missing: '+','.join(missing))
print('Working-week 144h countdown patched: latest weekly-rest end -> 144:00 to 0:00; 140h yellow; 143h red')
