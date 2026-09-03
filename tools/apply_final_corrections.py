from pathlib import Path
import re

p=Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s=p.read_text()

# This patch intentionally fixes ONE thing only:
# continuous work = driving + other work since the last qualifying 45-minute rest.
# Other-work and readiness counters are shift-cumulative and must not be reset here.

helper='''
    private fun qualifiedBreak45():Boolean=
        currentActivity.contains("ОТДЫХ") && maxOf(breakMinutes,activityMinutes)>=45
'''
if 'private fun qualifiedBreak45()' not in s:
    for anchor in ['    private fun updateShiftDriving()','    private fun buildNow()']:
        if anchor in s:
            s=s.replace(anchor,helper+'\n'+anchor,1)
            break

# Remove the old incorrect behaviour that reset shift-cumulative counters.
s=s.replace('workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;','workWindowMinutes=0;')
s=s.replace('workWindowMinutes=0;otherWorkWindowMinutes=0;','workWindowMinutes=0;')

# Persist a latch when the 45-minute rest is reached. This prevents the synchronizer
# from rebuilding continuous work from shift start immediately after the reset.
reset_code='if(qualifiedBreak45()){workWindowMinutes=0;prefs.edit().putBoolean("work45_reset_latched",true).apply();}'
inserted=False
for pat in [
    r'(private\s+fun\s+processCycle\s*\(\s*\)\s*\{)',
    r'(private\s+fun\s+processWorkWindow\s*\(\s*\)\s*\{)'
]:
    m=re.search(pat,s)
    if m:
        pos=m.end()
        if 'work45_reset_latched' not in s[pos:pos+500]:
            s=s[:pos]+reset_code+s[pos:]
        inserted=True
        break

# Build-158 variants have a synchronizer that can restore work from shift start.
# Once a 45-minute reset happened in this shift, do not let that synchronizer overwrite 0/new-cycle values.
m=re.search(r'(private\s+fun\s+syncWorkWindowToCurrentShift\s*\(\s*\)\s*\{)',s)
if m:
    pos=m.end()
    guard='if(prefs.getBoolean("work45_reset_latched",false))return;'
    if guard not in s[pos:pos+350]:
        s=s[:pos]+guard+s[pos:]

# While the qualifying rest itself is active, the UI must display 0:00 immediately.
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

# Fallback for a block-bodied UI update if neither expression-bodied helper exists.
if 'if(qualifiedBreak45())0 else' not in s:
    s=s.replace('val work=workTotalForUi()','val work=if(qualifiedBreak45())0 else workTotalForUi()',1)
    s=s.replace('val wt=workTotalForUi()','val wt=if(qualifiedBreak45())0 else workTotalForUi()',1)

# Clear the latch when a shift is explicitly closed, so the next shift can initialize normally.
for name in ['onShiftClosedDetected','resetShiftCounters','clearShiftCounters']:
    m=re.search(rf'(private\s+fun\s+{name}\s*\([^)]*\)\s*\{{)',s)
    if m:
        pos=m.end()
        clear='prefs.edit().remove("work45_reset_latched").apply();'
        if clear not in s[pos:pos+300]:
            s=s[:pos]+clear+s[pos:]
        break

p.write_text(s)

gp=Path('app/build.gradle.kts')
g=gp.read_text()
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 122',g)
g=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.0-build122-work45-only"',g)
gp.write_text(g)

final=p.read_text()
if 'qualifiedBreak45' not in final:
    raise SystemExit('45-minute helper was not inserted')
if 'workWindowMinutes=0;otherWorkWindowMinutes=0' in final:
    raise SystemExit('Other work would incorrectly reset at 45 minutes')
print('Continuous-work 45-minute reset patch applied; cycle_hook='+str(inserted))
