from pathlib import Path

# Final, deliberately small patch applied AFTER build 347's independent shift counter.
# F923 remains the authoritative continuous-driving counter.
# F99A, when supported by the DTCO, is the authoritative current daily-driving total.
p = Path("app/src/main/java/com/pylikv/tachowatch/DriverLiveService.kt")
s = p.read_text(encoding="utf-8")

def replace_once(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f"F99A shift fix anchor not found: {label}")
    s = s.replace(old, new, 1)

replace_once(
    '    private var twoWeekMinutes = 0\n    private var lastProcessedCycle = 0',
    '    private var twoWeekMinutes = 0\n    private var dailyDrivingMinutes: Int? = null\n    private var lastProcessedCycle = 0',
    'daily field'
)

replace_once(
    '        mins(last(log, "F938"))?.let { twoWeekMinutes = it }\n',
    '        mins(last(log, "F938"))?.let { twoWeekMinutes = it }\n        dailyDrivingMinutes = mins(last(log, "F99A"))\n',
    'F99A parse'
)

old_total = '''    private fun shiftDrivingTotal(): Int = if (independentShiftAnchored) {
        independentShiftBase + independentShiftPost + (continuousMinutes - independentLiveBase).coerceAtLeast(0)
    } else {
        shiftCompletedMinutes + continuousMinutes
    }'''
new_total = '''    private fun shiftDrivingTotal(): Int = dailyDrivingMinutes ?: if (independentShiftAnchored) {
        independentShiftBase + independentShiftPost + (continuousMinutes - independentLiveBase).coerceAtLeast(0)
    } else {
        shiftCompletedMinutes + continuousMinutes
    }'''
replace_once(old_total, new_total, 'direct daily total')

p.write_text(s, encoding="utf-8")
print("Applied final F99A daily-driving source; F923 continuous-driving unchanged")
