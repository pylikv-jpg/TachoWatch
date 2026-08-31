package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * TachoWatch
 *
 * DTCO Bluetooth diagnostic
 *
 * Версия RFCOMM-SPP-1
 *
 * Цель этого теста:
 *
 * 1. НЕ использовать BLE GATT.
 * 2. Проверить Classic Bluetooth / RFCOMM.
 * 3. Проверить стандартный Serial Port Profile.
 * 4. НИЧЕГО не отправлять тахографу.
 * 5. Только установить соединение и посмотреть,
 *    принимает ли DTCO RFCOMM/SPP.
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
            "RFCOMM-SPP-1"

        private const val UI_REFRESH_MS =
            400L

        private const val CONNECT_TIMEOUT_MS =
            8000L

        /*
         * Стандартный UUID Bluetooth
         * Serial Port Profile (SPP).
         */
        private val SPP_UUID: UUID =
            UUID.fromString(
                "00001101-0000-1000-8000-00805F9B34FB"
            )
    }

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val workerThread =
        HandlerThread(
            "TachoWatch-RFCOMM-Worker"
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
    private var stopped =
        true

    @Volatile
    private var currentSocket:
        BluetoothSocket? = null

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

            return
        }

        disconnectSocket()

        logLines.clear()

        stopped =
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
            "Режим: CLASSIC BLUETOOTH / RFCOMM"
        )

        appendLog(
            "BLE GATT: НЕ используется"
        )

        appendLog(
            "Запись данных в DTCO: ОТКЛЮЧЕНА"
        )

        appendLog(
            "Команды DTCO: НЕ отправляются"
        )

        appendLog(
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

        val bondState =
            try {

                bondStateToString(
                    device.bondState
                )

            } catch (
                _: Throwable
            ) {

                "Нет доступа"
            }

        val deviceType =
            try {

                deviceTypeToString(
                    device.type
                )

            } catch (
                _: Throwable
            ) {

                "Нет доступа"
            }

        appendLog(
            "Устройство: $name"
        )

        appendLog(
            "Адрес: $address"
        )

        appendLog(
            "Bond state: $bondState"
        )

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
            "STEP 1: проверяем UUID устройства"
        )

        logCachedUuids(
            device
        )

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "STEP 2: запускаем RFCOMM/SPP тест"
        )

        workerHandler.post {

            runRfcommDiagnostic(
                device
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun runRfcommDiagnostic(
        device: BluetoothDevice
    ) {

        if (
            stopped
        ) {

            return
        }

        appendLog(
            "Рабочий поток: ${Thread.currentThread().name}"
        )

        appendLog(
            "SPP UUID:"
        )

        appendLog(
            SPP_UUID.toString()
        )

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "TEST A: Secure RFCOMM / SPP"
        )

        val secureResult =
            trySocketConnection(
                device = device,
                secure = true
            )

        if (
            secureResult
        ) {

            appendLog(
                "========================================"
            )

            appendLog(
                "РЕЗУЛЬТАТ:"
            )

            appendLog(
                "SECURE RFCOMM/SPP ПОДКЛЮЧЕНИЕ УСПЕШНО"
            )

            appendLog(
                "Это сильный признак, что DTCO имеет"
            )

            appendLog(
                "Classic Bluetooth serial channel."
            )

            appendLog(
                "Никакие команды тахографу не отправлялись."
            )

            appendLog(
                "========================================"
            )

            return
        }

        if (
            stopped
        ) {

            return
        }

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "TEST B: Insecure RFCOMM / SPP"
        )

        val insecureResult =
            trySocketConnection(
                device = device,
                secure = false
            )

        if (
            insecureResult
        ) {

            appendLog(
                "========================================"
            )

            appendLog(
                "РЕЗУЛЬТАТ:"
            )

            appendLog(
                "INSECURE RFCOMM/SPP ПОДКЛЮЧЕНИЕ УСПЕШНО"
            )

            appendLog(
                "DTCO принимает Classic Bluetooth RFCOMM."
            )

            appendLog(
                "Никакие команды тахографу не отправлялись."
            )

            appendLog(
                "========================================"
            )

            return
        }

        appendLog(
            "========================================"
        )

        appendLog(
            "РЕЗУЛЬТАТ:"
        )

        appendLog(
            "Стандартный RFCOMM/SPP канал не открылся."
        )

        appendLog(
            "Secure: FAIL"
        )

        appendLog(
            "Insecure: FAIL"
        )

        appendLog(
            "Это НЕ означает, что Bluetooth DTCO"
        )

        appendLog(
            "не работает."
        )

        appendLog(
            "Следующим тестом потребуется определить"
        )

        appendLog(
            "конкретный профиль/UUID, который использует"
        )

        appendLog(
            "VDO Fleet."
        )

        appendLog(
            "========================================"
        )

        notifyConnectionState(
            false,
            safeDeviceName(
                device
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun trySocketConnection(
        device: BluetoothDevice,
        secure: Boolean
    ): Boolean {

        if (
            stopped
        ) {

            return false
        }

        var socket:
            BluetoothSocket? = null

        try {

            appendLog(
                if (secure) {
                    "Создание secure RFCOMM socket..."
                } else {
                    "Создание insecure RFCOMM socket..."
                }
            )

            socket =
                if (
                    secure
                ) {

                    device
                        .createRfcommSocketToServiceRecord(
                            SPP_UUID
                        )

                } else {

                    device
                        .createInsecureRfcommSocketToServiceRecord(
                            SPP_UUID
                        )
                }

            currentSocket =
                socket

            appendLog(
                "Socket создан."
            )

            appendLog(
                "Запускаю connect()."
            )

            appendLog(
                "Timeout теста: ${CONNECT_TIMEOUT_MS / 1000} сек."
            )

            val result =
                connectSocketWithTimeout(
                    socket
                )

            if (
                !result
            ) {

                appendLog(
                    "Результат connect(): FAIL"
                )

                closeSocket(
                    socket
                )

                currentSocket =
                    null

                return false
            }

            if (
                !socket.isConnected
            ) {

                appendLog(
                    "connect() завершился, но socket.isConnected = false"
                )

                closeSocket(
                    socket
                )

                currentSocket =
                    null

                return false
            }

            appendLog(
                "----------------------------------------"
            )

            appendLog(
                "RFCOMM SOCKET CONNECTED"
            )

            appendLog(
                "socket.isConnected = true"
            )

            appendLog(
                "Remote device: ${safeDeviceName(device)}"
            )

            notifyConnectionState(
                true,
                safeDeviceName(
                    device
                )
            )

            /*
             * ВАЖНО:
             * Мы намеренно ничего не пишем
             * в OutputStream.
             *
             * Только смотрим, есть ли уже
             * какие-либо входящие байты.
             */
            try {

                val available =
                    socket.inputStream.available()

                appendLog(
                    "InputStream.available(): $available"
                )

                if (
                    available > 0
                ) {

                    appendLog(
                        "DTCO уже имеет входящие данные,"
                    )

                    appendLog(
                        "но в этом тесте мы их НЕ читаем."
                    )

                } else {

                    appendLog(
                        "Самопроизвольных входящих данных нет."
                    )
                }

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "InputStream.available",
                    e
                )
            }

            appendLog(
                "Ничего в OutputStream не отправлено."
            )

            appendLog(
                "Закрываю тестовое соединение."
            )

            closeSocket(
                socket
            )

            currentSocket =
                null

            notifyConnectionState(
                false,
                safeDeviceName(
                    device
                )
            )

            return true

        } catch (
            e: Throwable
        ) {

            appendThrowable(
                if (secure) {
                    "SECURE RFCOMM"
                } else {
                    "INSECURE RFCOMM"
                },
                e
            )

            closeSocket(
                socket
            )

            currentSocket =
                null

            return false
        }
    }

    private fun connectSocketWithTimeout(
        socket: BluetoothSocket
    ): Boolean {

        val success =
            AtomicBoolean(
                false
            )

        val error =
            AtomicReference<Throwable?>(
                null
            )

        val connectThread =
            Thread {

                try {

                    socket.connect()

                    success.set(
                        true
                    )

                } catch (
                    e: Throwable
                ) {

                    error.set(
                        e
                    )
                }

            }.apply {

                name =
                    "TachoWatch-RFCOMM-Connect"
            }

        connectThread.start()

        try {

            connectThread.join(
                CONNECT_TIMEOUT_MS
            )

        } catch (
            e: InterruptedException
        ) {

            Thread.currentThread()
                .interrupt()

            appendThrowable(
                "RFCOMM JOIN",
                e
            )

            closeSocket(
                socket
            )

            return false
        }

        if (
            connectThread.isAlive
        ) {

            appendLog(
                "TIMEOUT: connect() не завершился за ${CONNECT_TIMEOUT_MS / 1000} сек."
            )

            /*
             * Закрытие BluetoothSocket должно
             * прервать блокирующий connect().
             */
            closeSocket(
                socket
            )

            try {

                connectThread.join(
                    1500L
                )

            } catch (
                _: InterruptedException
            ) {

                Thread.currentThread()
                    .interrupt()
            }

            return false
        }

        val throwable =
            error.get()

        if (
            throwable != null
        ) {

            appendThrowable(
                "RFCOMM connect",
                throwable
            )

            return false
        }

        return success.get()
    }

    @SuppressLint("MissingPermission")
    private fun logCachedUuids(
        device: BluetoothDevice
    ) {

        val uuids =
            try {

                device.uuids

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "device.uuids",
                    e
                )

                null
            }

        if (
            uuids == null
        ) {

            appendLog(
                "Cached UUID: null"
            )

            return
        }

        if (
            uuids.isEmpty()
        ) {

            appendLog(
                "Cached UUID: список пуст"
            )

            return
        }

        appendLog(
            "Найдено сохранённых UUID: ${uuids.size}"
        )

        uuids.forEachIndexed {
                index,
                parcelUuid ->

            val uuid =
                parcelUuid.uuid

            appendLog(
                "UUID ${index + 1}: $uuid"
            )

            if (
                uuid == SPP_UUID
            ) {

                appendLog(
                    ">>> ЭТО STANDARD SERIAL PORT PROFILE"
                )
            }
        }
    }

    fun disconnect() {

        stopped =
            true

        workerHandler.removeCallbacksAndMessages(
            null
        )

        disconnectSocket()

        notifyConnectionState(
            false,
            null
        )
    }

    private fun disconnectSocket() {

        val socket =
            currentSocket

        currentSocket =
            null

        closeSocket(
            socket
        )
    }

    private fun closeSocket(
        socket: BluetoothSocket?
    ) {

        if (
            socket == null
        ) {

            return
        }

        try {

            socket.close()

        } catch (
            _: Throwable
        ) {
        }
    }

    fun clearLog() {

        logLines.clear()
    }

    fun getLog():
        String {

        return logLines.joinToString(
            "\n"
        )
    }

    private fun appendLog(
        message: String
    ) {

        val time =
            SimpleDateFormat(
                "HH:mm:ss.SSS",
                Locale.getDefault()
            ).format(
                Date()
            )

        logLines.add(
            "$time  $message"
        )

        /*
         * Ограничиваем размер журнала,
         * чтобы диагностическая программа
         * не могла бесконечно накапливать память.
         */
        while (
            logLines.size > 1200
        ) {

            if (
                logLines.isNotEmpty()
            ) {

                logLines.removeAt(
                    0
                )
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
            "Сообщение: ${throwable.message ?: "(нет сообщения)"}"
        )

        val cause =
            throwable.cause

        if (
            cause != null
        ) {

            appendLog(
                "Cause: ${cause.javaClass.name}"
            )

            appendLog(
                "Cause message: ${cause.message ?: "(нет сообщения)"}"
            )
        }
    }

    private fun notifyConnectionState(
        connected: Boolean,
        deviceName: String?
    ) {

        mainHandler.post {

            try {

                listener?.onConnectionStateChanged(
                    connected,
                    deviceName
                )

            } catch (
                _: Throwable
            ) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(
        device: BluetoothDevice
    ): String {

        return try {

            device.name
                ?: "DTCO"

        } catch (
            _: Throwable
        ) {

            "DTCO"
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
                "DUAL (Classic + LE)"

            BluetoothDevice.DEVICE_TYPE_UNKNOWN ->
                "UNKNOWN"

            else ->
                "UNKNOWN($type)"
        }
    }
}
