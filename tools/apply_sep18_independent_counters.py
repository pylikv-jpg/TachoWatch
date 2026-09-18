from pathlib import Path

def replace_once(s, old, new, label):
    if old not in s:
        raise SystemExit(f"Independent counters anchor missing: {label}")
    return s.replace(old, new, 1)

p=Path("app/src/main/java/com/pylikv/tachowatch/DriverLiveService.kt")
s=p.read_text(encoding="utf-8")

s=replace_once(s,
'''        const val WORK_WINDOW = "work_window_minutes"
        const val WORK_PREV_ACTIVITY = "work_prev_activity"''',
'''        const val WORK_WINDOW = "work_window_minutes"
        const val CW_PREV_ACTIVITY = "continuous_work_prev_activity"
        const val CW_PREV_DURATION = "continuous_work_prev_duration"
        const val WORK_PREV_ACTIVITY = "work_prev_activity"''',
"continuous-work prefs")

s=replace_once(s,
'''    private var workWindowMinutes = 0
    private var previousActivity = "—"''',
'''    private var workWindowMinutes = 0
    private var continuousWorkPreviousActivity = "—"
    private var continuousWorkPreviousDuration = 0
    private var previousActivity = "—"''',
"continuous-work fields")

s=replace_once(s,
'''        workWindowMinutes = p.getInt(WORK_WINDOW, 0)
        previousActivity = p.getString(WORK_PREV_ACTIVITY, "—") ?: "—"''',
'''        workWindowMinutes = p.getInt(WORK_WINDOW, 0)
        continuousWorkPreviousActivity = p.getString(CW_PREV_ACTIVITY, "—") ?: "—"
        continuousWorkPreviousDuration = p.getInt(CW_PREV_DURATION, 0)
        previousActivity = p.getString(WORK_PREV_ACTIVITY, "—") ?: "—"''',
"restore independent work")

s=replace_once(s,
'''            .putInt(WORK_WINDOW, workWindowMinutes)
            .putString(WORK_PREV_ACTIVITY, previousActivity)''',
'''            .putInt(WORK_WINDOW, workWindowMinutes)
            .putString(CW_PREV_ACTIVITY, continuousWorkPreviousActivity)
            .putInt(CW_PREV_DURATION, continuousWorkPreviousDuration)
            .putString(WORK_PREV_ACTIVITY, previousActivity)''',
"persist independent work")

start=s.index("    private fun processCycle() {")
end=s.index("    private fun processWorkWindow(restMinutes: Int) {", start)
new_cycle='''    private fun processCycle() {
        val restNow = isRest(currentActivity)
        val restMinutes = if (restNow) maxOf(activityMinutes, breakMinutes) else 0
        val dailyRestCompleted = restMinutes >= 9 * 60

        // Counter 1: SHIFT DRIVING. Completely independent from continuous-work state.
        val shift = ShiftDrivingCounter.update(
            initialized = shiftCounterInitialized,
            totalMinutes = shiftCompletedMinutes,
            previousContinuousMinutes = previousContinuousMinutes,
            currentContinuousMinutes = continuousMinutes,
            dailyRestCompleted = dailyRestCompleted
        )
        shiftCounterInitialized = shift.initialized
        shiftCompletedMinutes = shift.totalMinutes
        previousContinuousMinutes = shift.previousContinuousMinutes

        // Counter 2: CONTINUOUS WORK. Completely independent from shift-driving state.
        // Same DTCO source, but its own state and its own 45-minute reset rule.
        val cw = ContinuousWorkCounter.update(
            state = ContinuousWorkCounter.State(
                workMinutes = workWindowMinutes,
                otherWorkMinutes = 0,
                previousActivity = continuousWorkPreviousActivity,
                previousSourceMinutes = continuousWorkPreviousDuration
            ),
            currentActivity = currentActivity,
            activityMinutes = activityMinutes,
            continuousDrivingMinutes = continuousMinutes,
            qualifyingRestMinutes = restMinutes
        )

        // Preserve the already-tested OTHER WORK / AVAILABILITY bookkeeping path.
        // It may update workWindowMinutes internally; overwrite only that one value with
        // the independent continuous-work result afterwards.
        processWorkWindow(restMinutes)
        workWindowMinutes = cw.workMinutes
        continuousWorkPreviousActivity = cw.previousActivity
        continuousWorkPreviousDuration = cw.previousSourceMinutes

        if (restMinutes >= 45) {
            clearAlertGroup("cont_")
            clearAlertGroup("work_")
        }

        if (dailyRestCompleted) {
            workWindowMinutes = 0
            continuousWorkPreviousActivity = currentActivity
            continuousWorkPreviousDuration = activitySourceMinutes()
            otherWorkWindowMinutes = 0
            availabilityWindowMinutes = 0
            previousActivity = currentActivity
            previousActivityDuration = activitySourceMinutes()
            clearAlertGroup("cont_")
            clearAlertGroup("work_")
            clearAlertGroup("shift9_")
            clearAlertGroup("shift10_")
        }
    }

'''
s=s[:start]+new_cycle+s[end:]

# The legacy bookkeeping path must no longer decide when CONTINUOUS WORK resets.
# Its 30-minute reset used to corrupt the independent 6h counter.
s=replace_once(s,
'''        if (restMinutes >= 30) {
            workWindowMinutes = 0
            clearAlertGroup("work_")
        }
        if (restMinutes >= 45) {
            clearAlertGroup("cont_")
        }''',
'''        // Continuous-work reset is owned exclusively by ContinuousWorkCounter (45 min).
        if (restMinutes >= 45) {
            clearAlertGroup("cont_")
        }''',
"remove legacy 30m work reset")

p.write_text(s,encoding="utf-8")
print("Applied independent shift-driving and continuous-work engines")
