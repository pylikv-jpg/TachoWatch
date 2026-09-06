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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * DTCO TARGET CORRELATOR v8
 *
 * Read-only diagnostic tool. It opens the Remote HMI diagnostic routine and then
 * only uses UDS ReadDataByIdentifier (0x22). No card/activity/place writes.
 *
 * Phase 1: discover F900..F9FF once.
 * Phase 2: monitor every positive DID for 12 cycles, approximately once/minute.
 * Correlation targets confirmed from the DTCO display:
 *   09:12 = 552 min = 0x0228  (remaining 2-week driving time)
 *   weekly-rest remainder = 24:00 - current uninterrupted rest
 *   remaining allowances: 1 reduced 9h daily rest, 2 extended 10h driving days.
 */
class TargetCorrelatorDiagnostic(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onLogChanged(fullLog: String)
        fun onConnectionChanged(connected: Boolean, deviceName: String?)
        fun onFinished(fullLog: String)
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE = UUID.fromString("fa213def-aef4-475c-bcea-0a8d69073efc")
        private val FIFO = UUID.fromString("e413960c-75ba-4ca9-8a67-99bc052a1b13")
        private val CREDITS = UUID.fromString("e168d1a6-304f-42b4-ab96-4cd1d4efebd9")
        private const val RESPONSE_TIMEOUT_MS = 2500L
        private const val TX_GAP_MS = 115L
        private const val MONITOR_CYCLES = 12
        private const val MONITOR_PERIOD_MS = 60_000L
        private const val TWO_WEEK_LEFT_MIN = 552
        private const val WEEKLY_REST_MIN = 24 * 60
    }

    private enum class Phase { IDLE, DISCOVERY, MONITOR, FINISHED }

    private val handler = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val lines = ArrayList<String>()
    private val positiveDids = linkedSetOf<Int>()
    private val previous = HashMap<Int, ByteArray>()
    private val latest = HashMap<Int, ByteArray>()
    private val exactTwoWeekHits = linkedSetOf<String>()
    private val weeklyRestHits = linkedSetOf<String>()
    private val countOneHits = linkedSetOf<String>()
    private val countTwoHits = linkedSetOf<String>()
    private val trendHits = linkedSetOf<String>()

    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var connected = false
    private var phase = Phase.IDLE
    private var txCredits = 0
    private var openSent = false
    private var statusSent = false
    private var rhmiReady = false
    private var waiting = false
    private var requestToken = 0L
    private var currentDid = -1
    private var discoveryDid = 0xF900
    private var monitorList = emptyList<Int>()
    private var monitorIndex = 0
    private var monitorCycle = 0
    private var cycleStartedAt = 0L
    private var stopped = false

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun getLog(): String = lines.joinToString("\n")

    @SuppressLint("MissingPermission")
    fun start(device: BluetoothDevice) {
        stopInternal(notify = false)
        if (!hasPermission()) {
            append("ERROR: BLUETOOTH_CONNECT permission missing")
            return
        }
        lines.clear()
        positiveDids.clear()
        previous.clear()
        latest.clear()
        exactTwoWeekHits.clear()
        weeklyRestHits.clear()
        countOneHits.clear()
        countTwoHits.clear()
        trendHits.clear()
        targetDevice = device
        stopped = false
        phase = Phase.IDLE
        txCredits = 0
        openSent = false
        statusSent = false
        rhmiReady = false
        waiting = false
        currentDid = -1
        discoveryDid = 0xF900
        monitorCycle = 0
        monitorIndex = 0
        requestToken++

        append("============================================================")
        append("DTCO TARGET CORRELATOR v8")
        append("DID discovery: F900-F9FF • READ ONLY • UDS 0x22")
        append("Targets: 09:12(552), weekly-rest-to-24h, remaining counts 1 / 2")
        append("Monitor: all positive DIDs, 12 cycles, ~1 cycle/min")
        append("============================================================")

        gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context, false, callback)
        } catch (t: Throwable) {
            append("CONNECT ERROR: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (gatt == null) finish("connectGatt returned null")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        append("TEST STOPPED BY USER")
        finish("stopped")
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal(notify: Boolean) {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        requestToken++
        waiting = false
        connected = false
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null
        if (notify) listener.onConnectionChanged(false, null)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            append("GATT STATE status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                connected = true
                listener.onConnectionChanged(true, safeName(g.device))
                try {
                    if (!g.requestMtu(240)) g.discoverServices()
                } catch (_: Throwable) {
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                listener.onConnectionChanged(false, safeName(g.device))
                if (!stopped && phase != Phase.FINISHED) finish("unexpected disconnect status=$status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            append("MTU=$mtu status=$status")
            try { g.discoverServices() } catch (t: Throwable) { finish("discoverServices error ${t.message}") }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            append("SERVICE DISCOVERY status=$status services=${g.services.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish("service discovery failed")
                return
            }
            val s = g.getService(SERVICE)
            if (s == null) {
                finish("DIAG service not found")
                return
            }
            val fifo = s.getCharacteristic(FIFO)
            val credits = s.getCharacteristic(CREDITS)
            if (fifo == null || credits == null) {
                finish("DIAG FIFO/CREDITS missing")
                return
            }
            subscribe(g, fifo)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            append("CCCD ${d.characteristic.uuid} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish("CCCD write failed status=$status")
                return
            }
            if (d.characteristic.uuid == FIFO) {
                val c = g.getService(SERVICE)?.getCharacteristic(CREDITS) ?: return
                handler.postDelayed({ subscribe(g, c) }, 120L)
            } else if (d.characteristic.uuid == CREDITS) {
                handler.postDelayed({ grantPeer(g) }, 120L)
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
        try {
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD) ?: run { finish("CCCD descriptor missing"); return }
            if (Build.VERSION.SDK_INT >= 33) {
                val rc = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                if (rc != BluetoothGatt.GATT_SUCCESS) finish("writeDescriptor start rc=$rc")
            } else {
                @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                @Suppress("DEPRECATION") if (!g.writeDescriptor(d)) finish("writeDescriptor did not start")
            }
        } catch (t: Throwable) {
            finish("subscribe exception ${t.message}")
        }
    }

    private fun incoming(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
        if (c.uuid == CREDITS) {
            if (v.isNotEmpty()) {
                txCredits += u(v[0])
                if (!openSent && txCredits > 0) sendOpen(g)
            }
            return
        }
        if (c.uuid != FIFO || v.size < 3) return
        val a = v.copyOfRange(2, v.size)

        if (a.size >= 4 && u(a[0]) == 0x71 && u(a[1]) == 0x01 && u(a[2]) == 0xF2 && u(a[3]) == 0x11) {
            append("OPEN RHMI POSITIVE")
            grantPeer(g)
            handler.postDelayed({ sendStatus(g) }, 120L)
            return
        }
        if (a.size >= 5 && u(a[0]) == 0x71 && u(a[1]) == 0x03 && u(a[2]) == 0xF2 && u(a[3]) == 0x11) {
            append("RHMI STATUS=0x${hex2(u(a[4]))}")
            if (u(a[4]) == 0x10) {
                rhmiReady = true
                phase = Phase.DISCOVERY
                handler.postDelayed({ requestNext(g) }, 120L)
            } else finish("RHMI not ready")
            return
        }
        if (a.size >= 3 && u(a[0]) == 0x62) {
            val did = (u(a[1]) shl 8) or u(a[2])
            val data = if (a.size > 3) a.copyOfRange(3, a.size) else byteArrayOf()
            if (phase == Phase.DISCOVERY) positiveDids.add(did)
            latest[did] = data.copyOf()
            val tag = if (phase == Phase.DISCOVERY) "DISC" else "MON C$monitorCycle"
            val analysis = correlate(did, data)
            append("$tag ${hex4(did)} POS len=${data.size} hex=${hex(data)}${if (analysis.isBlank()) "" else " | $analysis"}")
            completeRequest(did, g)
            return
        }
        if (a.size >= 3 && u(a[0]) == 0x7F && u(a[1]) == 0x22) {
            val nrc = u(a[2])
            if (phase == Phase.DISCOVERY && (nrc == 0x22 || nrc == 0x31)) {
                // keep discovery log compact: only log condition-dependent DIDs
                if (nrc == 0x22) append("DISC ${hex4(currentDid)} NRC=0x22 conditionsNotCorrect")
            } else if (phase == Phase.MONITOR) {
                append("MON C$monitorCycle ${hex4(currentDid)} NRC=0x${hex2(nrc)}")
            }
            completeRequest(currentDid, g)
        }
    }

    private fun completeRequest(did: Int, g: BluetoothGatt) {
        if (!waiting || did != currentDid) return
        waiting = false
        requestToken++
        when (phase) {
            Phase.DISCOVERY -> discoveryDid++
            Phase.MONITOR -> monitorIndex++
            else -> Unit
        }
        handler.postDelayed({ requestNext(g) }, TX_GAP_MS)
    }

    private fun requestNext(g: BluetoothGatt) {
        if (stopped || !connected || !rhmiReady || waiting || gatt !== g) return
        when (phase) {
            Phase.DISCOVERY -> {
                if (discoveryDid > 0xF9FF) {
                    finishDiscovery(g)
                    return
                }
                sendDid(g, discoveryDid)
            }
            Phase.MONITOR -> {
                if (monitorIndex >= monitorList.size) {
                    finishMonitorCycle(g)
                    return
                }
                sendDid(g, monitorList[monitorIndex])
            }
            else -> Unit
        }
    }

    private fun finishDiscovery(g: BluetoothGatt) {
        monitorList = positiveDids.toList().sorted()
        append("============================================================")
        append("DISCOVERY COMPLETE • positive=${monitorList.size}")
        append("Positive DIDs: ${monitorList.joinToString(" ") { hex4(it) }}")
        append("Starting TARGET MONITOR")
        append("============================================================")
        phase = Phase.MONITOR
        monitorCycle = 1
        monitorIndex = 0
        cycleStartedAt = System.currentTimeMillis()
        previous.clear()
        handler.postDelayed({ requestNext(g) }, 300L)
    }

    private fun finishMonitorCycle(g: BluetoothGatt) {
        appendCycleSummary()
        if (monitorCycle >= MONITOR_CYCLES) {
            finish("complete")
            return
        }
        val elapsed = System.currentTimeMillis() - cycleStartedAt
        val delay = (MONITOR_PERIOD_MS - elapsed).coerceAtLeast(500L)
        append("MONITOR cycle=$monitorCycle complete elapsed=${elapsed / 1000}s nextIn=${delay / 1000}s")
        monitorCycle++
        monitorIndex = 0
        cycleStartedAt = System.currentTimeMillis() + delay
        handler.postDelayed({
            if (!stopped && phase == Phase.MONITOR) {
                cycleStartedAt = System.currentTimeMillis()
                requestNext(g)
            }
        }, delay)
    }

    private fun appendCycleSummary() {
        val rest = latest[0xF927]?.let { u16be(it, 0) } ?: latest[0xF925]?.let { u16be(it, 0) }
        val derivedWeekly = rest?.takeIf { it in 0..WEEKLY_REST_MIN }?.let { WEEKLY_REST_MIN - it }
        val twoWeekUsed = latest[0xF938]?.let { u16be(it, 0) }
        val derived2w = twoWeekUsed?.takeIf { it in 0..5400 }?.let { 5400 - it }
        append("---------------- CORRELATOR CYCLE $monitorCycle ----------------")
        if (rest != null) append("CONTROL currentRest=${fmtMin(rest)} • derived to 24h=${derivedWeekly?.let(::fmtMin) ?: "—"}")
        if (twoWeekUsed != null) append("CONTROL F938 used2w=${fmtMin(twoWeekUsed)} • derived remaining=${derived2w?.let(::fmtMin) ?: "—"}")
        append("HITS 09:12: ${if (exactTwoWeekHits.isEmpty()) "none" else exactTwoWeekHits.joinToString("; ")}")
        append("HITS weekly-rest remainder: ${if (weeklyRestHits.isEmpty()) "none" else weeklyRestHits.joinToString("; ")}")
        append("COUNT=1 candidates: ${if (countOneHits.isEmpty()) "none" else countOneHits.joinToString("; ")}")
        append("COUNT=2 candidates: ${if (countTwoHits.isEmpty()) "none" else countTwoHits.joinToString("; ")}")
        if (trendHits.isNotEmpty()) append("TREND candidates: ${trendHits.takeLast(20).joinToString("; ")}")
        append("---------------------------------------------------------------")
    }

    private fun correlate(did: Int, data: ByteArray): String {
        val notes = ArrayList<String>()
        val prev = previous[did]
        val currentRest = latest[0xF927]?.let { u16be(it, 0) } ?: latest[0xF925]?.let { u16be(it, 0) }
        val expectedWeeklyLeft = currentRest?.takeIf { it in 0..WEEKLY_REST_MIN }?.let { WEEKLY_REST_MIN - it }

        for (i in 0 until data.size - 1) {
            val be = u16be(data, i)
            val le = u16le(data, i)
            if (be == TWO_WEEK_LEFT_MIN) {
                val hit = "${hex4(did)}[${i}] BE=${fmtMin(be)}"
                exactTwoWeekHits.add(hit); notes.add("TARGET 2W_LEFT $hit")
            }
            if (le == TWO_WEEK_LEFT_MIN && le != be) {
                val hit = "${hex4(did)}[${i}] LE=${fmtMin(le)}"
                exactTwoWeekHits.add(hit); notes.add("TARGET 2W_LEFT $hit")
            }
            if (expectedWeeklyLeft != null && abs(be - expectedWeeklyLeft) <= 2) {
                val hit = "${hex4(did)}[${i}] BE=${fmtMin(be)} exp=${fmtMin(expectedWeeklyLeft)}"
                weeklyRestHits.add(hit); notes.add("TARGET REST24 $hit")
            }
            if (expectedWeeklyLeft != null && le != be && abs(le - expectedWeeklyLeft) <= 2) {
                val hit = "${hex4(did)}[${i}] LE=${fmtMin(le)} exp=${fmtMin(expectedWeeklyLeft)}"
                weeklyRestHits.add(hit); notes.add("TARGET REST24 $hit")
            }
            if (prev != null && prev.size > i + 1) {
                val old = u16be(prev, i)
                val delta = be - old
                if (delta in -3..3 && delta != 0) {
                    val direction = if (delta > 0) "UP" else "DOWN"
                    val hit = "${hex4(did)}[$i] $direction ${fmtMin(old)}→${fmtMin(be)} Δ=$delta"
                    trendHits.add(hit)
                    notes.add("TREND $hit")
                }
            }
        }

        // Counts are deliberately conservative: short payloads only, to avoid thousands of false positives.
        if (data.size <= 4) {
            data.forEachIndexed { i, b ->
                when (u(b)) {
                    1 -> {
                        val hit = "${hex4(did)}[$i]=1"
                        countOneHits.add(hit); notes.add("COUNT?9h $hit")
                    }
                    2 -> {
                        val hit = "${hex4(did)}[$i]=2"
                        countTwoHits.add(hit); notes.add("COUNT?10h $hit")
                    }
                }
            }
        }

        previous[did] = data.copyOf()
        return notes.joinToString(" | ")
    }

    private fun sendDid(g: BluetoothGatt, did: Int) {
        grantPeer(g)
        handler.postDelayed({
            if (stopped || waiting || gatt !== g) return@postDelayed
            val fifo = g.getService(SERVICE)?.getCharacteristic(FIFO) ?: run { finish("FIFO lost"); return@postDelayed }
            val req = byteArrayOf(1, 1, 0x22, (did shr 8).toByte(), did.toByte())
            if (writeNoResponse(g, fifo, req)) {
                currentDid = did
                waiting = true
                val token = ++requestToken
                handler.postDelayed({
                    if (waiting && token == requestToken && currentDid == did && !stopped) {
                        append("${if (phase == Phase.DISCOVERY) "DISC" else "MON C$monitorCycle"} ${hex4(did)} TIMEOUT")
                        waiting = false
                        when (phase) {
                            Phase.DISCOVERY -> discoveryDid++
                            Phase.MONITOR -> monitorIndex++
                            else -> Unit
                        }
                        requestNext(g)
                    }
                }, RESPONSE_TIMEOUT_MS)
            } else {
                append("TX ${hex4(did)} did not start; retry")
                handler.postDelayed({ requestNext(g) }, 300L)
            }
        }, TX_GAP_MS)
    }

    private fun sendOpen(g: BluetoothGatt) {
        val fifo = g.getService(SERVICE)?.getCharacteristic(FIFO) ?: return
        if (writeNoResponse(g, fifo, byteArrayOf(1, 1, 0x31, 0x01, 0xF2.toByte(), 0x11))) {
            openSent = true
            txCredits = (txCredits - 1).coerceAtLeast(0)
            append("TX OPEN RHMI")
        }
    }

    private fun sendStatus(g: BluetoothGatt) {
        if (statusSent) return
        val fifo = g.getService(SERVICE)?.getCharacteristic(FIFO) ?: return
        if (writeNoResponse(g, fifo, byteArrayOf(1, 1, 0x31, 0x03, 0xF2.toByte(), 0x11))) {
            statusSent = true
            append("TX RHMI STATUS")
        }
    }

    private fun grantPeer(g: BluetoothGatt) {
        val c = g.getService(SERVICE)?.getCharacteristic(CREDITS) ?: return
        writeNoResponse(g, c, byteArrayOf(1))
    }

    @SuppressLint("MissingPermission")
    private fun writeNoResponse(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION") c.value = value
            @Suppress("DEPRECATION") g.writeCharacteristic(c)
        }
    } catch (_: Throwable) { false }

    private fun finish(reason: String) {
        if (phase == Phase.FINISHED) return
        if (phase == Phase.MONITOR) appendCycleSummary()
        phase = Phase.FINISHED
        append("============================================================")
        append("TARGET CORRELATOR FINISHED reason=$reason")
        append("positiveDids=${positiveDids.size} cycles=$monitorCycle")
        append("FINAL 09:12 hits=${if (exactTwoWeekHits.isEmpty()) "none" else exactTwoWeekHits.joinToString("; ")}")
        append("FINAL REST24 hits=${if (weeklyRestHits.isEmpty()) "none" else weeklyRestHits.joinToString("; ")}")
        append("FINAL COUNT1=${if (countOneHits.isEmpty()) "none" else countOneHits.joinToString("; ")}")
        append("FINAL COUNT2=${if (countTwoHits.isEmpty()) "none" else countTwoHits.joinToString("; ")}")
        append("============================================================")
        val out = getLog()
        listener.onFinished(out)
        stopInternal(notify = true)
    }

    private fun append(message: String) {
        lines.add("[${time.format(Date())}] $message")
        listener.onLogChanged(getLog())
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String = try { device.name ?: device.address } catch (_: Throwable) { "DTCO" }
    private fun u(b: Byte): Int = b.toInt() and 0xFF
    private fun u16be(b: ByteArray, i: Int): Int = if (i + 1 < b.size) (u(b[i]) shl 8) or u(b[i + 1]) else -1
    private fun u16le(b: ByteArray, i: Int): Int = if (i + 1 < b.size) u(b[i]) or (u(b[i + 1]) shl 8) else -1
    private fun fmtMin(m: Int): String = if (m < 0) "—" else "${m / 60}:${String.format(Locale.US, "%02d", m % 60)}"
    private fun hex(b: ByteArray): String = b.joinToString(" ") { hex2(u(it)) }
    private fun hex2(v: Int): String = String.format(Locale.US, "%02X", v and 0xFF)
    private fun hex4(v: Int): String = String.format(Locale.US, "F%03X", v and 0x0FFF)
}
