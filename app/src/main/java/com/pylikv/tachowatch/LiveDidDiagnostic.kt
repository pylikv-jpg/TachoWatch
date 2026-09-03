package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID

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
        private const val POLL_INTERVAL = 30000L
    }

    private val dids = intArrayOf(0xF903, 0xF923, 0xF925, 0xF927, 0xF931, 0xF938)
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

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasPermission()) return
        disconnect(false)
        lines.clear(); txCredits = 0; openSent = false; statusSent = false; rhmi = false
        index = 0; waiting = false; cycle = 0; token++
        log("LIVE START")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, cb)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() = disconnect(true)

    @SuppressLint("MissingPermission")
    private fun disconnect(notify: Boolean) {
        token++
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null; connected = false; waiting = false
        if (notify) listener.onLiveConnection(false, null)
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true; gatt = g
                listener.onLiveConnection(true, try { g.device.name } catch (_: Throwable) { "DTCO" })
                try { if (!g.requestMtu(512)) g.discoverServices() } catch (_: Throwable) { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false; waiting = false; token++
                listener.onLiveConnection(false, null)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val s = g.getService(SERVICE) ?: run { log("LIVE ERROR service not found"); return }
            subscribe(g, s.getCharacteristic(FIFO))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
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
            grant(g); handler.postDelayed({ sendStatus(g) }, 180); return
        }
        if (a.size >= 5 && u(a[0]) == 0x71 && u(a[1]) == 3 && u(a[2]) == 0xF2 && u(a[3]) == 0x11) {
            if (u(a[4]) == 0x10 && !rhmi) { rhmi = true; handler.postDelayed({ requestNext(g) }, 180) }
            return
        }
        if (a.size >= 3 && u(a[0]) == 0x62) {
            val did = (u(a[1]) shl 8) or u(a[2])
            val data = if (a.size > 3) a.copyOfRange(3, a.size) else byteArrayOf()
            log("${hex4(did)}=${hex(data)} | ${decode(did, data)}")
            if (waiting && index < dids.size && did == dids[index]) {
                waiting = false; token++; index++; handler.postDelayed({ requestNext(g) }, 140)
            }
            return
        }
        if (a.size >= 3 && u(a[0]) == 0x7F && u(a[1]) == 0x22 && waiting) {
            waiting = false; token++; index++; handler.postDelayed({ requestNext(g) }, 140)
        }
    }

    private fun requestNext(g: BluetoothGatt) {
        if (!connected || !rhmi || waiting) return

        if (index >= dids.size) {
            cycle++
            log("LIVE CYCLE #$cycle COMPLETE")
            val t = ++token
            handler.postDelayed({
                if (!connected || !rhmi || t != token) return@postDelayed
                index = 0; waiting = false; requestNext(g)
            }, POLL_INTERVAL)
            return
        }

        grant(g)
        handler.postDelayed({
            if (!connected || waiting || index >= dids.size) return@postDelayed
            if (txCredits <= 0) { requestNext(g); return@postDelayed }
            val did = dids[index]
            val fifo = g.getService(SERVICE)?.getCharacteristic(FIFO) ?: return@postDelayed
            val ok = writeNoResponse(g, fifo, byteArrayOf(1, 1, 0x22, (did shr 8).toByte(), did.toByte()))
            if (ok) {
                txCredits--; waiting = true
                val t = ++token
                handler.postDelayed({
                    if (t == token && waiting) { waiting = false; index++; requestNext(g) }
                }, RESPONSE_TIMEOUT)
            }
        }, 180)
    }

    private fun sendOpen(g: BluetoothGatt) {
        val fifo = g.getService(SERVICE)?.getCharacteristic(FIFO) ?: return
        if (writeNoResponse(g, fifo, byteArrayOf(1, 1, 0x31, 1, 0xF2.toByte(), 0x11))) { openSent = true; txCredits-- }
    }

    private fun sendStatus(g: BluetoothGatt) {
        if (statusSent || txCredits <= 0) return
        val fifo = g.getService(SERVICE)?.getCharacteristic(FIFO) ?: return
        if (writeNoResponse(g, fifo, byteArrayOf(1, 1, 0x31, 3, 0xF2.toByte(), 0x11))) { statusSent = true; txCredits-- }
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
    } catch (_: Throwable) { false }

    private fun decode(did: Int, b: ByteArray): String = when (did) {
        0xF903 -> if (b.isEmpty()) "—" else when (u(b[0]) and 7) {
            0 -> "ОТДЫХ / ПЕРЕРЫВ"; 1 -> "ГОТОВНОСТЬ"; 2 -> "ДРУГАЯ РАБОТА"; 3 -> "ВОЖДЕНИЕ"; else -> "КОД ${u(b[0]) and 7}"
        }
        0xF923, 0xF925, 0xF927, 0xF938 -> if (b.size < 2) "—" else {
            val m = (u(b[0]) shl 8) or u(b[1]); "$m мин = ${m / 60}:${String.format("%02d", m % 60)}"
        }
        0xF931 -> if (b.size >= 72) "${clean(b.copyOfRange(0, 36))} ${clean(b.copyOfRange(36, 72))}".trim() else clean(b)
        else -> hex(b)
    }

    private fun clean(b: ByteArray) = buildString {
        b.forEach { val x = u(it); if (x in 32..126) append(x.toChar()) else if (x == 0) append(' ') }
    }.trim()

    private fun log(s: String) {
        lines.add(s); while (lines.size > 300) lines.removeAt(0); listener.onLiveLog(lines.joinToString("\n"))
    }

    private fun u(b: Byte) = b.toInt() and 0xFF
    private fun hex(b: ByteArray) = b.joinToString(" ") { String.format("%02X", u(it)) }
    private fun hex4(v: Int) = String.format("%04X", v and 0xFFFF)
}
