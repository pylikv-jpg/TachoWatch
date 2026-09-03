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
    if anchor not in s:
        raise SystemExit('updateShiftDriving anchor not found')
    s=s.replace(anchor,qualified_helper+'\n'+anchor,1)

# Force reset into processCycle regardless of its compact formatting.
m=re.search(r'private fun processCycle\(\)\{',s)
if not m:
    raise SystemExit('processCycle not found')
insert=m.end()
if 'if(qualifiedBreak45())workWindowMinutes=0;' not in s[insert:insert+250]:
    s=s[:insert]+'if(qualifiedBreak45())workWindowMinutes=0;'+s[insert:]

# Remove any accidental 45-minute reset of shift-cumulative counters.
s=s.replace('workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;', 'workWindowMinutes=0;')
s=s.replace('workWindowMinutes=0;otherWorkWindowMinutes=0;', 'workWindowMinutes=0;')

# Make the displayed six-hour counter zero immediately during the qualifying break.
s=s.replace(
    'private fun activeWorkTotal():Int=workWindowMinutes+when{',
    'private fun activeWorkTotal():Int=if(qualifiedBreak45())0 else workWindowMinutes+when{'
)
s=s.replace(
    'private fun activeWorkTotal(): Int = workWindowMinutes + when {',
    'private fun activeWorkTotal(): Int = if(qualifiedBreak45()) 0 else workWindowMinutes + when {'
)
# workTotalForUi can be either expression-bodied or block-bodied. Patch expression form when present.
s=re.sub(
    r'private fun workTotalForUi\(\):Int=([^\n]+)',
    lambda m:'private fun workTotalForUi():Int=if(qualifiedBreak45())0 else '+m.group(1) if 'qualifiedBreak45()' not in m.group(0) else m.group(0),
    s,
    count=1
)

