package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import androidx.core.content.ContextCompat
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class DtcoTargetEventMonitor(private val context: Context, private val listener: Listener? = null) {
    interface Listener {
        fun onLogChanged(fullLog: String)
        fun onConnectionStateChanged(connected: Boolean, deviceName: String?)
        fun onDidUpdate(did: Int, rawHex: String, decoded: String, changed: Boolean, byteDiff: String, timestamp: String) {}
    }

    companion object {
        private const val VERSION = "DTCO-LIVE-DID-v9.6"
        private const val NEXT_CYCLE_MS = 15000L
        private const val RESPONSE_TIMEOUT_MS = 3500L
        private const val MAX_LOG_LINES = 20000
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE = UUID.fromString("fa213def-aef4-475c-bcea-0a8d69073efc")
        private val FIFO = UUID.fromString("e413960c-75ba-4ca9-8a67-99bc052a1b13")
        private val CREDITS = UUID.fromString("e168d1a6-304f-42b4-ab96-4cd1d4efebd9")
    }

    // Control channels + ISO 16844-7 driver-1 target block.
    private val dids = intArrayOf(
        0xF923, 0xF925, 0xF927, 0xF938,
        0xF997, 0xF998, 0xF999, 0xF99A, 0xF99B, 0xF99C,
        0xF99D, 0xF99E, 0xF99F,
        0xF9A0, 0xF9A1, 0xF9A2, 0xF9A3, 0xF9A4, 0xF9A5
    )

    private val names = mapOf(
        0xF923 to "CONTROL continuous driving",
        0xF925 to "CONTROL cumulative break/rest",
        0xF927 to "CONTROL selected activity duration",
        0xF938 to "CONTROL previous+current week driving",
        0xF997 to "Driver1EndOfLastDailyRestPeriod",
        0xF998 to "Driver1EndOfLastWeeklyRestPeriod",
        0xF999 to "Driver1EndOfSecondLastWeeklyRestPeriod",
        0xF99A to "Driver1CurrentDailyDrivingTime",
        0xF99B to "Driver1CurrentWeeklyDrivingTime",
        0xF99C to "Driver1TimeLeftUntilNewDailyRestPeriod",
        0xF99D to "Driver1CardExpiryDate",
        0xF99E to "Driver1CardNextMandatoryDownloadDate",
        0xF99F to "TachographNextMandatoryDownloadDate",
        0xF9A0 to "Driver1NumberOfTimes9hDailyDrivingTimesExceeded",
        0xF9A1 to "Driver1TimeLeftUntilNewWeeklyRestPeriod",
        0xF9A2 to "Driver1CumulativeUninterruptedRestTime",
        0xF9A3 to "Driver1MinimumDailyRest",
        0xF9A4 to "Driver1MinimumWeeklyRest",
        0xF9A5 to "Driver1MaximumDailyPeriod"
    )

    private val handler = Handler(Looper.getMainLooper())
    private val lines = CopyOnWriteArrayList<String>()
    private val previous = linkedMapOf<Int, ByteArray>()
    private val fileLock = Any()
    private var logFile: File? = null
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var connected = false
    private var fifoSubscribed = false
    private var creditsSubscribed = false
    private var openSent = false
    private var statusSent = false
    private var rhmiOpen = false
    private var index = 0
    private var cycle = 0
    private var waitingDid: Int? = null
    private var timeoutToken = 0L
    private var connectionNumber = 0

    fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun connect(d: BluetoothDevice) {
        if (!hasConnectPermission()) { log("ERROR: no BLUETOOTH_CONNECT"); return }
        closeGatt()
        if (logFile == null) {
            lines.clear()
            startLogFile()
            log("============================================================")
            log("$VERSION")
            log("READ ONLY: UDS 0x22 only")
            log("ONE SESSION = ONE FILE; Bluetooth reconnects append to the same file")
            log("AUTOSAVE: every log line is appended immediately to ${getCurrentLogFileName() ?: "internal file"}")
            log("TARGET DIDs: ${dids.joinToString { did(it) }}")
            log("Primary target: F997-F99C plus neighbouring ISO driver-1 DIDs")
            log("Controls retained: F923/F925/F927/F938")
            log("Cycle interval: ${NEXT_CYCLE_MS / 1000}s")
            log("============================================================")
        }
        previous.clear()
        device = d
        connected = false
        fifoSubscribed = false
        creditsSubscribed = false
        openSent = false
        statusSent = false
        rhmiOpen = false
        index = 0
        waitingDid = null
        timeoutToken++
        connectionNumber++
        log("========== BLE CONNECT ATTEMPT #$connectionNumber ${safeName(d)} ==========")
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) d.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            else d.connectGatt(context, false, cb)
        } catch (e: Throwable) {
            log("connectGatt ERROR ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startLogFile() {
        synchronized(fileLock) {
            val dir = File(context.filesDir, "target_scanner_logs")
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            logFile = File(dir, "DTCO_LIVE_DID_v9_6_$stamp.txt")
            try { logFile?.createNewFile() } catch (_: Throwable) { logFile = null }
        }
    }

    fun getCurrentLogFileName(): String? = synchronized(fileLock) { logFile?.name }

    fun exportCurrentLog(out: OutputStream): Boolean {
        return try {
            val f = synchronized(fileLock) { logFile }
            if (f != null && f.exists()) f.inputStream().use { input -> input.copyTo(out) }
            else out.write(getLog().toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (_: Throwable) { false }
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("GATT STATE status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                gatt = g
                listener?.onConnectionStateChanged(true, safeName(g.device))
                try { if (!g.requestMtu(240)) g.discoverServices() } catch (_: Throwable) { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                rhmiOpen = false
                timeoutToken++
                waitingDid = null
                listener?.onConnectionStateChanged(false, safeName(g.device))
                log("BLE DISCONNECTED; session file preserved for reconnect")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { log("MTU=$mtu status=$status"); g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("SERVICES status=$status count=${g.services.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            subscribe(g, FIFO)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("CCCD ${d.characteristic.uuid} status=$status")
            if (d.characteristic.uuid == FIFO) {
                fifoSubscribed = status == BluetoothGatt.GATT_SUCCESS
                handler.postDelayed({ subscribe(g, CREDITS) }, 180)
            } else if (d.characteristic.uuid == CREDITS) {
                creditsSubscribed = status == BluetoothGatt.GATT_SUCCESS
                handler.postDelayed({ startHandshake(g) }, 250)
            }
        }

        @Deprecated("legacy")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION") incoming(g, c, c.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { incoming(g, c, value) }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(g: BluetoothGatt, uuid: UUID) {
        val c = g.getService(SERVICE)?.getCharacteristic(uuid) ?: run { log("CHAR $uuid NOT FOUND"); return }
        val ok = g.setCharacteristicNotification(c, true)
        log("SUBSCRIBE $uuid local=$ok")
        val d = c.getDescriptor(CCCD) ?: run { log("CCCD NOT FOUND"); return }
        writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
    }

    private fun startHandshake(g: BluetoothGatt) {
        if (!fifoSubscribed || !creditsSubscribed) { log("DIAG subscriptions incomplete"); return }
        grantRx(g, 1)
        handler.postDelayed({ sendOpen(g) }, 220)
    }

    private fun incoming(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
        if (c.uuid == CREDITS) { log("RX CREDITS ${hex(v)} (informational)"); return }
        if (c.uuid != FIFO || v.size < 3) return
        val app = v.copyOfRange(2, v.size)
        log("RX FIFO ${hex(v)}")
        if (app.size >= 4 && u(app[0]) == 0x71 && u(app[1]) == 0x01 && u(app[2]) == 0xF2 && u(app[3]) == 0x11) {
            log("OPEN RHMI POSITIVE")
            grantRx(g, 1)
            handler.postDelayed({ sendStatus(g) }, 220)
            return
        }
        if (app.size >= 5 && u(app[0]) == 0x71 && u(app[1]) == 0x03 && u(app[2]) == 0xF2 && u(app[3]) == 0x11) {
            val s = u(app[4])
            log("RHMI STATUS=0x${hb(s)}")
            if (s == 0x10) { rhmiOpen = true; startCycle(g) }
            return
        }
        if (app.size >= 3 && u(app[0]) == 0x62) {
            val id = (u(app[1]) shl 8) or u(app[2])
            val data = if (app.size > 3) app.copyOfRange(3, app.size) else byteArrayOf()
            onPositive(g, id, data)
            return
        }
        if (app.size >= 3 && u(app[0]) == 0x7F) {
            val nrc = u(app[2])
            log("NRC service=0x${hb(u(app[1]))} nrc=0x${hb(nrc)}")
            if (waitingDid != null) {
                timeoutToken++
                waitingDid = null
                index++
                handler.postDelayed({ sendNext(g) }, 180)
            }
        }
    }

    private fun sendOpen(g: BluetoothGatt) { if (!openSent) { openSent = true; writeFifo(g, byteArrayOf(1, 1, 0x31, 0x01, 0xF2.toByte(), 0x11), "OPEN RHMI") } }
    private fun sendStatus(g: BluetoothGatt) { if (!statusSent) { statusSent = true; writeFifo(g, byteArrayOf(1, 1, 0x31, 0x03, 0xF2.toByte(), 0x11), "RHMI STATUS") } }

    private fun startCycle(g: BluetoothGatt) {
        if (!connected || !rhmiOpen) return
        cycle++
        index = 0
        waitingDid = null
        log("---------------- CYCLE $cycle ----------------")
        sendNext(g)
    }

    private fun sendNext(g: BluetoothGatt) {
        if (!connected || !rhmiOpen || waitingDid != null) return
        if (index >= dids.size) {
            log("CYCLE $cycle COMPLETE; next in ${NEXT_CYCLE_MS / 1000}s")
            handler.postDelayed({ if (connected && rhmiOpen) startCycle(g) }, NEXT_CYCLE_MS)
            return
        }
        val id = dids[index]
        waitingDid = id
        grantRx(g, 1)
        handler.postDelayed({
            if (waitingDid == id) {
                writeFifo(g, byteArrayOf(1, 1, 0x22, (id shr 8).toByte(), id.toByte()), "READ ${did(id)} ${names[id]}")
                val token = ++timeoutToken
                handler.postDelayed({
                    if (token == timeoutToken && waitingDid == id) {
                        log("TIMEOUT ${did(id)}")
                        waitingDid = null
                        index++
                        sendNext(g)
                    }
                }, RESPONSE_TIMEOUT_MS)
            }
        }, 180)
    }

    private fun onPositive(g: BluetoothGatt, id: Int, data: ByteArray) {
        if (waitingDid != id) { log("UNEXPECTED ${did(id)}=${hex(data)}"); return }
        timeoutToken++
        val old = previous[id]
        val changed = old != null && !old.contentEquals(data)
        val diff = if (old == null) "baseline" else if (changed) byteDiff(old, data) else "none"
        if (old == null) log("BASELINE ${did(id)} ${names[id]} = ${hex(data)}")
        else if (changed) {
            log("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            log("CHANGED ${did(id)} ${names[id]}")
            log("OLD = ${hex(old)}")
            log("NEW = ${hex(data)}")
            log("BYTE DIFF = $diff")
            log("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        } else log("UNCHANGED ${did(id)} = ${hex(data)}")
        previous[id] = data.copyOf()

        val decoded = decodeDid(id, data)
        if (decoded.isNotBlank()) log("DECODE ${did(id)} = $decoded")
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        handler.post { listener?.onDidUpdate(id, hex(data), decoded.ifBlank { "Пока не расшифровано" }, changed, diff, ts) }

        waitingDid = null
        index++
        handler.postDelayed({ sendNext(g) }, 180)
    }

    private fun decodeDid(id: Int, data: ByteArray): String {
        if (data.isEmpty()) return "Пустой ответ"

        // Known two-byte minute counters in ISO 16844-7.
        if (data.size >= 2) {
            val mins = (u(data[0]) shl 8) or u(data[1])
            val time = "${mins / 60}:${String.format(Locale.US, "%02d", mins % 60)}"
            when (id) {
                0xF923 -> return "$mins мин = $time • непрерывное вождение"
                0xF925 -> return "$mins мин = $time • накопленная зачётная пауза/отдых"
                0xF927 -> return "$mins мин = $time • длительность текущей выбранной деятельности"
                0xF938 -> return "$mins мин = $time • вождение за предыдущую + текущую неделю"
                0xF99A -> return "$mins мин = $time • текущее суточное вождение водителя 1"
                0xF99B -> return "$mins мин = $time • текущее недельное вождение водителя 1"
                0xF99C -> return "$mins мин = $time • до нового обязательного суточного отдыха"
                0xF9A1 -> return "$mins мин = $time • до нового обязательного недельного отдыха"
                0xF9A2 -> return "$mins мин = $time • накопленный непрерывный отдых"
                0xF9A3 -> return "$mins мин = $time • минимальный суточный отдых"
                0xF9A4 -> return "$mins мин = $time • минимальный недельный отдых"
                0xF9A5 -> return "$mins мин = $time • максимальный суточный период"
            }
        }

        return when (id) {
            0xF997 -> "Окончание последнего суточного отдыха • raw=${hex(data)}"
            0xF998 -> "Окончание последнего недельного отдыха • raw=${hex(data)}"
            0xF999 -> "Окончание предпоследнего недельного отдыха • raw=${hex(data)}"
            0xF99D -> "Срок действия карты водителя 1 • raw=${hex(data)}"
            0xF99E -> "Следующая обязательная выгрузка карты водителя 1 • raw=${hex(data)}"
            0xF99F -> "Следующая обязательная выгрузка тахографа • raw=${hex(data)}"
            0xF9A0 -> "Число превышений 9 ч суточного вождения • raw=${hex(data)}"
            else -> "Пока не расшифровано • raw=${hex(data)}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun grantRx(g: BluetoothGatt, n: Int) {
        val c = g.getService(SERVICE)?.getCharacteristic(CREDITS) ?: return
        writeCharacteristic(g, c, byteArrayOf(n.toByte()))
        log("TX GRANT RX +$n")
    }

    @SuppressLint("MissingPermission")
    private fun writeFifo(g: BluetoothGatt, data: ByteArray, label: String) {
        val c = g.getService(SERVICE)?.getCharacteristic(FIFO) ?: return
        val ok = writeCharacteristic(g, c, data)
        log("TX $label started=$ok hex=${hex(data)}")
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        else {
            @Suppress("DEPRECATION") c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION") c.value = data
            @Suppress("DEPRECATION") g.writeCharacteristic(c)
        }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, data: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeDescriptor(d, data) == BluetoothGatt.GATT_SUCCESS
        else {
            @Suppress("DEPRECATION") d.value = data
            @Suppress("DEPRECATION") g.writeDescriptor(d)
        }

    fun manualGattCheck() {
        log("STATUS connected=$connected fifo=$fifoSubscribed credits=$creditsSubscribed rhmi=$rhmiOpen cycle=$cycle waiting=${waitingDid?.let { did(it) } ?: "none"} autosave=${getCurrentLogFileName() ?: "OFF"}")
    }

    fun addMarker(text: String) { if (text.isNotBlank()) log("========== USER MARKER: ${text.trim()} ==========") }
    fun clearLog() { lines.clear(); log("Visible log cleared; autosaved file preserved") }
    fun getLog(): String = lines.joinToString("\n")

    fun disconnect() {
        connected = false
        rhmiOpen = false
        timeoutToken++
        waitingDid = null
        closeGatt()
        listener?.onConnectionStateChanged(false, device?.let { safeName(it) })
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val x = gatt
        gatt = null
        if (x != null) {
            try { x.disconnect() } catch (_: Throwable) {}
            try { x.close() } catch (_: Throwable) {}
        }
    }

    private fun byteDiff(a: ByteArray, b: ByteArray): String {
        val out = ArrayList<String>()
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.let { u(it) }
            val y = b.getOrNull(i)?.let { u(it) }
            if (x != y) out.add("[$i] ${x?.let { hb(it) } ?: "--"}->${y?.let { hb(it) } ?: "--"}")
        }
        return if (out.isEmpty()) "none" else out.joinToString(", ")
    }

    private fun log(s: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "[$ts] $s"
        lines.add(line)
        while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
        synchronized(fileLock) {
            try { logFile?.appendText(line + "\n", Charsets.UTF_8) } catch (_: Throwable) {}
        }
        val all = getLog()
        handler.post { listener?.onLogChanged(all) }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice) = try { d.name ?: d.address } catch (_: Throwable) { "DTCO" }
    private fun u(b: Byte) = b.toInt() and 0xFF
    private fun hb(i: Int) = "%02X".format(Locale.US, i and 0xFF)
    private fun did(i: Int) = "%04X".format(Locale.US, i and 0xFFFF)
    private fun hex(b: ByteArray) = if (b.isEmpty()) "(empty)" else b.joinToString(" ") { hb(u(it)) }
}
