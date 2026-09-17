from pathlib import Path

# Applied at build time after the legacy compatibility scripts. Keep the patch small and
# fail loudly when an expected anchor changes so CI cannot silently ship a partial fix.

def replace_once(s: str, old: str, new: str, label: str) -> str:
    if old not in s:
        raise SystemExit(f"Sep17 patch anchor not found: {label}")
    return s.replace(old, new, 1)

# 1) Dashboard: all card history, permanent weekly-rest compensation display,
# and card-authoritative recovery of current shift/work state after a card read.
p = Path("app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt")
s = p.read_text(encoding="utf-8")

s = s.replace(
    'val days = history?.days?.takeLast(56)?.asReversed().orEmpty()',
    'val days = history?.days?.asReversed().orEmpty()'
)
s = s.replace(
    'c.addView(sub("История карты: ${days.size} из 56 дней"))',
    'c.addView(sub("История карты: ${days.size} смен • все считанные записи"))'
)

old_gap = '''                HistoryData.gapMinutes(days[i + 1], day)?.let { gap ->
                    c.addView(TextView(this).apply {
                        text = if (gap >= 24 * 60) "🛏 Недельный отдых  ${HistoryData.fmt(gap)}" else "🛏 Межсуточный отдых  ${HistoryData.fmt(gap)}"
                        textSize = 14f; gravity = Gravity.CENTER; setTextColor(if (gap >= 24 * 60) CYAN else MUTED); setPadding(0, dp(7), 0, dp(7))
                    })
                }
'''
new_gap = '''                HistoryData.gapMinutes(days[i + 1], day)?.let { gap ->
                    val rest = history?.restBetween(days[i + 1], day)
                    c.addView(TextView(this).apply {
                        val base = if (gap >= 24 * 60) "🛏 Недельный отдых  ${HistoryData.fmt(gap)}" else "🛏 Межсуточный отдых  ${HistoryData.fmt(gap)}"
                        val compensation = when {
                            rest == null || rest.compensationCreatedMinutes <= 0 -> ""
                            rest.compensationRemainingMinutes > 0 -> "\nКомпенсация ${HistoryData.fmt(rest.compensationCreatedMinutes)} • требуется до ${rest.compensationDueDate ?: "—"}"
                            else -> "\nКомпенсация ${HistoryData.fmt(rest.compensationCreatedMinutes)} • возмещена ${rest.compensationPaidDate ?: "—"}"
                        }
                        text = base + compensation
                        textSize = 14f; gravity = Gravity.CENTER; setTextColor(if (gap >= 24 * 60) CYAN else MUTED); setPadding(0, dp(7), 0, dp(7))
                    })
                }
'''
s = replace_once(s, old_gap, new_gap, "history compensation UI")

old_init = '''    private fun initialiseFallbackFromCard() {
        fallbackShiftBase = cardShiftDrivingMinutes()
        fallbackShiftLive = 0
        fallbackPreviousContinuous = continuousMinutes
        fallbackInitialized = true
        persistState()
    }
'''
new_init = '''    private fun initialiseFallbackFromCard() {
        // A successful card read is authoritative for the working-period baseline. Never
        // merge stale persisted counters from the previous shift into the newly read card.
        fallbackShiftBase = cardShiftDrivingMinutes()
        fallbackShiftLive = 0
        fallbackPreviousContinuous = continuousMinutes
        fallbackInitialized = true

        val latest = history?.days?.lastOrNull()
        if (latest != null) {
            var work = 0
            var other = 0
            var avail = 0
            for (period in latest.periods.asReversed()) {
                if (period.type == "REST" && period.minutes >= 30) break
                when (period.type) {
                    "DRIVING" -> work += period.minutes
                    "WORK" -> { work += period.minutes; other += period.minutes }
                    "AVAILABILITY" -> avail += period.minutes
                }
            }
            workWindowMinutes = work
            otherWorkWindowMinutes = other
            availabilityWindowMinutes = avail
            previousActivity = currentActivity
            previousActivityDuration = activityMinutes
        } else {
            workWindowMinutes = 0
            otherWorkWindowMinutes = 0
            availabilityWindowMinutes = 0
            previousActivity = currentActivity
            previousActivityDuration = activityMinutes
        }
        persistState()
    }
'''
s = replace_once(s, old_init, new_init, "card authoritative recovery")

