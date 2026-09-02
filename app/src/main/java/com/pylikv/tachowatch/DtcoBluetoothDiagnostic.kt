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
        private const val VERSION = "BLE-RHMI-READONLY-DIAG-SESSION-TEST-7"
        private const val MAX_LOG_LINES = 12000
        private const val INITIAL_CREDITS = 1
        private const val RESPONSE_TIMEOUT_MS = 2200L
        private const val NEXT_REQUEST_DELAY_MS = 250L

        private val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val UUID_DIAGNOSTICS_SERVICE = UUID.fromString("fa213def-aef4-475c-bcea-0a8d69073efc")
        private val UUID_DIAGNOSTICS_FIFO = UUID.fromString("e413960c-75ba-4ca9-8a67-99bc052a1b13")
        private val UUID_DIAGNOSTICS_CREDITS = UUID.fromString("e168d1a6-304f-42b4-ab96-4cd1d4efebd9")
    }

    private enum class ResultType { POSITIVE, NRC, TIMEOUT, ERROR }
    private enum class ReadStage { NONE, DEFAULT_BASELINE, EXTENDED }

    private data class ReadResult(
        val did: Int,
        val type: ResultType,
        val data: ByteArray = byteArrayOf(),
        val nrc: Int? = null,
        val decoded: String = ""
    )

    private val readDids = listOf(
        0xF903, // activity control
        0xF923, // continuous driving
        0xF925, // cumulative break
        0xF927, // current activity duration
        0xF938, // previous + current week driving
        0xF99A, // current daily driving time (optional ISO DID)
        0xF99B, // current weekly driving time (optional ISO DID)
        0xF99C  // time left until new daily rest (optional ISO DID)
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = CopyOnWriteArrayList<String>()
    private val baselineResults = linkedMapOf<Int, ReadResult>()
    private val extendedResults = linkedMapOf<Int, ReadResult>()

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
    @Volatile private var pendingSessionType: Int? = null
    @Volatile private var requestGeneration = 0L
    @Volatile private var readStage = ReadStage.NONE
    @Volatile private var extendedAccepted = false
    @Volatile private var testFinished = false

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
        appendLog("TachoWatch — DIAGNOSTIC SESSION TEST")
        appendLog("Версия: $VERSION")
        appendLog("========================================")
        appendLog("БЕЗ ЗАПИСИ ДАННЫХ В DTCO/КАРТУ")
        appendLog("Чтение: UDS 0x22 ReadDataByIdentifier")
        appendLog("Единственное изменение состояния: UDS 0x10 diagnostic session")
        appendLog("TEST-7 сравнивает default session и extended session 0x03")
        appendLog("После теста запрашивается возврат в default session 0x01")
        appendLog("НЕТ 0x2E WriteDataByIdentifier")
        appendLog("НЕТ SecurityAccess 0x27")
        appendLog("НЕТ изменения деятельности / manual entry / карты")
        appendLog("DID: ${readDids.joinToString(" / ") { hexDid(it) }}")
        appendLog("Цель: F99A / F99B / F99C")
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
        baselineResults.clear()
        extendedResults.clear()
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
        pendingSessionType = null
        readStage = ReadStage.NONE
        extendedAccepted = false
        testFinished = false
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
        appendLog("Stage = $readStage")
        appendLog("Read index = $readIndex / ${readDids.size}")
        appendLog("Waiting response = $waitingForResponse")
        appendLog("Current DID = ${currentDid?.let { hexDid(it) } ?: "NONE"}")
        appendLog("Pending session = ${pendingSessionType?.let { "0x${hexByte(it)}" } ?: "NONE"}")
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
                    readingStarted = false
                    waitingForResponse = false
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
            if (status != BluetoothGatt.GATT_SUCCESS) appendLog("TX ${characteristic.uuid} -> ${gattStatusToString(status)}")
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
        try { appendLog("discoverServices = ${gatt.discoverServices()}") }
        catch (e: Throwable) { appendThrowable("discoverServices", e) }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeDiagnosticsFifo(gatt: BluetoothGatt) {
        val c = gatt.getService(UUID_DIAGNOSTICS_SERVICE)?.getCharacteristic(UUID_DIAGNOSTICS_FIFO)
        if (c == null) { appendLog("Diagnostics FIFO NOT FOUND"); return }
        appendLog("Subscribe Diagnostics FIFO")
        gatt.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(UUID_CCCD) ?: return
        appendLog("FIFO CCCD write = ${writeDescriptorCompat(gatt, d, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)}")
    }

    @SuppressLint("MissingPermission")
    private fun subscribeDiagnosticsCredits(gatt: BluetoothGatt) {
        val c = gatt.getService(UUID_DIAGNOSTICS_SERVICE)?.getCharacteristic(UUID_DIAGNOSTICS_CREDITS)
        if (c == null) { appendLog("Diagnostics Credits NOT FOUND"); return }
        appendLog("Subscribe Diagnostics Credits")
        gatt.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(UUID_CCCD) ?: return
        appendLog("Credits CCCD write = ${writeDescriptorCompat(gatt, d, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)}")
    }

    private fun sendCredit(gatt: BluetoothGatt, label: String) {
        val c = gatt.getService(UUID_DIAGNOSTICS_SERVICE)?.getCharacteristic(UUID_DIAGNOSTICS_CREDITS) ?: return
        if (!writeCharacteristicCompat(gatt, c, byteArrayOf(INITIAL_CREDITS.toByte()))) appendLog("TX RX-CREDIT [$label] FAILED")
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
        if (credits == 0xFF) { appendLog("FLOW CONTROL REJECTED"); return }
        diagnosticsTxCredits += credits

        if (diagnosticsTxCredits > 0 && !openRequestSent) {
            mainHandler.postDelayed({ sendOpenRhmiRequest(gatt) }, 200L)
            return
        }
        if (diagnosticsTxCredits > 0 && readingStarted && !waitingForResponse && readIndex < readDids.size) {
            mainHandler.postDelayed({ sendCurrentRead(gatt) }, 120L)
            return
        }
        val session = pendingSessionType
        if (diagnosticsTxCredits > 0 && session != null && !waitingForResponse) {
            mainHandler.postDelayed({ sendSessionControlNow(gatt, session) }, 120L)
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
        if (started) { openRequestSent = true; diagnosticsTxCredits -= 1 }
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
            if (status == 0x10 && !rhmiOpened) {
                rhmiOpened = true
                appendLog(">>> RHMI SESSION OPENED <<<")
                mainHandler.postDelayed({ startReadStage(gatt, ReadStage.DEFAULT_BASELINE) }, 300L)
            }
            return
        }
        if (appData.isNotEmpty() && unsigned(appData[0]) == 0x50) {
            handleSessionPositive(gatt, appData)
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
            if (service == 0x22 && waitingForResponse && currentDid != null) {
                val did = currentDid!!
                val r = ReadResult(did, ResultType.NRC, nrc = nrc, decoded = negativeResponseToString(nrc))
                storeResult(did, r)
                appendLog("${hexDid(did)} -> NRC 0x${hexByte(nrc)} ${r.decoded}")
                completeCurrentRead(gatt)
                return
            }
            if (service == 0x10 && waitingForResponse && pendingSessionType != null) {
                handleSessionNegative(gatt, nrc)
                return
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
            if (started) { statusRequestSent = true; diagnosticsTxCredits -= 1 }
            return
        }
        if (attempt < 12) mainHandler.postDelayed({ trySendRhmiStatus(gatt, attempt + 1) }, 250L)
    }

    private fun startReadStage(gatt: BluetoothGatt, stage: ReadStage) {
        if (!connected || testFinished) return
        readStage = stage
        readingStarted = true
        readIndex = 0
        waitingForResponse = false
        currentDid = null
        appendLog("")
        appendLog("========================================")
        appendLog(if (stage == ReadStage.DEFAULT_BASELINE) "===== PHASE A: DEFAULT SESSION BASELINE =====" else "===== PHASE B: EXTENDED SESSION 0x03 =====")
        appendLog("Время: ${clockTime()}")
        appendLog("========================================")
        prepareNextRead(gatt)
    }

    private fun prepareNextRead(gatt: BluetoothGatt) {
        if (!connected || !readingStarted || testFinished) return
        if (readIndex >= readDids.size) { finishReadStage(gatt); return }
        if (waitingForResponse) return
        val did = readDids[readIndex]
        sendCredit(gatt, "RX-${hexDid(did)}")
        mainHandler.postDelayed({ trySendCurrentRead(gatt, 1) }, 250L)
    }

    private fun trySendCurrentRead(gatt: BluetoothGatt, attempt: Int) {
        if (waitingForResponse || !readingStarted || testFinished) return
        if (readIndex >= readDids.size) { finishReadStage(gatt); return }
        if (diagnosticsTxCredits > 0) { sendCurrentRead(gatt); return }
        if (attempt >= 15) {
            val did = readDids[readIndex]
            val r = ReadResult(did, ResultType.ERROR, decoded = "NO TX CREDIT")
            storeResult(did, r)
            appendLog("${hexDid(did)} -> NO TX CREDIT")
            readIndex += 1
            mainHandler.postDelayed({ prepareNextRead(gatt) }, NEXT_REQUEST_DELAY_MS)
            return
        }
        mainHandler.postDelayed({ trySendCurrentRead(gatt, attempt + 1) }, 180L)
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
            storeResult(did, r)
            readIndex += 1
            mainHandler.postDelayed({ prepareNextRead(gatt) }, NEXT_REQUEST_DELAY_MS)
            return
        }
        diagnosticsTxCredits -= 1
        waitingForResponse = true
        currentDid = did
        requestGeneration += 1
        val generation = requestGeneration
        mainHandler.postDelayed({ handleReadTimeout(gatt, did, generation) }, RESPONSE_TIMEOUT_MS)
    }

    private fun handleReadTimeout(gatt: BluetoothGatt, did: Int, generation: Long) {
        if (generation != requestGeneration || !waitingForResponse || currentDid != did) return
        val r = ReadResult(did, ResultType.TIMEOUT, decoded = "TIMEOUT")
        storeResult(did, r)
        appendLog("${hexDid(did)} -> TIMEOUT")
        completeCurrentRead(gatt)
    }

    private fun handlePositiveRead(gatt: BluetoothGatt, did: Int, data: ByteArray) {
        val decoded = decodeDid(did, data)
        val r = ReadResult(did, ResultType.POSITIVE, data.copyOf(), decoded = decoded)
        storeResult(did, r)
        appendLog("${hexDid(did)} POSITIVE | RAW=${bytesToHex(data)} | $decoded")
        if (waitingForResponse && currentDid == did) completeCurrentRead(gatt)
    }

    private fun storeResult(did: Int, result: ReadResult) {
        when (readStage) {
            ReadStage.DEFAULT_BASELINE -> baselineResults[did] = result
            ReadStage.EXTENDED -> extendedResults[did] = result
            else -> Unit
        }
    }

    private fun completeCurrentRead(gatt: BluetoothGatt) {
        requestGeneration += 1
        waitingForResponse = false
        currentDid = null
        readIndex += 1
        mainHandler.postDelayed({ prepareNextRead(gatt) }, NEXT_REQUEST_DELAY_MS)
    }

    private fun finishReadStage(gatt: BluetoothGatt) {
        readingStarted = false
        waitingForResponse = false
        currentDid = null
        requestGeneration += 1
        appendLog("")
        appendLog(if (readStage == ReadStage.DEFAULT_BASELINE) "----- DEFAULT BASELINE SUMMARY -----" else "----- EXTENDED SESSION SUMMARY -----")
        val map = if (readStage == ReadStage.DEFAULT_BASELINE) baselineResults else extendedResults
        readDids.forEach { appendCompactResult(it, map[it]) }
        if (readStage == ReadStage.DEFAULT_BASELINE) {
            appendLog("")
            appendLog("STEP: запрос ExtendedDiagnosticSession 10 03")
            requestSessionControl(gatt, 0x03)
        } else {
            appendLog("")
            appendLog("STEP: возврат в DefaultSession 10 01")
            requestSessionControl(gatt, 0x01)
        }
    }

    private fun requestSessionControl(gatt: BluetoothGatt, sessionType: Int) {
        if (testFinished) return
        pendingSessionType = sessionType
        waitingForResponse = false
        sendCredit(gatt, "SESSION-${hexByte(sessionType)}")
        mainHandler.postDelayed({ trySendSessionControl(gatt, sessionType, 1) }, 250L)
    }

    private fun trySendSessionControl(gatt: BluetoothGatt, sessionType: Int, attempt: Int) {
        if (testFinished || pendingSessionType != sessionType || waitingForResponse) return
        if (diagnosticsTxCredits > 0) { sendSessionControlNow(gatt, sessionType); return }
        if (attempt >= 15) {
            appendLog("SESSION 0x${hexByte(sessionType)} -> NO TX CREDIT")
            pendingSessionType = null
            if (sessionType == 0x03) finishTest("Extended session не удалось запросить")
            else finishTest("Не удалось подтвердить возврат в default session")
            return
        }
        mainHandler.postDelayed({ trySendSessionControl(gatt, sessionType, attempt + 1) }, 180L)
    }

    private fun sendSessionControlNow(gatt: BluetoothGatt, sessionType: Int) {
        if (diagnosticsTxCredits <= 0 || pendingSessionType != sessionType || waitingForResponse) return
        val fifo = getDiagnosticsFifo(gatt) ?: return
        val packet = byteArrayOf(0x01, 0x01, 0x10, sessionType.toByte())
        appendLog("TX UDS: 10 ${hexByte(sessionType)}")
        val started = writeCharacteristicCompat(gatt, fifo, packet)
        appendLog("DiagnosticSessionControl write = $started")
        if (!started) {
            pendingSessionType = null
            if (sessionType == 0x03) finishTest("Запуск 10 03 не удался") else finishTest("Запуск 10 01 не удался")
            return
        }
        diagnosticsTxCredits -= 1
        waitingForResponse = true
        requestGeneration += 1
        val generation = requestGeneration
        mainHandler.postDelayed({ handleSessionTimeout(gatt, sessionType, generation) }, RESPONSE_TIMEOUT_MS)
    }

    private fun handleSessionPositive(gatt: BluetoothGatt, appData: ByteArray) {
        val requested = pendingSessionType ?: return
        if (!waitingForResponse || appData.size < 2) return
        val echoed = unsigned(appData[1]) and 0x7F
        if (echoed != (requested and 0x7F)) return
        requestGeneration += 1
        waitingForResponse = false
        pendingSessionType = null
        appendLog("RX UDS: ${bytesToHex(appData)}")
        appendLog("SESSION 0x${hexByte(requested)} POSITIVE")
        if (requested == 0x03) {
            extendedAccepted = true
            mainHandler.postDelayed({ startReadStage(gatt, ReadStage.EXTENDED) }, 300L)
        } else if (requested == 0x01) {
            finishTest("Default session восстановлена")
        }
    }

    private fun handleSessionNegative(gatt: BluetoothGatt, nrc: Int) {
        val requested = pendingSessionType ?: return
        requestGeneration += 1
        waitingForResponse = false
        pendingSessionType = null
        appendLog("SESSION 0x${hexByte(requested)} -> NRC 0x${hexByte(nrc)} ${negativeResponseToString(nrc)}")
        if (requested == 0x03) {
            extendedAccepted = false
            finishTest("ExtendedDiagnosticSession отклонена; session не менялась")
        } else {
            finishTest("Возврат в default session получил NRC")
        }
    }

    private fun handleSessionTimeout(gatt: BluetoothGatt, sessionType: Int, generation: Long) {
        if (generation != requestGeneration || !waitingForResponse || pendingSessionType != sessionType) return
        requestGeneration += 1
        waitingForResponse = false
        pendingSessionType = null
        appendLog("SESSION 0x${hexByte(sessionType)} -> TIMEOUT")
        if (sessionType == 0x03) finishTest("Нет ответа на 10 03") else finishTest("Нет ответа на 10 01")
    }

    private fun finishTest(note: String) {
        if (testFinished) return
        testFinished = true
        readingStarted = false
        waitingForResponse = false
        requestGeneration += 1
        appendLog("")
        appendLog("========================================")
        appendLog("===== DIAGNOSTIC SESSION TEST-7 RESULT =====")
        appendLog("========================================")
        appendLog("Extended 10 03 accepted = $extendedAccepted")
        appendLog("$note")
        appendLog("")
        appendLog("TARGET COMPARISON:")
        listOf(0xF99A, 0xF99B, 0xF99C).forEach { did ->
            appendLog("${hexDid(did)} DEFAULT=${resultText(baselineResults[did])} | EXTENDED=${resultText(extendedResults[did])}")
        }
        appendLog("")
        appendLog("CONTROL COMPARISON:")
        listOf(0xF903, 0xF923, 0xF925, 0xF927, 0xF938).forEach { did ->
            appendLog("${hexDid(did)} DEFAULT=${resultText(baselineResults[did])} | EXTENDED=${resultText(extendedResults[did])}")
        }
        appendLog("")
        appendLog("Интерпретация:")
        appendLog("- Если F99A/F99B/F99C стали POSITIVE только после 10 03 -> найдена prerequisite session.")
        appendLog("- Если NRC не изменился -> extended session сама по себе доступ не открывает.")
        appendLog("- Если 10 03 отклонена -> фиксируем NRC и не перебираем другие session ID вслепую.")
        appendLog("ГЛАВНОЕ ДЛЯ ФОТО: от 'DIAGNOSTIC SESSION TEST-7 RESULT' до конца + строки ответа на 10 03.")
        appendLog("========================================")
    }

    private fun appendCompactResult(did: Int, r: ReadResult?) {
        appendLog("${hexDid(did)} = ${resultText(r)}")
    }

    private fun resultText(r: ReadResult?): String = when (r?.type) {
        ResultType.POSITIVE -> "POSITIVE ${bytesToHex(r.data)} (${r.decoded})"
        ResultType.NRC -> "NRC 0x${hexByte(r.nrc ?: 0)} ${r.decoded}"
        ResultType.TIMEOUT -> "TIMEOUT"
        ResultType.ERROR -> "ERROR ${r.decoded}"
        null -> "NO RESULT"
    }

    private fun decodeDid(did: Int, data: ByteArray): String = when (did) {
        0xF903 -> decodeActivity(data)
        0xF923, 0xF925, 0xF927, 0xF938, 0xF99A, 0xF99B, 0xF99C -> decodeMinutes(data)
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
        if (data.size >= 2) parts += "u16BE=${decodeUnsigned16(data[0], data[1])}"
        return parts.joinToString(" | ")
    }

    private fun didTitle(did: Int): String = when (did) {
        0xF903 -> "Driver1WorkingState"
        0xF923 -> "Driver1ContinuousDrivingTime"
        0xF925 -> "Driver1CumulativeBreakTime"
        0xF927 -> "Driver1CurrentActivityDuration"
        0xF938 -> "Driver1PreviousAndCurrentWeekDriving"
        0xF99A -> "Driver1CurrentDailyDrivingTime"
        0xF99B -> "Driver1CurrentWeeklyDrivingTime"
        0xF99C -> "Driver1TimeLeftUntilNewDailyRestPeriod"
        else -> "UNKNOWN"
    }

    private fun decodeUnsigned16(high: Byte, low: Byte): Int = (unsigned(high) shl 8) or unsigned(low)
    private fun formatMinutes(minutes: Int): String = String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)

    private fun getDiagnosticsFifo(gatt: BluetoothGatt): BluetoothGattCharacteristic? =
        gatt.getService(UUID_DIAGNOSTICS_SERVICE)?.getCharacteristic(UUID_DIAGNOSTICS_FIFO)

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { descriptor.value = value; gatt.writeDescriptor(descriptor) }
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
        0x24 -> "requestSequenceError"
        0x31 -> "requestOutOfRange"
        0x33 -> "securityAccessDenied"
        0x7E -> "subFunctionNotSupportedInActiveSession"
        0x7F -> "serviceNotSupportedInActiveSession"
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
        readingStarted = false
        waitingForResponse = false
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
        mainHandler.post { try { listener?.onLogChanged(fullLog) } catch (_: Throwable) {} }
    }

    private fun notifyConnectionState(isConnected: Boolean, deviceName: String?) {
        mainHandler.post { try { listener?.onConnectionStateChanged(isConnected, deviceName) } catch (_: Throwable) {} }
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
