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
import java.util.concurrent.CopyOnWriteArrayList

class DtcoBluetoothDiagnostic(
    private val context: Context,
    private val listener: Listener? = null
) {
    interface Listener {
        fun onLogChanged(fullLog: String)
        fun onConnectionStateChanged(connected: Boolean, deviceName: String?)
    }

    companion object {
        private const val VERSION = "BLE-RHMI-READONLY-DYNAMIC-SERIES-TEST-6"
        private const val MAX_LOG_LINES = 12000
        private const val INITIAL_CREDITS = 1
        private const val RESPONSE_TIMEOUT_MS = 1800L
        private const val NEXT_REQUEST_DELAY_MS = 250L
        private const val ROUND_INTERVAL_MS = 60000L
        private const val TOTAL_ROUNDS = 5

        private val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val UUID_DIAGNOSTICS_SERVICE = UUID.fromString("fa213def-aef4-475c-bcea-0a8d69073efc")
        private val UUID_DIAGNOSTICS_FIFO = UUID.fromString("e413960c-75ba-4ca9-8a67-99bc052a1b13")
        private val UUID_DIAGNOSTICS_CREDITS = UUID.fromString("e168d1a6-304f-42b4-ab96-4cd1d4efebd9")
    }

    private enum class ResultType { POSITIVE, NRC, TIMEOUT, ERROR }

    private data class ReadResult(
        val did: Int,
        val type: ResultType,
        val data: ByteArray = byteArrayOf(),
        val nrc: Int? = null,
        val decoded: String = ""
    )

    private data class Sample(
        val round: Int,
        val time: String,
        val result: ReadResult
    )

    /*
     * TEST-6: only dynamic/unknown DID plus reliable controls.
     * UDS 0x22 only. No DTCO/card writes and no activity control.
     */
    private val readDids = listOf(
        0xF903, // current activity
        0xF912, 0xF913, 0xF91B,
        0xF923, 0xF925, 0xF927, 0xF928, 0xF938,
        0xF940, 0xF95A, 0xF960,
        0xF979, 0xF97A, 0xF97B, 0xF97C
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = CopyOnWriteArrayList<String>()
    private val results = linkedMapOf<Int, ReadResult>()
    private val history = linkedMapOf<Int, MutableList<Sample>>()

    @Volatile private var currentGatt: BluetoothGatt? = null
    @Volatile private var currentDevice: BluetoothDevice? = null
    @Volatile private var connected = false
    @Volatile private var servicesDiscovered = false
    @Volatile private var diagnosticsTxCredits = 0
    @Volatile private var fifoSubscribed = false
    @Volatile private var creditsSubscribed = false
    @Volatile private var openRequestSent = false
    @Volatile private var statusRequestSent = false
    @Volatile private var rhmiOpened = false
    @Volatile private var readingStarted = false
    @Volatile private var readIndex = 0
    @Volatile private var waitingForResponse = false
    @Volatile private var currentDid: Int? = null
    @Volatile private var requestGeneration = 0L
    @Volatile private var currentRound = 0

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            appendLog("ОШИБКА: нет BLUETOOTH_CONNECT")
            return
        }

        closeGatt()
        resetState()
        currentDevice = device

        appendLog("========================================")
        appendLog("TachoWatch — DYNAMIC DID SERIES")
        appendLog("Версия: $VERSION")
        appendLog("========================================")
        appendLog("СТРОГО READ-ONLY")
        appendLog("UDS service: только 0x22 ReadDataByIdentifier")
        appendLog("Запись в DTCO/карту: НЕТ")
        appendLog("Управление режимами: НЕТ")
        appendLog("Раундов: $TOTAL_ROUNDS")
        appendLog("Интервал между раундами: ${ROUND_INTERVAL_MS / 1000} сек")
        appendLog("DID в каждом раунде: ${readDids.size}")
        appendLog("Контроль: F903 / F923 / F925 / F927 / F928 / F938")
        appendLog("Кандидаты: F912/F913/F91B/F940/F95A/F960/F979-F97C")
        appendLog("========================================")
        appendDeviceInfo(device)
        appendLog("")
        appendLog("STEP 1: connectGatt()")

        try {
            currentGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            appendLog("BluetoothGatt object = ${if (currentGatt != null) "CREATED" else "NULL"}")
        } catch (e: Throwable) {
            appendThrowable("connectGatt", e)
        }
    }

    private fun resetState() {
        logLines.clear()
        results.clear()
        history.clear()
        connected = false
        servicesDiscovered = false
        diagnosticsTxCredits = 0
        fifoSubscribed = false
        creditsSubscribed = false
        openRequestSent = false
        statusRequestSent = false
        rhmiOpened = false
        readingStarted = false
        readIndex = 0
        waitingForResponse = false
        currentDid = null
        currentRound = 0
        requestGeneration += 1
    }

    fun manualGattCheck() {
        appendLog("")
        appendLog("========================================")
        appendLog("MANUAL STATUS")
        appendLog("connected = $connected")
        appendLog("servicesDiscovered = $servicesDiscovered")
        appendLog("FIFO subscribed = $fifoSubscribed")
        appendLog("Credits subscribed = $creditsSubscribed")
        appendLog("TX Credits = $diagnosticsTxCredits")
        appendLog("RHMI opened = $rhmiOpened")
        appendLog("Round = $currentRound / $TOTAL_ROUNDS")
        appendLog("Read index = $readIndex / ${readDids.size}")
        appendLog("Waiting response = $waitingForResponse")
        appendLog("Current DID = ${currentDid?.let { hexDid(it) } ?: "NONE"}")
        appendLog("========================================")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            appendLog("")
            appendLog("CALLBACK: onConnectionStateChange")
            appendLog("status=$status (${gattStatusToString(status)})")
            appendLog("newState=$newState (${profileStateToString(newState)})")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    currentGatt = gatt
                    appendLog("BLE GATT CONNECTED")
                    notifyConnectionState(true, safeDeviceName(gatt.device))
                    try {
                        val started = gatt.requestMtu(512)
                        appendLog("requestMtu(512) = $started")
                        if (!started) discoverServicesSafe(gatt)
                    } catch (e: Throwable) {
                        appendThrowable("requestMtu", e)
                        discoverServicesSafe(gatt)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    servicesDiscovered = false
                    waitingForResponse = false
                    readingStarted = false
                    requestGeneration += 1
                    appendLog("BLE GATT DISCONNECTED")
                    notifyConnectionState(false, safeDeviceName(gatt.device))
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            appendLog("MTU = $mtu")
            appendLog("MTU status = ${gattStatusToString(status)}")
            discoverServicesSafe(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            appendLog("")
            appendLog("CALLBACK: onServicesDiscovered")
            appendLog("status = ${gattStatusToString(status)}")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            servicesDiscovered = true
            if (gatt.getService(UUID_DIAGNOSTICS_SERVICE) == null) {
                appendLog("Diagnostics Service NOT FOUND")
                return
            }
            appendLog("Diagnostics Service FOUND")
            subscribeDiagnosticsFifo(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.characteristic.uuid
            appendLog("CCCD callback $uuid -> ${gattStatusToString(status)}")
            if (uuid == UUID_DIAGNOSTICS_FIFO) {
                fifoSubscribed = status == BluetoothGatt.GATT_SUCCESS
                mainHandler.postDelayed({ subscribeDiagnosticsCredits(gatt) }, 150L)
            } else if (uuid == UUID_DIAGNOSTICS_CREDITS) {
                creditsSubscribed = status == BluetoothGatt.GATT_SUCCESS
                mainHandler.postDelayed({ sendCredit(gatt, "INITIAL") }, 300L)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("TX ${characteristic.uuid} -> ${gattStatusToString(status)}")
            }
        }

        @Deprecated("Legacy callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handleIncoming(gatt, characteristic, characteristic.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(gatt, characteristic, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServicesSafe(gatt: BluetoothGatt) {
        try {
            appendLog("discoverServices = ${gatt.discoverServices()}")
        } catch (e: Throwable) {
            appendThrowable("discoverServices", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeDiagnosticsFifo(gatt: BluetoothGatt) {
        val c = gatt.getService(UUID_DIAGNOSTICS_SERVICE)?.getCharacteristic(UUID_DIAGNOSTICS_FIFO)
        if (c == null) {
            appendLog("Diagnostics FIFO NOT FOUND")
            return
        }
        appendLog("Subscribe Diagnostics FIFO")
        gatt.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(UUID_CCCD) ?: return
        appendLog("FIFO CCCD write = ${writeDescriptorCompat(gatt, d, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)}")
    }

    @SuppressLint("MissingPermission")
    private fun subscribeDiagnosticsCredits(gatt: BluetoothGatt) {
        val c = gatt.getService(UUID_DIAGNOSTICS_SERVICE)?.getCharacteristic(UUID_DIAGNOSTICS_CREDITS)
        if (c == null) {
            appendLog("Diagnostics Credits NOT FOUND")
            return
        }
        appendLog("Subscribe Diagnostics Credits")
        gatt.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(UUID_CCCD) ?: return
        appendLog("Credits CCCD write = ${writeDescriptorCompat(gatt, d, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)}")
    }

    private fun sendCredit(gatt: BluetoothGatt, label: String) {
        val c = gatt.getService(UUID_DIAGNOSTICS_SERVICE)?.getCharacteristic(UUID_DIAGNOSTICS_CREDITS) ?: return
        if (!writeCharacteristicCompat(gatt, c, byteArrayOf(INITIAL_CREDITS.toByte()))) {
            appendLog("TX RX-CREDIT [$label] FAILED")
        }
    }

    private fun handleIncoming(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        when (characteristic.uuid) {
            UUID_DIAGNOSTICS_CREDITS -> handleDiagnosticsCredits(gatt, value)
            UUID_DIAGNOSTICS_FIFO -> handleDiagnosticsFifo(gatt, value)
        }
    }

    private fun handleDiagnosticsCredits(gatt: BluetoothGatt, value: ByteArray) {
        if (value.isEmpty()) return
        val credits = unsigned(value[0])
        if (credits == 0xFF) {
            appendLog("FLOW CONTROL REJECTED")
            return
        }
        diagnosticsTxCredits += credits

        if (diagnosticsTxCredits > 0 && !openRequestSent) {
            mainHandler.postDelayed({ sendOpenRhmiRequest(gatt) }, 200L)
            return
        }
        if (rhmiOpened && readingStarted && !waitingForResponse && readIndex < readDids.size && diagnosticsTxCredits > 0) {
            mainHandler.postDelayed({ sendCurrentRead(gatt) }, 150L)
        }
    }

    private fun sendOpenRhmiRequest(gatt: BluetoothGatt) {
        if (openRequestSent || diagnosticsTxCredits <= 0) return
        val fifo = getDiagnosticsFifo(gatt) ?: return
        val packet = byteArrayOf(0x01, 0x01, 0x31, 0x01, 0xF2.toByte(), 0x11)
        appendLog("")
        appendLog("OPEN RHMI SESSION")
        val started = writeCharacteristicCompat(gatt, fifo, packet)
        appendLog("Open session write = $started")
        if (started) {
            openRequestSent = true
            diagnosticsTxCredits -= 1
        }
    }

    private fun handleDiagnosticsFifo(gatt: BluetoothGatt, value: ByteArray) {
        if (value.size < 3) return
        val appData = value.copyOfRange(2, value.size)

        if (appData.size >= 4 && unsigned(appData[0]) == 0x71 && unsigned(appData[1]) == 0x01 && unsigned(appData[2]) == 0xF2 && unsigned(appData[3]) == 0x11) {
            appendLog("OPEN RHMI POSITIVE")
            sendCredit(gatt, "RHMI-STATUS")
            mainHandler.postDelayed({ trySendRhmiStatus(gatt, 1) }, 400L)
            return
        }

        if (appData.size >= 5 && unsigned(appData[0]) == 0x71 && unsigned(appData[1]) == 0x03 && unsigned(appData[2]) == 0xF2 && unsigned(appData[3]) == 0x11) {
            val status = unsigned(appData[4])
            appendLog("RHMI STATUS = 0x${hexByte(status)}")
            if (status == 0x10) {
                rhmiOpened = true
                appendLog(">>> RHMI SESSION OPENED <<<")
                mainHandler.postDelayed({ startRound(gatt, 1) }, 300L)
            }
            return
        }

        if (appData.size >= 3 && unsigned(appData[0]) == 0x62) {
            val did = (unsigned(appData[1]) shl 8) or unsigned(appData[2])
            val data = if (appData.size > 3) appData.copyOfRange(3, appData.size) else byteArrayOf()
            handlePositiveRead(gatt, did, data)
            return
        }

        if (appData.size >= 3 && unsigned(appData[0]) == 0x7F) {
            val service = unsigned(appData[1])
            val nrc = unsigned(appData[2])
            if (service == 0x22 && waitingForResponse) {
                currentDid?.let { did ->
                    val r = ReadResult(did, ResultType.NRC, nrc = nrc, decoded = negativeResponseToString(nrc))
                    results[did] = r
                    addSample(did, r)
                    appendLog("${hexDid(did)} -> NRC 0x${hexByte(nrc)} ${r.decoded}")
                }
                completeCurrentRead(gatt)
            }
        }
    }

    private fun trySendRhmiStatus(gatt: BluetoothGatt, attempt: Int) {
        if (statusRequestSent) return
        if (diagnosticsTxCredits > 0) {
            val fifo = getDiagnosticsFifo(gatt) ?: return
            val packet = byteArrayOf(0x01, 0x01, 0x31, 0x03, 0xF2.toByte(), 0x11)
            val started = writeCharacteristicCompat(gatt, fifo, packet)
            appendLog("RHMI status request = $started")
            if (started) {
                statusRequestSent = true
                diagnosticsTxCredits -= 1
            }
            return
        }
        if (attempt < 12) mainHandler.postDelayed({ trySendRhmiStatus(gatt, attempt + 1) }, 250L)
    }

    private fun startRound(gatt: BluetoothGatt, round: Int) {
        if (!connected || !rhmiOpened || round > TOTAL_ROUNDS) return
        currentRound = round
        readingStarted = true
        readIndex = 0
        waitingForResponse = false
        currentDid = null
        results.clear()

        appendLog("")
        appendLog("========================================")
        appendLog("===== ROUND $round / $TOTAL_ROUNDS =====")
        appendLog("Время: ${clockTime()}")
        appendLog("DID: ${readDids.joinToString(" / ") { hexDid(it) }}")
        appendLog("========================================")
        prepareNextRead(gatt)
    }

    private fun prepareNextRead(gatt: BluetoothGatt) {
        if (!connected || !readingStarted) return
        if (readIndex >= readDids.size) {
            finishRound(gatt)
            return
        }
        if (waitingForResponse) return
        val did = readDids[readIndex]
        sendCredit(gatt, "RX-${hexDid(did)}")
        mainHandler.postDelayed({ trySendCurrentRead(gatt, 1) }, 300L)
    }

    private fun trySendCurrentRead(gatt: BluetoothGatt, attempt: Int) {
        if (waitingForResponse || !readingStarted) return
        if (readIndex >= readDids.size) {
            finishRound(gatt)
            return
        }
        if (diagnosticsTxCredits > 0) {
            sendCurrentRead(gatt)
            return
        }
        if (attempt >= 15) {
            val did = readDids[readIndex]
            val r = ReadResult(did, ResultType.ERROR, decoded = "NO TX CREDIT")
            results[did] = r
            addSample(did, r)
            appendLog("${hexDid(did)} -> NO TX CREDIT")
            readIndex += 1
            mainHandler.postDelayed({ prepareNextRead(gatt) }, NEXT_REQUEST_DELAY_MS)
            return
        }
        mainHandler.postDelayed({ trySendCurrentRead(gatt, attempt + 1) }, 200L)
    }

    private fun sendCurrentRead(gatt: BluetoothGatt) {
        if (waitingForResponse || diagnosticsTxCredits <= 0 || readIndex >= readDids.size) return
        val did = readDids[readIndex]
        val fifo = getDiagnosticsFifo(gatt) ?: return
        val packet = byteArrayOf(0x01, 0x01, 0x22, (did shr 8).toByte(), (did and 0xFF).toByte())
        appendLog("READ ${hexDid(did)} — ${didTitle(did)}")
        val started = writeCharacteristicCompat(gatt, fifo, packet)
        if (!started) {
            val r = ReadResult(did, ResultType.ERROR, decoded = "WRITE START FAILED")
            results[did] = r
            addSample(did, r)
            readIndex += 1
            mainHandler.postDelayed({ prepareNextRead(gatt) }, NEXT_REQUEST_DELAY_MS)
            return
        }
        diagnosticsTxCredits -= 1
        waitingForResponse = true
        currentDid = did
        requestGeneration += 1
        val generation = requestGeneration
        mainHandler.postDelayed({ handleTimeout(gatt, did, generation) }, RESPONSE_TIMEOUT_MS)
    }

    private fun handleTimeout(gatt: BluetoothGatt, did: Int, generation: Long) {
        if (generation != requestGeneration || !waitingForResponse || currentDid != did) return
        val r = ReadResult(did, ResultType.TIMEOUT, decoded = "TIMEOUT")
        results[did] = r
        addSample(did, r)
        appendLog("${hexDid(did)} -> TIMEOUT")
        completeCurrentRead(gatt)
    }

    private fun handlePositiveRead(gatt: BluetoothGatt, did: Int, data: ByteArray) {
        val decoded = decodeDid(did, data)
        val r = ReadResult(did, ResultType.POSITIVE, data.copyOf(), decoded = decoded)
        results[did] = r
        addSample(did, r)
        appendLog("${hexDid(did)} POSITIVE | RAW=${bytesToHex(data)} | $decoded")
        if (waitingForResponse && currentDid == did) completeCurrentRead(gatt)
    }

    private fun addSample(did: Int, result: ReadResult) {
        history.getOrPut(did) { mutableListOf() }.add(Sample(currentRound, clockTime(), result))
    }

    private fun completeCurrentRead(gatt: BluetoothGatt) {
        requestGeneration += 1
        waitingForResponse = false
        currentDid = null
        readIndex += 1
        mainHandler.postDelayed({ prepareNextRead(gatt) }, NEXT_REQUEST_DELAY_MS)
    }

    private fun finishRound(gatt: BluetoothGatt) {
        readingStarted = false
        waitingForResponse = false
        currentDid = null
        requestGeneration += 1

        appendLog("")
        appendLog("----- ROUND $currentRound SUMMARY -----")
        readDids.forEach { did -> appendCompactResult(did) }
        appendLog("")
        appendLog("----- CHANGES VS PREVIOUS ROUND -----")
        appendChangesForRound(currentRound)

        if (currentRound < TOTAL_ROUNDS && connected && rhmiOpened) {
            appendLog("")
            appendLog("Следующий раунд через ${ROUND_INTERVAL_MS / 1000} сек...")
            val next = currentRound + 1
            mainHandler.postDelayed({ startRound(gatt, next) }, ROUND_INTERVAL_MS)
        } else {
            finishSeries()
        }
    }

    private fun appendCompactResult(did: Int) {
        val r = results[did]
        when (r?.type) {
            ResultType.POSITIVE -> appendLog("${hexDid(did)} = ${bytesToHex(r.data)} | ${r.decoded}")
            ResultType.NRC -> appendLog("${hexDid(did)} = NRC 0x${hexByte(r.nrc ?: 0)}")
            ResultType.TIMEOUT -> appendLog("${hexDid(did)} = TIMEOUT")
            ResultType.ERROR -> appendLog("${hexDid(did)} = ERROR ${r.decoded}")
            null -> appendLog("${hexDid(did)} = NO RESULT")
        }
    }

    private fun appendChangesForRound(round: Int) {
        if (round <= 1) {
            appendLog("Первый раунд — база для сравнения.")
            return
        }
        var changed = 0
        readDids.forEach { did ->
            val samples = history[did].orEmpty()
            val now = samples.lastOrNull { it.round == round }
            val prev = samples.lastOrNull { it.round == round - 1 }
            if (now == null || prev == null) return@forEach
            val text = changeText(did, prev.result, now.result)
            if (text != null) {
                changed += 1
                appendLog(text)
            }
        }
        if (changed == 0) appendLog("Изменений относительно предыдущего раунда нет.")
    }

    private fun changeText(did: Int, prev: ReadResult, now: ReadResult): String? {
        if (prev.type != now.type) {
            return "${hexDid(did)}: ${prev.type} -> ${now.type}"
        }
        if (prev.type != ResultType.POSITIVE) {
            if (prev.nrc == now.nrc && prev.decoded == now.decoded) return null
            return "${hexDid(did)}: ${prev.decoded} -> ${now.decoded}"
        }
        if (prev.data.contentEquals(now.data)) return null
        val delta = numericDelta(prev.data, now.data)
        return if (delta != null) {
            "${hexDid(did)}: ${bytesToHex(prev.data)} -> ${bytesToHex(now.data)} | ΔBE=${if (delta >= 0) "+$delta" else delta}"
        } else {
            "${hexDid(did)}: ${bytesToHex(prev.data)} -> ${bytesToHex(now.data)}"
        }
    }

    private fun numericDelta(a: ByteArray, b: ByteArray): Long? {
        if (a.size != b.size) return null
        return when (a.size) {
            1 -> (unsigned(b[0]) - unsigned(a[0])).toLong()
            2 -> (decodeUnsigned16(b[0], b[1]) - decodeUnsigned16(a[0], a[1])).toLong()
            4 -> decodeUnsigned32BE(b) - decodeUnsigned32BE(a)
            else -> null
        }
    }

    private fun finishSeries() {
        appendLog("")
        appendLog("========================================")
        appendLog("===== DYNAMIC DID SERIES RESULT =====")
        appendLog("========================================")
        appendLog("Раундов завершено: $currentRound / $TOTAL_ROUNDS")
        appendLog("Интервал: ${ROUND_INTERVAL_MS / 1000} сек")
        appendLog("")

        readDids.forEach { did ->
            val samples = history[did].orEmpty().filter { it.result.type == ResultType.POSITIVE }
            val values = samples.map { bytesToHex(it.result.data) }
            val unique = values.distinct()
            val label = if (unique.size > 1) "DYNAMIC" else "STABLE"
            appendLog("${hexDid(did)} $label | ${samples.joinToString(" -> ") { "R${it.round}:${bytesToHex(it.result.data)}" }}")
        }

        appendLog("")
        appendLog("Особенно сравнить: F940, F95A, F960, F979, F97A, F97B, F97C")
        appendLog("Контроль состояния: F903; времени: F923/F925/F927/F938")
        appendLog("ГЛАВНОЕ ДЛЯ ФОТО: от строки 'DYNAMIC DID SERIES RESULT' до конца")
        appendLog("========================================")
    }

    private fun decodeDid(did: Int, data: ByteArray): String = when (did) {
        0xF903 -> decodeActivity(data)
        0xF923, 0xF925, 0xF927, 0xF938 -> decodeMinutes(data)
        else -> decodeGeneric(data)
    }

    private fun decodeActivity(data: ByteArray): String {
        if (data.isEmpty()) return "EMPTY"
        val value = unsigned(data[0])
        val text = when (value) {
            0 -> "ОТДЫХ / ПЕРЕРЫВ"
            1 -> "ГОТОВНОСТЬ"
            2 -> "ДРУГАЯ РАБОТА"
            3 -> "ВОЖДЕНИЕ"
            0xFE -> "ERROR"
            0xFF -> "NOT AVAILABLE"
            else -> "НЕИЗВЕСТНО"
        }
        return "$value — $text"
    }

    private fun decodeMinutes(data: ByteArray): String {
        if (data.size < 2) return decodeGeneric(data)
        val minutes = decodeUnsigned16(data[0], data[1])
        return when {
            minutes >= 0xFF00 -> "NOT AVAILABLE | RAW-U16=$minutes"
            minutes >= 0xFE00 -> "ERROR | RAW-U16=$minutes"
            minutes >= 0xFB00 -> "SPECIAL INDICATOR | RAW-U16=$minutes"
            else -> "$minutes min = ${formatMinutes(minutes)}"
        }
    }

    private fun decodeGeneric(data: ByteArray): String {
        if (data.isEmpty()) return "EMPTY"
        val parts = mutableListOf<String>()
        parts += "len=${data.size}"
        if (data.size == 1) parts += "u8=${unsigned(data[0])}"
        if (data.size >= 2) {
            val be = decodeUnsigned16(data[0], data[1])
            val le = decodeUnsigned16(data[1], data[0])
            parts += "u16BE=$be"
            parts += "u16LE=$le"
        }
        if (data.size >= 4) parts += "u32BE=${decodeUnsigned32BE(data)}"
        return parts.joinToString(" | ")
    }

    private fun didTitle(did: Int): String = when (did) {
        0xF903 -> "CurrentActivity"
        0xF912 -> "DynamicCandidateF912"
        0xF913 -> "DynamicCandidateF913"
        0xF91B -> "DynamicCandidateF91B"
        0xF923 -> "ContinuousDrivingTime"
        0xF925 -> "CumulativeBreakTime"
        0xF927 -> "CurrentActivityDuration"
        0xF928 -> "UnknownF928"
        0xF938 -> "TwoWeekDriving"
        0xF940 -> "UnknownF940"
        0xF95A -> "UnknownF95A"
        0xF960 -> "UnknownF960"
        0xF979 -> "UnknownF979"
        0xF97A -> "UnknownF97A"
        0xF97B -> "UnknownF97B"
        0xF97C -> "UnknownF97C"
        else -> "UNKNOWN"
    }

    private fun decodeUnsigned16(high: Byte, low: Byte): Int = (unsigned(high) shl 8) or unsigned(low)

    private fun decodeUnsigned32BE(data: ByteArray): Long {
        if (data.size < 4) return 0L
        return (unsigned(data[0]).toLong() shl 24) or
            (unsigned(data[1]).toLong() shl 16) or
            (unsigned(data[2]).toLong() shl 8) or
            unsigned(data[3]).toLong()
    }

    private fun formatMinutes(minutes: Int): String = String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)

    private fun getDiagnosticsFifo(gatt: BluetoothGatt): BluetoothGattCharacteristic? =
        gatt.getService(UUID_DIAGNOSTICS_SERVICE)?.getCharacteristic(UUID_DIAGNOSTICS_FIFO)

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        }

    private fun negativeResponseToString(nrc: Int): String = when (nrc) {
        0x10 -> "generalReject"
        0x11 -> "serviceNotSupported"
        0x12 -> "subFunctionNotSupported"
        0x13 -> "incorrectMessageLengthOrInvalidFormat"
        0x21 -> "busyRepeatRequest"
        0x22 -> "conditionsNotCorrect"
        0x31 -> "requestOutOfRange"
        0x33 -> "securityAccessDenied"
        0x78 -> "responsePending"
        else -> "UNKNOWN NRC"
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF
    private fun hexByte(value: Int): String = "%02X".format(Locale.US, value and 0xFF)
    private fun hexDid(did: Int): String = "%04X".format(Locale.US, did and 0xFFFF)
    private fun bytesToHex(bytes: ByteArray): String = if (bytes.isEmpty()) "(empty)" else bytes.joinToString(" ") { "%02X".format(Locale.US, unsigned(it)) }
    private fun clockTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    @SuppressLint("MissingPermission")
    private fun appendDeviceInfo(device: BluetoothDevice) {
        appendLog("Устройство: ${safeDeviceName(device)}")
        appendLog("Адрес: ${safeDeviceAddress(device)}")
        appendLog("Bond state: ${bondStateToString(device.bondState)}")
        appendLog("Android API: ${Build.VERSION.SDK_INT}")
    }

    fun disconnect() {
        connected = false
        servicesDiscovered = false
        waitingForResponse = false
        readingStarted = false
        requestGeneration += 1
        appendLog("Ручное отключение.")
        closeGatt()
        notifyConnectionState(false, currentDevice?.let { safeDeviceName(it) })
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val gatt = currentGatt
        currentGatt = null
        if (gatt != null) {
            try { gatt.disconnect() } catch (_: Throwable) {}
            try { gatt.close() } catch (_: Throwable) {}
        }
    }

    fun clearLog() {
        logLines.clear()
        appendLog("Журнал очищен.")
    }

    fun getLog(): String = logLines.joinToString("\n")

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        logLines.add("[$timestamp] $message")
        while (logLines.size > MAX_LOG_LINES) {
            try { logLines.removeAt(0) } catch (_: Throwable) { break }
        }
        val fullLog = getLog()
        mainHandler.post {
            try { listener?.onLogChanged(fullLog) } catch (_: Throwable) {}
        }
    }

    private fun notifyConnectionState(isConnected: Boolean, deviceName: String?) {
        mainHandler.post {
            try { listener?.onConnectionStateChanged(isConnected, deviceName) } catch (_: Throwable) {}
        }
    }

    private fun appendThrowable(place: String, throwable: Throwable) {
        appendLog("ОШИБКА [$place]: ${throwable.javaClass.simpleName}: ${throwable.message ?: "(без сообщения)"}")
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String = try { device.name ?: "Без имени" } catch (_: Throwable) { "Нет доступа" }

    @SuppressLint("MissingPermission")
    private fun safeDeviceAddress(device: BluetoothDevice): String = try { device.address } catch (_: Throwable) { "Нет доступа" }

    private fun bondStateToString(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "BOND_NONE"
        BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
        BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
        else -> "UNKNOWN($state)"
    }

    private fun profileStateToString(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($state)"
    }

    private fun gattStatusToString(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
        BluetoothGatt.GATT_READ_NOT_PERMITTED -> "GATT_READ_NOT_PERMITTED"
        BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "GATT_WRITE_NOT_PERMITTED"
        BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "GATT_INSUFFICIENT_AUTHENTICATION"
        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "GATT_REQUEST_NOT_SUPPORTED"
        BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION"
        BluetoothGatt.GATT_INVALID_OFFSET -> "GATT_INVALID_OFFSET"
        BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "GATT_INVALID_ATTRIBUTE_LENGTH"
        BluetoothGatt.GATT_CONNECTION_CONGESTED -> "GATT_CONNECTION_CONGESTED"
        BluetoothGatt.GATT_FAILURE -> "GATT_FAILURE"
        else -> "STATUS_$status"
    }
}
