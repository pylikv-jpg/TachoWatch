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
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class EventScannerActivity : AppCompatActivity(), DtcoTargetEventMonitor.Listener {
    companion object {
        private const val BG = 0xFF0B1118.toInt()
        private const val CARD = 0xFF141D27.toInt()
        private const val CARD_CHANGED = 0xFF28341D.toInt()
        private const val TEXT = 0xFFF3F7FA.toInt()
        private const val MUTED = 0xFF9BAAB8.toInt()
        private const val BLUE = 0xFF2196F3.toInt()
        private const val GREEN = 0xFF42C77A.toInt()
        private const val ORANGE = 0xFFFFB547.toInt()
        private const val RED = 0xFFE85D5D.toInt()
    }

    private data class DidViews(
        val card: LinearLayout,
        val raw: TextView,
        val decoded: TextView,
        val meta: TextView
    )

    private val btManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private lateinit var monitor: DtcoTargetEventMonitor
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var liveContainer: LinearLayout
    private var selected: BluetoothDevice? = null
    private val didViews = linkedMapOf<Int, DidViews>()

    private val displayDids = listOf(
        0xF90B to "Динамический структурированный канал",
        0xF925 to "Отдых / пауза",
        0xF927 to "Длительность выбранной деятельности",
        0xF923 to "Непрерывное вождение",
        0xF938 to "Вождение за 2 недели",
        0xF930 to "Неизвестный кандидат A",
        0xF979 to "Неизвестный кандидат B",
        0xF9D5 to "Неизвестный кандидат C",
        0xF907 to "Карта в слоте 1"
    )

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { ok -> ok }) loadDevices() else status.text = "Нет разрешения Bluetooth"
    }

    private val saveReport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = try {
            contentResolver.openOutputStream(uri, "w")?.use { out -> monitor.exportCurrentLog(out) } ?: false
        } catch (_: Throwable) { false }
        Toast.makeText(this, if (ok) "Отчёт сохранён" else "Не удалось сохранить отчёт", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        monitor = DtcoTargetEventMonitor(applicationContext, this)
        buildUi()
        requestPermissionsIfNeeded()
    }

    override fun onDestroy() {
        monitor.disconnect()
        super.onDestroy()
    }

    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun rounded(color:Int,r:Float=dp(14).toFloat())=GradientDrawable().apply{setColor(color);cornerRadius=r}
    private fun button(t:String,color:Int)=Button(this).apply{text=t;setTextColor(TEXT);background=rounded(color);isAllCaps=false}

    private fun buildUi(){
        val outer = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
        }
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(10),dp(12),dp(18))
            setBackgroundColor(BG)
        }
        outer.addView(root, ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply{
            text="DTCO Live DID Monitor v9.2"
            textSize=22f
            setTextColor(TEXT)
            setTypeface(typeface,Typeface.BOLD)
        })
        root.addView(TextView(this).apply{
            text="READ ONLY • живые значения каналов • автосохранение"
            textSize=12f
            setTextColor(GREEN)
        })
        status=TextView(this).apply{
            text="Выбери DTCO"
            textSize=14f
            setTextColor(ORANGE)
            setPadding(0,dp(8),0,dp(8))
        }
        root.addView(status)

        val devCard=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(10),dp(8),dp(10),dp(8))
            background=rounded(CARD)
        }
        devCard.addView(TextView(this).apply{text="СОПРЯЖЁННЫЕ УСТРОЙСТВА";textSize=12f;setTextColor(MUTED)})
        devices=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        devCard.addView(devices)
        root.addView(devCard)

        val connect=button("Подключить и начать мониторинг",BLUE).apply{setOnClickListener{
            val d=selected?:return@setOnClickListener
            logText.text=""
            status.text="Подключение..."
            resetCards()
            monitor.connect(d)
            Toast.makeText(this@EventScannerActivity,"Автосохранение включено: ${monitor.getCurrentLogFileName() ?: "внутренний файл"}",Toast.LENGTH_LONG).show()
        }}
        root.addView(connect,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(52)).apply{topMargin=dp(8)})

        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        actions.addView(button("Метка события",ORANGE).apply{setOnClickListener{showMarkerDialog()}},LinearLayout.LayoutParams(0,dp(48),1f))
        actions.addView(Space(this),LinearLayout.LayoutParams(dp(6),1))
        actions.addView(button("Статус",GREEN).apply{setOnClickListener{monitor.manualGattCheck()}},LinearLayout.LayoutParams(0,dp(48),1f))
        root.addView(actions,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(48)).apply{topMargin=dp(6)})

        val export=button("Сохранить отчёт",ORANGE).apply{setOnClickListener{
            val name=monitor.getCurrentLogFileName() ?: "DTCO_LIVE_DID_v9_2.txt"
            saveReport.launch(name)
        }}
        root.addView(export,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(48)).apply{topMargin=dp(6)})

        root.addView(TextView(this).apply{
            text="ЖИВЫЕ КАНАЛЫ"
            textSize=13f
            setTextColor(MUTED)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0,dp(14),0,dp(6))
        })

        liveContainer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(liveContainer)
        displayDids.forEach { (id, title) -> addDidCard(id, title) }

        root.addView(TextView(this).apply{
            text="ЖУРНАЛ"
            textSize=13f
            setTextColor(MUTED)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0,dp(14),0,dp(6))
        })

        val logButtons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        logButtons.addView(button("Очистить экран",CARD).apply{setOnClickListener{monitor.clearLog()}},LinearLayout.LayoutParams(0,dp(44),1f))
        root.addView(logButtons)

        logScroll=ScrollView(this).apply{
            isFillViewport=false
            isVerticalScrollBarEnabled=true
        }
        logText=TextView(this).apply{
            text="После подключения здесь появится технический журнал. Основные значения каналов смотри в карточках выше."
            textSize=11f
            setTextColor(TEXT)
            typeface=Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8),dp(8),dp(8),dp(12))
            background=rounded(CARD)
        }
        logScroll.addView(logText)
        root.addView(logScroll,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(340)).apply{topMargin=dp(8)})

        setContentView(outer)
    }

    private fun addDidCard(id:Int, title:String){
        val card=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(10),dp(12),dp(10))
            background=rounded(CARD)
        }
        val header=TextView(this).apply{
            text="${did(id)}  •  $title"
            textSize=16f
            setTextColor(TEXT)
            setTypeface(typeface,Typeface.BOLD)
        }
        val raw=TextView(this).apply{
            text="RAW: —"
            textSize=15f
            setTextColor(ORANGE)
            typeface=Typeface.MONOSPACE
            setPadding(0,dp(5),0,dp(2))
        }
        val decoded=TextView(this).apply{
            text="Расшифровка: ожидаем данные"
            textSize=14f
            setTextColor(TEXT)
        }
        val meta=TextView(this).apply{
            text="Последнее чтение: —"
            textSize=12f
            setTextColor(MUTED)
            setPadding(0,dp(4),0,0)
        }
        card.addView(header)
        card.addView(raw)
        card.addView(decoded)
        card.addView(meta)
        liveContainer.addView(card,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(7)})
        didViews[id]=DidViews(card,raw,decoded,meta)
    }

    private fun resetCards(){
        didViews.values.forEach { v ->
            v.card.background=rounded(CARD)
            v.raw.text="RAW: —"
            v.decoded.text="Расшифровка: ожидаем данные"
            v.meta.text="Последнее чтение: —"
        }
    }

    private fun showMarkerDialog(){
        val input=EditText(this).apply{
            hint="Например: открыл смену / молотки / начал движение"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
        }
        AlertDialog.Builder(this)
            .setTitle("Метка события")
            .setMessage("Метка попадёт в лог с точным временем.")
            .setView(input)
            .setPositiveButton("Записать") { _, _ ->
                val text=input.text?.toString()?.trim().orEmpty()
                if(text.isNotBlank()){
                    monitor.addMarker(text)
                    Toast.makeText(this,"Метка записана",Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена",null)
            .show()
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
            val b=button(label,if(d.address==sel.address) GREEN else CARD).apply{
                setOnClickListener{selected=d;status.text="Выбрано: ${d.name?:d.address}";loadDevicesSelected(d)}
            }
            devices.addView(b,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(46)).apply{topMargin=dp(4)})
        }
    }

    override fun onLogChanged(fullLog:String){
        runOnUiThread{
            val atBottom = logScroll.scrollY + logScroll.height >= logText.height - dp(80)
            logText.text=fullLog
            if(atBottom) logScroll.post{logScroll.fullScroll(ScrollView.FOCUS_DOWN)}
        }
    }

    override fun onDidUpdate(did:Int, rawHex:String, decoded:String, changed:Boolean, byteDiff:String, timestamp:String){
        runOnUiThread {
            val v=didViews[did] ?: return@runOnUiThread
            v.raw.text="RAW: $rawHex"
            v.decoded.text="Расшифровка: $decoded"
            v.meta.text=if(changed) "ИЗМЕНЕНО $timestamp • $byteDiff" else "Последнее чтение: $timestamp • без изменений"
            v.meta.setTextColor(if(changed) ORANGE else MUTED)
            v.card.background=rounded(if(changed) CARD_CHANGED else CARD)
        }
    }

    override fun onConnectionStateChanged(connected:Boolean,deviceName:String?){
        runOnUiThread{
            status.setTextColor(if(connected)GREEN else RED)
            status.text=if(connected)"Подключено: ${deviceName?:"DTCO"}" else "Отключено"
        }
    }

    private fun did(i:Int)="%04X".format(Locale.US,i and 0xFFFF)
}
