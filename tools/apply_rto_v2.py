from pathlib import Path
import re

p = Path("app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt")
text = p.read_text(encoding="utf-8")
marker = "RTO_V2_DASHBOARD"
if marker in text:
    print("RTO v2 dashboard integration already applied")
    raise SystemExit(0)

old = "    private lateinit var live:LiveDidDiagnostic; private lateinit var cardReader:DtcoBluetoothDiagnostic\n"
new = old + "    private lateinit var rto:RtoRuntime // RTO_V2_DASHBOARD\n    private var rtoSnapshot:RtoCore.Snapshot?=null\n"
if old not in text:
    raise SystemExit("RTO v2: runtime property anchor not found")
text = text.replace(old, new, 1)

old = "live=LiveDidDiagnostic(applicationContext,this);cardReader=DtcoBluetoothDiagnostic(applicationContext,this);restoreCounters();buildUi();loadHistory();requestPermission()"
new = "live=LiveDidDiagnostic(applicationContext,this);cardReader=DtcoBluetoothDiagnostic(applicationContext,this);rto=RtoRuntime(applicationContext);restoreCounters();buildUi();loadHistory();rto.seedFromCard(history);requestPermission()"
if old not in text:
    raise SystemExit("RTO v2: onCreate anchor not found")
text = text.replace(old, new, 1)

# Replace legacy shift/work counter processing with one regulation-based state machine.
pattern = re.compile(r"    private fun processCycle\(\)\{.*?\}\n    private fun processWorkWindow\(\)\{", re.S)
m = pattern.search(text)
if not m:
    raise SystemExit("RTO v2: processCycle anchor not found")
replacement = '''    private fun processCycle(){
        rtoSnapshot=rto.update(currentActivity,activityMinutes,continuousMinutes,history)
        persistCounters()
        updateShiftDriving()
    }
    private fun processWorkWindow(){'''
text = text[:m.start()] + replacement + text[m.end():]

old = '''    private fun activeWorkTotal():Int=workWindowMinutes+when{currentActivity.contains("ВОЖДЕНИЕ")||currentActivity.contains("РАБОТА")->activityMinutes;else->0}
    private fun activeOtherWorkTotal():Int=otherWorkWindowMinutes+if(currentActivity.contains("РАБОТА"))activityMinutes else 0
    private fun activeAvailabilityTotal():Int=availabilityWindowMinutes+if(currentActivity.contains("ГОТОВНОСТЬ"))activityMinutes else 0
'''
new = '''    private fun activeWorkTotal():Int=rtoSnapshot?.continuousWorkMinutes?:0
    private fun activeOtherWorkTotal():Int=rtoSnapshot?.otherWorkDailyMinutes?:0
    private fun activeAvailabilityTotal():Int=rtoSnapshot?.availabilityDailyMinutes?:0
'''
if old not in text:
    raise SystemExit("RTO v2: active totals anchor not found")
text = text.replace(old, new, 1)

pattern = re.compile(r"    private fun updateShiftDriving\(\)\{.*?\}\n    private fun shiftDrivingLimit\(\):Int=.*?\n    private fun currentWeekTenHourUses\(\):Int\{.*?\}\n", re.S)
m = pattern.search(text)
if not m:
    raise SystemExit("RTO v2: shift driving block anchor not found")
replacement = '''    private fun updateShiftDriving(){
        if(!::shiftDriving.isInitialized)return
        val snap=rtoSnapshot
        if(snap==null){shiftDriving.text="—";shiftDrivingSub.text="ожидание live-данных";return}
        val total=snap.dailyDrivingMinutes
        val limit=snap.dailyDrivingLimitMinutes
        val remain=(limit-total).coerceAtLeast(0)
        shiftDriving.text="${HistoryData.fmt(total)} из ${HistoryData.fmt(limit)}"
        shiftDrivingSub.text=if(remain<=30)"⚠ Осталось ${HistoryData.fmt(remain)} вождения" else "осталось ${HistoryData.fmt(remain)} • 10ч: ${snap.tenHourDrivingUsesThisWeek}/2"
        setProgress(shiftDrivingFrame,shiftDrivingProgress,total.toFloat()/limit,shiftDriveColor(total,limit))
    }
    private fun shiftDrivingLimit():Int=rtoSnapshot?.dailyDrivingLimitMinutes?:RtoCore.DAILY_DRIVING_NORMAL
    private fun currentWeekTenHourUses():Int=rtoSnapshot?.tenHourDrivingUsesThisWeek?:0
'''
text = text[:m.start()] + replacement + text[m.end():]

