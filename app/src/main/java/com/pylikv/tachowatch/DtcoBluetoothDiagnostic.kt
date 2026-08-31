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
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TachoWatch
 *
 * DTCO Bluetooth diagnostics.
 *
 * Версия GATT-SERVICES-6
 *
 * Ключевой эксперимент:
 *
 * Bluetooth callback НЕ запускает:
 * - Handler.postDelayed()
 * - discoverServices()
 * - requestMtu()
 * - gatt.services
 * - READ / WRITE / NOTIFY
 *
 * Callback только записывает состояние в переменные
 * и немедленно завершается.
 *
 * Вся дальнейшая BLE-логика выполняется
 * отдельным рабочим потоком.
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
            "GATT-SERVICES-6"

        private const val DISCOVERY_DELAY_MS =
            1000L

        private const val WORKER_INTERVAL_MS =
            200L

        private const val SERVICES_READ_DELAY_MS =
            300L
    }

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val workerThread =
        HandlerThread(
            "TachoWatch-BLE-Worker"
        ).apply {
            start()
        }

    private val workerHandler =
        Handler(
            workerThread.looper
        )

    private val logLines =
        CopyOnWriteArrayList<String>()

    @Volatile
    private var bluetoothGatt:
        BluetoothGatt? = null

    @Volatile
    private var callbackConnected =
        false

    @Volatile
    private var callbackDisconnected =
        false

    @Volatile
    private var connectionStatus =
        -1

    @Volatile
    private var connectionState =
        BluetoothProfile.STATE_DISCONNECTED

    @Volatile
    private var connectedAt =
        0L

    @Volatile
    private var connectionProcessed =
        false

    @Volatile
    private var discoveryRequested =
        false

    @Volatile
    private var discoveryCallReturned =
        false

    @Volatile
    private var discoveryCallResult =
        false

    @Volatile
    private var servicesCallbackReceived =
        false

    @Volatile
    private var servicesCallbackStatus =
        -1

    @Volatile
    private var servicesCallbackAt =
        0L

    @Volatile
    private var servicesProcessed =
        false

    @Volatile
    private var stopped =
        false

    init {

        workerHandler.post(
            workerLoop
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

            log(
                "ОШИБКА: отсутствует BLUETOOTH_CONNECT"
            )

            return
        }

        disconnectGattOnly()

        resetDiagnosticState()

        clearLog()

        stopped =
            false

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
            "Callback mode: STATE ONLY"
        )

        log(
            "MTU: ОТКЛЮЧЕН"
        )

        log(
            "BLE worker: ОТДЕЛЬНЫЙ ПОТОК"
        )

        log(
            "========================================"
        )

        val name =
            try {

                device.name
                    ?: "Без имени"

            } catch (
                _: Throwable
            ) {

                "Нет доступа"
            }

        val address =
            try {

                device.address

            } catch (
                _: Throwable
            ) {

                "Нет доступа"
            }

        val bond =
            try {

                bondStateToString(
                    device.bondState
                )

            } catch (
                _: Throwable
            ) {

                "Нет доступа"
            }

        val type =
            try {

                deviceTypeToString(
                    device.type
                )

            } catch (
                _: Throwable
            ) {

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
            "STEP 1: вызываю connectGatt()"
        )

        try {

            val gatt =
                device.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )

            bluetoothGatt =
                gatt

            log(
                "STEP 1 OK: connectGatt() вернулся"
            )

            log(
                "BluetoothGatt null: ${gatt == null}"
            )

        } catch (
            e: Throwable
        ) {

            logThrowable(
                "connectGatt",
                e
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {

        stopped =
            true

        disconnectGattOnly()

        resetDiagnosticState()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGattOnly() {

        val gatt =
            bluetoothGatt

        bluetoothGatt =
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

    private fun resetDiagnosticState() {

        callbackConnected =
            false

        callbackDisconnected =
            false

        connectionStatus =
            -1

        connectionState =
            BluetoothProfile.STATE_DISCONNECTED

        connectedAt =
            0L

        connectionProcessed =
            false

        discoveryRequested =
            false

        discoveryCallReturned =
            false

        discoveryCallResult =
            false

        servicesCallbackReceived =
            false

        servicesCallbackStatus =
            -1

        servicesCallbackAt =
            0L

        servicesProcessed =
            false
    }

    fun clearLog() {

        logLines.clear()

        updateUi()
    }

    fun getLog():
        String {

        return logLines.joinToString(
            "\n"
        )
    }

    /*
     * КРИТИЧЕСКОЕ ОТЛИЧИЕ VERSION 6:
     *
     * В Bluetooth callbacks НЕТ log(),
     * НЕТ Handler.post(),
     * НЕТ discoverServices().
     *
     * Только простое сохранение состояния.
     */
    private val gattCallback =
        object :
            BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                bluetoothGatt =
                    gatt

                connectionStatus =
                    status

                connectionState =
                    newState

                if (
                    newState ==
                    BluetoothProfile.STATE_CONNECTED
                ) {

                    callbackConnected =
                        true

                    connectedAt =
                        SystemClock.elapsedRealtime()

                } else if (
                    newState ==
                    BluetoothProfile.STATE_DISCONNECTED
                ) {

                    callbackDisconnected =
                        true
                }

                /*
                 * CALLBACK ЗАКАНЧИВАЕТСЯ ЗДЕСЬ.
                 */
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                bluetoothGatt =
                    gatt

                servicesCallbackStatus =
                    status

                servicesCallbackAt =
                    SystemClock.elapsedRealtime()

                servicesCallbackReceived =
                    true

                /*
                 * CALLBACK ЗАКАНЧИВАЕТСЯ ЗДЕСЬ.
                 */
            }
        }

    private val workerLoop =
        object :
            Runnable {

            override fun run() {

                try {

                    processBleState()

                } catch (
                    e: Throwable
                ) {

                    logThrowable(
                        "BLE WORKER LOOP",
                        e
                    )
                }

                workerHandler.postDelayed(
                    this,
                    WORKER_INTERVAL_MS
                )
            }
        }

    private fun processBleState() {

        if (
            stopped
        ) {

            return
        }

        processConnectionState()

        processServiceDiscoveryResult()
    }

    private fun processConnectionState() {

        if (
            callbackDisconnected
        ) {

            callbackDisconnected =
                false

            log(
                "----------------------------------------"
            )

            log(
                "WORKER: получен DISCONNECTED"
            )

            notifyConnectionState(
                false,
                null
            )

            return
        }

        if (
            !callbackConnected
        ) {

            return
        }

        val gatt =
            bluetoothGatt
                ?: return

        if (
            !connectionProcessed
        ) {

            connectionProcessed =
                true

            log(
                "----------------------------------------"
            )

            log(
                "STEP 2 OK: WORKER увидел CONNECTED"
            )

            log(
                "GATT status: $connectionStatus"
            )

            log(
                "GATT state: " +
                    connectionStateToString(
                        connectionState
                    )
            )

            log(
                "Bluetooth callback уже завершён."
            )

            log(
                "MTU НЕ запрашивается."
            )

            log(
                "Жду $DISCOVERY_DELAY_MS мс."
            )

            val name =
                try {

                    if (
                        hasConnectPermission()
                    ) {

                        gatt.device.name

                    } else {

                        null
                    }

                } catch (
                    _: Throwable
                ) {

                    null
                }

            notifyConnectionState(
                true,
                name
            )
        }

        if (
            discoveryRequested
        ) {

            return
        }

        val elapsed =
            SystemClock.elapsedRealtime() -
                connectedAt

        if (
            elapsed <
            DISCOVERY_DELAY_MS
        ) {

            return
        }

        discoveryRequested =
            true

        runDiscoverServices(
            gatt
        )
    }

    @SuppressLint("MissingPermission")
    private fun runDiscoverServices(
        gatt: BluetoothGatt
    ) {

        log(
            "----------------------------------------"
        )

        log(
            "STEP 3: WORKER перед discoverServices()"
        )

        log(
            "Текущий поток: " +
                Thread.currentThread().name
        )

        try {

            val result =
                gatt.discoverServices()

            discoveryCallResult =
                result

            discoveryCallReturned =
                true

            log(
                "STEP 4 OK: discoverServices() ВЕРНУЛСЯ"
            )

            log(
                "discoverServices result: $result"
            )

            if (
                !result
            ) {

                log(
                    "РЕЗУЛЬТАТ: Android вернул false."
                )
            }

        } catch (
            e: Throwable
        ) {

            discoveryCallReturned =
                true

            logThrowable(
                "discoverServices",
                e
            )
        }
    }

    private fun processServiceDiscoveryResult() {

        if (
            !servicesCallbackReceived
        ) {

            return
        }

        if (
            servicesProcessed
        ) {

            return
        }

        val elapsed =
            SystemClock.elapsedRealtime() -
                servicesCallbackAt

        if (
            elapsed <
            SERVICES_READ_DELAY_MS
        ) {

            return
        }

        servicesProcessed =
            true

        val gatt =
            bluetoothGatt

        log(
            "----------------------------------------"
        )

        log(
            "STEP 5: WORKER увидел onServicesDiscovered"
        )

        log(
            "Callback уже завершён."
        )

        log(
            "Discovery callback status: " +
                servicesCallbackStatus
        )

        log(
            "discoverServices call returned: " +
                discoveryCallReturned
        )

        log(
            "discoverServices result: " +
                discoveryCallResult
        )

        if (
            servicesCallbackStatus !=
            BluetoothGatt.GATT_SUCCESS
        ) {

            log(
                "Service discovery завершён с ошибкой."
            )

            return
        }

        if (
            gatt == null
        ) {

            log(
                "ОШИБКА: BluetoothGatt == null"
            )

            return
        }

        inspectGattServices(
            gatt
        )
    }

    private fun inspectGattServices(
        gatt: BluetoothGatt
    ) {

        log(
            "----------------------------------------"
        )

        log(
            "STEP 6: перед gatt.services"
        )

        log(
            "Текущий поток: " +
                Thread.currentThread().name
        )

        val services:
            List<BluetoothGattService>

        try {

            services =
                gatt.services

        } catch (
            e: Throwable
        ) {

            logThrowable(
                "gatt.services",
                e
            )

            return
        }

        log(
            "STEP 7 OK: gatt.services ВЕРНУЛСЯ"
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
                "РЕЗУЛЬТАТ: GATT SERVICES = 0"
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

            dumpService(
                serviceIndex,
                service
            )
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

    private fun dumpService(
        serviceIndex: Int,
        service:
            BluetoothGattService
    ) {

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
                    characteristicIndex,
                    characteristic ->

                dumpCharacteristic(
                    characteristicIndex,
                    characteristic
                )
            }

        } catch (
            e: Throwable
        ) {

            logThrowable(
                "SERVICE #${serviceIndex + 1}",
                e
            )
        }
    }

    private fun dumpCharacteristic(
        characteristicIndex: Int,
        characteristic:
            BluetoothGattCharacteristic
    ) {

        try {

            log(
                "  CHARACTERISTIC " +
                    "#${characteristicIndex + 1}"
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

        } catch (
            e: Throwable
        ) {

            logThrowable(
                "CHARACTERISTIC " +
                    "#${characteristicIndex + 1}",
                e
            )
        }
    }

    private fun propertiesToString(
        properties: Int
    ): String {

        val result =
            mutableListOf<String>()

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_BROADCAST != 0
        ) {

            result +=
                "BROADCAST"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_READ != 0
        ) {

            result +=
                "READ"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_WRITE_NO_RESPONSE != 0
        ) {

            result +=
                "WRITE_NO_RESPONSE"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_WRITE != 0
        ) {

            result +=
                "WRITE"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_NOTIFY != 0
        ) {

            result +=
                "NOTIFY"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_INDICATE != 0
        ) {

            result +=
                "INDICATE"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_SIGNED_WRITE != 0
        ) {

            result +=
                "SIGNED_WRITE"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_EXTENDED_PROPS != 0
        ) {

            result +=
                "EXTENDED"
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

        return when (
            type
        ) {

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

            BluetoothDevice
                .DEVICE_TYPE_CLASSIC ->

                "CLASSIC"

            BluetoothDevice
                .DEVICE_TYPE_LE ->

                "LE"

            BluetoothDevice
                .DEVICE_TYPE_DUAL ->

                "DUAL"

            BluetoothDevice
                .DEVICE_TYPE_UNKNOWN ->

                "UNKNOWN"

            else ->

                "UNKNOWN($type)"
        }
    }

    private fun connectionStateToString(
        state: Int
    ): String {

        return when (
            state
        ) {

            BluetoothProfile
                .STATE_DISCONNECTED ->

                "DISCONNECTED"

            BluetoothProfile
                .STATE_CONNECTING ->

                "CONNECTING"

            BluetoothProfile
                .STATE_CONNECTED ->

                "CONNECTED"

            BluetoothProfile
                .STATE_DISCONNECTING ->

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

            } catch (
                _: Throwable
            ) {
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

        } catch (
            _: Throwable
        ) {
        }

        updateUi()
    }

    private fun updateUi() {

        mainHandler.post {

            try {

                listener
                    ?.onLogChanged(
                        getLog()
                    )

            } catch (
                _: Throwable
            ) {
            }
        }
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

        val cause =
            error.cause

        if (
            cause != null
        ) {

            log(
                "Cause: " +
                    cause.javaClass.name +
                    ": " +
                    (
                        cause.message
                            ?: "<нет>"
                        )
            )
        }
    }
}
