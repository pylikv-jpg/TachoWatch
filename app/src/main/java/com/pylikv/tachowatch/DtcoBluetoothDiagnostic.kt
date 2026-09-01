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

        fun onLogChanged(
            fullLog: String
        )

        fun onConnectionStateChanged(
            connected: Boolean,
            deviceName: String?
        )
    }

    companion object {

        private const val VERSION =
            "BLE-RHMI-DID-DECODER-1"

        private const val MAX_LOG_LINES =
            12000

        private const val INITIAL_CREDITS =
            1

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

    private data class ReadResult(
        val did: Int,
        val type: ResultType,
        val data: ByteArray = byteArrayOf(),
        val nrc: Int? = null,
        val decoded: String = ""
    )

    /*
     * Только DIDs, которые уже дали
     * положительный ответ на реальном DTCO.
     */
    private val readDids =
        listOf(
            0xF902,
            0xF903,
            0xF904,
            0xF905,
            0xF906,
            0xF907,
            0xF908,
            0xF909,
            0xF90A,
            0xF90B,
            0xF90D,
            0xF90E,
            0xF90F,

            0xF912,
            0xF913,
            0xF914,
            0xF915,
            0xF916,
            0xF918,
            0xF919,
            0xF91B,
            0xF91C,
            0xF91D,
            0xF91E,

            0xF920,
            0xF921,
            0xF922,
            0xF923,
            0xF924,
            0xF925,
            0xF926,
            0xF927,
            0xF928,
            0xF92C,

            0xF930,
            0xF931,
            0xF933,
            0xF936,
            0xF937,
            0xF938,
            0xF939
        )

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val logLines =
        CopyOnWriteArrayList<String>()

    private val results =
        linkedMapOf<Int, ReadResult>()

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
    private var diagnosticsTxCredits =
        0

    @Volatile
    private var fifoSubscribed =
        false

    @Volatile
    private var creditsSubscribed =
        false

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
    private var readingStarted =
        false

    @Volatile
    private var readIndex =
        0

    @Volatile
    private var waitingForResponse =
        false

    @Volatile
    private var currentDid:
        Int? = null

    @Volatile
    private var requestGeneration =
        0L

    fun hasConnectPermission():
        Boolean {

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

        if (
            !hasConnectPermission()
        ) {

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
            "TachoWatch — DTCO DID DECODER"
        )

        appendLog(
            "Версия: $VERSION"
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "ЦЕЛЕВОЙ READ-ONLY ТЕСТ"
        )

        appendLog(
            "Проверяем только подтверждённые DIDs: ${readDids.size}"
        )

        appendLog(
            "Чтение данных: UDS ReadDataByIdentifier 0x22"
        )

        appendLog(
            "Запись данных DTCO: НЕТ"
        )

        appendLog(
            "Изменение деятельности: НЕТ"
        )

        appendLog(
            "Запись в карту водителя: НЕТ"
        )

        appendLog(
            "F931 подтверждён: ФАМИЛИЯ ВОДИТЕЛЯ"
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
                "BluetoothGatt object = ${
                    if (
                        currentGatt != null
                    ) {
                        "CREATED"
                    } else {
                        "NULL"
                    }
                }"
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

        results.clear()

        connected =
            false

        servicesDiscovered =
            false

        diagnosticsTxCredits =
            0

        fifoSubscribed =
            false

        creditsSubscribed =
            false

        openRequestSent =
            false

        statusRequestSent =
            false

        rhmiOpened =
            false

        readingStarted =
            false

        readIndex =
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
            "TX Credits = $diagnosticsTxCredits"
        )

        appendLog(
            "RHMI opened = $rhmiOpened"
        )

        appendLog(
            "Read index = $readIndex / ${readDids.size}"
        )

        appendLog(
            "Waiting response = $waitingForResponse"
        )

        appendLog(
            "Current DID = ${
                currentDid?.let {
                    hexDid(
                        it
                    )
                } ?: "NONE"
            }"
        )

        appendLog(
            "Results = ${results.size}"
        )

        appendLog(
            "========================================"
        )
    }

    private val gattCallback =
        object :
            BluetoothGattCallback() {

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
                    "CALLBACK: onConnectionStateChange"
                )

                appendLog(
                    "status=$status (${gattStatusToString(status)})"
                )

                appendLog(
                    "newState=$newState (${profileStateToString(newState)})"
                )

                when (
                    newState
                ) {

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

                            val started =
                                gatt.requestMtu(
                                    512
                                )

                            appendLog(
                                "requestMtu(512) = $started"
                            )

                            if (
                                !started
                            ) {

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
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {

                appendLog(
                    "MTU = $mtu"
                )

                appendLog(
                    "MTU status = ${gattStatusToString(status)}"
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
                    "CALLBACK: onServicesDiscovered"
                )

                appendLog(
                    "status = ${gattStatusToString(status)}"
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

                if (
                    service == null
                ) {

                    appendLog(
                        "Diagnostics Service NOT FOUND"
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
                    "CCCD callback $uuid -> ${gattStatusToString(status)}"
                )

                if (
                    uuid ==
                    UUID_DIAGNOSTICS_FIFO
                ) {

                    fifoSubscribed =
                        status ==
                            BluetoothGatt.GATT_SUCCESS

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

                    creditsSubscribed =
                        status ==
                            BluetoothGatt.GATT_SUCCESS

                    mainHandler.postDelayed(
                        {

                            sendCredit(
                                gatt,
                                "INITIAL"
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
                    "TX ${characteristicLabel(characteristic.uuid)} -> " +
                        gattStatusToString(
                            status
                        )
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

        try {

            appendLog(
                "discoverServices()"
            )

            val started =
                gatt.discoverServices()

            appendLog(
                "discoverServices = $started"
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

        val characteristic =
            gatt
                .getService(
                    UUID_DIAGNOSTICS_SERVICE
                )
                ?.getCharacteristic(
                    UUID_DIAGNOSTICS_FIFO
                )

        if (
            characteristic == null
        ) {

            appendLog(
                "Diagnostics FIFO NOT FOUND"
            )

            return
        }

        appendLog(
            "Subscribe Diagnostics FIFO"
        )

        val notificationStarted =
            gatt.setCharacteristicNotification(
                characteristic,
                true
            )

        appendLog(
            "setCharacteristicNotification FIFO = $notificationStarted"
        )

        val descriptor =
            characteristic.getDescriptor(
                UUID_CCCD
            )

        if (
            descriptor == null
        ) {

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
            "FIFO CCCD write = $started"
        )
    }

    @SuppressLint("MissingPermission")
    private fun subscribeDiagnosticsCredits(
        gatt: BluetoothGatt
    ) {

        val characteristic =
            gatt
                .getService(
                    UUID_DIAGNOSTICS_SERVICE
                )
                ?.getCharacteristic(
                    UUID_DIAGNOSTICS_CREDITS
                )

        if (
            characteristic == null
        ) {

            appendLog(
                "Diagnostics Credits NOT FOUND"
            )

            return
        }

        appendLog(
            "Subscribe Diagnostics Credits"
        )

        val notificationStarted =
            gatt.setCharacteristicNotification(
                characteristic,
                true
            )

        appendLog(
            "setCharacteristicNotification Credits = $notificationStarted"
        )

        val descriptor =
            characteristic.getDescriptor(
                UUID_CCCD
            )

        if (
            descriptor == null
        ) {

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
            "Credits CCCD write = $started"
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

        if (
            characteristic == null
        ) {

            appendLog(
                "Credits characteristic NOT FOUND"
            )

            return
        }

        val data =
            byteArrayOf(
                INITIAL_CREDITS.toByte()
            )

        val started =
            writeCharacteristicCompat(
                gatt,
                characteristic,
                data
            )

        appendLog(
            "TX RX-CREDIT [$label] = 01, started=$started"
        )
    }

    private fun handleIncoming(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {

        when (
            characteristic.uuid
        ) {

            UUID_DIAGNOSTICS_CREDITS -> {

                handleDiagnosticsCredits(
                    gatt,
                    value
                )
            }

            UUID_DIAGNOSTICS_FIFO -> {

                handleDiagnosticsFifo(
                    gatt,
                    value
                )
            }
        }
    }

    private fun handleDiagnosticsCredits(
        gatt: BluetoothGatt,
        value: ByteArray
    ) {

        if (
            value.isEmpty()
        ) {
            return
        }

        val credits =
            unsigned(
                value[0]
            )

        appendLog(
            "RX TX-CREDITS = $credits"
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

        if (
            diagnosticsTxCredits >
                0 &&
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
            readingStarted &&
            !waitingForResponse &&
            readIndex <
                readDids.size &&
            diagnosticsTxCredits >
                0
        ) {

            mainHandler.postDelayed(
                {

                    sendCurrentRead(
                        gatt
                    )

                },
                150L
            )
        }
    }

    private fun sendOpenRhmiRequest(
        gatt: BluetoothGatt
    ) {

        if (
            openRequestSent ||
            diagnosticsTxCredits <=
                0
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
            "OPEN RHMI SESSION"
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
            "Open session write = $started"
        )

        if (
            started
        ) {

            openRequestSent =
                true

            diagnosticsTxCredits -=
                1
        }
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
                "RX FIFO too short: ${bytesToHex(value)}"
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
            "RX FIFO [$packetNumber/$totalPackets] ${bytesToHex(appData)}"
        )

        /*
         * Open RHMI positive response.
         */
        if (
            appData.size >=
            4 &&
            unsigned(appData[0]) ==
                0x71 &&
            unsigned(appData[1]) ==
                0x01 &&
            unsigned(appData[2]) ==
                0xF2 &&
            unsigned(appData[3]) ==
                0x11
        ) {

            appendLog(
                "OPEN RHMI POSITIVE"
            )

            sendCredit(
                gatt,
                "RHMI-STATUS"
            )

            mainHandler.postDelayed(
                {

                    trySendRhmiStatus(
                        gatt,
                        1
                    )

                },
                400L
            )

            return
        }

        /*
         * RHMI session status.
         */
        if (
            appData.size >=
            5 &&
            unsigned(appData[0]) ==
                0x71 &&
            unsigned(appData[1]) ==
                0x03 &&
            unsigned(appData[2]) ==
                0xF2 &&
            unsigned(appData[3]) ==
                0x11
        ) {

            val status =
                unsigned(
                    appData[4]
                )

            appendLog(
                ""
            )

            appendLog(
                "RHMI STATUS = 0x${hexByte(status)}"
            )

            appendLog(
                rhmiStatusToString(
                    status
                )
            )

            if (
                status ==
                0x10
            ) {

                rhmiOpened =
                    true

                appendLog(
                    ">>> RHMI SESSION OPENED <<<"
                )

                mainHandler.postDelayed(
                    {

                        startReads(
                            gatt
                        )

                    },
                    300L
                )
            }

            return
        }

        /*
         * Positive ReadDataByIdentifier.
         */
        if (
            appData.size >=
            3 &&
            unsigned(appData[0]) ==
                0x62
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
                data
            )

            return
        }

        /*
         * Negative UDS response.
         */
        if (
            appData.size >=
            3 &&
            unsigned(appData[0]) ==
                0x7F
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
                "UDS NEGATIVE: service=0x${hexByte(originalService)} " +
                    "NRC=0x${hexByte(nrc)} " +
                    negativeResponseToString(
                        nrc
                    )
            )

            if (
                originalService ==
                0x22 &&
                waitingForResponse
            ) {

                val did =
                    currentDid

                if (
                    did != null
                ) {

                    results[
                        did
                    ] =
                        ReadResult(
                            did = did,
                            type = ResultType.NRC,
                            nrc = nrc,
                            decoded =
                                negativeResponseToString(
                                    nrc
                                )
                        )
                }

                completeCurrentRead(
                    gatt
                )
            }
        }
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
                "RHMI STATUS: NO TX CREDIT"
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
            diagnosticsTxCredits <=
                0
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

        val started =
            writeCharacteristicCompat(
                gatt,
                fifo,
                packet
            )

        appendLog(
            "RHMI status request = $started"
        )

        if (
            started
        ) {

            statusRequestSent =
                true

            diagnosticsTxCredits -=
                1
        }
    }

    private fun startReads(
        gatt: BluetoothGatt
    ) {

        if (
            readingStarted
        ) {
            return
        }

        readingStarted =
            true

        readIndex =
            0

        waitingForResponse =
            false

        currentDid =
            null

        results.clear()

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "TARGET DID DECODER START"
        )

        appendLog(
            "DIDs = ${readDids.size}"
        )

        appendLog(
            "========================================"
        )

        prepareNextRead(
            gatt
        )
    }

    private fun prepareNextRead(
        gatt: BluetoothGatt
    ) {

        if (
            !connected
        ) {
            return
        }

        if (
            readIndex >=
            readDids.size
        ) {

            finishReads()

            return
        }

        if (
            waitingForResponse
        ) {
            return
        }

        val did =
            readDids[
                readIndex
            ]

        sendCredit(
            gatt,
            "RX-${hexDid(did)}"
        )

        mainHandler.postDelayed(
            {

                trySendCurrentRead(
                    gatt,
                    1
                )

            },
            300L
        )
    }

    private fun trySendCurrentRead(
        gatt: BluetoothGatt,
        attempt: Int
    ) {

        if (
            waitingForResponse
        ) {
            return
        }

        if (
            readIndex >=
            readDids.size
        ) {

            finishReads()

            return
        }

        if (
            diagnosticsTxCredits >
            0
        ) {

            sendCurrentRead(
                gatt
            )

            return
        }

        if (
            attempt >=
            15
        ) {

            val did =
                readDids[
                    readIndex
                ]

            results[
                did
            ] =
                ReadResult(
                    did = did,
                    type = ResultType.ERROR,
                    decoded = "NO TX CREDIT"
                )

            appendLog(
                "${hexDid(did)} -> NO TX CREDIT"
            )

            readIndex +=
                1

            mainHandler.postDelayed(
                {

                    prepareNextRead(
                        gatt
                    )

                },
                NEXT_REQUEST_DELAY_MS
            )

            return
        }

        mainHandler.postDelayed(
            {

                trySendCurrentRead(
                    gatt,
                    attempt + 1
                )

            },
            200L
        )
    }

    private fun sendCurrentRead(
        gatt: BluetoothGatt
    ) {

        if (
            waitingForResponse ||
            diagnosticsTxCredits <=
                0 ||
            readIndex >=
                readDids.size
        ) {
            return
        }

        val did =
            readDids[
                readIndex
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
                    did and
                        0xFF
                    ).toByte()
            )

        appendLog(
            ""
        )

        appendLog(
            "READ ${readIndex + 1}/${readDids.size}: ${hexDid(did)}"
        )

        val started =
            writeCharacteristicCompat(
                gatt,
                fifo,
                packet
            )

        appendLog(
            "TX 22 ${hexDidSpaced(did)} -> $started"
        )

        if (
            !started
        ) {

            results[
                did
            ] =
                ReadResult(
                    did = did,
                    type = ResultType.ERROR,
                    decoded = "WRITE START FAILED"
                )

            readIndex +=
                1

            mainHandler.postDelayed(
                {

                    prepareNextRead(
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

                handleTimeout(
                    gatt,
                    did,
                    generation
                )

            },
            RESPONSE_TIMEOUT_MS
        )
    }

    private fun handleTimeout(
        gatt: BluetoothGatt,
        did: Int,
        generation: Long
    ) {

        if (
            generation !=
            requestGeneration
        ) {
            return
        }

        if (
            !waitingForResponse ||
            currentDid !=
                did
        ) {
            return
        }

        results[
            did
        ] =
            ReadResult(
                did = did,
                type = ResultType.TIMEOUT,
                decoded = "TIMEOUT"
            )

        appendLog(
            "${hexDid(did)} -> TIMEOUT"
        )

        completeCurrentRead(
            gatt
        )
    }

    private fun handlePositiveRead(
        gatt: BluetoothGatt,
        did: Int,
        data: ByteArray
    ) {

        val decoded =
            decodeDid(
                did,
                data
            )

        results[
            did
        ] =
            ReadResult(
                did = did,
                type = ResultType.POSITIVE,
                data = data.copyOf(),
                decoded = decoded
            )

        appendLog(
            "${hexDid(did)} -> POSITIVE"
        )

        appendLog(
            "RAW = ${bytesToHex(data)}"
        )

        appendLog(
            "DECODED = $decoded"
        )

        if (
            waitingForResponse &&
            currentDid ==
                did
        ) {

            completeCurrentRead(
                gatt
            )
        }
    }

    private fun completeCurrentRead(
        gatt: BluetoothGatt
    ) {

        requestGeneration +=
            1

        waitingForResponse =
            false

        currentDid =
            null

        readIndex +=
            1

        mainHandler.postDelayed(
            {

                prepareNextRead(
                    gatt
                )

            },
            NEXT_REQUEST_DELAY_MS
        )
    }

    private fun finishReads() {

        readingStarted =
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
            "DID DECODER COMPLETE"
        )

        appendLog(
            "========================================"
        )

        appendLog(
            ""
        )

        appendLog(
            "====== ОСНОВНЫЕ ПОДТВЕРЖДЁННЫЕ ДАННЫЕ ======"
        )

        appendResult(
            0xF902,
            "Скорость автомобиля"
        )

        appendResult(
            0xF903,
            "Текущая деятельность водителя"
        )

        appendResult(
            0xF905,
            "Состояние движения автомобиля"
        )

        appendResult(
            0xF906,
            "Предупреждения по времени"
        )

        appendResult(
            0xF907,
            "Карта водителя в слоте 1"
        )

        appendResult(
            0xF923,
            "Непрерывное вождение"
        )

        appendResult(
            0xF925,
            "Накопленная пауза"
        )

        appendResult(
            0xF927,
            "Длительность текущей деятельности"
        )

        appendResult(
            0xF938,
            "Вождение за текущую + предыдущую неделю"
        )

        appendResult(
            0xF931,
            "Фамилия водителя"
        )

        appendLog(
            ""
        )

        appendLog(
            "====== НЕИЗВЕСТНЫЕ / ИССЛЕДУЕМЫЕ DIDs ======"
        )

        readDids.forEach {
            did ->

            if (
                did !=
                    0xF902 &&
                did !=
                    0xF903 &&
                did !=
                    0xF905 &&
                did !=
                    0xF906 &&
                did !=
                    0xF907 &&
                did !=
                    0xF923 &&
                did !=
                    0xF925 &&
                did !=
                    0xF927 &&
                did !=
                    0xF938 &&
                did !=
                    0xF931
            ) {

                appendResult(
                    did,
                    "Исследуемый параметр"
                )
            }
        }

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "ВАЖНО ДЛЯ СЛЕДУЮЩЕГО АНАЛИЗА:"
        )

        appendLog(
            "Нужны скрины начиная с"
        )

        appendLog(
            "====== НЕИЗВЕСТНЫЕ / ИССЛЕДУЕМЫЕ DIDs ======"
        )

        appendLog(
            "и до конца."
        )

        appendLog(
            "========================================"
        )
    }

    private fun appendResult(
        did: Int,
        title: String
    ) {

        val result =
            results[
                did
            ]

        appendLog(
            ""
        )

        appendLog(
            "$title — ${hexDid(did)}"
        )

        if (
            result ==
            null
        ) {

            appendLog(
                "  NO RESULT"
            )

            return
        }

        when (
            result.type
        ) {

            ResultType.POSITIVE -> {

                appendLog(
                    "  RAW: ${bytesToHex(result.data)}"
                )

                appendLog(
                    "  ${result.decoded}"
                )
            }

            ResultType.NRC -> {

                appendLog(
                    "  NRC 0x${hexByte(result.nrc ?: 0)}"
                )

                appendLog(
                    "  ${result.decoded}"
                )
            }

            ResultType.TIMEOUT -> {

                appendLog(
                    "  TIMEOUT"
                )
            }

            ResultType.ERROR -> {

                appendLog(
                    "  ERROR: ${result.decoded}"
                )
            }
        }
    }

    private fun decodeDid(
        did: Int,
        data: ByteArray
    ): String {

        return when (
            did
        ) {

            0xF902 -> {

                decodeSpeed(
                    data
                )
            }

            0xF903 -> {

                decodeActivity(
                    data
                )
            }

            0xF905 -> {

                decodeMotion(
                    data
                )
            }

            0xF906 -> {

                decodeTimeWarning(
                    data
                )
            }

            0xF907 -> {

                decodeDriverCard(
                    data
                )
            }

            0xF923,
            0xF925,
            0xF927,
            0xF938 -> {

                decodeMinutes(
                    data
                )
            }

            /*
             * Подтверждено пользователем:
             * PYLIK = фамилия водителя.
             */
            0xF931 -> {

                decodeDriverSurname(
                    data
                )
            }

            /*
             * У F916 уже был явно видимый ASCII.
             */
            0xF916 -> {

                decodeGeneric(
                    data,
                    preferAscii = true
                )
            }

            else -> {

                decodeGeneric(
                    data,
                    preferAscii = false
                )
            }
        }
    }

    private fun decodeDriverSurname(
        data: ByteArray
    ): String {

        val ascii =
            extractPrintableAscii(
                data
            )

        return if (
            ascii.isNotBlank()
        ) {

            "ФАМИЛИЯ ВОДИТЕЛЯ / ASCII: \"$ascii\" | " +
                numericRepresentations(
                    data
                )

        } else {

            "ФАМИЛИЯ ВОДИТЕЛЯ | ASCII не распознан | " +
                numericRepresentations(
                    data
                )
        }
    }

    private fun decodeGeneric(
        data: ByteArray,
        preferAscii: Boolean
    ): String {

        if (
            data.isEmpty()
        ) {

            return "EMPTY"
        }

        val parts =
            mutableListOf<String>()

        parts.add(
            "len=${data.size}"
        )

        val ascii =
            extractPrintableAscii(
                data
            )

        val printableCount =
            data.count {

                val v =
                    unsigned(
                        it
                    )

                v in
                    32..126
            }

        val mostlyPrintable =
            data.isNotEmpty() &&
                printableCount.toDouble() /
                data.size.toDouble() >=
                0.60

        if (
            ascii.isNotBlank() &&
            (
                preferAscii ||
                    mostlyPrintable
                )
        ) {

            parts.add(
                "ASCII=\"$ascii\""
            )
        }

        val numeric =
            numericRepresentations(
                data
            )

        if (
            numeric.isNotBlank()
        ) {

            parts.add(
                numeric
            )
        }

        return parts.joinToString(
            separator = " | "
        )
    }

    private fun numericRepresentations(
        data: ByteArray
    ): String {

        val parts =
            mutableListOf<String>()

        if (
            data.size ==
            1
        ) {

            parts.add(
                "u8=${unsigned(data[0])}"
            )
        }

        if (
            data.size >=
            2
        ) {

            val be16 =
                decodeUnsigned16(
                    data[0],
                    data[1]
                )

            val le16 =
                decodeUnsigned16(
                    data[1],
                    data[0]
                )

            parts.add(
                "u16BE=$be16"
            )

            parts.add(
                "u16LE=$le16"
            )

            if (
                data.size ==
                2 &&
                be16 <
                10000
            ) {

                parts.add(
                    "BE-as-min=${formatMinutes(be16)}"
                )
            }
        }

        if (
            data.size ==
            3
        ) {

            val be24 =
                (
                    unsigned(
                        data[0]
                    ).toLong() shl 16
                    ) or
                    (
                        unsigned(
                            data[1]
                        ).toLong() shl 8
                    ) or
                    unsigned(
                        data[2]
                    ).toLong()

            parts.add(
                "u24BE=$be24"
            )
        }

        if (
            data.size >=
            4
        ) {

            val be32 =
                decodeUnsigned32BE(
                    data
                )

            val le32 =
                decodeUnsigned32LE(
                    data
                )

            parts.add(
                "u32BE=$be32"
            )

            parts.add(
                "u32LE=$le32"
            )
        }

        return parts.joinToString(
            separator = " | "
        )
    }

    private fun extractPrintableAscii(
        data: ByteArray
    ): String {

        if (
            data.isEmpty()
        ) {

            return ""
        }

        val builder =
            StringBuilder()

        data.forEach {
            byte ->

            val value =
                unsigned(
                    byte
                )

            when {

                value in
                    32..126 -> {

                    builder.append(
                        value.toChar()
                    )
                }

                value ==
                    0 -> {

                    builder.append(
                        ' '
                    )
                }

                else -> {

                    /*
                     * Непечатный байт не вставляем.
                     */
                }
            }
        }

        return builder
            .toString()
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()
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

                "NOT AVAILABLE | RAW=${bytesToHex(data)}"
            }

            raw >=
                0xFE00 -> {

                "ERROR | RAW=${bytesToHex(data)}"
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

    private fun decodeActivity(
        data: ByteArray
    ): String {

        if (
            data.isEmpty()
        ) {

            return "EMPTY"
        }

        val value =
            unsigned(
                data[0]
            )

        return "$value — ${
            when (
                value
            ) {

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
                    "НЕИЗВЕСТНО"
            }
        }"
    }

    private fun decodeMotion(
        data: ByteArray
    ): String {

        if (
            data.isEmpty()
        ) {

            return "EMPTY"
        }

        val value =
            unsigned(
                data[0]
            )

        return "$value — ${
            when (
                value
            ) {

                0 ->
                    "АВТОМОБИЛЬ НЕ ДВИЖЕТСЯ"

                1 ->
                    "АВТОМОБИЛЬ ДВИЖЕТСЯ"

                0xFE ->
                    "ERROR"

                0xFF ->
                    "NOT AVAILABLE"

                else ->
                    "НЕИЗВЕСТНО"
            }
        }"
    }

    private fun decodeTimeWarning(
        data: ByteArray
    ): String {

        if (
            data.isEmpty()
        ) {

            return "EMPTY"
        }

        val value =
            unsigned(
                data[0]
            )

        val low =
            value and
                0x0F

        val meaning =

            when (
                low
            ) {

                0 ->
                    "ПРЕДУПРЕЖДЕНИЙ НЕТ"

                1 ->
                    "ОКОЛО 15 МИН ДО 4:30"

                2 ->
                    "4:30 ДОСТИГНУТО / ПРЕВЫШЕНО"

                3 ->
                    "OPTIONAL WARNING 1"

                4 ->
                    "OPTIONAL LIMIT 1"

                5 ->
                    "OPTIONAL WARNING 2"

                6 ->
                    "OPTIONAL LIMIT 2"

                0x0D ->
                    "ДРУГОЕ ПРЕДУПРЕЖДЕНИЕ"

                0x0E ->
                    "ERROR"

                0x0F ->
                    "NOT AVAILABLE"

                else ->
                    "RESERVED"
            }

        return "0x${hexByte(value)} — $meaning"
    }

    private fun decodeDriverCard(
        data: ByteArray
    ): String {

        if (
            data.isEmpty()
        ) {

            return "EMPTY"
        }

        val value =
            unsigned(
                data[0]
            )

        return "$value — ${
            when (
                value
            ) {

                0 ->
                    "КАРТА ВОДИТЕЛЯ НЕ ОБНАРУЖЕНА"

                1 ->
                    "КАРТА ВОДИТЕЛЯ УСТАНОВЛЕНА"

                0xFE ->
                    "ERROR"

                0xFF ->
                    "NOT AVAILABLE"

                else ->
                    "НЕИЗВЕСТНО"
            }
        }"
    }

    private fun decodeMinutes(
        data: ByteArray
    ): String {

        if (
            data.size <
            2
        ) {

            return "INVALID LENGTH ${data.size}"
        }

        val minutes =
            decodeUnsigned16(
                data[0],
                data[1]
            )

        return when {

            minutes >=
                0xFF00 -> {

                "NOT AVAILABLE"
            }

            minutes >=
                0xFE00 -> {

                "ERROR"
            }

            minutes >=
                0xFB00 -> {

                "SPECIAL INDICATOR"
            }

            else -> {

                "$minutes min = ${formatMinutes(minutes)}"
            }
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

    private fun decodeUnsigned32BE(
        data: ByteArray
    ): Long {

        if (
            data.size <
            4
        ) {

            return 0L
        }

        return (
            unsigned(
                data[0]
            ).toLong() shl 24
            ) or
            (
                unsigned(
                    data[1]
                ).toLong() shl 16
                ) or
            (
                unsigned(
                    data[2]
                ).toLong() shl 8
                ) or
            unsigned(
                data[3]
            ).toLong()
    }

    private fun decodeUnsigned32LE(
        data: ByteArray
    ): Long {

        if (
            data.size <
            4
        ) {

            return 0L
        }

        return (
            unsigned(
                data[3]
            ).toLong() shl 24
            ) or
            (
                unsigned(
                    data[2]
                ).toLong() shl 16
                ) or
            (
                unsigned(
                    data[1]
                ).toLong() shl 8
                ) or
            unsigned(
                data[0]
            ).toLong()
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

    private fun rhmiStatusToString(
        status: Int
    ): String {

        return when (
            status
        ) {

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
                "Условия RHMI не выполнены"

            else ->
                "Неизвестный статус"
        }
    }

    private fun negativeResponseToString(
        nrc: Int
    ): String {

        return when (
            nrc
        ) {

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
                "responsePending"

            0x7E ->
                "subFunctionNotSupportedInActiveSession"

            0x7F ->
                "serviceNotSupportedInActiveSession"

            else ->
                "UNKNOWN NRC"
        }
    }

    private fun characteristicLabel(
        uuid: UUID
    ): String {

        return when (
            uuid
        ) {

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
                did and
                    0xFF
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

        readingStarted =
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

    fun getLog():
        String {

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

        return when (
            state
        ) {

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

        return when (
            type
        ) {

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

        return when (
            state
        ) {

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

        return when (
            status
        ) {

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
