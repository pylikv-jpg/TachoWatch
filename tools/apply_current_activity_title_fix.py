from pathlib import Path

p=Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s=p.read_text(encoding='utf-8')
marker='CURRENT_ACTIVITY_HEADER_SAFE_V1'
if marker in s:
    print('safe current activity header already applied')
    raise SystemExit(0)

old='val a=progressCard();activityFrame=a.first;activityProgress=a.second;activityTitle=label("ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ");a.third.addView(activityTitle)'
new='val a=progressCard();activityFrame=a.first;activityProgress=a.second;a.third.addView(label("ТЕКУЩИЙ ВИД ДЕЯТЕЛЬНОСТИ"));activityTitle=label("ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ");a.third.addView(activityTitle) // CURRENT_ACTIVITY_HEADER_SAFE_V1'
if old not in s:
    raise SystemExit('activity card anchor not found')
s=s.replace(old,new,1)

p.write_text(s,encoding='utf-8')
print('Added permanent current activity header without changing runtime activity logic')
