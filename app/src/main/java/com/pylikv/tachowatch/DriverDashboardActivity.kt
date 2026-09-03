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

class DriverDashboardActivity : AppCompatActivity(), LiveDidDiagnostic.Listener, DtcoBluetoothDiagnostic.Listener {
    companion object {
        private const val BG=0xFF0B1118.toInt(); private const val CARD=0xFF141D27.toInt(); private const val TEXT=0xFFF3F7FA.toInt()
        private const val MUTED=0xFF9BAAB8.toInt(); private const val GREEN=0xFF238A52.toInt(); private const val YELLOW=0xFF9A7A1B.toInt()
        private const val RED=0xFF9E3434.toInt(); private const val CYAN=0xFF29B6C8.toInt(); private const val BORDER=0xFF263545.toInt()
        private const val PREFS="tachowatch_auto_card"; private const val FIRST_READ="first_card_read_done"; private const val SELECTED_DTCO="selected_dtco_address"; private const val CARD_NAME="driver_name_from_card"
        private const val SHIFT_INITIALIZED="shift_counter_initialized"; private const val SHIFT_COMPLETED="shift_completed_driving"; private const val SHIFT_PREV_CONTINUOUS="shift_prev_continuous"
        private const val WORK_WINDOW="work_window_minutes"; private const val WORK_PREV_ACTIVITY="work_prev_activity"; private const val WORK_PREV_DURATION="work_prev_duration"
        private const val WORK_ACC="other_work_window_minutes"; private const val AVAIL_ACC="availability_window_minutes"
    }

    private val btManager by lazy{getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager}; private val adapter by lazy{btManager.adapter}; private val prefs by lazy{getSharedPreferences(PREFS,Context.MODE_PRIVATE)}
    private val mainHandler=Handler(Looper.getMainLooper())
    private lateinit var live:LiveDidDiagnostic; private lateinit var cardReader:DtcoBluetoothDiagnostic
    private var dtco:BluetoothDevice?=null; private var history:HistoryData.Model?=null; private var cardReading=false; private var resumeLive=false

    private lateinit var status:TextView; private lateinit var connectButton:Button; private lateinit var nowRoot:LinearLayout; private lateinit var historyRoot:LinearLayout; private lateinit var driver:TextView
    private lateinit var activityTitle:TextView; private lateinit var activityTime:TextView; private lateinit var activitySub:TextView; private lateinit var activityFrame:FrameLayout; private lateinit var activityProgress:View
    private lateinit var continuous:TextView; private lateinit var continuousFrame:FrameLayout; private lateinit var continuousProgress:View
    private lateinit var shiftDriving:TextView; private lateinit var shiftDrivingSub:TextView; private lateinit var shiftDrivingFrame:FrameLayout; private lateinit var shiftDrivingProgress:View
    private lateinit var work6:TextView; private lateinit var work6Sub:TextView; private lateinit var work6Frame:FrameLayout; private lateinit var work6Progress:View
    private lateinit var otherWork:TextView; private lateinit var otherWorkFrame:FrameLayout; private lateinit var otherWorkProgress:View
    private lateinit var availability:TextView; private lateinit var availabilityFrame:FrameLayout; private lateinit var availabilityProgress:View
    private lateinit var week:TextView; private lateinit var weekSub:TextView; private lateinit var weekFrame:FrameLayout; private lateinit var weekProgress:View
    private lateinit var twoWeek:TextView; private lateinit var twoWeekFrame:FrameLayout; private lateinit var twoWeekProgress:View

    private var currentActivity="—"; private var activityMinutes=0; private var continuousMinutes=0; private var breakMinutes=0; private var twoWeekMinutes=0
    private var shiftCounterInitialized=false; private var shiftCompletedMinutes=0; private var previousContinuousMinutes=0; private var lastProcessedCycle=0
    private var workWindowMinutes=0; private var previousActivity="—"; private var previousActivityDuration=0; private var otherWorkWindowMinutes=0; private var availabilityWindowMinutes=0

