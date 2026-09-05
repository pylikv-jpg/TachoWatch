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
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * New application layer for TachoWatch.
 *
 * DTCO Bluetooth transport/DDD decoding are retained because they are proven protocol
 * adapters. All counter state, shift logic and dashboard rendering are new.
 */
class TachoWatchActivity : AppCompatActivity(), LiveDidDiagnostic.Listener, DtcoBluetoothDiagnostic.Listener {
    companion object {
        private const val BG = 0xFF0B1118.toInt()
        private const val CARD = 0xFF182129.toInt()
        private const val TEXT = 0xFFF6F8FA.toInt()
        private const val MUTED = 0xFFAAB5BF.toInt()
        private const val GREEN = 0xFF85D837.toInt()
        private const val YELLOW = 0xFFFFC928.toInt()
        private const val RED = 0xFFE65050.toInt()
        private const val BORDER = 0xFF3A4650.toInt()
        private const val PREFS = "tachowatch_ui_v3"
        private const val SELECTED_DTCO = "selected_dtco_address"
        private const val FIRST_CARD_READ = "first_card_read_done"
        private const val DRIVER_NAME = "driver_name"
        private const val KEEP_SCREEN_ON = "keep_screen_on"
    }

    private val btManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val adapter by lazy { btManager.adapter }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var live: LiveDidDiagnostic
    private lateinit var cardReader: DtcoBluetoothDiagnostic
    private lateinit var sessionStore: TachoSessionStore
    private var engineState = TachoStateEngine.State()
    private var snapshot: TachoStateEngine.Snapshot? = null
    private var dtco: BluetoothDevice? = null
    private var history: HistoryData.Model? = null
    private var cardReading = false
    private var resumeLiveAfterCard = false
    private var lastProcessedCycle = 0

    private lateinit var status: TextView
    private lateinit var driver: TextView
    private lateinit var nowRoot: LinearLayout
    private lateinit var historyRoot: LinearLayout
    private lateinit var nowTab: Button
    private lateinit var historyTab: Button

    private data class MetricViews(val frame: FrameLayout, val progress: View, val value: TextView, val sub: TextView)
    private lateinit var rest: MetricViews
    private lateinit var continuousDriving: MetricViews
    private lateinit var shiftDriving: MetricViews
    private lateinit var continuousWork: MetricViews
    private lateinit var otherWork: MetricViews
    private lateinit var availability: MetricViews
    private lateinit var weekDriving: MetricViews
    private lateinit var twoWeekDriving: MetricViews

