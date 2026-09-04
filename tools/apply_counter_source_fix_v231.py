from pathlib import Path

# Build-time correction for the live counter engine.
# Direct DTCO sources stay direct: F903 activity, F927 current activity duration,
# F923 continuous driving, F925 break, F938 two-week driving.

core = Path('app/src/main/java/com/pylikv/tachowatch/RtoCore.kt')
s = core.read_text(encoding='utf-8')

# User-confirmed TachoWatch rule: the 6h continuous-work clock is driving + other work
# and is reset by the same completed 45-minute pause, not by a 15-minute fragment.
s = s.replace(
    'if (sample.activity == Activity.REST && sample.activityMinutes >= 15 &&\n            (s.previousActivity != Activity.REST || s.previousActivityMinutes < 15)',
    'if (sample.activity == Activity.REST && sample.activityMinutes >= 45 &&\n            (s.previousActivity != Activity.REST || s.previousActivityMinutes < 45)'
)
s = s.replace(
    'val continuousWork = if (sample.activity == Activity.REST && sample.activityMinutes >= 15) 0',
    'val continuousWork = if (sample.activity == Activity.REST && sample.activityMinutes >= 45) 0'
)
core.write_text(s, encoding='utf-8')

runtime = Path('app/src/main/java/com/pylikv/tachowatch/RtoRuntime.kt')
r = runtime.read_text(encoding='utf-8')
old = '''        prefs.edit()\n            .putInt("seed_driving", history.days.lastOrNull()?.drivingMinutes ?: 0)\n            .putInt("seed_work", history.days.lastOrNull()?.let { it.drivingMinutes + it.workMinutes } ?: 0)\n            .putInt("seed_other", history.days.lastOrNull()?.workMinutes ?: 0)\n            .putInt("seed_avail", history.days.lastOrNull()?.availabilityMinutes ?: 0)\n            .apply()\n'''
new = '''        val latest = history.days.lastOrNull()\n        prefs.edit()\n            .putInt("seed_driving", latest?.drivingMinutes ?: 0)\n            .putInt("seed_work", latest?.let { it.drivingMinutes + it.workMinutes } ?: 0)\n            .putInt("seed_other", latest?.workMinutes ?: 0)\n            .putInt("seed_avail", latest?.availabilityMinutes ?: 0)\n            .apply()\n        // Do not keep counters synthesized by an older build. Reconstruct the state\n        // from the card snapshot plus the next atomic live DTCO cycle.\n        state = RtoCore.State()\n'''
if old not in r:
    raise SystemExit('RtoRuntime seed anchor not found')
r = r.replace(old, new, 1)
runtime.write_text(r, encoding='utf-8')

# Force one fresh full card read after installing this counter-engine revision so
# the reseed cannot use an old DDD snapshot left by previous builds.
dash = Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt')
d = dash.read_text(encoding='utf-8')
d = d.replace('private const val FIRST_READ="first_card_read_done"', 'private const val FIRST_READ="first_card_read_done_v231"')
dash.write_text(d, encoding='utf-8')

print('Applied DTCO source/counter fix v231')
