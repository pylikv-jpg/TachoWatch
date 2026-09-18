from pathlib import Path

def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Sep18 patch anchor not found: {label}")
    return text.replace(old, new, 1)

# 1) Daily-rest boundary: never carry the last F923 segment into the next shift.
p = Path("app/src/main/java/com/pylikv/tachowatch/DriverLiveService.kt")
s = p.read_text(encoding="utf-8")
s = replace_once(
    s,
    """        if (restMinutes >= 9 * 60) {
            shiftCompletedMinutes = 0
            previousContinuousMinutes = continuousMinutes""",
    """        if (restMinutes >= 9 * 60) {
            shiftCompletedMinutes = 0
            // F923 may still expose the last driving segment throughout the daily rest.
            // Do not use that stale value as the baseline of the next shift; otherwise
            // the later F923 reset (e.g. 0:33 -> 0:00) is misread as a completed segment.
            previousContinuousMinutes = 0""",
    "daily-rest stale F923 baseline",
)

# 2) OTHER WORK / AVAILABILITY: on an activity transition F927 already contains the
# elapsed duration of the new segment. Seed that duration instead of discarding it.
s = replace_once(
    s,
    """        } else {
            previousActivity = currentActivity
            previousActivityDuration = sourceNow
        }

        // A single 15-minute part""",
    """        } else {
            previousActivity = currentActivity
            previousActivityDuration = sourceNow
            when {
                isOtherWork(currentActivity) -> {
                    workWindowMinutes += sourceNow
                    otherWorkWindowMinutes += sourceNow
                }
                isAvailability(currentActivity) -> availabilityWindowMinutes += sourceNow
            }
        }

        // A single 15-minute part""",
    "seed new non-driving activity segment",
)
p.write_text(s, encoding="utf-8")

# 3) UI: once an uninterrupted >=9h daily rest is visible, shift driving is 0:00 even
# while DTCO F923 still reports the previous shift's final continuous-driving segment.
p = Path("app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivityV2.kt")
s = p.read_text(encoding="utf-8")
s = replace_once(
    s,
    """val total=shiftCompletedMinutes+continuousMinutes;val limit=""",
    """val dailyRestClosed=(currentActivity.contains("ОТДЫХ",true)||currentActivity.contains("ПЕРЕРЫВ",true))&&activityMinutes>=9*60;val total=if(dailyRestClosed)0 else shiftCompletedMinutes+continuousMinutes;val limit=""",
    "V2 shift-driving zero during completed daily rest",
)
p.write_text(s, encoding="utf-8")

print("Applied Sep18 shift boundary + OTHER WORK transition repair")
