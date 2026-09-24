package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persistent live DTCO service.
 * Keeps counters outside the Activity, survives UI recreation and evaluates alerts.
 */
class DriverLiveService : Service(), LiveDidDiagnostic.Listener, TextToSpeech.OnInitListener {
    companion object {
        private const val ACTION_START = "com.pylikv.tachowatch.DRIVER_LIVE_START"
        private const val ACTION_PAUSE = "com.pylikv.tachowatch.DRIVER_LIVE_PAUSE"
        private const val ACTION_STOP = "com.pylikv.tachowatch.DRIVER_LIVE_STOP"
        private const val EXTRA_ADDRESS = "device_address"

        private const val CHANNEL_SERVICE = "driver_live_service"
        private const val CHANNEL_ALERTS = "driver_limit_alerts"
        private const val NOTIFICATION_ID = 1401
        const val ALERT_NOTIFICATION_ID = 1402

        const val PREFS = "tachowatch_auto_card"
        const val SELECTED_DTCO = "selected_dtco_address"
        const val SHIFT_INITIALIZED = "shift_counter_initialized"
        const val SHIFT_COMPLETED = "shift_completed_driving"
        const val SHIFT_PREV_CONTINUOUS = "shift_prev_continuous"
        const val WORK_WINDOW = "work_window_minutes"
        const val CW_OTHER_WINDOW = "continuous_work_other_minutes"
        const val CW_PREV_ACTIVITY = "continuous_work_prev_activity"
        const val CW_PREV_DURATION = "continuous_work_prev_duration"
        const val CW_PREV_CONTINUOUS = "continuous_work_prev_continuous"
        const val WORK_PREV_ACTIVITY = "work_prev_activity"
        const val WORK_PREV_DURATION = "work_prev_duration"
        const val WORK_ACC = "other_work_window_minutes"
        const val AVAIL_ACC = "availability_window_minutes"
        const val DAILY_REST_CARD_READ_ARMED = "daily_rest_card_read_armed"
        const val SHIFT_CARD_READ_PENDING = "shift_card_read_pending"

        const val SNAP_ACTIVITY = "live_current_activity"
        const val SNAP_ACTIVITY_MIN = "live_activity_minutes"
        const val SNAP_CONTINUOUS_MIN = "live_continuous_minutes"
        const val SNAP_BREAK_MIN = "live_break_minutes"
        const val SNAP_TWO_WEEK_MIN = "live_two_week_minutes"
        const val SNAP_UPDATED_AT = "live_updated_at"

        private val listeners = CopyOnWriteArrayList<LiveDidDiagnostic.Listener>()
        @Volatile private var running = false
        @Volatile private var connected = false
        @Volatile private var deviceName: String? = null
        @Volatile private var fullLog = ""

        fun start(context: Context, address: String) {
            val i = Intent(context, DriverLiveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ADDRESS, address)
            ContextCompat.startForegroundService(context, i)
        }

        fun pause(context: Context) {
            context.startService(Intent(context, DriverLiveService::class.java).setAction(ACTION_PAUSE))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DriverLiveService::class.java).setAction(ACTION_STOP))
        }

        fun registerListener(listener: LiveDidDiagnostic.Listener) {
            if (!listeners.contains(listener)) listeners.add(listener)
            listener.onLiveConnection(connected, deviceName)
            if (fullLog.isNotBlank()) listener.onLiveLog(fullLog)
        }

        fun unregisterListener(listener: LiveDidDiagnostic.Listener) {
            listeners.remove(listener)
        }

        fun isRunning(): Boolean = running

        fun diagnosticConnectionSummary(): String =
            "running=$running connected=$connected device=${deviceName ?: "unknown"}"
    }

    private lateinit var live: LiveDidDiagnostic
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var pausedForCardRead = false
    private var stopping = false

    private var currentActivity = "—"
    private var activityMinutes = 0
    private var continuousMinutes = 0
    private var continuousAlertStage = 0
    private var lastPresentedContinuousAlertKey: String? = null
    private var breakMinutes = 0
    private var twoWeekMinutes = 0
    private var lastProcessedCycle = 0

    private var shiftCounterInitialized = false
    private var shiftCompletedMinutes = 0
    private var previousContinuousMinutes = 0
    private var workWindowMinutes = 0
    private var continuousWorkOtherMinutes = 0
    private var continuousWorkPreviousActivity = "—"
    private var continuousWorkPreviousDuration = 0
    private var continuousWorkPreviousContinuous = 0
    private var previousActivity = "—"
    private var previousActivityDuration = 0
    private var otherWorkWindowMinutes = 0
    private var availabilityWindowMinutes = 0

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIFICATION_ID, serviceNotification("Мониторинг запускается…"))
        running = true
        restoreState()
        live = LiveDidDiagnostic(applicationContext, this)
        tts = TextToSpeech(applicationContext, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                live.disconnect()
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pausedForCardRead = true
                DiagnosticReporter.record(applicationContext, "CARD_READ", "Live paused for driver-card read")
                live.disconnect()
                updateServiceNotification("Считывание карты • live временно приостановлен")
            }
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (!address.isNullOrBlank()) {
                    prefs().edit().putString(SELECTED_DTCO, address).apply()
                    if (pausedForCardRead) {
                        DiagnosticReporter.record(applicationContext, "CARD_READ", "Live resumed after driver-card read")
                    }
                    pausedForCardRead = false
                    connectAddress(address)
                }
            }
            else -> if (!pausedForCardRead) {
                prefs().getString(SELECTED_DTCO, null)?.let(::connectAddress)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { live.disconnect() } catch (_: Throwable) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        ttsReady = false
        running = false
        connected = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = tts ?: return
        var result = engine.setLanguage(Locale("ru", "RU"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = engine.setLanguage(Locale.getDefault())
        }
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (ttsReady) {
            pendingSpeech?.let {
                pendingSpeech = null
                speakNow(it)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectAddress(address: String) {
        if (stopping || pausedForCardRead) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            updateServiceNotification("Нет разрешения Bluetooth")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = try { adapter?.getRemoteDevice(address) } catch (_: Throwable) { null }
        if (device == null) {
            updateServiceNotification("DTCO не найден")
            return
        }
        updateServiceNotification("Подключение к DTCO…")
        live.connect(device)
    }

    override fun onLiveConnection(isConnected: Boolean, name: String?) {
        connected = isConnected
        deviceName = name
        DiagnosticReporter.record(
            applicationContext,
            "CONNECTION",
            if (isConnected) "DTCO connected: ${name ?: "unknown"}" else "DTCO disconnected"
        )
        if (isConnected) {
            // LiveDidDiagnostic restarts cycle numbering from #1 for each GATT session.
            // Treat every reconnect as a new processing epoch.
            lastProcessedCycle = 0
        }
        updateServiceNotification(if (isConnected) "DTCO подключён • контроль лимитов активен" else "Связь потеряна • переподключение…")
        listeners.forEach { it.onLiveConnection(isConnected, name) }
    }

    override fun onLiveLog(log: String) {
        fullLog = log
        val cycle = Regex("LIVE CYCLE #(\\d+) COMPLETE").findAll(log).lastOrNull()
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        if (cycle != null && cycle > lastProcessedCycle) {
            val block = currentCycleBlock(log, cycle)
            // Mark this cycle consumed even when one mandatory DID timed out. Reusing a value
            // from an older cycle is more dangerous than waiting for the next complete cycle.
            lastProcessedCycle = cycle

            val freshActivity = block?.let { last(it, "F903") }
            val freshActivityMinutes = block?.let { mins(last(it, "F927")) }
            val freshContinuous = block?.let { mins(last(it, "F923")) }
            val freshBreak = block?.let { mins(last(it, "F925")) }
            // Direct DTCO shift-driving pair. F9AF is the remaining driving time on
            // the current shift; F9A6 is the maximum daily driving time for that shift.
            // Use only values from this same completed live cycle — never a stale snapshot.
            val freshRemainingShift = block?.let { mins(last(it, "F9AF")) }
            val freshMaximumDailyDriving = block?.let { mins(last(it, "F9A6")) }
            val freshTimeLeftUntilDailyRest = block?.let { mins(last(it, "F99C")) }
            val freshMaximumDailyPeriod = block?.let { mins(last(it, "F9A5")) }

            if (freshActivity != null && freshActivityMinutes != null && freshContinuous != null && freshBreak != null) {
                val previousRawContinuous = continuousMinutes
                currentActivity = freshActivity
                activityMinutes = freshActivityMinutes
                continuousMinutes = freshContinuous
                breakMinutes = freshBreak

                // A new continuous-driving cycle starts only when the tachograph itself
                // resets F923. Do not re-arm alerts because of elapsed time or polling.
                if (previousRawContinuous >= 30 && continuousMinutes <= 5) {
                    resetContinuousAlertCycle()
                }
                block?.let { mins(last(it, "F938")) }?.let { twoWeekMinutes = it }

                processCycle(
                    directRemainingShiftMinutes = freshRemainingShift,
                    directMaximumDailyDrivingMinutes = freshMaximumDailyDriving
                )
                persistState()
                evaluateAlerts(
                    maximumDailyPeriodMinutes = freshMaximumDailyPeriod,
                    timeLeftUntilDailyRestMinutes = freshTimeLeftUntilDailyRest
                )
                updateServiceNotification("DTCO подключён • свежие данные")
            } else {
                updateServiceNotification("DTCO подключён • неполный live-цикл, ожидание свежих данных")
            }
        }

        listeners.forEach { it.onLiveLog(log) }
    }

    private fun restoreState() {
        val p = prefs()
        currentActivity = p.getString(SNAP_ACTIVITY, "—") ?: "—"
        activityMinutes = p.getInt(SNAP_ACTIVITY_MIN, 0)
        continuousMinutes = p.getInt(SNAP_CONTINUOUS_MIN, 0)
        continuousAlertStage = p.getInt("alert_stage_cont", 0)
        breakMinutes = p.getInt(SNAP_BREAK_MIN, 0)
        twoWeekMinutes = p.getInt(SNAP_TWO_WEEK_MIN, 0)
        shiftCounterInitialized = p.getBoolean(SHIFT_INITIALIZED, false)
        shiftCompletedMinutes = p.getInt(SHIFT_COMPLETED, 0)
        previousContinuousMinutes = p.getInt(SHIFT_PREV_CONTINUOUS, 0)
        workWindowMinutes = p.getInt(WORK_WINDOW, 0)
        continuousWorkOtherMinutes = p.getInt(CW_OTHER_WINDOW, 0)
        continuousWorkPreviousActivity = p.getString(CW_PREV_ACTIVITY, "—") ?: "—"
        continuousWorkPreviousDuration = p.getInt(CW_PREV_DURATION, 0)
        continuousWorkPreviousContinuous = p.getInt(CW_PREV_CONTINUOUS, continuousMinutes)
        previousActivity = p.getString(WORK_PREV_ACTIVITY, "—") ?: "—"
        previousActivityDuration = p.getInt(WORK_PREV_DURATION, 0)
        otherWorkWindowMinutes = p.getInt(WORK_ACC, 0)
        availabilityWindowMinutes = p.getInt(AVAIL_ACC, 0)
    }

    private fun persistState() {
        prefs().edit()
            .putString(SNAP_ACTIVITY, currentActivity)
            .putInt(SNAP_ACTIVITY_MIN, activityMinutes)
            .putInt(SNAP_CONTINUOUS_MIN, continuousMinutes)
            .putInt(SNAP_BREAK_MIN, breakMinutes)
            .putInt(SNAP_TWO_WEEK_MIN, twoWeekMinutes)
            .putLong(SNAP_UPDATED_AT, System.currentTimeMillis())
            .putBoolean(SHIFT_INITIALIZED, shiftCounterInitialized)
            .putInt(SHIFT_COMPLETED, shiftCompletedMinutes)
            .putInt(SHIFT_PREV_CONTINUOUS, previousContinuousMinutes)
            .putInt(WORK_WINDOW, workWindowMinutes)
            .putInt(CW_OTHER_WINDOW, continuousWorkOtherMinutes)
            .putString(CW_PREV_ACTIVITY, continuousWorkPreviousActivity)
            .putInt(CW_PREV_DURATION, continuousWorkPreviousDuration)
            .putInt(CW_PREV_CONTINUOUS, continuousWorkPreviousContinuous)
            .putString(WORK_PREV_ACTIVITY, previousActivity)
            .putInt(WORK_PREV_DURATION, previousActivityDuration)
            .putInt(WORK_ACC, otherWorkWindowMinutes)
            .putInt(AVAIL_ACC, availabilityWindowMinutes)
            .apply()
    }

    private fun processCycle(
        directRemainingShiftMinutes: Int?,
        directMaximumDailyDrivingMinutes: Int?
    ) {
        val restNow = isRest(currentActivity)
        val restMinutes = if (restNow) maxOf(activityMinutes, breakMinutes) else 0
        val dailyRestCompleted = restMinutes >= 9 * 60
        updateShiftCardReadState(restNow, dailyRestCompleted)

        // Shift driving has one authoritative source when the DTCO supports it:
        // used shift driving = maximum allowed daily driving (F9A6)
        //                    - remaining driving on current shift (F9AF).
        //
        // This prevents persisted/card values from the previous shift being carried into
        // a newly opened shift. If either direct DID is absent/invalid in this cycle, keep
        // the existing F923-based counter strictly as a compatibility fallback.
        val directShiftLimit = directMaximumDailyDrivingMinutes?.takeIf { it in 9 * 60..10 * 60 }
        val directShiftRemaining = directRemainingShiftMinutes?.takeIf {
            directShiftLimit != null && it in 0..directShiftLimit
        }

        val directShiftDrivingMinutes =
            if (directShiftLimit != null && directShiftRemaining != null) {
                (directShiftLimit - directShiftRemaining).coerceIn(0, directShiftLimit)
            } else {
                null
            }

        if (dailyRestCompleted) {
            shiftCounterInitialized = true
            shiftCompletedMinutes = 0
            previousContinuousMinutes = continuousMinutes
        } else if (directShiftDrivingMinutes != null) {
            shiftCounterInitialized = true
            shiftCompletedMinutes = directShiftDrivingMinutes
            // Keep fallback checkpoint aligned so a later unsupported cycle cannot add the
            // current F923 segment a second time.
            previousContinuousMinutes = continuousMinutes
        } else {
            val shift = ShiftDrivingCounter.update(
                initialized = shiftCounterInitialized,
                totalMinutes = shiftCompletedMinutes,
                previousContinuousMinutes = previousContinuousMinutes,
                currentContinuousMinutes = continuousMinutes,
                dailyRestCompleted = false
            )
            shiftCounterInitialized = shift.initialized
            shiftCompletedMinutes = shift.totalMinutes
            previousContinuousMinutes = shift.previousContinuousMinutes
        }

        val previousContinuousOtherWork = continuousWorkOtherMinutes
        val cw = ContinuousWorkCounter.update(
            state = ContinuousWorkCounter.State(
                workMinutes = workWindowMinutes,
                otherWorkMinutes = continuousWorkOtherMinutes,
                previousActivity = continuousWorkPreviousActivity,
                previousSourceMinutes = continuousWorkPreviousDuration,
                previousContinuousDrivingMinutes = continuousWorkPreviousContinuous
            ),
            currentActivity = currentActivity,
            activityMinutes = activityMinutes,
            continuousDrivingMinutes = continuousMinutes,
            qualifyingRestMinutes = restMinutes,
            currentShiftDrivingMinutes = directShiftDrivingMinutes
        )

        // ContinuousWorkCounter is the only owner of the 6h work window and of live
        // OTHER WORK deltas. Feed that same delta into the shift OTHER WORK total so
        // the two counters cannot diverge while standing in OTHER WORK.
        val liveOtherWorkDelta =
            (cw.otherWorkMinutes - previousContinuousOtherWork).coerceAtLeast(0)
        processWorkWindowBookkeeping(liveOtherWorkDelta)
        workWindowMinutes = cw.workMinutes
        continuousWorkOtherMinutes = cw.otherWorkMinutes
        continuousWorkPreviousActivity = cw.previousActivity
        continuousWorkPreviousDuration = cw.previousSourceMinutes
        continuousWorkPreviousContinuous = cw.previousContinuousDrivingMinutes

        if (dailyRestCompleted) {
            workWindowMinutes = 0
            continuousWorkOtherMinutes = 0
            continuousWorkPreviousActivity = currentActivity
            continuousWorkPreviousDuration = activitySourceMinutes()
            continuousWorkPreviousContinuous = continuousMinutes
            otherWorkWindowMinutes = 0
            availabilityWindowMinutes = 0
            previousActivity = currentActivity
            previousActivityDuration = activitySourceMinutes()
            // Continuous-driving alerts are NOT reset here. Their cycle is owned by
            // F923 and is reset only when F923 itself drops back to the start range.
            clearAlertGroup("work_")
            clearAlertGroup("shift9_")
            clearAlertGroup("shift10_")
            clearAlertGroup("shiftperiod13_")
            clearAlertGroup("shiftperiod15_")
        }
    }

    private fun updateShiftCardReadState(restNow: Boolean, dailyRestCompleted: Boolean) {
        val p = prefs()
        if (dailyRestCompleted) {
            // Arm exactly one automatic card read for the next shift opening. Keep it
            // armed throughout the daily rest; the trigger is the first fresh non-rest
            // live cycle after that rest.
            if (!p.getBoolean(DAILY_REST_CARD_READ_ARMED, false) ||
                p.getBoolean(SHIFT_CARD_READ_PENDING, false)
            ) {
                p.edit()
                    .putBoolean(DAILY_REST_CARD_READ_ARMED, true)
                    .putBoolean(SHIFT_CARD_READ_PENDING, false)
                    .apply()
            }
            return
        }

        if (!restNow && p.getBoolean(DAILY_REST_CARD_READ_ARMED, false)) {
            // Commit synchronously before notifying listeners through onLiveLog so the
            // dashboard can consume this flag from the same completed live cycle.
            p.edit()
                .putBoolean(DAILY_REST_CARD_READ_ARMED, false)
                .putBoolean(SHIFT_CARD_READ_PENDING, true)
                .commit()
        }
    }

    private fun processWorkWindowBookkeeping(liveOtherWorkDelta: Int) {
        // OTHER WORK is decoded once by ContinuousWorkCounter from F927.
        // Reuse exactly that live delta for the full-shift OTHER WORK total instead
        // of maintaining a second accumulator that can miss or duplicate segments.
        otherWorkWindowMinutes += liveOtherWorkDelta

        // Availability stays independent and is not part of the 6h work window.
        val sourceNow = activitySourceMinutes()
        if (previousActivity == "—") {
            previousActivity = currentActivity
            previousActivityDuration = sourceNow
            if (isAvailability(currentActivity)) {
                availabilityWindowMinutes += sourceNow
            }
        } else if (previousActivity == currentActivity) {
            val delta = (sourceNow - previousActivityDuration).coerceAtLeast(0)
            if (isAvailability(currentActivity)) {
                availabilityWindowMinutes += delta
            }
            previousActivityDuration = sourceNow
        } else {
            previousActivity = currentActivity
            previousActivityDuration = sourceNow
            if (isAvailability(currentActivity)) {
                availabilityWindowMinutes += sourceNow
            }
        }
    }

    private fun activitySourceMinutes(): Int = when {
        isDriving(currentActivity) -> continuousMinutes
        else -> activityMinutes
    }

    private fun activeWorkTotal(): Int = workWindowMinutes

    private fun shiftDrivingTotal(): Int = shiftCompletedMinutes

    private fun evaluateAlerts(
        maximumDailyPeriodMinutes: Int?,
        timeLeftUntilDailyRestMinutes: Int?
    ) {
        evaluateContinuousDrivingAlert()
        evaluateRemaining("work", 360 - activeWorkTotal(), "непрерывной работы", "Лимит непрерывной работы 6 часов достигнут")

        val shift = shiftDrivingTotal()
        evaluateRemaining("shift9", 540 - shift, "суточного вождения 9 часов", "Лимит суточного вождения 9 часов достигнут. При допустимом продлении остаётся до 10 часов")
        if (shift >= 540) {
            evaluateRemaining("shift10", 600 - shift, "продлённого суточного вождения 10 часов", "Лимит продлённого суточного вождения 10 часов достигнут")
        }

        evaluateDailyPeriodAlerts(maximumDailyPeriodMinutes, timeLeftUntilDailyRestMinutes)
    }

    private fun evaluateDailyPeriodAlerts(
        maximumDailyPeriodMinutes: Int?,
        timeLeftUntilDailyRestMinutes: Int?
    ) {
        val remaining = ShiftPeriodAlertMath.remaining(
            maximumDailyPeriodMinutes = maximumDailyPeriodMinutes,
            timeLeftUntilDailyRestMinutes = timeLeftUntilDailyRestMinutes
        )

        if (ShiftPeriodAlertMath.shouldWarn(remaining.toThirteenHours)) {
            fireOnce(
                "shiftperiod13_30",
                "До 13-часового периода смены осталось ${remaining.toThirteenHours} мин"
            )
        }

        if (ShiftPeriodAlertMath.shouldWarn(remaining.toFifteenHours)) {
            fireOnce(
                "shiftperiod15_30",
                "До максимального 15-часового периода смены осталось ${remaining.toFifteenHours} мин"
            )
        }
    }

    private fun evaluateContinuousDrivingAlert() {
        val remaining = 270 - continuousMinutes
        val nextStage = when {
            remaining <= 0 -> 4
            remaining <= 5 -> 3
            remaining <= 15 -> 2
            remaining <= 30 -> 1
            else -> 0
        }
        if (nextStage == 0) return

        val p = prefs()
        val stageKey = "alert_stage_cont"
        val persistedStage = p.getInt(stageKey, 0)
        if (persistedStage > continuousAlertStage) {
            continuousAlertStage = persistedStage
        }
        if (nextStage <= continuousAlertStage) return

        // First update the in-memory latch, then persist it. If another code path ever
        // removes the preference, the running service still cannot re-fire this stage.
        continuousAlertStage = nextStage
        if (!p.edit().putInt(stageKey, nextStage).commit()) return

        val key: String
        val text: String
        when (nextStage) {
            4 -> {
                key = "cont_0"
                text = "Лимит непрерывного вождения 4 часа 30 минут достигнут"
            }
            3 -> {
                key = "cont_5"
                text = "До лимита непрерывного вождения осталось 5 минут"
            }
            2 -> {
                key = "cont_15"
                text = "До лимита непрерывного вождения осталось 15 минут"
            }
            else -> {
                key = "cont_30"
                text = "До лимита непрерывного вождения осталось 30 минут"
            }
        }

        // Keep the legacy flags in sync for the acknowledgement Activity and migration.
        p.edit()
            .putBoolean("alert_shown_$key", true)
            .commit()

        if (lastPresentedContinuousAlertKey == key) return
        lastPresentedContinuousAlertKey = key
        showAlert(key, text)
        speak(text)
    }

    private fun evaluateRemaining(group: String, remaining: Int, label: String, reachedText: String) {
        // Re-arm only after a real counter reset, not after a 1-minute fluctuation
        // around a warning boundary. Example: 30 -> 31 -> 30 must NOT repeat the
        // 30-minute alert. Continuous driving/work counters reset far away from the
        // warning zone after a qualifying rest, so 120 minutes gives safe hysteresis.
        // Shift alerts are also cleared explicitly after completed daily rest.
        val rearmAbove = 120
        if (remaining >= rearmAbove) {
            clearAlertGroup("${group}_")
            return
        }

        when {
            remaining <= 0 -> fireOnce("${group}_0", reachedText)
            remaining <= 5 -> fireOnce("${group}_5", "До лимита $label осталось 5 минут")
            remaining <= 15 -> fireOnce("${group}_15", "До лимита $label осталось 15 минут")
            remaining <= 30 -> fireOnce("${group}_30", "До лимита $label осталось 30 минут")
            group.startsWith("shift") && remaining <= 60 -> fireOnce("${group}_60", "До лимита $label остался 1 час")
        }
    }

    private fun fireOnce(key: String, text: String) {
        val p = prefs()
        val ackKey = "alert_ack_$key"
        val shownKey = "alert_shown_$key"
        if (p.getBoolean(ackKey, false) || p.getBoolean(shownKey, false)) return

        // Mark this threshold as shown before presenting it so every DTCO refresh
        // cannot create another copy of the same warning.
        // Persist synchronously before publishing the notification so the same
        // threshold cannot be re-fired by another service callback/reconnect race.
        p.edit().putBoolean(shownKey, true).commit()
        showAlert(key, text)
        speak(text)
    }

    private fun clearAlertGroup(group: String) {
        val p = prefs()
        val suffixes = listOf("30", "15", "5", "0", "60")
        val keys = buildList {
            suffixes.forEach { suffix ->
                add("alert_fired_${group}$suffix") // migration from older builds
                add("alert_ack_${group}$suffix")
                add("alert_shown_${group}$suffix")
            }
        }
        if (keys.none(p::contains)) return
        val editor = p.edit()
        keys.forEach(editor::remove)
        editor.commit()
    }

    private fun resetContinuousAlertCycle() {
        continuousAlertStage = 0
        lastPresentedContinuousAlertKey = null
        val p = prefs()
        val editor = p.edit().remove("alert_stage_cont")
        listOf("30", "15", "5", "0").forEach { suffix ->
            editor.remove("alert_fired_cont_$suffix")
            editor.remove("alert_ack_cont_$suffix")
            editor.remove("alert_shown_cont_$suffix")
        }
        editor.commit()
        try {
            getSystemService(NotificationManager::class.java)
                .cancel(ALERT_NOTIFICATION_ID)
        } catch (_: Throwable) {}
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tachowatch_${System.currentTimeMillis()}")
        } catch (_: Throwable) {}
    }

    private fun showAlert(key: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        try { manager.notify(ALERT_NOTIFICATION_ID, alertNotification(key, text)) } catch (_: Throwable) {}
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "TachoWatch фоновый контроль", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное Bluetooth-подключение к DTCO"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Предупреждения лимитов", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Голосовые и всплывающие предупреждения о лимитах работы и вождения"
                enableVibration(true)
            }
        )
    }

    private fun launchPendingIntent(): PendingIntent {
        val launch = Intent(this, DriverDashboardActivityV2::class.java)
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun serviceNotification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("TachoWatch")
        .setContentText(text)
        .setContentIntent(launchPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun alertNotification(key: String, text: String): Notification {
        val alertIntent = Intent(this, LimitAlertActivity::class.java)
            .putExtra(LimitAlertActivity.EXTRA_ALERT_KEY, key)
            .putExtra(LimitAlertActivity.EXTRA_ALERT_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val requestCode = key.hashCode() and 0x7fffffff
        val fullScreen = PendingIntent.getActivity(
            this,
            requestCode,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("TachoWatch • требуется подтверждение")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, serviceNotification(text)) } catch (_: Throwable) {}
    }

    private fun isRest(v: String) = v.contains("ОТДЫХ", true) || v.contains("ПЕРЕРЫВ", true)
    private fun isDriving(v: String) = v.contains("ВОЖДЕНИЕ", true)
    private fun isOtherWork(v: String) = v.contains("РАБОТА", true) && !isDriving(v)
    private fun isAvailability(v: String) = v.contains("ГОТОВНОСТЬ", true)

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun currentCycleBlock(log: String, cycle: Int): String? {
        val endMarker = "LIVE CYCLE #$cycle COMPLETE"
        val end = log.lastIndexOf(endMarker)
        if (end < 0) return null
        val before = log.substring(0, end)
        val start = if (cycle > 1) {
            val previous = before.lastIndexOf("LIVE CYCLE #${cycle - 1} COMPLETE")
            if (previous >= 0) previous else maxOf(before.lastIndexOf("LIVE START"), before.lastIndexOf("LIVE RECONNECT"))
        } else {
            maxOf(before.lastIndexOf("LIVE START"), before.lastIndexOf("LIVE RECONNECT"))
        }
        return log.substring(start.coerceAtLeast(0), end)
    }

    private fun last(log: String, did: String) = log.lines().asReversed().firstOrNull { it.startsWith("$did=") }?.substringAfter(" | ")?.trim()
    private fun mins(v: String?): Int? = v?.let { Regex("^(\\d+) мин").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
}
