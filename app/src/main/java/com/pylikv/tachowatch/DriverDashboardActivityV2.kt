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
        private const val BG=0xFF0B1118.toInt(); private const val CARD=0xFF141D27.toInt(); private const val TEXT=0xFFF3F7FA.toInt()
        private const val MUTED=0xFF9BAAB8.toInt(); private const val GREEN=0xFF238A52.toInt(); private const val YELLOW=0xFF9A7A1B.toInt()
        private const val RED=0xFF9E3434.toInt(); private const val CYAN=0xFF29B6C8.toInt(); private const val BORDER=0xFF263545.toInt()
        private const val TRACK=0xFF0D1822.toInt(); private const val NEON_GREEN=0xFF22E980.toInt(); private const val AMBER=0xFFFFC928.toInt()
        private const val PREFS="tachowatch_auto_card"; private const val FIRST_READ="first_card_read_done"; private const val SELECTED_DTCO="selected_dtco_address"; private const val CARD_NAME="driver_name_from_card"
        private const val SHIFT_INITIALIZED="shift_counter_initialized"; private const val SHIFT_COMPLETED="shift_completed_driving"; private const val SHIFT_PREV_CONTINUOUS="shift_prev_continuous"
        private const val WORK_WINDOW="work_window_minutes"; private const val WORK_PREV_ACTIVITY="work_prev_activity"; private const val WORK_PREV_DURATION="work_prev_duration"
        private const val WORK_ACC="other_work_window_minutes"; private const val AVAIL_ACC="availability_window_minutes"
        private const val KEEP_SCREEN_ON="keep_screen_on"
    }

    private data class Gauge(
        val container:LinearLayout,
        val frame:FrameLayout,
        val progress:View,
        val value:TextView,
        val sub:TextView
    )

    private val btManager by lazy{getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager}
    private val adapter by lazy{btManager.adapter}
    private val prefs by lazy{getSharedPreferences(PREFS,Context.MODE_PRIVATE)}
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var cardReader:DtcoBluetoothDiagnostic
    private var dtco:BluetoothDevice?=null
    private var history:HistoryData.Model?=null
    private var cardReading=false
    private var resumeLive=false
    private var initialReadAttemptedThisSession=false

    private lateinit var status:TextView; private lateinit var nowTab:Button; private lateinit var historyTab:Button
    private lateinit var nowRoot:LinearLayout; private lateinit var historyRoot:LinearLayout; private lateinit var driver:TextView
    private lateinit var keepScreenButton:Button; private lateinit var activityState:TextView
    private lateinit var continuous:TextView; private lateinit var continuousSub:TextView; private lateinit var continuousFrame:FrameLayout; private lateinit var continuousProgress:View
    private lateinit var shiftDriving:TextView; private lateinit var shiftDrivingSub:TextView; private lateinit var shiftDrivingFrame:FrameLayout; private lateinit var shiftDrivingProgress:View
    private lateinit var restTime:TextView; private lateinit var restSub:TextView; private lateinit var restFrame:FrameLayout; private lateinit var restProgress:View
    private lateinit var dailyRestTime:TextView; private lateinit var dailyRestSub:TextView; private lateinit var dailyRestFrame:FrameLayout; private lateinit var dailyRestProgress:View
    private lateinit var work6:TextView; private lateinit var work6Sub:TextView; private lateinit var work6Frame:FrameLayout; private lateinit var work6Progress:View
    private lateinit var otherWork:TextView; private lateinit var otherWorkFrame:FrameLayout; private lateinit var otherWorkProgress:View
    private lateinit var availability:TextView; private lateinit var availabilityFrame:FrameLayout; private lateinit var availabilityProgress:View
    private lateinit var workWeek:TextView; private lateinit var workWeekSub:TextView; private lateinit var workWeekFrame:FrameLayout; private lateinit var workWeekProgress:View
    private lateinit var week:TextView; private lateinit var weekSub:TextView; private lateinit var weekFrame:FrameLayout; private lateinit var weekProgress:View
    private lateinit var twoWeek:TextView; private lateinit var twoWeekSub:TextView; private lateinit var twoWeekFrame:FrameLayout; private lateinit var twoWeekProgress:View

    private var currentActivity="—"; private var activityMinutes=0; private var continuousMinutes=0; private var breakMinutes=0; private var twoWeekMinutes=0
    private var shiftCounterInitialized=false; private var shiftCompletedMinutes=0; private var previousContinuousMinutes=0; private var lastProcessedCycle=0
    private var workWindowMinutes=0; private var previousActivity="—"; private var previousActivityDuration=0; private var otherWorkWindowMinutes=0; private var availabilityWindowMinutes=0

    private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){findAndAutoConnect()}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=BG; window.navigationBarColor=BG
        cardReader=DtcoBluetoothDiagnostic(applicationContext,this)
        restoreCounters();restoreSnapshot();buildUi();loadHistory();DriverLiveService.registerListener(this);requestPermission();updateNow()
    }
    override fun onDestroy(){DriverLiveService.unregisterListener(this);cardReader.disconnect();super.onDestroy()}

    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG);setPadding(dp(12),dp(10),dp(12),dp(10))}
        root.setOnApplyWindowInsetsListener{v,insets->v.setPadding(dp(12),dp(10)+insets.systemWindowInsetTop,dp(12),dp(10));insets}
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val titles=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        titles.addView(TextView(this).apply{text="TachoWatch";textSize=25f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD);setOnLongClickListener{startActivity(Intent(this@DriverDashboardActivityV2,EventScannerActivity::class.java));true}})
        status=TextView(this).apply{text="DTCO не подключён";textSize=11.5f;setTextColor(CYAN)};titles.addView(status)
        top.addView(titles,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        top.addView(smallButton("Подключить DTCO").apply{setOnClickListener{showDtcoPicker()}})
        root.addView(top);root.addView(space(7))
        val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        nowTab=tabButton("Сейчас").apply{setOnClickListener{showNow()}};historyTab=tabButton("История").apply{setOnClickListener{showHistory()}}
        tabs.addView(nowTab,LinearLayout.LayoutParams(0,dp(42),1f));tabs.addView(hspace(6));tabs.addView(historyTab,LinearLayout.LayoutParams(0,dp(42),1f));root.addView(tabs);root.addView(space(7))
        val viewport=FrameLayout(this);nowRoot=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};historyRoot=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        viewport.addView(nowRoot);viewport.addView(historyRoot);root.addView(viewport,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root);buildNow();buildHistoryView();updateTabState(true);applyKeepScreenSetting();root.requestApplyInsets()
    }

    private fun buildNow(){
        val scroll=ScrollView(this).apply{isFillViewport=true}
        val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}

        val identity=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(2),dp(2),dp(2),dp(8))}
        driver=TextView(this).apply{text=prefs.getString(CARD_NAME,null)?:"Водитель";textSize=21f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD)}
        identity.addView(driver,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        keepScreenButton=smallButton("☀ Экран: AUTO").apply{setOnClickListener{toggleKeepScreen()}}
        identity.addView(keepScreenButton)
        c.addView(identity)

        val d=gaugeCard("◉  НЕПРЕРЫВНОЕ ВОЖДЕНИЕ","4:30",listOf(1f))
        continuousFrame=d.frame;continuousProgress=d.progress;continuous=d.value;continuousSub=d.sub;c.addView(d.container);c.addView(space(7))

        val s=gaugeCard("◉  ВОЖДЕНИЕ ЗА СМЕНУ","9:00                              10:00",listOf(0.9f,1f))
        shiftDrivingFrame=s.frame;shiftDrivingProgress=s.progress;shiftDriving=s.value;shiftDrivingSub=s.sub;c.addView(s.container);c.addView(space(7))

        val six=gaugeCard("▣  НЕПРЕРЫВНАЯ РАБОТА","6:00",listOf(1f))
        work6Frame=six.frame;work6Progress=six.progress;work6=six.value;work6Sub=six.sub;c.addView(six.container);c.addView(space(7))

        val r=gaugeCard("☕  ПЕРЕРЫВ  15 + 30 = 45 МИН","15 мин                                      45 мин",listOf(15f/45f,1f))
        restFrame=r.frame;restProgress=r.progress;restTime=r.value;restSub=r.sub;c.addView(r.container);c.addView(space(7))

        val dr=gaugeCard("▰  СУТОЧНЫЙ ОТДЫХ  3 → 9 → 11 Ч","3:00                         9:00      11:00",listOf(3f/11f,9f/11f,1f))
        dailyRestFrame=dr.frame;dailyRestProgress=dr.progress;dailyRestTime=dr.value;dailyRestSub=dr.sub;c.addView(dr.container);c.addView(space(7))

        val ww=gaugeCard("▦  РАБОЧАЯ НЕДЕЛЯ","144:00",listOf(1f))
        workWeekFrame=ww.frame;workWeekProgress=ww.progress;workWeek=ww.value;workWeekSub=ww.sub;c.addView(ww.container);c.addView(space(7))

        val w=gaugeCard("▥  НЕДЕЛЬНОЕ ВОЖДЕНИЕ","динамический предел",emptyList())
        weekFrame=w.frame;weekProgress=w.progress;week=w.value;weekSub=w.sub;c.addView(w.container);c.addView(space(7))

        val tw=gaugeCard("↻  ДВУХНЕДЕЛЬНОЕ ВОЖДЕНИЕ","90:00",listOf(1f))
        twoWeekFrame=tw.frame;twoWeekProgress=tw.progress;twoWeek=tw.value;twoWeekSub=tw.sub;c.addView(tw.container);c.addView(space(7))

        val secondary=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val ow=miniMetric("⚒  Другая работа");otherWorkFrame=ow.frame;otherWorkProgress=ow.progress;otherWork=ow.value
        val av=miniMetric("▤  Готовность");availabilityFrame=av.frame;availabilityProgress=av.progress;availability=av.value
        secondary.addView(ow.container,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));secondary.addView(hspace(7));secondary.addView(av.container,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        c.addView(secondary);c.addView(space(7))

        activityState=TextView(this).apply{text="Текущее состояние: —";textSize=13f;setTextColor(CYAN);gravity=Gravity.CENTER;setPadding(dp(10),dp(10),dp(10),dp(10));background=rounded(CARD,dp(13).toFloat(),BORDER)}
        c.addView(activityState)

        scroll.addView(c);nowRoot.addView(scroll,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun buildHistoryView(){
        historyRoot.removeAllViews();val scroll=ScrollView(this);val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val model=history;val days=recentThreeWeeks(model?.days.orEmpty()).asReversed()
        c.addView(value("История · 3 недели",22f))
        val previous=model?.previousWeekDrivingMinutes?:0;val current=model?.currentWeekCardMinutes?:0;val total=previous+current;val remaining=(90*60-total).coerceAtLeast(0)
        val summary=card();summary.addView(label("ДВЕ ПОСЛЕДОВАТЕЛЬНЫЕ НЕДЕЛИ"));summary.addView(value("${HistoryData.fmt(total)} из 90:00",24f));summary.addView(sub("Предыдущая ${HistoryData.fmt(previous)} • текущая ${HistoryData.fmt(current)} • осталось ${HistoryData.fmt(remaining)}"))
        val reduced=usedReducedDailyRests();summary.addView(sub("Сокращённые суточные отдыхи: $reduced/3 использовано • ${(3-reduced).coerceAtLeast(0)} осталось"));summary.addView(sub("10-часовые вождения на этой неделе: ${currentWeekTenHourUses()}/2"));c.addView(summary);c.addView(space(7))
        c.addView(sub("Все смены и отдыхи: ${days.size} смен"))
        if(days.isEmpty())c.addView(value("История появится после полного считывания карты",17f))
        days.forEachIndexed{i,day->
            val box=card();val details=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
            val head=TextView(this).apply{text="${prettyDate(day.date)}  •  ${day.startTime?:"—"}–${day.endTime?:"—"}   ▾";textSize=18f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(4),0,dp(4));setOnClickListener{details.visibility=if(details.visibility==View.VISIBLE)View.GONE else View.VISIBLE}}
            box.addView(head);box.addView(sub("${flag(day.startCountry)} ${day.startCountry?:"—"} → ${flag(day.endCountry)} ${day.endCountry?:"—"} • смена ${day.shiftMinutes?.let(HistoryData::fmt)?:"—"}"));box.addView(value("🚗 ${HistoryData.fmt(day.drivingMinutes)}  ⚒ ${HistoryData.fmt(day.workMinutes)}  ✉ ${HistoryData.fmt(day.availabilityMinutes)}",18f))
            details.addView(label("ПОДРОБНЫЙ ОТЧЁТ ПО ВИДАМ РАБОТ"))
            val shiftPeriods=periodsInsideShift(day);if(shiftPeriods.isEmpty())details.addView(sub("Подробные периоды отсутствуют в считанных данных карты"))
            shiftPeriods.forEach{p->details.addView(sub("${activityIcon(p.type)} ${p.startTime}  ${activityName(p.type)}  •  ${HistoryData.fmt(p.minutes)}"))}
            details.addView(sub("Открытие: ${day.startTime?:"—"} ${flag(day.startCountry)} ${day.startCountry?:"—"}"));details.addView(sub("Закрытие: ${day.endTime?:"—"} ${flag(day.endCountry)} ${day.endCountry?:"—"}"));box.addView(details);c.addView(box);c.addView(space(7))
            if(i<days.lastIndex){val older=days[i+1];model?.restBetween(older,day)?.let{rest->c.addView(TextView(this).apply{text=restTitle(rest);textSize=14f;gravity=Gravity.CENTER;setTextColor(if(rest.weekly)CYAN else if(rest.creditedDailyMinutes==540)YELLOW else MUTED);setPadding(dp(6),dp(7),dp(6),dp(7))})}}
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
    private fun restoreSnapshot(){currentActivity=prefs.getString(DriverLiveService.SNAP_ACTIVITY,"—")?:"—";activityMinutes=prefs.getInt(DriverLiveService.SNAP_ACTIVITY_MIN,0);continuousMinutes=prefs.getInt(DriverLiveService.SNAP_CONTINUOUS_MIN,0);breakMinutes=prefs.getInt(DriverLiveService.SNAP_BREAK_MIN,0);twoWeekMinutes=prefs.getInt(DriverLiveService.SNAP_TWO_WEEK_MIN,0)}
    private fun activeWorkTotal()=workWindowMinutes+if(currentActivity.contains("ВОЖДЕНИЕ")||currentActivity.contains("РАБОТА"))activityMinutes else 0
    private fun activeOtherWorkTotal()=otherWorkWindowMinutes+if(currentActivity.contains("РАБОТА"))activityMinutes else 0
    private fun activeAvailabilityTotal()=availabilityWindowMinutes+if(currentActivity.contains("ГОТОВНОСТЬ"))activityMinutes else 0

    private fun updateShiftDriving(){
        if(!::shiftDriving.isInitialized)return
        if(!shiftCounterInitialized){shiftDriving.text="—";shiftDrivingSub.text="ожидание первого live-цикла";return}
        val total=shiftCompletedMinutes+continuousMinutes;val limit=if(currentWeekTenHourUses()>=2)540 else 600;val remain=(limit-total).coerceAtLeast(0)
        shiftDriving.text="${HistoryData.fmt(total)} / ${HistoryData.fmt(limit)}"
        shiftDrivingSub.text=when{
            total>=limit->"⚠ Достигнут предел ${HistoryData.fmt(limit)}"
            remain<=30->"⚠ Осталось ${HistoryData.fmt(remain)} • предупреждение активно"
            limit==600->"осталось ${HistoryData.fmt(remain)} • 9:00 обычный предел • 10:00 доступно"
            else->"осталось ${HistoryData.fmt(remain)} • сегодня предел 9:00"
        }
        setProgress(shiftDrivingFrame,shiftDrivingProgress,total.toFloat()/limit,when{total>=limit-30->RED;total>=limit-60->YELLOW;else->GREEN})
    }
    private fun currentWeekTenHourUses():Int{val now=isoCalendar(Date());return history?.days.orEmpty().count{d->val date=parseDateOnly(d.date)?:return@count false;val c=isoCalendar(date);c.get(Calendar.WEEK_OF_YEAR)==now.get(Calendar.WEEK_OF_YEAR)&&c.getWeekYear()==now.getWeekYear()&&d.drivingMinutes>540}}
    private fun updateWorkWeekClock(){
        if(!::workWeek.isInitialized)return
        val start=history?.let{HistoryData.lastWeeklyRestEndMillis(it.days)}
        if(start==null){workWeek.text="—";workWeekSub.text="не найден законченный недельный отдых на карте";setProgress(workWeekFrame,workWeekProgress,0f,GREEN);return}
        val total=144*60;val elapsed=((System.currentTimeMillis()-start)/60000L).toInt().coerceAtLeast(0);val remain=(total-elapsed).coerceAtLeast(0)
        workWeek.text="${HistoryData.fmt(elapsed.coerceAtMost(total))} / 144:00"
        val stamp=SimpleDateFormat("dd.MM HH:mm",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date(start))
        workWeekSub.text="осталось ${HistoryData.fmt(remain)} • отсчёт от $stamp"
        setProgress(workWeekFrame,workWeekProgress,elapsed.coerceAtMost(total).toFloat()/total,when{remain<=12*60->RED;remain<=24*60->YELLOW;else->GREEN})
    }

    private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE;updateTabState(true)}
    private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE;updateTabState(false)}
    private fun updateTabState(nowSelected:Boolean){nowTab.background=rounded(if(nowSelected)GREEN else CARD,dp(11).toFloat(),if(nowSelected)GREEN else BORDER);historyTab.background=rounded(if(nowSelected)CARD else GREEN,dp(11).toFloat(),if(nowSelected)BORDER else GREEN)}

    private fun requestPermission(){
        val req=mutableListOf<String>()
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){if(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)req+=Manifest.permission.BLUETOOTH_CONNECT;if(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)req+=Manifest.permission.BLUETOOTH_SCAN}
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)req+=Manifest.permission.POST_NOTIFICATIONS
        if(req.isNotEmpty())permissionLauncher.launch(req.toTypedArray())else findAndAutoConnect()
    }
    @SuppressLint("MissingPermission") private fun findAndAutoConnect(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;val saved=prefs.getString(SELECTED_DTCO,null);dtco=try{adapter?.bondedDevices?.firstOrNull{it.address==saved}}catch(_:Throwable){null};val d=dtco;if(d==null){status.text="Выберите DTCO";return};connectSelected(d)}
    private fun connectSelected(d:BluetoothDevice){dtco=d;status.text="Подключение к DTCO…";DriverLiveService.start(applicationContext,d.address)}

    @SuppressLint("MissingPermission") private fun showDtcoPicker(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)){permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN));return}
        val devices=linkedMapOf<String,BluetoothDevice>();try{adapter?.bondedDevices?.filter{(it.name?:"").contains("DTCO",true)}?.forEach{devices[it.address]=it}}catch(_:Throwable){}
        val labels=mutableListOf<String>();val listAdapter=ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels);fun refresh(){labels.clear();devices.values.forEach{labels.add("${safeName(it)}\n${it.address}")};listAdapter.notifyDataSetChanged()};refresh()
        val dialog=AlertDialog.Builder(this).setTitle("Выберите DTCO").setAdapter(listAdapter){_,which->val d=devices.values.toList().getOrNull(which)?:return@setAdapter;prefs.edit().putString(SELECTED_DTCO,d.address).putBoolean(FIRST_READ,false).apply();initialReadAttemptedThisSession=false;dtco=d;status.text="Выбран ${safeName(d)}";connectSelected(d)}.setNegativeButton("Закрыть",null).create();dialog.setOnShowListener{startNearbyScan(devices,::refresh)};dialog.show()
    }
    @SuppressLint("MissingPermission") private fun startNearbyScan(devices:MutableMap<String,BluetoothDevice>,refresh:()->Unit){val a=adapter?:return;val cb=BluetoothAdapter.LeScanCallback{device,_,_->val n=try{device.name}catch(_:Throwable){null};if((n?:"").contains("DTCO",true)){devices[device.address]=device;runOnUiThread{refresh()}}};try{a.startLeScan(cb);handler.postDelayed({try{a.stopLeScan(cb)}catch(_:Throwable){}},4000)}catch(_:Throwable){}}
    @SuppressLint("MissingPermission") private fun safeName(d:BluetoothDevice)=try{d.name?:"DTCO"}catch(_:Throwable){"DTCO"}

    private fun startCardRead(reason:String,resume:Boolean){if(cardReading)return;val d=dtco?:return;cardReading=true;resumeLive=resume;status.text="Считывание карты • $reason";DriverLiveService.pause(applicationContext);handler.postDelayed({cardReader.connect(d)},500)}
    override fun onLiveConnection(connected:Boolean,deviceName:String?){runOnUiThread{if(cardReading)return@runOnUiThread;if(connected){status.text="Онлайн • ${deviceName?:"DTCO"}";status.setTextColor(GREEN);if(!prefs.getBoolean(FIRST_READ,false)&&!initialReadAttemptedThisSession){initialReadAttemptedThisSession=true;handler.postDelayed({if(!cardReading)startCardRead("Первое успешное подключение",true)},800)}}else{status.text="Связь потеряна • автоматическое переподключение…";status.setTextColor(YELLOW)}}}
    override fun onLiveLog(log:String){runOnUiThread{last(log,"F931")?.let{if(it.isNotBlank()&&it!="—"){driver.text=it;prefs.edit().putString(CARD_NAME,it).apply()}};last(log,"F903")?.let{currentActivity=it};mins(last(log,"F927"))?.let{activityMinutes=it};mins(last(log,"F923"))?.let{continuousMinutes=it};mins(last(log,"F925"))?.let{breakMinutes=it};mins(last(log,"F938"))?.let{twoWeekMinutes=it};val cycle=Regex("LIVE CYCLE #(\\d+) COMPLETE").findAll(log).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull();if(cycle!=null&&cycle>lastProcessedCycle){lastProcessedCycle=cycle;restoreCounters();restoreSnapshot();status.text="Онлайн • данные актуальны"};updateNow()}}
    override fun onLogChanged(fullLog:String){if(!cardReading)return;when{fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER)&&fullLog.contains("STATUS=SUCCESS")->runOnUiThread{prefs.edit().putBoolean(FIRST_READ,true).apply();loadHistory();finishCardRead(true)};fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER)&&fullLog.contains("STATUS=FAILED")->runOnUiThread{finishCardRead(false)}}}
    override fun onConnectionStateChanged(connected:Boolean,deviceName:String?){if(cardReading&&connected)runOnUiThread{status.text="Считывание карты…"}}
    private fun finishCardRead(ok:Boolean){val resume=resumeLive;cardReading=false;resumeLive=false;status.text=if(ok)"Карта считана • данные обновлены" else "Ошибка чтения карты • live восстановлен";cardReader.disconnect();if(resume){val d=dtco?:return;handler.postDelayed({DriverLiveService.start(applicationContext,d.address)},800)}}

    private fun updateNow(){
        if(!::continuous.isInitialized)return

        val continuousRemain=(270-continuousMinutes).coerceAtLeast(0)
        continuous.text="${HistoryData.fmt(continuousMinutes)} / 4:30"
        continuousSub.text=if(continuousRemain<=15)"⚠ До лимита ${HistoryData.fmt(continuousRemain)}" else "осталось ${HistoryData.fmt(continuousRemain)}"
        setProgress(continuousFrame,continuousProgress,continuousMinutes/270f,driveColor(continuousMinutes))
        updateShiftDriving()

        val resting=currentActivity.contains("ОТДЫХ")
        val actual=if(resting)maxOf(activityMinutes,breakMinutes) else breakMinutes
        val breakShown=actual.coerceAtMost(45)
        val first15=actual>=15
        restTime.text="${HistoryData.fmt(breakShown)} / 0:45"
        restSub.text=when{
            !resting&&actual<=0->"перерыв не начат"
            actual>=45->"✓ 45 минут выполнено"
            first15->"✓ 15 минут зафиксировано • осталось ${(45-actual).coerceAtLeast(0)} мин"
            else->"до первой ступени 15 мин осталось ${(15-actual).coerceAtLeast(0)} мин"
        }
        setProgress(restFrame,restProgress,breakShown/45f,when{actual>=45->GREEN;actual>=15->YELLOW;else->GREEN})

        dailyRestTime.text="${HistoryData.fmt(actual.coerceAtMost(11*60))} / 11:00"
        dailyRestSub.text=when{
            actual>=11*60->"✓ нормальный суточный отдых 11:00"
            actual>=9*60->"✓ сокращённый 9:00 • до 11:00 ${HistoryData.fmt(11*60-actual)}"
            actual>=3*60->"✓ первая часть 3:00 зафиксирована • следующая ступень 9:00"
            resting->"до фиксации 3:00 осталось ${HistoryData.fmt((3*60-actual).coerceAtLeast(0))}"
            else->"ступени: 3:00 → 9:00 → 11:00"
        }
        setProgress(dailyRestFrame,dailyRestProgress,actual.coerceAtMost(11*60)/(11f*60f),when{actual>=9*60->GREEN;actual>=3*60->YELLOW;else->GREEN})

        val wt=activeWorkTotal()
        work6.text="${HistoryData.fmt(wt)} / 6:00"
        work6Sub.text=if(wt>=330)"⚠ До 6 часов осталось ${HistoryData.fmt((360-wt).coerceAtLeast(0))}" else "осталось ${HistoryData.fmt((360-wt).coerceAtLeast(0))} • вождение + другая работа"
        setProgress(work6Frame,work6Progress,wt/360f,workColor(wt))

        val ow=activeOtherWorkTotal();otherWork.text=HistoryData.fmt(ow);setProgress(otherWorkFrame,otherWorkProgress,ow/360f,workColor(ow))
        val av=activeAvailabilityTotal();availability.text=HistoryData.fmt(av);setProgress(availabilityFrame,availabilityProgress,av/360f,GREEN)

        twoWeek.text="${HistoryData.fmt(twoWeekMinutes)} / 90:00"
        twoWeekSub.text="осталось ${HistoryData.fmt((90*60-twoWeekMinutes).coerceAtLeast(0))}"
        setProgress(twoWeekFrame,twoWeekProgress,twoWeekMinutes/(90f*60f),limitColor(twoWeekMinutes,90*60))

        activityState.text="Текущее состояние: ${currentActivity.ifBlank{"—"}}"
        updateWeekCards();updateWorkWeekClock()
    }

    private fun updateWeekCards(){
        if(!::week.isInitialized)return
        val current=history?.currentWeekCardMinutes?:0;val prev=history?.previousWeekDrivingMinutes?:0;val limit=minOf(56*60,(90*60-prev).coerceAtLeast(0))
        week.text="${HistoryData.fmt(current)} / ${HistoryData.fmt(limit)}"
        weekSub.text="доступно ещё ${HistoryData.fmt((limit-current).coerceAtLeast(0))} • прошл. неделя ${HistoryData.fmt(prev)} • 10ч: ${currentWeekTenHourUses()}/2"
        setProgress(weekFrame,weekProgress,if(limit>0)current.toFloat()/limit else 1f,limitColor(current,limit))
    }

    private fun parseDateOnly(v:String):Date?=runCatching{SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.parse(v)}.getOrNull()
    private fun isoCalendar(d:Date)=Calendar.getInstance(TimeZone.getTimeZone("UTC"),Locale.US).apply{firstDayOfWeek=Calendar.MONDAY;minimalDaysInFirstWeek=4;time=d}
    private fun last(log:String,did:String)=log.lines().asReversed().firstOrNull{it.startsWith("$did=")}?.substringAfter(" | ")?.trim();private fun mins(v:String?):Int?=v?.let{Regex("^(\\d+) мин").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()}
    private fun driveColor(m:Int)=when{m>=255->RED;m>=240->YELLOW;else->GREEN};private fun restMilestoneColor(m:Int)=when{m>=45*60->CYAN;m>=24*60->GREEN;m>=11*60->GREEN;m>=9*60->GREEN;m>=3*60->YELLOW;m>=45->GREEN;m>=15->YELLOW;else->RED};private fun workColor(m:Int)=when{m>=360->RED;m>=330->YELLOW;else->GREEN};private fun limitColor(v:Int,limit:Int)=when{limit<=0||v>=limit-120->RED;v>=limit-360->YELLOW;else->GREEN}

    private fun gaugeCard(title:String,markerText:String,ticks:List<Float>):Gauge{
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(10),dp(12),dp(9))
            background=rounded(CARD,dp(15).toFloat(),BORDER)
        }
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        top.addView(TextView(this).apply{text=title;textSize=13f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD)},LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        val v=TextView(this).apply{text="—";textSize=23f;setTextColor(NEON_GREEN);setTypeface(typeface,Typeface.BOLD);gravity=Gravity.END}
        top.addView(v)
        box.addView(top)

        val frame=FrameLayout(this).apply{background=rounded(TRACK,dp(6).toFloat(),BORDER)}
        val progress=View(this).apply{background=progressDrawable(GREEN)}
        frame.addView(progress,FrameLayout.LayoutParams(0,dp(10)))
        ticks.forEach{ratio->
            val tick=View(this).apply{setBackgroundColor(if(ratio>=0.999f)RED else AMBER)}
            frame.addView(tick,FrameLayout.LayoutParams(dp(2),dp(16)).apply{gravity=Gravity.TOP})
            frame.post{
                val lp=tick.layoutParams as FrameLayout.LayoutParams
                lp.leftMargin=((frame.width-dp(2))*ratio.coerceIn(0f,1f)).toInt()
                tick.layoutParams=lp
            }
        }
        box.addView(frame,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(10)).apply{topMargin=dp(7)})

        if(markerText.isNotBlank())box.addView(TextView(this).apply{text=markerText;textSize=10.5f;setTextColor(MUTED);gravity=Gravity.END;setPadding(0,dp(3),0,0)})
        val s=TextView(this).apply{text="";textSize=12f;setTextColor(MUTED);setPadding(0,dp(3),0,0)}
        box.addView(s)
        return Gauge(box,frame,progress,v,s)
    }

    private fun miniMetric(title:String):Gauge{
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(11),dp(9),dp(11),dp(9));background=rounded(CARD,dp(14).toFloat(),BORDER)}
        box.addView(TextView(this).apply{text=title;textSize=11.5f;setTextColor(MUTED);setTypeface(typeface,Typeface.BOLD)})
        val v=TextView(this).apply{text="0:00";textSize=21f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD)}
        box.addView(v)
        val frame=FrameLayout(this).apply{background=rounded(TRACK,dp(4).toFloat())}
        val progress=View(this).apply{background=progressDrawable(GREEN)}
        frame.addView(progress,FrameLayout.LayoutParams(0,dp(5)))
        box.addView(frame,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(5)).apply{topMargin=dp(5)})
        val s=TextView(this)
        return Gauge(box,frame,progress,v,s)
    }

    private fun setProgress(frame:FrameLayout,bar:View,p:Float,color:Int){
        frame.post{
            val w=(frame.width*p.coerceIn(0f,1f)).toInt()
            val lp=bar.layoutParams as FrameLayout.LayoutParams
            if(lp.width!=w){lp.width=w;bar.layoutParams=lp}
            bar.background=progressDrawable(color)
        }
    }

    private fun progressDrawable(color:Int)=when(color){
        RED->GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(NEON_GREEN,AMBER,0xFFFF8A22.toInt(),0xFFFF345F.toInt())).apply{cornerRadius=dp(6).toFloat()}
        YELLOW->GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(NEON_GREEN,0xFF9FEA39.toInt(),AMBER)).apply{cornerRadius=dp(6).toFloat()}
        else->GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xFF1DEB7E.toInt(),0xFF62F15C.toInt())).apply{cornerRadius=dp(6).toFloat()}
    }

    private fun toggleKeepScreen(){prefs.edit().putBoolean(KEEP_SCREEN_ON,!prefs.getBoolean(KEEP_SCREEN_ON,false)).apply();applyKeepScreenSetting()}
    private fun applyKeepScreenSetting(){
        val enabled=prefs.getBoolean(KEEP_SCREEN_ON,false)
        if(enabled)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(::keepScreenButton.isInitialized){
            keepScreenButton.text=if(enabled)"☀ Экран: ON" else "☀ Экран: AUTO"
            keepScreenButton.background=rounded(if(enabled)GREEN else CARD,dp(11).toFloat(),if(enabled)NEON_GREEN else BORDER)
        }
    }

    private fun progressCard():Triple<FrameLayout,View,LinearLayout>{val f=FrameLayout(this).apply{background=rounded(CARD,dp(15).toFloat(),BORDER)};val p=View(this).apply{background=rounded(GREEN,dp(15).toFloat())};f.addView(p,FrameLayout.LayoutParams(0,FrameLayout.LayoutParams.MATCH_PARENT));val b=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(11),dp(12),dp(11))};f.addView(b,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.WRAP_CONTENT));return Triple(f,p,b)}
    private fun card()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(CARD,dp(14).toFloat(),BORDER)}
    private fun label(t:String)=TextView(this).apply{text=t;textSize=11.5f;setTextColor(MUTED);setTypeface(typeface,Typeface.BOLD)};private fun value(t:String,s:Float)=TextView(this).apply{text=t;textSize=s;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(2),0,0)};private fun sub(t:String)=TextView(this).apply{text=t;textSize=13f;setTextColor(MUTED);setPadding(0,dp(2),0,0)}
    private fun tabButton(t:String)=Button(this).apply{text=t;isAllCaps=false;textSize=14f;setTextColor(TEXT);background=rounded(CARD,dp(11).toFloat(),BORDER)};private fun smallButton(t:String)=tabButton(t).apply{textSize=11f;minWidth=0;minimumWidth=0;setPadding(dp(9),0,dp(9),0)}
    private fun rounded(c:Int,r:Float,stroke:Int?=null)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;cornerRadius=r;setColor(c);if(stroke!=null)setStroke(dp(1),stroke)};private fun space(h:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(h))};private fun hspace(w:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(dp(w),1)};private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun prettyDate(d:String)=runCatching{val p=d.split('-');"${p[2]}.${p[1]}.${p[0]}"}.getOrDefault(d);private fun flag(c:String?)=when(c){"B"->"🇧🇪";"F"->"🇫🇷";"D"->"🇩🇪";"NL"->"🇳🇱";"L"->"🇱🇺";"E"->"🇪🇸";"LT"->"🇱🇹";"LV"->"🇱🇻";else->"🌐"}
}
