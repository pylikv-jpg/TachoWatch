package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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
 * DTCO Bluetooth diagnostic
 *
 * Версия: BLE-CONNECT-ONLY-1
 *
 * Цель теста:
 *
 * 1. Использовать BLE GATT — как в предыдущих тестах,
 *    где DTCO запрашивал подтверждение подключения.
 *
 * 2. Проверить ТОЛЬКО установление BLE-соединения.
 *
 * 3. НЕ запускать discoverServices().
 *
 * 4. НЕ читать характеристики.
 *
 * 5. НЕ писать никаких данных в тахограф.
 *
 * 6. После CONNECTED удерживать соединение
 *    и каждые несколько секунд фиксировать,
 *    остаётся ли оно активным.
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
            "BLE-CONNECT-ONLY-1"

        private const val UI_REFRESH_MS =
            400L

        private const val HEARTBEAT_MS =
            3000L

        private const val MAX_LOG_LINES =
            1200
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
            "Версия: $VERSION"
        )

        appendLog(
            "Режим: BLE GATT"
        )

        appendLog(
            "discoverServices(): ОТКЛЮЧЕН"
        )

        appendLog(
            "Чтение характеристик: ОТКЛЮЧЕНО"
        )

        appendLog(
            "Запись в DTCO: ОТКЛЮЧЕНА"
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
            "STEP 1: создаём BLE GATT соединение"
        )

        appendLog(
            "Ожидаем callback onConnectionStateChange()"
        )

        appendLog(
            "Если DTCO покажет запрос подтверждения — подтвердить."
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
                    "Ждём фактического CONNECTED..."
                )
            }

        } catch (
            e: Throwable
        ) {

            appendThrowable(
                "connectGatt",
                e
            )

            connected =
                false

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
                            "ВАЖНО: discoverServices() НЕ запускаем."
                        )

                        appendLog(
                            "Никакие данные DTCO не читаем."
                        )

                        appendLog(
                            "Никакие данные DTCO не отправляем."
                        )

                        appendLog(
                            "Теперь просто удерживаем соединение."
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
                                "До отключения соединение держалось: ${duration} сек."
                            )

                        } else {

                            appendLog(
                                "CONNECTED до этого не был получен."
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

        val bond =

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
            "Bond state: $bond"
        )

        val type =

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
            "Тип Bluetooth: $type"
        )

        appendLog(
            "Android API: ${Build.VERSION.SDK_INT}"
        )

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "Сохранённые UUID Android:"
        )

        try {

            val uuids =
                device.uuids

            if (
                uuids.isNullOrEmpty()
            ) {

                appendLog(
                    "UUID: список пуст / null"
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
                "Чтение UUID",
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
            "Ручное отключение диагностики."
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
                "UNKNOWN"
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

            8 ->
                "GATT_CONN_TIMEOUT / status 8"

            19 ->
                "GATT_CONN_TERMINATE_PEER_USER / status 19"

            22 ->
                "GATT_CONN_TERMINATE_LOCAL_HOST / status 22"

            62 ->
                "GATT_CONN_FAIL_ESTABLISH / status 62"

            133 ->
                "GATT_ERROR / status 133"

            257 ->
                "GATT_FAILURE / status 257"

            else ->
                "status $status"
        }
    }
}
