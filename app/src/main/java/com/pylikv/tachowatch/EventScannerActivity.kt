package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class EventScannerActivity : AppCompatActivity(), DtcoTargetEventMonitor.Listener {
    companion object {
        private const val BG = 0xFF0B1118.toInt()
        private const val CARD = 0xFF141D27.toInt()
        private const val TEXT = 0xFFF3F7FA.toInt()
        private const val MUTED = 0xFF9BAAB8.toInt()
        private const val BLUE = 0xFF2196F3.toInt()
        private const val GREEN = 0xFF42C77A.toInt()
        private const val ORANGE = 0xFFFFB547.toInt()
        private const val RED = 0xFFE85D5D.toInt()
    }

    private val btManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private lateinit var monitor: DtcoTargetEventMonitor
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private var selected: BluetoothDevice? = null

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { ok -> ok }) loadDevices() else status.text = "Нет разрешения Bluetooth"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG; window.navigationBarColor = BG
        monitor = DtcoTargetEventMonitor(applicationContext, this)
        buildUi(); requestPermissionsIfNeeded()
    }

    override fun onDestroy() { monitor.disconnect(); super.onDestroy() }

    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun rounded(color:Int,r:Float=dp(14).toFloat())=GradientDrawable().apply{setColor(color);cornerRadius=r}
    private fun button(t:String,color:Int)=Button(this).apply{text=t;setTextColor(TEXT);background=rounded(color);isAllCaps=false}

    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));setBackgroundColor(BG)}
        root.addView(TextView(this).apply{text="DTCO Target Event Scanner v9";textSize=22f;setTextColor(TEXT);setTypeface(typeface,Typeface.BOLD)})
        root.addView(TextView(this).apply{text="READ ONLY • F930 / F979 / F9D5 / F90B + controls";textSize=12f;setTextColor(GREEN)})
        status=TextView(this).apply{text="Выбери DTCO";textSize=14f;setTextColor(ORANGE);setPadding(0,dp(8),0,dp(8))}
        root.addView(status)

        val devCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8));background=rounded(CARD)}
        devCard.addView(TextView(this).apply{text="СОПРЯЖЁННЫЕ УСТРОЙСТВА";textSize=12f;setTextColor(MUTED)})
        devices=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        devCard.addView(devices)
        root.addView(devCard)

        val connect=button("Подключить и начать мониторинг",BLUE).apply{setOnClickListener{ val d=selected?:return@setOnClickListener; logText.text=""; status.text="Подключение..."; monitor.connect(d)}}
        root.addView(connect,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(52)).apply{topMargin=dp(8)})

        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        row.addView(button("Статус",GREEN).apply{setOnClickListener{monitor.manualGattCheck()}},LinearLayout.LayoutParams(0,dp(48),1f))
        row.addView(Space(this),LinearLayout.LayoutParams(dp(6),1))
        row.addView(button("Очистить",CARD).apply{setOnClickListener{monitor.clearLog()}},LinearLayout.LayoutParams(0,dp(48),1f))
        root.addView(row,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(48)).apply{topMargin=dp(6)})

        scroll=ScrollView(this).apply{isFillViewport=false;isVerticalScrollBarEnabled=true}
        logText=TextView(this).apply{text="После подключения каждые 15 секунд выполняется целевой цикл. Ищи строки CHANGED.";textSize=11.5f;setTextColor(TEXT);typeface=Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(dp(8),dp(8),dp(8),dp(12));background=rounded(CARD)}
        scroll.addView(logText)
        root.addView(scroll,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f).apply{topMargin=dp(8)})
        setContentView(root)
    }

    private fun requestPermissionsIfNeeded(){
        val need=mutableListOf<String>()
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) need+=Manifest.permission.BLUETOOTH_CONNECT
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED) need+=Manifest.permission.BLUETOOTH_SCAN
        }
        if(need.isEmpty()) loadDevices() else perms.launch(need.toTypedArray())
    }

    @SuppressLint("MissingPermission") private fun loadDevices(){
        devices.removeAllViews(); selected=null
        val list=btManager.adapter?.bondedDevices?.sortedBy{it.name?:it.address}.orEmpty()
        if(list.isEmpty()){devices.addView(TextView(this).apply{text="Сопряжённых устройств нет";setTextColor(MUTED)});return}
        list.forEach{d->
            val b=button("${d.name?:"Без имени"}  ${d.address}",CARD).apply{
                setOnClickListener{selected=d;status.text="Выбрано: ${d.name?:d.address}";loadDevicesSelected(d)}
            }
            devices.addView(b,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(46)).apply{topMargin=dp(4)})
        }
    }

    @SuppressLint("MissingPermission") private fun loadDevicesSelected(sel:BluetoothDevice){
        devices.removeAllViews()
        btManager.adapter?.bondedDevices?.sortedBy{it.name?:it.address}.orEmpty().forEach{d->
            val label=(if(d.address==sel.address) "✓ " else "")+"${d.name?:"Без имени"}  ${d.address}"
            val b=button(label,if(d.address==sel.address) GREEN else CARD).apply{setOnClickListener{selected=d;status.text="Выбрано: ${d.name?:d.address}";loadDevicesSelected(d)}}
            devices.addView(b,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(46)).apply{topMargin=dp(4)})
        }
    }

    override fun onLogChanged(fullLog:String){runOnUiThread{
        val atBottom = scroll.scrollY + scroll.height >= logText.height - dp(80)
        logText.text=fullLog
        if(atBottom) scroll.post{scroll.fullScroll(ScrollView.FOCUS_DOWN)}
    }}
    override fun onConnectionStateChanged(connected:Boolean,deviceName:String?){runOnUiThread{status.setTextColor(if(connected)GREEN else RED);status.text=if(connected)"Подключено: ${deviceName?:"DTCO"}" else "Отключено"}}
}
