package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Read-only Remote HMI diagnostics reader.
 *
 * The transport/service UUIDs are the Smart Tachograph V2 Diagnostics service.
 * We only open the Remote HMI diagnostic routine and issue ReadDataByIdentifier
 * requests (SID 0x22). No activity, place or manual-entry write operations are used.
 *
 * Important source rule:
 * - direct tachograph timer DIDs are authoritative when supported;
 * - legacy F9xx values remain available for old units/fallback logic.
 */
class LiveDidDiagnostic(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onLiveLog(log: String)
        fun onLiveConnection(connected: Boolean, deviceName: String?)
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE = UUID.fromString("fa213def-aef4-475c-bcea-0a8d69073efc")
        private val FIFO = UUID.fromString("e413960c-75ba-4ca9-8a67-99bc052a1b13")
        private val CREDITS = UUID.fromString("e168d1a6-304f-42b4-ab96-4cd1d4efebd9")
        private const val RESPONSE_TIMEOUT = 3000L
        private const val POLL_INTERVAL = 0L
        private const val RECONNECT_MIN_DELAY = 1500L
        private const val RECONNECT_MAX_DELAY = 15000L
        private const val WATCHDOG_INTERVAL = 5000L
        private const val STALE_TIMEOUT = 20000L
    }

    /*
     * Mandatory/established live values first, then ISO 16844-7 optional driver-1
     * derived timers used by current DTCO generations and mirrored by VDO Fleet.
     * Unsupported optional DIDs are simply skipped on NRC from SID 0x22.
     */
    private val dids = intArrayOf(
        0xF903, // Driver1WorkingState
        0xF905, // DriveRecognize
        0xF906, // Driver1TimeRelatedStates
        0xF923, // Driver1ContinuousDrivingTime
        0xF925, // Driver1CumulativeBreakTime
        0xF927, // Driver1CurrentDurationOfSelectedActivity
        0xF931, // Driver1Name
        0xF938, // Driver1CumulatedDrivingTimePreviousAndCurrentWeek

        0xF99A, // Driver1CurrentDailyDrivingTime
        0xF99B, // Driver1CurrentWeeklyDrivingTime
        0xF99C, // Driver1TimeLeftUntilNewDailyRestPeriod
        0xF9A0, // Driver1NumberOfTimes9hDailyDrivingTimesExceeded
        0xF9A1, // Driver1TimeLeftUntilNewWeeklyRestPeriod
        0xF9A2, // Driver1CumulativeUninterruptedRestTime
        0xF9A3, // Driver1MinimumDailyRest
        0xF9A4, // Driver1MinimumWeeklyRest
        0xF9A5, // Driver1MaximumDailyPeriod
        0xF9A6, // Driver1MaximumDailyDrivingTime
        0xF9AB, // Driver1NumberOfUsedReducedDailyRestPeriods
        0xF9AD, // Driver1RemainingCurrentDrivingTime
        0xF9AF, // Driver1RemainingDrivingTimeOnCurrentShift
        0xF9B1, // Driver1RemainingDrivingTimeOfCurrentWeek
        0xF9B3, // Driver1Remaining2WeeksDrivingTime
        0xF9B5, // Driver1TimeLeftUntilNextDrivingPeriod
        0xF9B7, // Driver1DurationOfNextDrivingPeriod
        0xF9B9, // Driver1DurationOfNextBreakRest
        0xF9C0, // Driver1RemainingTimeOfCurrentBreakRest
        0xF9C2  // Driver1RemainingTimeUntilNextBreakOrRest
    )

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var connected = false
    private var txCredits = 0
    private var openSent = false
    private var statusSent = false
    private var rhmi = false
    private var index = 0
    private var waiting = false
    private var token = 0L
    private var cycle = 0
    private val lines = ArrayList<String>()

    private var targetDevice: BluetoothDevice? = null
    private var reconnectEnabled = false
    private var reconnectAttempt = 0
    private var reconnectGeneration = 0L
    private var lastFreshDataAt = 0L
    private var connectionStartedAt = 0L
    private var watchdogStarted = false

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasPermission()) return
        targetDevice = device
        reconnectEnabled = true
        reconnectAttempt = 0
        reconnectGeneration++
        ensureWatchdog()
        startConnection(device, clearLog = true)
    }

    @SuppressLint("MissingPermission")
    private fun startConnection(device: BluetoothDevice, clearLog: Boolean) {
        if (!hasPermission() || !reconnectEnabled) return
        closeCurrentGatt(notify = false)
        if (clearLog) lines.clear()
        txCredits = 0
        openSent = false
        statusSent = false
        rhmi = false
        index = 0
        waiting = false
        cycle = 0
        token++
        lastFreshDataAt = 0L
        connectionStartedAt = System.currentTimeMillis()
        log(if (clearLog) "LIVE START" else "LIVE RECONNECT #$reconnectAttempt")
        gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context, false, cb)
        } catch (_: Throwable) {
            null
        }
        if (gatt == null) scheduleReconnect()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        reconnectEnabled = false
        targetDevice = null
        reconnectGeneration++
        watchdogStarted = false
        handler.removeCallbacksAndMessages(null)
        closeCurrentGatt(notify = true)
    }

    @SuppressLint("MissingPermission")
    private fun closeCurrentGatt(notify: Boolean) {
        token++
        val old = gatt
        gatt = null
        connected = false
        waiting = false
        try { old?.disconnect() } catch (_: Throwable) {}
        try { old?.close() } catch (_: Throwable) {}
        if (notify) listener.onLiveConnection(false, null)
    }

    private fun ensureWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        handler.post(object : Runnable {
            override fun run() {
                if (!watchdogStarted || !reconnectEnabled) return
                val now = System.currentTimeMillis()
                if (connected) {
                    val base = if (lastFreshDataAt > 0L) lastFreshDataAt else connectionStartedAt
                    if (base > 0L && now - base >= STALE_TIMEOUT) {
                        log("LIVE WATCHDOG stale=${now - base}ms • restarting connection")
                        forceRecovery()
                    }
                }
                if (watchdogStarted && reconnectEnabled) handler.postDelayed(this, WATCHDOG_INTERVAL)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun forceRecovery() {
        val device = targetDevice ?: return
        if (!reconnectEnabled) return
        reconnectGeneration++
        reconnectAttempt = (reconnectAttempt + 1).coerceAtLeast(1)
        closeCurrentGatt(notify = false)
        listener.onLiveConnection(false, null)
        handler.postDelayed({
            if (reconnectEnabled && targetDevice == device) startConnection(device, clearLog = false)
        }, RECONNECT_MIN_DELAY)
    }

    private fun scheduleReconnect() {
        val device = targetDevice ?: return
        if (!reconnectEnabled || !hasPermission()) return
        val generation = ++reconnectGeneration
        reconnectAttempt++
        val shift = (reconnectAttempt - 1).coerceIn(0, 4)
        val delay = (RECONNECT_MIN_DELAY * (1L shl shift)).coerceAtMost(RECONNECT_MAX_DELAY)
        handler.postDelayed({
            if (!reconnectEnabled || generation != reconnectGeneration || connected) return@postDelayed
            startConnection(device, clearLog = false)
        }, delay)
    }

    private fun markFresh() {
        lastFreshDataAt = System.currentTimeMillis()
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != g && newState != BluetoothProfile.STATE_CONNECTED) {
                try { g.close() } catch (_: Throwable) {}
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectGeneration++
                reconnectAttempt = 0
                connected = true
                gatt = g
                connectionStartedAt = System.currentTimeMillis()
                lastFreshDataAt = 0L
                listener.onLiveConnection(true, try { g.device.name } catch (_: Throwable) { "DTCO" })
                try { if (!g.requestMtu(512)) g.discoverServices() } catch (_: Throwable) { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (gatt == g) gatt = null
                connected = false
                waiting = false
                token++
                try { g.close() } catch (_: Throwable) {}
                listener.onLiveConnection(false, null)
                scheduleReconnect()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("LIVE ERROR service discovery status=$status")
                forceRecovery()
                return
            }
            val s = g.getService(SERVICE) ?: run {
                log("LIVE ERROR service not found")
                forceRecovery()
                return
            }
            subscribe(g, s.getCharacteristic(FIFO))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("LIVE ERROR descriptor status=$status")
                forceRecovery()
                return
            }
            if (d.characteristic.uuid == FIFO) {
                val c = g.getService(SERVICE)?.getCharacteristic(CREDITS) ?: return
                handler.postDelayed({ subscribe(g, c) }, 120)
            } else if (d.characteristic.uuid == CREDITS) {
                handler.postDelayed({ grant(g) }, 180)
            }
        }

        @Deprecated("legacy")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) incoming(g, c, c.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming(g, c, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        else {
            @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            @Suppress("DEPRECATION") g.writeDescriptor(d)
        }
    }

    private fun incoming(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
        if (c.uuid == CREDITS) {
            if (v.isEmpty()) return
            txCredits += u(v[0])
            if (!openSent && txCredits > 0) sendOpen(g)
            return
        }
        if (c.uuid != FIFO || v.size < 3) return
        val a = v.copyOfRange(2, v.size)

        if (a.size >= 4 && u(a[0]) == 0x71 && u(a[1]) == 1 && u(a[2]) == 0xF2 && u(a[3]) == 0x11) {
            grant(g)
            handler.postDelayed({ sendStatus(g) }, 180)
            return
        }
        if (a.size >= 5 && u(a[0]) == 0x71 && u(a[1]) == 3 && u(a[2]) == 0xF2 && u(a[3]) == 0x11) {
            if (u(a[4]) == 0x10 && !rhmi) {
                rhmi = true
                markFresh()
                handler.postDelayed({ requestNext(g) }, 180)
            }
            return
        }
        if (a.size >= 3 && u(a[0]) == 0x62) {
            val did = (u(a[1]) shl 8) or u(a[2])
            val data = if (a.size > 3) a.copyOfRange(3, a.size) else byteArrayOf()
            markFresh()
            log("${hex4(did)}=${hex(data)} | ${decode(did, data)}", notify = false)
            if (waiting && index < dids.size && did == dids[index]) {
                waiting = false
                token++
                index++
                handler.postDelayed({ requestNext(g) }, 140)
            }
            return
        }
        if (a.size >= 3 && u(a[0]) == 0x7F && u(a[1]) == 0x22 && waiting) {
            // Optional ISO DIDs are not guaranteed on every tachograph. NRC means
            // unsupported/unavailable here, not a broken live session.
            markFresh()
            waiting = false
            token++
            index++
            handler.postDelayed({ requestNext(g) }, 140)
        }
    }

    private fun requestNext(g: BluetoothGatt) {
        if (!connected || !rhmi || waiting || gatt != g) return
        if (index >= dids.size) {
            cycle++
            markFresh()
            log("LIVE CYCLE #$cycle COMPLETE")
            val t = ++token
            handler.postDelayed({
                if (!connected || !rhmi || t != token || gatt != g) return@postDelayed
                index = 0
                waiting = false
                requestNext(g)
            }, POLL_INTERVAL)
            return
        }

        grant(g)
        handler.postDelayed({
            if (!connected || waiting || index >= dids.size || gatt != g) return@postDelayed
            if (txCredits <= 0) {
                handler.postDelayed({ requestNext(g) }, 250)
                return@postDelayed
            }
            val did = dids[index]
            val fifo = g.getService(SERVICE)?.getCharacteristic(FIFO) ?: return@postDelayed
            val ok = writeNoResponse(g, fifo, byteArrayOf(1, 1, 0x22, (did shr 8).toByte(), did.toByte()))
            if (ok) {
                txCredits--
                waiting = true
                val t = ++token
                handler.postDelayed({
                    if (t == token && waiting && gatt == g) {
                        waiting = false
                        index++
                        requestNext(g)
                    }
                }, RESPONSE_TIMEOUT)
            } else {
                handler.postDelayed({ requestNext(g) }, 250)
            }
        }, 180)
    }

    private fun sendOpen(g: BluetoothGatt) {
        val fifo = g.getService(SERVICE)?.getCharacteristic(FIFO) ?: return
        if (writeNoResponse(g, fifo, byteArrayOf(1, 1, 0x31, 1, 0xF2.toByte(), 0x11))) {
            openSent = true
            txCredits--
        }
    }

    private fun sendStatus(g: BluetoothGatt) {
        if (statusSent || txCredits <= 0) return
        val fifo = g.getService(SERVICE)?.getCharacteristic(FIFO) ?: return
        if (writeNoResponse(g, fifo, byteArrayOf(1, 1, 0x31, 3, 0xF2.toByte(), 0x11))) {
            statusSent = true
            txCredits--
        }
    }

    private fun grant(g: BluetoothGatt) {
        val c = g.getService(SERVICE)?.getCharacteristic(CREDITS) ?: return
        writeNoResponse(g, c, byteArrayOf(1))
    }

    @SuppressLint("MissingPermission")
    private fun writeNoResponse(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33)
            g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        else {
            @Suppress("DEPRECATION") c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION") c.value = v
            @Suppress("DEPRECATION") g.writeCharacteristic(c)
        }
    } catch (_: Throwable) {
        false
    }

    private fun decode(did: Int, b: ByteArray): String = when (did) {
        0xF903 -> if (b.isEmpty()) "—" else when (u(b[0]) and 7) {
            0 -> "ОТДЫХ / ПЕРЕРЫВ"
            1 -> "ГОТОВНОСТЬ"
            2 -> "ДРУГАЯ РАБОТА"
            3 -> "ВОЖДЕНИЕ"
            else -> "КОД ${u(b[0]) and 7}"
        }
        0xF905 -> if (b.isEmpty()) "—" else "motion=${u(b[0])}"
        0xF906 -> if (b.isEmpty()) "—" else "warning=${u(b[0]) and 0x0F}"
        0xF931 -> if (b.size >= 72) "${clean(b.copyOfRange(0, 36))} ${clean(b.copyOfRange(36, 72))}".trim() else clean(b)
        0xF9A0, 0xF9AB -> if (b.isEmpty()) "—" else "${u(b[0])} count"
        in minuteDids -> minuteValue(b)
        else -> hex(b)
    }

    private val minuteDids = setOf(
        0xF923, 0xF925, 0xF927, 0xF938,
        0xF99A, 0xF99B, 0xF99C, 0xF9A1, 0xF9A2, 0xF9A3, 0xF9A4, 0xF9A5, 0xF9A6,
        0xF9AD, 0xF9AF, 0xF9B1, 0xF9B3, 0xF9B5, 0xF9B7, 0xF9B9, 0xF9C0, 0xF9C2
    )

    private fun minuteValue(b: ByteArray): String {
        if (b.size < 2) return "—"
        val m = (u(b[0]) shl 8) or u(b[1])
        return "$m мин = ${m / 60}:${String.format("%02d", m % 60)}"
    }

    private fun clean(b: ByteArray) = buildString {
        b.forEach {
            val x = u(it)
            if (x in 32..126) append(x.toChar()) else if (x == 0) append(' ')
        }
    }.trim()

    private fun log(s: String, notify: Boolean = true) {
        lines.add(s)
        while (lines.size > 500) lines.removeAt(0)
        if (notify) listener.onLiveLog(lines.joinToString("\n"))
    }

    private fun u(b: Byte) = b.toInt() and 0xFF
    private fun hex(b: ByteArray) = b.joinToString(" ") { String.format("%02X", u(it)) }
    private fun hex4(v: Int) = String.format("%04X", v and 0xFFFF)
}
