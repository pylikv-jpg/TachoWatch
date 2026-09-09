package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DriverDashboardActivityV2 : AppCompatActivity(), LiveDidDiagnostic.Listener, DtcoBluetoothDiagnostic.Listener {
    companion object {
        private const val BG=0xFF0B1118.toInt(); private const val CARD=0xFF17212C.toInt(); private const val TEXT=0xFFF3F7FA.toInt()
        private const val MUTED=0xFF9BAAB8.toInt(); private const val GREEN=0xFF69E69C.toInt(); private const val YELLOW=0xFFF0BE46.toInt()
        private const val RED=0xFFEF6262.toInt(); private const val CYAN=0xFF29B6C8.toInt(); private const val BORDER=0xFF263545.toInt()
        private const val PREFS="tachowatch_auto_card"; private const val FIRST_READ="first_card_read_done"; private const val SELECTED_DTCO="selected_dtco_address"; private const val CARD_NAME="driver_name_from_card"
        private const val SHIFT_INITIALIZED="shift_counter_initialized"; private const val SHIFT_COMPLETED="shift_completed_driving"; private const val SHIFT_PREV_CONTINUOUS="shift_prev_continuous"
        private const val WORK_WINDOW="work_window_minutes"; private const val WORK_PREV_ACTIVITY="work_prev_activity"; private const val WORK_PREV_DURATION="work_prev_duration"
        private const val WORK_ACC="other_work_window_minutes"; private const val AVAIL_ACC="availability_window_minutes"
    }

    private val btManager by lazy{getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager}
    private val adapter by lazy{btManager.adapter}
    private val prefs by lazy{getSharedPreferences(PREFS,Context.MODE_PRIVATE)}
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var live:LiveDidDiagnostic
    private lateinit var cardReader:DtcoBluetoothDiagnostic
    private var dtco:BluetoothDevice?=null
    private var history:HistoryData.Model?=null
    private var cardReading=false
    private var resumeLive=false
    private var initialReadAttemptedThisSession=false

    private lateinit var status:TextView; private lateinit var nowTab:Button; private lateinit var historyTab:Button
    private lateinit var nowRoot:LinearLayout; private lateinit var historyRoot:LinearLayout; private lateinit var driver:TextView
    private lateinit var continuous:TextView; private lateinit var continuousFrame:FrameLayout; private lateinit var continuousProgress:View
    private lateinit var shiftDriving:TextView; private lateinit var shiftDrivingSub:TextView; private lateinit var shiftDrivingFrame:FrameLayout; private lateinit var shiftDrivingProgress:View
    private lateinit var restTime:TextView; private lateinit var restSub:TextView; private lateinit var restFrame:FrameLayout; private lateinit var restProgress:View
    private lateinit var work6:TextView; private lateinit var work6Sub:TextView; private lateinit var work6Frame:FrameLayout; private lateinit var work6Progress:View
    private lateinit var otherWork:TextView; private lateinit var otherWorkFrame:FrameLayout; private lateinit var otherWorkProgress:View
    private lateinit var availability:TextView; private lateinit var availabilityFrame:FrameLayout; private lateinit var availabilityProgress:View
    private lateinit var workWeek:TextView; private lateinit var workWeekSub:TextView; private lateinit var workWeekFrame:FrameLayout; private lateinit var workWeekProgress:View
    private lateinit var week:TextView; private lateinit var weekSub:TextView; private lateinit var weekFrame:FrameLayout; private lateinit var weekProgress:View
    private lateinit var twoWeek:TextView; private lateinit var twoWeekFrame:FrameLayout; private lateinit var twoWeekProgress:View

    private lateinit var continuousSub:TextView
    private lateinit var freshness:TextView
    private lateinit var twoWeekSub:TextView
    private var lastCycleAt=0L
    private var liveConnected=false
    private val freshnessTick=object:Runnable{
        override fun run(){
            if(!cardReading){
                val age=if(lastCycleAt==0L)null else (android.os.SystemClock.elapsedRealtime()-lastCycleAt)/1000
                freshness.text=when{!liveConnected->"Ожидание подключения";age==null->"Ожидание данных";age>30->"Данные устарели • ${age} сек назад";else->"Обновлено ${age} сек назад"}
                freshness.setTextColor(if(liveConnected&&age!=null&&age>30)YELLOW else MUTED)
            }
            handler.postDelayed(this,1000)
        }
    }
    private var currentActivity="—"; private var activityMinutes=0; private var continuousMinutes=0; private var breakMinutes=0; private var twoWeekMinutes=0
    private var shiftCounterInitialized=false; private var shiftCompletedMinutes=0; private var previousContinuousMinutes=0; private var lastProcessedCycle=0
    private var workWindowMinutes=0; private var previousActivity="—"; private var previousActivityDuration=0; private var otherWorkWindowMinutes=0; private var availabilityWindowMinutes=0

    private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){findAndAutoConnect()}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=BG; window.navigationBarColor=BG
        live=LiveDidDiagnostic(applicationContext,this); cardReader=DtcoBluetoothDiagnostic(applicationContext,this)
        restoreCounters(); buildUi(); loadHistory(); requestPermission(); handler.post(freshnessTick)
    }
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);live.disconnect();cardReader.disconnect();super.onDestroy()}

    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG)}
        root.setOnApplyWindowInsetsListener{v,i->v.setPadding(dp(14),i.systemWindowInsetTop+dp(8),dp(14),i.systemWindowInsetBottom);i}
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        top.addView(value("TachoWatch",26f),LinearLayout.LayoutParams(0,-2,1f))
        top.addView(smallButton("♧").apply{text="Bluetooth";contentDescription="Выбрать тахограф";setTextColor(CYAN);setOnClickListener{showDtcoPicker()}},LinearLayout.LayoutParams(dp(84),dp(48)))
        top.addView(smallButton("⚙").apply{textSize=24f;contentDescription="Настройки";setOnClickListener{showSettings()}},LinearLayout.LayoutParams(dp(48),dp(48)))
        root.addView(top)
        status=sub("● DTCO не подключён");status.setTextColor(MUTED);root.addView(status)
        freshness=sub("Ожидание подключения");freshness.textSize=12f;root.addView(freshness);root.addView(space(8))
        val viewport=FrameLayout(this)
        nowRoot=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        historyRoot=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        viewport.addView(nowRoot);viewport.addView(historyRoot)
        root.addView(viewport,LinearLayout.LayoutParams(-1,0,1f))
        val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(4),0,dp(4))}
        nowTab=tabButton("◷\nСейчас").apply{setOnClickListener{showNow()}}
        historyTab=tabButton("≡\nИстория").apply{setOnClickListener{showHistory()}}
        val scanner=tabButton("▣\nСканер").apply{setOnClickListener{live.disconnect();startActivity(Intent(this@DriverDashboardActivityV2,EventScannerActivity::class.java))}}
        for(tab in listOf(nowTab,historyTab,scanner))tabs.addView(tab,LinearLayout.LayoutParams(0,dp(60),1f))
        root.addView(tabs);setContentView(root);buildNow();buildHistoryView();updateTabState(true);root.requestApplyInsets()
    }
    private fun showSettings(){
        AlertDialog.Builder(this).setTitle("Настройки").setItems(arrayOf("Выбрать тахограф","Обновить карту водителя")){_,i->
            if(i==0)showDtcoPicker() else if(dtco!=null)startCardRead("По запросу",true)
            else showDtcoPicker()
        }.setNegativeButton("Закрыть",null).show()
    }
    override fun onRestart(){super.onRestart();if(!cardReading)dtco?.let{connectSelected(it)}}

    private fun buildNow(){
        val scroll=ScrollView(this).apply{isFillViewport=true}
        val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(12))}
        driver=value(prefs.getString(CARD_NAME,null)?:"Водитель",18f);c.addView(driver);c.addView(space(12))
        fun addCard(title:String,kind:String):Triple<FrameLayout,View,LinearLayout>{
            val card=progressCard();card.third.addView(iconLabel(title,kind));c.addView(card.first);c.addView(space(10));return card
        }
        val d=addCard("Непрерывное вождение","drive");continuousFrame=d.first;continuousProgress=d.second
        continuous=value("—",44f);d.third.addView(continuous);d.third.addView(sub("осталось до перерыва"));continuousSub=sub("Ожидание данных");d.third.addView(continuousSub)
        val s=addCard("Вождение за смену","drive");shiftDrivingFrame=s.first;shiftDrivingProgress=s.second
        shiftDriving=value("—",40f);s.third.addView(shiftDriving);shiftDrivingSub=sub("Ожидание данных");s.third.addView(shiftDrivingSub)
        val six=addCard("Непрерывная работа","work");work6Frame=six.first;work6Progress=six.second
        work6=value("—",40f);six.third.addView(work6);six.third.addView(sub("Вождение + другая работа"));work6Sub=sub("До 6 часов — —");six.third.addView(work6Sub)
        fun row(title:String,kind:String):TextView{
            val line=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(CARD,dp(14).toFloat(),BORDER)}
            line.addView(iconLabel(title,kind),LinearLayout.LayoutParams(0,-2,1f));val v=value("—",20f);line.addView(v);c.addView(line);c.addView(space(7));return v
        }
        otherWork=row("Другая работа","work")
        row("Ожидание","wait").apply{contentDescription="Отдельные данные ожидания недоступны";setOnClickListener{AlertDialog.Builder(this@DriverDashboardActivityV2).setMessage("Тахограф передаёт общий режим готовности. Отдельный счётчик ожидания пока недоступен.").setPositiveButton("Понятно",null).show()}}
        availability=row("Готовность","available")
        val r=addCard("Отдых / Пауза","rest");restFrame=r.first;restProgress=r.second
        restTime=value("—",44f);r.third.addView(restTime);r.third.addView(sub("фактическая длительность"));restSub=sub("Ожидание данных").apply{setTextColor(TEXT);setPadding(dp(10),dp(9),dp(10),dp(9));background=rounded(BG,dp(10).toFloat())};r.third.addView(space(6));r.third.addView(restSub)
        val ww=addCard("Рабочая неделя","wait");workWeekFrame=ww.first;workWeekProgress=ww.second
        workWeek=value("—",40f);ww.third.addView(workWeek);workWeekSub=sub("до начала недельного отдыха");ww.third.addView(workWeekSub)
        val w=addCard("Текущая неделя","drive");weekFrame=w.first;weekProgress=w.second
        week=value("—",40f);w.third.addView(week);weekSub=sub("По карте водителя");w.third.addView(weekSub)
        val tw=addCard("Две недели","wait");twoWeekFrame=tw.first;twoWeekProgress=tw.second
        twoWeek=value("—",40f);tw.third.addView(twoWeek);twoWeekSub=sub("Вождение • предел 90:00");tw.third.addView(twoWeekSub)
        c.addView(sub("Bluetooth • при обрыве — автоподключение"))
        scroll.addView(c);nowRoot.addView(scroll,LinearLayout.LayoutParams(-1,-1))
    }

    private fun buildHistoryView(){
        historyRoot.removeAllViews();val scroll=ScrollView(this);val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val model=history;val days=recentThreeWeeks(model?.days.orEmpty()).asReversed()
        c.addView(value("История",26f));c.addView(sub("Последние 3 недели"));c.addView(space(12))
        val previous=model?.previousWeekDrivingMinutes?:0;val current=model?.currentWeekCardMinutes?:0;val total=previous+current;val remaining=(90*60-total).coerceAtLeast(0)
        val summary=card();summary.addView(label("Две последовательные недели"));summary.addView(value(if(model==null)"— / 90:00" else "${HistoryData.fmt(total)} / 90:00",30f));summary.addView(sub(if(model==null)"Ожидание карты водителя" else "Предыдущая ${HistoryData.fmt(previous)} • текущая ${HistoryData.fmt(current)} • осталось ${HistoryData.fmt(remaining)}"))
        val reduced=usedReducedDailyRests();val ten=currentWeekTenHourUses()
        summary.addView(space(8));summary.addView(sub(if(model==null)"Вождение до 10 ч: нет данных" else "Вождение до 10 ч: $ten использовано • ${(2-ten).coerceAtLeast(0)} осталось"))
        summary.addView(sub(if(model==null)"Сокращённый отдых: нет данных" else "Сокращённый отдых: $reduced использовано • ${(3-reduced).coerceAtLeast(0)} осталось"))
        summary.addView(sub("Отдыхи — между недельными отдыхами"));c.addView(summary);c.addView(space(7))
        c.addView(sub("Все смены и отдыхи: ${days.size} смен"))
        if(days.isEmpty())c.addView(value("История появится после полного считывания карты",17f))
        days.forEachIndexed{i,day->
            val box=card();val details=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
            val head=TextView(this).apply{text="${prettyDate(day.date)}  •  ${day.startTime?:"—"}–${day.endTime?:"—"}   ▾";textSize=18f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(4),0,dp(4));setOnClickListener{details.visibility=if(details.visibility==View.VISIBLE)View.GONE else View.VISIBLE}}
            box.addView(head);box.addView(sub("${flag(day.startCountry)} ${day.startCountry?:"—"} → ${flag(day.endCountry)} ${day.endCountry?:"—"} • смена ${day.shiftMinutes?.let(HistoryData::fmt)?:"—"}"));box.addView(sub("Вождение   ${HistoryData.fmt(day.drivingMinutes)}"));box.addView(sub("Другая работа   ${HistoryData.fmt(day.workMinutes)}"));box.addView(sub("Готовность   ${HistoryData.fmt(day.availabilityMinutes)}"))
            details.addView(label("ПОДРОБНЫЙ ОТЧЁТ ПО ВИДАМ РАБОТ"))
            val shiftPeriods=periodsInsideShift(day);if(shiftPeriods.isEmpty())details.addView(sub("Подробные периоды отсутствуют в считанных данных карты"))
            shiftPeriods.forEach{p->details.addView(sub("${activityIcon(p.type)} ${p.startTime}  ${activityName(p.type)}  •  ${HistoryData.fmt(p.minutes)}"))}
            details.addView(sub("Открытие: ${day.startTime?:"—"} ${flag(day.startCountry)} ${day.startCountry?:"—"}"));details.addView(sub("Закрытие: ${day.endTime?:"—"} ${flag(day.endCountry)} ${day.endCountry?:"—"}"));box.addView(details);c.addView(box);c.addView(space(7))
            if(i<days.lastIndex){val older=days[i+1];model?.restBetween(older,day)?.let{rest->c.addView(TextView(this).apply{text=restTitle(rest);background=rounded(CARD,dp(12).toFloat(),BORDER);textSize=14f;gravity=Gravity.CENTER;setTextColor(if(rest.weekly)CYAN else if(rest.creditedDailyMinutes==540)YELLOW else MUTED);setPadding(dp(6),dp(7),dp(6),dp(7))})}}
        }
        scroll.addView(c);historyRoot.addView(scroll,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun recentThreeWeeks(days:List<HistoryData.Day>):List<HistoryData.Day>{val latest=days.lastOrNull()?.date?.let(::parseDateOnly)?:return emptyList();val cutoff=Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply{time=latest;add(Calendar.DAY_OF_MONTH,-20)}.time;return days.filter{(parseDateOnly(it.date)?:Date(0)).time>=cutoff.time}}
    private fun usedReducedDailyRests():Int{val rests=history?.rests.orEmpty();val after=rests.indexOfLast{it.weekly};return rests.drop(after+1).count{!it.weekly&&!it.splitDaily&&it.creditedDailyMinutes==540}}
    private fun restTitle(r:HistoryData.RestInfo):String=when{r.weekly&&r.compensationCreatedMinutes>0->"🛏 СОКРАЩЁННЫЙ НЕДЕЛЬНЫЙ ОТДЫХ  ${HistoryData.fmt(r.actualMinutes)}\nКомпенсация ${HistoryData.fmt(r.compensationRemainingMinutes)} • до ${r.compensationDueDate?.let(HistoryData::prettyDate)?:"—"}";r.weekly->"🛏 НЕДЕЛЬНЫЙ ОТДЫХ  ${HistoryData.fmt(r.actualMinutes)}";r.splitDaily->"🛏 РЕГУЛЯРНЫЙ РАЗДЕЛЁННЫЙ ОТДЫХ  3:00 + ${HistoryData.fmt(r.actualMinutes)}";r.creditedDailyMinutes==540->"🛏 СОКРАЩЁННЫЙ СУТОЧНЫЙ ОТДЫХ  ${HistoryData.fmt(r.actualMinutes)}";else->"🛏 СУТОЧНЫЙ ОТДЫХ  ${HistoryData.fmt(r.actualMinutes)}"}
    private fun activityName(type:String)=when(type){"DRIVING"->"Вождение";"WORK"->"Другая работа";"AVAILABILITY"->"Ожидание / готовность";"REST"->"Отдых / пауза";else->type}
    private fun activityIcon(type:String)=when(type){"DRIVING"->"🚗";"WORK"->"⚒";"AVAILABILITY"->"✉";"REST"->"🛏";else->"•"}
    private fun periodsInsideShift(day:HistoryData.Day):List<HistoryData.ActivityPeriod>{val start=day.startTime?.let(::clockValue)?:return day.periods;val length=day.shiftMinutes?:return day.periods;return day.periods.filter{((clockValue(it.startTime)-start+1440)%1440)<length}}
    private fun clockValue(v:String):Int=v.substringBefore(':').toIntOrNull()?.times(60)?.plus(v.substringAfter(':').toIntOrNull()?:0)?:0

    private fun loadHistory(){val f=TlvInventory.findLatestDdd(getExternalFilesDir(null))?:return;val r=TlvInventory.parse(f);if(r.error==null){history=HistoryData.load(r);if(::historyRoot.isInitialized)buildHistoryView();updateWeekCards();updateWorkWeekClock();updateShiftDriving()}}
    private fun restoreCounters(){shiftCounterInitialized=prefs.getBoolean(SHIFT_INITIALIZED,false);shiftCompletedMinutes=prefs.getInt(SHIFT_COMPLETED,0);previousContinuousMinutes=prefs.getInt(SHIFT_PREV_CONTINUOUS,0);workWindowMinutes=prefs.getInt(WORK_WINDOW,0);previousActivity=prefs.getString(WORK_PREV_ACTIVITY,"—")?:"—";previousActivityDuration=prefs.getInt(WORK_PREV_DURATION,0);otherWorkWindowMinutes=prefs.getInt(WORK_ACC,0);availabilityWindowMinutes=prefs.getInt(AVAIL_ACC,0)}
    private fun persistCounters(){prefs.edit().putBoolean(SHIFT_INITIALIZED,shiftCounterInitialized).putInt(SHIFT_COMPLETED,shiftCompletedMinutes).putInt(SHIFT_PREV_CONTINUOUS,previousContinuousMinutes).putInt(WORK_WINDOW,workWindowMinutes).putString(WORK_PREV_ACTIVITY,previousActivity).putInt(WORK_PREV_DURATION,previousActivityDuration).putInt(WORK_ACC,otherWorkWindowMinutes).putInt(AVAIL_ACC,availabilityWindowMinutes).apply()}
    private fun initializeShiftCounterFromCard(){if(shiftCounterInitialized)return;shiftCompletedMinutes=0;previousContinuousMinutes=continuousMinutes;shiftCounterInitialized=true;persistCounters()}
    private fun processCycle(){
        initializeShiftCounterFromCard()
        if(previousContinuousMinutes>0&&continuousMinutes<previousContinuousMinutes){
            shiftCompletedMinutes+=previousContinuousMinutes
        }
        previousContinuousMinutes=continuousMinutes
        processWorkWindow();persistCounters();updateShiftDriving()
    }
    private fun processWorkWindow(){val restReached45=currentActivity.contains("ОТДЫХ")&&maxOf(breakMinutes,activityMinutes)>=45;if(restReached45){workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;previousActivity=currentActivity;previousActivityDuration=activityMinutes;return};if(previousActivity!=currentActivity){val finished=previousActivityDuration.coerceAtLeast(0);when{previousActivity.contains("ВОЖДЕНИЕ")->workWindowMinutes+=finished;previousActivity.contains("РАБОТА")->{workWindowMinutes+=finished;otherWorkWindowMinutes+=finished};previousActivity.contains("ГОТОВНОСТЬ")->availabilityWindowMinutes+=finished};previousActivity=currentActivity;previousActivityDuration=activityMinutes;return};previousActivityDuration=activityMinutes}
    private fun activeWorkTotal()=workWindowMinutes+if(currentActivity.contains("ВОЖДЕНИЕ")||currentActivity.contains("РАБОТА"))activityMinutes else 0
    private fun activeOtherWorkTotal()=otherWorkWindowMinutes+if(currentActivity.contains("РАБОТА"))activityMinutes else 0
    private fun activeAvailabilityTotal()=availabilityWindowMinutes+if(currentActivity.contains("ГОТОВНОСТЬ"))activityMinutes else 0

    private fun updateShiftDriving(){if(!::shiftDriving.isInitialized)return;if(!shiftCounterInitialized){shiftDriving.text="—";shiftDrivingSub.text="ожидание первого live-цикла";return};val total=shiftCompletedMinutes+continuousMinutes;val limit=if(total<=540||currentWeekTenHourUses()>=2)540 else 600;val remain=(limit-total).coerceAtLeast(0);shiftDriving.text=HistoryData.fmt(total);shiftDrivingSub.text=if(remain<=30)"⚠ Осталось ${HistoryData.fmt(remain)} вождения" else "До ${if(limit==540)9 else 10} часов — ${HistoryData.fmt(remain)}";setProgress(shiftDrivingFrame,shiftDrivingProgress,total.toFloat()/limit,when{total>=limit-30->RED;total>=limit-60->YELLOW;else->GREEN})}
    private fun currentWeekTenHourUses():Int{val now=isoCalendar(Date());return history?.days.orEmpty().count{d->val date=parseDateOnly(d.date)?:return@count false;val c=isoCalendar(date);c.get(Calendar.WEEK_OF_YEAR)==now.get(Calendar.WEEK_OF_YEAR)&&c.getWeekYear()==now.getWeekYear()&&d.drivingMinutes>540}}
    private fun updateWorkWeekClock(){if(!::workWeek.isInitialized)return;val start=history?.let{HistoryData.lastWeeklyRestEndMillis(it.days)};if(start==null){workWeek.text="—";workWeekSub.text="не найден законченный недельный отдых на карте";setProgress(workWeekFrame,workWeekProgress,0f,GREEN);return};val total=144*60;val elapsed=((System.currentTimeMillis()-start)/60000L).toInt().coerceAtLeast(0);val remain=(total-elapsed).coerceAtLeast(0);workWeek.text=HistoryData.fmt(remain);val stamp=SimpleDateFormat("dd.MM HH:mm",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date(start));workWeekSub.text="до начала недельного отдыха\nОтсчёт с $stamp UTC";setProgress(workWeekFrame,workWeekProgress,elapsed.coerceAtMost(total).toFloat()/total,when{remain<=12*60->RED;remain<=24*60->YELLOW;else->GREEN})}

    private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE;updateTabState(true)}
    private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE;updateTabState(false)}
    private fun updateTabState(nowSelected:Boolean){
        nowTab.setTextColor(if(nowSelected)GREEN else MUTED);historyTab.setTextColor(if(nowSelected)MUTED else GREEN)
    }

    private fun requestPermission(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN))else findAndAutoConnect()}
    @SuppressLint("MissingPermission") private fun findAndAutoConnect(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;val saved=prefs.getString(SELECTED_DTCO,null);dtco=try{adapter?.bondedDevices?.firstOrNull{it.address==saved}}catch(_:Throwable){null};val d=dtco;if(d==null){status.text="Выберите DTCO";return};connectSelected(d)}
    private fun connectSelected(d:BluetoothDevice){dtco=d;status.text="Подключение к DTCO…";live.connect(d)}

    @SuppressLint("MissingPermission") private fun showDtcoPicker(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)){permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN));return}
        val devices=linkedMapOf<String,BluetoothDevice>();try{adapter?.bondedDevices?.filter{(it.name?:"").contains("DTCO",true)}?.forEach{devices[it.address]=it}}catch(_:Throwable){}
        val labels=mutableListOf<String>();val listAdapter=ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels);fun refresh(){labels.clear();devices.values.forEach{labels.add("${safeName(it)}\n${it.address}")};listAdapter.notifyDataSetChanged()};refresh()
        val dialog=AlertDialog.Builder(this).setTitle("Выберите DTCO").setAdapter(listAdapter){_,which->val d=devices.values.toList().getOrNull(which)?:return@setAdapter;prefs.edit().putString(SELECTED_DTCO,d.address).putBoolean(FIRST_READ,false).apply();initialReadAttemptedThisSession=false;dtco=d;status.text="Выбран ${safeName(d)}";connectSelected(d)}.setNegativeButton("Закрыть",null).create();dialog.setOnShowListener{startNearbyScan(devices,::refresh)};dialog.show()
    }
    @SuppressLint("MissingPermission") private fun startNearbyScan(devices:MutableMap<String,BluetoothDevice>,refresh:()->Unit){val a=adapter?:return;val cb=BluetoothAdapter.LeScanCallback{device,_,_->val n=try{device.name}catch(_:Throwable){null};if((n?:"").contains("DTCO",true)){devices[device.address]=device;runOnUiThread{refresh()}}};try{a.startLeScan(cb);handler.postDelayed({try{a.stopLeScan(cb)}catch(_:Throwable){}},4000)}catch(_:Throwable){}}
    @SuppressLint("MissingPermission") private fun safeName(d:BluetoothDevice)=try{d.name?:"DTCO"}catch(_:Throwable){"DTCO"}

    private fun startCardRead(reason:String,resume:Boolean){if(cardReading)return;val d=dtco?:return;cardReading=true;resumeLive=resume;status.text="Считывание карты • $reason";live.disconnect();handler.postDelayed({cardReader.connect(d)},500)}
    override fun onLiveConnection(connected:Boolean,deviceName:String?){runOnUiThread{if(cardReading)return@runOnUiThread;liveConnected=connected;if(connected){lastProcessedCycle=0;lastCycleAt=0;status.text="● ${deviceName?:"DTCO"} подключён";status.setTextColor(GREEN);if(!prefs.getBoolean(FIRST_READ,false)&&!initialReadAttemptedThisSession){initialReadAttemptedThisSession=true;handler.postDelayed({if(!cardReading)startCardRead("Первое успешное подключение",true)},800)}}else{status.text="Связь потеряна • автоматическое переподключение…";status.setTextColor(YELLOW)}}}
    override fun onLiveLog(log:String){runOnUiThread{last(log,"F931")?.let{if(it.isNotBlank()&&it!="—"){driver.text=it;prefs.edit().putString(CARD_NAME,it).apply()}};last(log,"F903")?.let{currentActivity=it};mins(last(log,"F927"))?.let{activityMinutes=it};mins(last(log,"F923"))?.let{continuousMinutes=it};mins(last(log,"F925"))?.let{breakMinutes=it};mins(last(log,"F938"))?.let{twoWeekMinutes=it};val cycle=Regex("LIVE CYCLE #(\\d+) COMPLETE").findAll(log).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull();if(log.lineSequence().lastOrNull()?.startsWith("LIVE CYCLE #")==true&&cycle!=null&&cycle>lastProcessedCycle){lastProcessedCycle=cycle;lastCycleAt=android.os.SystemClock.elapsedRealtime();processCycle();status.text="● DTCO подключён"};updateNow()}}
    override fun onLogChanged(fullLog:String){if(!cardReading)return;when{fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER)&&fullLog.contains("STATUS=SUCCESS")->runOnUiThread{prefs.edit().putBoolean(FIRST_READ,true).apply();loadHistory();finishCardRead(true)};fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER)&&fullLog.contains("STATUS=FAILED")->runOnUiThread{finishCardRead(false)}}}
    override fun onConnectionStateChanged(connected:Boolean,deviceName:String?){if(cardReading&&connected)runOnUiThread{status.text="Считывание карты…"}}
    private fun finishCardRead(ok:Boolean){val resume=resumeLive;cardReading=false;resumeLive=false;status.text=if(ok)"Карта считана • данные обновлены" else "Ошибка чтения карты • live восстановлен";cardReader.disconnect();if(resume){val d=dtco?:return;handler.postDelayed({live.connect(d)},800)}}

    private fun updateNow(){
        continuous.text=HistoryData.fmt((270-continuousMinutes).coerceAtLeast(0));continuous.setTextColor(driveColor(continuousMinutes));continuousSub.text="Проехал ${HistoryData.fmt(continuousMinutes)} из 4:30";setProgress(continuousFrame,continuousProgress,continuousMinutes/270f,driveColor(continuousMinutes));updateShiftDriving()
        val resting=currentActivity.contains("ОТДЫХ");val actual=if(resting)maxOf(activityMinutes,breakMinutes) else breakMinutes;val credited=HistoryData.creditedRestMinutes(actual);val next=HistoryData.nextRestMilestone(actual)
        restTime.text=HistoryData.fmt(actual);restTime.setTextColor(if(actual<45)RED else GREEN);restSub.text=if(resting){if(next!=null)"Засчитано: ${HistoryData.fmt(credited)}\nДо ${HistoryData.fmt(next)} — ${HistoryData.fmt((next-actual).coerceAtLeast(0))}" else "Засчитано: ${HistoryData.fmt(credited)}"}else "сейчас не отдых • накоплено ${HistoryData.fmt(actual)}";val target=next?:45*60;setProgress(restFrame,restProgress,if(target>0)actual.toFloat()/target else 0f,restMilestoneColor(credited))
        val wt=activeWorkTotal();work6.text=HistoryData.fmt(wt);work6Sub.text=if(wt>=330)"⚠ До 6 часов осталось ${HistoryData.fmt((360-wt).coerceAtLeast(0))}" else "До 6 часов — ${HistoryData.fmt((360-wt).coerceAtLeast(0))}";setProgress(work6Frame,work6Progress,wt/360f,workColor(wt))
        val ow=activeOtherWorkTotal();otherWork.text=HistoryData.fmt(ow);val av=activeAvailabilityTotal();availability.text=HistoryData.fmt(av)
        twoWeek.text=HistoryData.fmt(twoWeekMinutes);twoWeekSub.text="Вождение • предел 90:00\nОсталось ${HistoryData.fmt((5400-twoWeekMinutes).coerceAtLeast(0))}";setProgress(twoWeekFrame,twoWeekProgress,twoWeekMinutes/(90f*60f),limitColor(twoWeekMinutes,90*60));updateWeekCards();updateWorkWeekClock()
    }
    private fun updateWeekCards(){if(!::week.isInitialized)return;val current=history?.currentWeekCardMinutes?:0;val prev=history?.previousWeekDrivingMinutes?:0;val limit=minOf(56*60,(90*60-prev).coerceAtLeast(0));week.text=if(history==null)"—" else HistoryData.fmt(current);weekSub.text=if(history==null)"Ожидание карты водителя" else "Вождение • предел ${HistoryData.fmt(limit)}";setProgress(weekFrame,weekProgress,if(limit>0)current.toFloat()/limit else 1f,limitColor(current,limit))}
    private fun parseDateOnly(v:String):Date?=runCatching{SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.parse(v)}.getOrNull()
    private fun isoCalendar(d:Date)=Calendar.getInstance(TimeZone.getTimeZone("UTC"),Locale.US).apply{firstDayOfWeek=Calendar.MONDAY;minimalDaysInFirstWeek=4;time=d}
    private fun last(log:String,did:String)=log.lines().asReversed().firstOrNull{it.startsWith("$did=")}?.substringAfter(" | ")?.trim();private fun mins(v:String?):Int?=v?.let{Regex("^(\\d+) мин").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()}
    private fun driveColor(m:Int)=when{m>=255->RED;m>=240->YELLOW;else->GREEN};private fun restMilestoneColor(m:Int)=when{m>=45*60->CYAN;m>=24*60->GREEN;m>=11*60->GREEN;m>=9*60->GREEN;m>=3*60->YELLOW;m>=45->GREEN;m>=15->YELLOW;else->RED};private fun workColor(m:Int)=when{m>=360->RED;m>=330->YELLOW;else->GREEN};private fun limitColor(v:Int,limit:Int)=when{limit<=0||v>=limit-120->RED;v>=limit-360->YELLOW;else->GREEN}
    private fun iconLabel(title:String,kind:String)=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        addView(DashboardIconView(this@DriverDashboardActivityV2,kind,MUTED),LinearLayout.LayoutParams(dp(32),dp(32)).apply{rightMargin=dp(10)})
        addView(label(title),LinearLayout.LayoutParams(0,-2,1f))
    }
    private fun progressCard():Triple<FrameLayout,View,LinearLayout>{
        val f=FrameLayout(this).apply{background=rounded(CARD,dp(16).toFloat(),BORDER)}
        val b=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(24))}
        f.addView(b,FrameLayout.LayoutParams(-1,-2))
        val track=View(this).apply{background=rounded(BORDER,dp(3).toFloat())}
        f.addView(track,FrameLayout.LayoutParams(-1,dp(5),Gravity.BOTTOM).apply{setMargins(dp(16),0,dp(16),dp(10))})
        val bar=View(this).apply{background=rounded(GREEN,dp(3).toFloat())}
        f.addView(bar,FrameLayout.LayoutParams(0,dp(5),Gravity.BOTTOM).apply{leftMargin=dp(16);bottomMargin=dp(10)})
        return Triple(f,bar,b)
    }
    private fun setProgress(frame:FrameLayout,bar:View,p:Float,color:Int){frame.post{
        val lp=bar.layoutParams as FrameLayout.LayoutParams;lp.width=((frame.width-dp(32)).coerceAtLeast(0)*p.coerceIn(0f,1f)).toInt();bar.layoutParams=lp;bar.background=rounded(color,dp(3).toFloat())
    }}
    private fun card()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(CARD,dp(14).toFloat(),BORDER)}
    private fun label(t:String)=TextView(this).apply{text=t;textSize=15f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD)};private fun value(t:String,s:Float)=TextView(this).apply{text=t;textSize=s;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(2),0,0)};private fun sub(t:String)=TextView(this).apply{text=t;textSize=13f;setTextColor(MUTED);setPadding(0,dp(2),0,0)}
    private fun tabButton(t:String)=Button(this).apply{text=t;isAllCaps=false;textSize=14f;setTextColor(TEXT);background=rounded(BG,dp(11).toFloat())};private fun smallButton(t:String)=tabButton(t).apply{textSize=11f;minWidth=0;minimumWidth=0;setPadding(dp(9),0,dp(9),0)}
    private fun rounded(c:Int,r:Float,stroke:Int?=null)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;cornerRadius=r;setColor(c);if(stroke!=null)setStroke(dp(1),stroke)};private fun space(h:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(h))};private fun hspace(w:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(dp(w),1)};private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun prettyDate(d:String)=runCatching{val p=d.split('-');"${p[2]}.${p[1]}.${p[0]}"}.getOrDefault(d);private fun flag(c:String?)=when(c){"B"->"🇧🇪";"F"->"🇫🇷";"D"->"🇩🇪";"NL"->"🇳🇱";"L"->"🇱🇺";"E"->"🇪🇸";"LT"->"🇱🇹";"LV"->"🇱🇻";else->"🌐"}
}
