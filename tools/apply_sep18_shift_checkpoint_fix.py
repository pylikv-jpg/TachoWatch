from pathlib import Path

def replace_once(s, old, new, label):
    if old not in s:
        raise SystemExit(f"Sep18 anchor missing: {label}")
    return s.replace(old, new, 1)

p=Path("app/src/main/java/com/pylikv/tachowatch/ShiftStateRecoveryProvider.kt")
s=p.read_text(encoding="utf-8")
s=replace_once(s,
'''                .putInt(DriverLiveService.SHIFT_COMPLETED, seed.completedDrivingMinutes)
                .putInt(DriverLiveService.SHIFT_PREV_CONTINUOUS, seed.liveDrivingSegmentMinutes)''',
'''                // Card read is the authoritative checkpoint for shift driving.
                // Store the complete card shift total as the base and the live F923 value
                // visible at that checkpoint as the delta anchor. The service will add only
                // F923 growth after this checkpoint, never the whole F923 again.
                .putInt(DriverLiveService.SHIFT_COMPLETED, seed.drivingMinutes)
                .putInt(DriverLiveService.SHIFT_PREV_CONTINUOUS,
                    prefs.getInt(DriverLiveService.SNAP_CONTINUOUS_MIN, seed.liveDrivingSegmentMinutes))''',
"card shift checkpoint")
p.write_text(s,encoding="utf-8")

p=Path("app/src/main/java/com/pylikv/tachowatch/DriverLiveService.kt")
s=p.read_text(encoding="utf-8")
old='''        if (!shiftCounterInitialized) {
            previousContinuousMinutes = continuousMinutes
            shiftCounterInitialized = true
        } else if (previousContinuousMinutes > 0 && continuousMinutes < previousContinuousMinutes && restMinutes < 9 * 60) {
            shiftCompletedMinutes += previousContinuousMinutes
        }
        previousContinuousMinutes = continuousMinutes'''
new='''        if (!shiftCounterInitialized) {
            previousContinuousMinutes = continuousMinutes
            shiftCounterInitialized = true
        } else if (continuousMinutes >= previousContinuousMinutes) {
            // Card/cache base already contains everything up to previousContinuousMinutes.
            // Add only new live driving since that anchor.
            shiftCompletedMinutes += continuousMinutes - previousContinuousMinutes
            previousContinuousMinutes = continuousMinutes
        } else {
            // F923 reset: after a qualifying 45-minute break it starts a new cycle.
            // The completed cycle has already been integrated minute-by-minute above,
            // therefore never add the old F923 value again.
            previousContinuousMinutes = continuousMinutes
        }'''
s=replace_once(s,old,new,"live delta integration")
s=replace_once(s,
'''    private fun shiftDrivingTotal(): Int = shiftCompletedMinutes + continuousMinutes''',
'''    private fun shiftDrivingTotal(): Int = shiftCompletedMinutes''',
"shift total base plus delta")
p.write_text(s,encoding="utf-8")

p=Path("app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivityV2.kt")
s=p.read_text(encoding="utf-8")
s=replace_once(s,
'''val total=if(dailyRestClosed)0 else shiftCompletedMinutes+continuousMinutes;val limit=''',
'''val total=if(dailyRestClosed)0 else shiftCompletedMinutes;val limit=''',
"V2 checkpoint total")
p.write_text(s,encoding="utf-8")

print("Applied shift-driving card checkpoint + live delta model")
