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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class DashboardActivity : AppCompatActivity(), LiveDidDiagnostic.Listener {
    companion object {
        private const val BG = 0xFF0B1118.toInt()
        private const val CARD = 0xFF141D27.toInt()
        private const val TEXT = 0xFFF3F7FA.toInt()
        private const val MUTED = 0xFF9BAAB8.toInt()
        private const val BLUE = 0xFF2196F3.toInt()
        private const val CYAN = 0xFF29B6C8.toInt()
        private const val GREEN = 0xFF42C77A.toInt()
        private const val ORANGE = 0xFFFFB547.toInt()
        private const val BORDER = 0xFF263545.toInt()
    }

    private val btManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val adapter by lazy { btManager.adapter }
    private lateinit var live: LiveDidDiagnostic
    private var dtco: BluetoothDevice? = null

    private lateinit var status: TextView
    private lateinit var onlineActivity: TextView
    private lateinit var onlineDuration: TextView
    private lateinit var onlineContinuous: TextView
    private lateinit var onlineBreak: TextView
    private lateinit var onlineTwoWeek: TextView
    private lateinit var onlineDriver: TextView

    private lateinit var cardFile: TextView
    private lateinit var cardVehicle: TextView
    private lateinit var cardSession: TextView
    private lateinit var cardDay: TextView
    private lateinit var cardLastActivity: TextView
    private lateinit var cardTotals: TextView
    private lateinit var cardFirstPlace: TextView
    private lateinit var cardLastPlace: TextView

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { findDtco() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        live = LiveDidDiagnostic(applicationContext, this)
        buildUi()
        loadCard()
        requestPermission()
    }

    override fun onDestroy() {
        live.disconnect()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(BG)
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply { text = "TachoWatch"; textSize = 25f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD) })
        titles.addView(TextView(this).apply { text = "Тахограф онлайн + карта водителя"; textSize = 12f; setTextColor(CYAN) })
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("Диагностика").apply { setOnClickListener { startActivity(Intent(this@DashboardActivity, MainActivity::class.java)) } })
        root.addView(header)
        root.addView(space(8))

        val actions = card()
        status = value("Bluetooth: поиск DTCO…", 14f)
        actions.addView(status)
        actions.addView(space(6))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Обновить онлайн", BLUE).apply { setOnClickListener { refreshLive() } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(hspace(6))
        row.addView(button("Обновить карту", GREEN).apply { setOnClickListener { loadCard() } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(row)
        actions.addView(space(6))
        actions.addView(button("Считать свежую карту из тахографа", CARD).apply { setOnClickListener { startActivity(Intent(this@DashboardActivity, MainActivity::class.java)) } })
        root.addView(actions)
        root.addView(space(8))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(section("ОНЛАЙН ИЗ ТАХОГРАФА", CYAN))
        onlineDriver = addMetric(content, "Водитель", "—")
        onlineActivity = addMetric(content, "Текущая деятельность", "—")
        onlineDuration = addMetric(content, "Длительность текущей деятельности", "—")
        onlineContinuous = addMetric(content, "Непрерывное вождение", "—")
        onlineBreak = addMetric(content, "Накопленная пауза", "—")
        onlineTwoWeek = addMetric(content, "Вождение за текущую + предыдущую неделю", "—")

        content.addView(space(10))
        content.addView(section("С КАРТЫ ВОДИТЕЛЯ", GREEN))
        cardFile = addMetric(content, "DDD-файл", "—")
        cardVehicle = addMetric(content, "Текущий автомобиль", "—")
        cardSession = addMetric(content, "Сессия карты открыта", "—")
        cardDay = addMetric(content, "Последний день на карте", "—")
        cardLastActivity = addMetric(content, "Последняя деятельность на карте", "—")
        cardTotals = addMetric(content, "Итоги последнего дня", "—")
        cardFirstPlace = addMetric(content, "Первая запись страны за последний день", "—")
        cardLastPlace = addMetric(content, "Последняя запись страны за последний день", "—")

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun section(title: String, accent: Int) = TextView(this).apply {
        text = title; textSize = 13f; setTextColor(accent); setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), dp(4), 0, dp(5))
    }

    private fun addMetric(parent: LinearLayout, title: String, initial: String): TextView {
        val c = card()
        c.addView(TextView(this).apply { text = title; textSize = 11.5f; setTextColor(MUTED); setTypeface(typeface, Typeface.BOLD) })
        val v = value(initial, 20f)
        c.addView(v)
        parent.addView(c)
        parent.addView(space(6))
        return v
    }

    private fun loadCard() {
        val file = TlvInventory.findLatestDdd(getExternalFilesDir(null))
        if (file == null) {
            if (::cardFile.isInitialized) cardFile.text = "DDD ещё не считан"
            return
        }
        val result = TlvInventory.parse(file)
        if (result.error != null) {
            Toast.makeText(this, "DDD требует проверки", Toast.LENGTH_SHORT).show()
            return
        }
        val s = CardSnapshot.load(result)
        cardFile.text = s.fileName
        cardVehicle.text = "${s.vehicleNumber} • ${s.vehicleNation}"
        cardSession.text = "${s.sessionOpen} UTC"
        cardDay.text = s.currentDay
        cardLastActivity.text = s.lastCardActivity
        cardTotals.text = "Вождение ${s.dayDriving} • Работа ${s.dayWork} • Готовность ${s.dayAvailability} • Отдых ${s.dayRest}"
        cardFirstPlace.text = s.firstPlace
        cardLastPlace.text = s.lastPlace
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else findDtco()
    }

    @SuppressLint("MissingPermission")
    private fun findDtco() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        dtco = try { adapter?.bondedDevices?.firstOrNull { (it.name ?: "").contains("DTCO", true) } } catch (_: Throwable) { null }
        status.text = if (dtco != null) "Bluetooth: DTCO найден" else "Bluetooth: DTCO не найден"
    }

    private fun refreshLive() {
        if (dtco == null) findDtco()
        val d = dtco ?: run { Toast.makeText(this, "DTCO не найден среди сопряжённых устройств", Toast.LENGTH_LONG).show(); return }
        status.text = "Bluetooth: подключение…"
        live.connect(d)
    }

    override fun onLiveConnection(connected: Boolean, deviceName: String?) {
        runOnUiThread { status.text = if (connected) "Bluetooth: подключено к ${deviceName ?: "DTCO"}" else "Bluetooth: отключено" }
    }

    override fun onLiveLog(log: String) {
        runOnUiThread {
            lastDecoded(log, "F903")?.let { onlineActivity.text = it }
            lastDecoded(log, "F923")?.let { onlineContinuous.text = shortMinutes(it) }
            lastDecoded(log, "F925")?.let { onlineBreak.text = shortMinutes(it) }
            lastDecoded(log, "F927")?.let { onlineDuration.text = shortMinutes(it) }
            lastDecoded(log, "F938")?.let { onlineTwoWeek.text = shortMinutes(it) }
            lastDecoded(log, "F931")?.let { onlineDriver.text = it }
            if (log.contains("LIVE COMPLETE")) status.text = "Онлайн-данные обновлены"
        }
    }

    private fun lastDecoded(log: String, did: String): String? {
        return log.lines().asReversed().firstOrNull { it.startsWith("$did=") }?.substringAfter(" | ")?.trim()
    }

    private fun shortMinutes(v: String): String = Regex("=\\s*([0-9]+:[0-9]{2})").find(v)?.groupValues?.getOrNull(1) ?: v

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(CARD, dp(14).toFloat(), BORDER)
    }
    private fun value(textValue: String, size: Float) = TextView(this).apply { text = textValue; textSize = size; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(2), 0, 0) }
    private fun button(textValue: String, color: Int) = Button(this).apply { text = textValue; isAllCaps = false; textSize = 12f; setTextColor(TEXT); background = rounded(color, dp(10).toFloat(), BORDER); minHeight = dp(44) }
    private fun smallButton(textValue: String) = button(textValue, CARD).apply { minHeight = dp(38); textSize = 11f }
    private fun rounded(color: Int, radius: Float, strokeColor: Int? = null) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = radius; setColor(color); if (strokeColor != null) setStroke(dp(1), strokeColor) }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun hspace(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(w), 1) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
