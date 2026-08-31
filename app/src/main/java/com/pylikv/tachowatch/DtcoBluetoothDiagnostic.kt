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

        fun onLogChanged(fullLog: String)

        fun onConnectionStateChanged(
            connected: Boolean,
            deviceName: String?
        )
    }

    companion object {

        private const val VERSION =
            "BLE-GATT-MANUAL-1"

        private const val UI_REFRESH_MS =
            400L

        private const val MAX_LOG_LINES =
            2500
    }

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val bluetoothManager =
        context.getSystemService(
            Context.BLUETOOTH_SERVICE
        ) as BluetoothManager

    private val logLines =
        CopyOnWriteArrayList<String>()

    @Volatile
    private var currentGatt: BluetoothGatt? =
        null

    @Volatile
    private var currentDevice: BluetoothDevice? =
        null

    @Volatile
    private var connected =
        false

    @Volatile
    private var servicesDiscovered =
        false

    private val uiLoop =
        object : Runnable {

            override fun run() {

                try {

                    listener?.onLogChanged(
                        getLog()
                    )

                } catch (_: Throwable) {
                }

                mainHandler.postDelayed(
                    this,
                    UI_REFRESH_MS
                )
            }
        }

    init {

        mainHandler.post(
            uiLoop
        )
    }

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
            "Разрешено: BLE connection + GATT service discovery"
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
                "BluetoothGatt object: " +
                    if (gatt != null) {
                        "CREATED"
                    } else {
                        "NULL"
                    }
            )

            appendLog(
                "Теперь можно нажать «Проверить GATT сейчас»."
            )

        } catch (e: Throwable) {

            appendThrowable(
                "connectGatt",
                e
            )
        }
    }

    /*
     * РУЧНАЯ ПРОВЕРКА.
     *
     * Никакой записи.
     * Никаких команд карте.
     * Никакого изменения DTCO.
     *
     * Только:
     * 1. состояние Android GATT;
     * 2. проверка уже известных services;
     * 3. discoverServices().
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

        if (!hasConnectPermission()) {

            appendLog(
                "ОШИБКА: нет BLUETOOTH_CONNECT"
            )

            return
        }

        val device =
            currentDevice

        val gatt =
            currentGatt

        if (device == null) {

            appendLog(
                "currentDevice = NULL"
            )

            appendLog(
                "Сначала выбери DTCO и нажми подключение."
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
            "Bond: ${
                try {
                    bondStateToString(
                        device.bondState
                    )
                } catch (_: Throwable) {
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

            } catch (e: Throwable) {

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

        if (gatt == null) {

            appendLog(
                "currentGatt = NULL"
            )

            appendLog(
                "discoverServices невозможен."
            )

            appendLog(
                "========================================"
            )

            return
        }

        appendLog(
            "currentGatt = EXISTS"
        )

        /*
         * Сначала смотрим, нет ли уже сервисов
         * в локальном объекте BluetoothGatt.
         */
        val existingServices =

            try {

                gatt.services

            } catch (e: Throwable) {

                appendThrowable(
                    "gatt.services BEFORE discovery",
                    e
                )

                emptyList()
            }

        appendLog(
            "Services BEFORE discoverServices(): " +
                existingServices.size
        )

        if (existingServices.isNotEmpty()) {

            appendLog(
                "GATT services уже присутствуют."
            )

            servicesDiscovered =
                true

            dumpGattMap(
                gatt,
                "CACHED/AVAILABLE"
            )

            return
        }

        appendLog(
            "STEP 2: ручной discoverServices()"
        )

        appendLog(
            "Операций записи НЕТ."
        )

        try {

            val result =
                gatt.discoverServices()

            appendLog(
                "discoverServices() returned = $result"
            )

            if (result) {

                appendLog(
                    "Запрос service discovery принят Android."
                )

                appendLog(
                    "Ожидаем onServicesDiscovered()."
                )

            } else {

                appendLog(
                    "Android НЕ запустил service discovery."
                )
            }

        } catch (e: Throwable) {

            appendThrowable(
                "manual discoverServices",
                e
            )
        }

        /*
         * Ещё раз смотрим локальную GATT-базу.
         * Это НЕ чтение карты.
         */
        val afterServices =

            try {

                gatt.services

            } catch (e: Throwable) {

                appendThrowable(
                    "gatt.services AFTER discovery call",
                    e
                )

                emptyList()
            }

        appendLog(
            "Services immediately AFTER call: " +
                afterServices.size
        )

        if (afterServices.isNotEmpty()) {

            servicesDiscovered =
                true

            dumpGattMap(
                gatt,
                "AVAILABLE AFTER MANUAL CALL"
            )
        }

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
                    "status = $status (${gattStatusToString(status)})"
                )

                appendLog(
                    "newState = $newState (${profileStateToString(newState)})"
                )

                when (newState) {

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

                        /*
                         * Автоматически тоже пробуем.
                         * Только service discovery.
                         */
                        try {

                            appendLog(
                                "AUTO: discoverServices()"
                            )

                            val result =
                                gatt.discoverServices()

                            appendLog(
                                "AUTO discoverServices returned = $result"
                            )

                        } catch (e: Throwable) {

                            appendThrowable(
                                "AUTO discoverServices",
                                e
                            )
                        }
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

                appendLog(
                    "========================================"
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

    private fun dumpGattMap(
        gatt: BluetoothGatt,
        source: String
    ) {

        val services =

            try {

                gatt.services

            } catch (e: Throwable) {

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

            } catch (_: Throwable) {

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
                "  Properties HEX: 0x" +
                    characteristic.properties
                        .toString(16)
                        .uppercase(Locale.US)
            )

            appendLog(
                "  Properties: " +
                    characteristicPropertiesToString(
                        characteristic.properties
                    )
            )

            appendLog(
                "  Permissions HEX: 0x" +
                    characteristic.permissions
                        .toString(16)
                        .uppercase(Locale.US)
            )

            val descriptors =

                try {

                    characteristic.descriptors

                } catch (_: Throwable) {

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
                    "    Permissions HEX: 0x" +
                        descriptor.permissions
                            .toString(16)
                            .uppercase(Locale.US)
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
                } catch (_: Throwable) {
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
                } catch (_: Throwable) {
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

            } catch (_: Throwable) {

                null
            }

        if (cached.isNullOrEmpty()) {

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
                safeDeviceName(it)
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {

        val gatt =
            currentGatt

        currentGatt =
            null

        if (gatt != null) {

            try {

                gatt.disconnect()

            } catch (_: Throwable) {
            }

            try {

                gatt.close()

            } catch (_: Throwable) {
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

            } catch (_: Throwable) {
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

                logLines.removeAt(0)

            } catch (_: Throwable) {

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

        } catch (_: Throwable) {

            "Нет доступа"
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceAddress(
        device: BluetoothDevice
    ): String {

        return try {

            device.address

        } catch (_: Throwable) {

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

            -1 ->
                "ERROR"

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

    private fun serviceTypeToString(
        type: Int
    ): String {

        return when (type) {

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
            result.add("BROADCAST")
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_READ != 0
        ) {
            result.add("READ")
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            result.add("WRITE_NO_RESPONSE")
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        ) {
            result.add("WRITE")
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        ) {
            result.add("NOTIFY")
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        ) {
            result.add("INDICATE")
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0
        ) {
            result.add("SIGNED_WRITE")
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0
        ) {
            result.add("EXTENDED_PROPS")
        }

        return if (result.isEmpty()) {

            "NONE"

        } else {

            result.joinToString(
                separator = " | "
            )
        }
    }
}
