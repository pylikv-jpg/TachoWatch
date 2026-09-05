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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Main driver dashboard.
 *
 * Data-source policy (important):
 * 1. Use direct tachograph/ISO 16844-7 driver timers whenever the DTCO supports them.
 * 2. Never derive daily/shift driving from a sum of completed F923 periods when F99A exists.
 * 3. Keep a conservative card + live fallback for older DTCO units that reject optional DIDs.
 * 4. The 6-hour working-time counter remains read-only/local: driving + other work only;
 *    availability and rest are excluded, and a qualifying 45-minute rest resets that window.
 */
class DriverDashboardActivity : AppCompatActivity(), LiveDidDiagnostic.Listener, DtcoBluetoothDiagnostic.Listener {

    companion object {
        private const val BG = 0xFF0B1118.toInt()
        private const val CARD = 0xFF141D27.toInt()
        private const val TEXT = 0xFFF3F7FA.toInt()
        private const val MUTED = 0xFF9BAAB8.toInt()
        private const val GREEN = 0xFF238A52.toInt()
        private const val YELLOW = 0xFF9A7A1B.toInt()
        private const val RED = 0xFF9E3434.toInt()
        private const val CYAN = 0xFF29B6C8.toInt()
        private const val BORDER = 0xFF263545.toInt()

        private const val PREFS = "tachowatch_auto_card"
        private const val FIRST_READ = "first_card_read_done"
        private const val SELECTED_DTCO = "selected_dtco_address"
        private const val CARD_NAME = "driver_name_from_card"
        private const val KEEP_SCREEN_ON = "keep_screen_on"
        private const val SHIFT_CLOSE_HANDLED = "direct_shift_close_handled"

        private const val FALLBACK_SHIFT_BASE = "fallback_shift_base"
        private const val FALLBACK_SHIFT_LIVE = "fallback_shift_live"
        private const val FALLBACK_PREV_CONT = "fallback_prev_cont"
        private const val FALLBACK_INITIALIZED = "fallback_shift_initialized"

        private const val WORK_TOTAL = "work6_direct_v4"
        private const val WORK_OTHER = "work_other_direct_v4"
        private const val WORK_AVAIL = "work_avail_direct_v4"
        private const val WORK_PREV_ACTIVITY = "work_prev_activity_direct_v4"
        private const val WORK_PREV_DURATION = "work_prev_duration_direct_v4"
    }

    private val btManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val adapter by lazy { btManager.adapter }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var live: LiveDidDiagnostic
    private lateinit var cardReader: DtcoBluetoothDiagnostic
    private var dtco: BluetoothDevice? = null
    private var history: HistoryData.Model? = null
    private var cardReading = false
    private var resumeLive = false

    private lateinit var status: TextView
    private lateinit var nowTab: Button
    private lateinit var historyTab: Button
    private lateinit var nowRoot: LinearLayout
    private lateinit var historyRoot: LinearLayout
    private lateinit var driver: TextView

    private lateinit var continuous: TextView
    private lateinit var continuousSub: TextView
    private lateinit var continuousFrame: FrameLayout
    private lateinit var continuousProgress: View
    private lateinit var shiftDriving: TextView
    private lateinit var shiftDrivingSub: TextView
    private lateinit var shiftDrivingFrame: FrameLayout
    private lateinit var shiftDrivingProgress: View
    private lateinit var work6: TextView
    private lateinit var work6Sub: TextView
    private lateinit var work6Frame: FrameLayout
    private lateinit var work6Progress: View
    private lateinit var otherWork: TextView
    private lateinit var otherWorkFrame: FrameLayout
    private lateinit var otherWorkProgress: View
    private lateinit var availability: TextView
    private lateinit var availabilityFrame: FrameLayout
    private lateinit var availabilityProgress: View
    private lateinit var restTime: TextView
    private lateinit var restSub: TextView
    private lateinit var restFrame: FrameLayout
    private lateinit var restProgress: View
    private lateinit var workWeek: TextView
    private lateinit var workWeekSub: TextView
    private lateinit var workWeekFrame: FrameLayout
    private lateinit var workWeekProgress: View
    private lateinit var week: TextView
    private lateinit var weekSub: TextView
    private lateinit var weekFrame: FrameLayout
    private lateinit var weekProgress: View
    private lateinit var twoWeek: TextView
    private lateinit var twoWeekFrame: FrameLayout
    private lateinit var twoWeekProgress: View

    // Established live DIDs.
    private var currentActivity = "—"
    private var activityMinutes = 0
    private var continuousMinutes = 0
    private var breakMinutes = 0
    private var twoWeekMinutes = 0

