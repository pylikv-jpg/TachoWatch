from pathlib import Path
import re

p=Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s=p.read_text()

# This patch intentionally fixes ONE UI problem only:
# the active top tab must be green: Сейчас on Now screen, История on History screen.

# Ensure tab references exist.
if 'private var nowTab:Button?=null' not in s:
    s=s.replace('private lateinit var driver:TextView',
                'private lateinit var driver:TextView; private var nowTab:Button?=null; private var historyTab:Button?=null',1)

# Ensure tabButton stores references regardless of earlier formatting.
pat=r'private\s+fun\s+tabButton\(t:String\)\s*=\s*Button\(this\)\.apply\{([^}]*)\}'
m=re.search(pat,s)
if m:
    body=m.group(1)
    if 'nowTab=this' not in body:
        body += ';if(t=="Сейчас")nowTab=this;if(t=="История")historyTab=this'
        s=s[:m.start()]+'private fun tabButton(t:String)=Button(this).apply{'+body+'}'+s[m.end():]

# Add/replace one authoritative state painter.
state='''
    private fun setTabState(now:Boolean){
        nowTab?.apply{
            background=rounded(if(now) GREEN else CARD,dp(11).toFloat(),if(now) GREEN else BORDER)
            setTextColor(if(now) TEXT else MUTED)
            alpha=if(now)1.0f else 0.68f
        }
        historyTab?.apply{
            background=rounded(if(!now) GREEN else CARD,dp(11).toFloat(),if(!now) GREEN else BORDER)
            setTextColor(if(!now) TEXT else MUTED)
            alpha=if(!now)1.0f else 0.68f
        }
    }
'''
# Replace an existing compact setTabState, otherwise insert before showNow/buildNow.
s,n=re.subn(r'\s*private\s+fun\s+setTabState\s*\(now:Boolean\)\s*\{.*?\n\s*\}', '\n'+state, s, count=1, flags=re.S)
if n==0:
    for anchor in ['    private fun showNow()','    private fun buildNow()','    private fun updateShiftDriving()']:
        if anchor in s:
            s=s.replace(anchor,state+'\n'+anchor,1)
            break

# Force navigation functions to repaint the active tab every time.
s=re.sub(r'private\s+fun\s+showNow\s*\(\s*\)\s*\{[^{}]*\}',
         'private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE;setTabState(true)}',s,count=1)
s=re.sub(r'private\s+fun\s+showHistory\s*\(\s*\)\s*\{[^{}]*\}',
         'private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE;setTabState(false)}',s,count=1)

# On startup the Now screen is visible, so Сейчас must already be green.
if 'setContentView(root);buildNow();buildHistoryView();setTabState(true)' not in s:
    s=s.replace('setContentView(root);buildNow();buildHistoryView()',
                'setContentView(root);buildNow();buildHistoryView();setTabState(true)',1)

p.write_text(s)

gp=Path('app/build.gradle.kts')
g=gp.read_text()
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 124',g)
g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0-build124-active-tabs"',g)
gp.write_text(g)

final=p.read_text()
checks={
    'refs':'nowTab:Button?=null',
    'state':'private fun setTabState(now:Boolean)',
    'nowGreen':'setTabState(true)',
    'historyGreen':'setTabState(false)'
}
missing=[k for k,v in checks.items() if v not in final]
if missing:
    raise SystemExit('Active-tab correction missing: '+','.join(missing))
print('Active tab highlighting patched: Сейчас green on Now, История green on History')
