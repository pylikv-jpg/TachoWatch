from pathlib import Path
import re

p=Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s=p.read_text()

# Qualified 45-minute rest. Only continuous work resets.
helper='''
    private fun qualifiedBreak45():Boolean=
        currentActivity.contains("ОТДЫХ") && (breakMinutes>=45 || activityMinutes>=45)
'''
if 'private fun qualifiedBreak45()' not in s:
    anchor='    private fun updateShiftDriving()'
    if anchor not in s: raise SystemExit('updateShiftDriving anchor not found')
    s=s.replace(anchor,helper+'\n'+anchor,1)

# Never reset shift-cumulative other work or readiness at 45 minutes.
s=s.replace('workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;','workWindowMinutes=0;')
s=s.replace('workWindowMinutes=0;otherWorkWindowMinutes=0;','workWindowMinutes=0;')

# Persist the reset inside processWorkWindow. This prevents an old continuous-work value
# from returning after the 45-minute break ends.
work_marker='private fun processWorkWindow(){'
if work_marker in s:
    # Remove older duplicated guard if this script has been applied to a generated variant.
    s=s.replace(work_marker+'if(qualifiedBreak45()){workWindowMinutes=0;previousActivity=currentActivity;previousActivityDuration=activityMinutes;return};',work_marker,1)
    s=s.replace(work_marker,work_marker+'if(qualifiedBreak45()){workWindowMinutes=0;previousActivity=currentActivity;previousActivityDuration=activityMinutes;return};',1)
else:
    raise SystemExit('processWorkWindow anchor not found')

# If processCycle exists, also reset there immediately, but do not depend on its presence.
cycle=re.search(r'private\s+fun\s+processCycle\s*\(\s*\)\s*\{',s)
if cycle:
    pos=cycle.end()
    if 'if(qualifiedBreak45())workWindowMinutes=0;' not in s[pos:pos+250]:
        s=s[:pos]+'if(qualifiedBreak45())workWindowMinutes=0;'+s[pos:]

# UI must show 0:00 immediately during a qualifying break. Patch any expression-bodied
# activeWorkTotal regardless of whitespace/formatting.
def patch_active(m):
    expr=m.group(1).strip()
    if expr.startswith('if(qualifiedBreak45())'):
        return m.group(0)
    return 'private fun activeWorkTotal():Int=if(qualifiedBreak45())0 else '+expr
s=re.sub(r'private\s+fun\s+activeWorkTotal\s*\(\s*\)\s*:\s*Int\s*=\s*([^\n]+)',patch_active,s,count=1)

def patch_work_ui(m):
    expr=m.group(1).strip()
    if expr.startswith('if(qualifiedBreak45())'):
        return m.group(0)
    return 'private fun workTotalForUi():Int=if(qualifiedBreak45())0 else '+expr
s=re.sub(r'private\s+fun\s+workTotalForUi\s*\(\s*\)\s*:\s*Int\s*=\s*([^\n]+)',patch_work_ui,s,count=1)

