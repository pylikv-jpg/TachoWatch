package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
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
            "BLE-GATT-SUBSCRIBE-1"

        private const val MAX_LOG_LINES =
            3000

        private val UUID_CCCD =
            UUID.fromString(
                "00002902-0000-1000-8000-00805f9b34fb"
            )

        /*
         * OFFICIAL SMART TACHOGRAPH V2
         * DIAGNOSTICS SERVICE
         */
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

        /*
         * OFFICIAL SMART TACHOGRAPH V2
         * DOWNLOAD SERVICE
         */
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

        /*
         * ТРЕТИЙ СЕРВИС, ОБНАРУЖЕННЫЙ НА DTCO.
         * Назначение пока не утверждаем.
         */
        private val UUID_EXTRA_SERVICE =
            UUID.fromString(
                "36ab9731-5701-489a-943a-5e16a9dd3183"
            )

        private val UUID_EXTRA_NOTIFY =
            UUID.fromString(
                "c9446e2e-527f-48eb-bbd4-f3c5e79ee716"
            )
    }

    private data class SubscriptionItem(
        val label: String,
        val characteristic: BluetoothGattCharacteristic,
        val mode: SubscriptionMode
    )

    private enum class SubscriptionMode {
        INDICATE,
        NOTIFY
    }

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

        appendLog(
            "========================================"
        )

        appendLog(
            "TachoWatch — диагностика DTCO"
        )

        appendLog(
            "Версия диагностики: $VERSION"
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "БЕЗОПАСНЫЙ РЕЖИМ"
        )

        appendLog(
            "Запись в карту водителя: НЕТ"
        )

        appendLog(
            "Изменение данных DTCO: НЕТ"
        )

        appendLog(
            "Чтение содержимого карты: НЕТ"
        )

        appendLog(
            "FIFO-команды: НЕ отправляются"
        )

        appendLog(
            "Credits: НЕ отправляются"
        )

        appendLog(
            "writeCharacteristic(): НЕ используется"
        )

        appendLog(
            "Разрешена только настройка BLE CCCD"
        )

        appendLog(
            "для INDICATE / NOTIFY."
        )

        appendLog(
            "========================================"
        )

        appendDeviceInfo(
            device
        )

        appendLog(
            "----------------------------------------"
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

                    appendLog(
                        "connectGatt(false, TRANSPORT_LE)"
                    )

                    device.connectGatt(
                        context,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )

                } else {

                    appendLog(
                        "connectGatt(false)"
                    )

                    device.connectGatt(
                        context,
                        false,
                        gattCallback
                    )
                }

            currentGatt =
                gatt

            appendLog(
                "BluetoothGatt object: ${
                    if (gatt != null) {
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

    /*
     * Совместимость с кнопкой
     * "Проверить GATT сейчас".
     */
    @SuppressLint("MissingPermission")
    fun manualGattCheck() {

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "MANUAL GATT CHECK"
        )

        appendLog(
            "========================================"
        )

        val device =
            currentDevice

        val gatt =
            currentGatt

        if (
            device == null
        ) {

            appendLog(
                "currentDevice = NULL"
            )

            return
        }

        val state =

            try {

                bluetoothManager.getConnectionState(
                    device,
                    BluetoothProfile.GATT
                )

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "getConnectionState",
                    e
                )

                -1
            }

        appendLog(
            "Android GATT state = " +
                "$state (${profileStateToString(state)})"
        )

        appendLog(
            "Internal connected = $connected"
        )

        appendLog(
            "servicesDiscovered = $servicesDiscovered"
        )

        if (
            gatt == null
        ) {

            appendLog(
                "currentGatt = NULL"
            )

            return
        }

        appendLog(
            "currentGatt = EXISTS"
        )

        val services =

            try {

                gatt.services

            } catch (
                _: Throwable
            ) {

                emptyList()
            }

        appendLog(
            "Services currently available = ${services.size}"
        )

        if (
            services.isNotEmpty()
        ) {

            dumpGattMap(
                gatt,
                "MANUAL"
            )

            appendLog(
                "Повторная проверка подписок..."
            )

            prepareSubscriptions(
                gatt
            )

        } else {

            try {

                val result =
                    gatt.discoverServices()

                appendLog(
                    "discoverServices() returned = $result"
                )

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "manual discoverServices",
                    e
                )
            }
        }
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
                                "STEP 2: discoverServices()"
                            )

                            val result =
                                gatt.discoverServices()

                            appendLog(
                                "discoverServices() returned = $result"
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

                    BluetoothProfile.STATE_DISCONNECTED -> {

                        connected =
                            false

                        servicesDiscovered =
                            false

                        subscriptionQueue.clear()

                        subscriptionInProgress =
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
                }
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

                    dumpGattMap(
                        gatt,
                        "CALLBACK"
                    )

                    appendLog(
                        ""
                    )

                    appendLog(
                        "STEP 3: настройка INDICATE / NOTIFY"
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

                val characteristic =
                    descriptor.characteristic

                appendLog(
                    ""
                )

                appendLog(
                    "CALLBACK: onDescriptorWrite"
                )

                appendLog(
                    "Characteristic: ${characteristic.uuid}"
                )

                appendLog(
                    "Descriptor: ${descriptor.uuid}"
                )

                appendLog(
                    "status = $status (${gattStatusToString(status)})"
                )

                subscriptionInProgress =
                    false

                if (
                    status ==
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    appendLog(
                        "CCCD настроен успешно."
                    )

                } else {

                    appendLog(
                        "CCCD настройка завершилась ошибкой."
                    )
                }

                /*
                 * BLE GATT требует последовательных операций.
                 * Запускаем следующую подписку только после callback.
                 */
                mainHandler.postDelayed(
                    {
                        subscribeNext(
                            gatt
                        )
                    },
                    120L
                )
            }

            @Deprecated(
                "Legacy callback required for older Android"
            )
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {

                if (
                    Build.VERSION.SDK_INT <
                    Build.VERSION_CODES.TIRAMISU
                ) {

                    val value =
                        try {

                            characteristic.value
                                ?: byteArrayOf()

                        } catch (
                            _: Throwable
                        ) {

                            byteArrayOf()
                        }

                    handleIncoming(
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

    /*
     * Формируем очередь подписок.
     * В characteristic value ничего НЕ записываем.
     */
    @SuppressLint("MissingPermission")
    private fun prepareSubscriptions(
        gatt: BluetoothGatt
    ) {

        subscriptionQueue.clear()
        subscriptionInProgress = false

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
            "Unknown service NOTIFY",
            SubscriptionMode.NOTIFY
        )

        appendLog(
            "Подписок в очереди: ${subscriptionQueue.size}"
        )

        if (
            subscriptionQueue.isEmpty()
        ) {

            appendLog(
                "Нет подходящих характеристик."
            )

            return
        }

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
                "$label: service NOT FOUND"
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
                "$label: characteristic NOT FOUND"
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
            "$label: добавлен в очередь."
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
                "Теперь слушаем DTCO."
            )

            appendLog(
                "FIFO-команды НЕ отправлялись."
            )

            appendLog(
                "Credits НЕ отправлялись."
            )

            appendLog(
                "========================================"
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

        /*
         * Локально включаем delivery callback.
         */
        val notificationResult =

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
            "setCharacteristicNotification(true) = $notificationResult"
        )

        if (
            !notificationResult
        ) {

            appendLog(
                "Не удалось локально включить подписку."
            )

            mainHandler.postDelayed(
                {
                    subscribeNext(
                        gatt
                    )
                },
                120L
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
                "CCCD 0x2902 NOT FOUND."
            )

            mainHandler.postDelayed(
                {
                    subscribeNext(
                        gatt
                    )
                },
                120L
            )

            return
        }

        val value =

            when (
                item.mode
            ) {

                SubscriptionMode.INDICATE ->
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

                SubscriptionMode.NOTIFY ->
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }

        subscriptionInProgress =
            true

        appendLog(
            "Пишем только стандартный CCCD 0x2902."
        )

        appendLog(
            "Characteristic value НЕ записывается."
        )

        val started =

            try {

                writeCccd(
                    gatt,
                    descriptor,
                    value
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
                150L
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCccd(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            val result =
                gatt.writeDescriptor(
                    descriptor,
                    value
                )

            result ==
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

    private fun handleIncoming(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {

        val label =
            characteristicLabel(
                characteristic.uuid
            )

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
            "Source: $label"
        )

        appendLog(
            "UUID: ${characteristic.uuid}"
        )

        appendLog(
            "Length: ${value.size} byte(s)"
        )

        appendLog(
            "HEX: ${bytesToHex(value)}"
        )

        val ascii =
            bytesToReadableAscii(
                value
            )

        if (
            ascii.isNotBlank()
        ) {

            appendLog(
                "ASCII: $ascii"
            )
        }

        appendLog(
            "========================================"
        )
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
                "Unknown service NOTIFY"

            else ->
                "Unknown characteristic"
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

    private fun bytesToReadableAscii(
        bytes: ByteArray
    ): String {

        if (
            bytes.isEmpty()
        ) {

            return ""
        }

        val builder =
            StringBuilder()

        bytes.forEach {

            val code =
                it.toInt() and 0xFF

            if (
                code in 32..126
            ) {

                builder.append(
                    code.toChar()
                )

            } else {

                builder.append(
                    '.'
                )
            }
        }

        return builder.toString()
    }

    private fun dumpGattMap(
        gatt: BluetoothGatt,
        source: String
    ) {

        val services =

            try {

                gatt.services

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "dumpGattMap",
                    e
                )

                emptyList()
            }

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "GATT MAP — $source"
        )

        appendLog(
            "SERVICES = ${services.size}"
        )

        appendLog(
            "========================================"
        )

        services.forEachIndexed {
                serviceIndex,
                service ->

            dumpService(
                serviceIndex,
                service
            )
        }

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "GATT MAP COMPLETE"
        )

        appendLog(
            "Карта водителя НЕ читалась."
        )

        appendLog(
            "Карта водителя НЕ изменялась."
        )

        appendLog(
            "DTCO данные НЕ изменялись."
        )

        appendLog(
            "========================================"
        )
    }

    private fun dumpService(
        serviceIndex: Int,
        service: BluetoothGattService
    ) {

        appendLog(
            ""
        )

        appendLog(
            "######## SERVICE [$serviceIndex] ########"
        )

        appendLog(
            "Service UUID: ${service.uuid}"
        )

        appendLog(
            "Type: ${serviceTypeToString(service.type)}"
        )

        appendLog(
            "Instance ID: ${service.instanceId}"
        )

        val characteristics =

            try {

                service.characteristics

            } catch (
                _: Throwable
            ) {

                emptyList()
            }

        appendLog(
            "Characteristics: ${characteristics.size}"
        )

        characteristics.forEachIndexed {
                characteristicIndex,
                characteristic ->

            appendLog(
                "  ------------------------------------"
            )

            appendLog(
                "  CHARACTERISTIC [$serviceIndex.$characteristicIndex]"
            )

            appendLog(
                "  UUID: ${characteristic.uuid}"
            )

            appendLog(
                "  Properties HEX: 0x${
                    characteristic.properties
                        .toString(16)
                        .uppercase(Locale.US)
                }"
            )

            appendLog(
                "  Properties: ${
                    characteristicPropertiesToString(
                        characteristic.properties
                    )
                }"
            )

            val descriptors =

                try {

                    characteristic.descriptors

                } catch (
                    _: Throwable
                ) {

                    emptyList()
                }

            appendLog(
                "  Descriptors: ${descriptors.size}"
            )

            descriptors.forEachIndexed {
                    descriptorIndex,
                    descriptor ->

                appendLog(
                    "    DESCRIPTOR [$serviceIndex.$characteristicIndex.$descriptorIndex]"
                )

                appendLog(
                    "    UUID: ${descriptor.uuid}"
                )
            }
        }

        appendLog(
            "######## END SERVICE [$serviceIndex] ########"
        )
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
                try {

                    bondStateToString(
                        device.bondState
                    )

                } catch (
                    _: Throwable
                ) {

                    "Недоступно"
                }
            }"
        )

        appendLog(
            "Тип Bluetooth: ${
                try {

                    deviceTypeToString(
                        device.type
                    )

                } catch (
                    _: Throwable
                ) {

                    "Недоступно"
                }
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

            -1 ->
                "ERROR"

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

    private fun serviceTypeToString(
        type: Int
    ): String {

        return when (
            type
        ) {

            BluetoothGattService.SERVICE_TYPE_PRIMARY ->
                "PRIMARY"

            BluetoothGattService.SERVICE_TYPE_SECONDARY ->
                "SECONDARY"

            else ->
                "UNKNOWN($type)"
        }
    }

    private fun characteristicPropertiesToString(
        properties: Int
    ): String {

        val result =
            mutableListOf<String>()

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0
        ) {
            result.add(
                "BROADCAST"
            )
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_READ != 0
        ) {
            result.add(
                "READ"
            )
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            result.add(
                "WRITE_NO_RESPONSE"
            )
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        ) {
            result.add(
                "WRITE"
            )
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        ) {
            result.add(
                "NOTIFY"
            )
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        ) {
            result.add(
                "INDICATE"
            )
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0
        ) {
            result.add(
                "SIGNED_WRITE"
            )
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0
        ) {
            result.add(
                "EXTENDED_PROPS"
            )
        }

        return if (
            result.isEmpty()
        ) {

            "NONE"

        } else {

            result.joinToString(
                separator = " | "
            )
        }
    }
}
