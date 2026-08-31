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
 * TachoWatch
 *
 * Диагностика DTCO Bluetooth.
 *
 * Версия GATT-SERVICES-4.
 *
 * Главное отличие:
 * onServicesDiscovered() НЕ читает gatt.services.
 *
 * Callback только фиксирует факт завершения discovery,
 * после чего чтение GATT-базы запускается отдельно
 * через Handler с небольшой задержкой.
 *
 * В этой версии:
 * - нет WRITE;
 * - нет READ характеристик;
 * - нет NOTIFY/INDICATE;
 * - никаких команд DTCO не отправляется.
 *
 * Задача только одна:
 * надёжно получить карту GATT сервисов.
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
            "GATT-SERVICES-4"

        private const val GATT_READ_DELAY_MS =
            500L

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

    private var pendingGattRead =
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
                "ОШИБКА: отсутствует BLUETOOTH_CONNECT"
            )

            return
        }

        disconnect()

        clearLog()

        pendingGattRead =
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
            "========================================"
        )

        val deviceName =
            try {

                device.name
                    ?: "Без имени"

            } catch (
                _: Throwable
            ) {

                "Нет доступа"
            }

        val deviceAddress =
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
            "Устройство: $deviceName"
        )

        log(
            "Адрес: $deviceAddress"
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
            "TEST 1: начинаю connectGatt()"
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
                    "ОШИБКА: connectGatt вернул null"
                )

            } else {

                log(
                    "TEST 1 OK: connectGatt объект создан"
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
            .removeCallbacksAndMessages(
                null
            )

        uiUpdateScheduled =
            false

        pendingGattRead =
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
                        "GATT state: status=$status, " +
                            "state=" +
                            connectionStateToString(
                                newState
                            )
                    )

                    if (
                        newState ==
                        BluetoothProfile.STATE_CONNECTED
                    ) {

                        log(
                            "TEST 2 OK: Bluetooth GATT CONNECTED"
                        )

                        val name =
                            try {

                                gatt.device.name

                            } catch (
                                _: Throwable
                            ) {

                                null
                            }

                        notifyConnectionState(
                            true,
                            name
                        )

                        requestMtu(
                            gatt
                        )

                    } else if (
                        newState ==
                        BluetoothProfile.STATE_DISCONNECTED
                    ) {

                        log(
                            "Bluetooth GATT отключён."
                        )

                        val name =
                            try {

                                gatt.device.name

                            } catch (
                                _: Throwable
                            ) {

                                null
                            }

                        notifyConnectionState(
                            false,
                            name
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
                        "MTU changed: mtu=$mtu, status=$status"
                    )

                    log(
                        "TEST 3: запускаю Service Discovery"
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
                 * КРИТИЧЕСКОЕ ИЗМЕНЕНИЕ.
                 *
                 * Здесь НЕ читаем:
                 *
                 * gatt.services
                 *
                 * И вообще не разбираем GATT.
                 */

                try {

                    log(
                        "CALLBACK onServicesDiscovered ENTER"
                    )

                    /*
                     * Сначала сохраняем status
                     * в обычную переменную.
                     */
                    val discoveryStatus =
                        status

                    /*
                     * А теперь весь дальнейший анализ
                     * выполняем отдельно.
                     */
                    scheduleGattRead(
                        gatt,
                        discoveryStatus
                    )

                } catch (
                    e: Throwable
                ) {

                    logThrowable(
                        "onServicesDiscovered",
                        e
                    )
                }
            }
        }

    @SuppressLint(
        "MissingPermission"
    )
    private fun requestMtu(
        gatt: BluetoothGatt
    ) {

        try {

            log(
                "TEST 3A: requestMtu(517)"
            )

            val started =
                gatt.requestMtu(
                    517
                )

            log(
                "requestMtu started: $started"
            )

            if (
                !started
            ) {

                log(
                    "requestMtu=false"
                )

                log(
                    "Запускаю discovery без изменения MTU."
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
                "discoverServices()..."
            )

            val started =
                gatt.discoverServices()

            log(
                "discoverServices started: $started"
            )

            if (
                !started
            ) {

                log(
                    "ОШИБКА: discoverServices вернул false"
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

    /**
     * Чтение GATT запускается НЕ из Bluetooth callback.
     */
    private fun scheduleGattRead(
        gatt: BluetoothGatt,
        discoveryStatus: Int
    ) {

        if (
            pendingGattRead
        ) {

            log(
                "GATT read уже запланирован."
            )

            return
        }

        pendingGattRead =
            true

        /*
         * Этот вызов выполняется уже через Handler,
         * после завершения onServicesDiscovered().
         */
        mainHandler.postDelayed(
            {

                pendingGattRead =
                    false

                runGattReadOutsideCallback(
                    gatt,
                    discoveryStatus
                )

            },
            GATT_READ_DELAY_MS
        )
    }

    /**
     * Вот здесь теперь происходит вся диагностика.
     *
     * Это уже НЕ onServicesDiscovered().
     */
    private fun runGattReadOutsideCallback(
        gatt: BluetoothGatt,
        discoveryStatus: Int
    ) {

        try {

            log(
                "----------------------------------------"
            )

            log(
                "TEST 4: работа ВНЕ Bluetooth callback"
            )

            log(
                "Discovery status сохранён: " +
                    discoveryStatus
            )

            if (
                discoveryStatus !=
                BluetoothGatt.GATT_SUCCESS
            ) {

                log(
                    "ОШИБКА: discoveryStatus=" +
                        discoveryStatus
                )

                return
            }

            if (
                bluetoothGatt !==
                gatt
            ) {

                log(
                    "ОШИБКА: BluetoothGatt уже изменился."
                )

                return
            }

            log(
                "TEST 5: перед обращением к gatt.services"
            )

            /*
             * ВОТ КЛЮЧЕВОЙ ТЕСТ.
             */
            val services:
                List<BluetoothGattService>

            try {

                services =
                    gatt.services

            } catch (
                e: Throwable
            ) {

                logThrowable(
                    "gatt.services OUTSIDE CALLBACK",
                    e
                )

                return
            }

            log(
                "TEST 6 OK: gatt.services вернулся"
            )

            log(
                "Количество сервисов Android: " +
                    services.size
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

                log(
                    "Bluetooth соединение установлено."
                )

                log(
                    "Service Discovery возвращает status=0."
                )

                log(
                    "Но Android отдаёт пустой список сервисов."
                )

                return
            }

            log(
                "TEST 7: начинаю разбор GATT-базы"
            )

            dumpGattDatabase(
                services
            )

            log(
                "========================================"
            )

            log(
                "GATT DIAGNOSTIC COMPLETE"
            )

            log(
                "Всего сервисов: ${services.size}"
            )

            log(
                "========================================"
            )

        } catch (
            e: Throwable
        ) {

            logThrowable(
                "runGattReadOutsideCallback GLOBAL",
                e
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
            "========================================"
        )

        services
            .forEachIndexed {
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

        try {

            log(
                ""
            )

            log(
                "SERVICE #" +
                    "${serviceIndex + 1}"
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

            characteristics
                .forEachIndexed {
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
                "SERVICE #" +
                    "${serviceIndex + 1}",
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
                characteristic.descriptors

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

            "NONE " +
                "(0x" +
                properties
                    .toString(
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
            "!!! ОШИБКА В $location !!!"
        )

        log(
            "Тип: " +
                error.javaClass.name
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