# 2) Large pictograms as separate 30sp TextViews, not merely larger label text.
activity_label = r'''
    private fun activityLabel(icon:String,title:String):LinearLayout=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
        addView(TextView(this@DriverDashboardActivity).apply{
            text=icon
            textSize=30f
            setTextColor(TEXT)
            setPadding(0,0,dp(9),0)
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
    if anchor not in s:
        raise SystemExit('progressCard anchor not found')
    s=s.replace(anchor,activity_label+'\n'+anchor,1)

# Replace emoji-bearing labels wherever they appear.
def repl_label(m):
    return f'activityLabel("{m.group(1)}","{m.group(2).strip()}")'
s=re.sub(r'label\("(🚗|⚒|✉|🛏|⏱|☕)\s*([^"\\]+)"\)',repl_label,s)

# Also replace known plain dashboard titles if the icon was built separately/omitted before.
plain_map={
    'НЕПРЕРЫВНОЕ ВОЖДЕНИЕ':('🚗','НЕПРЕРЫВНОЕ ВОЖДЕНИЕ'),
    'ВОЖДЕНИЕ ЗА СМЕНУ':('🚗','ВОЖДЕНИЕ ЗА СМЕНУ'),
    'ОТДЫХ':('🛏','ОТДЫХ'),
    'НЕПРЕРЫВНАЯ РАБОТА':('⏱','НЕПРЕРЫВНАЯ РАБОТА'),
    'ДРУГАЯ РАБОТА':('⚒','ДРУГАЯ РАБОТА'),
    'ОЖИДАНИЕ / ГОТОВНОСТЬ':('✉','ОЖИДАНИЕ / ГОТОВНОСТЬ')
}
for title,(icon,text) in plain_map.items():
    s=s.replace(f'label("{title}")',f'activityLabel("{icon}","{text}")')

# 3) Active tab buttons. Bind actual instances and make active one green.
if 'private lateinit var nowTab:Button' not in s:
    s=s.replace('private var nowTab:Button?=null; private var historyTab:Button?=null',
                'private lateinit var nowTab:Button; private lateinit var historyTab:Button')

# Directly replace the concrete tab construction, with regex fallback for spacing differences.
pattern=r'val tabs=LinearLayout\(this\)\.apply\{orientation=LinearLayout\.HORIZONTAL\};tabs\.addView\(tabButton\("Сейчас"\)\.apply\{setOnClickListener\{showNow\(\)\}\},LinearLayout\.LayoutParams\(0,dp\(42\),1f\)\);tabs\.addView\(hspace\(6\)\);tabs\.addView\(tabButton\("История"\)\.apply\{setOnClickListener\{showHistory\(\)\}\},LinearLayout\.LayoutParams\(0,dp\(42\),1f\)\)'
replacement='val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};nowTab=tabButton("Сейчас").apply{setOnClickListener{showNow()}};historyTab=tabButton("История").apply{setOnClickListener{showHistory()}};tabs.addView(nowTab,LinearLayout.LayoutParams(0,dp(42),1f));tabs.addView(hspace(6));tabs.addView(historyTab,LinearLayout.LayoutParams(0,dp(42),1f))'
s,n_tabs=re.subn(pattern,replacement,s,count=1)

# If tabButton() side-effect binding exists, make it compatible with lateinit fields by removing nullable assignment behavior.
s=s.replace(';if(t=="Сейчас")nowTab=this;if(t=="История")historyTab=this}', '}')

# Normalize setTabState to lateinit-safe references.
s=s.replace('nowTab?.let{','if(::nowTab.isInitialized) nowTab.let{')
s=s.replace('historyTab?.let{','if(::historyTab.isInitialized) historyTab.let{')
s=s.replace('it.alpha=if(now)1.0f else 0.68f','it.alpha=if(now)1.0f else 0.60f')
s=s.replace('it.alpha=if(!now)1.0f else 0.68f','it.alpha=if(!now)1.0f else 0.60f')

# Ensure navigation calls update the selected state.
s=s.replace('private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE}',
            'private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE;setTabState(true)}')
s=s.replace('private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE}',
            'private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE;setTabState(false)}')

# 4) Split daily rest: any in-shift rest >= 3h is credited/displayed as exactly 3:00.
s=re.sub(
    r'fun splitRestText\(older:HistoryData\.Day,gap:Int\):String\{.*?\n\s*\}',
    'fun splitRestText(older:HistoryData.Day,gap:Int):String{\n            val inside=older.longestRestMinutes\n            if(inside<180)return HistoryData.fmt(gap)\n            return "3:00 + ${HistoryData.fmt(gap)}"\n        }',
    s,
    count=1,
    flags=re.S
)

# 5) Rest milestones including 24h reduced weekly and 45h full weekly.
s=s.replace('0:45 • 3:00 • 9:00 • 11:00','0:45 • 3:00 • 9:00 • 11:00 • 24:00 • 45:00')
s=s.replace('45 мин • 3:00 • 9:00 • 11:00','45 мин • 3:00 • 9:00 • 11:00 • 24:00 • 45:00')

# Version bump.
gp=Path('app/build.gradle.kts')
g=gp.read_text()
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 117',g)
g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0-work45-large-icons-active-tabs-split3"',g)
gp.write_text(g)

p.write_text(s)

# Sanity checks. Refuse partial builds.
final=p.read_text()
checks={
    'qualified45':'private fun qualifiedBreak45()',
    'processReset':'if(qualifiedBreak45())workWindowMinutes=0;',
    'split3':'return "3:00 + ${HistoryData.fmt(gap)}"',
    'tabState':'private fun setTabState(now:Boolean)',
    'nowTab':'nowTab=tabButton("Сейчас")',
    'historyTab':'historyTab=tabButton("История")',
    'largeIconHelper':'textSize=30f'
}
missing=[k for k,v in checks.items() if v not in final]
if missing:
    raise SystemExit('Final correction missing: '+','.join(missing))
if 'workWindowMinutes=0;otherWorkWindowMinutes=0' in final:
    raise SystemExit('Other-work counter would be reset by 45-minute break; refusing build')
# Require at least four actual activityLabel usages in addition to helper declaration.
if final.count('activityLabel("') < 4:
    raise SystemExit('Large pictograms were not applied to dashboard labels; refusing build')
print('Final corrections applied successfully')
