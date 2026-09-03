from pathlib import Path
import re

p = Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s = p.read_text()

# 1) Six-hour continuous-work window: once a real 45-minute rest is reached,
# the value shown to the driver must be zero immediately, regardless of stale persisted counters.
# IMPORTANT: other work and readiness are shift-cumulative counters and must NOT reset here.
s = s.replace(
    'private fun activeWorkTotal():Int=workWindowMinutes+when{',
    'private fun activeWorkTotal():Int=if(currentActivity.contains("ОТДЫХ")&&maxOf(breakMinutes,activityMinutes)>=45)0 else workWindowMinutes+when{'
)
s = s.replace(
    'private fun activeWorkTotal(): Int = workWindowMinutes + when {',
    'private fun activeWorkTotal(): Int = if(currentActivity.contains("ОТДЫХ")&&maxOf(breakMinutes,activityMinutes)>=45) 0 else workWindowMinutes + when {'
)
s = re.sub(
    r'private fun workTotalForUi\(\):Int\s*=\s*([^\n]+)',
    lambda m: 'private fun workTotalForUi():Int=if(currentActivity.contains("ОТДЫХ")&&maxOf(breakMinutes,activityMinutes)>=45)0 else '+m.group(1),
    s,
    count=1
)
# At 45 minutes reset ONLY the continuous-work window.
s = s.replace(
    'if(currentActivity.contains("ОТДЫХ")&&breakMinutes>=45){workWindowMinutes=0;',
    'if(currentActivity.contains("ОТДЫХ")&&maxOf(breakMinutes,activityMinutes)>=45){workWindowMinutes=0;'
)
s = s.replace(
    'if(currentActivity.contains("ОТДЫХ")&&maxOf(breakMinutes,activityMinutes)>=45){workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;',
    'if(currentActivity.contains("ОТДЫХ")&&maxOf(breakMinutes,activityMinutes)>=45){workWindowMinutes=0;'
)

# 2) Make pictograms genuinely larger than the text by using a two-part row.
activity_label = r'''
    private fun activityLabel(icon:String,title:String):LinearLayout=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
        addView(TextView(this@DriverDashboardActivity).apply{
            text=icon
            textSize=28f
            setTextColor(TEXT)
            setPadding(0,0,dp(8),0)
        })
        addView(TextView(this@DriverDashboardActivity).apply{
            text=title
            textSize=12.5f
            setTextColor(MUTED)
            setTypeface(typeface,Typeface.BOLD)
        })
    }
'''
if 'private fun activityLabel(icon:String,title:String)' not in s:
    anchor='    private fun progressCard()'
    if anchor in s:
        s=s.replace(anchor,activity_label+'\n'+anchor,1)
    else:
        raise SystemExit('progressCard anchor not found')

def repl_label(m):
    icon=m.group(1)
    title=m.group(2)
    return f'activityLabel("{icon}","{title}")'
s = re.sub(r'label\("(🚗|⚒|✉|🛏)\s{1,2}([^"\\]+)"\)', repl_label, s)

# 3) Active tab state: bind the actual two buttons in buildUi and always refresh state.
old_tabs='val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};tabs.addView(tabButton("Сейчас").apply{setOnClickListener{showNow()}},LinearLayout.LayoutParams(0,dp(42),1f));tabs.addView(hspace(6));tabs.addView(tabButton("История").apply{setOnClickListener{showHistory()}},LinearLayout.LayoutParams(0,dp(42),1f))'
new_tabs='val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};nowTab=tabButton("Сейчас").apply{setOnClickListener{showNow()}};historyTab=tabButton("История").apply{setOnClickListener{showHistory()}};tabs.addView(nowTab,LinearLayout.LayoutParams(0,dp(42),1f));tabs.addView(hspace(6));tabs.addView(historyTab,LinearLayout.LayoutParams(0,dp(42),1f))'
if old_tabs in s:
    s=s.replace(old_tabs,new_tabs,1)
s=s.replace('private var nowTab:Button?=null; private var historyTab:Button?=null','private lateinit var nowTab:Button; private lateinit var historyTab:Button')
s=s.replace('nowTab?.let{','if(::nowTab.isInitialized) nowTab.let{').replace('historyTab?.let{','if(::historyTab.isInitialized) historyTab.let{')
s=s.replace('it.alpha=if(now)1.0f else 0.68f','it.alpha=if(now)1.0f else 0.60f')
s=s.replace('it.alpha=if(!now)1.0f else 0.68f','it.alpha=if(!now)1.0f else 0.60f')

# 4) Split daily rest: any in-shift rest >=3h is represented as exactly 3:00.
s=s.replace('val prefix=if(inside%60==0)"${inside/60}" else HistoryData.fmt(inside)\n            return "$prefix + ${HistoryData.fmt(gap)}"',
            'return "3:00 + ${HistoryData.fmt(gap)}"')

# 5) Main rest card milestones: add reduced/full weekly-rest markers.
s=s.replace('0:45 • 3:00 • 9:00 • 11:00','0:45 • 3:00 • 9:00 • 11:00 • 24:00 • 45:00')
s=s.replace('45 мин • 3:00 • 9:00 • 11:00','45 мин • 3:00 • 9:00 • 11:00 • 24:00 • 45:00')
s=s.replace('rest>=660->', 'rest>=2700->"Полный недельный отдых 45:00";rest>=1440->"Сокращённый недельный отдых 24:00";rest>=660->')
s=s.replace('r>=660->', 'r>=2700->"Полный недельный отдых 45:00";r>=1440->"Сокращённый недельный отдых 24:00";r>=660->')

# Version bump.
gp=Path('app/build.gradle.kts')
g=gp.read_text()
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 116',g)
g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0-final-work45-icons-tabs-rest24-45"',g)
gp.write_text(g)

p.write_text(s)

checks={
    'activityLabel': 'private fun activityLabel(icon:String,title:String)',
    'split3': '3:00 + ${HistoryData.fmt(gap)}',
    'work45': 'maxOf(breakMinutes,activityMinutes)>=45',
    'tabState': 'private fun setTabState(now:Boolean)'
}
final=p.read_text()
missing=[k for k,v in checks.items() if v not in final]
if missing:
    raise SystemExit('Final correction missing: '+','.join(missing))
if 'workWindowMinutes=0;otherWorkWindowMinutes=0' in final:
    raise SystemExit('Other-work counter would be reset by 45-minute break; refusing build')
print('Final corrections applied successfully')
