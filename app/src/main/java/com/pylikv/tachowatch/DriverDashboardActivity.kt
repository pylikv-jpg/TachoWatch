package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DriverDashboardActivity : AppCompatActivity(), LiveDidDiagnostic.Listener {
    companion object {
        private const val BG=0xFF0B1118.toInt(); private const val CARD=0xFF141D27.toInt(); private const val TEXT=0xFFF3F7FA.toInt()
        private const val MUTED=0xFF9BAAB8.toInt(); private const val GREEN=0xFF238A52.toInt(); private const val YELLOW=0xFF9A7A1B.toInt()
        private const val RED=0xFF9E3434.toInt(); private const val CYAN=0xFF29B6C8.toInt(); private const val BORDER=0xFF263545.toInt()
    }

    private val btManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val adapter by lazy { btManager.adapter }
    private lateinit var live: LiveDidDiagnostic
    private var dtco: BluetoothDevice?=null
    private var history: HistoryData.Model?=null

    private lateinit var status:TextView; private lateinit var nowRoot:LinearLayout; private lateinit var historyRoot:LinearLayout
    private lateinit var driver:TextView; private lateinit var activityTitle:TextView; private lateinit var activityTime:TextView; private lateinit var activitySub:TextView
    private lateinit var activityFrame:FrameLayout; private lateinit var activityProgress:View
    private lateinit var continuous:TextView; private lateinit var continuousFrame:FrameLayout; private lateinit var continuousProgress:View
    private lateinit var week:TextView; private lateinit var weekSub:TextView; private lateinit var weekFrame:FrameLayout; private lateinit var weekProgress:View
    private lateinit var twoWeek:TextView; private lateinit var twoWeekFrame:FrameLayout; private lateinit var twoWeekProgress:View

    private var currentActivity="—"; private var activityMinutes=0; private var continuousMinutes=0; private var breakMinutes=0; private var twoWeekMinutes=0
    private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){findAndAutoConnect()}

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=BG;window.navigationBarColor=BG;live=LiveDidDiagnostic(applicationContext,this);buildUi();loadHistory();requestPermission()}
    override fun onDestroy(){live.disconnect();super.onDestroy()}

    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));setBackgroundColor(BG)}
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val titles=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        titles.addView(TextView(this).apply{text="TachoWatch";textSize=25f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD)})
        status=TextView(this).apply{text="Поиск DTCO…";textSize=11.5f;setTextColor(CYAN)};titles.addView(status)
        top.addView(titles,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));top.addView(smallButton("Диагностика").apply{setOnClickListener{startActivity(Intent(this@DriverDashboardActivity,MainActivity::class.java))}})
        root.addView(top);root.addView(space(7))
        val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        tabs.addView(tabButton("Сейчас").apply{setOnClickListener{showNow()}},LinearLayout.LayoutParams(0,dp(42),1f));tabs.addView(hspace(6));tabs.addView(tabButton("История").apply{setOnClickListener{showHistory()}},LinearLayout.LayoutParams(0,dp(42),1f))
        root.addView(tabs);root.addView(space(7))
        val viewport=FrameLayout(this);nowRoot=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};historyRoot=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE};viewport.addView(nowRoot);viewport.addView(historyRoot)
        root.addView(viewport,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));setContentView(root);buildNow();buildHistoryView()
    }

    private fun buildNow(){
        val scroll=ScrollView(this);val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        driver=TextView(this).apply{text="Пылик Василий";textSize=25f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD);setPadding(dp(3),dp(4),0,dp(8))};c.addView(driver)
        val a=progressCard();activityFrame=a.first;activityProgress=a.second;activityTitle=label("ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ");a.third.addView(activityTitle);activityTime=value("—",34f);a.third.addView(activityTime);activitySub=sub("ожидание онлайн-данных");a.third.addView(activitySub);c.addView(activityFrame);c.addView(space(7))
        val d=progressCard();continuousFrame=d.first;continuousProgress=d.second;d.third.addView(label("НЕПРЕРЫВНОЕ ВОЖДЕНИЕ"));continuous=value("—",30f);d.third.addView(continuous);d.third.addView(sub("лимит 4:30"));c.addView(continuousFrame);c.addView(space(7))
        val w=progressCard();weekFrame=w.first;weekProgress=w.second;w.third.addView(label("ТЕКУЩАЯ НЕДЕЛЯ"));week=value("—",28f);w.third.addView(week);weekSub=sub("по карте водителя");w.third.addView(weekSub);c.addView(weekFrame);c.addView(space(7))
        val tw=progressCard();twoWeekFrame=tw.first;twoWeekProgress=tw.second;tw.third.addView(label("ДВЕ НЕДЕЛИ"));twoWeek=value("—",28f);tw.third.addView(twoWeek);tw.third.addView(sub("лимит 90:00"));c.addView(twoWeekFrame)
        scroll.addView(c);nowRoot.addView(scroll,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun buildHistoryView(){
        historyRoot.removeAllViews();val scroll=ScrollView(this);val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val days=history?.days?.takeLast(14).orEmpty()
        if(days.isEmpty())c.addView(value("История появится после первого считанного DDD",17f))
        days.forEachIndexed{i,day->
            val box=card();box.addView(TextView(this).apply{text=prettyDate(day.date);textSize=18f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD)});box.addView(sub("${flag(day.startCountry)} ${day.startCountry?:"—"}  Начало смены  ${day.startTime?:"—"}"));box.addView(value("◉ Вождение  ${HistoryData.fmt(day.drivingMinutes)}",18f));box.addView(sub("⚒ Другая работа  ${HistoryData.fmt(day.workMinutes)}"));box.addView(sub("Продолжительность смены  ${day.shiftMinutes?.let(HistoryData::fmt)?:"—"}"));box.addView(sub("${flag(day.endCountry)} ${day.endCountry?:"—"}  Конец смены  ${day.endTime?:"—"}"));c.addView(box)
            if(i<days.lastIndex){HistoryData.gapMinutes(day,days[i+1])?.let{gap->c.addView(TextView(this).apply{text=if(gap>=24*60)"🛏 Недельный отдых  ${HistoryData.fmt(gap)}" else "🛏 Межсуточный отдых  ${HistoryData.fmt(gap)}";textSize=14f;gravity=Gravity.CENTER;setTextColor(if(gap>=24*60)CYAN else MUTED);setPadding(0,dp(7),0,dp(7))})}}
        }
        scroll.addView(c);historyRoot.addView(scroll,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun loadHistory(){val f=TlvInventory.findLatestDdd(getExternalFilesDir(null))?:return;val r=TlvInventory.parse(f);if(r.error==null){history=HistoryData.load(r);if(::historyRoot.isInitialized)buildHistoryView();updateWeekCards()}}
    private fun showNow(){nowRoot.visibility=View.VISIBLE;historyRoot.visibility=View.GONE}
    private fun showHistory(){loadHistory();nowRoot.visibility=View.GONE;historyRoot.visibility=View.VISIBLE}

    private fun requestPermission(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))else findAndAutoConnect()}
    @SuppressLint("MissingPermission") private fun findAndAutoConnect(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;dtco=try{adapter?.bondedDevices?.firstOrNull{(it.name?:"").contains("DTCO",true)}}catch(_:Throwable){null};val d=dtco;if(d==null){status.text="DTCO не найден";return};status.text="Подключение к DTCO…";live.connect(d)}

    override fun onLiveConnection(connected:Boolean,deviceName:String?){runOnUiThread{status.text=if(connected)"Онлайн • ${deviceName?:"DTCO"}" else "Связь с DTCO потеряна"}}
    override fun onLiveLog(log:String){runOnUiThread{last(log,"F931")?.let{if(it.isNotBlank())driver.text=it};last(log,"F903")?.let{currentActivity=it};mins(last(log,"F927"))?.let{activityMinutes=it};mins(last(log,"F923"))?.let{continuousMinutes=it};mins(last(log,"F925"))?.let{breakMinutes=it};mins(last(log,"F938"))?.let{twoWeekMinutes=it};updateNow();if(log.contains("LIVE COMPLETE"))status.text="Онлайн • данные актуальны"}}

    private fun updateNow(){
        val dailyRest=isDailyRestNow()
        val icon=when{currentActivity.contains("ВОЖДЕНИЕ")->"◉";currentActivity.contains("РАБОТА")->"⚒";currentActivity.contains("ГОТОВНОСТЬ")->"▣";currentActivity.contains("ОТДЫХ")->"🛏";else->"•"}
        if(dailyRest){
            activityTitle.text="🛏  СУТОЧНЫЙ ОТДЫХ";activityTime.text=HistoryData.fmt(activityMinutes);activitySub.text="минимум 9:00 • полный 11:00"
            setProgress(activityFrame,activityProgress,activityMinutes/(11f*60f),dailyRestColor(activityMinutes))
        }else{
            activityTitle.text="$icon  ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ • $currentActivity";activityTime.text=HistoryData.fmt(activityMinutes)
            val p:Float;val color:Int;val text:String
            when{currentActivity.contains("ВОЖДЕНИЕ")->{p=continuousMinutes/270f;color=driveColor(continuousMinutes);text="непрерывно ${HistoryData.fmt(continuousMinutes)} из 4:30"};currentActivity.contains("ОТДЫХ")->{p=breakMinutes/45f;color=breakColor(breakMinutes);text="накопленная пауза ${HistoryData.fmt(breakMinutes)} из 0:45"};else->{p=(activityMinutes%60)/60f;color=GREEN;text="текущая деятельность ${HistoryData.fmt(activityMinutes)}"}}
            activitySub.text=text;setProgress(activityFrame,activityProgress,p,color)
        }
        continuous.text="${HistoryData.fmt(continuousMinutes)} из 4:30";setProgress(continuousFrame,continuousProgress,continuousMinutes/270f,driveColor(continuousMinutes));twoWeek.text="${HistoryData.fmt(twoWeekMinutes)} из 90:00";setProgress(twoWeekFrame,twoWeekProgress,twoWeekMinutes/(90f*60f),limitColor(twoWeekMinutes,90*60));updateWeekCards()
    }

    private fun updateWeekCards(){if(!::week.isInitialized)return;val current=history?.currentWeekCardMinutes?:0;val prev=history?.previousWeekDrivingMinutes?:0;val limit=minOf(56*60,(90*60-prev).coerceAtLeast(0));week.text="${HistoryData.fmt(current)} из ${HistoryData.fmt(limit)}";weekSub.text="прошлая неделя ${HistoryData.fmt(prev)} • данные недели с карты";setProgress(weekFrame,weekProgress,if(limit>0)current.toFloat()/limit else 1f,limitColor(current,limit))}

    private fun isDailyRestNow():Boolean{if(!currentActivity.contains("ОТДЫХ"))return false;val last=history?.days?.lastOrNull{it.endTime!=null}?:return false;val end=parseUtc("${last.date} ${last.endTime}")?:return false;val elapsed=(System.currentTimeMillis()-end.time)/60000L;return elapsed in 0..900}
    private fun parseUtc(v:String):Date?=runCatching{SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.parse(v)}.getOrNull()
    private fun last(log:String,did:String)=log.lines().asReversed().firstOrNull{it.startsWith("$did=")}?.substringAfter(" | ")?.trim()
    private fun mins(v:String?):Int?=v?.let{Regex("^(\\d+) мин").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()}
    private fun driveColor(m:Int)=when{m>=255->RED;m>=240->YELLOW;else->GREEN};private fun breakColor(m:Int)=when{m>=45->GREEN;m>=15->YELLOW;else->RED};private fun dailyRestColor(m:Int)=when{m>=660->GREEN;m>=540->YELLOW;else->RED};private fun limitColor(v:Int,limit:Int)=when{limit<=0||v>=limit-120->RED;v>=limit-360->YELLOW;else->GREEN}

    private fun progressCard():Triple<FrameLayout,View,LinearLayout>{val f=FrameLayout(this).apply{background=rounded(CARD,dp(15).toFloat(),BORDER)};val p=View(this).apply{background=rounded(GREEN,dp(15).toFloat())};f.addView(p,FrameLayout.LayoutParams(0,FrameLayout.LayoutParams.MATCH_PARENT));val b=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(11),dp(12),dp(11))};f.addView(b,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.WRAP_CONTENT));return Triple(f,p,b)}
    private fun setProgress(frame:FrameLayout,bar:View,p:Float,color:Int){frame.post{val w=(frame.width*p.coerceIn(0f,1f)).toInt();val lp=bar.layoutParams as FrameLayout.LayoutParams;if(lp.width!=w){lp.width=w;bar.layoutParams=lp};bar.background=rounded(color,dp(15).toFloat())}}
    private fun card()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(CARD,dp(14).toFloat(),BORDER)}
    private fun label(t:String)=TextView(this).apply{text=t;textSize=11.5f;setTextColor(MUTED);setTypeface(typeface,Typeface.BOLD)};private fun value(t:String,s:Float)=TextView(this).apply{text=t;textSize=s;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(2),0,0)};private fun sub(t:String)=TextView(this).apply{text=t;textSize=13f;setTextColor(MUTED);setPadding(0,dp(2),0,0)}
    private fun tabButton(t:String)=Button(this).apply{text=t;isAllCaps=false;textSize=14f;setTextColor(TEXT);background=rounded(CARD,dp(11).toFloat(),BORDER)};private fun smallButton(t:String)=tabButton(t).apply{textSize=11f;minWidth=0;minimumWidth=0;setPadding(dp(9),0,dp(9),0)}
    private fun rounded(c:Int,r:Float,stroke:Int?=null)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;cornerRadius=r;setColor(c);if(stroke!=null)setStroke(dp(1),stroke)};private fun space(h:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(h))};private fun hspace(w:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(dp(w),1)};private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun prettyDate(d:String)=runCatching{val p=d.split('-');"${p[2]}.${p[1]}.${p[0]}"}.getOrDefault(d)
    private fun flag(c:String?)=when(c){"B"->"🇧🇪";"F"->"🇫🇷";"D"->"🇩🇪";"NL"->"🇳🇱";"L"->"🇱🇺";"E"->"🇪🇸";"LT"->"🇱🇹";"LV"->"🇱🇻";else->"🌐"}
}
