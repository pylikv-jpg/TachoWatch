from pathlib import Path

p=Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s=p.read_text(encoding='utf-8')
marker='CURRENT_ACTIVITY_TITLE_FIX_V1'
if marker in s:
    print('current activity title fix already applied')
    raise SystemExit(0)

old='activityTitle=label("ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ")'
new='activityTitle=label("ТЕКУЩИЙ ВИД ДЕЯТЕЛЬНОСТИ") // CURRENT_ACTIVITY_TITLE_FIX_V1'
if old not in s:
    raise SystemExit('initial activity title anchor not found')
s=s.replace(old,new,1)

old='activityTitle.text="🛏  ОТДЫХ"'
new='activityTitle.text="ТЕКУЩИЙ ВИД ДЕЯТЕЛЬНОСТИ • 🛏 ОТДЫХ"'
if old not in s:
    raise SystemExit('rest activity title anchor not found')
s=s.replace(old,new,1)

old='activityTitle.text="$icon  ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ • $currentActivity"'
new='activityTitle.text="ТЕКУЩИЙ ВИД ДЕЯТЕЛЬНОСТИ • $icon $currentActivity"'
if old not in s:
    raise SystemExit('live activity title anchor not found')
s=s.replace(old,new,1)

p.write_text(s,encoding='utf-8')
print('Restored permanent current activity card as first dashboard card')
