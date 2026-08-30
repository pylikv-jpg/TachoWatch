package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TARGET_NAME = "DTCO-20701187"

        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bluetoothAdapter by lazy {
        bluetoothManager.adapter
    }

    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var logText: TextView

    private lateinit var findButton: Button
    private lateinit var uuidButton: Button
    private lateinit var classicButton: Button
    private lateinit var gattButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var clearButton: Button

    private var targetDevice: BluetoothDevice? = null

    private var bluetoothSocket: BluetoothSocket? = null
    private var bluetoothInput: InputStream? = null

    private var bluetoothGatt: BluetoothGatt? = null

    private var uuidReceiverRegistered = false

    private val timeFormat =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            if (result.values.all { it }) {
                appendLog("Разрешения Bluetooth получены")
                showBluetoothStatus()
                findDtco()
            } else {
                appendLog("Не все разрешения Bluetooth предоставлены")
            }
        }

    private val uuidReceiver = object : BroadcastReceiver() {

        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent?.action != BluetoothDevice.ACTION_UUID) {
                return
            }

            val device =
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

            if (device?.address != targetDevice?.address) {
                return
            }

            val uuids =
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayExtra(
                        BluetoothDevice.EXTRA_UUID,
                        ParcelUuid::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayExtra(
                        BluetoothDevice.EXTRA_UUID
                    )?.mapNotNull { it as? ParcelUuid }?.toTypedArray()
                }

            appendLog("Получен результат SDP")

            if (uuids.isNullOrEmpty()) {
                appendLog("UUID через SDP не найдены")
                return
            }

            uuids.forEach {
                appendLog("UUID: ${it.uuid}")
            }
        }
    }

    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                appendLog(
                    "GATT состояние: status=$status, state=$newState"
                )

                when (newState) {

                    BluetoothProfile.STATE_CONNECTED -> {
                        appendLog("GATT: подключено")
                        updateStatus("GATT подключён")

                        try {
                            gatt.discoverServices()
                            appendLog("Запрошено обнаружение GATT-сервисов")
                        } catch (e: SecurityException) {
                            appendLog(
                                "Ошибка discoverServices: ${e.message}"
                            )
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        appendLog("GATT: соединение разорвано")
                        updateStatus("Не подключено")
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                appendLog(
                    "GATT services discovered, status=$status"
                )

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    return
                }

                val services = gatt.services

                appendLog(
                    "Количество GATT-сервисов: ${services.size}"
                )

                services.forEach { service ->
                    logService(service)
                }

                readGattCharacteristics(gatt, services)
            }

            @Deprecated("Deprecated in Android")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logCharacteristicData(
                        "READ",
                        characteristic.uuid,
                        characteristic.value
                    )
                } else {
                    appendLog(
                        "READ ошибка ${characteristic.uuid}, status=$status"
                    )
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logCharacteristicData(
                        "READ",
                        characteristic.uuid,
                        value
                    )
                } else {
                    appendLog(
                        "READ ошибка ${characteristic.uuid}, status=$status"
                    )
                }
            }

            @Deprecated("Deprecated in Android")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {

                logCharacteristicData(
                    "NOTIFY",
                    characteristic.uuid,
                    characteristic.value
                )
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                logCharacteristicData(
                    "NOTIFY",
                    characteristic.uuid,
                    value
                )
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {

                appendLog(
                    "Descriptor write ${descriptor.uuid}, status=$status"
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createInterface()
        registerUuidReceiver()

        appendLog("TachoWatch Diagnostic v2")
        appendLog(
            "Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}"
        )
        appendLog("Целевой тахограф: $TARGET_NAME")

        requestPermissions()
    }

    private fun createInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "TachoWatch — DTCO диагностика"
            textSize = 22f
        }

        statusText = TextView(this).apply {
            text = "Статус: запуск..."
            textSize = 17f
            setPadding(0, 14, 0, 8)
        }

        deviceText = TextView(this).apply {
            text = "DTCO пока не найден"
            textSize = 15f
            setPadding(0, 4, 0, 16)
        }

        findButton = Button(this).apply {
            text = "НАЙТИ DTCO-20701187"
            setOnClickListener {
                findDtco()
            }
        }

        uuidButton = Button(this).apply {
            text = "ПОЛУЧИТЬ UUID / SDP"
            isEnabled = false
            setOnClickListener {
                requestDeviceUuids()
            }
        }

        classicButton = Button(this).apply {
            text = "ПОДКЛЮЧИТЬ CLASSIC / RFCOMM"
            isEnabled = false
            setOnClickListener {
                connectClassic()
            }
        }

        gattButton = Button(this).apply {
            text = "ПОДКЛЮЧИТЬ BLE / GATT"
            isEnabled = false
            setOnClickListener {
                connectGatt()
            }
        }

        disconnectButton = Button(this).apply {
            text = "ОТКЛЮЧИТЬСЯ"
            setOnClickListener {
                disconnectAll()
            }
        }

        clearButton = Button(this).apply {
            text = "ОЧИСТИТЬ ЖУРНАЛ"
            setOnClickListener {
                logText.text = ""
                appendLog("Журнал очищен")
            }
        }

        val logTitle = TextView(this).apply {
            text = "Диагностический журнал"
            textSize = 18f
            setPadding(0, 18, 0, 8)
        }

        logText = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }

        val scroll = ScrollView(this).apply {
            addView(logText)
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(deviceText)

        root.addView(findButton)
        root.addView(uuidButton)
        root.addView(classicButton)
        root.addView(gattButton)
        root.addView(disconnectButton)
        root.addView(clearButton)

        root.addView(logTitle)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun requestPermissions() {

        val requiredPermissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )

            } else {

                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }

        val missing =
            requiredPermissions.filter {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missing.isEmpty()) {
            showBluetoothStatus()
            findDtco()
        } else {
            permissionLauncher.launch(
                missing.toTypedArray()
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothStatus() {

        if (bluetoothAdapter == null) {
            updateStatus("Bluetooth отсутствует")
            appendLog("BluetoothAdapter отсутствует")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            updateStatus("Bluetooth выключен")
            appendLog("Включите Bluetooth")
            return
        }

        updateStatus("Bluetooth включен")
        appendLog("Bluetooth активен")
    }

    @SuppressLint("MissingPermission")
    private fun findDtco() {

        if (!hasBluetoothPermission()) {
            requestPermissions()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            appendLog("Bluetooth выключен")
            return
        }

        appendLog("--------------------------------")
        appendLog("Поиск $TARGET_NAME среди сопряжённых устройств")

        val bonded = bluetoothAdapter.bondedDevices

        if (bonded.isEmpty()) {
            appendLog("Сопряжённых Bluetooth-устройств нет")
            return
        }

        bonded.forEach { device ->

            val name =
                try {
                    device.name ?: "Без имени"
                } catch (_: Exception) {
                    "Недоступно"
                }

            appendLog(
                "PAIR: $name | ${device.address} | type=${deviceType(device.type)}"
            )
        }

        val found =
            bonded.firstOrNull { device ->
                try {
                    device.name?.equals(
                        TARGET_NAME,
                        ignoreCase = true
                    ) == true
                } catch (_: Exception) {
                    false
                }
            }

        if (found == null) {

            targetDevice = null

            deviceText.text =
                "DTCO-20701187 не найден среди сопряжённых"

            uuidButton.isEnabled = false
            classicButton.isEnabled = false
            gattButton.isEnabled = false

            appendLog("$TARGET_NAME НЕ найден")
            return
        }

        targetDevice = found

        val info =
            "$TARGET_NAME\n" +
                    "MAC: ${found.address}\n" +
                    "Тип: ${deviceType(found.type)}\n" +
                    "Bond state: ${found.bondState}"

        deviceText.text = info

        uuidButton.isEnabled = true
        classicButton.isEnabled = true
        gattButton.isEnabled = true

        appendLog("НАЙДЕН ЦЕЛЕВОЙ DTCO")
        appendLog("MAC: ${found.address}")
        appendLog("Тип: ${deviceType(found.type)}")
        appendLog("Bond state: ${found.bondState}")

        found.uuids?.forEach {
            appendLog("Сохранённый UUID: ${it.uuid}")
        }

        appendLog("--------------------------------")
    }

    @SuppressLint("MissingPermission")
    private fun requestDeviceUuids() {

        val device = targetDevice ?: return

        appendLog("--------------------------------")
        appendLog("Запрашиваю UUID через SDP")

        try {

            device.uuids?.forEach {
                appendLog(
                    "UUID из cache: ${it.uuid}"
                )
            }

            val started =
                device.fetchUuidsWithSdp()

            appendLog(
                "fetchUuidsWithSdp() = $started"
            )

        } catch (e: Exception) {

            appendLog(
                "Ошибка SDP: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectClassic() {

        val device = targetDevice ?: return

        disconnectClassic()

        appendLog("--------------------------------")
        appendLog("CLASSIC/RFCOMM подключение")
        appendLog("Устройство: ${device.name}")
        appendLog("MAC: ${device.address}")

        thread {

            val uuidCandidates = mutableListOf<UUID>()

            try {
                device.uuids?.forEach {
                    uuidCandidates.add(it.uuid)
                }
            } catch (_: Exception) {
            }

            if (!uuidCandidates.contains(SPP_UUID)) {
                uuidCandidates.add(SPP_UUID)
            }

            if (uuidCandidates.isEmpty()) {
                appendLog("Нет UUID для проверки RFCOMM")
                return@thread
            }

            uuidCandidates.forEach { uuid ->

                if (bluetoothSocket?.isConnected == true) {
                    return@forEach
                }

                appendLog(
                    "Пробую RFCOMM UUID: $uuid"
                )

                var socket: BluetoothSocket? = null

                try {

                    bluetoothAdapter.cancelDiscovery()

                    socket =
                        device.createRfcommSocketToServiceRecord(uuid)

                    socket.connect()

                    bluetoothSocket = socket

                    appendLog(
                        "RFCOMM ПОДКЛЮЧЕНИЕ УСПЕШНО"
                    )

                    appendLog(
                        "Рабочий UUID: $uuid"
                    )

                    updateStatus("DTCO подключён по RFCOMM")

                    bluetoothInput =
                        socket.inputStream

                    startClassicReader(
                        bluetoothInput!!
                    )

                } catch (e: Exception) {

                    appendLog(
                        "RFCOMM не подключился: " +
                                "${e.javaClass.simpleName}: ${e.message}"
                    )

                    try {
                        socket?.close()
                    } catch (_: Exception) {
                    }
                }
            }

            if (bluetoothSocket?.isConnected != true) {

                appendLog(
                    "Все проверенные RFCOMM UUID не дали соединение"
                )

                appendLog(
                    "Это ещё не означает, что DTCO недоступен — " +
                            "он может использовать другой Bluetooth-профиль."
                )
            }
        }
    }

    private fun startClassicReader(
        input: InputStream
    ) {

        thread {

            appendLog(
                "Запущено чтение входящего RFCOMM потока"
            )

            val buffer = ByteArray(4096)

            while (bluetoothSocket?.isConnected == true) {

                try {

                    val count =
                        input.read(buffer)

                    if (count <= 0) {
                        continue
                    }

                    val data =
                        buffer.copyOf(count)

                    appendLog(
                        "RX RFCOMM [$count]: ${bytesToHex(data)}"
                    )

                    val ascii =
                        bytesToAscii(data)

                    if (ascii.isNotBlank()) {
                        appendLog(
                            "ASCII: $ascii"
                        )
                    }

                } catch (e: Exception) {

                    appendLog(
                        "RFCOMM чтение завершено: ${e.message}"
                    )

                    break
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt() {

        val device =
            targetDevice ?: return

        disconnectGatt()

        appendLog("--------------------------------")
        appendLog("Попытка BLE/GATT подключения")
        appendLog("MAC: ${device.address}")

        try {

            bluetoothGatt =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    device.connectGatt(
                        this,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )

                } else {

                    @Suppress("DEPRECATION")
                    device.connectGatt(
                        this,
                        false,
                        gattCallback
                    )
                }

            updateStatus("GATT: подключение...")

        } catch (e: Exception) {

            appendLog(
                "Ошибка connectGatt: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun logService(
        service: BluetoothGattService
    ) {

        appendLog(
            "SERVICE ${service.uuid}"
        )

        service.characteristics.forEach { characteristic ->

            val properties =
                characteristicProperties(
                    characteristic.properties
                )

            appendLog(
                "  CHAR ${characteristic.uuid}"
            )

            appendLog(
                "    properties: $properties"
            )

            characteristic.descriptors.forEach { descriptor ->

                appendLog(
                    "    DESCRIPTOR ${descriptor.uuid}"
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readGattCharacteristics(
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>
    ) {

        thread {

            for (service in services) {

                for (characteristic in service.characteristics) {

                    val properties =
                        characteristic.properties

                    if (
                        properties and
                        BluetoothGattCharacteristic.PROPERTY_READ != 0
                    ) {

                        appendLog(
                            "Запрос READ ${characteristic.uuid}"
                        )

                        try {

                            gatt.readCharacteristic(
                                characteristic
                            )

                            Thread.sleep(500)

                        } catch (e: Exception) {

                            appendLog(
                                "READ запуск ошибка: ${e.message}"
                            )
                        }
                    }

                    val notify =
                        properties and
                                BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

                    val indicate =
                        properties and
                                BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

                    if (notify || indicate) {

                        enableGattNotification(
                            gatt,
                            characteristic,
                            indicate
                        )

                        Thread.sleep(500)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableGattNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        indication: Boolean
    ) {

        try {

            val localResult =
                gatt.setCharacteristicNotification(
                    characteristic,
                    true
                )

            appendLog(
                "Notify local ${characteristic.uuid}: $localResult"
            )

            val descriptor =
                characteristic.getDescriptor(
                    CCCD_UUID
                )

            if (descriptor == null) {

                appendLog(
                    "CCCD отсутствует для ${characteristic.uuid}"
                )

                return
            }

            val value =
                if (indication) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }

            if (Build.VERSION.SDK_INT >= 33) {

                val result =
                    gatt.writeDescriptor(
                        descriptor,
                        value
                    )

                appendLog(
                    "CCCD write ${characteristic.uuid}: $result"
                )

            } else {

                @Suppress("DEPRECATION")
                descriptor.value = value

                @Suppress("DEPRECATION")
                val result =
                    gatt.writeDescriptor(
                        descriptor
                    )

                appendLog(
                    "CCCD write ${characteristic.uuid}: $result"
                )
            }

        } catch (e: Exception) {

            appendLog(
                "Notify ошибка ${characteristic.uuid}: ${e.message}"
            )
        }
    }

    private fun logCharacteristicData(
        source: String,
        uuid: UUID,
        data: ByteArray?
    ) {

        if (data == null) {
            appendLog("$source $uuid: null")
            return
        }

        appendLog(
            "$source $uuid [${data.size}]"
        )

        appendLog(
            "HEX: ${bytesToHex(data)}"
        )

        val ascii =
            bytesToAscii(data)

        if (ascii.isNotBlank()) {

            appendLog(
                "ASCII: $ascii"
            )
        }
    }

    private fun characteristicProperties(
        properties: Int
    ): String {

        val result =
            mutableListOf<String>()

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_READ != 0
        ) {
            result.add("READ")
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        ) {
            result.add("WRITE")
        }

        if (
            properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            result.add("WRITE_NO_RESPONSE")
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
            BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0
        ) {
            result.add("BROADCAST")
        }

        return if (result.isEmpty()) {
            "NONE"
        } else {
            result.joinToString(" | ")
        }
    }

    private fun deviceType(type: Int): String {

        return when (type) {

            BluetoothDevice.DEVICE_TYPE_CLASSIC ->
                "CLASSIC"

            BluetoothDevice.DEVICE_TYPE_LE ->
                "BLE"

            BluetoothDevice.DEVICE_TYPE_DUAL ->
                "DUAL"

            else ->
                "UNKNOWN"
        }
    }

    private fun bytesToHex(
        bytes: ByteArray
    ): String {

        return bytes.joinToString(" ") {
            "%02X".format(
                it.toInt() and 0xFF
            )
        }
    }

    private fun bytesToAscii(
        bytes: ByteArray
    ): String {

        return buildString {

            bytes.forEach {

                val value =
                    it.toInt() and 0xFF

                if (value in 32..126) {
                    append(value.toChar())
                } else {
                    append('.')
                }
            }
        }
    }

    private fun updateStatus(
        text: String
    ) {

        runOnUiThread {
            statusText.text =
                "Статус: $text"
        }
    }

    private fun appendLog(
        message: String
    ) {

        val timestamp =
            timeFormat.format(Date())

        runOnUiThread {

            logText.append(
                "[$timestamp] $message\n"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectClassic() {

        try {
            bluetoothInput?.close()
        } catch (_: Exception) {
        }

        try {
            bluetoothSocket?.close()
        } catch (_: Exception) {
        }

        bluetoothInput = null
        bluetoothSocket = null
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {

        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {
        }

        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }

        bluetoothGatt = null
    }

    private fun disconnectAll() {

        disconnectClassic()
        disconnectGatt()

        updateStatus("Не подключено")

        appendLog(
            "Все Bluetooth-соединения закрыты"
        )
    }

    private fun hasBluetoothPermission(): Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

        } else {

            true
        }
    }

    private fun registerUuidReceiver() {

        if (uuidReceiverRegistered) {
            return
        }

        val filter =
            IntentFilter(
                BluetoothDevice.ACTION_UUID
            )

        if (Build.VERSION.SDK_INT >= 33) {

            registerReceiver(
                uuidReceiver,
                filter,
                Context.RECEIVER_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")
            registerReceiver(
                uuidReceiver,
                filter
            )
        }

        uuidReceiverRegistered = true
    }

    override fun onDestroy() {

        disconnectAll()

        if (uuidReceiverRegistered) {

            try {
                unregisterReceiver(
                    uuidReceiver
                )
            } catch (_: Exception) {
            }

            uuidReceiverRegistered = false
        }

        super.onDestroy()
    }
}
