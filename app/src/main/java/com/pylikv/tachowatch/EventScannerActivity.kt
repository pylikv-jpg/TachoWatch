package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
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

    private data class DidViews(val card: LinearLayout, val raw: TextView, val decoded: TextView, val meta: TextView)

    private val btManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var liveContainer: LinearLayout
    private lateinit var outerScroll: ScrollView
    private var selected: BluetoothDevice? = null
    private var userTouching = false
    private var followLog = false
    private var lastRenderedLog = ""
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
        loadDevices()
    }

    private val saveReport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = try {
            contentResolver.openOutputStream(uri, "w")?.use { out -> TargetMonitorService.exportCurrentLog(out) } ?: false
        } catch (_: Throwable) { false }
        Toast.makeText(this, if (ok) "Отчёт сохранён" else "Не удалось сохранить отчёт", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        TargetMonitorService.registerListener(this)
    }

    override fun onStop() {
        TargetMonitorService.unregisterListener(this)
        super.onStop()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Float = dp(14).toFloat()) = GradientDrawable().apply { setColor(color); cornerRadius = r }
    private fun button(t: String, color: Int) = Button(this).apply { text = t; setTextColor(TEXT); background = rounded(color); isAllCaps = false }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        outerScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> userTouching = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> userTouching = false
                }
                false
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(18))
            setBackgroundColor(BG)
        }
        outerScroll.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "DTCO Live DID Monitor v9.5"
            textSize = 22f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "ФОНОВЫЙ РЕЖИМ • постоянная запись • автопереподключение • защищённый журнал"
            textSize = 12f
            setTextColor(GREEN)
        })
        status = TextView(this).apply {
            text = if (TargetMonitorService.isRunning()) "Мониторинг работает в фоне" else "Выбери DTCO"
            textSize = 14f
            setTextColor(if (TargetMonitorService.isRunning()) GREEN else ORANGE)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)

        val devCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(CARD)
        }
        devCard.addView(TextView(this).apply { text = "СОПРЯЖЁННЫЕ УСТРОЙСТВА"; textSize = 12f; setTextColor(MUTED) })
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devCard.addView(devices)
        root.addView(devCard)

        root.addView(button("Подключить и начать фоновый мониторинг", BLUE).apply {
            setOnClickListener {
                val d = selected ?: return@setOnClickListener
                resetCards()
                status.text = "Запуск фонового мониторинга…"
                TargetMonitorService.start(this@EventScannerActivity, d.address)
                Toast.makeText(this@EventScannerActivity, "Мониторинг продолжит работать после сворачивания приложения", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })

        root.addView(button("Остановить мониторинг", RED).apply {
            setOnClickListener {
                TargetMonitorService.stop(this@EventScannerActivity)
                status.text = "Мониторинг остановлен"
                status.setTextColor(RED)
            }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Метка события", ORANGE).apply { setOnClickListener { showMarkerDialog() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Space(this), LinearLayout.LayoutParams(dp(6), 1))
        actions.addView(button("Статус", GREEN).apply { setOnClickListener { TargetMonitorService.manualGattCheck() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })

        root.addView(button("Сохранить отчёт", ORANGE).apply {
            setOnClickListener { saveReport.launch(TargetMonitorService.getCurrentLogFileName() ?: "DTCO_LIVE_DID_v9_5.txt") }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })

        root.addView(TextView(this).apply {
            text = "ЖИВЫЕ КАНАЛЫ"
            textSize = 13f
            setTextColor(MUTED)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        })
        liveContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(liveContainer)
        displayDids.forEach { (id, title) -> addDidCard(id, title) }

        root.addView(TextView(this).apply {
            text = "ЖУРНАЛ • на экране последние 500 строк, полный журнал сохраняется в файл"
            textSize = 13f
            setTextColor(MUTED)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        })
        val logButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logButtons.addView(button("Очистить экран", CARD).apply { setOnClickListener { TargetMonitorService.clearVisibleLog() } }, LinearLayout.LayoutParams(0, dp(44), 1f))
        logButtons.addView(Space(this), LinearLayout.LayoutParams(dp(6), 1))
        logButtons.addView(button("Вниз / LIVE", GREEN).apply {
            setOnClickListener {
                followLog = true
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(logButtons)

        logScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) followLog = false
                false
            }
        }
        logText = TextView(this).apply {
            text = "После запуска здесь появится технический журнал. Можно свернуть приложение — мониторинг останется активным."
            textSize = 11f
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(12))
            background = rounded(CARD)
        }
        logScroll.addView(logText)
        root.addView(logScroll, LinearLayout.LayoutParams(-1, dp(340)).apply { topMargin = dp(8) })
        setContentView(outerScroll)
    }

    private fun addDidCard(id: Int, title: String) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(CARD) }
        val header = TextView(this).apply { text = "${did(id)}  •  $title"; textSize = 16f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD) }
        val raw = TextView(this).apply { text = "RAW: —"; textSize = 15f; setTextColor(ORANGE); typeface = Typeface.MONOSPACE; setPadding(0, dp(5), 0, dp(2)) }
        val decoded = TextView(this).apply { text = "Расшифровка: ожидаем данные"; textSize = 14f; setTextColor(TEXT) }
        val meta = TextView(this).apply { text = "Последнее чтение: —"; textSize = 12f; setTextColor(MUTED); setPadding(0, dp(4), 0, 0) }
        card.addView(header); card.addView(raw); card.addView(decoded); card.addView(meta)
        liveContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        didViews[id] = DidViews(card, raw, decoded, meta)
    }

    private fun resetCards() {
        didViews.values.forEach { v ->
            v.card.background = rounded(CARD)
            v.raw.text = "RAW: —"
            v.decoded.text = "Расшифровка: ожидаем данные"
            v.meta.text = "Последнее чтение: —"
        }
    }

    private fun showMarkerDialog() {
        val input = EditText(this).apply {
            hint = "Например: открыл смену / молотки / начал движение"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
        }
        AlertDialog.Builder(this)
            .setTitle("Метка события")
            .setMessage("Метка попадёт в лог с точным временем.")
            .setView(input)
            .setPositiveButton("Записать") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) {
                    TargetMonitorService.addMarker(text)
                    Toast.makeText(this, "Метка записана", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun requestPermissionsIfNeeded() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.BLUETOOTH_CONNECT
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isEmpty()) loadDevices() else perms.launch(need.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        devices.removeAllViews()
        selected = null
        val list = btManager.adapter?.bondedDevices?.sortedBy { it.name ?: it.address }.orEmpty()
        if (list.isEmpty()) {
            devices.addView(TextView(this).apply { text = "Сопряжённых устройств нет"; setTextColor(MUTED) })
            return
        }
        list.forEach { d ->
            devices.addView(button("${d.name ?: "Без имени"}  ${d.address}", CARD).apply {
                setOnClickListener {
                    selected = d
                    status.text = "Выбрано: ${d.name ?: d.address}"
                    loadDevicesSelected(d)
                }
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(4) })
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadDevicesSelected(sel: BluetoothDevice) {
        devices.removeAllViews()
        btManager.adapter?.bondedDevices?.sortedBy { it.name ?: it.address }.orEmpty().forEach { d ->
            val label = (if (d.address == sel.address) "✓ " else "") + "${d.name ?: "Без имени"}  ${d.address}"
            devices.addView(button(label, if (d.address == sel.address) GREEN else CARD).apply {
                setOnClickListener {
                    selected = d
                    status.text = "Выбрано: ${d.name ?: d.address}"
                    loadDevicesSelected(d)
                }
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(4) })
        }
    }

    override fun onLogChanged(fullLog: String) {
        if (fullLog == lastRenderedLog) return
        runOnUiThread {
            if (fullLog == lastRenderedLog) return@runOnUiThread
            val outerY = outerScroll.scrollY
            val innerY = logScroll.scrollY
            lastRenderedLog = fullLog
            logText.text = fullLog
            if (!userTouching) outerScroll.post { outerScroll.scrollTo(0, outerY) }
            if (followLog) logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            else logScroll.post { logScroll.scrollTo(0, innerY) }
        }
    }

    override fun onDidUpdate(did: Int, rawHex: String, decoded: String, changed: Boolean, byteDiff: String, timestamp: String) {
        runOnUiThread {
            val v = didViews[did] ?: return@runOnUiThread
            v.raw.text = "RAW: $rawHex"
            v.decoded.text = "Расшифровка: $decoded"
            v.meta.text = if (changed) "ИЗМЕНЕНО $timestamp • $byteDiff" else "Последнее чтение: $timestamp • без изменений"
            v.meta.setTextColor(if (changed) ORANGE else MUTED)
            v.card.background = rounded(if (changed) CARD_CHANGED else CARD)
        }
    }

    override fun onConnectionStateChanged(connected: Boolean, deviceName: String?) {
        runOnUiThread {
            status.setTextColor(if (connected) GREEN else if (TargetMonitorService.isRunning()) ORANGE else RED)
            status.text = when {
                connected -> "Подключено: ${deviceName ?: "DTCO"} • работает в фоне"
                TargetMonitorService.isRunning() -> "Мониторинг активен • переподключение к DTCO…"
                else -> "Отключено"
            }
        }
    }

    private fun did(i: Int) = "%04X".format(Locale.US, i and 0xFFFF)
}