# Large activity pictograms.
activity_label='''
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

def repl(m): return f'activityLabel("{m.group(1)}","{m.group(2).strip()}")'
s=re.sub(r'label\("(🚗|⚒|✉|🛏|⏱|☕)\s*([^"\\]+)"\)',repl,s)
for title,icon in [('НЕПРЕРЫВНОЕ ВОЖДЕНИЕ','🚗'),('ВОЖДЕНИЕ ЗА СМЕНУ','🚗'),('ОТДЫХ','🛏'),('НЕПРЕРЫВНАЯ РАБОТА','⏱'),('ДРУГАЯ РАБОТА','⚒'),('ОЖИДАНИЕ / ГОТОВНОСТЬ','✉')]:
    s=s.replace(f'label("{title}")',f'activityLabel("{icon}","{title}")')

# Active tabs. Keep references from apply_next_update.py and force state in navigation.
s=s.replace('it.alpha=if(now)1.0f else 0.68f','it.alpha=if(now)1.0f else 0.60f')
s=s.replace('it.alpha=if(!now)1.0f else 0.68f','it.alpha=if(!now)1.0f else 0.60f')
# Robustly replace compact navigation functions regardless of their previous body text.
s,n_now=re.subn(r'private\s+fun\s+showNow\s*\(\s*\)\s*\{[^{}]*\}',
                 'private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE;setTabState(true)}',s,count=1)
s,n_hist=re.subn(r'private\s+fun\s+showHistory\s*\(\s*\)\s*\{[^{}]*\}',
                  'private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE;setTabState(false)}',s,count=1)
if n_now!=1 or n_hist!=1:
    raise SystemExit(f'navigation patch failed now={n_now} history={n_hist}')
if 'setContentView(root);buildNow();buildHistoryView();setTabState(true)' not in s:
    s=s.replace('setContentView(root);buildNow();buildHistoryView()', 'setContentView(root);buildNow();buildHistoryView();setTabState(true)',1)

# Split daily rest: credit/display exactly 3:00 for the first part.
s=re.sub(r'fun splitRestText\(older:HistoryData\.Day,gap:Int\):String\{.*?\n\s*\}',
         'fun splitRestText(older:HistoryData.Day,gap:Int):String{\n            val inside=older.longestRestMinutes\n            if(inside<180)return HistoryData.fmt(gap)\n            return "3:00 + ${HistoryData.fmt(gap)}"\n        }',s,count=1,flags=re.S)

# Rest milestones.
s=s.replace('0:45 • 3:00 • 9:00 • 11:00','0:45 • 3:00 • 9:00 • 11:00 • 24:00 • 45:00')
s=s.replace('45 мин • 3:00 • 9:00 • 11:00','45 мин • 3:00 • 9:00 • 11:00 • 24:00 • 45:00')

# Working week: 144 hours from the end of the most recent weekly rest >=24h.
week_helper='''
    private fun workingWeekElapsedMinutes():Int?{
        val d=history?.days?:return null
        if(d.size<2)return null
        for(i in d.lastIndex downTo 1){
            val older=d[i-1];val newer=d[i]
            val gap=HistoryData.gapMinutes(older,newer)?:continue
            if(gap>=24*60){
                val t=newer.startTime?:continue
                val sdf=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.US)
                val start=sdf.parse("${newer.date} $t")?.time?:continue
                return (((System.currentTimeMillis()-start)/60000L).coerceAtLeast(0L)).toInt()
            }
        }
        return null
    }
    private fun workingWeekCard():LinearLayout{
        val elapsed=workingWeekElapsedMinutes();val limit=144*60;val remaining=(limit-(elapsed?:0)).coerceAtLeast(0)
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
if 'c.addView(workingWeekCard())' not in s:
    if 'c.addView(twoWeekFrame)' not in s: raise SystemExit('two-week card insertion anchor not found')
    s=s.replace('c.addView(twoWeekFrame)','c.addView(twoWeekFrame);c.addView(space(7));c.addView(workingWeekCard())',1)

p.write_text(s)

gp=Path('app/build.gradle.kts');g=gp.read_text();g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 120',g);g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0-build120-work45-tabs-week144"',g);gp.write_text(g)

final=p.read_text()
# Keep only structural sanity checks that are independent of formatting.
checks={
 'qualified45':'private fun qualifiedBreak45()',
 'workResetGuard':'if(qualifiedBreak45()){workWindowMinutes=0;',
 'tabState':'private fun setTabState(now:Boolean)',
 'showNow':'setTabState(true)',
 'showHistory':'setTabState(false)',
 'split3':'return "3:00 + ${HistoryData.fmt(gap)}"',
 'largeIcons':'textSize=30f',
 'week144':'РАБОЧАЯ НЕДЕЛЯ',
 'yellow140':'elapsed>=140*60',
 'red142':'elapsed>=142*60'
}
missing=[k for k,v in checks.items() if v not in final]
if missing: raise SystemExit('Final correction missing: '+','.join(missing))
if 'workWindowMinutes=0;otherWorkWindowMinutes=0' in final: raise SystemExit('Other work would reset at 45 minutes')
print('Final corrections applied; ready for Gradle build')
