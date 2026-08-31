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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
            "BLE-GATT-AUTO-2"

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

    private var scheduler:
        ScheduledExecutorService? = null

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

        stopScheduler()

        closeGatt()

        logLines.clear()

        currentDevice =
            device

        connected =
            false

        servicesDiscovered =
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
            "Запись в карту водителя: ЗАПРЕЩЕНА"
        )

        appendLog(
            "Запись в DTCO: ЗАПРЕЩЕНА"
        )

        appendLog(
            "writeCharacteristic(): НЕ используется"
        )

        appendLog(
            "writeDescriptor(): НЕ используется"
        )

        appendLog(
            "Чтение содержимого карты: НЕ выполняется"
        )

        appendLog(
            "Команды карте водителя: НЕ отправляются"
        )

        appendLog(
            "Команды изменения DTCO: НЕ отправляются"
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

            if (
                gatt == null
            ) {

                appendLog(
                    "ОШИБКА: connectGatt вернул NULL."
                )

                return
            }

            appendLog(
                "AUTO CHECK #1 назначен через 2 секунды."
            )

            appendLog(
                "AUTO CHECK #2 назначен через 5 секунд."
            )

            startAutomaticChecks()

        } catch (
            e: Throwable
        ) {

            appendThrowable(
                "connectGatt",
                e
            )
        }
    }

    private fun startAutomaticChecks() {

        stopScheduler()

        val executor =
            Executors.newSingleThreadScheduledExecutor()

        scheduler =
            executor

        executor.schedule(
            {

                try {

                    automaticGattCheck(
                        1
                    )

                } catch (
                    e: Throwable
                ) {

                    appendThrowable(
                        "AUTO CHECK #1",
                        e
                    )
                }

            },
            2,
            TimeUnit.SECONDS
        )

        executor.schedule(
            {

                try {

                    automaticGattCheck(
                        2
                    )

                } catch (
                    e: Throwable
                ) {

                    appendThrowable(
                        "AUTO CHECK #2",
                        e
                    )
                }

            },
            5,
            TimeUnit.SECONDS
        )
    }

    @SuppressLint("MissingPermission")
    private fun automaticGattCheck(
        number: Int
    ) {

        appendLog(
            ""
        )

        appendLog(
            "========================================"
        )

        appendLog(
            "AUTO GATT CHECK #$number"
        )

        appendLog(
            "Thread: ${Thread.currentThread().name}"
        )

        appendLog(
            "========================================"
        )

        performGattCheck(
            "AUTO #$number"
        )
    }

    /*
     * Оставляем ручную функцию совместимой с MainActivity.
     * Даже если кнопка не работает, сборка не сломается.
     */
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

        performGattCheck(
            "MANUAL"
        )
    }

    @SuppressLint("MissingPermission")
    private fun performGattCheck(
        source: String
    ) {

        if (
            !hasConnectPermission()
        ) {

            appendLog(
                "$source: нет BLUETOOTH_CONNECT"
            )

            return
        }

        val device =
            currentDevice

        val gatt =
            currentGatt

        if (
            device == null
        ) {

            appendLog(
                "$source: currentDevice = NULL"
            )

            return
        }

        appendLog(
            "Device: ${safeDeviceName(device)}"
        )

        appendLog(
            "Address: ${safeDeviceAddress(device)}"
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
                    "UNKNOWN"
                }
            }"
        )

        val managerState =

            try {

                bluetoothManager.getConnectionState(
                    device,
                    BluetoothProfile.GATT
                )

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "BluetoothManager.getConnectionState",
                    e
                )

                -1
            }

        appendLog(
            "Android GATT state = " +
                "$managerState (${profileStateToString(managerState)})"
        )

        appendLog(
            "Internal connected flag = $connected"
        )

        appendLog(
            "Internal servicesDiscovered flag = $servicesDiscovered"
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

        val servicesBefore =

            try {

                gatt.services

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "gatt.services BEFORE",
                    e
                )

                emptyList()
            }

        appendLog(
            "Services BEFORE discoverServices(): ${servicesBefore.size}"
        )

        if (
            servicesBefore.isNotEmpty()
        ) {

            appendLog(
                "Сервисы уже доступны."
            )

            servicesDiscovered =
                true

            dumpGattMap(
                gatt,
                "$source / ALREADY AVAILABLE"
            )

            return
        }

        appendLog(
            "$source: вызываем discoverServices()"
        )

        appendLog(
            "Это только запрос структуры GATT."
        )

        appendLog(
            "WRITE операций нет."
        )

        try {

            val result =
                gatt.discoverServices()

            appendLog(
                "discoverServices() returned = $result"
            )

            if (
                result
            ) {

                appendLog(
                    "Android принял запрос service discovery."
                )

                appendLog(
                    "Ожидаем onServicesDiscovered()."
                )

            } else {

                appendLog(
                    "Android отказался запускать service discovery."
                )
            }

        } catch (
            e: Throwable
        ) {

            appendThrowable(
                "$source discoverServices",
                e
            )
        }

        val servicesAfter =

            try {

                gatt.services

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "gatt.services AFTER",
                    e
                )

                emptyList()
            }

        appendLog(
            "Services immediately AFTER call: ${servicesAfter.size}"
        )

        if (
            servicesAfter.isNotEmpty()
        ) {

            servicesDiscovered =
                true

            dumpGattMap(
                gatt,
                "$source / AFTER CALL"
            )
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

                        appendLog(
                            "BLE GATT CONNECTED"
                        )

                        notifyConnectionState(
                            true,
                            safeDeviceName(
                                gatt.device
                            )
                        )

                        appendLog(
                            "CALLBACK: пробуем discoverServices()"
                        )

                        try {

                            val result =
                                gatt.discoverServices()

                            appendLog(
                                "CALLBACK discoverServices() returned = $result"
                            )

                        } catch (
                            e: Throwable
                        ) {

                            appendThrowable(
                                "callback discoverServices",
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

                } else {

                    appendLog(
                        "SERVICE DISCOVERY ERROR"
                    )
                }
            }

            override fun onServiceChanged(
                gatt: BluetoothGatt
            ) {

                appendLog(
                    "CALLBACK: onServiceChanged"
                )
            }
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
            "DTCO НЕ изменялся."
        )

        appendLog(
            "WRITE операций НЕ выполнялось."
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

            appendLog(
                "  Permissions HEX: 0x${
                    characteristic.permissions
                        .toString(16)
                        .uppercase(Locale.US)
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

                appendLog(
                    "    Permissions HEX: 0x${
                        descriptor.permissions
                            .toString(16)
                            .uppercase(Locale.US)
                    }"
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

        val cached =

            try {

                device.uuids

            } catch (
                _: Throwable
            ) {

                null
            }

        if (
            cached.isNullOrEmpty()
        ) {

            appendLog(
                "Cached UUID: null"
            )

        } else {

            cached.forEachIndexed {
                    index,
                    uuid ->

                appendLog(
                    "Cached UUID[$index]: ${uuid.uuid}"
                )
            }
        }
    }

    fun disconnect() {

        stopScheduler()

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

    private fun stopScheduler() {

        val old =
            scheduler

        scheduler =
            null

        try {

            old?.shutdownNow()

        } catch (
            _: Throwable
        ) {
        }
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

        pushLogToUi()
    }

    private fun pushLogToUi() {

        val text =
            getLog()

        mainHandler.post {

            try {

                listener?.onLogChanged(
                    text
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
