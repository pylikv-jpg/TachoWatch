package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
            "BLE-GATT-WATCHDOG-1"

        private const val UI_REFRESH_MS =
            400L

        private const val WATCHDOG_MS =
            1000L

        private const val MAX_WATCHDOG_SECONDS =
            20

        private const val MAX_LOG_LINES =
            2500
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
    private var stopped =
        true

    @Volatile
    private var serviceDiscoveryStarted =
        false

    @Volatile
    private var servicesDiscovered =
        false

    private var watchdogSeconds =
        0

    private var connectionStartedAt =
        0L

    private val uiLoop =
        object : Runnable {

            override fun run() {

                try {

                    listener?.onLogChanged(
                        getLog()
                    )

                } catch (
                    _: Throwable
                ) {
                }

                mainHandler.postDelayed(
                    this,
                    UI_REFRESH_MS
                )
            }
        }

    private val watchdogLoop =
        object : Runnable {

            @SuppressLint("MissingPermission")
            override fun run() {

                if (
                    stopped
                ) {
                    return
                }

                watchdogSeconds++

                val device =
                    currentDevice

                val gatt =
                    currentGatt

                appendLog(
                    "WATCHDOG ${watchdogSeconds}s"
                )

                if (
                    device == null
                ) {

                    appendLog(
                        "WATCHDOG: currentDevice = null"
                    )

                } else {

                    try {

                        val state =
                            bluetoothManager.getConnectionState(
                                device,
                                BluetoothProfile.GATT
                            )

                        appendLog(
                            "WATCHDOG: BluetoothManager GATT state = " +
                                "$state (${profileStateToString(state)})"
                        )

                        if (
                            state ==
                            BluetoothProfile.STATE_CONNECTED
                        ) {

                            if (
                                !connected
                            ) {

                                appendLog(
                                    "WATCHDOG: Android уже считает GATT CONNECTED."
                                )

                                appendLog(
                                    "WATCHDOG: callback CONNECTED ранее не получен."
                                )

                                connected =
                                    true

                                connectionStartedAt =
                                    System.currentTimeMillis()

                                notifyConnectionState(
                                    true,
                                    safeDeviceName(
                                        device
                                    )
                                )
                            }

                            if (
                                !serviceDiscoveryStarted &&
                                !servicesDiscovered
                            ) {

                                if (
                                    gatt != null
                                ) {

                                    appendLog(
                                        "WATCHDOG: запускаем discoverServices() вручную."
                                    )

                                    startServiceDiscovery(
                                        gatt,
                                        "WATCHDOG"
                                    )

                                } else {

                                    appendLog(
                                        "WATCHDOG: BluetoothGatt = null, discoverServices невозможен."
                                    )
                                }
                            }
                        }

                    } catch (
                        e: Throwable
                    ) {

                        appendThrowable(
                            "WATCHDOG getConnectionState",
                            e
                        )
                    }
                }

                if (
                    servicesDiscovered
                ) {

                    appendLog(
                        "WATCHDOG: GATT MAP уже получен."
                    )

                    appendLog(
                        "WATCHDOG остановлен."
                    )

                    return
                }

                if (
                    watchdogSeconds >=
                    MAX_WATCHDOG_SECONDS
                ) {

                    appendLog(
                        "========================================"
                    )

                    appendLog(
                        "WATCHDOG завершён после $MAX_WATCHDOG_SECONDS сек."
                    )

                    appendLog(
                        "servicesDiscovered = $servicesDiscovered"
                    )

                    appendLog(
                        "serviceDiscoveryStarted = $serviceDiscoveryStarted"
                    )

                    appendLog(
                        "connected = $connected"
                    )

                    appendLog(
                        "========================================"
                    )

                    return
                }

                mainHandler.postDelayed(
                    this,
                    WATCHDOG_MS
                )
            }
        }

    init {

        mainHandler.post(
            uiLoop
        )
    }

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
                "ОШИБКА: нет разрешения BLUETOOTH_CONNECT"
            )

            notifyConnectionState(
                false,
                safeDeviceName(
                    device
                )
            )

            return
        }

        closeGatt()

        logLines.clear()

        stopped =
            false

        connected =
            false

        serviceDiscoveryStarted =
            false

        servicesDiscovered =
            false

        watchdogSeconds =
            0

        connectionStartedAt =
            0L

        currentDevice =
            device

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
            "Запись в карту водителя: ЗАПРЕЩЕНА"
        )

        appendLog(
            "writeCharacteristic(): НЕ используется"
        )

        appendLog(
            "writeDescriptor(): НЕ используется"
        )

        appendLog(
            "Команды изменения DTCO: НЕ отправляются"
        )

        appendLog(
            "Команды карты водителя: НЕ отправляются"
        )

        appendLog(
            "Чтение содержимого карты: НЕ выполняется"
        )

        appendLog(
            "Разрешено только BLE/GATT connection + service discovery"
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
            "STEP 1: запускаем connectGatt()"
        )

        appendLog(
            "После запуска включится WATCHDOG."
        )

        appendLog(
            "Если callback CONNECTED потеряется,"
        )

        appendLog(
            "WATCHDOG проверит состояние через BluetoothManager."
        )

        appendLog(
            "----------------------------------------"
        )

        try {

            val gatt =

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.M
                ) {

                    appendLog(
                        "connectGatt(autoConnect=false, TRANSPORT_LE)"
                    )

                    device.connectGatt(
                        context,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )

                } else {

                    appendLog(
                        "connectGatt(autoConnect=false)"
                    )

                    device.connectGatt(
                        context,
                        false,
                        gattCallback
                    )
                }

            currentGatt =
                gatt

            if (
                gatt == null
            ) {

                appendLog(
                    "ОШИБКА: connectGatt() вернул null"
                )

            } else {

                appendLog(
                    "BluetoothGatt создан."
                )

                appendLog(
                    "Ожидаем callback или WATCHDOG..."
                )
            }

        } catch (
            e: Throwable
        ) {

            appendThrowable(
                "connectGatt",
                e
            )
        }

        mainHandler.removeCallbacks(
            watchdogLoop
        )

        mainHandler.postDelayed(
            watchdogLoop,
            WATCHDOG_MS
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
                    "----------------------------------------"
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
                    "device = ${safeDeviceName(gatt.device)}"
                )

                when (
                    newState
                ) {

                    BluetoothProfile.STATE_CONNECTED -> {

                        connected =
                            true

                        connectionStartedAt =
                            System.currentTimeMillis()

                        appendLog(
                            "========================================"
                        )

                        appendLog(
                            "BLE GATT CONNECTED"
                        )

                        appendLog(
                            "CALLBACK CONNECTED получен штатно."
                        )

                        appendLog(
                            "========================================"
                        )

                        notifyConnectionState(
                            true,
                            safeDeviceName(
                                gatt.device
                            )
                        )

                        if (
                            !serviceDiscoveryStarted
                        ) {

                            startServiceDiscovery(
                                gatt,
                                "CALLBACK"
                            )
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {

                        connected =
                            false

                        appendLog(
                            "========================================"
                        )

                        appendLog(
                            "BLE GATT DISCONNECTED"
                        )

                        appendLog(
                            "status = $status (${gattStatusToString(status)})"
                        )

                        appendLog(
                            "========================================"
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
                            "BLE состояние: CONNECTING"
                        )
                    }

                    BluetoothProfile.STATE_DISCONNECTING -> {

                        appendLog(
                            "BLE состояние: DISCONNECTING"
                        )
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                appendLog(
                    "========================================"
                )

                appendLog(
                    "CALLBACK: onServicesDiscovered"
                )

                appendLog(
                    "status = $status (${gattStatusToString(status)})"
                )

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    appendLog(
                        "ОШИБКА SERVICE DISCOVERY"
                    )

                    serviceDiscoveryStarted =
                        false

                    appendLog(
                        "========================================"
                    )

                    return
                }

                servicesDiscovered =
                    true

                dumpGattMap(
                    gatt
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
    private fun startServiceDiscovery(
        gatt: BluetoothGatt,
        source: String
    ) {

        if (
            serviceDiscoveryStarted ||
            servicesDiscovered
        ) {
            return
        }

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "$source: STEP 2 — discoverServices()"
        )

        appendLog(
            "Это только получение структуры BLE GATT."
        )

        appendLog(
            "Никаких WRITE-команд нет."
        )

        try {

            val started =
                gatt.discoverServices()

            serviceDiscoveryStarted =
                started

            appendLog(
                "discoverServices() returned: $started"
            )

            if (
                started
            ) {

                appendLog(
                    "Ожидаем onServicesDiscovered..."
                )

            } else {

                appendLog(
                    "discoverServices() не стартовал."
                )
            }

        } catch (
            e: Throwable
        ) {

            serviceDiscoveryStarted =
                false

            appendThrowable(
                "discoverServices",
                e
            )
        }
    }

    private fun dumpGattMap(
        gatt: BluetoothGatt
    ) {

        val services =
            try {

                gatt.services

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "gatt.services",
                    e
                )

                emptyList()
            }

        appendLog(
            "========================================"
        )

        appendLog(
            "GATT MAP"
        )

        appendLog(
            "Services found: ${services.size}"
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
            "========================================"
        )

        appendLog(
            "GATT MAP COMPLETE"
        )

        appendLog(
            "Всего services: ${services.size}"
        )

        appendLog(
            "Данные карты НЕ читались."
        )

        appendLog(
            "Данные карты НЕ изменялись."
        )

        appendLog(
            "Никаких WRITE операций не было."
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
            "Service type: ${serviceTypeToString(service.type)}"
        )

        appendLog(
            "Service instanceId: ${service.instanceId}"
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
                "  --------------------------------------"
            )

            appendLog(
                "  CHARACTERISTIC [$serviceIndex.$characteristicIndex]"
            )

            appendLog(
                "  UUID: ${characteristic.uuid}"
            )

            appendLog(
                "  properties HEX: 0x" +
                    characteristic.properties
                        .toString(16)
                        .uppercase(Locale.US)
            )

            appendLog(
                "  properties: " +
                    characteristicPropertiesToString(
                        characteristic.properties
                    )
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

        appendLog(
            "----------------------------------------"
        )

        try {

            val uuids =
                device.uuids

            if (
                uuids.isNullOrEmpty()
            ) {

                appendLog(
                    "Cached UUID: null"
                )

            } else {

                uuids.forEachIndexed {
                        index,
                        uuid ->

                    appendLog(
                        "Cached UUID[$index]: ${uuid.uuid}"
                    )
                }
            }

        } catch (
            e: Throwable
        ) {

            appendThrowable(
                "Cached UUID",
                e
            )
        }
    }

    fun disconnect() {

        stopped =
            true

        connected =
            false

        serviceDiscoveryStarted =
            false

        servicesDiscovered =
            false

        mainHandler.removeCallbacks(
            watchdogLoop
        )

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
            "Сообщение: ${throwable.message ?: "(без сообщения)"}"
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