old_close = '''        if (dailyRestReached && !shiftCloseHandled) {
            shiftCloseHandled = true
            fallbackShiftBase = 0
            fallbackShiftLive = 0
            fallbackPreviousContinuous = continuousMinutes
            fallbackInitialized = true
'''
new_close = '''        if (dailyRestReached && !shiftCloseHandled) {
            shiftCloseHandled = true
            fallbackShiftBase = 0
            fallbackShiftLive = 0
            fallbackPreviousContinuous = continuousMinutes
            fallbackInitialized = true
            workWindowMinutes = 0
            otherWorkWindowMinutes = 0
            availabilityWindowMinutes = 0
            previousActivity = currentActivity
            previousActivityDuration = activityMinutes
'''
s = replace_once(s, old_close, new_close, "daily-rest state reset")
p.write_text(s, encoding="utf-8")

# 2) Background service: a completed 45-minute break closes the old alert cycle.
# Do not re-arm 30/15/5-minute warnings until actual driving starts in the new F923 cycle.
p = Path("app/src/main/java/com/pylikv/tachowatch/DriverLiveService.kt")
s = p.read_text(encoding="utf-8")
s = replace_once(s,
    '        const val AVAIL_ACC = "availability_window_minutes"\n',
    '        const val AVAIL_ACC = "availability_window_minutes"\n        const val CONT_BREAK_ARMED = "continuous_break_completed_armed"\n',
    "alert state constant")
s = replace_once(s,
    '    private var availabilityWindowMinutes = 0\n',
    '    private var availabilityWindowMinutes = 0\n    private var continuousBreakArmed = false\n',
    "alert state field")
s = replace_once(s,
    '        availabilityWindowMinutes = p.getInt(AVAIL_ACC, 0)\n',
    '        availabilityWindowMinutes = p.getInt(AVAIL_ACC, 0)\n        continuousBreakArmed = p.getBoolean(CONT_BREAK_ARMED, false)\n',
    "restore alert state")
s = replace_once(s,
    '            .putInt(AVAIL_ACC, availabilityWindowMinutes)\n',
    '            .putInt(AVAIL_ACC, availabilityWindowMinutes)\n            .putBoolean(CONT_BREAK_ARMED, continuousBreakArmed)\n',
    "persist alert state")

old_45 = '''        if (restMinutes >= 45) {
            clearAlertGroup("cont_")
        }
'''
new_45 = '''        if (restMinutes >= 45) {
            // Close the OLD continuous-driving warning cycle. Keep its fired flags while
            // still resting, otherwise every live poll can emit the old 15-minute warning.
            continuousBreakArmed = true
            try { getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID) } catch (_: Throwable) {}
            try { tts?.stop() } catch (_: Throwable) {}
            pendingSpeech = null
        }
        if (continuousBreakArmed && isDriving(currentActivity) && continuousMinutes in 0..30) {
            // F923 has actually entered a new driving cycle: only now re-arm warnings.
            clearAlertGroup("cont_")
            continuousBreakArmed = false
        }
'''
s = replace_once(s, old_45, new_45, "continuous alert lifecycle")

# Daily rest also starts a clean cycle, but do not leave an armed transition behind.
s = replace_once(s,
    '            clearAlertGroup("cont_")\n            clearAlertGroup("work_")\n',
    '            clearAlertGroup("cont_")\n            continuousBreakArmed = false\n            clearAlertGroup("work_")\n',
    "daily rest alert reset")
p.write_text(s, encoding="utf-8")

print("Applied Sep17 agreed fixes: card reconciliation, alert cycle, full history, compensation ledger UI")
