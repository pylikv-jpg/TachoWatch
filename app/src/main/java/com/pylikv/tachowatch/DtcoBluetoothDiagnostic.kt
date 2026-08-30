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
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Диагностический Bluetooth-модуль TachoWatch.
 *
 * Назначение:
 * 1. Подключиться к уже найденному/сопряжённому DTCO.
 * 2. Выполнить GATT service discovery.
 * 3. Записать UUID сервисов, характеристик и дескрипторов.
 * 4. Определить READ / WRITE / NOTIFY / INDICATE.
 * 5. Попытаться прочитать доступные характеристики.
 * 6. Подписаться на NOTIFY / INDICATE.
 * 7. Записывать полученные данные в HEX.
 *
 * Здесь НЕТ предположений о протоколе DTCO.
 * Сначала собираем реальные данные конкретного тахографа.
 */
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

    private var bluetoothGatt: BluetoothGatt? = null

    private val logLines = CopyOnWriteArrayList<String>()

    private val readQueue =
        ArrayDeque<BluetoothGattCharacteristic>()

    private val notificationQueue =
        ArrayDeque<BluetoothGattCharacteristic>()

    private var currentRead:
        BluetoothGattCharacteristic? = null

    private var currentNotification:
        BluetoothGattCharacteristic? = null

    private var notificationStage = 0

    companion object {

        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString(
                "00002902-0000-1000-8000-00805f9b34fb"
            )

        private const val PROPERTY_BROADCAST = 0x01
        private const val PROPERTY_READ = 0x02
        private const val PROPERTY_WRITE_NO_RESPONSE = 0x04
        private const val PROPERTY_WRITE = 0x08
        private const val PROPERTY_NOTIFY = 0x10
        private const val PROPERTY_INDICATE = 0x20
        private const val PROPERTY_SIGNED_WRITE = 0x40
        private const val PROPERTY_EXTENDED_PROPS = 0x80
    }

    /**
     * Проверяем, разрешено ли приложению работать
     * с уже известными Bluetooth-устройствами.
     */
    fun hasConnectPermission(): Boolean {

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

        } else {
            true
        }
    }

    /**
     * Начинаем диагностическое подключение.
     *
     * BluetoothDevice должен быть получен экраном приложения:
     * например из списка сопряжённых устройств или BLE scan.
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {

        if (!hasConnectPermission()) {

            log(
                "ОШИБКА: отсутствует разрешение " +
                    "BLUETOOTH_CONNECT"
            )

            return
        }

        disconnect()

        clearLog()

        log("========================================")
        log("TachoWatch — диагностика DTCO")
        log("========================================")

        val safeName = try {
            device.name ?: "Без имени"
        } catch (_: SecurityException) {
            "Нет доступа"
        }

        val safeAddress = try {
            device.address
        } catch (_: SecurityException) {
            "Нет доступа"
        }

        log("Устройство: $safeName")
        log("Адрес: $safeAddress")
        log("Bond state: ${bondStateToString(device.bondState)}")
        log("Тип устройства: ${deviceTypeToString(device.type)}")
        log("Android API: ${Build.VERSION.SDK_INT}")

        log("----------------------------------------")
        log("Начинаю GATT-подключение...")

        try {

            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            if (bluetoothGatt == null) {
                log("ОШИБКА: connectGatt вернул null")
            }

        } catch (e: Exception) {

            log(
                "ОШИБКА connectGatt: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * Останавливаем соединение.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {

        try {

            bluetoothGatt?.disconnect()

        } catch (_: Exception) {
        }

        try {

            bluetoothGatt?.close()

        } catch (_: Exception) {
        }

        bluetoothGatt = null

        readQueue.clear()
        notificationQueue.clear()

        currentRead = null
        currentNotification = null
        notificationStage = 0
    }

    /**
     * Полный диагностический журнал.
     */
    fun getLog(): String {
        return logLines.joinToString("\n")
    }

    fun clearLog() {

        logLines.clear()

        listener?.onLogChanged("")
    }

    private val gattCallback =
        object : BluetoothGattCallback() {

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                log(
                    "GATT state: status=$status, " +
                        "state=${connectionStateToString(newState)}"
                )

                when (newState) {

                    BluetoothProfile.STATE_CONNECTED -> {

                        val name = try {
                            gatt.device.name
                        } catch (_: Exception) {
                            null
                        }

                        log("Bluetooth GATT подключён.")

                        listener?.onConnectionStateChanged(
                            true,
                            name
                        )

                        try {

                            val mtuStarted =
                                gatt.requestMtu(517)

                            log(
                                "Запрос MTU отправлен: " +
                                    "$mtuStarted"
                            )

                        } catch (e: Exception) {

                            log(
                                "MTU request exception: " +
                                    e.message
                            )

                            startServiceDiscovery(gatt)
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {

                        log("Bluetooth GATT отключён.")

                        val name = try {
                            gatt.device.name
                        } catch (_: Exception) {
                            null
                        }

                        listener?.onConnectionStateChanged(
                            false,
                            name
                        )

                        try {
                            gatt.close()
                        } catch (_: Exception) {
                        }

                        if (bluetoothGatt === gatt) {
                            bluetoothGatt = null
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {

                log(
                    "MTU changed: mtu=$mtu, status=$status"
                )

                startServiceDiscovery(gatt)
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                log("----------------------------------------")
                log("Service discovery завершён.")
                log("Status: $status")

                if (status != BluetoothGatt.GATT_SUCCESS) {

                    log(
                        "ОШИБКА: сервисы не удалось " +
                            "прочитать."
                    )

                    return
                }

                dumpGattDatabase(gatt)

                prepareOperations(gatt)
            }

            @Deprecated(
                "Deprecated in Android API, " +
                    "required for compatibility"
            )
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {

                handleCharacteristicRead(
                    gatt,
                    characteristic,
                    characteristic.value ?: byteArrayOf(),
                    status
                )
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {

                handleCharacteristicRead(
                    gatt,
                    characteristic,
                    value,
                    status
                )
            }

            @Deprecated(
                "Deprecated in Android API, " +
                    "required for compatibility"
            )
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {

                handleCharacteristicChanged(
                    characteristic,
                    characteristic.value ?: byteArrayOf()
                )
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                handleCharacteristicChanged(
                    characteristic,
                    value
                )
            }

            @SuppressLint("MissingPermission")
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {

                log(
                    "Descriptor write: ${descriptor.uuid} " +
                        "status=$status"
                )

                currentNotification = null
                notificationStage = 0

                enableNextNotification(gatt)
            }

            override fun onReadRemoteRssi(
                gatt: BluetoothGatt,
                rssi: Int,
                status: Int
            ) {

                log(
                    "RSSI: $rssi dBm, status=$status"
                )
            }
        }

    @SuppressLint("MissingPermission")
    private fun startServiceDiscovery(
        gatt: BluetoothGatt
    ) {

        try {

            log("----------------------------------------")
            log("Запускаю discoverServices()...")

            val started =
                gatt.discoverServices()

            log(
                "discoverServices started: $started"
            )

        } catch (e: Exception) {

            log(
                "ОШИБКА discoverServices: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun dumpGattDatabase(
        gatt: BluetoothGatt
    ) {

        val services = gatt.services

        log("========================================")
        log("НАЙДЕННАЯ GATT-БАЗА")
        log("Количество сервисов: ${services.size}")
        log("========================================")

        services.forEachIndexed { serviceIndex, service ->

            log("")
            log(
                "SERVICE #${serviceIndex + 1}"
            )

            log(
                "UUID: ${service.uuid}"
            )

            log(
                "Type: " +
                    when (service.type) {
                        0 -> "PRIMARY"
                        1 -> "SECONDARY"
                        else -> service.type.toString()
                    }
            )

            log(
                "Characteristics: " +
                    service.characteristics.size
            )

            service.characteristics
                .forEachIndexed { charIndex, characteristic ->

                    log(
                        "  CHARACTERISTIC " +
                            "#${charIndex + 1}"
                    )

                    log(
                        "  UUID: " +
                            characteristic.uuid
                    )

                    log(
                        "  Properties: " +
                            propertiesToString(
                                characteristic.properties
                            )
                    )

                    log(
                        "  Permissions: " +
                            characteristic.permissions
                    )

                    if (
                        characteristic.descriptors.isEmpty()
                    ) {

                        log(
                            "    Descriptors: нет"
                        )

                    } else {

                        characteristic.descriptors
                            .forEachIndexed {
                                    descriptorIndex,
                                    descriptor ->

                                log(
                                    "    DESCRIPTOR " +
                                        "#${descriptorIndex + 1}"
                                )

                                log(
                                    "    UUID: " +
                                        descriptor.uuid
                                )

                                log(
                                    "    Permissions: " +
                                        descriptor.permissions
                                )
                            }
                    }
                }
        }

        log("")
        log("========================================")
        log("Конец GATT-базы")
        log("========================================")
    }

    /**
     * После обнаружения сервисов формируем две очереди:
     *
     * 1. READ — безопасно пытаемся прочитать.
     * 2. NOTIFY/INDICATE — подписываемся на изменения.
     *
     * Записи в неизвестные характеристики НЕ выполняются.
     */
    private fun prepareOperations(
        gatt: BluetoothGatt
    ) {

        readQueue.clear()
        notificationQueue.clear()

        gatt.services.forEach { service ->

            service.characteristics.forEach {
                    characteristic ->

                val properties =
                    characteristic.properties

                if (
                    properties and
                    BluetoothGattCharacteristic.PROPERTY_READ != 0
                ) {

                    readQueue.addLast(
                        characteristic
                    )
                }

                if (
                    properties and
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    properties and
                    BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                ) {

                    notificationQueue.addLast(
                        characteristic
                    )
                }
            }
        }

        log("----------------------------------------")
        log(
            "READ характеристик: " +
                readQueue.size
        )

        log(
            "NOTIFY/INDICATE характеристик: " +
                notificationQueue.size
        )

        log("----------------------------------------")

        readNextCharacteristic(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun readNextCharacteristic(
        gatt: BluetoothGatt
    ) {

        if (currentRead != null) {
            return
        }

        val characteristic =
            if (readQueue.isEmpty()) {
                null
            } else {
                readQueue.removeFirst()
            }

        if (characteristic == null) {

            log("----------------------------------------")
            log("Чтение READ характеристик завершено.")
            log("Начинаю подписку на уведомления.")

            enableNextNotification(gatt)

            return
        }

        currentRead = characteristic

        log(
            "READ -> ${characteristic.uuid}"
        )

        try {

            val started =
                gatt.readCharacteristic(
                    characteristic
                )

            if (!started) {

                log(
                    "READ не запущен: " +
                        characteristic.uuid
                )

                currentRead = null

                readNextCharacteristic(gatt)
            }

        } catch (e: Exception) {

            log(
                "READ exception ${characteristic.uuid}: " +
                    e.message
            )

            currentRead = null

            readNextCharacteristic(gatt)
        }
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {

        log(
            "READ result <- ${characteristic.uuid}"
        )

        log(
            "Status: $status"
        )

        if (status == BluetoothGatt.GATT_SUCCESS) {

            logByteArray(
                prefix = "DATA",
                value = value
            )
        }

        currentRead = null

        readNextCharacteristic(gatt)
    }

    private fun handleCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {

        log("----------------------------------------")

        log(
            "NOTIFY/INDICATE <- " +
                characteristic.uuid
        )

        logByteArray(
            prefix = "DATA",
            value = value
        )
    }

    @SuppressLint("MissingPermission")
    private fun enableNextNotification(
        gatt: BluetoothGatt
    ) {

        if (currentNotification != null) {
            return
        }

        val characteristic =
            if (notificationQueue.isEmpty()) {
                null
            } else {
                notificationQueue.removeFirst()
            }

        if (characteristic == null) {

            log("----------------------------------------")
            log(
                "Подписка на доступные " +
                    "NOTIFY/INDICATE завершена."
            )

            try {

                val started =
                    gatt.readRemoteRssi()

                log(
                    "RSSI request started: $started"
                )

            } catch (e: Exception) {

                log(
                    "RSSI request exception: " +
                        e.message
                )
            }

            log("========================================")
            log("DTCO DIAGNOSTIC READY")
            log("Жду входящие Bluetooth-данные...")
            log("========================================")

            return
        }

        currentNotification = characteristic

        val properties =
            characteristic.properties

        val supportsNotify =
            properties and
                BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

        val supportsIndicate =
            properties and
                BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

        log(
            "SUBSCRIBE -> ${characteristic.uuid}"
        )

        log(
            "Mode: " +
                when {
                    supportsIndicate -> "INDICATE"
                    supportsNotify -> "NOTIFY"
                    else -> "UNKNOWN"
                }
        )

        try {

            val localEnabled =
                gatt.setCharacteristicNotification(
                    characteristic,
                    true
                )

            log(
                "setCharacteristicNotification: " +
                    localEnabled
            )

            if (!localEnabled) {

                currentNotification = null

                enableNextNotification(gatt)

                return
            }

            val descriptor =
                characteristic.getDescriptor(
                    CLIENT_CHARACTERISTIC_CONFIG_UUID
                )

            if (descriptor == null) {

                log(
                    "CCCD отсутствует у " +
                        characteristic.uuid
                )

                /*
                 * Некоторые устройства могут отправлять
                 * уведомления даже без доступного CCCD.
                 * Локальную подписку оставляем включённой.
                 */

                currentNotification = null

                enableNextNotification(gatt)

                return
            }

            val descriptorValue =
                if (supportsIndicate) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }

            notificationStage = 1

            val writeStarted =
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU) {

                    gatt.writeDescriptor(
                        descriptor,
                        descriptorValue
                    ) == android.bluetooth.BluetoothStatusCodes.SUCCESS

                } else {

                    @Suppress("DEPRECATION")
                    descriptor.value = descriptorValue

                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(
                        descriptor
                    )
                }

            log(
                "CCCD write started: $writeStarted"
            )

            if (!writeStarted) {

                currentNotification = null
                notificationStage = 0

                enableNextNotification(gatt)
            }

        } catch (e: Exception) {

            log(
                "SUBSCRIBE exception " +
                    "${characteristic.uuid}: " +
                    e.message
            )

            currentNotification = null
            notificationStage = 0

            enableNextNotification(gatt)
        }
    }

    private fun logByteArray(
        prefix: String,
        value: ByteArray
    ) {

        log(
            "$prefix length: ${value.size}"
        )

        log(
            "$prefix HEX: ${value.toHexString()}"
        )

        val printable =
            value.toPrintableString()

        if (printable.isNotBlank()) {

            log(
                "$prefix TEXT: $printable"
            )
        }
    }

    private fun ByteArray.toHexString(): String {

        if (isEmpty()) {
            return "<empty>"
        }

        return joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        }
    }

    private fun ByteArray.toPrintableString(): String {

        if (isEmpty()) {
            return ""
        }

        return buildString {

            this@toPrintableString.forEach { byte ->

                val value =
                    byte.toInt() and 0xFF

                when (value) {

                    in 32..126 ->
                        append(value.toChar())

                    9 ->
                        append("\\t")

                    10 ->
                        append("\\n")

                    13 ->
                        append("\\r")

                    else ->
                        append('.')
                }
            }
        }
    }

    private fun propertiesToString(
        properties: Int
    ): String {

        val result =
            mutableListOf<String>()

        if (properties and PROPERTY_BROADCAST != 0) {
            result += "BROADCAST"
        }

        if (properties and PROPERTY_READ != 0) {
            result += "READ"
        }

        if (
            properties and
            PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            result += "WRITE_NO_RESPONSE"
        }

        if (properties and PROPERTY_WRITE != 0) {
            result += "WRITE"
        }

        if (properties and PROPERTY_NOTIFY != 0) {
            result += "NOTIFY"
        }

        if (properties and PROPERTY_INDICATE != 0) {
            result += "INDICATE"
        }

        if (
            properties and
            PROPERTY_SIGNED_WRITE != 0
        ) {
            result += "SIGNED_WRITE"
        }

        if (
            properties and
            PROPERTY_EXTENDED_PROPS != 0
        ) {
            result += "EXTENDED"
        }

        return if (result.isEmpty()) {
            "NONE (0x${properties.toString(16)})"
        } else {
            result.joinToString(" | ")
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

    private fun connectionStateToString(
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

    private fun log(
        message: String
    ) {

        val timestamp =
            SimpleDateFormat(
                "HH:mm:ss.SSS",
                Locale.getDefault()
            ).format(Date())

        val line =
            "[$timestamp] $message"

        logLines.add(line)

        listener?.onLogChanged(
            getLog()
        )
    }
}
