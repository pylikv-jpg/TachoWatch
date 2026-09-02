package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), DtcoBluetoothDiagnostic.Listener {

    companion object {
        private const val COLOR_BG = 0xFF0B1118.toInt()
        private const val COLOR_CARD = 0xFF141D27.toInt()
        private const val COLOR_CARD_ALT = 0xFF101821.toInt()
        private const val COLOR_TEXT = 0xFFF3F7FA.toInt()
        private const val COLOR_MUTED = 0xFF9BAAB8.toInt()
        private const val COLOR_BLUE = 0xFF2196F3.toInt()
        private const val COLOR_CYAN = 0xFF29B6C8.toInt()
        private const val COLOR_GREEN = 0xFF42C77A.toInt()
        private const val COLOR_ORANGE = 0xFFFFB547.toInt()
        private const val COLOR_RED = 0xFFE85D5D.toInt()
        private const val COLOR_BORDER = 0xFF263545.toInt()
        private const val RESULT_MARKER = "===== TEST-11 DOWNLOAD RESULT ====="
    }

    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }

    private lateinit var diagnostic: DtcoBluetoothDiagnostic
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var connectButton: Button
    private lateinit var manualGattButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var refreshButton: Button
    private lateinit var clearButton: Button
    private lateinit var copyResultButton: Button
    private lateinit var copyAllButton: Button

    private var selectedDevice: BluetoothDevice? = null
    private var renderedLog = ""

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            setStatus("Bluetooth готов", COLOR_ORANGE)
            loadBondedDevices()
        } else {
            setStatus("Нет разрешения Bluetooth", COLOR_RED)
            deviceText.text = "Разрешение Bluetooth не предоставлено"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BG
        window.navigationBarColor = COLOR_BG
        diagnostic = DtcoBluetoothDiagnostic(applicationContext, this)
        createInterface()
        requestBluetoothPermission()
    }

    override fun onDestroy() {
        if (::diagnostic.isInitialized) diagnostic.disconnect()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(COLOR_BG)
        }

        root.addView(TextView(this).apply {
            text = "TachoWatch"
            textSize = 24f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "DTCO Bluetooth — TEST-11B DOWNLOAD"
            textSize = 12f
            setTextColor(COLOR_CYAN)
        })
        root.addView(space(8))

        val statusCard = card()
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusRow.addView(label("СОЕДИНЕНИЕ"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusDot = View(this).apply { background = rounded(COLOR_ORANGE, dp(20).toFloat()) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusText = TextView(this).apply {
            text = "Запуск..."; textSize = 15f; setTextColor(COLOR_TEXT); setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
        }
        statusRow.addView(statusText)
        statusCard.addView(statusRow)
        deviceText = TextView(this).apply {
            text = "DTCO пока не выбран"; textSize = 12f; setTextColor(COLOR_MUTED); setPadding(0, dp(5), 0, 0); maxLines = 2
        }
        statusCard.addView(deviceText)
        root.addView(statusCard)
        root.addView(space(7))

        val deviceCard = card()
        val deviceHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        deviceHeader.addView(label("СОПРЯЖЁННЫЕ УСТРОЙСТВА"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        refreshButton = smallButton("Обновить").apply { setOnClickListener { loadBondedDevices() } }
        deviceHeader.addView(refreshButton)
        deviceCard.addView(deviceHeader)
        val devicesScroll = ScrollView(this).apply { isFillViewport = false; isNestedScrollingEnabled = true }
        devicesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devicesScroll.addView(devicesContainer)
        deviceCard.addView(devicesScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))
        root.addView(deviceCard)
        root.addView(space(7))

        val controlCard = card()
        val controlHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controlHeader.addView(label("DOWNLOAD SERVICE"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controlHeader.addView(TextView(this).apply {
            text = "READ ONLY"; textSize = 11f; setTextColor(COLOR_GREEN); setTypeface(typeface, Typeface.BOLD)
        })
        controlCard.addView(controlHeader)
        controlCard.addView(space(6))

        connectButton = button("Подключиться и запустить TEST-11B", COLOR_BLUE).apply {
            isEnabled = false; alpha = 0.55f
            setOnClickListener {
                val d = selectedDevice ?: return@setOnClickListener
                renderedLog = ""; logText.text = ""; logScroll.scrollTo(0, 0)
                setStatus("Подключение...", COLOR_ORANGE)
                diagnostic.connect(d)
            }
        }
        controlCard.addView(connectButton)
        controlCard.addView(space(5))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manualGattButton = button("Проверить GATT", COLOR_CYAN).apply {
            isEnabled = false; alpha = 0.55f; setOnClickListener { diagnostic.manualGattCheck() }
        }
        disconnectButton = button("Отключиться", COLOR_CARD_ALT).apply {
            setOnClickListener { diagnostic.disconnect(); setStatus("Отключено", COLOR_ORANGE) }
        }
        row.addView(manualGattButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(hspace(6))
        row.addView(disconnectButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controlCard.addView(row)
        root.addView(controlCard)
        root.addView(space(7))

        val logCard = card()
        val logHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        logHeader.addView(label("ЖУРНАЛ DOWNLOAD"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        clearButton = smallButton("Очистить").apply {
            setOnClickListener { renderedLog = ""; logText.text = ""; diagnostic.clearLog() }
        }
        logHeader.addView(clearButton)
        logCard.addView(logHeader)

        val copyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        copyResultButton = button("Копировать результат", COLOR_GREEN).apply { setOnClickListener { copyResultOnly() } }
        copyAllButton = button("Копировать весь журнал", COLOR_CYAN).apply { setOnClickListener { copyText(renderedLog, "Весь журнал скопирован") } }
        copyRow.addView(copyResultButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        copyRow.addView(hspace(6))
        copyRow.addView(copyAllButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        logCard.addView(copyRow)

        logScroll = ScrollView(this).apply {
            isFillViewport = false; isNestedScrollingEnabled = true; isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false; isSmoothScrollingEnabled = false
        }
        logText = TextView(this).apply {
            text = "Ожидание запуска TEST-11B..."; textSize = 11.5f; setTextColor(COLOR_TEXT); typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.08f); setTextIsSelectable(true); setPadding(0, dp(4), 0, dp(12))
        }
        logScroll.addView(logText)
        logCard.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(logCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun copyResultOnly() {
        val idx = renderedLog.lastIndexOf(RESULT_MARKER)
        if (idx < 0) {
            Toast.makeText(this, "Итог TEST-11 ещё не сформирован", Toast.LENGTH_SHORT).show(); return
        }
        val start = renderedLog.lastIndexOf('\n', idx).let { if (it >= 0) it + 1 else idx }
        copyText(renderedLog.substring(start).trim(), "Результат TEST-11 скопирован")
    }

    private fun copyText(text: String, message: String) {
        if (text.isBlank()) { Toast.makeText(this, "Копировать пока нечего", Toast.LENGTH_SHORT).show(); return }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TachoWatch TEST-11B", text))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun loadBondedDevices() {
        if (!hasBluetoothPermission()) return
        devicesContainer.removeAllViews()
        val bonded = bluetoothAdapter?.bondedDevices?.sortedBy { safeName(it) } ?: emptyList()
        val dtcos = bonded.filter { safeName(it).contains("DTCO", ignoreCase = true) }
        val list = if (dtcos.isNotEmpty()) dtcos else bonded
        if (list.isEmpty()) {
            devicesContainer.addView(TextView(this).apply { text = "Сопряжённых Bluetooth-устройств нет"; setTextColor(COLOR_MUTED); textSize = 12f })
            return
        }
        list.forEach { d ->
            val b = smallButton(safeName(d)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    selectedDevice = d
                    deviceText.text = "Выбран: ${safeName(d)}\n${d.address}"
                    connectButton.isEnabled = true; connectButton.alpha = 1f
                    setStatus("DTCO выбран", COLOR_GREEN)
                }
            }
            devicesContainer.addView(b); devicesContainer.addView(space(4))
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                loadBondedDevices(); setStatus("Bluetooth готов", COLOR_ORANGE)
            } else permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        } else { loadBondedDevices(); setStatus("Bluetooth готов", COLOR_ORANGE) }
    }

    private fun hasBluetoothPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice): String = try { d.name ?: d.address } catch (_: Throwable) { "Bluetooth device" }

    override fun onLogChanged(fullLog: String) {
        runOnUiThread {
            val savedY = if (::logScroll.isInitialized) logScroll.scrollY else 0
            val old = renderedLog
            if (fullLog.startsWith(old)) {
                val delta = fullLog.substring(old.length)
                if (delta.isNotEmpty()) logText.append(delta)
            } else logText.text = fullLog
            renderedLog = fullLog
            logScroll.post { logScroll.scrollTo(0, savedY) }
        }
    }

    override fun onConnectionStateChanged(connected: Boolean, deviceName: String?) {
        runOnUiThread {
            if (connected) {
                setStatus("Подключено", COLOR_GREEN)
                deviceText.text = "Подключено к ${deviceName ?: "DTCO"}"
                manualGattButton.isEnabled = true; manualGattButton.alpha = 1f
            } else {
                setStatus("Отключено", COLOR_ORANGE)
                manualGattButton.isEnabled = false; manualGattButton.alpha = 0.55f
            }
        }
    }

    private fun setStatus(text: String, color: Int) { statusText.text = text; statusDot.background = rounded(color, dp(20).toFloat()) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(9), dp(10), dp(9)); background = rounded(COLOR_CARD, dp(12).toFloat(), COLOR_BORDER)
    }
    private fun label(textValue: String) = TextView(this).apply {
        text = textValue; textSize = 11f; setTextColor(COLOR_MUTED); setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(textValue: String, bg: Int) = Button(this).apply {
        text = textValue; isAllCaps = false; textSize = 12f; setTextColor(COLOR_TEXT); background = rounded(bg, dp(10).toFloat(), COLOR_BORDER)
        minHeight = dp(44); setPadding(dp(10), dp(6), dp(10), dp(6))
    }
    private fun smallButton(textValue: String) = Button(this).apply {
        text = textValue; isAllCaps = false; textSize = 11f; setTextColor(COLOR_TEXT); background = rounded(COLOR_CARD_ALT, dp(8).toFloat(), COLOR_BORDER)
        minHeight = dp(36); setPadding(dp(8), dp(3), dp(8), dp(3))
    }
    private fun rounded(color: Int, radius: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius; setColor(color); if (strokeColor != null) setStroke(dp(1), strokeColor)
    }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, h) }
    private fun hspace(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(w, 1) }
}
