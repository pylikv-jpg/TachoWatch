package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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
 * DTCO BLE diagnostic
 *
 * Версия GATT-SERVICES-7
 *
 * Главное отличие:
 * BLE worker НИКОГДА напрямую не обновляет интерфейс.
 *
 * appendLog() только сохраняет текст в памяти.
 * UI отдельно забирает журнал раз в 500 мс.
 *
 * MTU / READ / WRITE / NOTIFY отключены.
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
            "GATT-SERVICES-7"

        private const val DISCOVERY_DELAY_MS =
            1000L

        private const val WORKER_INTERVAL_MS =
            100L

        private const val SERVICE_READ_DELAY_MS =
            300L

        private const val UI_REFRESH_MS =
            500L
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
    private var stopped =
        true

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
    private var connectionHandled =
        false

    @Volatile
    private var discoveryStarted =
        false

    @Volatile
    private var discoveryReturned =
        false

    @Volatile
    private var discoveryReturnValue =
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
    private var servicesHandled =
        false

    /*
     * ВАЖНО:
     * Этот runnable только выполняет BLE-логику.
     * UI здесь не вызывается.
     */
    private val workerLoop =
        object : Runnable {

            override fun run() {

                try {

                    processBle()

                } catch (
                    e: Throwable
                ) {

                    appendThrowable(
                        "WORKER LOOP",
                        e
                    )
                }

                workerHandler.postDelayed(
                    this,
                    WORKER_INTERVAL_MS
                )
            }
        }

    /*
     * Отдельный цикл отображения журнала.
     *
     * BLE-worker к нему отношения не имеет.
     */
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

        workerHandler.post(
            workerLoop
        )

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
                "ОШИБКА: нет BLUETOOTH_CONNECT"
            )

            return
        }

        disconnectGattOnly()

        resetState()

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
            "BLE logger: MEMORY ONLY"
        )

        appendLog(
            "UI refresh: ${UI_REFRESH_MS} мс"
        )

        appendLog(
            "MTU: ОТКЛЮЧЕН"
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

        appendLog(
            "Устройство: $name"
        )

        appendLog(
            "Адрес: $address"
        )

        appendLog(
            "Bond state: $bond"
        )

        appendLog(
            "Тип устройства: $type"
        )

        appendLog(
            "Android API: ${Build.VERSION.SDK_INT}"
        )

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "STEP 1: connectGatt()"
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

            appendLog(
                "STEP 1 OK: connectGatt() вернулся"
            )

            appendLog(
                "BluetoothGatt null: ${gatt == null}"
            )

        } catch (
            e: Throwable
        ) {

            appendThrowable(
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

        resetState()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGattOnly() {

        val oldGatt =
            bluetoothGatt

        bluetoothGatt =
            null

        if (
            oldGatt != null
        ) {

            try {

                oldGatt.disconnect()

            } catch (
                _: Throwable
            ) {
            }

            try {

                oldGatt.close()

            } catch (
                _: Throwable
            ) {
            }
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

    private fun resetState() {

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

        connectionHandled =
            false

        discoveryStarted =
            false

        discoveryReturned =
            false

        discoveryReturnValue =
            false

        servicesCallbackReceived =
            false

        servicesCallbackStatus =
            -1

        servicesCallbackAt =
            0L

        servicesHandled =
            false
    }

    /*
     * Bluetooth callback:
     *
     * НЕТ log()
     * НЕТ Handler
     * НЕТ discoverServices()
     *
     * Только переменные.
     */
    private val gattCallback =
        object : BluetoothGattCallback() {

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

                    connectedAt =
                        SystemClock.elapsedRealtime()

                    callbackConnected =
                        true

                } else if (
                    newState ==
                    BluetoothProfile.STATE_DISCONNECTED
                ) {

                    callbackDisconnected =
                        true
                }
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
            }
        }

    private fun processBle() {

        if (
            stopped
        ) {

            return
        }

        processDisconnected()

        processConnected()

        processServices()
    }

    private fun processDisconnected() {

        if (
            !callbackDisconnected
        ) {

            return
        }

        callbackDisconnected =
            false

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "WORKER: DISCONNECTED"
        )

        notifyConnectionState(
            false,
            null
        )
    }

    private fun processConnected() {

        if (
            !callbackConnected
        ) {

            return
        }

        val gatt =
            bluetoothGatt
                ?: return

        if (
            !connectionHandled
        ) {

            connectionHandled =
                true

            /*
             * Сначала запишем ВЕСЬ блок
             * без какого-либо обращения к UI.
             */
            appendLog(
                "----------------------------------------"
            )

            appendLog(
                "STEP 2 OK: WORKER увидел CONNECTED"
            )

            appendLog(
                "GATT status: $connectionStatus"
            )

            appendLog(
                "GATT state: " +
                    connectionStateToString(
                        connectionState
                    )
            )

            appendLog(
                "MARKER A: после GATT state"
            )

            appendLog(
                "MARKER B: worker продолжает работу"
            )

            appendLog(
                "MTU НЕ запрашивается"
            )

            appendLog(
                "MARKER C: перед ожиданием discovery"
            )

            notifyConnectionState(
                true,
                safeGattDeviceName(
                    gatt
                )
            )
        }

        if (
            discoveryStarted
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

        discoveryStarted =
            true

        runDiscovery(
            gatt
        )
    }

    @SuppressLint("MissingPermission")
    private fun runDiscovery(
        gatt: BluetoothGatt
    ) {

        /*
         * Весь диагностический текст сначала
         * складывается только в память.
         */
        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "STEP 3: ПЕРЕД discoverServices()"
        )

        appendLog(
            "Worker thread: " +
                Thread.currentThread().name
        )

        appendLog(
            "MARKER D: сейчас вызываю Android API"
        )

        try {

            val result =
                gatt.discoverServices()

            discoveryReturnValue =
                result

            discoveryReturned =
                true

            appendLog(
                "STEP 4 OK: discoverServices() ВЕРНУЛСЯ"
            )

            appendLog(
                "discoverServices result: $result"
            )

        } catch (
            e: Throwable
        ) {

            discoveryReturned =
                true

            appendThrowable(
                "discoverServices",
                e
            )
        }
    }

    private fun processServices() {

        if (
            !servicesCallbackReceived
        ) {

            return
        }

        if (
            servicesHandled
        ) {

            return
        }

        val elapsed =
            SystemClock.elapsedRealtime() -
                servicesCallbackAt

        if (
            elapsed <
            SERVICE_READ_DELAY_MS
        ) {

            return
        }

        servicesHandled =
            true

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "STEP 5: WORKER получил результат discovery"
        )

        appendLog(
            "Callback status: $servicesCallbackStatus"
        )

        appendLog(
            "discoverServices returned: $discoveryReturned"
        )

        appendLog(
            "discoverServices result: $discoveryReturnValue"
        )

        if (
            servicesCallbackStatus !=
            BluetoothGatt.GATT_SUCCESS
        ) {

            appendLog(
                "Discovery завершён с ошибкой."
            )

            return
        }

        val gatt =
            bluetoothGatt

        if (
            gatt == null
        ) {

            appendLog(
                "ОШИБКА: gatt == null"
            )

            return
        }

        inspectServices(
            gatt
        )
    }

    private fun inspectServices(
        gatt: BluetoothGatt
    ) {

        appendLog(
            "----------------------------------------"
        )

        appendLog(
            "STEP 6: ПЕРЕД gatt.services"
        )

        val services:
            List<BluetoothGattService>

        try {

            services =
                gatt.services

        } catch (
            e: Throwable
        ) {

            appendThrowable(
                "gatt.services",
                e
            )

            return
        }

        appendLog(
            "STEP 7 OK: gatt.services ВЕРНУЛСЯ"
        )

        appendLog(
            "Количество сервисов: ${services.size}"
        )

        if (
            services.isEmpty()
        ) {

            appendLog(
                "========================================"
            )

            appendLog(
                "РЕЗУЛЬТАТ: GATT SERVICES = 0"
            )

            appendLog(
                "========================================"
            )

            return
        }

        dumpServices(
            services
        )
    }

    private fun dumpServices(
        services:
            List<BluetoothGattService>
    ) {

        appendLog(
            "========================================"
        )

        appendLog(
            "НАЙДЕННАЯ GATT-БАЗА"
        )

        appendLog(
            "Сервисов: ${services.size}"
        )

        appendLog(
            "========================================"
        )

        services.forEachIndexed {
                serviceIndex,
                service ->

            try {

                appendLog(
                    ""
                )

                appendLog(
                    "SERVICE #${serviceIndex + 1}"
                )

                appendLog(
                    "UUID: ${service.uuid}"
                )

                appendLog(
                    "Type: " +
                        serviceTypeToString(
                            service.type
                        )
                )

                val characteristics =
                    service.characteristics

                appendLog(
                    "Characteristics: " +
                        characteristics.size
                )

                characteristics.forEachIndexed {
                        charIndex,
                        characteristic ->

                    appendLog(
                        "  CHARACTERISTIC " +
                            "#${charIndex + 1}"
                    )

                    appendLog(
                        "  UUID: " +
                            characteristic.uuid
                    )

                    appendLog(
                        "  Properties: 0x" +
                            characteristic.properties
                                .toString(
                                    16
                                )
                    )

                    appendLog(
                        "  Permissions: " +
                            characteristic.permissions
                    )

                    val descriptors =
                        characteristic.descriptors

                    appendLog(
                        "  Descriptors: " +
                            descriptors.size
                    )

                    descriptors.forEachIndexed {
                            descIndex,
                            descriptor ->

                        appendLog(
                            "    DESCRIPTOR " +
                                "#${descIndex + 1}"
                        )

                        appendLog(
                            "    UUID: " +
                                descriptor.uuid
                        )

                        appendLog(
                            "    Permissions: " +
                                descriptor.permissions
                        )
                    }
                }

            } catch (
                e: Throwable
            ) {

                appendThrowable(
                    "SERVICE #${serviceIndex + 1}",
                    e
                )
            }
        }

        appendLog(
            "========================================"
        )

        appendLog(
            "GATT DATABASE COMPLETE"
        )

        appendLog(
            "========================================"
        )
    }

    @SuppressLint("MissingPermission")
    private fun safeGattDeviceName(
        gatt: BluetoothGatt
    ): String? {

        return try {

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

    /*
     * КЛЮЧЕВАЯ ФУНКЦИЯ VERSION 7.
     *
     * Здесь НЕТ обращения к UI.
     */
    private fun appendLog(
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
    }

    private fun appendThrowable(
        location: String,
        error: Throwable
    ) {

        appendLog(
            "!!! ОШИБКА В $location !!!"
        )

        appendLog(
            "Тип: ${error.javaClass.name}"
        )

        appendLog(
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

            appendLog(
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

    private fun connectionStateToString(
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
}
