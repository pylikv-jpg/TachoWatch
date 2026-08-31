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
 * TachoWatch — диагностический BLE-модуль DTCO.
 *
 * Версия GATT-SERVICES-3.
 *
 * Цель этой версии:
 * 1. Подключиться к DTCO.
 * 2. Получить MTU.
 * 3. Выполнить discoverServices().
 * 4. Надёжно получить gatt.services.
 * 5. Вывести все SERVICE / CHARACTERISTIC / DESCRIPTOR.
 *
 * ВАЖНО:
 * В этой версии специально НЕ выполняются READ, WRITE,
 * NOTIFY или INDICATE.
 *
 * Сначала необходимо надёжно получить карту BLE/GATT
 * конкретного DTCO.
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
            "GATT-SERVICES-3"

        private const val MAX_DISCOVERY_RETRIES =
            1

        private const val DISCOVERY_RETRY_DELAY_MS =
            1500L

        private const val UI_UPDATE_DELAY_MS =
            50L
    }

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private var bluetoothGatt:
        BluetoothGatt? = null

    private val logLines =
        CopyOnWriteArrayList<String>()

    private var discoveryRetryCount =
        0

    private var uiUpdateScheduled =
        false

    private val uiUpdateRunnable =
        Runnable {

            uiUpdateScheduled =
                false

            try {

                listener
                    ?.onLogChanged(
                        getLog()
                    )

            } catch (
                _: Throwable
            ) {
                /*
                 * Ошибка интерфейса не должна
                 * останавливать Bluetooth callback.
                 */
            }
        }

    fun hasConnectPermission():
        Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            ContextCompat
                .checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) ==
                PackageManager.PERMISSION_GRANTED

        } else {

            true
        }
    }

    @SuppressLint(
        "MissingPermission"
    )
    fun connect(
        device: BluetoothDevice
    ) {

        if (
            !hasConnectPermission()
        ) {

            log(
                "ОШИБКА: отсутствует " +
                    "BLUETOOTH_CONNECT"
            )

            return
        }

        disconnect()

        clearLog()

        discoveryRetryCount =
            0

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
            "========================================"
        )

        val safeName =
            try {

                device.name
                    ?: "Без имени"

            } catch (
                _: Throwable
            ) {

                "Нет доступа"
            }

        val safeAddress =
            try {

                device.address

            } catch (
                _: Throwable
            ) {

                "Нет доступа"
            }

        val safeBondState =
            try {

                bondStateToString(
                    device.bondState
                )

            } catch (
                _: Throwable
            ) {

                "Нет доступа"
            }

        val safeDeviceType =
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
            "Устройство: $safeName"
        )

        log(
            "Адрес: $safeAddress"
        )

        log(
            "Bond state: $safeBondState"
        )

        log(
            "Тип устройства: $safeDeviceType"
        )

        log(
            "Android API: " +
                Build.VERSION.SDK_INT
        )

        log(
            "----------------------------------------"
        )

        log(
            "Начинаю GATT-подключение..."
        )

        try {

            bluetoothGatt =
                device.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )

            if (
                bluetoothGatt == null
            ) {

                log(
                    "ОШИБКА: connectGatt " +
                        "вернул null"
                )
            }

        } catch (
            e: Throwable
        ) {

            logThrowable(
                "connectGatt",
                e
            )
        }
    }

    @SuppressLint(
        "MissingPermission"
    )
    fun disconnect() {

        mainHandler
            .removeCallbacks(
                uiUpdateRunnable
            )

        uiUpdateScheduled =
            false

        try {

            bluetoothGatt
                ?.disconnect()

        } catch (
            _: Throwable
        ) {
        }

        try {

            bluetoothGatt
                ?.close()

        } catch (
            _: Throwable
        ) {
        }

        bluetoothGatt =
            null

        discoveryRetryCount =
            0
    }

    fun clearLog() {

        logLines.clear()

        scheduleUiUpdate()
    }

    fun getLog():
        String {

        return logLines
            .joinToString(
                "\n"
            )
    }

    private val gattCallback =
        object :
            BluetoothGattCallback() {

            @SuppressLint(
                "MissingPermission"
            )
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                try {

                    log(
                        "GATT state: " +
                            "status=$status, " +
                            "state=" +
                            connectionStateToString(
                                newState
                            )
                    )

                    when (
                        newState
                    ) {

                        BluetoothProfile
                            .STATE_CONNECTED -> {

                            val deviceName =
                                try {

                                    gatt
                                        .device
                                        .name

                                } catch (
                                    _: Throwable
                                ) {

                                    null
                                }

                            log(
                                "Bluetooth GATT подключён."
                            )

                            notifyConnectionState(
                                true,
                                deviceName
                            )

                            requestMtuOrDiscover(
                                gatt
                            )
                        }

                        BluetoothProfile
                            .STATE_DISCONNECTED -> {

                            log(
                                "Bluetooth GATT отключён."
                            )

                            val deviceName =
                                try {

                                    gatt
                                        .device
                                        .name

                                } catch (
                                    _: Throwable
                                ) {

                                    null
                                }

                            notifyConnectionState(
                                false,
                                deviceName
                            )

                            try {

                                gatt.close()

                            } catch (
                                _: Throwable
                            ) {
                            }

                            if (
                                bluetoothGatt ===
                                gatt
                            ) {

                                bluetoothGatt =
                                    null
                            }
                        }
                    }

                } catch (
                    e: Throwable
                ) {

                    logThrowable(
                        "onConnectionStateChange",
                        e
                    )
                }
            }

            @SuppressLint(
                "MissingPermission"
            )
            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {

                try {

                    log(
                        "MTU changed: " +
                            "mtu=$mtu, " +
                            "status=$status"
                    )

                    startServiceDiscovery(
                        gatt
                    )

                } catch (
                    e: Throwable
                ) {

                    logThrowable(
                        "onMtuChanged",
                        e
                    )
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                /*
                 * Весь callback целиком защищён.
                 * Даже ошибка Android API или интерфейса
                 * должна попасть в журнал.
                 */
                try {

                    log(
                        "----------------------------------------"
                    )

                    log(
                        "CALLBACK onServicesDiscovered ENTER"
                    )

                    log(
                        "Service discovery завершён."
                    )

                    log(
                        "Status: $status"
                    )

                    if (
                        status !=
                        BluetoothGatt.GATT_SUCCESS
                    ) {

                        log(
                            "ОШИБКА: discovery " +
                                "status=$status"
                        )

                        return
                    }

                    log(
                        "Шаг 1: callback продолжает работу."
                    )

                    log(
                        "Шаг 2: запрашиваю gatt.services..."
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
                        "Шаг 3: gatt.services получен."
                    )

                    log(
                        "Количество сервисов Android: " +
                            services.size
                    )

                    if (
                        services.isEmpty()
                    ) {

                        handleEmptyServices(
                            gatt
                        )

                        return
                    }

                    discoveryRetryCount =
                        0

                    log(
                        "Шаг 4: начинаю разбор GATT."
                    )

                    dumpGattDatabase(
                        services
                    )

                    log(
                        "----------------------------------------"
                    )

                    log(
                        "GATT SERVICE DISCOVERY ГОТОВ"
                    )

                    log(
                        "Всего сервисов: " +
                            services.size
                    )

                    log(
                        "В этой версии READ/WRITE/NOTIFY " +
                            "не выполняются."
                    )

                    log(
                        "========================================"
                    )

                } catch (
                    e: Throwable
                ) {

                    logThrowable(
                        "onServicesDiscovered GLOBAL",
                        e
                    )
                }
            }
        }

    @SuppressLint(
        "MissingPermission"
    )
    private fun requestMtuOrDiscover(
        gatt: BluetoothGatt
    ) {

        try {

            val started =
                gatt.requestMtu(
                    517
                )

            log(
                "Запрос MTU отправлен: " +
                    started
            )

            if (
                !started
            ) {

                log(
                    "requestMtu=false. " +
                        "Перехожу к discoverServices()."
                )

                startServiceDiscovery(
                    gatt
                )
            }

        } catch (
            e: Throwable
        ) {

            logThrowable(
                "requestMtu",
                e
            )

            startServiceDiscovery(
                gatt
            )
        }
    }

    @SuppressLint(
        "MissingPermission"
    )
    private fun startServiceDiscovery(
        gatt: BluetoothGatt
    ) {

        try {

            log(
                "----------------------------------------"
            )

            log(
                "Запускаю discoverServices()..."
            )

            val started =
                gatt.discoverServices()

            log(
                "discoverServices started: " +
                    started
            )

            if (
                !started
            ) {

                log(
                    "ОШИБКА: discoverServices() " +
                        "вернул false"
                )
            }

        } catch (
            e: Throwable
        ) {

            logThrowable(
                "discoverServices",
                e
            )
        }
    }

    private fun handleEmptyServices(
        gatt: BluetoothGatt
    ) {

        log(
            "========================================"
        )

        log(
            "ANDROID ВЕРНУЛ 0 GATT-СЕРВИСОВ"
        )

        log(
            "========================================"
        )

        if (
            discoveryRetryCount <
            MAX_DISCOVERY_RETRIES
        ) {

            discoveryRetryCount++

            log(
                "Выполняю повтор discovery " +
                    "$discoveryRetryCount/" +
                    "$MAX_DISCOVERY_RETRIES"
            )

            log(
                "Задержка: " +
                    "$DISCOVERY_RETRY_DELAY_MS мс"
            )

            mainHandler
                .postDelayed(
                    {

                        try {

                            if (
                                bluetoothGatt ===
                                gatt
                            ) {

                                startServiceDiscovery(
                                    gatt
                                )

                            } else {

                                log(
                                    "Повтор отменён: " +
                                        "GATT уже изменился."
                                )
                            }

                        } catch (
                            e: Throwable
                        ) {

                            logThrowable(
                                "discovery retry",
                                e
                            )
                        }
                    },
                    DISCOVERY_RETRY_DELAY_MS
                )

        } else {

            log(
                "Повтор discovery уже выполнен."
            )

            log(
                "Сервисов по-прежнему 0."
            )

            log(
                "Это важный результат диагностики:"
            )

            log(
                "BLE CONNECTED и status=0 есть,"
            )

            log(
                "но Android не предоставляет " +
                    "GATT services."
            )
        }
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
            "Количество сервисов: " +
                services.size
        )

        log(
            "========================================"
        )

        services
            .forEachIndexed {
                    serviceIndex,
                    service ->

                try {

                    dumpService(
                        serviceIndex,
                        service
                    )

                } catch (
                    e: Throwable
                ) {

                    logThrowable(
                        "SERVICE #" +
                            "${serviceIndex + 1}",
                        e
                    )
                }
            }

        log(
            "========================================"
        )

        log(
            "КОНЕЦ GATT-БАЗЫ"
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

        log(
            ""
        )

        log(
            "SERVICE #" +
                "${serviceIndex + 1}"
        )

        log(
            "UUID: " +
                service.uuid
        )

        log(
            "Type: " +
                serviceTypeToString(
                    service.type
                )
        )

        val characteristics =
            try {

                service.characteristics

            } catch (
                e: Throwable
            ) {

                logThrowable(
                    "service.characteristics",
                    e
                )

                return
            }

        log(
            "Characteristics: " +
                characteristics.size
        )

        characteristics
            .forEachIndexed {
                    characteristicIndex,
                    characteristic ->

                try {

                    dumpCharacteristic(
                        characteristicIndex,
                        characteristic
                    )

                } catch (
                    e: Throwable
                ) {

                    logThrowable(
                        "CHARACTERISTIC #" +
                            "${characteristicIndex + 1}",
                        e
                    )
                }
            }
    }

    private fun dumpCharacteristic(
        characteristicIndex: Int,
        characteristic:
            BluetoothGattCharacteristic
    ) {

        log(
            "  CHARACTERISTIC #" +
                "${characteristicIndex + 1}"
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
            try {

                characteristic.descriptors

            } catch (
                e: Throwable
            ) {

                logThrowable(
                    "characteristic.descriptors",
                    e
                )

                return
            }

        log(
            "  Descriptors: " +
                descriptors.size
        )

        descriptors
            .forEachIndexed {
                    descriptorIndex,
                    descriptor ->

                try {

                    log(
                        "    DESCRIPTOR #" +
                            "${descriptorIndex + 1}"
                    )

                    log(
                        "    UUID: " +
                            descriptor.uuid
                    )

                    log(
                        "    Permissions: " +
                            descriptor.permissions
                    )

                } catch (
                    e: Throwable
                ) {

                    logThrowable(
                        "DESCRIPTOR #" +
                            "${descriptorIndex + 1}",
                        e
                    )
                }
            }
    }

    private fun propertiesToString(
        properties: Int
    ): String {

        val values =
            mutableListOf<String>()

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_BROADCAST != 0
        ) {

            values +=
                "BROADCAST"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_READ != 0
        ) {

            values +=
                "READ"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_WRITE_NO_RESPONSE != 0
        ) {

            values +=
                "WRITE_NO_RESPONSE"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_WRITE != 0
        ) {

            values +=
                "WRITE"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_NOTIFY != 0
        ) {

            values +=
                "NOTIFY"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_INDICATE != 0
        ) {

            values +=
                "INDICATE"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_SIGNED_WRITE != 0
        ) {

            values +=
                "SIGNED_WRITE"
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_EXTENDED_PROPS != 0
        ) {

            values +=
                "EXTENDED"
        }

        return if (
            values.isEmpty()
        ) {

            "NONE " +
                "(0x" +
                properties
                    .toString(
                        16
                    ) +
                ")"

        } else {

            values.joinToString(
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

            BluetoothDevice
                .BOND_NONE ->

                "BOND_NONE"

            BluetoothDevice
                .BOND_BONDING ->

                "BOND_BONDING"

            BluetoothDevice
                .BOND_BONDED ->

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

    /**
     * Ключевое отличие GATT-SERVICES-3:
     *
     * log() НЕ вызывает UI напрямую.
     * Он только записывает строку в память.
     *
     * Обновление интерфейса выполняется
     * отдельно на главном потоке Android.
     */
    private fun log(
        message: String
    ) {

        try {

            val timestamp =
                SimpleDateFormat(
                    "HH:mm:ss.SSS",
                    Locale.getDefault()
                )
                    .format(
                        Date()
                    )

            logLines.add(
                "[$timestamp] $message"
            )

        } catch (
            _: Throwable
        ) {

            /*
             * Даже проблема форматирования
             * не должна остановить BLE callback.
             */
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

        mainHandler
            .postDelayed(
                uiUpdateRunnable,
                UI_UPDATE_DELAY_MS
            )
    }

    private fun logThrowable(
        location: String,
        error: Throwable
    ) {

        log(
            "!!! ИСКЛЮЧЕНИЕ В $location !!!"
        )

        log(
            "Тип: " +
                error
                    .javaClass
                    .name
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
                    cause
                        .javaClass
                        .name +
                    ": " +
                    (
                        cause.message
                            ?: "<нет>"
                        )
            )
        }
    }
}
