package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
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
            "BLE-RHMI-D1-ACTIVITY-1"

        private const val MAX_LOG_LINES =
            5000

        private const val INITIAL_CREDITS =
            1

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

        private val UUID_DOWNLOAD_SERVICE =
            UUID.fromString(
                "eef90782-55dd-4388-b80b-695aba7a69b5"
            )

        private val UUID_DOWNLOAD_FIFO =
            UUID.fromString(
                "29d3a479-1592-47df-80a4-afa742d369bb"
            )

        private val UUID_DOWNLOAD_CREDITS =
            UUID.fromString(
                "db9c4128-bff3-41fe-a306-fb6f9a8aeb2d"
            )

        private val UUID_EXTRA_SERVICE =
            UUID.fromString(
                "36ab9731-5701-489a-943a-5e16a9dd3183"
            )

        private val UUID_EXTRA_NOTIFY =
            UUID.fromString(
                "c9446e2e-527f-48eb-bbd4-f3c5e79ee716"
            )
    }

    private enum class SubscriptionMode {
        INDICATE,
        NOTIFY
    }

    private data class SubscriptionItem(
        val label: String,
        val characteristic: BluetoothGattCharacteristic,
        val mode: SubscriptionMode
    )

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val bluetoothManager =
        context.getSystemService(
            Context.BLUETOOTH_SERVICE
        ) as BluetoothManager

    private val logLines =
        CopyOnWriteArrayList<String>()

    private val subscriptionQueue =
        ArrayDeque<SubscriptionItem>()

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
    private var subscriptionInProgress =
        false

    @Volatile
    private var handshakeStarted =
        false

    @Volatile
    private var diagnosticsTxCredits =
        0

    @Volatile
    private var downloadTxCredits =
        0

    @Volatile
    private var openRequestSent =
        false

    @Volatile
    private var openPositiveResponseReceived =
        false

    @Volatile
    private var statusRequestSent =
        false

    @Volatile
    private var finalRhmiStatus:
        Int? = null

    @Volatile
    private var activityRequestSent =
        false

    @Volatile
    private var driver1WorkingState:
        Int? = null

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

        logLines.clear()
        subscriptionQueue.clear()

        currentDevice =
            device

        connected =
            false

        servicesDiscovered =
            false

        subscriptionInProgress =
            false

        handshakeStarted =
            false

        diagnosticsTxCredits =
            0

        downloadTxCredits =
            0

        openRequestSent =
            false

        openPositiveResponseReceived =
            false

        statusRequestSent =
            false

        finalRhmiStatus =
            null

        activityRequestSent =
            false

        driver1WorkingState =
            null

        appendLog(
            "========================================"
        )

        appendLog(
            "TachoWatch — DTCO Remote HMI"
        )

        appendLog(
            "Версия диагностики: $VERSION"
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "READ-ONLY TEST"
        )

        appendLog(
            "Карта водителя: ЗАПИСИ НЕТ"
        )

        appendLog(
            "Деятельность водителя: ИЗМЕНЕНИЯ НЕТ"
        )

        appendLog(
            "DTCO: ИЗМЕНЕНИЯ ДАННЫХ НЕТ"
        )

        appendLog(
            "Download FIFO: НЕ используется"
        )

        appendLog(
            "После открытия RHMI читаем только:"
        )

        appendLog(
            "Driver1WorkingState DID F903"
        )

        appendLog(
            "UDS ReadDataByIdentifier = 22 F9 03"
        )

        appendLog(
            "========================================"
        )

        appendDeviceInfo(
            device
        )

        appendLog(
            "STEP 1: connectGatt()"
        )

        try {

            val gatt =

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

            currentGatt =
                gatt

            appendLog(
                "BluetoothGatt object = ${
                    if (
                        gatt != null
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
            "Diagnostics TX Credits = $diagnosticsTxCredits"
        )

        appendLog(
            "Open request sent = $openRequestSent"
        )

        appendLog(
            "Open positive response = $openPositiveResponseReceived"
        )

        appendLog(
            "RHMI status = ${
                finalRhmiStatus?.let {
                    "0x${
                        "%02X".format(
                            Locale.US,
                            it
                        )
                    }"
                } ?: "UNKNOWN"
            }"
        )

        appendLog(
            "Activity request sent = $activityRequestSent"
        )

        appendLog(
            "Driver1WorkingState = ${
                driver1WorkingState?.let {
                    "$it (${driverActivityToString(it)})"
                } ?: "UNKNOWN"
            }"
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
                    "========================================"
                )

                appendLog(
                    "CALLBACK: onConnectionStateChange"
                )

                appendLog(
                    "status = $status (${gattStatusToString(status)})"
                )

                appendLog(
                    "newState = $newState (${profileStateToString(newState)})"
                )

                appendLog(
                    "========================================"
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
                    "status = $status (${gattStatusToString(status)})"
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
                    "status = $status (${gattStatusToString(status)})"
                )

                appendLog(
                    "========================================"
                )

                if (
                    status ==
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    servicesDiscovered =
                        true

                    appendLog(
                        "Services = ${gatt.services.size}"
                    )

                    prepareSubscriptions(
                        gatt
                    )

                } else {

                    appendLog(
                        "SERVICE DISCOVERY ERROR"
                    )
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {

                appendLog(
                    ""
                )

                appendLog(
                    "CALLBACK: onDescriptorWrite"
                )

                appendLog(
                    "Characteristic = ${
                        descriptor.characteristic.uuid
                    }"
                )

                appendLog(
                    "status = $status (${gattStatusToString(status)})"
                )

                subscriptionInProgress =
                    false

                mainHandler.postDelayed(
                    {

                        subscribeNext(
                            gatt
                        )

                    },
                    120L
                )
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {

                appendLog(
                    ""
                )

                appendLog(
                    "CALLBACK: onCharacteristicWrite"
                )

                appendLog(
                    "UUID = ${characteristic.uuid}"
                )

                appendLog(
                    "status = $status (${gattStatusToString(status)})"
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

            override fun onServiceChanged(
                gatt: BluetoothGatt
            ) {

                appendLog(
                    "CALLBACK: onServiceChanged"
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

    private fun prepareSubscriptions(
        gatt: BluetoothGatt
    ) {

        subscriptionQueue.clear()

        subscriptionInProgress =
            false

        addSubscription(
            gatt,
            UUID_DIAGNOSTICS_SERVICE,
            UUID_DIAGNOSTICS_FIFO,
            "Diagnostics FIFO",
            SubscriptionMode.INDICATE
        )

        addSubscription(
            gatt,
            UUID_DIAGNOSTICS_SERVICE,
            UUID_DIAGNOSTICS_CREDITS,
            "Diagnostics Credits",
            SubscriptionMode.INDICATE
        )

        addSubscription(
            gatt,
            UUID_DOWNLOAD_SERVICE,
            UUID_DOWNLOAD_FIFO,
            "Download FIFO",
            SubscriptionMode.INDICATE
        )

        addSubscription(
            gatt,
            UUID_DOWNLOAD_SERVICE,
            UUID_DOWNLOAD_CREDITS,
            "Download Credits",
            SubscriptionMode.INDICATE
        )

        addSubscription(
            gatt,
            UUID_EXTRA_SERVICE,
            UUID_EXTRA_NOTIFY,
            "Extra NOTIFY",
            SubscriptionMode.NOTIFY
        )

        appendLog(
            ""
        )

        appendLog(
            "STEP 4: subscription count = " +
                subscriptionQueue.size
        )

        subscribeNext(
            gatt
        )
    }

    private fun addSubscription(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        label: String,
        mode: SubscriptionMode
    ) {

        val service =
            gatt.getService(
                serviceUuid
            )

        if (
            service == null
        ) {

            appendLog(
                "$label: SERVICE NOT FOUND"
            )

            return
        }

        val characteristic =
            service.getCharacteristic(
                characteristicUuid
            )

        if (
            characteristic == null
        ) {

            appendLog(
                "$label: CHARACTERISTIC NOT FOUND"
            )

            return
        }

        subscriptionQueue.addLast(
            SubscriptionItem(
                label = label,
                characteristic = characteristic,
                mode = mode
            )
        )

        appendLog(
            "$label: added"
        )
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNext(
        gatt: BluetoothGatt
    ) {

        if (
            subscriptionInProgress
        ) {

            return
        }

        if (
            subscriptionQueue.isEmpty()
        ) {

            appendLog(
                ""
            )

            appendLog(
                "========================================"
            )

            appendLog(
                "SUBSCRIPTION SETUP COMPLETE"
            )

            appendLog(
                "========================================"
            )

            mainHandler.postDelayed(
                {

                    startCreditsHandshake(
                        gatt
                    )

                },
                400L
            )

            return
        }

        val item =
            subscriptionQueue.removeFirst()

        val characteristic =
            item.characteristic

        appendLog(
            ""
        )

        appendLog(
            "SUBSCRIBE: ${item.label}"
        )

        appendLog(
            "UUID: ${characteristic.uuid}"
        )

        appendLog(
            "Mode: ${item.mode}"
        )

        val localResult =

            try {

                gatt.setCharacteristicNotification(
                    characteristic,
                    true
                )

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "setCharacteristicNotification",
                    e
                )

                false
            }

        appendLog(
            "setCharacteristicNotification = $localResult"
        )

        if (
            !localResult
        ) {

            mainHandler.postDelayed(
                {

                    subscribeNext(
                        gatt
                    )

                },
                100L
            )

            return
        }

        val descriptor =
            characteristic.getDescriptor(
                UUID_CCCD
            )

        if (
            descriptor == null
        ) {

            appendLog(
                "CCCD NOT FOUND"
            )

            mainHandler.postDelayed(
                {

                    subscribeNext(
                        gatt
                    )

                },
                100L
            )

            return
        }

        val cccdValue =

            if (
                item.mode ==
                SubscriptionMode.INDICATE
            ) {

                BluetoothGattDescriptor
                    .ENABLE_INDICATION_VALUE

            } else {

                BluetoothGattDescriptor
                    .ENABLE_NOTIFICATION_VALUE
            }

        subscriptionInProgress =
            true

        val started =

            try {

                writeDescriptorCompat(
                    gatt,
                    descriptor,
                    cccdValue
                )

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "write CCCD",
                    e
                )

                false
            }

        appendLog(
            "CCCD write started = $started"
        )

        if (
            !started
        ) {

            subscriptionInProgress =
                false

            mainHandler.postDelayed(
                {

                    subscribeNext(
                        gatt
                    )

                },
                120L
            )
        }
    }

    private fun startCreditsHandshake(
        gatt: BluetoothGatt
    ) {

        if (
            handshakeStarted
        ) {

            return
        }

        handshakeStarted =
            true

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "STEP 5: CREDITS HANDSHAKE"
        )

        appendLog(
            "========================================"
        )

        sendCredit(
            gatt,
            UUID_DIAGNOSTICS_SERVICE,
            UUID_DIAGNOSTICS_CREDITS,
            "Diagnostics"
        )

        mainHandler.postDelayed(
            {

                sendCredit(
                    gatt,
                    UUID_DOWNLOAD_SERVICE,
                    UUID_DOWNLOAD_CREDITS,
                    "Download"
                )

            },
            400L
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendCredit(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        label: String
    ) {

        val service =
            gatt.getService(
                serviceUuid
            )

        if (
            service == null
        ) {

            appendLog(
                "$label Credits: SERVICE NOT FOUND"
            )

            return
        }

        val characteristic =
            service.getCharacteristic(
                characteristicUuid
            )

        if (
            characteristic == null
        ) {

            appendLog(
                "$label Credits: CHARACTERISTIC NOT FOUND"
            )

            return
        }

        val data =
            byteArrayOf(
                INITIAL_CREDITS.toByte()
            )

        appendLog(
            ""
        )

        appendLog(
            "TX CREDIT: $label"
        )

        appendLog(
            "HEX: ${bytesToHex(data)}"
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

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "RX FROM DTCO"
        )

        appendLog(
            "Source: ${
                characteristicLabel(
                    uuid
                )
            }"
        )

        appendLog(
            "Length: ${value.size}"
        )

        appendLog(
            "HEX: ${bytesToHex(value)}"
        )

        when (
            uuid
        ) {

            UUID_DIAGNOSTICS_CREDITS -> {

                handleDiagnosticsCredits(
                    gatt,
                    value
                )
            }

            UUID_DOWNLOAD_CREDITS -> {

                handleDownloadCredits(
                    value
                )
            }

            UUID_DIAGNOSTICS_FIFO -> {

                handleDiagnosticsFifo(
                    gatt,
                    value
                )
            }

            UUID_DOWNLOAD_FIFO -> {

                appendLog(
                    "Download FIFO received."
                )

                appendLog(
                    "Download protocol НЕ используется."
                )
            }

            UUID_EXTRA_NOTIFY -> {

                appendLog(
                    "Extra NOTIFY received."
                )
            }
        }

        appendLog(
            "========================================"
        )
    }

    private fun handleDiagnosticsCredits(
        gatt: BluetoothGatt,
        value: ByteArray
    ) {

        if (
            value.isEmpty()
        ) {

            appendLog(
                "Diagnostics Credits EMPTY"
            )

            return
        }

        val credits =
            value[0].toInt() and 0xFF

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
            "Available Diagnostics TX Credits = " +
                diagnosticsTxCredits
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
                250L
            )

            return
        }

        /*
         * После открытия RHMI и получения
         * статуса 0x10 ждём credit для F903.
         */
        if (
            finalRhmiStatus ==
            0x10 &&
            diagnosticsTxCredits > 0 &&
            !activityRequestSent
        ) {

            mainHandler.postDelayed(
                {

                    sendDriver1ActivityRequest(
                        gatt
                    )

                },
                250L
            )
        }
    }

    private fun handleDownloadCredits(
        value: ByteArray
    ) {

        if (
            value.isEmpty()
        ) {

            appendLog(
                "Download Credits EMPTY"
            )

            return
        }

        val credits =
            value[0].toInt() and 0xFF

        appendLog(
            "Download Credits RX = $credits"
        )

        if (
            credits !=
            0xFF
        ) {

            downloadTxCredits +=
                credits
        }

        appendLog(
            "Download TX Credits = $downloadTxCredits"
        )

        appendLog(
            "Download FIFO НЕ используем."
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendOpenRhmiRequest(
        gatt: BluetoothGatt
    ) {

        if (
            openRequestSent
        ) {

            return
        }

        if (
            diagnosticsTxCredits <= 0
        ) {

            appendLog(
                "OPEN RHMI: нет Diagnostics Credits."
            )

            return
        }

        val fifo =
            getDiagnosticsFifo(
                gatt
            )

        if (
            fifo == null
        ) {

            appendLog(
                "Diagnostics FIFO NOT FOUND"
            )

            return
        }

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
            "STEP 6: OPEN RHMI SESSION"
        )

        appendLog(
            "Application: 31 01 F2 11"
        )

        appendLog(
            "Transport packet: ${bytesToHex(packet)}"
        )

        appendLog(
            "Карта водителя НЕ изменяется."
        )

        appendLog(
            "Деятельность НЕ изменяется."
        )

        val started =
            writeCharacteristicCompat(
                gatt,
                fifo,
                packet
            )

        appendLog(
            "Diagnostics FIFO write started = $started"
        )

        if (
            started
        ) {

            openRequestSent =
                true

            diagnosticsTxCredits -=
                1

            appendLog(
                "Remaining Diagnostics TX Credits = " +
                    diagnosticsTxCredits
            )
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
                "Diagnostics FIFO packet слишком короткий."
            )

            return
        }

        val totalPackets =
            value[0].toInt() and 0xFF

        val packetNumber =
            value[1].toInt() and 0xFF

        val appData =
            value.copyOfRange(
                2,
                value.size
            )

        appendLog(
            "Transport totalPackets = $totalPackets"
        )

        appendLog(
            "Transport packetNumber = $packetNumber"
        )

        appendLog(
            "Application HEX = ${bytesToHex(appData)}"
        )

        /*
         * OpenRHMISession:
         * positive = 71 01 F2 11
         */
        if (
            appData.size >= 4 &&
            (appData[0].toInt() and 0xFF) ==
            0x71 &&
            (appData[1].toInt() and 0xFF) ==
            0x01 &&
            (appData[2].toInt() and 0xFF) ==
            0xF2 &&
            (appData[3].toInt() and 0xFF) ==
            0x11
        ) {

            openPositiveResponseReceived =
                true

            appendLog(
                ""
            )

            appendLog(
                "******** OPEN RHMI POSITIVE RESPONSE ********"
            )

            appendLog(
                "DTCO принял OpenRHMISession."
            )

            appendLog(
                "*********************************************"
            )

            mainHandler.postDelayed(
                {

                    grantDiagnosticsCreditForStatus(
                        gatt
                    )

                },
                250L
            )

            return
        }

        /*
         * GetRHMISessionStatus:
         * positive = 71 03 F2 11 XX
         */
        if (
            appData.size >= 5 &&
            (appData[0].toInt() and 0xFF) ==
            0x71 &&
            (appData[1].toInt() and 0xFF) ==
            0x03 &&
            (appData[2].toInt() and 0xFF) ==
            0xF2 &&
            (appData[3].toInt() and 0xFF) ==
            0x11
        ) {

            val rhmiStatus =
                appData[4].toInt() and 0xFF

            finalRhmiStatus =
                rhmiStatus

            appendLog(
                ""
            )

            appendLog(
                "******** RHMI STATUS RESPONSE ********"
            )

            appendLog(
                "RHMIsessionStatus HEX = ${
                    "%02X".format(
                        Locale.US,
                        rhmiStatus
                    )
                }"
            )

            appendLog(
                "Meaning = ${
                    rhmiStatusToString(
                        rhmiStatus
                    )
                }"
            )

            if (
                rhmiStatus ==
                0x10
            ) {

                appendLog(
                    ""
                )

                appendLog(
                    ">>> REMOTE HMI SESSION OPENED <<<"
                )

                appendLog(
                    "Следующий шаг: READ-ONLY F903."
                )

                /*
                 * Даём DTCO один credit для ответа
                 * на чтение Driver1WorkingState.
                 */
                mainHandler.postDelayed(
                    {

                        grantDiagnosticsCreditForActivity(
                            gatt
                        )

                    },
                    300L
                )
            }

            appendLog(
                "***************************************"
            )

            return
        }

        /*
         * ReadDataByIdentifier F903:
         *
         * request:
         * 22 F9 03
         *
         * positive:
         * 62 F9 03 XX
         */
        if (
            appData.size >= 4 &&
            (appData[0].toInt() and 0xFF) ==
            0x62 &&
            (appData[1].toInt() and 0xFF) ==
            0xF9 &&
            (appData[2].toInt() and 0xFF) ==
            0x03
        ) {

            val state =
                appData[3].toInt() and 0xFF

            driver1WorkingState =
                state

            appendLog(
                ""
            )

            appendLog(
                "*********************************************"
            )

            appendLog(
                "DRIVER 1 WORKING STATE RECEIVED"
            )

            appendLog(
                "DID = F903"
            )

            appendLog(
                "RAW HEX = ${
                    "%02X".format(
                        Locale.US,
                        state
                    )
                }"
            )

            appendLog(
                "VALUE = $state"
            )

            appendLog(
                "ACTIVITY = ${
                    driverActivityToString(
                        state
                    )
                }"
            )

            appendLog(
                "*********************************************"
            )

            appendLog(
                ""
            )

            appendLog(
                ">>> FIRST DRIVER DATA READ SUCCESS <<<"
            )

            appendLog(
                "Карта/DTCO при этом НЕ изменялись."
            )

            return
        }

        /*
         * UDS negative response
         */
        if (
            appData.size >= 3 &&
            (appData[0].toInt() and 0xFF) ==
            0x7F
        ) {

            val originalService =
                appData[1].toInt() and 0xFF

            val nrc =
                appData[2].toInt() and 0xFF

            appendLog(
                ""
            )

            appendLog(
                "******** UDS NEGATIVE RESPONSE ********"
            )

            appendLog(
                "Original service = ${
                    "%02X".format(
                        Locale.US,
                        originalService
                    )
                }"
            )

            appendLog(
                "NRC = ${
                    "%02X".format(
                        Locale.US,
                        nrc
                    )
                }"
            )

            appendLog(
                "NRC meaning = ${
                    negativeResponseToString(
                        nrc
                    )
                }"
            )

            appendLog(
                "***************************************"
            )
        }
    }

    private fun grantDiagnosticsCreditForStatus(
        gatt: BluetoothGatt
    ) {

        appendLog(
            ""
        )

        appendLog(
            "STEP 7A: grant Diagnostics RX credit"
        )

        sendCredit(
            gatt,
            UUID_DIAGNOSTICS_SERVICE,
            UUID_DIAGNOSTICS_CREDITS,
            "Diagnostics-for-status"
        )

        mainHandler.postDelayed(
            {

                trySendRhmiStatusRequest(
                    gatt,
                    attempt = 1
                )

            },
            350L
        )
    }

    private fun trySendRhmiStatusRequest(
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
            10
        ) {

            appendLog(
                "STATUS REQUEST: TX Credit не получен."
            )

            return
        }

        appendLog(
            "STATUS REQUEST: ждём Diagnostics Credit " +
                "(attempt $attempt)"
        )

        mainHandler.postDelayed(
            {

                trySendRhmiStatusRequest(
                    gatt,
                    attempt + 1
                )

            },
            300L
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendRhmiStatusRequest(
        gatt: BluetoothGatt
    ) {

        if (
            statusRequestSent
        ) {

            return
        }

        if (
            diagnosticsTxCredits <=
            0
        ) {

            appendLog(
                "STATUS REQUEST: нет TX Credits."
            )

            return
        }

        val fifo =
            getDiagnosticsFifo(
                gatt
            )

        if (
            fifo ==
            null
        ) {

            appendLog(
                "Diagnostics FIFO NOT FOUND"
            )

            return
        }

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
            "========================================"
        )

        appendLog(
            "STEP 7B: GET RHMI SESSION STATUS"
        )

        appendLog(
            "Application: 31 03 F2 11"
        )

        appendLog(
            "Transport packet: ${bytesToHex(packet)}"
        )

        val started =
            writeCharacteristicCompat(
                gatt,
                fifo,
                packet
            )

        appendLog(
            "Diagnostics FIFO write started = $started"
        )

        if (
            started
        ) {

            statusRequestSent =
                true

            diagnosticsTxCredits -=
                1

            appendLog(
                "Remaining Diagnostics TX Credits = " +
                    diagnosticsTxCredits
            )
        }

        appendLog(
            "========================================"
        )
    }

    /*
     * ============================================================
     * DRIVER 1 ACTIVITY — DID F903
     * ============================================================
     */

    private fun grantDiagnosticsCreditForActivity(
        gatt: BluetoothGatt
    ) {

        if (
            activityRequestSent
        ) {

            return
        }

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "STEP 8A: prepare READ F903"
        )

        appendLog(
            "Grant one Diagnostics RX credit"
        )

        appendLog(
            "========================================"
        )

        sendCredit(
            gatt,
            UUID_DIAGNOSTICS_SERVICE,
            UUID_DIAGNOSTICS_CREDITS,
            "Diagnostics-for-F903"
        )

        mainHandler.postDelayed(
            {

                trySendDriver1ActivityRequest(
                    gatt,
                    attempt = 1
                )

            },
            350L
        )
    }

    private fun trySendDriver1ActivityRequest(
        gatt: BluetoothGatt,
        attempt: Int
    ) {

        if (
            activityRequestSent
        ) {

            return
        }

        if (
            diagnosticsTxCredits >
            0
        ) {

            sendDriver1ActivityRequest(
                gatt
            )

            return
        }

        if (
            attempt >=
            10
        ) {

            appendLog(
                "F903 REQUEST: TX Credit не получен."
            )

            return
        }

        appendLog(
            "F903 REQUEST: ждём Diagnostics Credit " +
                "(attempt $attempt)"
        )

        mainHandler.postDelayed(
            {

                trySendDriver1ActivityRequest(
                    gatt,
                    attempt + 1
                )

            },
            300L
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendDriver1ActivityRequest(
        gatt: BluetoothGatt
    ) {

        if (
            activityRequestSent
        ) {

            return
        }

        if (
            finalRhmiStatus !=
            0x10
        ) {

            appendLog(
                "F903 READ запрещён: RHMI session не OPEN."
            )

            return
        }

        if (
            diagnosticsTxCredits <=
            0
        ) {

            appendLog(
                "F903 READ запрещён: нет TX Credits."
            )

            return
        }

        val fifo =
            getDiagnosticsFifo(
                gatt
            )

        if (
            fifo ==
            null
        ) {

            appendLog(
                "Diagnostics FIFO NOT FOUND"
            )

            return
        }

        /*
         * Application:
         *
         * 22 F9 03
         *
         * BLE transport:
         *
         * 01 = total packets
         * 01 = packet number
         */
        val packet =
            byteArrayOf(
                0x01,
                0x01,
                0x22,
                0xF9.toByte(),
                0x03
            )

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "STEP 8B: READ DRIVER 1 WORKING STATE"
        )

        appendLog(
            "UDS service: ReadDataByIdentifier"
        )

        appendLog(
            "DID: F903"
        )

        appendLog(
            "Application: 22 F9 03"
        )

        appendLog(
            "Transport packet: ${bytesToHex(packet)}"
        )

        appendLog(
            "Операция READ-ONLY."
        )

        appendLog(
            "Никакой деятельности не меняем."
        )

        appendLog(
            "Никаких данных в карту не записываем."
        )

        val started =
            writeCharacteristicCompat(
                gatt,
                fifo,
                packet
            )

        appendLog(
            "Diagnostics FIFO write started = $started"
        )

        if (
            started
        ) {

            activityRequestSent =
                true

            diagnosticsTxCredits -=
                1

            appendLog(
                "Remaining Diagnostics TX Credits = " +
                    diagnosticsTxCredits
            )
        }

        appendLog(
            "========================================"
        )
    }

    private fun getDiagnosticsFifo(
        gatt: BluetoothGatt
    ): BluetoothGattCharacteristic? {

        val service =
            gatt.getService(
                UUID_DIAGNOSTICS_SERVICE
            )

        return service
            ?.getCharacteristic(
                UUID_DIAGNOSTICS_FIFO
            )
    }

    private fun driverActivityToString(
        value: Int
    ): String {

        return when (
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

            else ->
                "НЕИЗВЕСТНОЕ ЗНАЧЕНИЕ ($value)"
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
                "Сессия закрыта; ожидается решение пользователя"

            0x10 ->
                "Remote HMI session ОТКРЫТА"

            0x20 ->
                "Remote HMI отклонена пользователем"

            0x21 ->
                "Используется локальный интерфейс DTCO"

            0x2F ->
                "Общие условия Remote HMI не выполнены"

            else ->
                "Неизвестный статус 0x${
                    "%02X".format(
                        Locale.US,
                        status
                    )
                }"
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

            0x7E ->
                "subFunctionNotSupportedInActiveSession"

            else ->
                "NRC 0x${
                    "%02X".format(
                        Locale.US,
                        nrc
                    )
                }"
        }
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

        return when (
            uuid
        ) {

            UUID_DIAGNOSTICS_FIFO ->
                "Diagnostics FIFO"

            UUID_DIAGNOSTICS_CREDITS ->
                "Diagnostics Credits"

            UUID_DOWNLOAD_FIFO ->
                "Download FIFO"

            UUID_DOWNLOAD_CREDITS ->
                "Download Credits"

            UUID_EXTRA_NOTIFY ->
                "Extra NOTIFY"

            else ->
                "Unknown"
        }
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
                it.toInt() and 0xFF
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
            "Bond state: ${
                bondStateToString(
                    device.bondState
                )
            }"
        )

        appendLog(
            "Тип Bluetooth: ${
                deviceTypeToString(
                    device.type
                )
            }"
        )

        appendLog(
            "Android API: ${Build.VERSION.SDK_INT}"
        )
    }

    fun disconnect() {

        subscriptionQueue.clear()

        subscriptionInProgress =
            false

        connected =
            false

        servicesDiscovered =
            false

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
            gatt !=
            null
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
