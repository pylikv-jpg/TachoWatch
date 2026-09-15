from pathlib import Path

# TachoWatch v232 build-time corrections.
# 1) Alert thresholds are evaluated only while driving for continuous-driving warnings.
# 2) Alert state is reset on an actual F923 counter reset, not repeatedly during a pause.
# 3) Notification taps explicitly reopen the dashboard task.
# 4) History shows the latest 56 calendar days.
# 5) Existing latest DDD is reconciled once under recovery schema v2; no new card read required.

live = Path('app/src/main/java/com/pylikv/tachowatch/DriverLiveService.kt')
s = live.read_text(encoding='utf-8')

old = '''        if (!shiftCounterInitialized) {
            previousContinuousMinutes = continuousMinutes
            shiftCounterInitialized = true
        } else if (previousContinuousMinutes > 0 && continuousMinutes < previousContinuousMinutes && restMinutes < 9 * 60) {
            shiftCompletedMinutes += previousContinuousMinutes
        }
        previousContinuousMinutes = continuousMinutes
'''
new = '''        if (!shiftCounterInitialized) {
            previousContinuousMinutes = continuousMinutes
            shiftCounterInitialized = true
        } else if (previousContinuousMinutes > 0 && continuousMinutes < previousContinuousMinutes) {
            // F923 actually moved to a new continuous-driving cycle. Only now arm the
            // continuous-driving warnings for that new cycle. A long pause by itself must
            // not clear them while DTCO still reports the old F923 value.
            if (restMinutes < 9 * 60) shiftCompletedMinutes += previousContinuousMinutes
            clearAlertGroup("cont_")
        }
        previousContinuousMinutes = continuousMinutes
'''
if old not in s:
    raise SystemExit('DriverLiveService F923 reset anchor not found')
s = s.replace(old, new, 1)

# Do not repeatedly re-arm continuous-driving alerts merely because a break is in progress.
s = s.replace('''        if (restMinutes >= 45) {
            clearAlertGroup("cont_")
        }
''', '', 1)
# The daily-rest branch also ran every cycle. Continuous alerts are already reset by the
# actual F923 transition above; leave the other daily-shift groups unchanged.
s = s.replace('''            clearAlertGroup("cont_")
            clearAlertGroup("work_")
''', '''            clearAlertGroup("work_")
''', 1)

old = '''    private fun evaluateAlerts() {
        evaluateRemaining("cont", 270 - continuousMinutes, "непрерывного вождения", "Лимит непрерывного вождения 4 часа 30 минут достигнут")
        evaluateRemaining("work", 360 - activeWorkTotal(), "непрерывной работы", "Лимит непрерывной работы 6 часов достигнут")
'''
new = '''    private fun evaluateAlerts() {
        // F923 can retain the previous driving value during a pause. Never speak a driving
        // threshold while the driver is currently resting/working/available.
        if (isDriving(currentActivity)) {
            evaluateRemaining("cont", 270 - continuousMinutes, "непрерывного вождения", "Лимит непрерывного вождения 4 часа 30 минут достигнут")
        }
        evaluateRemaining("work", 360 - activeWorkTotal(), "непрерывной работы", "Лимит непрерывной работы 6 часов достигнут")
'''
if old not in s:
    raise SystemExit('DriverLiveService evaluateAlerts anchor not found')
s = s.replace(old, new, 1)

old = '''    private fun launchPendingIntent(): PendingIntent {
        val launch = Intent(this, DriverDashboardActivityV2::class.java)
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
'''
new = '''    private fun launchPendingIntent(): PendingIntent {
        val launch = Intent(this, DriverDashboardActivityV2::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(this, 1402, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
'''
if old not in s:
    raise SystemExit('DriverLiveService PendingIntent anchor not found')
s = s.replace(old, new, 1)
live.write_text(s, encoding='utf-8')

# 56-day history window.
dash = Path('app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivityV2.kt')
d = dash.read_text(encoding='utf-8')
d = d.replace('recentThreeWeeks(model?.days.orEmpty())', 'recent56Days(model?.days.orEmpty())', 1)
d = d.replace('История · 3 недели', 'История · 56 дней', 1)
d = d.replace('private fun recentThreeWeeks(days:List<HistoryData.Day>):List<HistoryData.Day>', 'private fun recent56Days(days:List<HistoryData.Day>):List<HistoryData.Day>', 1)
d = d.replace('add(Calendar.DAY_OF_MONTH,-20)', 'add(Calendar.DAY_OF_MONTH,-55)', 1)
if 'recentThreeWeeks(model?.days.orEmpty())' in d or 'История · 3 недели' in d:
    raise SystemExit('DriverDashboardActivityV2 56-day patch incomplete')
dash.write_text(d, encoding='utf-8')

# Re-run reconciliation once for the already-downloaded DDD after installing this schema.
# This intentionally does not initiate a card read.
recovery = Path('app/src/main/java/com/pylikv/tachowatch/ShiftStateRecoveryProvider.kt')
r = recovery.read_text(encoding='utf-8')
r = r.replace(
    'if (prefs.getString(KEY_CARD_FINGERPRINT, null) == fingerprint) return',
    'if (prefs.getString(KEY_CARD_FINGERPRINT, null) == fingerprint && prefs.getInt(KEY_RECOVERY_VERSION, 0) >= RECOVERY_VERSION) return',
    1
)
r = r.replace(
    '.putString(KEY_CARD_FINGERPRINT, fingerprint)\n                .putString(KEY_SHIFT_ID, seed.id)',
    '.putString(KEY_CARD_FINGERPRINT, fingerprint)\n                .putInt(KEY_RECOVERY_VERSION, RECOVERY_VERSION)\n                .putString(KEY_SHIFT_ID, seed.id)',
    1
)
r = r.replace(
    'private const val DAILY_REST_MINUTES = 9 * 60',
    'private const val DAILY_REST_MINUTES = 9 * 60\n        private const val RECOVERY_VERSION = 2\n        private const val KEY_RECOVERY_VERSION = "recovery_schema_version"',
    1
)
if 'KEY_RECOVERY_VERSION' not in r or 'RECOVERY_VERSION = 2' not in r:
    raise SystemExit('ShiftStateRecoveryProvider schema patch incomplete')
recovery.write_text(r, encoding='utf-8')

print('Applied live recovery/alerts/history fix v232')