    // Direct ISO/VDO-derived driver timers. Null = unsupported/not received yet.
    private var directDailyDrivingMinutes: Int? = null       // F99A
    private var directWeeklyDrivingMinutes: Int? = null      // F99B
    private var directTimeUntilDailyRest: Int? = null        // F99C
    private var directUninterruptedRest: Int? = null         // F9A2
    private var directMinimumDailyRest: Int? = null          // F9A3
    private var directMaximumDailyPeriod: Int? = null        // F9A5
    private var directMaximumDailyDriving: Int? = null       // F9A6
    private var directReducedDailyRestUses: Int? = null      // F9AB
    private var directRemainingContinuousDriving: Int? = null// F9AD
    private var directRemainingShiftDriving: Int? = null     // F9AF
    private var directRemainingWeeklyDriving: Int? = null    // F9B1
    private var directRemainingTwoWeekDriving: Int? = null   // F9B3
    private var directNextBreakDuration: Int? = null         // F9B9
    private var directRemainingCurrentBreak: Int? = null     // F9C0
    private var directUntilNextBreakOrRest: Int? = null      // F9C2

    // Old-DTCO fallback for shift driving only.
    private var fallbackShiftBase = 0
    private var fallbackShiftLive = 0
    private var fallbackPreviousContinuous = 0
    private var fallbackInitialized = false

