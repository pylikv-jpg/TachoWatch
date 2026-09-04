from pathlib import Path

path = Path("app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt")
text = path.read_text(encoding="utf-8")
marker = "SHIFT_REST_DASHBOARD_FIX_V1"
if marker in text:
    print("shift/rest/dashboard fix already applied")
    raise SystemExit(0)

old = '        private const val WORK_ACC="other_work_window_minutes"; private const val AVAIL_ACC="availability_window_minutes"\n'
new = old + '        private const val DAILY_REST_RESET_LATCH="daily_rest_shift_reset_latch" // SHIFT_REST_DASHBOARD_FIX_V1\n'
if old not in text:
    raise SystemExit("anchor 1 not found")
text = text.replace(old, new, 1)

old = '    private var workWindowMinutes=0; private var previousActivity="—"; private var previousActivityDuration=0; private var otherWorkWindowMinutes=0; private var availabilityWindowMinutes=0\n'
new = old + '    private var dailyRestResetLatched=false; private var pendingCardShiftDriving:Int?=null\n'
if old not in text:
    raise SystemExit("anchor 2 not found")
text = text.replace(old, new, 1)

# Remove the two separate dashboard cards. Their counters remain active internally.
old = '        val ow=progressCard();otherWorkFrame=ow.first;otherWorkProgress=ow.second;ow.third.addView(label("⚒  ДРУГАЯ РАБОТА"));otherWork=value("0:00",26f);ow.third.addView(otherWork);ow.third.addView(sub("в текущем 6-часовом рабочем окне"));c.addView(otherWorkFrame);c.addView(space(7))\n        val av=progressCard();availabilityFrame=av.first;availabilityProgress=av.second;av.third.addView(label("✉  ОЖИДАНИЕ / ГОТОВНОСТЬ"));availability=value("0:00",26f);av.third.addView(availability);av.third.addView(sub("в текущем рабочем окне"));c.addView(availabilityFrame);c.addView(space(7))\n'
if old not in text:
    raise SystemExit("dashboard cards anchor not found")
text = text.replace(old, '', 1)

old_prefix = '    private fun restoreCounters(){shiftCounterInitialized=prefs.getBoolean(SHIFT_INITIALIZED,false);shiftCompletedMinutes=prefs.getInt(SHIFT_COMPLETED,0);previousContinuousMinutes=prefs.getInt(SHIFT_PREV_CONTINUOUS,0);workWindowMinutes=prefs.getInt(WORK_WINDOW,0);previousActivity=prefs.getString(WORK_PREV_ACTIVITY,"—")?:"—";previousActivityDuration=prefs.getInt(WORK_PREV_DURATION,0);otherWorkWindowMinutes=prefs.getInt(WORK_ACC,0);availabilityWindowMinutes=prefs.getInt(AVAIL_ACC,0)}\n    private fun persistCounters(){prefs.edit().putBoolean(SHIFT_INITIALIZED,shiftCounterInitialized).putInt(SHIFT_COMPLETED,shiftCompletedMinutes).putInt(SHIFT_PREV_CONTINUOUS,previousContinuousMinutes).putInt(WORK_WINDOW,workWindowMinutes).putString(WORK_PREV_ACTIVITY,previousActivity).putInt(WORK_PREV_DURATION,previousActivityDuration).putInt(WORK_ACC,otherWorkWindowMinutes).putInt(AVAIL_ACC,availabilityWindowMinutes).apply()}\n\n    private fun initializeShiftCounterFromCard(){if(shiftCounterInitialized)return;val cardDriving=history?.days?.lastOrNull()?.drivingMinutes?:0;shiftCompletedMinutes=(cardDriving-continuousMinutes).coerceAtLeast(0);previousContinuousMinutes=continuousMinutes;shiftCounterInitialized=true;persistCounters()}\n'
old_process_legacy = '    private fun processCycle(){initializeShiftCounterFromCard();if(previousContinuousMinutes>0&&continuousMinutes<previousContinuousMinutes&&breakMinutes>=45)shiftCompletedMinutes+=previousContinuousMinutes;previousContinuousMinutes=continuousMinutes;processWorkWindow();persistCounters();updateShiftDriving()}\n'
old_process_current = '    private fun processCycle(){initializeShiftCounterFromCard();if(previousContinuousMinutes>0&&continuousMinutes<previousContinuousMinutes)shiftCompletedMinutes+=previousContinuousMinutes;previousContinuousMinutes=continuousMinutes;processWorkWindow();persistCounters();updateShiftDriving()}\n'
old = None
for candidate in (old_prefix + old_process_current, old_prefix + old_process_legacy):
    if candidate in text:
        old = candidate
        break
if old is None:
    raise SystemExit("counter logic anchor not found")
