package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var scanButton: Button
    private lateinit var stopButton: Button
    private lateinit var pairedButton: Button
    private lateinit var clearButton: Button

    private var scanning = false

    private val timeFormat =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val allGranted = permissions.values.all { it }

            if (allGranted) {
                appendLog("Все необходимые разрешения Bluetooth получены")
                showBluetoothStatus()
            } else {
                appendLog("Не все разрешения Bluetooth были предоставлены")
            }
        }

    private val scanCallback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            val device = result.device

            val name =
                try {
                    device.name ?: "Без имени"
                } catch (_: SecurityException) {
                    "Имя недоступно"
                }

            val address = device.address
            val rssi = result.rssi

            appendLog(
                "BLE найдено: $name | $address | RSSI $rssi dBm"
            )

            val record = result.scanRecord

            if (record != null) {

                val serviceUuids = record.serviceUuids

                if (!serviceUuids.isNullOrEmpty()) {
                    appendLog(
                        "  UUID сервисов: ${
                            serviceUuids.joinToString(", ")
                        }"
                    )
                }

                val manufacturerData = record.manufacturerSpecificData

                for (i in 0 until manufacturerData.size()) {

                    val manufacturerId =
                        manufacturerData.keyAt(i)

                    val data =
                        manufacturerData.valueAt(i)

                    appendLog(
                        "  Manufacturer $manufacturerId: ${
                            bytesToHex(data)
                        }"
                    )
                }

                val rawBytes = record.bytes

                appendLog(
                    "  RAW BLE: ${bytesToHex(rawBytes)}"
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            updateButtons()

            appendLog(
                "Ошибка BLE-сканирования. Код: $errorCode"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager =
            getSystemService(
                Context.BLUETOOTH_SERVICE
            ) as BluetoothManager

        bluetoothAdapter =
            bluetoothManager.adapter

        createInterface()

        appendLog("TachoWatch запущен")
        appendLog("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")

        requestBluetoothPermissions()
    }

    private fun createInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "TachoWatch — Bluetooth диагностика"
            textSize = 22f
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 16, 0, 16)
        }

        scanButton = Button(this).apply {
            text = "Сканировать Bluetooth"
            setOnClickListener {
                startBleScan()
            }
        }

        stopButton = Button(this).apply {
            text = "Остановить сканирование"
            isEnabled = false

            setOnClickListener {
                stopBleScan()
            }
        }

        pairedButton = Button(this).apply {
            text = "Показать сопряжённые устройства"

            setOnClickListener {
                showPairedDevices()
            }
        }

        clearButton = Button(this).apply {
            text = "Очистить журнал"

            setOnClickListener {
                logText.text = ""
                appendLog("Журнал очищен")
            }
        }

        val logTitle = TextView(this).apply {
            text = "Диагностический журнал"
            textSize = 18f
            setPadding(0, 24, 0, 8)
        }

        logText = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            addView(logText)
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(scanButton)
        root.addView(stopButton)
        root.addView(pairedButton)
        root.addView(clearButton)
        root.addView(logTitle)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun requestBluetoothPermissions() {

        val permissions =
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
            permissions.filter {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(
                missing.toTypedArray()
            )
        } else {
            showBluetoothStatus()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothStatus() {

        if (!packageManager.hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH
            )
        ) {
            statusText.text =
                "Bluetooth на устройстве отсутствует"

            appendLog(
                "Ошибка: телефон не поддерживает Bluetooth"
            )
            return
        }

        if (!bluetoothAdapter.isEnabled) {

            statusText.text =
                "Bluetooth выключен"

            appendLog(
                "Bluetooth выключен. Включите его в настройках Android."
            )

        } else {

            statusText.text =
                "Bluetooth включен"

            appendLog(
                "Bluetooth адаптер активен"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {

        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            appendLog(
                "Невозможно начать поиск: Bluetooth выключен"
            )
            return
        }

        val scanner =
            bluetoothAdapter.bluetoothLeScanner

        if (scanner == null) {
            appendLog(
                "BLE-сканер недоступен"
            )
            return
        }

        appendLog("--------------------------------")
        appendLog("Начало BLE-сканирования")

        scanning = true
        updateButtons()

        scanner.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {

        if (!scanning) {
            return
        }

        try {

            bluetoothAdapter.bluetoothLeScanner
                ?.stopScan(scanCallback)

        } catch (_: SecurityException) {
        }

        scanning = false
        updateButtons()

        appendLog("BLE-сканирование остановлено")
        appendLog("--------------------------------")
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevices() {

        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        appendLog("--------------------------------")
        appendLog("Сопряжённые Bluetooth-устройства:")

        val devices: Set<BluetoothDevice> =
            bluetoothAdapter.bondedDevices

        if (devices.isEmpty()) {

            appendLog(
                "Сопряжённых устройств нет"
            )

            return
        }

        devices.forEach { device ->

            val name =
                try {
                    device.name ?: "Без имени"
                } catch (_: Exception) {
                    "Имя недоступно"
                }

            val type =
                when (device.type) {

                    BluetoothDevice.DEVICE_TYPE_CLASSIC ->
                        "Classic"

                    BluetoothDevice.DEVICE_TYPE_LE ->
                        "BLE"

                    BluetoothDevice.DEVICE_TYPE_DUAL ->
                        "Dual"

                    else ->
                        "Неизвестно"
                }

            appendLog(
                "$name | ${device.address} | $type"
            )

            try {

                device.uuids?.forEach { uuid ->

                    appendLog(
                        "  UUID: ${uuid.uuid}"
                    )
                }

            } catch (_: Exception) {
            }
        }

        appendLog("--------------------------------")
    }

    private fun hasBluetoothPermissions(): Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED

        } else {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateButtons() {
        scanButton.isEnabled = !scanning
        stopButton.isEnabled = scanning
    }

    private fun appendLog(message: String) {

        val time =
            timeFormat.format(Date())

        runOnUiThread {

            logText.append(
                "[$time] $message\n"
            )
        }
    }

    private fun bytesToHex(bytes: ByteArray?): String {

        if (bytes == null) {
            return ""
        }

        return bytes.joinToString(" ") {
            "%02X".format(it)
        }
    }

    override fun onDestroy() {
        stopBleScan()
        super.onDestroy()
    }
}
