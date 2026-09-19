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
        private const val ALERT_NOTIFICATION_ID = 1402

        const val PREFS = "tachowatch_auto_card"
        const val SELECTED_DTCO = "selected_dtco_address"
        const val SHIFT_INITIALIZED = "shift_counter_initialized"
        const val SHIFT_COMPLETED = "shift_completed_driving"
        const val SHIFT_PREV_CONTINUOUS = "shift_prev_continuous"
        const val WORK_WINDOW = "work_window_minutes"
        const val CW_OTHER_WINDOW = "continuous_work_other_minutes"
        const val CW_PREV_ACTIVITY = "continuous_work_prev_activity"
        const val CW_PREV_DURATION = "continuous_work_prev_duration"
        const val WORK_PREV_ACTIVITY = "work_prev_activity"
        const val WORK_PREV_DURATION = "work_prev_duration"
        const val WORK_ACC = "other_work_window_minutes"
        const val AVAIL_ACC = "availability_window_minutes"

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
                live.disconnect()
                updateServiceNotification("Считывание карты • live временно приостановлен")
            }
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (!address.isNullOrBlank()) {
                    prefs().edit().putString(SELECTED_DTCO, address).apply()
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

            if (freshActivity != null && freshActivityMinutes != null && freshContinuous != null && freshBreak != null) {
                currentActivity = freshActivity
                activityMinutes = freshActivityMinutes
                continuousMinutes = freshContinuous
                breakMinutes = freshBreak
                block?.let { mins(last(it, "F938")) }?.let { twoWeekMinutes = it }

                processCycle()
                persistState()
                evaluateAlerts()
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
        breakMinutes = p.getInt(SNAP_BREAK_MIN, 0)
        twoWeekMinutes = p.getInt(SNAP_TWO_WEEK_MIN, 0)
        shiftCounterInitialized = p.getBoolean(SHIFT_INITIALIZED, false)
        shiftCompletedMinutes = p.getInt(SHIFT_COMPLETED, 0)
        previousContinuousMinutes = p.getInt(SHIFT_PREV_CONTINUOUS, 0)
        workWindowMinutes = p.getInt(WORK_WINDOW, 0)
        continuousWorkOtherMinutes = p.getInt(CW_OTHER_WINDOW, 0)
        continuousWorkPreviousActivity = p.getString(CW_PREV_ACTIVITY, "—") ?: "—"
        continuousWorkPreviousDuration = p.getInt(CW_PREV_DURATION, 0)
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
            .putString(WORK_PREV_ACTIVITY, previousActivity)
            .putInt(WORK_PREV_DURATION, previousActivityDuration)
            .putInt(WORK_ACC, otherWorkWindowMinutes)
            .putInt(AVAIL_ACC, availabilityWindowMinutes)
            .apply()
    }

    private fun processCycle() {
        val restNow = isRest(currentActivity)
        val restMinutes = if (restNow) maxOf(activityMinutes, breakMinutes) else 0
        val dailyRestCompleted = restMinutes >= 9 * 60

        val shift = ShiftDrivingCounter.update(
            initialized = shiftCounterInitialized,
            totalMinutes = shiftCompletedMinutes,
            previousContinuousMinutes = previousContinuousMinutes,
            currentContinuousMinutes = continuousMinutes,
            dailyRestCompleted = dailyRestCompleted
        )
        shiftCounterInitialized = shift.initialized
        shiftCompletedMinutes = shift.totalMinutes
        previousContinuousMinutes = shift.previousContinuousMinutes

        val previousContinuousOtherWork = continuousWorkOtherMinutes
        val cw = ContinuousWorkCounter.update(
            state = ContinuousWorkCounter.State(
                workMinutes = workWindowMinutes,
                otherWorkMinutes = continuousWorkOtherMinutes,
                previousActivity = continuousWorkPreviousActivity,
                previousSourceMinutes = continuousWorkPreviousDuration
            ),
            currentActivity = currentActivity,
            activityMinutes = activityMinutes,
            continuousDrivingMinutes = continuousMinutes,
            qualifyingRestMinutes = restMinutes
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

        if (dailyRestCompleted) {
            workWindowMinutes = 0
            continuousWorkOtherMinutes = 0
            continuousWorkPreviousActivity = currentActivity
            continuousWorkPreviousDuration = activitySourceMinutes()
            otherWorkWindowMinutes = 0
            availabilityWindowMinutes = 0
            previousActivity = currentActivity
            previousActivityDuration = activitySourceMinutes()
            clearAlertGroup("cont_")
            clearAlertGroup("work_")
            clearAlertGroup("shift9_")
            clearAlertGroup("shift10_")
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

    private fun evaluateAlerts() {
        evaluateRemaining("cont", 270 - continuousMinutes, "непрерывного вождения", "Лимит непрерывного вождения 4 часа 30 минут достигнут")
        evaluateRemaining("work", 360 - activeWorkTotal(), "непрерывной работы", "Лимит непрерывной работы 6 часов достигнут")

        val shift = shiftDrivingTotal()
        evaluateRemaining("shift9", 540 - shift, "суточного вождения 9 часов", "Лимит суточного вождения 9 часов достигнут. При допустимом продлении остаётся до 10 часов")
        if (shift >= 540) {
            evaluateRemaining("shift10", 600 - shift, "продлённого суточного вождения 10 часов", "Лимит продлённого суточного вождения 10 часов достигнут")
        }
    }

    private fun evaluateRemaining(group: String, remaining: Int, label: String, reachedText: String) {
        // Rearm alerts only after the monitored counter itself has moved back outside
        // the warning zone. Do not use the last break duration for rearming: some DTCO
        // live frames can keep reporting the completed 45-minute break value after
        // driving resumes, which previously cleared fireOnce() on every live cycle.
        val rearmAbove = if (group.startsWith("shift")) 60 else 30
        if (remaining > rearmAbove) {
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
        val prefKey = "alert_fired_$key"
        if (p.getBoolean(prefKey, false)) return
        p.edit().putBoolean(prefKey, true).apply()
        showAlert(text)
        speak(text)
    }

    private fun clearAlertGroup(group: String) {
        val p = prefs()
        val keys = listOf("30", "15", "5", "0", "60").map { "alert_fired_${group}$it" }
        if (keys.none(p::contains)) return
        val editor = p.edit()
        keys.forEach(editor::remove)
        editor.apply()
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

    private fun showAlert(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        try { manager.notify(ALERT_NOTIFICATION_ID, alertNotification(text)) } catch (_: Throwable) {}
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

    private fun alertNotification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle("TachoWatch • предупреждение")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(launchPendingIntent())
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setAutoCancel(true)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .build()

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