    private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){findAndAutoConnect()}

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=BG;window.navigationBarColor=BG;live=LiveDidDiagnostic(applicationContext,this);cardReader=DtcoBluetoothDiagnostic(applicationContext,this);restoreCounters();buildUi();loadHistory();requestPermission()}
    override fun onDestroy(){live.disconnect();cardReader.disconnect();super.onDestroy()}

    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));setBackgroundColor(BG)}
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};val titles=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        titles.addView(TextView(this).apply{text="TachoWatch";textSize=25f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD)});status=TextView(this).apply{text="DTCO не подключён";textSize=11.5f;setTextColor(CYAN)};titles.addView(status)
        top.addView(titles,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));connectButton=smallButton("Подключить DTCO").apply{setOnClickListener{showDtcoPicker()}};top.addView(connectButton);top.addView(hspace(5));top.addView(smallButton("Диагностика").apply{setOnClickListener{startActivity(Intent(this@DriverDashboardActivity,MainActivity::class.java))}});root.addView(top);root.addView(space(7))
        val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};tabs.addView(tabButton("Сейчас").apply{setOnClickListener{showNow()}},LinearLayout.LayoutParams(0,dp(42),1f));tabs.addView(hspace(6));tabs.addView(tabButton("История").apply{setOnClickListener{showHistory()}},LinearLayout.LayoutParams(0,dp(42),1f));root.addView(tabs);root.addView(space(7))
        val viewport=FrameLayout(this);nowRoot=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};historyRoot=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE};viewport.addView(nowRoot);viewport.addView(historyRoot);root.addView(viewport,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));setContentView(root);buildNow();buildHistoryView()
    }

    private fun buildNow(){
        val scroll=ScrollView(this);val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};driver=TextView(this).apply{text=prefs.getString(CARD_NAME,null)?:"Водитель";textSize=25f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD);setPadding(dp(3),dp(4),0,dp(8))};c.addView(driver)
        val a=progressCard();activityFrame=a.first;activityProgress=a.second;activityTitle=label("ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ");a.third.addView(activityTitle);activityTime=value("—",34f);a.third.addView(activityTime);activitySub=sub("ожидание онлайн-данных");a.third.addView(activitySub);c.addView(activityFrame);c.addView(space(7))
        val d=progressCard();continuousFrame=d.first;continuousProgress=d.second;d.third.addView(label("🚗  НЕПРЕРЫВНОЕ ВОЖДЕНИЕ"));continuous=value("—",30f);d.third.addView(continuous);d.third.addView(sub("лимит 4:30"));c.addView(continuousFrame);c.addView(space(7))
        val s=progressCard();shiftDrivingFrame=s.first;shiftDrivingProgress=s.second;s.third.addView(label("🚗  ВОЖДЕНИЕ ЗА СМЕНУ"));shiftDriving=value("—",28f);s.third.addView(shiftDriving);shiftDrivingSub=sub("онлайн");s.third.addView(shiftDrivingSub);c.addView(shiftDrivingFrame);c.addView(space(7))
        val six=progressCard();work6Frame=six.first;work6Progress=six.second;six.third.addView(label("⚒  НЕПРЕРЫВНАЯ РАБОТА"));work6=value("—",28f);six.third.addView(work6);work6Sub=sub("вождение + другая работа • окно 6:00");six.third.addView(work6Sub);c.addView(work6Frame);c.addView(space(7))
        val ow=progressCard();otherWorkFrame=ow.first;otherWorkProgress=ow.second;ow.third.addView(label("⚒  ДРУГАЯ РАБОТА"));otherWork=value("0:00",26f);ow.third.addView(otherWork);ow.third.addView(sub("в текущем 6-часовом рабочем окне"));c.addView(otherWorkFrame);c.addView(space(7))
        val av=progressCard();availabilityFrame=av.first;availabilityProgress=av.second;av.third.addView(label("✉  ОЖИДАНИЕ / ГОТОВНОСТЬ"));availability=value("0:00",26f);av.third.addView(availability);av.third.addView(sub("в текущем рабочем окне"));c.addView(availabilityFrame);c.addView(space(7))
        val w=progressCard();weekFrame=w.first;weekProgress=w.second;w.third.addView(label("🚗  ТЕКУЩАЯ НЕДЕЛЯ"));week=value("—",28f);w.third.addView(week);weekSub=sub("по карте водителя");w.third.addView(weekSub);c.addView(weekFrame);c.addView(space(7))
        val tw=progressCard();twoWeekFrame=tw.first;twoWeekProgress=tw.second;tw.third.addView(label("🚗  ДВЕ НЕДЕЛИ"));twoWeek=value("—",28f);tw.third.addView(twoWeek);tw.third.addView(sub("лимит 90:00"));c.addView(twoWeekFrame)
        scroll.addView(c);nowRoot.addView(scroll,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun buildHistoryView(){
        historyRoot.removeAllViews();val scroll=ScrollView(this);val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val days=history?.days?.takeLast(56)?.asReversed().orEmpty();c.addView(sub("История карты: ${days.size} из 56 дней"))
        if(days.isEmpty())c.addView(value("История появится после полного считывания карты",17f))
        days.forEachIndexed{i,day->val box=card();box.addView(TextView(this).apply{text=prettyDate(day.date);textSize=18f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD)});box.addView(sub("${flag(day.startCountry)} ${day.startCountry?:"—"}  Начало смены  ${day.startTime?:"—"}"));box.addView(value("🚗 Вождение  ${HistoryData.fmt(day.drivingMinutes)}",18f));box.addView(sub("⚒ Другая работа  ${HistoryData.fmt(day.workMinutes)}"));box.addView(sub("✉ Готовность  ${HistoryData.fmt(day.availabilityMinutes)}"));box.addView(sub("Продолжительность смены  ${day.shiftMinutes?.let(HistoryData::fmt)?:"—"}"));box.addView(sub("${flag(day.endCountry)} ${day.endCountry?:"—"}  Конец смены  ${day.endTime?:"—"}"));c.addView(box);if(i<days.lastIndex)HistoryData.gapMinutes(days[i+1],day)?.let{gap->c.addView(TextView(this).apply{text=if(gap>=24*60)"🛏 Недельный отдых  ${HistoryData.fmt(gap)}" else "🛏 Межсуточный отдых  ${HistoryData.fmt(gap)}";textSize=14f;gravity=Gravity.CENTER;setTextColor(if(gap>=24*60)CYAN else MUTED);setPadding(0,dp(7),0,dp(7))})}}
        scroll.addView(c);historyRoot.addView(scroll,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun loadHistory(){val f=TlvInventory.findLatestDdd(getExternalFilesDir(null))?:return;val r=TlvInventory.parse(f);if(r.error==null){history=HistoryData.load(r);if(::historyRoot.isInitialized)buildHistoryView();updateWeekCards();updateShiftDriving()}}

    private fun restoreCounters(){shiftCounterInitialized=prefs.getBoolean(SHIFT_INITIALIZED,false);shiftCompletedMinutes=prefs.getInt(SHIFT_COMPLETED,0);previousContinuousMinutes=prefs.getInt(SHIFT_PREV_CONTINUOUS,0);workWindowMinutes=prefs.getInt(WORK_WINDOW,0);previousActivity=prefs.getString(WORK_PREV_ACTIVITY,"—")?:"—";previousActivityDuration=prefs.getInt(WORK_PREV_DURATION,0);otherWorkWindowMinutes=prefs.getInt(WORK_ACC,0);availabilityWindowMinutes=prefs.getInt(AVAIL_ACC,0)}
    private fun persistCounters(){prefs.edit().putBoolean(SHIFT_INITIALIZED,shiftCounterInitialized).putInt(SHIFT_COMPLETED,shiftCompletedMinutes).putInt(SHIFT_PREV_CONTINUOUS,previousContinuousMinutes).putInt(WORK_WINDOW,workWindowMinutes).putString(WORK_PREV_ACTIVITY,previousActivity).putInt(WORK_PREV_DURATION,previousActivityDuration).putInt(WORK_ACC,otherWorkWindowMinutes).putInt(AVAIL_ACC,availabilityWindowMinutes).apply()}

    private fun initializeShiftCounterFromCard(){if(shiftCounterInitialized)return;val cardDriving=history?.days?.lastOrNull()?.drivingMinutes?:0;shiftCompletedMinutes=(cardDriving-continuousMinutes).coerceAtLeast(0);previousContinuousMinutes=continuousMinutes;shiftCounterInitialized=true;persistCounters()}
    private fun processCycle(){initializeShiftCounterFromCard();if(previousContinuousMinutes>0&&continuousMinutes<previousContinuousMinutes&&breakMinutes>=45)shiftCompletedMinutes+=previousContinuousMinutes;previousContinuousMinutes=continuousMinutes;processWorkWindow();persistCounters();updateShiftDriving()}
    private fun processWorkWindow(){
        if(currentActivity.contains("ОТДЫХ")&&breakMinutes>=45){workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;previousActivity=currentActivity;previousActivityDuration=activityMinutes;return}
        if(previousActivity!=currentActivity){val finished=previousActivityDuration.coerceAtLeast(0);when{previousActivity.contains("ВОЖДЕНИЕ")->workWindowMinutes+=finished;previousActivity.contains("РАБОТА")->{workWindowMinutes+=finished;otherWorkWindowMinutes+=finished};previousActivity.contains("ГОТОВНОСТЬ")->availabilityWindowMinutes+=finished};previousActivity=currentActivity;previousActivityDuration=activityMinutes;return}
        previousActivityDuration=activityMinutes
    }
    private fun activeWorkTotal():Int=workWindowMinutes+when{currentActivity.contains("ВОЖДЕНИЕ")||currentActivity.contains("РАБОТА")->activityMinutes;else->0}
    private fun activeOtherWorkTotal():Int=otherWorkWindowMinutes+if(currentActivity.contains("РАБОТА"))activityMinutes else 0
    private fun activeAvailabilityTotal():Int=availabilityWindowMinutes+if(currentActivity.contains("ГОТОВНОСТЬ"))activityMinutes else 0

    private fun updateShiftDriving(){if(!::shiftDriving.isInitialized)return;if(!shiftCounterInitialized){shiftDriving.text="—";shiftDrivingSub.text="ожидание первого live-цикла";return};val total=shiftCompletedMinutes+continuousMinutes;val limit=shiftDrivingLimit();val remain=(limit-total).coerceAtLeast(0);shiftDriving.text="${HistoryData.fmt(total)} из ${HistoryData.fmt(limit)}";shiftDrivingSub.text=if(remain<=30)"⚠ Осталось ${HistoryData.fmt(remain)} вождения" else "осталось ${HistoryData.fmt(remain)} • онлайн";setProgress(shiftDrivingFrame,shiftDrivingProgress,total.toFloat()/limit,shiftDriveColor(total,limit))}
    private fun shiftDrivingLimit():Int=if(currentWeekTenHourUses()>=2)540 else 600
    private fun currentWeekTenHourUses():Int{val now=isoCalendar(Date());return history?.days.orEmpty().count{d->val date=parseDateOnly(d.date)?:return@count false;val c=isoCalendar(date);c.get(Calendar.WEEK_OF_YEAR)==now.get(Calendar.WEEK_OF_YEAR)&&c.getWeekYear()==now.getWeekYear()&&d.drivingMinutes>540}}
    private fun shiftDriveColor(v:Int,limit:Int)=when{v>=limit-30->RED;v>=limit-60->YELLOW;else->GREEN}

    private fun resetAllWindows(){shiftCompletedMinutes=0;previousContinuousMinutes=0;shiftCounterInitialized=true;workWindowMinutes=0;otherWorkWindowMinutes=0;availabilityWindowMinutes=0;previousActivity="—";previousActivityDuration=0;persistCounters();updateShiftDriving()}
    private fun onShiftClosedDetected(){resetAllWindows();startCardRead("Смена закрыта",true)}

    private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE};private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE}

    private fun requestPermission(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN))else findAndAutoConnect()}
    @SuppressLint("MissingPermission") private fun findAndAutoConnect(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;val saved=prefs.getString(SELECTED_DTCO,null);dtco=try{adapter?.bondedDevices?.firstOrNull{it.address==saved}}catch(_:Throwable){null};val d=dtco;if(d==null){status.text="Выберите DTCO";return};connectSelected(d)}
    private fun connectSelected(d:BluetoothDevice){dtco=d;if(!prefs.getBoolean(FIRST_READ,false))startCardRead("Первое подключение",true)else{status.text="Подключение к DTCO…";live.connect(d)}}

    @SuppressLint("MissingPermission") private fun showDtcoPicker(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)){permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN));return}
        val devices=linkedMapOf<String,BluetoothDevice>();try{adapter?.bondedDevices?.filter{(it.name?:"").contains("DTCO",true)}?.forEach{devices[it.address]=it}}catch(_:Throwable){}
        val labels=mutableListOf<String>();val listAdapter=ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels);fun refresh(){labels.clear();devices.values.forEach{labels.add("${safeName(it)}\n${it.address}")};listAdapter.notifyDataSetChanged()};refresh()
        val dialog=AlertDialog.Builder(this).setTitle("Выберите DTCO").setAdapter(listAdapter){_,which->val d=devices.values.toList().getOrNull(which)?:return@setAdapter;prefs.edit().putString(SELECTED_DTCO,d.address).putBoolean(FIRST_READ,false).apply();dtco=d;status.text="Выбран ${safeName(d)}";connectSelected(d)}.setNegativeButton("Закрыть",null).create();dialog.setOnShowListener{startNearbyScan(devices,::refresh)};dialog.show()
    }
    @SuppressLint("MissingPermission") private fun startNearbyScan(devices:MutableMap<String,BluetoothDevice>,refresh:()->Unit){val a=adapter?:return;val cb=BluetoothAdapter.LeScanCallback{device,_,_->val n=try{device.name}catch(_:Throwable){null};if((n?:"").contains("DTCO",true)){devices[device.address]=device;runOnUiThread{refresh()}}};try{a.startLeScan(cb);mainHandler.postDelayed({try{a.stopLeScan(cb)}catch(_:Throwable){}},4000)}catch(_:Throwable){}}
    @SuppressLint("MissingPermission") private fun safeName(d:BluetoothDevice)=try{d.name?:"DTCO"}catch(_:Throwable){"DTCO"}

    private fun startCardRead(reason:String,resume:Boolean){if(cardReading)return;val d=dtco?:return;cardReading=true;resumeLive=resume;status.text="Считывание карты • $reason";live.disconnect();cardReader.connect(d)}
    override fun onLiveConnection(connected:Boolean,deviceName:String?){runOnUiThread{if(!cardReading)status.text=if(connected)"Онлайн • ${deviceName?:"DTCO"}" else "Нет связи с выбранным DTCO"}}
    override fun onLiveLog(log:String){runOnUiThread{last(log,"F931")?.let{if(it.isNotBlank()&&it!="—"){driver.text=it;prefs.edit().putString(CARD_NAME,it).apply()}};last(log,"F903")?.let{currentActivity=it};mins(last(log,"F927"))?.let{activityMinutes=it};mins(last(log,"F923"))?.let{continuousMinutes=it};mins(last(log,"F925"))?.let{breakMinutes=it};mins(last(log,"F938"))?.let{twoWeekMinutes=it};val cycle=Regex("LIVE CYCLE #(\\d+) COMPLETE").findAll(log).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull();if(cycle!=null&&cycle>lastProcessedCycle){lastProcessedCycle=cycle;processCycle();status.text="Онлайн • данные актуальны"};updateNow()}}
    override fun onLogChanged(fullLog:String){if(!cardReading)return;when{fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER)&&fullLog.contains("STATUS=SUCCESS")->runOnUiThread{prefs.edit().putBoolean(FIRST_READ,true).apply();loadHistory();finishCardRead(true)};fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER)&&fullLog.contains("STATUS=FAILED")->runOnUiThread{finishCardRead(false)}}}
    override fun onConnectionStateChanged(connected:Boolean,deviceName:String?){if(cardReading&&connected)runOnUiThread{status.text="Считывание карты…"}}
    private fun finishCardRead(ok:Boolean){val resume=resumeLive;cardReading=false;resumeLive=false;status.text=if(ok)"Карта считана • данные обновлены" else "Ошибка чтения карты • подключаю live";cardReader.disconnect();if(resume){val d=dtco?:return;status.postDelayed({live.connect(d)},500)}}

    private fun updateNow(){
        val dailyRest=isDailyRestNow();val icon=when{currentActivity.contains("ВОЖДЕНИЕ")->"🚗";currentActivity.contains("РАБОТА")->"⚒";currentActivity.contains("ГОТОВНОСТЬ")->"✉";currentActivity.contains("ОТДЫХ")->"🛏";else->"•"}
        if(dailyRest){activityTitle.text="🛏  СУТОЧНЫЙ ОТДЫХ";activityTime.text=HistoryData.fmt(activityMinutes);activitySub.text="${HistoryData.fmt(activityMinutes)} из 9:00 • полный 11:00";setProgress(activityFrame,activityProgress,activityMinutes/(9f*60f),dailyRestColor(activityMinutes))}else{activityTitle.text="$icon  ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ • $currentActivity";activityTime.text=HistoryData.fmt(activityMinutes);val p:Float;val color:Int;val text:String;when{currentActivity.contains("ВОЖДЕНИЕ")->{p=continuousMinutes/270f;color=driveColor(continuousMinutes);text="непрерывно ${HistoryData.fmt(continuousMinutes)} из 4:30"};currentActivity.contains("ОТДЫХ")->{p=breakMinutes/45f;color=breakColor(breakMinutes);text="пауза ${HistoryData.fmt(breakMinutes)} из 0:45"};currentActivity.contains("РАБОТА")->{p=activeWorkTotal()/360f;color=workColor(activeWorkTotal());text="⚒ рабочее окно ${HistoryData.fmt(activeWorkTotal())} из 6:00"};currentActivity.contains("ГОТОВНОСТЬ")->{p=activityMinutes/360f;color=GREEN;text="✉ ожидание ${HistoryData.fmt(activityMinutes)}"};else->{p=0f;color=GREEN;text="ожидание данных"}};activitySub.text=text;setProgress(activityFrame,activityProgress,p,color)}
        continuous.text="${HistoryData.fmt(continuousMinutes)} из 4:30";setProgress(continuousFrame,continuousProgress,continuousMinutes/270f,driveColor(continuousMinutes));updateShiftDriving()
        val wt=activeWorkTotal();work6.text="${HistoryData.fmt(wt)} из 6:00";work6Sub.text=if(wt>=330)"⚠ До 6 часов осталось ${HistoryData.fmt((360-wt).coerceAtLeast(0))}" else "вождение + другая работа";setProgress(work6Frame,work6Progress,wt/360f,workColor(wt))
        val ow=activeOtherWorkTotal();otherWork.text=HistoryData.fmt(ow);setProgress(otherWorkFrame,otherWorkProgress,ow/360f,workColor(ow))
        val av=activeAvailabilityTotal();availability.text=HistoryData.fmt(av);setProgress(availabilityFrame,availabilityProgress,av/360f,GREEN)
        twoWeek.text="${HistoryData.fmt(twoWeekMinutes)} из 90:00";setProgress(twoWeekFrame,twoWeekProgress,twoWeekMinutes/(90f*60f),limitColor(twoWeekMinutes,90*60));updateWeekCards()
    }

    private fun updateWeekCards(){if(!::week.isInitialized)return;val current=history?.currentWeekCardMinutes?:0;val prev=history?.previousWeekDrivingMinutes?:0;val limit=minOf(56*60,(90*60-prev).coerceAtLeast(0));week.text="${HistoryData.fmt(current)} из ${HistoryData.fmt(limit)}";weekSub.text="прошлая неделя ${HistoryData.fmt(prev)} • 10-часовых смен: ${currentWeekTenHourUses()}/2";setProgress(weekFrame,weekProgress,if(limit>0)current.toFloat()/limit else 1f,limitColor(current,limit))}
    private fun isDailyRestNow():Boolean{if(!currentActivity.contains("ОТДЫХ"))return false;if(activityMinutes>45||breakMinutes>45)return true;val last=history?.days?.lastOrNull{it.endTime!=null}?:return false;val end=parseUtc("${last.date} ${last.endTime}")?:return false;val elapsed=(System.currentTimeMillis()-end.time)/60000L;return elapsed in 0..900}
    private fun parseUtc(v:String):Date?=runCatching{SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.parse(v)}.getOrNull();private fun parseDateOnly(v:String):Date?=runCatching{SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.parse(v)}.getOrNull();private fun isoCalendar(d:Date)=Calendar.getInstance(TimeZone.getTimeZone("UTC"),Locale.US).apply{firstDayOfWeek=Calendar.MONDAY;minimalDaysInFirstWeek=4;time=d}
    private fun last(log:String,did:String)=log.lines().asReversed().firstOrNull{it.startsWith("$did=")}?.substringAfter(" | ")?.trim();private fun mins(v:String?):Int?=v?.let{Regex("^(\\d+) мин").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()}
    private fun driveColor(m:Int)=when{m>=255->RED;m>=240->YELLOW;else->GREEN};private fun breakColor(m:Int)=when{m>=45->GREEN;m>=15->YELLOW;else->RED};private fun dailyRestColor(m:Int)=when{m>=660->GREEN;m>=540->YELLOW;else->RED};private fun workColor(m:Int)=when{m>=360->RED;m>=330->YELLOW;else->GREEN};private fun limitColor(v:Int,limit:Int)=when{limit<=0||v>=limit-120->RED;v>=limit-360->YELLOW;else->GREEN}

    private fun progressCard():Triple<FrameLayout,View,LinearLayout>{val f=FrameLayout(this).apply{background=rounded(CARD,dp(15).toFloat(),BORDER)};val p=View(this).apply{background=rounded(GREEN,dp(15).toFloat())};f.addView(p,FrameLayout.LayoutParams(0,FrameLayout.LayoutParams.MATCH_PARENT));val b=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(11),dp(12),dp(11))};f.addView(b,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.WRAP_CONTENT));return Triple(f,p,b)}
    private fun setProgress(frame:FrameLayout,bar:View,p:Float,color:Int){frame.post{val w=(frame.width*p.coerceIn(0f,1f)).toInt();val lp=bar.layoutParams as FrameLayout.LayoutParams;if(lp.width!=w){lp.width=w;bar.layoutParams=lp};bar.background=rounded(color,dp(15).toFloat())}}
    private fun card()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(CARD,dp(14).toFloat(),BORDER)}
    private fun label(t:String)=TextView(this).apply{text=t;textSize=11.5f;setTextColor(MUTED);setTypeface(typeface,Typeface.BOLD)};private fun value(t:String,s:Float)=TextView(this).apply{text=t;textSize=s;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(2),0,0)};private fun sub(t:String)=TextView(this).apply{text=t;textSize=13f;setTextColor(MUTED);setPadding(0,dp(2),0,0)}
    private fun tabButton(t:String)=Button(this).apply{text=t;isAllCaps=false;textSize=14f;setTextColor(TEXT);background=rounded(CARD,dp(11).toFloat(),BORDER)};private fun smallButton(t:String)=tabButton(t).apply{textSize=11f;minWidth=0;minimumWidth=0;setPadding(dp(9),0,dp(9),0)}
    private fun rounded(c:Int,r:Float,stroke:Int?=null)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;cornerRadius=r;setColor(c);if(stroke!=null)setStroke(dp(1),stroke)};private fun space(h:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(h))};private fun hspace(w:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(dp(w),1)};private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun prettyDate(d:String)=runCatching{val p=d.split('-');"${p[2]}.${p[1]}.${p[0]}"}.getOrDefault(d);private fun flag(c:String?)=when(c){"B"->"🇧🇪";"F"->"🇫🇷";"D"->"🇩🇪";"NL"->"🇳🇱";"L"->"🇱🇺";"E"->"🇪🇸";"LT"->"🇱🇹";"LV"->"🇱🇻";else->"🌐"}
}
