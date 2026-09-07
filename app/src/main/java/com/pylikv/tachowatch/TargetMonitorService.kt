package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

class TargetMonitorService : Service(), DtcoTargetEventMonitor.Listener {

    data class DidSnapshot(
        val did: Int,
        val rawHex: String,
        val decoded: String,
        val changed: Boolean,
        val byteDiff: String,
        val timestamp: String
    )

    companion object {
        private const val CHANNEL_ID = "dtco_target_monitor"
        private const val NOTIFICATION_ID = 905
        private const val ACTION_START = "com.pylikv.tachowatch.MONITOR_START"
        private const val ACTION_STOP = "com.pylikv.tachowatch.MONITOR_STOP"
        private const val EXTRA_ADDRESS = "device_address"
        private const val PREFS = "target_monitor_service"
        private const val PREF_ACTIVE = "active"
        private const val PREF_ADDRESS = "address"

        private val listeners = CopyOnWriteArrayList<DtcoTargetEventMonitor.Listener>()
        private val lastDids = linkedMapOf<Int, DidSnapshot>()

        @Volatile private var monitorRef: DtcoTargetEventMonitor? = null
        @Volatile private var visibleLog: String = ""
        @Volatile private var isConnected: Boolean = false
        @Volatile private var deviceName: String? = null
        @Volatile private var running: Boolean = false

        fun start(context: Context, address: String) {
            val i = Intent(context, TargetMonitorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ADDRESS, address)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TargetMonitorService::class.java).setAction(ACTION_STOP))
        }

        fun registerListener(listener: DtcoTargetEventMonitor.Listener) {
            if (!listeners.contains(listener)) listeners.add(listener)
            listener.onConnectionStateChanged(isConnected, deviceName)
            if (visibleLog.isNotBlank()) listener.onLogChanged(visibleLog)
            synchronized(lastDids) {
                lastDids.values.forEach { s ->
                    listener.onDidUpdate(s.did, s.rawHex, s.decoded, s.changed, s.byteDiff, s.timestamp)
                }
            }
        }

        fun unregisterListener(listener: DtcoTargetEventMonitor.Listener) {
            listeners.remove(listener)
        }

        fun exportCurrentLog(out: OutputStream): Boolean = monitorRef?.exportCurrentLog(out) ?: false
        fun getCurrentLogFileName(): String? = monitorRef?.getCurrentLogFileName()
        fun addMarker(text: String) = monitorRef?.addMarker(text)
        fun manualGattCheck() = monitorRef?.manualGattCheck()
        fun clearVisibleLog() = monitorRef?.clearLog()
        fun isRunning(): Boolean = running
        fun isConnected(): Boolean = isConnected
    }

    private val mainHandler by lazy { android.os.Handler(mainLooper) }
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempts = 0
    private var stopping = false
    private var sessionStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Мониторинг запускается…"))
        running = true
        stopping = false
        monitorRef = DtcoTargetEventMonitor(applicationContext, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (!address.isNullOrBlank()) {
                    stopping = false
                    reconnectAttempts = 0
                    sessionStarted = false
                    visibleLog = ""
                    synchronized(lastDids) { lastDids.clear() }
                    prefs().edit().putBoolean(PREF_ACTIVE, true).putString(PREF_ADDRESS, address).apply()
                    connectAddress(address, newSession = true)
                }
            }
            else -> {
                val p = prefs()
                if (p.getBoolean(PREF_ACTIVE, false)) {
                    p.getString(PREF_ADDRESS, null)?.let {
                        sessionStarted = false
                        connectAddress(it, newSession = true)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        monitorRef?.shutdown()
        monitorRef = null
        running = false
        isConnected = false
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun connectAddress(address: String, newSession: Boolean) {
        if (stopping) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            onLogChanged("[SERVICE] Нет разрешения BLUETOOTH_CONNECT")
            updateNotification("Нет разрешения Bluetooth")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = try { adapter?.getRemoteDevice(address) } catch (_: Throwable) { null }
        if (device == null) {
            onLogChanged("[SERVICE] Не удалось получить Bluetooth-устройство $address")
            scheduleReconnect(address)
            return
        }
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        updateNotification(if (newSession) "Подключение к DTCO…" else "Переподключение к DTCO…")
        monitorRef?.connect(device, newSession = newSession || !sessionStarted)
        sessionStarted = true
    }

    private fun scheduleReconnect(address: String) {
        if (stopping || !prefs().getBoolean(PREF_ACTIVE, false)) return
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectAttempts++
        val delay = when {
            reconnectAttempts <= 3 -> 3_000L
            reconnectAttempts <= 10 -> 10_000L
            else -> 30_000L
        }
        val r = Runnable {
            reconnectRunnable = null
            if (!stopping && prefs().getBoolean(PREF_ACTIVE, false)) {
                connectAddress(address, newSession = false)
            }
        }
        reconnectRunnable = r
        mainHandler.postDelayed(r, delay)
        updateNotification("Связь потеряна • переподключение через ${delay / 1000}с")
    }

    private fun stopMonitoring() {
        stopping = true
        prefs().edit().putBoolean(PREF_ACTIVE, false).apply()
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        monitorRef?.disconnect()
        isConnected = false
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLogChanged(fullLogValue: String) {
        visibleLog = fullLogValue
        listeners.forEach { it.onLogChanged(fullLogValue) }
    }

    override fun onConnectionStateChanged(connected: Boolean, name: String?) {
        isConnected = connected
        deviceName = name
        if (connected) {
            reconnectAttempts = 0
            updateNotification("DTCO подключён • мониторинг идёт")
        } else {
            updateNotification("DTCO отключён • восстановление связи")
            if (!stopping) prefs().getString(PREF_ADDRESS, null)?.let { scheduleReconnect(it) }
        }
        listeners.forEach { it.onConnectionStateChanged(connected, name) }
    }

    override fun onDidUpdate(did: Int, rawHex: String, decoded: String, changed: Boolean, byteDiff: String, timestamp: String) {
        val s = DidSnapshot(did, rawHex, decoded, changed, byteDiff, timestamp)
        synchronized(lastDids) { lastDids[did] = s }
        listeners.forEach { it.onDidUpdate(did, rawHex, decoded, changed, byteDiff, timestamp) }
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DTCO мониторинг",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянный фоновый мониторинг тахографа"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val launch = Intent(this, EventScannerActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DTCO Live DID Monitor v9.5")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        } catch (_: Throwable) {}
    }
}
