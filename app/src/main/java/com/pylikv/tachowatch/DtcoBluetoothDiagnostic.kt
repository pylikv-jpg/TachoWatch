package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
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
            "BLE-GATT-MAP-1"

        private const val UI_REFRESH_MS =
            400L

        private const val HEARTBEAT_MS =
            3000L

        private const val MAX_LOG_LINES =
            2000
    }

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

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

    private val heartbeatLoop =
        object : Runnable {

            override fun run() {

                if (
                    stopped
                ) {
                    return
                }

                if (
                    connected
                ) {

                    val elapsed =
                        if (
                            connectionStartedAt > 0L
                        ) {
                            (
                                System.currentTimeMillis() -
                                    connectionStartedAt
                                ) / 1000L
                        } else {
                            0L
                        }

                    appendLog(
                        "HEARTBEAT: BLE соединение активно, ${elapsed} сек."
                    )

                } else {

                    appendLog(
                        "HEARTBEAT: BLE пока не CONNECTED"
                    )
                }

                mainHandler.postDelayed(
                    this,
                    HEARTBEAT_MS
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
            "Режим: BLE GATT SERVICE MAP"
        )

        appendLog(
            "discoverServices(): ВКЛЮЧЕНО"
        )

        appendLog(
            "Чтение значений характеристик: ОТКЛЮЧЕНО"
        )

        appendLog(
            "Подписка NOTIFY/INDICATE: ОТКЛЮЧЕНА"
        )

        appendLog(
            "Запись характеристик: ОТКЛЮЧЕНА"
        )

        appendLog(
            "Запись descriptors: ОТКЛЮЧЕНА"
        )

        appendLog(
            "Команды DTCO: НЕ отправляются"
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
            "STEP 1: запускаем BLE connectGatt()"
        )

        appendLog(
            "После CONNECTED автоматически запустим discoverServices()."
        )

        appendLog(
            "В DTCO ничего записываться не будет."
        )

        appendLog(
            "----------------------------------------"
        )

        mainHandler.removeCallbacks(
            heartbeatLoop
        )

        mainHandler.postDelayed(
            heartbeatLoop,
            HEARTBEAT_MS
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

                notifyConnectionState(
                    false,
                    safeDeviceName(
                        device
                    )
                )

            } else {

                appendLog(
                    "BluetoothGatt создан."
                )

                appendLog(
                    "Ожидаем onConnectionStateChange..."
                )
            }

        } catch (
            e: Throwable
        ) {

            appendThrowable(
                "connectGatt",
                e
            )

            notifyConnectionState(
                false,
                safeDeviceName(
                    device
                )
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
                            "Соединение с DTCO установлено."
                        )

                        appendLog(
                            "STEP 2: запускаем discoverServices()"
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

                        try {

                            val started =
                                gatt.discoverServices()

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
                                    "ОШИБКА: discoverServices() не запустился."
                                )
                            }

                        } catch (
                            e: Throwable
                        ) {

                            appendThrowable(
                                "discoverServices",
                                e
                            )
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {

                        val wasConnected =
                            connected

                        connected =
                            false

                        appendLog(
                            "========================================"
                        )

                        appendLog(
                            "BLE GATT DISCONNECTED"
                        )

                        if (
                            wasConnected
                        ) {

                            val duration =
                                if (
                                    connectionStartedAt > 0L
                                ) {
                                    (
                                        System.currentTimeMillis() -
                                            connectionStartedAt
                                        ) / 1000L
                                } else {
                                    0L
                                }

                            appendLog(
                                "Соединение продержалось: ${duration} сек."
                            )

                        } else {

                            appendLog(
                                "CONNECTED до отключения не был получен."
                            )
                        }

                        appendLog(
                            "Последний status = $status (${gattStatusToString(status)})"
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

                    else -> {

                        appendLog(
                            "Неизвестное состояние BLE: $newState"
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
                    status != BluetoothGatt.GATT_SUCCESS
                ) {

                    appendLog(
                        "ОШИБКА: GATT Service Discovery завершился с ошибкой."
                    )

                    appendLog(
                        "========================================"
                    )

                    return
                }

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
                    "Найдено GATT services: ${services.size}"
                )

                appendLog(
                    "========================================"
                )

                if (
                    services.isEmpty()
                ) {

                    appendLog(
                        "Список services пуст."
                    )

                    return
                }

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
                    "GATT MAP ЗАВЕРШЁН"
                )

                appendLog(
                    "Всего services: ${services.size}"
                )

                appendLog(
                    "Никакие READ/WRITE/NOTIFY операции не выполнялись."
                )

                appendLog(
                    "Пришли мне весь журнал начиная с GATT MAP."
                )

                appendLog(
                    "========================================"
                )
            }

            override fun onServiceChanged(
                gatt: BluetoothGatt
            ) {

                super.onServiceChanged(
                    gatt
                )

                appendLog(
                    "CALLBACK: onServiceChanged"
                )

                appendLog(
                    "GATT database устройства изменилась."
                )
            }
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

        if (
            characteristics.isEmpty()
        ) {

            appendLog(
                "  (характеристик нет)"
            )
        }

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
                "  instanceId: ${characteristic.instanceId}"
            )

            appendLog(
                "  properties HEX: 0x${characteristic.properties.toString(16).uppercase(Locale.US)}"
            )

            appendLog(
                "  properties: ${characteristicPropertiesToString(characteristic.properties)}"
            )

            appendLog(
                "  permissions HEX: 0x${characteristic.permissions.toString(16).uppercase(Locale.US)}"
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

            if (
                descriptors.isEmpty()
            ) {

                appendLog(
                    "    (descriptors нет)"
                )
            }

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
                    "    permissions HEX: 0x${descriptor.permissions.toString(16).uppercase(Locale.US)}"
                )
            }
        }

        val includedServices =
            try {

                service.includedServices

            } catch (
                _: Throwable
            ) {

                emptyList()
            }

        appendLog(
            "Included services: ${includedServices.size}"
        )

        includedServices.forEachIndexed {
                index,
                included ->

            appendLog(
                "  Included[$index]: ${included.uuid}"
            )
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

        val bondState =
            try {

                bondStateToString(
                    device.bondState
                )

            } catch (
                _: Throwable
            ) {

                "Недоступно"
            }

        appendLog(
            "Bond state: $bondState"
        )

        val deviceType =
            try {

                deviceTypeToString(
                    device.type
                )

            } catch (
                _: Throwable
            ) {

                "Недоступно"
            }

        appendLog(
            "Тип Bluetooth: $deviceType"
        )

        appendLog(
            "Android API: ${Build.VERSION.SDK_INT}"
        )

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "Cached UUID:"
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
                        parcelUuid ->

                    appendLog(
                        "UUID[$index]: ${parcelUuid.uuid}"
                    )
                }
            }

        } catch (
            e: Throwable
        ) {

            appendThrowable(
                "Чтение cached UUID",
                e
            )
        }
    }

    fun disconnect() {

        stopped =
            true

        connected =
            false

        connectionStartedAt =
            0L

        mainHandler.removeCallbacks(
            heartbeatLoop
        )

        appendLog(
            "----------------------------------------"
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
                e: Throwable
            ) {

                appendThrowable(
                    "gatt.disconnect",
                    e
                )
            }

            try {

                gatt.close()

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "gatt.close",
                    e
                )
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

        throwable.cause?.let {

            appendLog(
                "Причина: ${it.javaClass.name}: ${it.message ?: "(без сообщения)"}"
            )
        }
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
