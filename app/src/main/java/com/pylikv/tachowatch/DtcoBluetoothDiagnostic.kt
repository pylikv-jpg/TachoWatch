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

/**
 * TachoWatch — DTCO BLE diagnostic
 *
 * GATT-SERVICES-5
 *
 * Чистый тест:
 *
 * CONNECT
 *   ↓
 * ожидание 1000 мс
 *   ↓
 * discoverServices()
 *   ↓
 * onServicesDiscovered()
 *   ↓
 * gatt.services
 *
 * requestMtu() полностью исключён.
 *
 * READ / WRITE / NOTIFY / INDICATE
 * в этой версии не выполняются.
 */
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
            "GATT-SERVICES-5"

        private const val DISCOVERY_DELAY_MS =
            1000L

        private const val READ_SERVICES_DELAY_MS =
            300L

        private const val UI_UPDATE_DELAY_MS =
            80L
    }

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private var bluetoothGatt:
        BluetoothGatt? = null

    private val logLines =
        CopyOnWriteArrayList<String>()

    private var uiUpdateScheduled =
        false

    private val uiUpdateRunnable =
        Runnable {

            uiUpdateScheduled =
                false

            try {

                listener?.onLogChanged(
                    getLog()
                )

            } catch (_: Throwable) {
            }
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

            log(
                "ОШИБКА: отсутствует BLUETOOTH_CONNECT"
            )

            return
        }

        disconnect()
        clearLog()

        log(
            "========================================"
        )

        log(
            "TachoWatch — диагностика DTCO"
        )

        log(
            "Версия диагностики: $VERSION"
        )

        log(
            "MTU TEST: ОТКЛЮЧЕН"
        )

        log(
            "========================================"
        )

        val name =
            try {
                device.name ?: "Без имени"
            } catch (_: Throwable) {
                "Нет доступа"
            }

        val address =
            try {
                device.address
            } catch (_: Throwable) {
                "Нет доступа"
            }

        val bond =
            try {
                bondStateToString(
                    device.bondState
                )
            } catch (_: Throwable) {
                "Нет доступа"
            }

        val type =
            try {
                deviceTypeToString(
                    device.type
                )
            } catch (_: Throwable) {
                "Нет доступа"
            }

        log(
            "Устройство: $name"
        )

        log(
            "Адрес: $address"
        )

        log(
            "Bond state: $bond"
        )

        log(
            "Тип устройства: $type"
        )

        log(
            "Android API: ${Build.VERSION.SDK_INT}"
        )

        log(
            "----------------------------------------"
        )

        log(
            "STEP 1: connectGatt()"
        )

        try {

            val result =
                device.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )

            bluetoothGatt =
                result

            log(
                "STEP 1 RESULT: connectGatt returned"
            )

            log(
                "BluetoothGatt null: ${result == null}"
            )

        } catch (e: Throwable) {

            logThrowable(
                "connectGatt",
                e
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {

        mainHandler.removeCallbacksAndMessages(
            null
        )

        uiUpdateScheduled =
            false

        val gatt =
            bluetoothGatt

        bluetoothGatt =
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

        scheduleUiUpdate()
    }

    fun getLog(): String {

        return logLines.joinToString(
            "\n"
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

                try {

                    log(
                        "----------------------------------------"
                    )

                    log(
                        "CALLBACK: onConnectionStateChange"
                    )

                    log(
                        "status=$status"
                    )

                    log(
                        "newState=" +
                            connectionStateToString(
                                newState
                            )
                    )

                    if (
                        newState ==
                        BluetoothProfile.STATE_CONNECTED
                    ) {

                        log(
                            "STEP 2 OK: GATT CONNECTED"
                        )

                        val name =
                            try {
                                gatt.device.name
                            } catch (_: Throwable) {
                                null
                            }

                        notifyConnectionState(
                            true,
                            name
                        )

                        /*
                         * ВАЖНО:
                         *
                         * НИКАКОГО requestMtu().
                         * НИКАКИХ других GATT операций.
                         */

                        log(
                            "MTU НЕ запрашиваю."
                        )

                        log(
                            "Жду $DISCOVERY_DELAY_MS мс " +
                                "перед discoverServices()."
                        )

                        mainHandler.postDelayed(
                            {

                                if (
                                    bluetoothGatt ===
                                    gatt
                                ) {

                                    runServiceDiscovery(
                                        gatt
                                    )

                                } else {

                                    log(
                                        "Discovery отменён: " +
                                            "GATT уже неактивен."
                                    )
                                }
                            },
                            DISCOVERY_DELAY_MS
                        )

                    } else if (
                        newState ==
                        BluetoothProfile.STATE_DISCONNECTED
                    ) {

                        log(
                            "GATT DISCONNECTED"
                        )

                        val name =
                            try {
                                gatt.device.name
                            } catch (_: Throwable) {
                                null
                            }

                        notifyConnectionState(
                            false,
                            name
                        )

                        try {
                            gatt.close()
                        } catch (_: Throwable) {
                        }

                        if (
                            bluetoothGatt ===
                            gatt
                        ) {

                            bluetoothGatt =
                                null
                        }
                    }

                } catch (e: Throwable) {

                    logThrowable(
                        "onConnectionStateChange",
                        e
                    )
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                /*
                 * Ничего тяжёлого внутри callback.
                 */

                try {

                    log(
                        "----------------------------------------"
                    )

                    log(
                        "CALLBACK onServicesDiscovered ENTER"
                    )

                    log(
                        "Discovery status: $status"
                    )

                    log(
                        "CALLBACK onServicesDiscovered EXIT PLAN"
                    )

                    val savedStatus =
                        status

                    mainHandler.postDelayed(
                        {

                            inspectGattServices(
                                gatt,
                                savedStatus
                            )
                        },
                        READ_SERVICES_DELAY_MS
                    )

                } catch (e: Throwable) {

                    logThrowable(
                        "onServicesDiscovered",
                        e
                    )
                }
            }
        }

    @SuppressLint("MissingPermission")
    private fun runServiceDiscovery(
        gatt: BluetoothGatt
    ) {

        log(
            "----------------------------------------"
        )

        log(
            "STEP 3: перед discoverServices()"
        )

        try {

            /*
             * Если Android зависнет именно здесь,
             * последней строкой останется STEP 3.
             */

            val started =
                gatt.discoverServices()

            /*
             * Если вызов вернулся,
             * эта строка ОБЯЗАТЕЛЬНО появится.
             */

            log(
                "STEP 4: discoverServices() ВЕРНУЛСЯ"
            )

            log(
                "discoverServices result: $started"
            )

            if (!started) {

                log(
                    "РЕЗУЛЬТАТ: Android вернул false."
                )
            }

        } catch (e: Throwable) {

            logThrowable(
                "discoverServices CALL",
                e
            )
        }
    }

    private fun inspectGattServices(
        gatt: BluetoothGatt,
        discoveryStatus: Int
    ) {

        log(
            "----------------------------------------"
        )

        log(
            "STEP 5: inspectGattServices()"
        )

        log(
            "Работа идёт ВНЕ Bluetooth callback."
        )

        log(
            "Сохранённый discovery status: " +
                discoveryStatus
        )

        if (
            discoveryStatus !=
            BluetoothGatt.GATT_SUCCESS
        ) {

            log(
                "Service discovery неуспешен."
            )

            return
        }

        if (
            bluetoothGatt !==
            gatt
        ) {

            log(
                "GATT объект уже изменился."
            )

            return
        }

        log(
            "STEP 6: перед gatt.services"
        )

        val services:
            List<BluetoothGattService>

        try {

            services =
                gatt.services

        } catch (e: Throwable) {

            logThrowable(
                "gatt.services",
                e
            )

            return
        }

        log(
            "STEP 7: gatt.services ВЕРНУЛСЯ"
        )

        log(
            "Количество сервисов: ${services.size}"
        )

        if (
            services.isEmpty()
        ) {

            log(
                "========================================"
            )

            log(
                "GATT SERVICES = 0"
            )

            log(
                "========================================"
            )

            return
        }

        dumpGattDatabase(
            services
        )
    }

    private fun dumpGattDatabase(
        services:
            List<BluetoothGattService>
    ) {

        log(
            "========================================"
        )

        log(
            "НАЙДЕННАЯ GATT-БАЗА"
        )

        log(
            "Сервисов: ${services.size}"
        )

        log(
            "========================================"
        )

        services.forEachIndexed {
                serviceIndex,
                service ->

            try {

                log(
                    ""
                )

                log(
                    "SERVICE #${serviceIndex + 1}"
                )

                log(
                    "UUID: ${service.uuid}"
                )

                log(
                    "Type: " +
                        serviceTypeToString(
                            service.type
                        )
                )

                val characteristics =
                    service.characteristics

                log(
                    "Characteristics: " +
                        characteristics.size
                )

                characteristics.forEachIndexed {
                        charIndex,
                        characteristic ->

                    try {

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

                        val descriptors =
                            characteristic.descriptors

                        log(
                            "  Descriptors: " +
                                descriptors.size
                        )

                        descriptors.forEachIndexed {
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

                    } catch (e: Throwable) {

                        logThrowable(
                            "CHARACTERISTIC " +
                                "#${charIndex + 1}",
                            e
                        )
                    }
                }

            } catch (e: Throwable) {

                logThrowable(
                    "SERVICE " +
                        "#${serviceIndex + 1}",
                    e
                )
            }
        }

        log(
            "========================================"
        )

        log(
            "GATT DATABASE COMPLETE"
        )

        log(
            "========================================"
        )
    }

    private fun propertiesToString(
        properties: Int
    ): String {

        val result =
            mutableListOf<String>()

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0
        ) {
            result += "BROADCAST"
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_READ != 0
        ) {
            result += "READ"
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            result += "WRITE_NO_RESPONSE"
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        ) {
            result += "WRITE"
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        ) {
            result += "NOTIFY"
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        ) {
            result += "INDICATE"
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0
        ) {
            result += "SIGNED_WRITE"
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0
        ) {
            result += "EXTENDED"
        }

        return if (
            result.isEmpty()
        ) {

            "NONE (0x" +
                properties.toString(
                    16
                ) +
                ")"

        } else {

            result.joinToString(
                " | "
            )
        }
    }

    private fun serviceTypeToString(
        type: Int
    ): String {

        return when (type) {

            BluetoothGattService
                .SERVICE_TYPE_PRIMARY ->
                "PRIMARY"

            BluetoothGattService
                .SERVICE_TYPE_SECONDARY ->
                "SECONDARY"

            else ->
                "UNKNOWN($type)"
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

    private fun notifyConnectionState(
        connected: Boolean,
        deviceName: String?
    ) {

        mainHandler.post {

            try {

                listener
                    ?.onConnectionStateChanged(
                        connected,
                        deviceName
                    )

            } catch (_: Throwable) {
            }
        }
    }

    private fun log(
        message: String
    ) {

        try {

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

        } catch (_: Throwable) {
        }

        scheduleUiUpdate()
    }

    private fun scheduleUiUpdate() {

        if (
            uiUpdateScheduled
        ) {
            return
        }

        uiUpdateScheduled =
            true

        mainHandler.postDelayed(
            uiUpdateRunnable,
            UI_UPDATE_DELAY_MS
        )
    }

    private fun logThrowable(
        location: String,
        error: Throwable
    ) {

        log(
            "!!! ОШИБКА В $location !!!"
        )

        log(
            "Тип: ${error.javaClass.name}"
        )

        log(
            "Сообщение: " +
                (
                    error.message
                        ?: "<нет>"
                    )
        )

        if (
            error.cause != null
        ) {

            log(
                "Cause: " +
                    error.cause
                        ?.javaClass
                        ?.name +
                    ": " +
                    (
                        error.cause
                            ?.message
                            ?: "<нет>"
                        )
            )
        }
    }
}