# Manual END/country entry is not itself a legal daily-driving reset. Read the card,
# but let >=9h daily/weekly rest reset RTO counters.
old = '    private fun onShiftClosedDetected(){resetAllWindows();startCardRead("Смена закрыта",true)}\n'
if old in text:
    text = text.replace(old, '    private fun onShiftClosedDetected(){startCardRead("Смена закрыта",true)}\n', 1)

# Fresh card data seeds history only; live RTO state keeps its legal rest boundaries.
old = 'loadHistory();finishCardRead(true)'
if old in text:
    text = text.replace(old, 'loadHistory();rto.seedFromCard(history);finishCardRead(true)', 1)

# Rest card: large number is actual rest duration; credit is secondary.
old = '            activityTime.text=HistoryData.fmt(restCredited)\n'
if old in text:
    text = text.replace(old, '            activityTime.text=HistoryData.fmt(restActual)\n', 1)
old = '            activitySub.text=if(restNext!=null)"засчитано ${HistoryData.fmt(restCredited)} • фактически ${HistoryData.fmt(restActual)} • следующая ступень ${HistoryData.fmt(restNext)}" else "засчитано 45:00 • фактически ${HistoryData.fmt(restActual)}"\n'
new = '            activitySub.text=if(restNext!=null)"Засчитано: ${HistoryData.fmt(restCredited)} • следующая ступень ${HistoryData.fmt(restNext)}" else "Засчитано: ${HistoryData.fmt(restCredited)}"\n'
if old in text:
    text = text.replace(old, new, 1)

# Continuous work card: 6h clock is DRIVING+OTHER_WORK; >=15m qualifying break
# interrupts it. Required total work breaks are 30m (>6..9h) or 45m (>9h).
old = '        val wt=activeWorkTotal();work6.text="${HistoryData.fmt(wt)} из 6:00";work6Sub.text=if(wt>=330)"⚠ До 6 часов осталось ${HistoryData.fmt((360-wt).coerceAtLeast(0))}" else "вождение + другая работа";setProgress(work6Frame,work6Progress,wt/360f,workColor(wt))\n'
new = '''        val wt=activeWorkTotal();work6.text="${HistoryData.fmt(wt)} из 6:00";run{val s=rtoSnapshot;work6Sub.text=when{wt>=330->"⚠ До перерыва ${HistoryData.fmt((360-wt).coerceAtLeast(0))}";s!=null&&s.workBreakRequiredMinutes>0->"перерывы ${HistoryData.fmt(s.workBreakCreditedMinutes)} / ${HistoryData.fmt(s.workBreakRequiredMinutes)} • части ≥15 мин";else->"вождение + другая работа • перерыв ≥15 мин прерывает 6ч"}};setProgress(work6Frame,work6Progress,wt/360f,workColor(wt))
'''
if old not in text:
    raise SystemExit("RTO v2: work card anchor not found")
text = text.replace(old, new, 1)

p.write_text(text, encoding="utf-8")

# Rest milestones: 30m is meaningful for split driving break display.
h = Path("app/src/main/java/com/pylikv/tachowatch/HistoryData.kt")
htext = h.read_text(encoding="utf-8")
htext = htext.replace(
    'private val restMilestones = intArrayOf(15, 45, 3 * 60, 9 * 60, 11 * 60, 24 * 60, 45 * 60)',
    'private val restMilestones = intArrayOf(15, 30, 45, 3 * 60, 9 * 60, 11 * 60, 24 * 60, 45 * 60)'
)
h.write_text(htext, encoding="utf-8")
print("Applied canonical RTO v2 dashboard integration")
