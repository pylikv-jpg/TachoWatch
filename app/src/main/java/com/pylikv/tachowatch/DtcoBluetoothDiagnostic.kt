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

        fun onConnectionStateChanged(
            connected: Boolean,
            deviceName: String?
        )
    }

    companion object {

        private const val VERSION =
            "BLE-RHMI-DID-DISCOVERY-F900-F93F"

        private const val MAX_LOG_LINES =
            12000

        private const val INITIAL_CREDITS =
            1

        private const val SCAN_START_DID =
            0xF900

        private const val SCAN_END_DID =
            0xF93F

        private const val RESPONSE_TIMEOUT_MS =
            1800L

        private const val NEXT_REQUEST_DELAY_MS =
            250L

        private val UUID_CCCD =
            UUID.fromString(
                "00002902-0000-1000-8000-00805f9b34fb"
            )

        private val UUID_DIAGNOSTICS_SERVICE =
            UUID.fromString(
                "fa213def-aef4-475c-bcea-0a8d69073efc"
            )

        private val UUID_DIAGNOSTICS_FIFO =
            UUID.fromString(
                "e413960c-75ba-4ca9-8a67-99bc052a1b13"
            )

        private val UUID_DIAGNOSTICS_CREDITS =
            UUID.fromString(
                "e168d1a6-304f-42b4-ab96-4cd1d4efebd9"
            )
    }

    private enum class ResultType {
        POSITIVE,
        NRC,
        TIMEOUT,
        ERROR
    }

    private data class ScanResult(
        val did: Int,
        val type: ResultType,
        val rawData: ByteArray = byteArrayOf(),
        val nrc: Int? = null,
        val description: String = ""
    )

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val logLines =
        CopyOnWriteArrayList<String>()

    private val scanDids =
        (SCAN_START_DID..SCAN_END_DID)
            .toList()

    private val scanResults =
        linkedMapOf<Int, ScanResult>()

    @Volatile
    private var currentGatt:
        BluetoothGatt? = null

    @Volatile
    private var currentDevice:
        BluetoothDevice? = null

    @Volatile
    private var connected =
        false

    @Volatile
    private var servicesDiscovered =
        false

    @Volatile
    private var fifoSubscribed =
        false

    @Volatile
    private var creditsSubscribed =
        false

    @Volatile
    private var diagnosticsTxCredits =
        0

    @Volatile
    private var openRequestSent =
        false

    @Volatile
    private var statusRequestSent =
        false

    @Volatile
    private var rhmiOpened =
        false

    @Volatile
    private var scanStarted =
        false

    @Volatile
    private var scanIndex =
        0

    @Volatile
    private var waitingForResponse =
        false

    @Volatile
    private var currentDid:
        Int? = null

    /*
     * Каждый запрос получает собственный номер.
     * Это не даёт старому TIMEOUT завершить
     * уже следующий запрос.
     */
    @Volatile
    private var requestGeneration =
        0L

    fun hasConnectPermission(): Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) ==
                PackageManager.PERMISSION_GRANTED

        } else {

            true
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(
        device: BluetoothDevice
    ) {

        if (!hasConnectPermission()) {

            appendLog(
                "ОШИБКА: нет BLUETOOTH_CONNECT"
            )

            return
        }

        closeGatt()

        resetState()

        currentDevice =
            device

        appendLog(
            "========================================"
        )

        appendLog(
            "TachoWatch — DTCO DID DISCOVERY"
        )

        appendLog(
            "Версия: $VERSION"
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "РЕЖИМ: READ-ONLY DID DISCOVERY"
        )

        appendLog(
            "Диапазон: " +
                "${hexDid(SCAN_START_DID)}-" +
                hexDid(SCAN_END_DID)
        )

        appendLog(
            "Количество DID: ${scanDids.size}"
        )

        appendLog(
            "UDS чтение: только сервис 0x22"
        )

        appendLog(
            "WriteDataByIdentifier 0x2E: НЕ используется"
        )

        appendLog(
            "SecurityAccess 0x27: НЕ используется"
        )

        appendLog(
            "ECU Reset: НЕ используется"
        )

        appendLog(
            "Изменение деятельности водителя: НЕТ"
        )

        appendLog(
            "Запись данных карты: НЕТ"
        )

        appendLog(
            "========================================"
        )

        appendDeviceInfo(
            device
        )

        appendLog(
            ""
        )

        appendLog(
            "STEP 1: connectGatt()"
        )

        try {

            currentGatt =

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.M
                ) {

                    device.connectGatt(
                        context,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )

                } else {

                    device.connectGatt(
                        context,
                        false,
                        gattCallback
                    )
                }

            appendLog(
                "BluetoothGatt object = " +
                    if (currentGatt != null) {
                        "CREATED"
                    } else {
                        "NULL"
                    }
            )

        } catch (
            e: Throwable
        ) {

            appendThrowable(
                "connectGatt",
                e
            )
        }
    }

    private fun resetState() {

        logLines.clear()
        scanResults.clear()

        connected =
            false

        servicesDiscovered =
            false

        fifoSubscribed =
            false

        creditsSubscribed =
            false

        diagnosticsTxCredits =
            0

        openRequestSent =
            false

        statusRequestSent =
            false

        rhmiOpened =
            false

        scanStarted =
            false

        scanIndex =
            0

        waitingForResponse =
            false

        currentDid =
            null

        requestGeneration +=
            1
    }

    fun manualGattCheck() {

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "MANUAL STATUS"
        )

        appendLog(
            "connected = $connected"
        )

        appendLog(
            "servicesDiscovered = $servicesDiscovered"
        )

        appendLog(
            "FIFO subscribed = $fifoSubscribed"
        )

        appendLog(
            "Credits subscribed = $creditsSubscribed"
        )

        appendLog(
            "Diagnostics TX Credits = $diagnosticsTxCredits"
        )

        appendLog(
            "RHMI opened = $rhmiOpened"
        )

        appendLog(
            "Scan started = $scanStarted"
        )

        appendLog(
            "Scan index = $scanIndex / ${scanDids.size}"
        )

        appendLog(
            "Waiting response = $waitingForResponse"
        )

        appendLog(
            "Current DID = ${
                currentDid?.let {
                    hexDid(it)
                } ?: "NONE"
            }"
        )

        appendLog(
            "Results = ${scanResults.size}"
        )

        appendLog(
            "========================================"
        )
    }

    private val gattCallback =
        object : BluetoothGattCallback() {

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                appendLog(
                    ""
                )

                appendLog(
                    "========================================"
                )

                appendLog(
                    "CALLBACK: onConnectionStateChange"
                )

                appendLog(
                    "status = $status " +
                        "(${gattStatusToString(status)})"
                )

                appendLog(
                    "newState = $newState " +
                        "(${profileStateToString(newState)})"
                )

                appendLog(
                    "========================================"
                )

                when (newState) {

                    BluetoothProfile.STATE_CONNECTED -> {

                        connected =
                            true

                        currentGatt =
                            gatt

                        appendLog(
                            "BLE GATT CONNECTED"
                        )

                        notifyConnectionState(
                            true,
                            safeDeviceName(
                                gatt.device
                            )
                        )

                        try {

                            appendLog(
                                "STEP 2: requestMtu(512)"
                            )

                            val started =
                                gatt.requestMtu(
                                    512
                                )

                            appendLog(
                                "requestMtu = $started"
                            )

                            if (!started) {

                                discoverServicesSafe(
                                    gatt
                                )
                            }

                        } catch (
                            e: Throwable
                        ) {

                            appendThrowable(
                                "requestMtu",
                                e
                            )

                            discoverServicesSafe(
                                gatt
                            )
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {

                        connected =
                            false

                        servicesDiscovered =
                            false

                        waitingForResponse =
                            false

                        requestGeneration +=
                            1

                        appendLog(
                            "BLE GATT DISCONNECTED"
                        )

                        notifyConnectionState(
                            false,
                            safeDeviceName(
                                gatt.device
                            )
                        )
                    }

                    BluetoothProfile.STATE_CONNECTING -> {

                        appendLog(
                            "BLE GATT CONNECTING"
                        )
                    }

                    BluetoothProfile.STATE_DISCONNECTING -> {

                        appendLog(
                            "BLE GATT DISCONNECTING"
                        )
                    }
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {

                appendLog(
                    ""
                )

                appendLog(
                    "CALLBACK: onMtuChanged"
                )

                appendLog(
                    "MTU = $mtu"
                )

                appendLog(
                    "status = $status " +
                        "(${gattStatusToString(status)})"
                )

                discoverServicesSafe(
                    gatt
                )
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                appendLog(
                    ""
                )

                appendLog(
                    "========================================"
                )

                appendLog(
                    "CALLBACK: onServicesDiscovered"
                )

                appendLog(
                    "status = $status " +
                        "(${gattStatusToString(status)})"
                )

                appendLog(
                    "========================================"
                )

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    appendLog(
                        "SERVICE DISCOVERY ERROR"
                    )

                    return
                }

                servicesDiscovered =
                    true

                appendLog(
                    "Services = ${gatt.services.size}"
                )

                val service =
                    gatt.getService(
                        UUID_DIAGNOSTICS_SERVICE
                    )

                if (service == null) {

                    appendLog(
                        "ОШИБКА: Diagnostics Service НЕ НАЙДЕН"
                    )

                    return
                }

                appendLog(
                    "Diagnostics Service FOUND"
                )

                subscribeDiagnosticsFifo(
                    gatt
                )
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {

                val uuid =
                    descriptor.characteristic.uuid

                appendLog(
                    ""
                )

                appendLog(
                    "CALLBACK: onDescriptorWrite"
                )

                appendLog(
                    "Characteristic = $uuid"
                )

                appendLog(
                    "status = $status " +
                        "(${gattStatusToString(status)})"
                )

                if (
                    uuid ==
                    UUID_DIAGNOSTICS_FIFO
                ) {

                    if (
                        status ==
                        BluetoothGatt.GATT_SUCCESS
                    ) {

                        fifoSubscribed =
                            true

                        appendLog(
                            "Diagnostics FIFO indications ENABLED"
                        )
                    }

                    mainHandler.postDelayed(
                        {

                            subscribeDiagnosticsCredits(
                                gatt
                            )

                        },
                        150L
                    )

                    return
                }

                if (
                    uuid ==
                    UUID_DIAGNOSTICS_CREDITS
                ) {

                    if (
                        status ==
                        BluetoothGatt.GATT_SUCCESS
                    ) {

                        creditsSubscribed =
                            true

                        appendLog(
                            "Diagnostics Credits indications ENABLED"
                        )
                    }

                    mainHandler.postDelayed(
                        {

                            startCreditsHandshake(
                                gatt
                            )

                        },
                        300L
                    )
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {

                appendLog(
                    "TX callback: ${characteristicLabel(characteristic.uuid)} " +
                        "status=$status"
                )
            }

            @Deprecated(
                "Legacy callback"
            )
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {

                if (
                    Build.VERSION.SDK_INT <
                    Build.VERSION_CODES.TIRAMISU
                ) {

                    @Suppress("DEPRECATION")
                    val value =
                        characteristic.value
                            ?: byteArrayOf()

                    handleIncoming(
                        gatt,
                        characteristic,
                        value
                    )
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                handleIncoming(
                    gatt,
                    characteristic,
                    value
                )
            }
        }

    @SuppressLint("MissingPermission")
    private fun discoverServicesSafe(
        gatt: BluetoothGatt
    ) {

        appendLog(
            "STEP 3: discoverServices()"
        )

        try {

            val result =
                gatt.discoverServices()

            appendLog(
                "discoverServices() = $result"
            )

        } catch (
            e: Throwable
        ) {

            appendThrowable(
                "discoverServices",
                e
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeDiagnosticsFifo(
        gatt: BluetoothGatt
    ) {

        appendLog(
            ""
        )

        appendLog(
            "STEP 4A: SUBSCRIBE DIAGNOSTICS FIFO"
        )

        val characteristic =
            gatt
                .getService(
                    UUID_DIAGNOSTICS_SERVICE
                )
                ?.getCharacteristic(
                    UUID_DIAGNOSTICS_FIFO
                )

        if (characteristic == null) {

            appendLog(
                "Diagnostics FIFO NOT FOUND"
            )

            return
        }

        val local =
            try {

                gatt.setCharacteristicNotification(
                    characteristic,
                    true
                )

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "set FIFO indication",
                    e
                )

                false
            }

        appendLog(
            "setCharacteristicNotification = $local"
        )

        if (!local) {
            return
        }

        val descriptor =
            characteristic.getDescriptor(
                UUID_CCCD
            )

        if (descriptor == null) {

            appendLog(
                "FIFO CCCD NOT FOUND"
            )

            return
        }

        val started =
            writeDescriptorCompat(
                gatt,
                descriptor,
                BluetoothGattDescriptor
                    .ENABLE_INDICATION_VALUE
            )

        appendLog(
            "FIFO CCCD write started = $started"
        )
    }

    @SuppressLint("MissingPermission")
    private fun subscribeDiagnosticsCredits(
        gatt: BluetoothGatt
    ) {

        appendLog(
            ""
        )

        appendLog(
            "STEP 4B: SUBSCRIBE DIAGNOSTICS CREDITS"
        )

        val characteristic =
            gatt
                .getService(
                    UUID_DIAGNOSTICS_SERVICE
                )
                ?.getCharacteristic(
                    UUID_DIAGNOSTICS_CREDITS
                )

        if (characteristic == null) {

            appendLog(
                "Diagnostics Credits NOT FOUND"
            )

            return
        }

        val local =
            try {

                gatt.setCharacteristicNotification(
                    characteristic,
                    true
                )

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "set Credits indication",
                    e
                )

                false
            }

        appendLog(
            "setCharacteristicNotification = $local"
        )

        if (!local) {
            return
        }

        val descriptor =
            characteristic.getDescriptor(
                UUID_CCCD
            )

        if (descriptor == null) {

            appendLog(
                "Credits CCCD NOT FOUND"
            )

            return
        }

        val started =
            writeDescriptorCompat(
                gatt,
                descriptor,
                BluetoothGattDescriptor
                    .ENABLE_INDICATION_VALUE
            )

        appendLog(
            "Credits CCCD write started = $started"
        )
    }

    private fun startCreditsHandshake(
        gatt: BluetoothGatt
    ) {

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "STEP 5: DIAGNOSTICS CREDIT HANDSHAKE"
        )

        appendLog(
            "========================================"
        )

        sendCredit(
            gatt,
            "Initial"
        )
    }

    private fun sendCredit(
        gatt: BluetoothGatt,
        label: String
    ) {

        val characteristic =
            gatt
                .getService(
                    UUID_DIAGNOSTICS_SERVICE
                )
                ?.getCharacteristic(
                    UUID_DIAGNOSTICS_CREDITS
                )

        if (characteristic == null) {

            appendLog(
                "$label credit: characteristic NOT FOUND"
            )

            return
        }

        val data =
            byteArrayOf(
                INITIAL_CREDITS.toByte()
            )

        appendLog(
            "TX RX-CREDIT [$label] = ${bytesToHex(data)}"
        )

        val started =
            writeCharacteristicCompat(
                gatt,
                characteristic,
                data
            )

        appendLog(
            "Credit write started = $started"
        )
    }

    private fun handleIncoming(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {

        val uuid =
            characteristic.uuid

        when (uuid) {

            UUID_DIAGNOSTICS_CREDITS -> {

                appendLog(
                    ""
                )

                appendLog(
                    "RX Diagnostics Credits: ${bytesToHex(value)}"
                )

                handleDiagnosticsCredits(
                    gatt,
                    value
                )
            }

            UUID_DIAGNOSTICS_FIFO -> {

                appendLog(
                    ""
                )

                appendLog(
                    "RX Diagnostics FIFO: ${bytesToHex(value)}"
                )

                handleDiagnosticsFifo(
                    gatt,
                    value
                )
            }

            else -> {

                appendLog(
                    "RX Unknown characteristic: $uuid"
                )
            }
        }
    }

    private fun handleDiagnosticsCredits(
        gatt: BluetoothGatt,
        value: ByteArray
    ) {

        if (value.isEmpty()) {

            appendLog(
                "Diagnostics Credits EMPTY"
            )

            return
        }

        val credits =
            unsigned(
                value[0]
            )

        appendLog(
            "Diagnostics Credits RX = $credits"
        )

        if (
            credits ==
            0xFF
        ) {

            appendLog(
                "FLOW CONTROL REJECTED"
            )

            return
        }

        diagnosticsTxCredits +=
            credits

        appendLog(
            "Available TX Credits = $diagnosticsTxCredits"
        )

        if (
            diagnosticsTxCredits > 0 &&
            !openRequestSent
        ) {

            mainHandler.postDelayed(
                {

                    sendOpenRhmiRequest(
                        gatt
                    )

                },
                200L
            )

            return
        }

        if (
            rhmiOpened &&
            scanStarted &&
            !waitingForResponse &&
            scanIndex < scanDids.size &&
            diagnosticsTxCredits > 0
        ) {

            mainHandler.postDelayed(
                {

                    sendCurrentScanRead(
                        gatt
                    )

                },
                150L
            )
        }
    }

    /*
     * Сохраняем уже проверенный механизм
     * открытия Remote HMI transport session.
     *
     * Сканирование DIDs после этого использует
     * исключительно ReadDataByIdentifier 0x22.
     */
    private fun sendOpenRhmiRequest(
        gatt: BluetoothGatt
    ) {

        if (
            openRequestSent ||
            diagnosticsTxCredits <= 0
        ) {
            return
        }

        val fifo =
            getDiagnosticsFifo(
                gatt
            ) ?: return

        val packet =
            byteArrayOf(
                0x01,
                0x01,
                0x31,
                0x01,
                0xF2.toByte(),
                0x11
            )

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "STEP 6: OPEN RHMI TRANSPORT SESSION"
        )

        appendLog(
            "Application = 31 01 F2 11"
        )

        val started =
            writeCharacteristicCompat(
                gatt,
                fifo,
                packet
            )

        appendLog(
            "FIFO write started = $started"
        )

        if (started) {

            openRequestSent =
                true

            diagnosticsTxCredits -=
                1
        }

        appendLog(
            "========================================"
        )
    }

    private fun handleDiagnosticsFifo(
        gatt: BluetoothGatt,
        value: ByteArray
    ) {

        if (
            value.size <
            3
        ) {

            appendLog(
                "Diagnostics FIFO packet too short."
            )

            return
        }

        val totalPackets =
            unsigned(
                value[0]
            )

        val packetNumber =
            unsigned(
                value[1]
            )

        val appData =
            value.copyOfRange(
                2,
                value.size
            )

        appendLog(
            "Transport: total=$totalPackets packet=$packetNumber"
        )

        appendLog(
            "Application HEX = ${bytesToHex(appData)}"
        )

        /*
         * OpenRHMISession positive response.
         */
        if (
            appData.size >= 4 &&
            unsigned(appData[0]) == 0x71 &&
            unsigned(appData[1]) == 0x01 &&
            unsigned(appData[2]) == 0xF2 &&
            unsigned(appData[3]) == 0x11
        ) {

            appendLog(
                "OPEN RHMI POSITIVE RESPONSE"
            )

            mainHandler.postDelayed(
                {

                    sendCredit(
                        gatt,
                        "RHMI-status"
                    )

                },
                200L
            )

            mainHandler.postDelayed(
                {

                    trySendRhmiStatus(
                        gatt,
                        1
                    )

                },
                450L
            )

            return
        }

        /*
         * RHMI status response.
         */
        if (
            appData.size >= 5 &&
            unsigned(appData[0]) == 0x71 &&
            unsigned(appData[1]) == 0x03 &&
            unsigned(appData[2]) == 0xF2 &&
            unsigned(appData[3]) == 0x11
        ) {

            val status =
                unsigned(
                    appData[4]
                )

            appendLog(
                ""
            )

            appendLog(
                "******** RHMI STATUS ********"
            )

            appendLog(
                "Status = 0x${hexByte(status)}"
            )

            appendLog(
                "Meaning = ${rhmiStatusToString(status)}"
            )

            appendLog(
                "*****************************"
            )

            if (
                status ==
                0x10
            ) {

                rhmiOpened =
                    true

                appendLog(
                    ""
                )

                appendLog(
                    ">>> RHMI SESSION OPENED <<<"
                )

                mainHandler.postDelayed(
                    {

                        startDidScan(
                            gatt
                        )

                    },
                    300L
                )
            }

            return
        }

        /*
         * Positive ReadDataByIdentifier:
         *
         * 62 DID_H DID_L DATA...
         */
        if (
            appData.size >= 3 &&
            unsigned(appData[0]) == 0x62
        ) {

            val did =
                (
                    unsigned(
                        appData[1]
                    ) shl 8
                    ) or
                    unsigned(
                        appData[2]
                    )

            val data =

                if (
                    appData.size >
                    3
                ) {

                    appData.copyOfRange(
                        3,
                        appData.size
                    )

                } else {

                    byteArrayOf()
                }

            handlePositiveRead(
                gatt,
                did,
                data,
                appData
            )

            return
        }

        /*
         * Negative UDS response:
         *
         * 7F originalService NRC
         */
        if (
            appData.size >= 3 &&
            unsigned(appData[0]) == 0x7F
        ) {

            val originalService =
                unsigned(
                    appData[1]
                )

            val nrc =
                unsigned(
                    appData[2]
                )

            appendLog(
                ""
            )

            appendLog(
                "******** UDS NEGATIVE RESPONSE ********"
            )

            appendLog(
                "Original service = 0x${hexByte(originalService)}"
            )

            appendLog(
                "NRC = 0x${hexByte(nrc)}"
            )

            appendLog(
                "Meaning = ${negativeResponseToString(nrc)}"
            )

            /*
             * Для сканера учитываем только
             * отрицательный ответ именно на 0x22.
             */
            if (
                originalService ==
                0x22 &&
                waitingForResponse
            ) {

                val did =
                    currentDid

                if (did != null) {

                    scanResults[
                        did
                    ] =
                        ScanResult(
                            did = did,
                            type = ResultType.NRC,
                            nrc = nrc,
                            description =
                                negativeResponseToString(
                                    nrc
                                )
                        )

                    appendLog(
                        "${hexDid(did)} -> NRC " +
                            "0x${hexByte(nrc)} " +
                            negativeResponseToString(nrc)
                    )
                }

                completeCurrentRequest(
                    gatt
                )
            }

            appendLog(
                "***************************************"
            )

            return
        }

        appendLog(
            "Необработанный application packet."
        )
    }

    private fun trySendRhmiStatus(
        gatt: BluetoothGatt,
        attempt: Int
    ) {

        if (
            statusRequestSent
        ) {
            return
        }

        if (
            diagnosticsTxCredits >
            0
        ) {

            sendRhmiStatusRequest(
                gatt
            )

            return
        }

        if (
            attempt >=
            12
        ) {

            appendLog(
                "RHMI STATUS: TX Credit не получен."
            )

            return
        }

        mainHandler.postDelayed(
            {

                trySendRhmiStatus(
                    gatt,
                    attempt + 1
                )

            },
            250L
        )
    }

    private fun sendRhmiStatusRequest(
        gatt: BluetoothGatt
    ) {

        if (
            statusRequestSent ||
            diagnosticsTxCredits <= 0
        ) {
            return
        }

        val fifo =
            getDiagnosticsFifo(
                gatt
            ) ?: return

        val packet =
            byteArrayOf(
                0x01,
                0x01,
                0x31,
                0x03,
                0xF2.toByte(),
                0x11
            )

        appendLog(
            ""
        )

        appendLog(
            "STEP 7: GET RHMI SESSION STATUS"
        )

        appendLog(
            "Application = 31 03 F2 11"
        )

        val started =
            writeCharacteristicCompat(
                gatt,
                fifo,
                packet
            )

        appendLog(
            "FIFO write started = $started"
        )

        if (started) {

            statusRequestSent =
                true

            diagnosticsTxCredits -=
                1
        }
    }

    private fun startDidScan(
        gatt: BluetoothGatt
    ) {

        if (
            scanStarted
        ) {
            return
        }

        scanStarted =
            true

        scanIndex =
            0

        waitingForResponse =
            false

        currentDid =
            null

        scanResults.clear()

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "STEP 8: READ-ONLY DID DISCOVERY"
        )

        appendLog(
            "Диапазон: ${hexDid(SCAN_START_DID)}-" +
                hexDid(SCAN_END_DID)
        )

        appendLog(
            "Всего запросов: ${scanDids.size}"
        )

        appendLog(
            "Каждый application request: 22 DID_H DID_L"
        )

        appendLog(
            "TIMEOUT: ${RESPONSE_TIMEOUT_MS} ms"
        )

        appendLog(
            "========================================"
        )

        prepareNextScanRead(
            gatt
        )
    }

    private fun prepareNextScanRead(
        gatt: BluetoothGatt
    ) {

        if (
            !connected
        ) {
            return
        }

        if (
            scanIndex >=
            scanDids.size
        ) {

            finishDidScan()

            return
        }

        if (
            waitingForResponse
        ) {
            return
        }

        val did =
            scanDids[
                scanIndex
            ]

        appendLog(
            ""
        )

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "PREPARE ${scanIndex + 1}/${scanDids.size}"
        )

        appendLog(
            "DID = ${hexDid(did)}"
        )

        /*
         * Даём DTCO один credit для
         * следующего входящего FIFO packet.
         */
        sendCredit(
            gatt,
            "RX-${hexDid(did)}"
        )

        mainHandler.postDelayed(
            {

                trySendCurrentScanRead(
                    gatt,
                    1
                )

            },
            300L
        )
    }

    private fun trySendCurrentScanRead(
        gatt: BluetoothGatt,
        attempt: Int
    ) {

        if (
            waitingForResponse
        ) {
            return
        }

        if (
            scanIndex >=
            scanDids.size
        ) {

            finishDidScan()

            return
        }

        if (
            diagnosticsTxCredits >
            0
        ) {

            sendCurrentScanRead(
                gatt
            )

            return
        }

        if (
            attempt >=
            15
        ) {

            val did =
                scanDids[
                    scanIndex
                ]

            appendLog(
                "${hexDid(did)} -> ERROR: NO TX CREDIT"
            )

            scanResults[
                did
            ] =
                ScanResult(
                    did = did,
                    type = ResultType.ERROR,
                    description = "NO TX CREDIT"
                )

            scanIndex +=
                1

            mainHandler.postDelayed(
                {

                    prepareNextScanRead(
                        gatt
                    )

                },
                NEXT_REQUEST_DELAY_MS
            )

            return
        }

        mainHandler.postDelayed(
            {

                trySendCurrentScanRead(
                    gatt,
                    attempt + 1
                )

            },
            200L
        )
    }

    private fun sendCurrentScanRead(
        gatt: BluetoothGatt
    ) {

        if (
            waitingForResponse ||
            diagnosticsTxCredits <= 0 ||
            scanIndex >= scanDids.size
        ) {
            return
        }

        val did =
            scanDids[
                scanIndex
            ]

        val fifo =
            getDiagnosticsFifo(
                gatt
            ) ?: return

        val packet =
            byteArrayOf(
                0x01,
                0x01,
                0x22,
                (
                    did shr 8
                    ).toByte(),
                (
                    did and 0xFF
                    ).toByte()
            )

        appendLog(
            ""
        )

        appendLog(
            "READ ${scanIndex + 1}/${scanDids.size} " +
                "${hexDid(did)}"
        )

        appendLog(
            "Application = 22 ${hexDidSpaced(did)}"
        )

        appendLog(
            "Transport = ${bytesToHex(packet)}"
        )

        val started =
            writeCharacteristicCompat(
                gatt,
                fifo,
                packet
            )

        appendLog(
            "FIFO write started = $started"
        )

        if (!started) {

            scanResults[
                did
            ] =
                ScanResult(
                    did = did,
                    type = ResultType.ERROR,
                    description =
                        "WRITE START FAILED"
                )

            appendLog(
                "${hexDid(did)} -> WRITE START FAILED"
            )

            scanIndex +=
                1

            mainHandler.postDelayed(
                {

                    prepareNextScanRead(
                        gatt
                    )

                },
                NEXT_REQUEST_DELAY_MS
            )

            return
        }

        diagnosticsTxCredits -=
            1

        waitingForResponse =
            true

        currentDid =
            did

        requestGeneration +=
            1

        val generation =
            requestGeneration

        mainHandler.postDelayed(
            {

                handleResponseTimeout(
                    gatt,
                    did,
                    generation
                )

            },
            RESPONSE_TIMEOUT_MS
        )
    }

    private fun handleResponseTimeout(
        gatt: BluetoothGatt,
        did: Int,
        generation: Long
    ) {

        /*
         * Если generation уже изменился,
         * этот таймер относится к старому запросу.
         */
        if (
            generation !=
            requestGeneration
        ) {
            return
        }

        if (
            !waitingForResponse ||
            currentDid != did
        ) {
            return
        }

        appendLog(
            ""
        )

        appendLog(
            "******** RESPONSE TIMEOUT ********"
        )

        appendLog(
            "${hexDid(did)} -> TIMEOUT " +
                "${RESPONSE_TIMEOUT_MS} ms"
        )

        appendLog(
            "*******************************"
        )

        scanResults[
            did
        ] =
            ScanResult(
                did = did,
                type = ResultType.TIMEOUT,
                description =
                    "NO RESPONSE"
            )

        completeCurrentRequest(
            gatt
        )
    }

    private fun handlePositiveRead(
        gatt: BluetoothGatt,
        did: Int,
        data: ByteArray,
        fullApplication: ByteArray
    ) {

        appendLog(
            ""
        )

        appendLog(
            "******** POSITIVE READ RESPONSE ********"
        )

        appendLog(
            "DID = ${hexDid(did)}"
        )

        appendLog(
            "FULL = ${bytesToHex(fullApplication)}"
        )

        appendLog(
            "DATA = ${bytesToHex(data)}"
        )

        appendLog(
            "LENGTH = ${data.size}"
        )

        val decoded =
            decodeKnownDid(
                did,
                data
            )

        scanResults[
            did
        ] =
            ScanResult(
                did = did,
                type = ResultType.POSITIVE,
                rawData = data.copyOf(),
                description = decoded
            )

        appendLog(
            "RESULT = POSITIVE"
        )

        appendLog(
            "DECODED = $decoded"
        )

        appendLog(
            "***************************************"
        )

        /*
         * Обычно ответ соответствует текущему DID.
         * Если пришёл неожиданный DID —
         * результат сохраняем, но текущий запрос
         * не закрываем.
         */
        if (
            waitingForResponse &&
            currentDid ==
            did
        ) {

            completeCurrentRequest(
                gatt
            )

        } else {

            appendLog(
                "Ответ ${hexDid(did)} не совпадает " +
                    "с ожидаемым ${
                        currentDid?.let {
                            hexDid(it)
                        } ?: "NONE"
                    }"
            )
        }
    }

    private fun completeCurrentRequest(
        gatt: BluetoothGatt
    ) {

        requestGeneration +=
            1

        waitingForResponse =
            false

        currentDid =
            null

        scanIndex +=
            1

        mainHandler.postDelayed(
            {

                prepareNextScanRead(
                    gatt
                )

            },
            NEXT_REQUEST_DELAY_MS
        )
    }

    private fun finishDidScan() {

        scanStarted =
            false

        waitingForResponse =
            false

        currentDid =
            null

        requestGeneration +=
            1

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "DID DISCOVERY COMPLETE"
        )

        appendLog(
            "========================================"
        )

        val positives =
            scanResults.values.count {
                it.type ==
                    ResultType.POSITIVE
            }

        val nrcs =
            scanResults.values.count {
                it.type ==
                    ResultType.NRC
            }

        val timeouts =
            scanResults.values.count {
                it.type ==
                    ResultType.TIMEOUT
            }

        val errors =
            scanResults.values.count {
                it.type ==
                    ResultType.ERROR
            }

        appendLog(
            "POSITIVE = $positives"
        )

        appendLog(
            "NRC = $nrcs"
        )

        appendLog(
            "TIMEOUT = $timeouts"
        )

        appendLog(
            "ERROR = $errors"
        )

        appendLog(
            ""
        )

        appendLog(
            "====== POSITIVE DIDs ======"
        )

        scanDids.forEach {
            did ->

            val result =
                scanResults[
                    did
                ]

            if (
                result?.type ==
                ResultType.POSITIVE
            ) {

                appendLog(
                    "${hexDid(did)} -> POSITIVE"
                )

                appendLog(
                    "  RAW: ${bytesToHex(result.rawData)}"
                )

                appendLog(
                    "  ${result.description}"
                )
            }
        }

        appendLog(
            ""
        )

        appendLog(
            "====== COMPLETE MAP F900-F93F ======"
        )

        scanDids.forEach {
            did ->

            val result =
                scanResults[
                    did
                ]

            when (
                result?.type
            ) {

                ResultType.POSITIVE -> {

                    appendLog(
                        "${hexDid(did)} -> POSITIVE | " +
                            "${bytesToHex(result.rawData)} | " +
                            result.description
                    )
                }

                ResultType.NRC -> {

                    appendLog(
                        "${hexDid(did)} -> NRC " +
                            "0x${hexByte(result.nrc ?: 0)} | " +
                            result.description
                    )
                }

                ResultType.TIMEOUT -> {

                    appendLog(
                        "${hexDid(did)} -> TIMEOUT"
                    )
                }

                ResultType.ERROR -> {

                    appendLog(
                        "${hexDid(did)} -> ERROR | " +
                            result.description
                    )
                }

                null -> {

                    appendLog(
                        "${hexDid(did)} -> NO RESULT"
                    )
                }
            }
        }

        appendLog(
            ""
        )

        appendLog(
            "====== KNOWN TACHOWATCH DATA ======"
        )

        appendKnownSummary(
            0xF902,
            "Скорость автомобиля"
        )

        appendKnownSummary(
            0xF903,
            "Текущая деятельность"
        )

        appendKnownSummary(
            0xF905,
            "Движение автомобиля"
        )

        appendKnownSummary(
            0xF906,
            "Предупреждение по времени"
        )

        appendKnownSummary(
            0xF907,
            "Карта водителя"
        )

        appendKnownSummary(
            0xF923,
            "Непрерывное вождение"
        )

        appendKnownSummary(
            0xF925,
            "Накопленная пауза"
        )

        appendKnownSummary(
            0xF927,
            "Длительность текущей деятельности"
        )

        appendKnownSummary(
            0xF938,
            "Вождение: текущая + предыдущая неделя"
        )

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "SAFETY SUMMARY"
        )

        appendLog(
            "DID discovery requests: UDS 0x22 ONLY"
        )

        appendLog(
            "WriteDataByIdentifier: 0"
        )

        appendLog(
            "SecurityAccess: 0"
        )

        appendLog(
            "ECU Reset: 0"
        )

        appendLog(
            "Изменение деятельности: 0"
        )

        appendLog(
            "Запись в карту: 0"
        )

        appendLog(
            "========================================"
        )
    }

    private fun appendKnownSummary(
        did: Int,
        title: String
    ) {

        val result =
            scanResults[
                did
            ]

        appendLog(
            "$title:"
        )

        if (
            result ==
            null
        ) {

            appendLog(
                "  ${hexDid(did)} -> NO RESULT"
            )

            return
        }

        when (
            result.type
        ) {

            ResultType.POSITIVE -> {

                appendLog(
                    "  ${hexDid(did)} -> ${result.description}"
                )
            }

            ResultType.NRC -> {

                appendLog(
                    "  ${hexDid(did)} -> NRC " +
                        "0x${hexByte(result.nrc ?: 0)}"
                )
            }

            ResultType.TIMEOUT -> {

                appendLog(
                    "  ${hexDid(did)} -> TIMEOUT"
                )
            }

            ResultType.ERROR -> {

                appendLog(
                    "  ${hexDid(did)} -> ERROR"
                )
            }
        }
    }

    private fun decodeKnownDid(
        did: Int,
        data: ByteArray
    ): String {

        return when (did) {

            0xF902 -> {

                decodeSpeed(
                    data
                )
            }

            0xF903 -> {

                if (
                    data.isEmpty()
                ) {

                    "EMPTY"

                } else {

                    val value =
                        unsigned(
                            data[0]
                        )

                    "$value — ${driverActivityToString(value)}"
                }
            }

            0xF905 -> {

                if (
                    data.isEmpty()
                ) {

                    "EMPTY"

                } else {

                    val value =
                        unsigned(
                            data[0]
                        )

                    "$value — ${driveRecognizeToString(value)}"
                }
            }

            0xF906 -> {

                if (
                    data.isEmpty()
                ) {

                    "EMPTY"

                } else {

                    val value =
                        unsigned(
                            data[0]
                        )

                    "0x${hexByte(value)} — " +
                        timeRelatedStateToString(
                            value
                        )
                }
            }

            0xF907 -> {

                if (
                    data.isEmpty()
                ) {

                    "EMPTY"

                } else {

                    val value =
                        unsigned(
                            data[0]
                        )

                    "$value — ${driverCardToString(value)}"
                }
            }

            0xF923,
            0xF925,
            0xF927,
            0xF938 -> {

                decodeMinutes(
                    data
                )
            }

            else -> {

                classifyRawData(
                    data
                )
            }
        }
    }

    /*
     * Для неизвестных DID ничего не придумываем.
     * Показываем RAW и несколько объективных
     * числовых представлений для дальнейшего анализа.
     */
    private fun classifyRawData(
        data: ByteArray
    ): String {

        if (
            data.isEmpty()
        ) {

            return "RAW EMPTY"
        }

        val parts =
            mutableListOf<String>()

        parts.add(
            "RAW ${bytesToHex(data)}"
        )

        parts.add(
            "len=${data.size}"
        )

        if (
            data.size ==
            1
        ) {

            parts.add(
                "u8=${unsigned(data[0])}"
            )
        }

        if (
            data.size ==
            2
        ) {

            val be =
                decodeUnsigned16(
                    data[0],
                    data[1]
                )

            val le =
                decodeUnsigned16(
                    data[1],
                    data[0]
                )

            parts.add(
                "u16BE=$be"
            )

            parts.add(
                "u16LE=$le"
            )

            if (
                be <
                0xFB00
            ) {

                parts.add(
                    "BE-as-min=${formatMinutes(be)}"
                )
            }
        }

        return parts.joinToString(
            separator = " | "
        )
    }

    private fun decodeSpeed(
        data: ByteArray
    ): String {

        if (
            data.size <
            2
        ) {

            return "INVALID LENGTH ${data.size}"
        }

        val raw =
            decodeUnsigned16(
                data[0],
                data[1]
            )

        return when {

            raw >=
                0xFF00 -> {

                "NOT AVAILABLE / RAW ${bytesToHex(data)}"
            }

            raw >=
                0xFE00 -> {

                "ERROR / RAW ${bytesToHex(data)}"
            }

            else -> {

                val speed =
                    raw.toDouble() /
                        256.0

                String.format(
                    Locale.US,
                    "%.3f km/h (raw=%d)",
                    speed,
                    raw
                )
            }
        }
    }

    private fun decodeMinutes(
        data: ByteArray
    ): String {

        if (
            data.size <
            2
        ) {

            return "INVALID LENGTH ${data.size} / " +
                "RAW ${bytesToHex(data)}"
        }

        val minutes =
            decodeUnsigned16(
                data[0],
                data[1]
            )

        return when {

            minutes >=
                0xFF00 -> {

                "NOT AVAILABLE / RAW ${bytesToHex(data)}"
            }

            minutes >=
                0xFE00 -> {

                "ERROR / RAW ${bytesToHex(data)}"
            }

            minutes >=
                0xFB00 -> {

                "SPECIAL INDICATOR / RAW ${bytesToHex(data)}"
            }

            else -> {

                "$minutes min = ${formatMinutes(minutes)}"
            }
        }
    }

    private fun getDiagnosticsFifo(
        gatt: BluetoothGatt
    ): BluetoothGattCharacteristic? {

        return gatt
            .getService(
                UUID_DIAGNOSTICS_SERVICE
            )
            ?.getCharacteristic(
                UUID_DIAGNOSTICS_FIFO
            )
    }

    private fun driverActivityToString(
        value: Int
    ): String {

        return when (value) {

            0 ->
                "ОТДЫХ / ПЕРЕРЫВ"

            1 ->
                "ГОТОВНОСТЬ"

            2 ->
                "ДРУГАЯ РАБОТА"

            3 ->
                "ВОЖДЕНИЕ"

            0xFE ->
                "ERROR"

            0xFF ->
                "NOT AVAILABLE"

            else ->
                "НЕИЗВЕСТНО ($value)"
        }
    }

    private fun driveRecognizeToString(
        value: Int
    ): String {

        return when (value) {

            0 ->
                "АВТОМОБИЛЬ НЕ ДВИЖЕТСЯ"

            1 ->
                "АВТОМОБИЛЬ ДВИЖЕТСЯ"

            0xFE ->
                "ERROR"

            0xFF ->
                "NOT AVAILABLE"

            else ->
                "НЕИЗВЕСТНОЕ ЗНАЧЕНИЕ ($value)"
        }
    }

    private fun driverCardToString(
        value: Int
    ): String {

        return when (value) {

            0 ->
                "ВОДИТЕЛЬСКАЯ КАРТА НЕ ОБНАРУЖЕНА"

            1 ->
                "ВОДИТЕЛЬСКАЯ КАРТА УСТАНОВЛЕНА"

            0xFE ->
                "ERROR"

            0xFF ->
                "NOT AVAILABLE"

            else ->
                "НЕИЗВЕСТНОЕ ЗНАЧЕНИЕ ($value)"
        }
    }

    private fun timeRelatedStateToString(
        value: Int
    ): String {

        return when (
            value and
                0x0F
        ) {

            0x00 ->
                "ПРЕДУПРЕЖДЕНИЙ ПО ВРЕМЕНИ НЕТ"

            0x01 ->
                "ОКОЛО 15 МИН ДО 4:30 ВОЖДЕНИЯ"

            0x02 ->
                "4:30 ДОСТИГНУТО / ПРЕВЫШЕНО"

            0x03 ->
                "OPTIONAL WARNING #1"

            0x04 ->
                "OPTIONAL LIMIT #1"

            0x05 ->
                "OPTIONAL WARNING #2"

            0x06 ->
                "OPTIONAL LIMIT #2"

            0x0D ->
                "ДРУГОЕ ПРЕДУПРЕЖДЕНИЕ"

            0x0E ->
                "ERROR INDICATOR"

            0x0F ->
                "NOT AVAILABLE"

            else ->
                "RESERVED / НЕРАСШИФРОВАННО"
        }
    }

    private fun rhmiStatusToString(
        status: Int
    ): String {

        return when (status) {

            0x00 ->
                "Сессия закрыта; открытие возможно"

            0x01 ->
                "Ожидается решение пользователя"

            0x10 ->
                "Remote HMI session ОТКРЫТА"

            0x20 ->
                "Remote HMI отклонена"

            0x21 ->
                "Используется локальный HMI"

            0x2F ->
                "Общие условия RHMI не выполнены"

            else ->
                "Неизвестный статус 0x${hexByte(status)}"
        }
    }

    private fun negativeResponseToString(
        nrc: Int
    ): String {

        return when (nrc) {

            0x10 ->
                "generalReject"

            0x11 ->
                "serviceNotSupported"

            0x12 ->
                "subFunctionNotSupported"

            0x13 ->
                "incorrectMessageLengthOrInvalidFormat"

            0x21 ->
                "busyRepeatRequest"

            0x22 ->
                "conditionsNotCorrect"

            0x31 ->
                "requestOutOfRange"

            0x33 ->
                "securityAccessDenied"

            0x78 ->
                "requestCorrectlyReceivedResponsePending"

            0x7E ->
                "subFunctionNotSupportedInActiveSession"

            0x7F ->
                "serviceNotSupportedInActiveSession"

            else ->
                "NRC 0x${hexByte(nrc)}"
        }
    }

    private fun decodeUnsigned16(
        high: Byte,
        low: Byte
    ): Int {

        return (
            unsigned(
                high
            ) shl 8
            ) or
            unsigned(
                low
            )
    }

    private fun formatMinutes(
        minutes: Int
    ): String {

        val hours =
            minutes /
                60

        val mins =
            minutes %
                60

        return String.format(
            Locale.US,
            "%d:%02d",
            hours,
            mins
        )
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            gatt.writeDescriptor(
                descriptor,
                value
            ) ==
                BluetoothGatt.GATT_SUCCESS

        } else {

            @Suppress("DEPRECATION")
            descriptor.value =
                value

            @Suppress("DEPRECATION")
            gatt.writeDescriptor(
                descriptor
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic
                    .WRITE_TYPE_NO_RESPONSE
            ) ==
                BluetoothGatt.GATT_SUCCESS

        } else {

            @Suppress("DEPRECATION")
            characteristic.writeType =
                BluetoothGattCharacteristic
                    .WRITE_TYPE_NO_RESPONSE

            @Suppress("DEPRECATION")
            characteristic.value =
                value

            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(
                characteristic
            )
        }
    }

    private fun characteristicLabel(
        uuid: UUID
    ): String {

        return when (uuid) {

            UUID_DIAGNOSTICS_FIFO ->
                "Diagnostics FIFO"

            UUID_DIAGNOSTICS_CREDITS ->
                "Diagnostics Credits"

            else ->
                "Unknown"
        }
    }

    private fun unsigned(
        value: Byte
    ): Int {

        return value.toInt() and
            0xFF
    }

    private fun hexByte(
        value: Int
    ): String {

        return "%02X".format(
            Locale.US,
            value and
                0xFF
        )
    }

    private fun hexDid(
        did: Int
    ): String {

        return "%04X".format(
            Locale.US,
            did and
                0xFFFF
        )
    }

    private fun hexDidSpaced(
        did: Int
    ): String {

        return "${hexByte(did shr 8)} " +
            hexByte(
                did and 0xFF
            )
    }

    private fun bytesToHex(
        bytes: ByteArray
    ): String {

        if (
            bytes.isEmpty()
        ) {

            return "(empty)"
        }

        return bytes.joinToString(
            separator = " "
        ) {

            "%02X".format(
                Locale.US,
                unsigned(
                    it
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun appendDeviceInfo(
        device: BluetoothDevice
    ) {

        appendLog(
            "Устройство: ${safeDeviceName(device)}"
        )

        appendLog(
            "Адрес: ${safeDeviceAddress(device)}"
        )

        appendLog(
            "Bond state: ${bondStateToString(device.bondState)}"
        )

        appendLog(
            "Тип Bluetooth: ${deviceTypeToString(device.type)}"
        )

        appendLog(
            "Android API: ${Build.VERSION.SDK_INT}"
        )
    }

    fun disconnect() {

        connected =
            false

        servicesDiscovered =
            false

        waitingForResponse =
            false

        scanStarted =
            false

        requestGeneration +=
            1

        appendLog(
            "Ручное отключение."
        )

        closeGatt()

        notifyConnectionState(
            false,
            currentDevice?.let {

                safeDeviceName(
                    it
                )
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {

        val gatt =
            currentGatt

        currentGatt =
            null

        if (
            gatt != null
        ) {

            try {

                gatt.disconnect()

            } catch (
                _: Throwable
            ) {
            }

            try {

                gatt.close()

            } catch (
                _: Throwable
            ) {
            }
        }
    }

    fun clearLog() {

        logLines.clear()

        appendLog(
            "Журнал очищен."
        )
    }

    fun getLog(): String {

        return logLines.joinToString(
            separator = "\n"
        )
    }

    private fun appendLog(
        message: String
    ) {

        val timestamp =
            SimpleDateFormat(
                "HH:mm:ss.SSS",
                Locale.getDefault()
            ).format(
                Date()
            )

        logLines.add(
            "[$timestamp] $message"
        )

        while (
            logLines.size >
            MAX_LOG_LINES
        ) {

            try {

                logLines.removeAt(
                    0
                )

            } catch (
                _: Throwable
            ) {

                break
            }
        }

        val fullLog =
            getLog()

        mainHandler.post {

            try {

                listener?.onLogChanged(
                    fullLog
                )

            } catch (
                _: Throwable
            ) {
            }
        }
    }

    private fun notifyConnectionState(
        isConnected: Boolean,
        deviceName: String?
    ) {

        mainHandler.post {

            try {

                listener?.onConnectionStateChanged(
                    isConnected,
                    deviceName
                )

            } catch (
                _: Throwable
            ) {
            }
        }
    }

    private fun appendThrowable(
        place: String,
        throwable: Throwable
    ) {

        appendLog(
            "ОШИБКА [$place]"
        )

        appendLog(
            "Тип: ${throwable.javaClass.name}"
        )

        appendLog(
            "Сообщение: ${
                throwable.message
                    ?: "(без сообщения)"
            }"
        )
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(
        device: BluetoothDevice
    ): String {

        return try {

            device.name
                ?: "Без имени"

        } catch (
            _: Throwable
        ) {

            "Нет доступа"
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceAddress(
        device: BluetoothDevice
    ): String {

        return try {

            device.address

        } catch (
            _: Throwable
        ) {

            "Нет доступа"
        }
    }

    private fun bondStateToString(
        state: Int
    ): String {

        return when (state) {

            BluetoothDevice.BOND_NONE ->
                "BOND_NONE"

            BluetoothDevice.BOND_BONDING ->
                "BOND_BONDING"

            BluetoothDevice.BOND_BONDED ->
                "BOND_BONDED"

            else ->
                "UNKNOWN($state)"
        }
    }

    private fun deviceTypeToString(
        type: Int
    ): String {

        return when (type) {

            BluetoothDevice.DEVICE_TYPE_CLASSIC ->
                "CLASSIC"

            BluetoothDevice.DEVICE_TYPE_LE ->
                "LE"

            BluetoothDevice.DEVICE_TYPE_DUAL ->
                "DUAL"

            BluetoothDevice.DEVICE_TYPE_UNKNOWN ->
                "UNKNOWN"

            else ->
                "UNKNOWN($type)"
        }
    }

    private fun profileStateToString(
        state: Int
    ): String {

        return when (state) {

            BluetoothProfile.STATE_DISCONNECTED ->
                "DISCONNECTED"

            BluetoothProfile.STATE_CONNECTING ->
                "CONNECTING"

            BluetoothProfile.STATE_CONNECTED ->
                "CONNECTED"

            BluetoothProfile.STATE_DISCONNECTING ->
                "DISCONNECTING"

            else ->
                "UNKNOWN($state)"
        }
    }

    private fun gattStatusToString(
        status: Int
    ): String {

        return when (status) {

            BluetoothGatt.GATT_SUCCESS ->
                "GATT_SUCCESS"

            BluetoothGatt.GATT_READ_NOT_PERMITTED ->
                "GATT_READ_NOT_PERMITTED"

            BluetoothGatt.GATT_WRITE_NOT_PERMITTED ->
                "GATT_WRITE_NOT_PERMITTED"

            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ->
                "GATT_INSUFFICIENT_AUTHENTICATION"

            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED ->
                "GATT_REQUEST_NOT_SUPPORTED"

            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION ->
                "GATT_INSUFFICIENT_ENCRYPTION"

            BluetoothGatt.GATT_INVALID_OFFSET ->
                "GATT_INVALID_OFFSET"

            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH ->
                "GATT_INVALID_ATTRIBUTE_LENGTH"

            BluetoothGatt.GATT_CONNECTION_CONGESTED ->
                "GATT_CONNECTION_CONGESTED"

            BluetoothGatt.GATT_FAILURE ->
                "GATT_FAILURE"

            else ->
                "STATUS_$status"
        }
    }
}
