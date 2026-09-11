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
 * Owns the live DTCO connection independently from the Activity.
 * The service persists all derived counters after every completed live cycle,
 * so Android can destroy/recreate the UI without losing driver state.
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
            else -> {
                if (!pausedForCardRead) prefs().getString(SELECTED_DTCO, null)?.let(::connectAddress)
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
        // START_STICKY handles recreation. State is already persisted every cycle.
        super.onTaskRemoved(rootIntent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val ru = engine.setLanguage(Locale("ru", "RU"))
            ttsReady = ru != TextToSpeech.LANG_MISSING_DATA && ru != TextToSpeech.LANG_NOT_SUPPORTED
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
        updateServiceNotification(if (isConnected) "DTCO подключён • контроль лимитов активен" else "Связь потеряна • переподключение…")
        listeners.forEach { it.onLiveConnection(isConnected, name) }
    }

    override fun onLiveLog(log: String) {
        fullLog = log
        last(log, "F903")?.let { currentActivity = it }
        mins(last(log, "F927"))?.let { activityMinutes = it }
        mins(last(log, "F923"))?.let { continuousMinutes = it }
        mins(last(log, "F925"))?.let { breakMinutes = it }
        mins(last(log, "F938"))?.let { twoWeekMinutes = it }

        val cycle = Regex("LIVE CYCLE #(\\d+) COMPLETE").findAll(log).lastOrNull()
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (cycle != null && cycle > lastProcessedCycle) {
            lastProcessedCycle = cycle
            processCycle()
            persistState()
            evaluateAlerts()
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
            .putString(WORK_PREV_ACTIVITY, previousActivity)
            .putInt(WORK_PREV_DURATION, previousActivityDuration)
            .putInt(WORK_ACC, otherWorkWindowMinutes)
            .putInt(AVAIL_ACC, availabilityWindowMinutes)
            .apply()
    }

    private fun processCycle() {
        if (!shiftCounterInitialized) {
            shiftCompletedMinutes = 0
            previousContinuousMinutes = continuousMinutes
            shiftCounterInitialized = true
        } else if (previousContinuousMinutes > 0 && continuousMinutes < previousContinuousMinutes) {
            shiftCompletedMinutes += previousContinuousMinutes
        }
        previousContinuousMinutes = continuousMinutes
        processWorkWindow()
    }

    private fun processWorkWindow() {
        val restReached45 = currentActivity.contains("ОТДЫХ") && maxOf(breakMinutes, activityMinutes) >= 45
        if (restReached45) {
            workWindowMinutes = 0
            otherWorkWindowMinutes = 0
            availabilityWindowMinutes = 0
            previousActivity = currentActivity
            previousActivityDuration = activityMinutes
            clearAlertGroup("cont_")
            clearAlertGroup("work_")
            return
        }
        if (previousActivity != currentActivity) {
            val finished = previousActivityDuration.coerceAtLeast(0)
            when {
                previousActivity.contains("ВОЖДЕНИЕ") -> workWindowMinutes += finished
                previousActivity.contains("РАБОТА") -> {
                    workWindowMinutes += finished
                    otherWorkWindowMinutes += finished
                }
                previousActivity.contains("ГОТОВНОСТЬ") -> availabilityWindowMinutes += finished
            }
            previousActivity = currentActivity
            previousActivityDuration = activityMinutes
        } else {
            previousActivityDuration = activityMinutes
        }
    }

    private fun activeWorkTotal(): Int = workWindowMinutes +
        if (currentActivity.contains("ВОЖДЕНИЕ") || currentActivity.contains("РАБОТА")) activityMinutes else 0

    private fun shiftDrivingTotal(): Int = shiftCompletedMinutes + continuousMinutes

    private fun evaluateAlerts() {
        evaluateRemaining("cont", 270 - continuousMinutes, "непрерывного вождения", "Лимит непрерывного вождения 4 часа 30 минут достигнут")
        evaluateRemaining("work", 360 - activeWorkTotal(), "непрерывной работы", "Лимит непрерывной работы 6 часов достигнут")

        val shift = shiftDrivingTotal()
        // 9 h is the normal daily driving limit. 10 h is handled as the extension ceiling.
        evaluateRemaining("shift9", 540 - shift, "суточного вождения 9 часов", "Лимит суточного вождения 9 часов достигнут. При допустимом продлении остаётся до 10 часов")
        if (shift >= 540) evaluateRemaining("shift10", 600 - shift, "продлённого суточного вождения 10 часов", "Лимит продлённого суточного вождения 10 часов достигнут")
    }

    private fun evaluateRemaining(group: String, remaining: Int, label: String, reachedText: String) {
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
        val editor = prefs().edit()
        listOf("30", "15", "5", "0", "60").forEach { editor.remove("alert_fired_${group}$it") }
        editor.apply()
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tachowatch_${System.currentTimeMillis()}") } catch (_: Throwable) {}
    }

    private fun showAlert(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        try {
            manager.notify(ALERT_NOTIFICATION_ID, alertNotification(text))
        } catch (_: Throwable) {}
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "TachoWatch фоновый контроль", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Постоянное Bluetooth-подключение к DTCO"
            setShowBadge(false)
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Предупреждения лимитов", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Голосовые и всплывающие предупреждения о лимитах работы и вождения"
            enableVibration(true)
        })
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

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun last(log: String, did: String) = log.lines().asReversed().firstOrNull { it.startsWith("$did=") }?.substringAfter(" | ")?.trim()
    private fun mins(v: String?): Int? = v?.let { Regex("^(\\d+) мин").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
}
