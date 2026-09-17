from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Counter repair anchor not found: {label}")
    return text.replace(old, new, 1)

# Keep the currently approved driving counters untouched. Repair only the work window:
# DRIVING + OTHER WORK, reset by a completed 45-minute break. Daily/weekly rest also
# resets it because it necessarily exceeds 45 minutes.
p = Path("app/src/main/java/com/pylikv/tachowatch/DriverLiveService.kt")
s = p.read_text(encoding="utf-8")
s = replace_once(
    s,
    '''        if (restMinutes >= 30) {\n            workWindowMinutes = 0\n            clearAlertGroup("work_")\n        }''',
    '''        if (restMinutes >= 45) {\n            workWindowMinutes = 0\n            clearAlertGroup("work_")\n        }''',
    "live continuous-work 45-minute reset",
)
p.write_text(s, encoding="utf-8")

# Card reconciliation must restore CONTINUOUS work only from the part of history after
# the last qualifying >=45-minute REST. Other-work remains the full current-shift total,
# so a card read also corrects minutes that live polling may have missed at transitions.
p = Path("app/src/main/java/com/pylikv/tachowatch/ShiftStateRecoveryProvider.kt")
s = p.read_text(encoding="utf-8")
s = replace_once(
    s,
    '''            val parsed = TlvInventory.parse(file)\n            if (parsed.error != null) return\n            val seed = currentShiftSeed(HistoryData.load(parsed)) ?: return''',
    '''            val parsed = TlvInventory.parse(file)\n            if (parsed.error != null) return\n            val model = HistoryData.load(parsed)\n            val seed = currentShiftSeed(model) ?: return\n            val continuousWorkMinutes = currentContinuousWorkMinutes(model)''',
    "card model reuse",
)
s = replace_once(
    s,
    '''                .putInt(DriverLiveService.WORK_WINDOW, seed.drivingMinutes + seed.workMinutes)''',
    '''                .putInt(DriverLiveService.WORK_WINDOW, continuousWorkMinutes)''',
    "card continuous-work seed",
)
helper = '''\n    private fun currentContinuousWorkMinutes(model: HistoryData.Model): Int {\n        val latest = model.days.lastOrNull() ?: return 0\n        var total = 0\n        for (period in latest.periods.asReversed()) {\n            if (period.type == "REST" && period.minutes >= CONTINUOUS_BREAK_MINUTES) break\n            if (period.type == "DRIVING" || period.type == "WORK") total += period.minutes\n        }\n        return total\n    }\n\n'''
s = replace_once(
    s,
    '''    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null''',
    helper + '''    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null''',
    "continuous-work history helper",
)
p.write_text(s, encoding="utf-8")

# The legacy dashboard has its own card fallback. Keep it consistent with the same 45-min rule.
p = Path("app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt")
s = p.read_text(encoding="utf-8")
s = replace_once(
    s,
    '''                if (period.type == "REST" && period.minutes >= 30) break''',
    '''                if (period.type == "REST" && period.minutes >= 45) break''',
    "legacy card work reset",
)
p.write_text(s, encoding="utf-8")

# Daily rest is an uninterrupted current REST duration. It must not reuse F925/breakMinutes,
# because that value can retain a completed 15-minute break for the split 15+30 break logic.
p = Path("app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivityV2.kt")
s = p.read_text(encoding="utf-8")
old = '''        dailyRestTime.text="${HistoryData.fmt(actual.coerceAtMost(11*60))} / 11:00";dailyRestSub.text=when{actual>=11*60->"✓ нормальный суточный отдых 11:00";actual>=9*60->"✓ сокращённый 9:00 • до 11:00 ${HistoryData.fmt(11*60-actual)}";actual>=3*60->"✓ первая часть 3:00 зафиксирована • следующая ступень 9:00";resting->"до фиксации 3:00 осталось ${HistoryData.fmt((3*60-actual).coerceAtLeast(0))}";else->"ступени: 3:00 → 9:00 → 11:00"};val dailyColor=when{actual>=9*60->GREEN;actual>=3*60->YELLOW;else->RED};dailyRestTime.setTextColor(dailyColor);setRestProgress(dailyRestFrame,dailyRestProgress,actual.coerceAtMost(11*60)/(11f*60f),dailyColor)'''
new = '''        val dailyActual=if(resting)activityMinutes else 0;dailyRestTime.text="${HistoryData.fmt(dailyActual.coerceAtMost(11*60))} / 11:00";dailyRestSub.text=when{dailyActual>=11*60->"✓ нормальный суточный отдых 11:00";dailyActual>=9*60->"✓ сокращённый 9:00 • до 11:00 ${HistoryData.fmt(11*60-dailyActual)}";dailyActual>=3*60->"✓ первая часть 3:00 зафиксирована • следующая ступень 9:00";resting->"до фиксации 3:00 осталось ${HistoryData.fmt((3*60-dailyActual).coerceAtLeast(0))}";else->"ступени: 3:00 → 9:00 → 11:00"};val dailyColor=when{dailyActual>=9*60->GREEN;dailyActual>=3*60->YELLOW;else->RED};dailyRestTime.setTextColor(dailyColor);setRestProgress(dailyRestFrame,dailyRestProgress,dailyActual.coerceAtMost(11*60)/(11f*60f),dailyColor)'''
s = replace_once(s, old, new, "V2 daily-rest independent duration")
p.write_text(s, encoding="utf-8")

print("Applied Sep17 counter repair: 45-min work reset, card reconstruction, daily-rest isolation")
