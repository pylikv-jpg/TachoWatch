from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Shift-driving v347 anchor not found: {label}")
    return text.replace(old, new, 1)

# Build 347: isolate shift-driving from the legacy SHIFT_COMPLETED + raw F923 sum.
# A card read becomes an authoritative baseline. Live F923 contributes only minutes
# that appeared AFTER that baseline, so card history cannot be counted twice.
p = Path("app/src/main/java/com/pylikv/tachowatch/DriverLiveService.kt")
s = p.read_text(encoding="utf-8")
s = replace_once(s,
'''        const val SHIFT_PREV_CONTINUOUS = "shift_prev_continuous"\n        const val WORK_WINDOW = "work_window_minutes"''',
'''        const val SHIFT_PREV_CONTINUOUS = "shift_prev_continuous"\n        const val SHIFT_INDEPENDENT_ANCHORED = "shift_independent_anchored"\n        const val SHIFT_INDEPENDENT_BASE = "shift_independent_base"\n        const val SHIFT_INDEPENDENT_POST = "shift_independent_post"\n        const val SHIFT_INDEPENDENT_LIVE_BASE = "shift_independent_live_base"\n        const val SHIFT_INDEPENDENT_PREV = "shift_independent_prev"\n        const val SHIFT_INDEPENDENT_TOTAL = "shift_independent_total"\n        const val WORK_WINDOW = "work_window_minutes"''', "service constants")
s = replace_once(s,
'''    private var previousContinuousMinutes = 0\n    private var workWindowMinutes = 0''',
'''    private var previousContinuousMinutes = 0\n    private var independentShiftAnchored = false\n    private var independentShiftBase = 0\n    private var independentShiftPost = 0\n    private var independentLiveBase = 0\n    private var independentPrevContinuous = 0\n    private var workWindowMinutes = 0''', "service state")
s = replace_once(s,
'''        previousContinuousMinutes = p.getInt(SHIFT_PREV_CONTINUOUS, 0)\n        workWindowMinutes = p.getInt(WORK_WINDOW, 0)''',
'''        previousContinuousMinutes = p.getInt(SHIFT_PREV_CONTINUOUS, 0)\n        independentShiftAnchored = p.getBoolean(SHIFT_INDEPENDENT_ANCHORED, false)\n        independentShiftBase = p.getInt(SHIFT_INDEPENDENT_BASE, 0)\n        independentShiftPost = p.getInt(SHIFT_INDEPENDENT_POST, 0)\n        independentLiveBase = p.getInt(SHIFT_INDEPENDENT_LIVE_BASE, 0)\n        independentPrevContinuous = p.getInt(SHIFT_INDEPENDENT_PREV, continuousMinutes)\n        workWindowMinutes = p.getInt(WORK_WINDOW, 0)''', "restore independent shift")
s = replace_once(s,
'''            .putInt(SHIFT_PREV_CONTINUOUS, previousContinuousMinutes)\n            .putInt(WORK_WINDOW, workWindowMinutes)''',
'''            .putInt(SHIFT_PREV_CONTINUOUS, previousContinuousMinutes)\n            .putBoolean(SHIFT_INDEPENDENT_ANCHORED, independentShiftAnchored)\n            .putInt(SHIFT_INDEPENDENT_BASE, independentShiftBase)\n            .putInt(SHIFT_INDEPENDENT_POST, independentShiftPost)\n            .putInt(SHIFT_INDEPENDENT_LIVE_BASE, independentLiveBase)\n            .putInt(SHIFT_INDEPENDENT_PREV, independentPrevContinuous)\n            .putInt(SHIFT_INDEPENDENT_TOTAL, shiftDrivingTotal())\n            .putInt(WORK_WINDOW, workWindowMinutes)''', "persist independent shift")
s = replace_once(s,
'''        // Keep the 318 shift-driving behaviour: a reset of F923 transfers the completed''',
'''        // Independent shift-driving path. The card baseline already includes the driving\n        // represented by F923 at read time. Count only growth after that point.\n        if (independentShiftAnchored) {\n            if (restMinutes >= 9 * 60) {\n                independentShiftBase = 0\n                independentShiftPost = 0\n                independentLiveBase = 0\n                independentPrevContinuous = continuousMinutes\n            } else if (independentPrevContinuous > 0 && continuousMinutes < independentPrevContinuous) {\n                independentShiftPost += (independentPrevContinuous - independentLiveBase).coerceAtLeast(0)\n                independentLiveBase = 0\n                independentPrevContinuous = continuousMinutes\n            } else {\n                independentPrevContinuous = continuousMinutes\n            }\n        }\n\n        // Keep the legacy path alive as fallback for installations that have not yet read a card.\n        // Keep the 318 shift-driving behaviour: a reset of F923 transfers the completed''', "independent process")
s = replace_once(s,
'''    private fun shiftDrivingTotal(): Int = shiftCompletedMinutes + continuousMinutes''',
'''    private fun shiftDrivingTotal(): Int = if (independentShiftAnchored) {\n        independentShiftBase + independentShiftPost + (continuousMinutes - independentLiveBase).coerceAtLeast(0)\n    } else {\n        shiftCompletedMinutes + continuousMinutes\n    }''', "independent total")
p.write_text(s, encoding="utf-8")