    private var rawActivityText = "—"
    private var rawActivityMinutes = 0
    private var rawContinuousDriving = 0
    private var rawBreakMinutes = 0
    private var rawTwoWeekDriving = 0

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        findAndAutoConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        live = LiveDidDiagnostic(applicationContext, this)
        cardReader = DtcoBluetoothDiagnostic(applicationContext, this)
        sessionStore = TachoSessionStore(applicationContext)
        engineState = sessionStore.load()
        applyKeepScreenOn()
        buildUi()
        loadHistory()
        requestPermission()
    }

    override fun onDestroy() {
        live.disconnect()
        cardReader.disconnect()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(dp(12), dp(8) + bars.top, dp(12), dp(8))
            insets
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply { text = "TachoWatch"; textSize = 25f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD) })
        status = TextView(this).apply { text = "DTCO не подключён"; textSize = 11.5f; setTextColor(GREEN) }
        titles.addView(status)
        top.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("☰").apply { textSize = 21f; setOnClickListener { showMenu(this) } })
        root.addView(top)
        root.addView(space(7))

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nowTab = button("Сейчас").apply { setOnClickListener { showNow() } }
        historyTab = button("История").apply { setOnClickListener { showHistory() } }
        tabs.addView(nowTab, LinearLayout.LayoutParams(0, dp(42), 1f))
        tabs.addView(hspace(6))
        tabs.addView(historyTab, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(tabs)
        root.addView(space(7))

        val viewport = FrameLayout(this)
        nowRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        viewport.addView(nowRoot)
        viewport.addView(historyRoot)
        root.addView(viewport, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        buildNow()
        buildHistory()
        updateTabs(true)
    }

    private fun buildNow() {
        nowRoot.removeAllViews()
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        driver = TextView(this).apply {
            text = prefs.getString(DRIVER_NAME, null) ?: "Водитель"
            textSize = 25f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(3), dp(3), 0, dp(8))
        }
        c.addView(driver)

        rest = metric(c, "🛏  ОТДЫХ", "0:00", "нет текущего отдыха")
        continuousDriving = metric(c, "🚗  НЕПРЕРЫВНОЕ ВОЖДЕНИЕ", "0:00 из 4:30", "лимит 4:30")
        shiftDriving = metric(c, "🚗  ВОЖДЕНИЕ ЗА СМЕНУ", "0:00", "не сбрасывается после 45 минут")
        continuousWork = metric(c, "⚒  НЕПРЕРЫВНАЯ РАБОТА", "0:00 из 6:00", "вождение + другая работа")
        otherWork = metric(c, "⚒  ДРУГАЯ РАБОТА", "0:00", "отдельный счётчик текущего рабочего окна")
        availability = metric(c, "⌛  ОЖИДАНИЕ / ГОТОВНОСТЬ", "0:00", "не входит в непрерывную работу")
        weekDriving = metric(c, "🚗  ВОЖДЕНИЕ ЗА НЕДЕЛЮ", "—", "по карте водителя")
        twoWeekDriving = metric(c, "🚗  ДВЕ НЕДЕЛИ", "0:00 из 90:00", "лимит 90:00")

        scroll.addView(c)
        nowRoot.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun metric(parent: LinearLayout, title: String, initial: String, subtitle: String): MetricViews {
        val frame = FrameLayout(this).apply { background = rounded(CARD, dp(17).toFloat(), BORDER); clipToOutline = true }
        val progress = View(this).apply { background = rounded(GREEN, dp(17).toFloat(), GREEN) }
        frame.addView(progress, FrameLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        content.addView(TextView(this).apply { text = title; textSize = 12.5f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD) })
        val value = TextView(this).apply { text = initial; textSize = 29f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD) }
        val sub = TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(TEXT) }
        content.addView(value)
        content.addView(sub)
        frame.addView(content)
        parent.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(space(7))
        return MetricViews(frame, progress, value, sub)
    }

    private fun updateNow() {
        val s = snapshot ?: return
        if (s.activity == TachoStateEngine.Activity.REST) {
            rest.value.text = fmt(s.restMinutes)
            rest.sub.text = restSubtitle(s.restMinutes)
            val next = nextRestTarget(s.restMinutes)
            setProgress(rest, if (next > 0) s.restMinutes.toFloat() / next else 1f, restColor(s.restMinutes))
        } else {
            rest.value.text = "0:00"
            rest.sub.text = "сейчас: ${activityName(s.activity)} ${fmt(s.activityMinutes)}"
            setProgress(rest, 0f, GREEN)
        }

        continuousDriving.value.text = "${fmt(s.continuousDrivingMinutes)} из 4:30"
        continuousDriving.sub.text = "осталось ${fmt((270 - s.continuousDrivingMinutes).coerceAtLeast(0))}"
        setProgress(continuousDriving, s.continuousDrivingMinutes / 270f, driveColor(s.continuousDrivingMinutes))

        val shiftLimit = if (tenHourUsesThisWeek() >= 2) 540 else 600
        shiftDriving.value.text = "${fmt(s.shiftDrivingMinutes)} из ${fmt(shiftLimit)}"
        shiftDriving.sub.text = "осталось ${fmt((shiftLimit - s.shiftDrivingMinutes).coerceAtLeast(0))} • 10ч ${tenHourUsesThisWeek()}/2"
        setProgress(shiftDriving, s.shiftDrivingMinutes.toFloat() / shiftLimit, limitColor(s.shiftDrivingMinutes, shiftLimit))

        continuousWork.value.text = "${fmt(s.continuousWorkMinutes)} из 6:00"
        continuousWork.sub.text = "вождение + другая работа • осталось ${fmt((360 - s.continuousWorkMinutes).coerceAtLeast(0))}"
        setProgress(continuousWork, s.continuousWorkMinutes / 360f, limitColor(s.continuousWorkMinutes, 360))

        otherWork.value.text = fmt(s.otherWorkMinutes)
        otherWork.sub.text = if (s.activity == TachoStateEngine.Activity.OTHER_WORK) "текущая деятельность ${fmt(s.activityMinutes)}" else "в текущем рабочем окне"
        setProgress(otherWork, s.otherWorkMinutes / 360f, limitColor(s.otherWorkMinutes, 360))

        availability.value.text = fmt(s.availabilityMinutes)
        availability.sub.text = if (s.activity == TachoStateEngine.Activity.AVAILABILITY) "текущая деятельность ${fmt(s.activityMinutes)}" else "в текущем рабочем окне"
        setProgress(availability, s.availabilityMinutes / 360f, GREEN)

        val cardWeek = history?.currentWeekCardMinutes ?: 0
        val prevWeek = history?.previousWeekDrivingMinutes ?: 0
        val weekLimit = minOf(56 * 60, (90 * 60 - prevWeek).coerceAtLeast(0))
        weekDriving.value.text = if (history == null) "—" else "${fmt(cardWeek)} из ${fmt(weekLimit)}"
        weekDriving.sub.text = "прошлая неделя ${fmt(prevWeek)}"
        setProgress(weekDriving, if (weekLimit > 0) cardWeek.toFloat() / weekLimit else 0f, limitColor(cardWeek, weekLimit.coerceAtLeast(1)))

        twoWeekDriving.value.text = "${fmt(s.twoWeekDrivingMinutes)} из 90:00"
        setProgress(twoWeekDriving, s.twoWeekDrivingMinutes / (90f * 60f), limitColor(s.twoWeekDrivingMinutes, 90 * 60))
    }

    private fun processRawCycle() {
        val raw = TachoStateEngine.RawSample(
            activity = TachoStateEngine.activityFromText(rawActivityText),
            activityMinutes = rawActivityMinutes,
            continuousDrivingMinutes = rawContinuousDriving,
            breakMinutes = rawBreakMinutes,
            twoWeekDrivingMinutes = rawTwoWeekDriving
        )
        val result = TachoStateEngine.reduce(engineState, raw)
        engineState = result.state
        snapshot = result.snapshot
        sessionStore.save(engineState)
        updateNow()
    }

    private fun buildHistory() {
        historyRoot.removeAllViews()
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val model = history
        if (model == null) {
            c.addView(TextView(this).apply { text = "История появится после полного считывания карты"; textSize = 17f; setTextColor(TEXT); setPadding(dp(4), dp(10), dp(4), dp(10)) })
        } else {
            val reducedUsed = reducedDailyRestsUsed(model)
            val tenUsed = tenHourUsesThisWeek()
            val summary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(CARD, dp(15).toFloat(), BORDER); setPadding(dp(14), dp(12), dp(14), dp(12)) }
            summary.addView(TextView(this).apply { text = "СВОДКА ДОПУСКОВ"; textSize = 13f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD) })
            summary.addView(TextView(this).apply { text = "9-часовых сокращённых отдыхов осталось: ${(3 - reducedUsed).coerceAtLeast(0)} из 3"; textSize = 15f; setTextColor(TEXT) })
            summary.addView(TextView(this).apply { text = "10-часовых вождений осталось: ${(2 - tenUsed).coerceAtLeast(0)} из 2"; textSize = 15f; setTextColor(TEXT) })
            c.addView(summary)
            c.addView(space(8))

            model.days.takeLast(56).asReversed().forEachIndexed { i, day ->
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(CARD, dp(15).toFloat(), BORDER); setPadding(dp(14), dp(12), dp(14), dp(12)) }
                box.addView(TextView(this).apply { text = day.date; textSize = 18f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD) })
                box.addView(historyLine("Начало смены: ${day.startTime ?: "—"}  ${day.startCountry ?: "—"}"))
                box.addView(historyLine("🚗 Вождение: ${fmt(day.drivingMinutes)}"))
                box.addView(historyLine("⚒ Другая работа: ${fmt(day.workMinutes)}"))
                box.addView(historyLine("⌛ Готовность: ${fmt(day.availabilityMinutes)}"))
                box.addView(historyLine("Конец смены: ${day.endTime ?: "—"}  ${day.endCountry ?: "—"}"))
                c.addView(box)
                if (i < model.days.takeLast(56).size - 1) c.addView(space(7))
            }
        }
        scroll.addView(c)
        historyRoot.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun historyLine(text: String) = TextView(this).apply { this.text = text; textSize = 14f; setTextColor(TEXT); setPadding(0, dp(2), 0, dp(2)) }

    private fun loadHistory() {
        val file = TlvInventory.findLatestDdd(getExternalFilesDir(null)) ?: return
        val parsed = TlvInventory.parse(file)
        if (parsed.error == null) {
            history = HistoryData.load(parsed)
            if (::historyRoot.isInitialized) buildHistory()
            if (::weekDriving.isInitialized) updateNow()
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Подключить DTCO")
        popup.menu.add(0, 2, 1, "Диагностика")
        popup.menu.add(0, 3, 2, "Пересчитать текущую смену с нуля")
        popup.menu.add(0, 4, 3, "Не выключать экран").apply { isCheckable = true; isChecked = prefs.getBoolean(KEEP_SCREEN_ON, false) }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { showDtcoPicker(); true }
                2 -> { startActivity(Intent(this, MainActivity::class.java)); true }
                3 -> { sessionStore.clear(); engineState = TachoStateEngine.State(); snapshot = null; processRawCycle(); true }
                4 -> { prefs.edit().putBoolean(KEEP_SCREEN_ON, !prefs.getBoolean(KEEP_SCREEN_ON, false)).apply(); applyKeepScreenOn(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun applyKeepScreenOn() {
        if (prefs.getBoolean(KEEP_SCREEN_ON, false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showNow() { nowRoot.visibility = View.VISIBLE; historyRoot.visibility = View.GONE; updateTabs(true) }
    private fun showHistory() { loadHistory(); nowRoot.visibility = View.GONE; historyRoot.visibility = View.VISIBLE; updateTabs(false) }
    private fun updateTabs(now: Boolean) {
        nowTab.background = rounded(if (now) GREEN else CARD, dp(11).toFloat(), if (now) GREEN else BORDER)
        historyTab.background = rounded(if (now) CARD else GREEN, dp(11).toFloat(), if (now) BORDER else GREEN)
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        } else findAndAutoConnect()
    }

    @SuppressLint("MissingPermission")
    private fun findAndAutoConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val saved = prefs.getString(SELECTED_DTCO, null)
        dtco = try { adapter?.bondedDevices?.firstOrNull { it.address == saved } } catch (_: Throwable) { null }
        val d = dtco
        if (d == null) { status.text = "Выберите DTCO"; return }
        connectSelected(d)
    }

    private fun connectSelected(device: BluetoothDevice) {
        dtco = device
        if (!prefs.getBoolean(FIRST_CARD_READ, false)) startCardRead("Первое подключение", true)
        else { status.text = "Подключение к DTCO…"; live.connect(device) }
    }

    @SuppressLint("MissingPermission")
    private fun showDtcoPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)); return
        }
        val devices = linkedMapOf<String, BluetoothDevice>()
        try { adapter?.bondedDevices?.filter { (it.name ?: "").contains("DTCO", true) }?.forEach { devices[it.address] = it } } catch (_: Throwable) {}
        val labels = mutableListOf<String>()
        val listAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels)
        fun refresh() { labels.clear(); devices.values.forEach { labels.add("${safeName(it)}\n${it.address}") }; listAdapter.notifyDataSetChanged() }
        refresh()
        val dialog = AlertDialog.Builder(this).setTitle("Выберите DTCO").setAdapter(listAdapter) { _, which ->
            val d = devices.values.toList().getOrNull(which) ?: return@setAdapter
            prefs.edit().putString(SELECTED_DTCO, d.address).putBoolean(FIRST_CARD_READ, false).apply()
            dtco = d
            connectSelected(d)
        }.setNegativeButton("Закрыть", null).create()
        dialog.setOnShowListener { startNearbyScan(devices, ::refresh) }
        dialog.show()
    }

    @SuppressLint("MissingPermission")
    private fun startNearbyScan(devices: MutableMap<String, BluetoothDevice>, refresh: () -> Unit) {
        val a = adapter ?: return
        val cb = BluetoothAdapter.LeScanCallback { device, _, _ ->
            val name = try { device.name } catch (_: Throwable) { null }
            if ((name ?: "").contains("DTCO", true)) { devices[device.address] = device; runOnUiThread { refresh() } }
        }
        try { a.startLeScan(cb); handler.postDelayed({ try { a.stopLeScan(cb) } catch (_: Throwable) {} }, 4000) } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission") private fun safeName(d: BluetoothDevice) = try { d.name ?: "DTCO" } catch (_: Throwable) { "DTCO" }

    private fun startCardRead(reason: String, resume: Boolean) {
        if (cardReading) return
        val d = dtco ?: return
        cardReading = true
        resumeLiveAfterCard = resume
        status.text = "Считывание карты • $reason"
        live.disconnect()
        cardReader.connect(d)
    }

    override fun onLiveConnection(connected: Boolean, deviceName: String?) {
        runOnUiThread { if (!cardReading) status.text = if (connected) "Онлайн • ${deviceName ?: "DTCO"}" else "Нет связи с DTCO" }
    }

    override fun onLiveLog(log: String) {
        runOnUiThread {
            val cycle = Regex("LIVE CYCLE #(\\d+) COMPLETE").findAll(log).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@runOnUiThread
            if (cycle <= lastProcessedCycle) return@runOnUiThread
            lastProcessedCycle = cycle
            lastDecoded(log, "F931")?.takeIf { it.isNotBlank() && it != "—" }?.let { driver.text = it; prefs.edit().putString(DRIVER_NAME, it).apply() }
            lastDecoded(log, "F903")?.let { rawActivityText = it }
            minutes(lastDecoded(log, "F927"))?.let { rawActivityMinutes = it }
            minutes(lastDecoded(log, "F923"))?.let { rawContinuousDriving = it }
            minutes(lastDecoded(log, "F925"))?.let { rawBreakMinutes = it }
            minutes(lastDecoded(log, "F938"))?.let { rawTwoWeekDriving = it }
            processRawCycle()
            status.text = "Онлайн • данные актуальны"
        }
    }

    override fun onLogChanged(fullLog: String) {
        if (!cardReading) return
        when {
            fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER) && fullLog.contains("STATUS=SUCCESS") -> runOnUiThread {
                prefs.edit().putBoolean(FIRST_CARD_READ, true).apply(); loadHistory(); finishCardRead(true)
            }
            fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER) && fullLog.contains("STATUS=FAILED") -> runOnUiThread { finishCardRead(false) }
        }
    }

    override fun onConnectionStateChanged(connected: Boolean, deviceName: String?) {
        if (cardReading && connected) runOnUiThread { status.text = "Считывание карты…" }
    }

    private fun finishCardRead(ok: Boolean) {
        val resume = resumeLiveAfterCard
        cardReading = false
        resumeLiveAfterCard = false
        status.text = if (ok) "Карта считана" else "Ошибка чтения карты"
        cardReader.disconnect()
        if (resume) dtco?.let { d -> status.postDelayed({ live.connect(d) }, 500) }
    }

    private fun lastDecoded(log: String, did: String): String? = log.lines().lastOrNull { it.startsWith("$did=") }?.substringAfter("|")?.trim()
    private fun minutes(text: String?): Int? = text?.let { Regex("(\\d+) мин").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private fun reducedDailyRestsUsed(model: HistoryData.Model): Int {
        val rests = model.rests
        val lastWeekly = rests.indexOfLast { it.weekly }
        return rests.drop(lastWeekly + 1).count { !it.weekly && !it.splitDaily && it.creditedDailyMinutes == 9 * 60 }.coerceAtMost(3)
    }

    private fun tenHourUsesThisWeek(): Int {
        val now = java.time.LocalDate.now()
        val wf = java.time.temporal.WeekFields.ISO
        return history?.days.orEmpty().count { day ->
            val date = runCatching { java.time.LocalDate.parse(day.date) }.getOrNull() ?: return@count false
            date.get(wf.weekOfWeekBasedYear()) == now.get(wf.weekOfWeekBasedYear()) &&
                date.get(wf.weekBasedYear()) == now.get(wf.weekBasedYear()) &&
                day.drivingMinutes > 9 * 60
        }.coerceAtMost(2)
    }

    private fun restSubtitle(minutes: Int): String {
        val credited = when {
            minutes >= 11 * 60 -> "11:00"
            minutes >= 9 * 60 -> "9:00"
            minutes >= 3 * 60 -> "3:00"
            minutes >= 45 -> "0:45"
            minutes >= 15 -> "0:15"
            else -> "0:00"
        }
        val next = nextRestTarget(minutes)
        return if (next > minutes) "Засчитано: $credited • следующая ступень ${fmt(next)}" else "Засчитано: $credited"
    }

    private fun nextRestTarget(m: Int): Int = when {
        m < 15 -> 15
        m < 45 -> 45
        m < 180 -> 180
        m < 540 -> 540
        m < 660 -> 660
        m < 1440 -> 1440
        m < 2700 -> 2700
        else -> 0
    }

    private fun activityName(a: TachoStateEngine.Activity) = when (a) {
        TachoStateEngine.Activity.REST -> "отдых"
        TachoStateEngine.Activity.AVAILABILITY -> "готовность"
        TachoStateEngine.Activity.OTHER_WORK -> "другая работа"
        TachoStateEngine.Activity.DRIVING -> "вождение"
        TachoStateEngine.Activity.UNKNOWN -> "неизвестно"
    }

    private fun restColor(m: Int) = if (m >= 45) GREEN else YELLOW
    private fun driveColor(m: Int) = when { m >= 255 -> RED; m >= 240 -> YELLOW; else -> GREEN }
    private fun limitColor(v: Int, limit: Int) = when { v >= limit -> RED; v >= (limit * 0.9f).toInt() -> YELLOW; else -> GREEN }
    private fun fmt(m: Int) = "${m.coerceAtLeast(0) / 60}:${String.format("%02d", m.coerceAtLeast(0) % 60)}"

    private fun setProgress(metric: MetricViews, fraction: Float, color: Int) {
        metric.progress.background = rounded(color, dp(17).toFloat(), color)
        metric.frame.post {
            val width = (metric.frame.width * fraction.coerceIn(0f, 1f)).toInt()
            val lp = metric.progress.layoutParams as FrameLayout.LayoutParams
            if (lp.width != width) { lp.width = width; metric.progress.layoutParams = lp }
        }
    }

    private fun button(text: String) = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(TEXT)
        isAllCaps = false
        background = rounded(CARD, dp(11).toFloat(), BORDER)
        setPadding(dp(12), 0, dp(12), 0)
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        setStroke(dp(1), stroke)
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun hspace(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(w), 1) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