new = '''    private fun restoreCounters(){shiftCounterInitialized=prefs.getBoolean(SHIFT_INITIALIZED,false);shiftCompletedMinutes=prefs.getInt(SHIFT_COMPLETED,0);previousContinuousMinutes=prefs.getInt(SHIFT_PREV_CONTINUOUS,0);workWindowMinutes=prefs.getInt(WORK_WINDOW,0);previousActivity=prefs.getString(WORK_PREV_ACTIVITY,"—")?:"—";previousActivityDuration=prefs.getInt(WORK_PREV_DURATION,0);otherWorkWindowMinutes=prefs.getInt(WORK_ACC,0);availabilityWindowMinutes=prefs.getInt(AVAIL_ACC,0);dailyRestResetLatched=prefs.getBoolean(DAILY_REST_RESET_LATCH,false)}
    private fun persistCounters(){prefs.edit().putBoolean(SHIFT_INITIALIZED,shiftCounterInitialized).putInt(SHIFT_COMPLETED,shiftCompletedMinutes).putInt(SHIFT_PREV_CONTINUOUS,previousContinuousMinutes).putInt(WORK_WINDOW,workWindowMinutes).putString(WORK_PREV_ACTIVITY,previousActivity).putInt(WORK_PREV_DURATION,previousActivityDuration).putInt(WORK_ACC,otherWorkWindowMinutes).putInt(AVAIL_ACC,availabilityWindowMinutes).putBoolean(DAILY_REST_RESET_LATCH,dailyRestResetLatched).apply()}

    // A work shift may contain several tachograph BEGIN/END sessions. We only split it
    // after an explicit END followed by at least 9 hours of daily rest.
    private fun currentCardWorkShiftDriving():Int{
        val days=history?.days.orEmpty();if(days.isEmpty())return 0
        var total=0
        for(i in days.indices.reversed()){
            total+=days[i].drivingMinutes
            if(i==0)break
            val previous=days[i-1];val current=days[i]
            val gap=HistoryData.actualGapMinutes(previous,current)
            val explicitClose=previous.endCountry!=null
            if(explicitClose&&gap!=null&&gap>=9*60)break
        }
        return total
    }

    private fun applyPendingCardShiftSync(){
        val cardDriving=pendingCardShiftDriving?:return
        if(continuousMinutes<=0)return
        shiftCompletedMinutes=(cardDriving-continuousMinutes).coerceAtLeast(0)
        previousContinuousMinutes=continuousMinutes
        shiftCounterInitialized=true
        pendingCardShiftDriving=null
    }

    private fun syncShiftCounterFromFreshCard(){
        val cardDriving=currentCardWorkShiftDriving()
        pendingCardShiftDriving=cardDriving
        if(continuousMinutes>0)applyPendingCardShiftSync()
        else{shiftCompletedMinutes=cardDriving;previousContinuousMinutes=0;shiftCounterInitialized=true}
        persistCounters();updateShiftDriving()
    }

    private fun initializeShiftCounterFromCard(){if(shiftCounterInitialized)return;val cardDriving=currentCardWorkShiftDriving();shiftCompletedMinutes=(cardDriving-continuousMinutes).coerceAtLeast(0);previousContinuousMinutes=continuousMinutes;shiftCounterInitialized=true;persistCounters()}

    private fun qualifyingDailyRestAfterClose():Boolean{
        if(!currentActivity.contains("ОТДЫХ"))return false
        if(maxOf(activityMinutes,breakMinutes)<9*60)return false
        return history?.days?.lastOrNull{it.endCountry!=null}!=null
    }

    private fun resetShiftCounterForDailyRest(){shiftCompletedMinutes=0;previousContinuousMinutes=0;shiftCounterInitialized=true;pendingCardShiftDriving=null}

    private fun processCycle(){
        if(qualifyingDailyRestAfterClose()){
            if(!dailyRestResetLatched){resetShiftCounterForDailyRest();dailyRestResetLatched=true}
            processWorkWindow();persistCounters();updateShiftDriving();return
        }
        if(!currentActivity.contains("ОТДЫХ"))dailyRestResetLatched=false
        applyPendingCardShiftSync();initializeShiftCounterFromCard()
        if(previousContinuousMinutes>0&&continuousMinutes<previousContinuousMinutes)shiftCompletedMinutes+=previousContinuousMinutes
        previousContinuousMinutes=continuousMinutes;processWorkWindow();persistCounters();updateShiftDriving()
    }
'''
text = text.replace(old, new, 1)

old = '    private fun resetAllWindows(){shiftCompletedMinutes=0;previousContinuousMinutes=0;shiftCounterInitialized=true;workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;previousActivity="—";previousActivityDuration=0;persistCounters();updateShiftDriving()}\n    private fun onShiftClosedDetected(){resetAllWindows();startCardRead("Смена закрыта",true)}\n'
new = '    private fun resetAllWindows(){shiftCompletedMinutes=0;previousContinuousMinutes=0;shiftCounterInitialized=true;workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;previousActivity="—";previousActivityDuration=0;persistCounters();updateShiftDriving()}\n    private fun onShiftClosedDetected(){startCardRead("Смена закрыта",true)}\n'
if old not in text:
    raise SystemExit("shift close anchor not found")
text = text.replace(old, new, 1)

old = '    override fun onLogChanged(fullLog:String){if(!cardReading)return;when{fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER)&&fullLog.contains("STATUS=SUCCESS")->runOnUiThread{prefs.edit().putBoolean(FIRST_READ,true).apply();loadHistory();finishCardRead(true)};fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER)&&fullLog.contains("STATUS=FAILED")->runOnUiThread{finishCardRead(false)}}}\n'
new = '    override fun onLogChanged(fullLog:String){if(!cardReading)return;when{fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER)&&fullLog.contains("STATUS=SUCCESS")->runOnUiThread{prefs.edit().putBoolean(FIRST_READ,true).apply();loadHistory();syncShiftCounterFromFreshCard();finishCardRead(true)};fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER)&&fullLog.contains("STATUS=FAILED")->runOnUiThread{finishCardRead(false)}}}\n'
if old not in text:
    raise SystemExit("card read anchor not found")
text = text.replace(old, new, 1)

old = '        val ow=activeOtherWorkTotal();otherWork.text=HistoryData.fmt(ow);setProgress(otherWorkFrame,otherWorkProgress,ow/360f,workColor(ow))\n        val av=activeAvailabilityTotal();availability.text=HistoryData.fmt(av);setProgress(availabilityFrame,availabilityProgress,av/360f,GREEN)\n'
if old not in text:
    raise SystemExit("dashboard update anchor not found")
text = text.replace(old, '', 1)

path.write_text(text, encoding="utf-8")
print("Applied work-shift synchronization, daily-rest boundary, and compact dashboard fix")