# Seed the independent shift counter from parsed card history. The F923 baseline is NOT
# the final contiguous DRIVING period. F923 is cumulative continuous-driving since the
# last qualifying >=45-minute REST and can span WORK/AVAILABILITY/short REST periods.
p = Path("app/src/main/java/com/pylikv/tachowatch/ShiftStateRecoveryProvider.kt")
s = p.read_text(encoding="utf-8")
s = replace_once(s,
'''        val driving = active.filter { it.type == "DRIVING" }.sumOf { it.minutes }\n        val lastDrivingIndex = active.indexOfLast { it.type == "DRIVING" }\n        val lastDrivingMinutes = active.getOrNull(lastDrivingIndex)?.minutes ?: 0\n        val tailAfterDriving = if (lastDrivingIndex >= 0) active.drop(lastDrivingIndex + 1) else emptyList()\n        val tailRestMinutes = tailAfterDriving.filter { it.type == "REST" }.sumOf { it.minutes }\n        val hasActiveNonRestAfterDriving = tailAfterDriving.any { it.type != "REST" }\n\n        val lastSegmentStillLive = lastDrivingIndex >= 0 && !hasActiveNonRestAfterDriving && tailRestMinutes < CONTINUOUS_BREAK_MINUTES\n        val liveSegment = if (lastSegmentStillLive) lastDrivingMinutes else 0\n        val completed = (driving - liveSegment).coerceAtLeast(0)''',
'''        val driving = active.filter { it.type == "DRIVING" }.sumOf { it.minutes }\n        val lastContinuousBreakIndex = active.indexOfLast {\n            it.type == "REST" && it.minutes >= CONTINUOUS_BREAK_MINUTES\n        }\n        val continuousWindow = if (lastContinuousBreakIndex >= 0) {\n            active.drop(lastContinuousBreakIndex + 1)\n        } else {\n            active\n        }\n        // Match F923 semantics: all driving after the last completed 45-minute break,\n        // even when short REST, WORK or AVAILABILITY periods split the driving pieces.\n        val liveSegment = continuousWindow.filter { it.type == "DRIVING" }.sumOf { it.minutes }\n        val completed = (driving - liveSegment).coerceAtLeast(0)''', "provider F923 cumulative baseline")
s = replace_once(s,
'''                .putInt(DriverLiveService.SHIFT_PREV_CONTINUOUS, seed.liveDrivingSegmentMinutes)\n                .putInt(DriverLiveService.WORK_WINDOW, continuousWorkMinutes)''',
'''                .putInt(DriverLiveService.SHIFT_PREV_CONTINUOUS, seed.liveDrivingSegmentMinutes)\n                .putBoolean(DriverLiveService.SHIFT_INDEPENDENT_ANCHORED, true)\n                .putInt(DriverLiveService.SHIFT_INDEPENDENT_BASE, seed.drivingMinutes)\n                .putInt(DriverLiveService.SHIFT_INDEPENDENT_POST, 0)\n                .putInt(DriverLiveService.SHIFT_INDEPENDENT_LIVE_BASE, seed.liveDrivingSegmentMinutes)\n                .putInt(DriverLiveService.SHIFT_INDEPENDENT_PREV, seed.liveDrivingSegmentMinutes)\n                .putInt(DriverLiveService.SHIFT_INDEPENDENT_TOTAL, seed.drivingMinutes)\n                .putInt(DriverLiveService.WORK_WINDOW, continuousWorkMinutes)''', "provider independent seed")
p.write_text(s, encoding="utf-8")

# The V2 dashboard displays the independent total when available. No change is made to
# continuous driving, work, break, weekly or two-week counters in this patch.
p = Path("app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivityV2.kt")
s = p.read_text(encoding="utf-8")
s = replace_once(s,
'''val total=shiftCompletedMinutes+continuousMinutes;val limit=''',
'''val total=if(prefs.getBoolean(DriverLiveService.SHIFT_INDEPENDENT_ANCHORED,false))prefs.getInt(DriverLiveService.SHIFT_INDEPENDENT_TOTAL,shiftCompletedMinutes+continuousMinutes) else shiftCompletedMinutes+continuousMinutes;val limit=''', "dashboard independent total")
p.write_text(s, encoding="utf-8")

print("Applied build 347 independent shift-driving baseline with cumulative F923 card anchor")