    // Local 6-hour working window (driving + work only).
    private var workWindowMinutes = 0
    private var otherWorkWindowMinutes = 0
    private var availabilityWindowMinutes = 0
    private var previousActivity = "—"
    private var previousActivityDuration = 0
    private var shiftCloseHandled = false
    private var lastProcessedCycle = 0

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        findAndAutoConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        live = LiveDidDiagnostic(applicationContext, this)
        cardReader = DtcoBluetoothDiagnostic(applicationContext, this)
        restoreState()
        applyKeepScreenOn()
        buildUi()
        loadHistory()
        requestPermission()
    }

    override fun onDestroy() {
        live.disconnect()
        cardReader.disconnect()
        persistState()
        super.onDestroy()
    }

    private fun restoreState() {
        fallbackShiftBase = prefs.getInt(FALLBACK_SHIFT_BASE, 0)
        fallbackShiftLive = prefs.getInt(FALLBACK_SHIFT_LIVE, 0)
        fallbackPreviousContinuous = prefs.getInt(FALLBACK_PREV_CONT, 0)
        fallbackInitialized = prefs.getBoolean(FALLBACK_INITIALIZED, false)

        workWindowMinutes = prefs.getInt(WORK_TOTAL, 0)
        otherWorkWindowMinutes = prefs.getInt(WORK_OTHER, 0)
        availabilityWindowMinutes = prefs.getInt(WORK_AVAIL, 0)
        previousActivity = prefs.getString(WORK_PREV_ACTIVITY, "—") ?: "—"
        previousActivityDuration = prefs.getInt(WORK_PREV_DURATION, 0)
        shiftCloseHandled = prefs.getBoolean(SHIFT_CLOSE_HANDLED, false)
    }

    private fun persistState() {
        prefs.edit()
            .putInt(FALLBACK_SHIFT_BASE, fallbackShiftBase)
            .putInt(FALLBACK_SHIFT_LIVE, fallbackShiftLive)
            .putInt(FALLBACK_PREV_CONT, fallbackPreviousContinuous)
            .putBoolean(FALLBACK_INITIALIZED, fallbackInitialized)
            .putInt(WORK_TOTAL, workWindowMinutes)
            .putInt(WORK_OTHER, otherWorkWindowMinutes)
            .putInt(WORK_AVAIL, availabilityWindowMinutes)
            .putString(WORK_PREV_ACTIVITY, previousActivity)
            .putInt(WORK_PREV_DURATION, previousActivityDuration)
            .putBoolean(SHIFT_CLOSE_HANDLED, shiftCloseHandled)
            .apply()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(BG)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(dp(12), dp(10) + bars.top, dp(12), dp(10))
            insets
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "TachoWatch"; textSize = 25f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { text = "DTCO не подключён"; textSize = 11.5f; setTextColor(CYAN) }
        titles.addView(status)
        top.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(smallButton("☰").apply { textSize = 20f; contentDescription = "Меню"; setOnClickListener { showMainMenu(this) } })
        root.addView(top)
        root.addView(space(7))

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nowTab = tabButton("Сейчас").apply { setOnClickListener { showNow() } }
        historyTab = tabButton("История").apply { setOnClickListener { showHistory() } }
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
        buildHistoryView()
        updateTabState(true)
    }

    private fun showMainMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Подключить DTCO")
        popup.menu.add(0, 2, 1, "Диагностика")
        popup.menu.add(0, 3, 2, "Не выключать экран").apply {
            isCheckable = true
            isChecked = prefs.getBoolean(KEEP_SCREEN_ON, false)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { showDtcoPicker(); true }
                2 -> { startActivity(Intent(this, MainActivity::class.java)); true }
                3 -> {
                    val enabled = !prefs.getBoolean(KEEP_SCREEN_ON, false)
                    prefs.edit().putBoolean(KEEP_SCREEN_ON, enabled).apply()
                    applyKeepScreenOn(); true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun applyKeepScreenOn() {
        if (prefs.getBoolean(KEEP_SCREEN_ON, false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun buildNow() {
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        driver = TextView(this).apply {
            text = prefs.getString(CARD_NAME, null) ?: "Водитель"
            textSize = 25f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD); setPadding(dp(3), dp(4), 0, dp(8))
        }
        c.addView(driver)

        val d = progressCard(); continuousFrame = d.first; continuousProgress = d.second
        d.third.addView(label("🚗  НЕПРЕРЫВНОЕ ВОЖДЕНИЕ"))
        continuous = value("—", 30f); d.third.addView(continuous)
        continuousSub = sub("лимит 4:30"); d.third.addView(continuousSub)
        c.addView(continuousFrame); c.addView(space(7))

        val s = progressCard(); shiftDrivingFrame = s.first; shiftDrivingProgress = s.second
        s.third.addView(label("🚗  ВОЖДЕНИЕ ЗА СМЕНУ"))
        shiftDriving = value("—", 28f); s.third.addView(shiftDriving)
        shiftDrivingSub = sub("ожидание прямого счётчика DTCO"); s.third.addView(shiftDrivingSub)
        c.addView(shiftDrivingFrame); c.addView(space(7))

        val six = progressCard(); work6Frame = six.first; work6Progress = six.second
        six.third.addView(label("⚒  НЕПРЕРЫВНАЯ РАБОТА"))
        work6 = value("—", 28f); six.third.addView(work6)
        work6Sub = sub("вождение + другая работа • лимит 6:00"); six.third.addView(work6Sub)
        c.addView(work6Frame); c.addView(space(7))

        val ow = progressCard(); otherWorkFrame = ow.first; otherWorkProgress = ow.second
        ow.third.addView(label("⚒  ДРУГАЯ РАБОТА"))
        otherWork = value("0:00", 26f); ow.third.addView(otherWork)
        ow.third.addView(sub("молотки после последней засчитанной паузы 45 мин"))
        c.addView(otherWorkFrame); c.addView(space(7))

        val av = progressCard(); availabilityFrame = av.first; availabilityProgress = av.second
        av.third.addView(label("✉  ОЖИДАНИЕ / ГОТОВНОСТЬ"))
        availability = value("0:00", 26f); av.third.addView(availability)
        av.third.addView(sub("не входит в 6-часовую непрерывную работу"))
        c.addView(availabilityFrame); c.addView(space(7))

        val r = progressCard(); restFrame = r.first; restProgress = r.second
        r.third.addView(label("🛏  ОТДЫХ"))
        restTime = value("0:00", 30f); r.third.addView(restTime)
        restSub = sub("ожидание онлайн-данных"); r.third.addView(restSub)
        c.addView(restFrame); c.addView(space(7))

        val ww = progressCard(); workWeekFrame = ww.first; workWeekProgress = ww.second
        ww.third.addView(label("⏳  РАБОЧАЯ НЕДЕЛЯ"))
        workWeek = value("—", 28f); ww.third.addView(workWeek)
        workWeekSub = sub("144:00 от окончания последнего недельного отдыха"); ww.third.addView(workWeekSub)
        c.addView(workWeekFrame); c.addView(space(7))

        val w = progressCard(); weekFrame = w.first; weekProgress = w.second
        w.third.addView(label("🚗  ТЕКУЩАЯ НЕДЕЛЯ"))
        week = value("—", 28f); w.third.addView(week)
        weekSub = sub("прямой счётчик DTCO / карта"); w.third.addView(weekSub)
        c.addView(weekFrame); c.addView(space(7))

        val tw = progressCard(); twoWeekFrame = tw.first; twoWeekProgress = tw.second
        tw.third.addView(label("🚗  ДВЕ НЕДЕЛИ"))
        twoWeek = value("—", 28f); tw.third.addView(twoWeek); tw.third.addView(sub("лимит 90:00"))
        c.addView(twoWeekFrame)

        scroll.addView(c)
        nowRoot.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun buildHistoryView() {
        historyRoot.removeAllViews()
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val days = history?.days?.takeLast(56)?.asReversed().orEmpty()
        c.addView(sub("История карты: ${days.size} из 56 дней"))
        if (days.isEmpty()) c.addView(value("История появится после полного считывания карты", 17f))
        days.forEachIndexed { i, day ->
            val box = card()
            box.addView(TextView(this).apply { text = prettyDate(day.date); textSize = 18f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD) })
            box.addView(sub("${flag(day.startCountry)} ${day.startCountry ?: "—"}  Начало смены  ${day.startTime ?: "—"}"))
            box.addView(value("🚗 Вождение  ${HistoryData.fmt(day.drivingMinutes)}", 18f))
            box.addView(sub("⚒ Другая работа  ${HistoryData.fmt(day.workMinutes)}"))
            box.addView(sub("✉ Готовность  ${HistoryData.fmt(day.availabilityMinutes)}"))
            box.addView(sub("Продолжительность смены  ${day.shiftMinutes?.let(HistoryData::fmt) ?: "—"}"))
            box.addView(sub("${flag(day.endCountry)} ${day.endCountry ?: "—"}  Конец смены  ${day.endTime ?: "—"}"))
            c.addView(box)
            if (i < days.lastIndex) {
                HistoryData.gapMinutes(days[i + 1], day)?.let { gap ->
                    c.addView(TextView(this).apply {
                        text = if (gap >= 24 * 60) "🛏 Недельный отдых  ${HistoryData.fmt(gap)}" else "🛏 Межсуточный отдых  ${HistoryData.fmt(gap)}"
                        textSize = 14f; gravity = Gravity.CENTER; setTextColor(if (gap >= 24 * 60) CYAN else MUTED); setPadding(0, dp(7), 0, dp(7))
                    })
                }
            }
        }
        scroll.addView(c)
        historyRoot.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun loadHistory(): Boolean {
        val f = TlvInventory.findLatestDdd(getExternalFilesDir(null)) ?: return false
        val r = TlvInventory.parse(f)
        if (r.error != null) return false
        history = HistoryData.load(r)
        if (::historyRoot.isInitialized) buildHistoryView()
        if (!fallbackInitialized) initialiseFallbackFromCard()
        updateWeekCards()
        updateWorkWeekClock()
        return true
    }

    private fun cardShiftDrivingMinutes(): Int {
        val days = history?.days ?: return 0
        if (days.isEmpty()) return 0
        var total = 0
        for (i in days.indices.reversed()) {
            total += days[i].drivingMinutes
            if (i == 0) break
            val gap = HistoryData.gapMinutes(days[i - 1], days[i])
            if (gap != null && gap >= 9 * 60) break
        }
        return total
    }

    private fun initialiseFallbackFromCard() {
        fallbackShiftBase = cardShiftDrivingMinutes()
        fallbackShiftLive = 0
        fallbackPreviousContinuous = continuousMinutes
        fallbackInitialized = true
        persistState()
    }

    private fun processCycle() {
        processFallbackShiftDriving()
        processWorkingWindow()
        processShiftClosure()
        persistState()
    }

    private fun processFallbackShiftDriving() {
        if (!fallbackInitialized) initialiseFallbackFromCard()
        if (directDailyDrivingMinutes != null) {
            // Direct F99A is authoritative. Keep fallback baseline ready, but never mix it into F99A.
            fallbackPreviousContinuous = continuousMinutes
            return
        }
        val driving = currentActivity.contains("ВОЖДЕНИЕ")
        if (driving) {
            val delta = when {
                continuousMinutes >= fallbackPreviousContinuous -> continuousMinutes - fallbackPreviousContinuous
                else -> continuousMinutes
            }.coerceAtLeast(0)
            if (delta in 1..30) fallbackShiftLive += delta
        }
        fallbackPreviousContinuous = continuousMinutes
    }

    private fun processWorkingWindow() {
        val resting = currentActivity.contains("ОТДЫХ")
        val qualifying45 = resting && maxOf(activityMinutes, breakMinutes, directUninterruptedRest ?: 0) >= 45
        if (qualifying45) {
            workWindowMinutes = 0
            otherWorkWindowMinutes = 0
            availabilityWindowMinutes = 0
            previousActivity = currentActivity
            previousActivityDuration = activityMinutes
            return
        }

        val delta = if (currentActivity == previousActivity) {
            (activityMinutes - previousActivityDuration).coerceAtLeast(0)
        } else {
            // Mode changed between polls. F927 is duration of the new mode; count only
            // that observed duration rather than reusing the previous mode's total.
            activityMinutes.coerceAtLeast(0)
        }.coerceAtMost(30)

        when {
            currentActivity.contains("ВОЖДЕНИЕ") -> workWindowMinutes += delta
            currentActivity.contains("РАБОТА") -> {
                workWindowMinutes += delta
                otherWorkWindowMinutes += delta
            }
            currentActivity.contains("ГОТОВНОСТЬ") -> availabilityWindowMinutes += delta
        }
        previousActivity = currentActivity
        previousActivityDuration = activityMinutes
    }

    private fun processShiftClosure() {
        val actualRest = maxOf(activityMinutes, directUninterruptedRest ?: 0)
        val dailyRestReached = currentActivity.contains("ОТДЫХ") && actualRest >= 9 * 60
        if (dailyRestReached && !shiftCloseHandled) {
            shiftCloseHandled = true
            fallbackShiftBase = 0
            fallbackShiftLive = 0
            fallbackPreviousContinuous = continuousMinutes
            fallbackInitialized = true
            if (!cardReading) mainHandler.post { if (!cardReading) startCardRead("Смена завершена", true) }
        } else if (!currentActivity.contains("ОТДЫХ")) {
            shiftCloseHandled = false
        }
    }

    private fun updateNow() {
        val remainingContinuous = directRemainingContinuousDriving
        continuous.text = "${HistoryData.fmt(continuousMinutes)} из 4:30"
        continuousSub.text = if (remainingContinuous != null) "осталось ${HistoryData.fmt(remainingContinuous)} • прямой DTCO" else "лимит 4:30"
        setProgress(continuousFrame, continuousProgress, continuousMinutes / 270f, driveColor(continuousMinutes))

        val shiftTotal = directDailyDrivingMinutes ?: (fallbackShiftBase + fallbackShiftLive)
        val shiftLimit = directMaximumDailyDriving?.takeIf { it in 540..600 } ?: fallbackDailyLimit()
        val shiftRemaining = directRemainingShiftDriving ?: (shiftLimit - shiftTotal).coerceAtLeast(0)
        shiftDriving.text = "${HistoryData.fmt(shiftTotal)} из ${HistoryData.fmt(shiftLimit)}"
        shiftDrivingSub.text = if (directDailyDrivingMinutes != null) {
            if (shiftRemaining <= 30) "⚠ Осталось ${HistoryData.fmt(shiftRemaining)} • прямой DTCO" else "осталось ${HistoryData.fmt(shiftRemaining)} • прямой DTCO"
        } else {
            "осталось ${HistoryData.fmt(shiftRemaining)} • резерв: карта + live"
        }
        setProgress(shiftDrivingFrame, shiftDrivingProgress, shiftTotal.toFloat() / shiftLimit.coerceAtLeast(1), shiftDriveColor(shiftTotal, shiftLimit))

        work6.text = "${HistoryData.fmt(workWindowMinutes)} из 6:00"
        work6Sub.text = if (workWindowMinutes >= 330) "⚠ До 6 часов осталось ${HistoryData.fmt((360 - workWindowMinutes).coerceAtLeast(0))}" else "вождение + другая работа"
        setProgress(work6Frame, work6Progress, workWindowMinutes / 360f, workColor(workWindowMinutes))

        otherWork.text = HistoryData.fmt(otherWorkWindowMinutes)
        setProgress(otherWorkFrame, otherWorkProgress, otherWorkWindowMinutes / 360f, workColor(otherWorkWindowMinutes))
        availability.text = HistoryData.fmt(availabilityWindowMinutes)
        setProgress(availabilityFrame, availabilityProgress, availabilityWindowMinutes / 360f, GREEN)

        val resting = currentActivity.contains("ОТДЫХ")
        val actual = if (resting) maxOf(activityMinutes, breakMinutes, directUninterruptedRest ?: 0) else breakMinutes
        val credited = HistoryData.creditedRestMinutes(actual)
        val nextLocal = HistoryData.nextRestMilestone(actual)
        restTime.text = HistoryData.fmt(actual)
        restSub.text = if (resting) {
            val directText = when {
                directRemainingCurrentBreak != null && directRemainingCurrentBreak!! > 0 -> " • до конца текущего отдыха ${HistoryData.fmt(directRemainingCurrentBreak!!)}"
                directNextBreakDuration != null && directNextBreakDuration!! > 0 -> " • следующий перерыв ${HistoryData.fmt(directNextBreakDuration!!)}"
                else -> ""
            }
            "Засчитано ${HistoryData.fmt(credited)}$directText"
        } else {
            val until = directUntilNextBreakOrRest
            if (until != null) "до следующего перерыва/отдыха ${HistoryData.fmt(until)}" else "сейчас не отдых • накоплено ${HistoryData.fmt(actual)}"
        }
        val target = nextLocal ?: 45 * 60
        setProgress(restFrame, restProgress, if (target > 0) actual.toFloat() / target else 0f, restMilestoneColor(credited))

        twoWeek.text = "${HistoryData.fmt(twoWeekMinutes)} из 90:00"
        setProgress(twoWeekFrame, twoWeekProgress, twoWeekMinutes / (90f * 60f), limitColor(twoWeekMinutes, 90 * 60))
        updateWeekCards()
        updateWorkWeekClock()
    }

    private fun fallbackDailyLimit(): Int = if (currentWeekTenHourUses() >= 2) 540 else 600

    private fun currentWeekTenHourUses(): Int {
        val now = isoCalendar(Date())
        return history?.days.orEmpty().count { d ->
            val date = parseDateOnly(d.date) ?: return@count false
            val c = isoCalendar(date)
            c.get(Calendar.WEEK_OF_YEAR) == now.get(Calendar.WEEK_OF_YEAR) && c.getWeekYear() == now.getWeekYear() && d.drivingMinutes > 540
        }
    }

    private fun updateWeekCards() {
        if (!::week.isInitialized) return
        val current = directWeeklyDrivingMinutes ?: history?.currentWeekCardMinutes ?: 0
        val prev = history?.previousWeekDrivingMinutes ?: 0
        val calculatedLimit = minOf(56 * 60, (90 * 60 - prev).coerceAtLeast(0))
        val directRemaining = directRemainingWeeklyDriving
        val limit = if (directRemaining != null && current + directRemaining in 0..56 * 60) current + directRemaining else calculatedLimit
        week.text = "${HistoryData.fmt(current)} из ${HistoryData.fmt(limit)}"
        val src = if (directWeeklyDrivingMinutes != null) "прямой DTCO" else "карта"
        weekSub.text = if (directRemaining != null) "$src • осталось ${HistoryData.fmt(directRemaining)}" else "$src • прошл. неделя ${HistoryData.fmt(prev)}"
        setProgress(weekFrame, weekProgress, if (limit > 0) current.toFloat() / limit else 1f, limitColor(current, limit))
    }

    private fun updateWorkWeekClock() {
        if (!::workWeek.isInitialized) return
        val start = history?.let { HistoryData.lastWeeklyRestEndMillis(it.days) }
        if (start == null) {
            workWeek.text = directTimeUntilDailyRest?.let { "${HistoryData.fmt(it)} до суточного отдыха" } ?: "—"
            workWeekSub.text = "не найден законченный недельный отдых на карте"
            setProgress(workWeekFrame, workWeekProgress, 0f, GREEN)
            return
        }
        val total = 144 * 60
        val elapsed = ((System.currentTimeMillis() - start) / 60000L).toInt().coerceAtLeast(0)
        val remain = (total - elapsed).coerceAtLeast(0)
        workWeek.text = "${HistoryData.fmt(remain)} осталось"
        val stamp = SimpleDateFormat("dd.MM HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(start))
        workWeekSub.text = "от конца недельного отдыха $stamp • лимит 144:00"
        setProgress(workWeekFrame, workWeekProgress, elapsed.coerceAtMost(total).toFloat() / total, when {
            remain <= 12 * 60 -> RED
            remain <= 24 * 60 -> YELLOW
            else -> GREEN
        })
    }

    private fun loadDirectValues(log: String) {
        mins(last(log, "F99A"))?.let { directDailyDrivingMinutes = it }
        mins(last(log, "F99B"))?.let { directWeeklyDrivingMinutes = it }
        mins(last(log, "F99C"))?.let { directTimeUntilDailyRest = it }
        mins(last(log, "F9A2"))?.let { directUninterruptedRest = it }
        mins(last(log, "F9A3"))?.let { directMinimumDailyRest = it }
        mins(last(log, "F9A5"))?.let { directMaximumDailyPeriod = it }
        mins(last(log, "F9A6"))?.let { directMaximumDailyDriving = it }
        count(last(log, "F9AB"))?.let { directReducedDailyRestUses = it }
        mins(last(log, "F9AD"))?.let { directRemainingContinuousDriving = it }
        mins(last(log, "F9AF"))?.let { directRemainingShiftDriving = it }
        mins(last(log, "F9B1"))?.let { directRemainingWeeklyDriving = it }
        mins(last(log, "F9B3"))?.let { directRemainingTwoWeekDriving = it }
        mins(last(log, "F9B9"))?.let { directNextBreakDuration = it }
        mins(last(log, "F9C0"))?.let { directRemainingCurrentBreak = it }
        mins(last(log, "F9C2"))?.let { directUntilNextBreakOrRest = it }
    }

    override fun onLiveLog(log: String) {
        runOnUiThread {
            val cycle = Regex("LIVE CYCLE #(\\d+) COMPLETE").findAll(log).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (cycle == null || cycle <= lastProcessedCycle) return@runOnUiThread
            lastProcessedCycle = cycle

            last(log, "F931")?.let {
                if (it.isNotBlank() && it != "—") {
                    driver.text = it
                    prefs.edit().putString(CARD_NAME, it).apply()
                }
            }
            last(log, "F903")?.let { currentActivity = it }
            mins(last(log, "F927"))?.let { activityMinutes = it }
            mins(last(log, "F923"))?.let { continuousMinutes = it }
            mins(last(log, "F925"))?.let { breakMinutes = it }
            mins(last(log, "F938"))?.let { twoWeekMinutes = it }
            loadDirectValues(log)

            processCycle()
            status.text = if (directDailyDrivingMinutes != null) "Онлайн • прямые счётчики DTCO" else "Онлайн • совместимый режим"
            updateNow()
        }
    }

    override fun onLiveConnection(connected: Boolean, deviceName: String?) {
        runOnUiThread {
            if (!cardReading) status.text = if (connected) "Онлайн • ${deviceName ?: "DTCO"} • читаю счётчики" else "Восстановление связи с DTCO…"
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        else findAndAutoConnect()
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

    private fun connectSelected(d: BluetoothDevice) {
        dtco = d
        if (!prefs.getBoolean(FIRST_READ, false)) startCardRead("Первое подключение", true)
        else { status.text = "Подключение к DTCO…"; live.connect(d) }
    }

    @SuppressLint("MissingPermission")
    private fun showDtcoPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)); return
        }
        val devices = linkedMapOf<String, BluetoothDevice>()
        try { adapter?.bondedDevices?.filter { (it.name ?: "").contains("DTCO", true) }?.forEach { devices[it.address] = it } } catch (_: Throwable) {}
        val labels = mutableListOf<String>()
        val listAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels)
        fun refresh() { labels.clear(); devices.values.forEach { labels.add("${safeName(it)}\n${it.address}") }; listAdapter.notifyDataSetChanged() }
        refresh()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Выберите DTCO")
            .setAdapter(listAdapter) { _, which ->
                val d = devices.values.toList().getOrNull(which) ?: return@setAdapter
                prefs.edit().putString(SELECTED_DTCO, d.address).putBoolean(FIRST_READ, false).apply()
                fallbackInitialized = false
                directDailyDrivingMinutes = null
                dtco = d
                status.text = "Выбран ${safeName(d)}"
                connectSelected(d)
            }
            .setNegativeButton("Закрыть", null)
            .create()
        dialog.setOnShowListener { startNearbyScan(devices, ::refresh) }
        dialog.show()
    }

    @SuppressLint("MissingPermission")
    private fun startNearbyScan(devices: MutableMap<String, BluetoothDevice>, refresh: () -> Unit) {
        val a = adapter ?: return
        val cb = BluetoothAdapter.LeScanCallback { device, _, _ ->
            val n = try { device.name } catch (_: Throwable) { null }
            if ((n ?: "").contains("DTCO", true)) { devices[device.address] = device; runOnUiThread { refresh() } }
        }
        try { a.startLeScan(cb); mainHandler.postDelayed({ try { a.stopLeScan(cb) } catch (_: Throwable) {} }, 4000) } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice) = try { d.name ?: "DTCO" } catch (_: Throwable) { "DTCO" }

    private fun startCardRead(reason: String, resume: Boolean) {
        if (cardReading) return
        val d = dtco ?: return
        cardReading = true
        resumeLive = resume
        status.text = "Считывание карты • $reason"
        live.disconnect()
        cardReader.connect(d)
    }

    override fun onLogChanged(fullLog: String) {
        if (!cardReading) return
        when {
            fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER) && fullLog.contains("STATUS=SUCCESS") -> runOnUiThread {
                prefs.edit().putBoolean(FIRST_READ, true).apply()
                loadHistory()
                initialiseFallbackFromCard()
                finishCardRead(true)
            }
            fullLog.contains(DtcoBluetoothDiagnostic.RESULT_MARKER) && fullLog.contains("STATUS=FAILED") -> runOnUiThread { finishCardRead(false) }
        }
    }

    override fun onConnectionStateChanged(connected: Boolean, deviceName: String?) {
        if (cardReading && connected) runOnUiThread { status.text = "Считывание карты…" }
    }

    private fun finishCardRead(ok: Boolean) {
        val resume = resumeLive
        cardReading = false
        resumeLive = false
        status.text = if (ok) "Карта считана • данные обновлены" else "Ошибка чтения карты • подключаю live"
        cardReader.disconnect()
        if (resume) dtco?.let { d -> status.postDelayed({ live.connect(d) }, 500) }
    }

    private fun showNow() { nowRoot.visibility = View.VISIBLE; historyRoot.visibility = View.GONE; updateTabState(true) }
    private fun showHistory() { loadHistory(); nowRoot.visibility = View.GONE; historyRoot.visibility = View.VISIBLE; updateTabState(false) }
    private fun updateTabState(nowSelected: Boolean) {
        nowTab.background = rounded(if (nowSelected) GREEN else CARD, dp(11).toFloat(), if (nowSelected) GREEN else BORDER)
        historyTab.background = rounded(if (nowSelected) CARD else GREEN, dp(11).toFloat(), if (nowSelected) BORDER else GREEN)
    }

    private fun parseDateOnly(v: String): Date? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(v)
    }.getOrNull()

    private fun isoCalendar(d: Date) = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4; time = d
    }

    private fun last(log: String, did: String) = log.lines().asReversed().firstOrNull { it.startsWith("$did=") }?.substringAfter(" | ")?.trim()
    private fun mins(v: String?): Int? = v?.let { Regex("^(\\d+) мин").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    private fun count(v: String?): Int? = v?.let { Regex("^(\\d+) count").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private fun driveColor(m: Int) = when { m >= 255 -> RED; m >= 240 -> YELLOW; else -> GREEN }
    private fun shiftDriveColor(v: Int, limit: Int) = when { v >= limit - 30 -> RED; v >= limit - 60 -> YELLOW; else -> GREEN }
    private fun restMilestoneColor(m: Int) = when { m >= 45 * 60 -> CYAN; m >= 24 * 60 -> GREEN; m >= 11 * 60 -> GREEN; m >= 9 * 60 -> GREEN; m >= 3 * 60 -> YELLOW; m >= 45 -> GREEN; m >= 15 -> YELLOW; else -> RED }
    private fun workColor(m: Int) = when { m >= 360 -> RED; m >= 330 -> YELLOW; else -> GREEN }
    private fun limitColor(v: Int, limit: Int) = when { limit <= 0 || v >= limit - 120 -> RED; v >= limit - 360 -> YELLOW; else -> GREEN }

    private fun progressCard(): Triple<FrameLayout, View, LinearLayout> {
        val f = FrameLayout(this).apply { background = rounded(CARD, dp(15).toFloat(), BORDER) }
        val p = View(this).apply { background = rounded(GREEN, dp(15).toFloat()) }
        f.addView(p, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(11)) }
        f.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return Triple(f, p, b)
    }

    private fun setProgress(frame: FrameLayout, bar: View, p: Float, color: Int) {
        frame.post {
            val w = (frame.width * p.coerceIn(0f, 1f)).toInt()
            val lp = bar.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w) { lp.width = w; bar.layoutParams = lp }
            bar.background = rounded(color, dp(15).toFloat())
        }
    }

    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(CARD, dp(14).toFloat(), BORDER) }
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 11.5f; setTextColor(MUTED); setTypeface(typeface, Typeface.BOLD) }
    private fun value(t: String, s: Float) = TextView(this).apply { text = t; textSize = s; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(2), 0, 0) }
    private fun sub(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(MUTED); setPadding(0, dp(2), 0, 0) }
    private fun tabButton(t: String) = Button(this).apply { text = t; isAllCaps = false; textSize = 14f; setTextColor(TEXT); background = rounded(CARD, dp(11).toFloat(), BORDER) }
    private fun smallButton(t: String) = tabButton(t).apply { textSize = 11f; minWidth = 0; minimumWidth = 0; setPadding(dp(9), 0, dp(9), 0) }
    private fun rounded(c: Int, r: Float, stroke: Int? = null) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(c); if (stroke != null) setStroke(dp(1), stroke) }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun hspace(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(w), 1) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prettyDate(d: String) = runCatching { val p = d.split('-'); "${p[2]}.${p[1]}.${p[0]}" }.getOrDefault(d)
    private fun flag(c: String?) = when (c) { "B" -> "🇧🇪"; "F" -> "🇫🇷"; "D" -> "🇩🇪"; "NL" -> "🇳🇱"; "L" -> "🇱🇺"; "E" -> "🇪🇸"; "LT" -> "🇱🇹"; "LV" -> "🇱🇻"; else -> "🌐" }
}
