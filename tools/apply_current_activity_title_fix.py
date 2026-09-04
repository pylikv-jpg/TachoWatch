from pathlib import Path

p=Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
s=p.read_text(encoding='utf-8')

# The previous UI patch inserted a // marker in the middle of a minified Kotlin line.
# That commented out c.addView(activityFrame), so the entire first card was built
# but never attached to the screen. Convert that marker to a block comment first.
s=s.replace(' // UI_UPDATE_SEP04', ' /* UI_UPDATE_SEP04 */')

marker='CURRENT_ACTIVITY_TITLE_FIX_V2'
if marker not in s:
    old='activityTitle=label("ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ")'
    new='activityTitle=label("ТЕКУЩИЙ ВИД ДЕЯТЕЛЬНОСТИ")/* CURRENT_ACTIVITY_TITLE_FIX_V2 */'
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
print('Restored current activity card attachment and title safely')
