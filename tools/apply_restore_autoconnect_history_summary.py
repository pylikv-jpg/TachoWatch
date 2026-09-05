from pathlib import Path

p = Path("app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt")
s = p.read_text(encoding="utf-8")

# The current dashboard already has automatic recovery in LiveDidDiagnostic plus
# the selected-DTCO reconnect status. The older source-rewrite patch uses compact
# one-line anchors and must not be applied to the rewritten dashboard.
if "Восстановление связи с DTCO" in s and "private lateinit var restTime: TextView" in s:
    print("Current dashboard already contains reconnect handling")
    raise SystemExit(0)

marker = "RESTORE_AUTOCONNECT_HISTORY_V1"
if marker in s:
    print("auto-connect/history summary already restored")
    raise SystemExit(0)

old = '    private lateinit var live:LiveDidDiagnostic; private lateinit var cardReader:DtcoBluetoothDiagnostic\n'
new = old + '    private var activityDestroyed=false // RESTORE_AUTOCONNECT_HISTORY_V1\n    private val reconnectRunnable=Runnable{if(activityDestroyed||cardReading)return@Runnable;val d=dtco?:return@Runnable;status.text="Переподключение к DTCO…";live.connect(d)}\n'
if old not in s:
    raise SystemExit("autoconnect state anchor not found")
s = s.replace(old, new, 1)

old = '    override fun onDestroy(){live.disconnect();cardReader.disconnect();super.onDestroy()}\n'
new = '    override fun onDestroy(){activityDestroyed=true;mainHandler.removeCallbacks(reconnectRunnable);live.disconnect();cardReader.disconnect();super.onDestroy()}\n'
if old not in s:
    raise SystemExit("onDestroy anchor not found")
s = s.replace(old, new, 1)

old = '    override fun onLiveConnection(connected:Boolean,deviceName:String?){runOnUiThread{if(!cardReading)status.text=if(connected)"Онлайн • ${deviceName?:"DTCO"}" else "Нет связи с выбранным DTCO"}}\n'
new = '''    override fun onLiveConnection(connected:Boolean,deviceName:String?){runOnUiThread{
        if(cardReading)return@runOnUiThread
        if(connected){
            mainHandler.removeCallbacks(reconnectRunnable)
            status.text="Онлайн • ${deviceName?:"DTCO"}"
        }else if(!activityDestroyed&&dtco!=null){
            status.text="Связь потеряна • переподключение…"
            mainHandler.removeCallbacks(reconnectRunnable)
            mainHandler.postDelayed(reconnectRunnable,1500L)
        }else status.text="DTCO не подключён"
    }}
'''
if old not in s:
    raise SystemExit("live connection anchor not found")
s = s.replace(old, new, 1)

anchor = '    private fun loadHistory(){'
helpers = '''    private fun historyReducedDailyRestUses():Int?{
        val rests=history?.rests.orEmpty()
        if(rests.isEmpty())return null
        val lastWeekly=rests.indexOfLast{it.weekly}
        return rests.drop(lastWeekly+1).count{!it.weekly&&!it.splitDaily&&it.creditedDailyMinutes==RtoCore.DAILY_REST_REDUCED}.coerceAtMost(RtoCore.MAX_REDUCED_DAILY_RESTS_BETWEEN_WEEKLY_RESTS)
    }
    private fun historyTenHourDrivingUses():Int{
        val now=isoCalendar(Date())
        return history?.days.orEmpty().count{d->
            val date=parseDateOnly(d.date)?:return@count false
            val c=isoCalendar(date)
            c.get(Calendar.WEEK_OF_YEAR)==now.get(Calendar.WEEK_OF_YEAR)&&c.getWeekYear()==now.getWeekYear()&&d.drivingMinutes>RtoCore.DAILY_DRIVING_NORMAL
        }.coerceAtMost(RtoCore.MAX_TEN_HOUR_DRIVING_DAYS_PER_WEEK)
    }

'''
if anchor not in s:
    raise SystemExit("history helper anchor not found")
s = s.replace(anchor, helpers + anchor, 1)

old = '        historyRoot.removeAllViews();val scroll=ScrollView(this);val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val days=history?.days?.takeLast(56)?.asReversed().orEmpty();c.addView(sub("История карты: ${days.size} из 56 дней"))\n'
new = '''        historyRoot.removeAllViews();val scroll=ScrollView(this);val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val days=history?.days?.takeLast(56)?.asReversed().orEmpty()
        val allowance=card();allowance.addView(label("ДОСТУПНЫЕ ДОПУСКИ"))
        val reducedUsed=historyReducedDailyRestUses()
        val reducedRemaining=if(reducedUsed==null)null else (RtoCore.MAX_REDUCED_DAILY_RESTS_BETWEEN_WEEKLY_RESTS-reducedUsed).coerceAtLeast(0)
        allowance.addView(value(if(reducedRemaining==null)"🛏 9-часовые отдыхи: данных недостаточно" else "🛏 9-часовые отдыхи: осталось $reducedRemaining из ${RtoCore.MAX_REDUCED_DAILY_RESTS_BETWEEN_WEEKLY_RESTS}",16f))
        val tenUsed=historyTenHourDrivingUses();val tenRemaining=(RtoCore.MAX_TEN_HOUR_DRIVING_DAYS_PER_WEEK-tenUsed).coerceAtLeast(0)
        allowance.addView(value("🚗 10-часовые вождения: осталось $tenRemaining из ${RtoCore.MAX_TEN_HOUR_DRIVING_DAYS_PER_WEEK}",16f))
        allowance.addView(sub("9 ч — между недельными отдыхами • 10 ч — календарная неделя"));c.addView(allowance);c.addView(space(8));c.addView(sub("История карты: ${days.size} из 56 дней"))
'''
if old not in s:
    raise SystemExit("history summary anchor not found")
s = s.replace(old, new, 1)

p.write_text(s, encoding="utf-8")
print("Restored DTCO auto reconnect and history allowance summary")
