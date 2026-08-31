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
            "BLE-RHMI-LIMITS-1"

        private const val MAX_LOG_LINES =
            7000

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

    private enum class ValueKind {
        ACTIVITY,
        MINUTES
    }

    private data class SubscriptionItem(
        val label: String,
        val characteristic: BluetoothGattCharacteristic,
        val mode: SubscriptionMode
    )

    private data class ReadRequest(
        val did: Int,
        val name: String,
        val kind: ValueKind
    )

    /*
     * Уже подтверждённые параметры +
     * новые параметры лимитов.
     *
     * F99A / F99B намеренно удалены:
     * конкретный DTCO возвращает NRC 22.
     */
    private val readRequests =
        listOf(

            ReadRequest(
                did = 0xF903,
                name = "Driver1WorkingState",
                kind = ValueKind.ACTIVITY
            ),

            ReadRequest(
                did = 0xF923,
                name = "ContinuousDrivingTime",
                kind = ValueKind.MINUTES
            ),

            ReadRequest(
                did = 0xF925,
                name = "CumulativeBreakTime",
                kind = ValueKind.MINUTES
            ),

            ReadRequest(
                did = 0xF927,
                name = "CurrentDurationOfSelectedActivity",
                kind = ValueKind.MINUTES
            ),

            ReadRequest(
                did = 0xF938,
                name = "DrivingPreviousAndCurrentWeek",
                kind = ValueKind.MINUTES
            ),

            /*
             * НОВЫЕ LIMITS
             */

            ReadRequest(
                did = 0xF9AD,
                name = "RemainingCurrentDrivingTime",
                kind = ValueKind.MINUTES
            ),

            ReadRequest(
                did = 0xF9AF,
                name = "RemainingDrivingTimeOnCurrentShift",
                kind = ValueKind.MINUTES
            ),

            ReadRequest(
                did = 0xF9B1,
                name = "RemainingDrivingTimeOfCurrentWeek",
                kind = ValueKind.MINUTES
            ),

            ReadRequest(
                did = 0xF9B3,
                name = "Remaining2WeeksDrivingTime",
                kind = ValueKind.MINUTES
            ),

            ReadRequest(
                did = 0xF99C,
                name = "TimeLeftUntilNewDailyRestPeriod",
                kind = ValueKind.MINUTES
            ),

            ReadRequest(
                did = 0xF9A1,
                name = "TimeLeftUntilNewWeeklyRestPeriod",
                kind = ValueKind.MINUTES
            ),

            ReadRequest(
                did = 0xF9B9,
                name = "DurationOfNextBreakRest",
                kind = ValueKind.MINUTES
            ),

            ReadRequest(
                did = 0xF9C2,
                name = "RemainingTimeUntilNextBreakOrRest",
                kind = ValueKind.MINUTES
            )
        )

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val logLines =
        CopyOnWriteArrayList<String>()

    private val subscriptionQueue =
        ArrayDeque<SubscriptionItem>()

    private val results =
        linkedMapOf<Int, String>()

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
    private var openRequestSent =
        false

    @Volatile
    private var statusRequestSent =
        false

    @Volatile
    private var rhmiStatus:
        Int? = null

    @Volatile
    private var readStarted =
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
        results.clear()

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

        openRequestSent =
            false

        statusRequestSent =
            false

        rhmiStatus =
            null

        readStarted =
            false

        readIndex =
            0

        waitingForResponse =
            false

        currentDid =
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
            "READ-ONLY LIMITS TEST"
        )

        appendLog(
            "Запись в карту водителя: НЕТ"
        )

        appendLog(
            "Изменение деятельности: НЕТ"
        )

        appendLog(
            "Изменение данных DTCO: НЕТ"
        )

        appendLog(
            "Download FIFO: НЕ используется"
        )

        appendLog(
            "Читаем ${readRequests.size} Driver 1 DID."
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
            "RHMI status = ${
                rhmiStatus?.let {
                    "0x${hexByte(it)}"
                } ?: "UNKNOWN"
            }"
        )

        appendLog(
            "Read started = $readStarted"
        )

        appendLog(
            "Read index = $readIndex / ${readRequests.size}"
        )

        appendLog(
            "Waiting response = $waitingForResponse"
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

                        waitingForResponse =
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

        /*
         * Download канал оставляем полностью
         * без содержательных запросов.
         */
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

                if (
                    value.isNotEmpty()
                ) {

                    appendLog(
                        "Download Credits RX = ${
                            unsigned(
                                value[0]
                            )
                        }"
                    )
                }

                appendLog(
                    "Download FIFO НЕ используем."
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

        if (
            rhmiStatus ==
            0x10 &&
            readStarted &&
            !waitingForResponse &&
            readIndex <
            readRequests.size &&
            diagnosticsTxCredits >
            0
        ) {

            mainHandler.postDelayed(
                {

                    sendCurrentReadRequest(
                        gatt
                    )

                },
                200L
            )
        }
    }

    @SuppressLint("MissingPermission")
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
            "========================================"
        )

        appendLog(
            "STEP 6: OPEN RHMI SESSION"
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

        if (
            started
        ) {

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
                "Diagnostics FIFO packet слишком короткий."
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
            "Transport totalPackets = $totalPackets"
        )

        appendLog(
            "Transport packetNumber = $packetNumber"
        )

        appendLog(
            "Application HEX = ${bytesToHex(appData)}"
        )

        /*
         * Open RHMI positive
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

                    grantCreditForRhmiStatus(
                        gatt
                    )

                },
                250L
            )

            return
        }

        /*
         * Get RHMI session status
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

            rhmiStatus =
                status

            appendLog(
                ""
            )

            appendLog(
                "******** RHMI STATUS RESPONSE ********"
            )

            appendLog(
                "RHMIsessionStatus = 0x${hexByte(status)}"
            )

            appendLog(
                "Meaning = ${
                    rhmiStatusToString(
                        status
                    )
                }"
            )

            appendLog(
                "***************************************"
            )

            if (
                status ==
                0x10
            ) {

                appendLog(
                    ""
                )

                appendLog(
                    ">>> REMOTE HMI SESSION OPENED <<<"
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
         * Positive ReadDataByIdentifier
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
         * Negative UDS
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
                ""
            )

            appendLog(
                "******** UDS NEGATIVE RESPONSE ********"
            )

            appendLog(
                "Original service = ${hexByte(originalService)}"
            )

            appendLog(
                "NRC = ${hexByte(nrc)}"
            )

            appendLog(
                "NRC meaning = ${
                    negativeResponseToString(
                        nrc
                    )
                }"
            )

            if (
                waitingForResponse
            ) {

                val did =
                    currentDid

                if (
                    did !=
                    null
                ) {

                    results[
                        did
                    ] =
                        "NOT AVAILABLE / NRC ${hexByte(nrc)}"
                }

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
                    300L
                )
            }

            appendLog(
                "***************************************"
            )
        }
    }

    private fun grantCreditForRhmiStatus(
        gatt: BluetoothGatt
    ) {

        sendCredit(
            gatt,
            UUID_DIAGNOSTICS_SERVICE,
            UUID_DIAGNOSTICS_CREDITS,
            "Diagnostics-for-status"
        )

        mainHandler.postDelayed(
            {

                trySendRhmiStatus(
                    gatt,
                    1
                )

            },
            350L
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
            10
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
            300L
        )
    }

    @SuppressLint("MissingPermission")
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

        appendLog(
            ""
        )

        appendLog(
            "STEP 7: GET RHMI SESSION STATUS"
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

        if (
            started
        ) {

            statusRequestSent =
                true

            diagnosticsTxCredits -=
                1
        }
    }

    /*
     * ============================================================
     * READ SEQUENCE
     * ============================================================
     */

    private fun startReads(
        gatt: BluetoothGatt
    ) {

        if (
            readStarted
        ) {

            return
        }

        readStarted =
            true

        readIndex =
            0

        waitingForResponse =
            false

        currentDid =
            null

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "STEP 8: DRIVER 1 BASIC + LIMIT DATA"
        )

        appendLog(
            "Запросов = ${readRequests.size}"
        )

        appendLog(
            "========================================"
        )

        readRequests.forEachIndexed {
                index,
                request ->

            appendLog(
                "${index + 1}. ${
                    hexDid(
                        request.did
                    )
                } — ${request.name}"
            )
        }

        prepareNextRead(
            gatt
        )
    }

    private fun prepareNextRead(
        gatt: BluetoothGatt
    ) {

        if (
            readIndex >=
            readRequests.size
        ) {

            finishReads()

            return
        }

        if (
            waitingForResponse
        ) {

            return
        }

        val request =
            readRequests[
                readIndex
            ]

        appendLog(
            ""
        )

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "PREPARE READ ${readIndex + 1}/${readRequests.size}"
        )

        appendLog(
            "DID = ${hexDid(request.did)}"
        )

        appendLog(
            "Name = ${request.name}"
        )

        appendLog(
            "Grant one Diagnostics RX credit."
        )

        sendCredit(
            gatt,
            UUID_DIAGNOSTICS_SERVICE,
            UUID_DIAGNOSTICS_CREDITS,
            "Diagnostics-for-${hexDid(request.did)}"
        )

        mainHandler.postDelayed(
            {

                trySendCurrentRead(
                    gatt,
                    1
                )

            },
            350L
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
            diagnosticsTxCredits >
            0
        ) {

            sendCurrentReadRequest(
                gatt
            )

            return
        }

        if (
            attempt >=
            12
        ) {

            val request =
                readRequests[
                    readIndex
                ]

            results[
                request.did
            ] =
                "NO TX CREDIT"

            appendLog(
                "Нет TX Credit для ${hexDid(request.did)}"
            )

            readIndex +=
                1

            mainHandler.postDelayed(
                {

                    prepareNextRead(
                        gatt
                    )

                },
                250L
            )

            return
        }

        appendLog(
            "Ждём TX Credit (attempt $attempt)"
        )

        mainHandler.postDelayed(
            {

                trySendCurrentRead(
                    gatt,
                    attempt + 1
                )

            },
            250L
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendCurrentReadRequest(
        gatt: BluetoothGatt
    ) {

        if (
            readIndex >=
            readRequests.size
        ) {

            finishReads()

            return
        }

        if (
            diagnosticsTxCredits <=
            0
        ) {

            return
        }

        val request =
            readRequests[
                readIndex
            ]

        val fifo =
            getDiagnosticsFifo(
                gatt
            ) ?: return

        val didHigh =
            (
                request.did shr 8
                ).toByte()

        val didLow =
            (
                request.did and
                    0xFF
                ).toByte()

        val packet =
            byteArrayOf(
                0x01,
                0x01,
                0x22,
                didHigh,
                didLow
            )

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "READ ${readIndex + 1}/${readRequests.size}"
        )

        appendLog(
            "DID = ${hexDid(request.did)}"
        )

        appendLog(
            "Name = ${request.name}"
        )

        appendLog(
            "Application = 22 ${hexDidSpaced(request.did)}"
        )

        appendLog(
            "Transport = ${bytesToHex(packet)}"
        )

        appendLog(
            "READ-ONLY"
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

        if (
            started
        ) {

            diagnosticsTxCredits -=
                1

            waitingForResponse =
                true

            currentDid =
                request.did

        } else {

            results[
                request.did
            ] =
                "WRITE START FAILED"

            readIndex +=
                1

            mainHandler.postDelayed(
                {

                    prepareNextRead(
                        gatt
                    )

                },
                250L
            )
        }

        appendLog(
            "========================================"
        )
    }

    private fun handlePositiveRead(
        gatt: BluetoothGatt,
        did: Int,
        data: ByteArray
    ) {

        appendLog(
            ""
        )

        appendLog(
            "******** READ DATA RESPONSE ********"
        )

        appendLog(
            "DID = ${hexDid(did)}"
        )

        appendLog(
            "DATA HEX = ${bytesToHex(data)}"
        )

        val request =
            readRequests.firstOrNull {
                it.did ==
                    did
            }

        val decoded =

            if (
                request ==
                null
            ) {

                "UNKNOWN DID / RAW ${bytesToHex(data)}"

            } else {

                decodeValue(
                    request,
                    data
                )
            }

        results[
            did
        ] =
            decoded

        appendLog(
            "DECODED = $decoded"
        )

        appendLog(
            "************************************"
        )

        if (
            waitingForResponse &&
            currentDid ==
            did
        ) {

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
                300L
            )
        }
    }

    private fun decodeValue(
        request: ReadRequest,
        data: ByteArray
    ): String {

        return when (
            request.kind
        ) {

            ValueKind.ACTIVITY -> {

                if (
                    data.isEmpty()
                ) {

                    "EMPTY"

                } else {

                    val activity =
                        unsigned(
                            data[0]
                        )

                    "$activity — ${
                        driverActivityToString(
                            activity
                        )
                    }"
                }
            }

            ValueKind.MINUTES -> {

                if (
                    data.size <
                    2
                ) {

                    "INVALID LENGTH ${data.size}"

                } else {

                    val minutes =
                        decodeUnsigned16(
                            data[0],
                            data[1]
                        )

                    /*
                     * ISO special ranges:
                     * FBxx / FCxx / FDxx / FExx / FFxx
                     * не трактуем как реальные минуты.
                     */
                    when {

                        minutes >=
                            0xFF00 -> {

                            "NOT AVAILABLE / RAW ${
                                bytesToHex(
                                    data
                                )
                            }"
                        }

                        minutes >=
                            0xFE00 -> {

                            "ERROR INDICATOR / RAW ${
                                bytesToHex(
                                    data
                                )
                            }"
                        }

                        minutes >=
                            0xFB00 -> {

                            "SPECIAL INDICATOR / RAW ${
                                bytesToHex(
                                    data
                                )
                            }"
                        }

                        else -> {

                            "$minutes min = ${
                                formatMinutes(
                                    minutes
                                )
                            }"
                        }
                    }
                }
            }
        }
    }

    private fun finishReads() {

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "LIMIT DATA READ COMPLETE"
        )

        appendLog(
            "========================================"
        )

        readRequests.forEach {
                request ->

            val result =
                results[
                    request.did
                ] ?: "NO RESPONSE"

            appendLog(
                "${hexDid(request.did)} ${request.name}"
            )

            appendLog(
                "  -> $result"
            )
        }

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "КЛЮЧЕВЫЕ ПОКАЗАТЕЛИ TACHOWATCH"
        )

        appendLog(
            "========================================"
        )

        appendSummaryLine(
            0xF903,
            "Текущая деятельность"
        )

        appendSummaryLine(
            0xF923,
            "Непрерывное вождение"
        )

        appendSummaryLine(
            0xF925,
            "Накопленная пауза"
        )

        appendSummaryLine(
            0xF9AD,
            "Остаток текущего вождения"
        )

        appendSummaryLine(
            0xF9AF,
            "Остаток дневного вождения"
        )

        appendSummaryLine(
            0xF9B1,
            "Остаток недельного вождения"
        )

        appendSummaryLine(
            0xF9B3,
            "Остаток двухнедельного вождения"
        )

        appendSummaryLine(
            0xF99C,
            "До нового суточного отдыха"
        )

        appendSummaryLine(
            0xF9A1,
            "До нового недельного отдыха"
        )

        appendSummaryLine(
            0xF9B9,
            "Следующий перерыв / отдых"
        )

        appendSummaryLine(
            0xF9C2,
            "До следующего перерыва / отдыха"
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "Запись в карту: 0 операций"
        )

        appendLog(
            "Изменение деятельности: 0 операций"
        )

        appendLog(
            "Download FIFO: 0 запросов"
        )

        appendLog(
            "========================================"
        )
    }

    private fun appendSummaryLine(
        did: Int,
        title: String
    ) {

        val value =
            results[
                did
            ] ?: "NO RESPONSE"

        appendLog(
            "$title:"
        )

        appendLog(
            "  ${hexDid(did)} -> $value"
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
                "НЕИЗВЕСТНО ($value)"
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
                "Общие условия RHMI не выполнены"

            else ->
                "Неизвестный статус ${hexByte(status)}"
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
                "requestOutOfRange / DID unavailable"

            0x33 ->
                "securityAccessDenied"

            0x7E ->
                "subFunctionNotSupportedInActiveSession"

            else ->
                "NRC ${hexByte(nrc)}"
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

        return "${
            hexByte(
                did shr 8
            )
        } ${
            hexByte(
                did
            )
        }"
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

        waitingForResponse =
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
