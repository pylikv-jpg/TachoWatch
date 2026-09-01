package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class DriverActivity : AppCompatActivity(), DtcoBluetoothDiagnostic.Listener {

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
        private const val COLOR_BORDER = 0xFF263545.toInt()
    }

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }

    private lateinit var diagnostic: DtcoBluetoothDiagnostic
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var connectButton: Button

    private lateinit var driverNameValue: TextView
    private lateinit var activityValue: TextView
    private lateinit var activityDurationValue: TextView
    private lateinit var continuousDrivingValue: TextView
    private lateinit var breakValue: TextView
    private lateinit var twoWeekDrivingValue: TextView

    private var dtcoDevice: BluetoothDevice? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            findDtco()
        } else {
            setConnectionStatus("Нет разрешения Bluetooth", COLOR_ORANGE)
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

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(COLOR_BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(TextView(this).apply {
            text = "TachoWatch"
            textSize = 25f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, Typeface.BOLD)
        })
        titleBlock.addView(TextView(this).apply {
            text = "Экран водителя"
            textSize = 12f
            setTextColor(COLOR_CYAN)
        })
        header.addView(titleBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("Диагностика").apply {
            setOnClickListener { startActivity(Intent(this@DriverActivity, MainActivity::class.java)) }
        })
        root.addView(header)
        root.addView(space(8))

        val statusCard = card()
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply { background = rounded(COLOR_ORANGE, dp(20).toFloat()) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusText = TextView(this).apply {
            text = "Поиск DTCO..."
            textSize = 15f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        statusRow.addView(statusText)
        statusCard.addView(statusRow)

        deviceText = TextView(this).apply {
            text = "Ожидание Bluetooth"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(5), 0, 0)
        }
        statusCard.addView(deviceText)

        connectButton = Button(this).apply {
            text = "Подключиться и обновить данные"
            textSize = 13f
            isAllCaps = false
            setTextColor(COLOR_TEXT)
            background = rounded(COLOR_BLUE, dp(10).toFloat(), COLOR_BORDER)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            isEnabled = false
            alpha = 0.55f
            setOnClickListener { connectToDtco() }
        }
        statusCard.addView(space(7))
        statusCard.addView(connectButton)
        root.addView(statusCard)
        root.addView(space(8))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val driverCard = card()
        driverCard.addView(label("ВОДИТЕЛЬ"))
        driverNameValue = valueText("—", 22f)
        driverCard.addView(driverNameValue)
        content.addView(driverCard)
        content.addView(space(8))

        val activityCard = card()
        activityCard.addView(label("ТЕКУЩАЯ ДЕЯТЕЛЬНОСТЬ"))
        activityValue = valueText("—", 24f)
        activityCard.addView(activityValue)
        activityCard.addView(space(6))
        activityCard.addView(label("Длительность текущей деятельности"))
        activityDurationValue = valueText("—", 30f)
        activityCard.addView(activityDurationValue)
        content.addView(activityCard)
        content.addView(space(8))

        val driveCard = card()
        driveCard.addView(label("НЕПРЕРЫВНОЕ ВОЖДЕНИЕ"))
        continuousDrivingValue = valueText("—", 34f)
        driveCard.addView(continuousDrivingValue)
        driveCard.addView(subText("из 4:30"))
        content.addView(driveCard)
        content.addView(space(8))

        val breakCard = card()
        breakCard.addView(label("ТЕКУЩАЯ / НАКОПЛЕННАЯ ПАУЗА"))
        breakValue = valueText("—", 34f)
        breakCard.addView(breakValue)
        content.addView(breakCard)
        content.addView(space(8))

        val twoWeekCard = card()
        twoWeekCard.addView(label("ВОЖДЕНИЕ ЗА ТЕКУЩУЮ + ПРЕДЫДУЩУЮ НЕДЕЛЮ"))
        twoWeekDrivingValue = valueText("—", 34f)
        twoWeekCard.addView(twoWeekDrivingValue)
        twoWeekCard.addView(subText("из 90:00"))
        content.addView(twoWeekCard)

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            findDtco()
        }
    }

    @SuppressLint("MissingPermission")
    private fun findDtco() {
        if (!hasBluetoothPermission()) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            setConnectionStatus("Bluetooth выключен", COLOR_ORANGE)
            deviceText.text = "Включи Bluetooth"
            return
        }

        dtcoDevice = try {
            adapter.bondedDevices.firstOrNull {
                try { (it.name ?: "").contains("DTCO", ignoreCase = true) }
                catch (_: SecurityException) { false }
            }
        } catch (_: SecurityException) { null }

        val device = dtcoDevice
        if (device == null) {
            setConnectionStatus("DTCO не найден", COLOR_ORANGE)
            deviceText.text = "Сначала сопряги тахограф по Bluetooth"
            connectButton.isEnabled = false
            connectButton.alpha = 0.55f
        } else {
            val name = try { device.name ?: "DTCO" } catch (_: SecurityException) { "DTCO" }
            setConnectionStatus("DTCO готов", COLOR_ORANGE)
            deviceText.text = name
            connectButton.isEnabled = true
            connectButton.alpha = 1f
        }
    }

    private fun connectToDtco() {
        val device = dtcoDevice ?: return
        clearDisplayedValues()
        setConnectionStatus("Подключение...", COLOR_ORANGE)
        connectButton.isEnabled = false
        connectButton.alpha = 0.55f
        diagnostic.connect(device)
    }

    override fun onConnectionStateChanged(connected: Boolean, deviceName: String?) {
        runOnUiThread {
            if (connected) {
                setConnectionStatus("Подключено", COLOR_GREEN)
                deviceText.text = "Подключено к ${deviceName ?: "DTCO"}"
            } else {
                setConnectionStatus("Отключено", COLOR_ORANGE)
                connectButton.isEnabled = dtcoDevice != null
                connectButton.alpha = if (dtcoDevice != null) 1f else 0.55f
            }
        }
    }

    override fun onLogChanged(fullLog: String) {
        runOnUiThread { parseDriverData(fullLog) }
    }

    private fun parseDriverData(log: String) {
        extractTimeForDid(log, "F923")?.let { continuousDrivingValue.text = it }
        extractTimeForDid(log, "F925")?.let { breakValue.text = it }
        extractTimeForDid(log, "F927")?.let { activityDurationValue.text = it }
        extractTimeForDid(log, "F938")?.let { twoWeekDrivingValue.text = it }
        extractActivity(log)?.let { activityValue.text = it }
        extractDriverName(log)?.let { driverNameValue.text = it }
    }

    /*
     * DtcoBluetoothDiagnostic ставит перед каждой строкой время вида [21:49:44.771].
     * Поэтому здесь мы намеренно не привязываемся к началу строки, а ищем значение
     * внутри небольшого блока, начинающегося с нужного DID.
     */
    private fun extractTimeForDid(log: String, did: String): String? {
        val positions = Regex("\\b$did\\b").findAll(log).map { it.range.first }.toList()
        for (position in positions.asReversed()) {
            val end = (position + 600).coerceAtMost(log.length)
            val block = log.substring(position, end)

            Regex("(?:DECODED\\s*=\\s*)?(\\d+)\\s+min\\s*=\\s*([0-9]+:[0-9]{2})")
                .find(block)
                ?.groupValues
                ?.getOrNull(2)
                ?.let { return it }
        }
        return null
    }

    private fun extractActivity(log: String): String? {
        val positions = Regex("\\bF903\\b").findAll(log).map { it.range.first }.toList()
        for (position in positions.asReversed()) {
            val end = (position + 500).coerceAtMost(log.length)
            val block = log.substring(position, end)

            val state = Regex("(?:DECODED\\s*=\\s*)?([0-3])\\s*[—-]\\s*(?:ОТДЫХ|ПЕРЕРЫВ|ГОТОВНОСТЬ|РАБОТА|ВОЖДЕНИЕ)")
                .find(block)
                ?.groupValues
                ?.getOrNull(1)
                ?: continue

            return when (state) {
                "0" -> "ОТДЫХ / ПЕРЕРЫВ"
                "1" -> "ГОТОВНОСТЬ"
                "2" -> "РАБОТА"
                "3" -> "ВОЖДЕНИЕ"
                else -> null
            }
        }
        return null
    }

    private fun extractDriverName(log: String): String? {
        val positions = Regex("\\bF931\\b").findAll(log).map { it.range.first }.toList()
        for (position in positions.asReversed()) {
            val end = (position + 900).coerceAtMost(log.length)
            val block = log.substring(position, end)
            val value = Regex("ASCII=\\\"([^\\\"]+)\\\"")
                .find(block)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun clearDisplayedValues() {
        driverNameValue.text = "—"
        activityValue.text = "—"
        activityDurationValue.text = "—"
        continuousDrivingValue.text = "—"
        breakValue.text = "—"
        twoWeekDrivingValue.text = "—"
    }

    private fun setConnectionStatus(text: String, color: Int) {
        if (::statusText.isInitialized) statusText.text = text
        if (::statusDot.isInitialized) statusDot.background = rounded(color, dp(20).toFloat())
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(COLOR_MUTED)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun valueText(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(COLOR_TEXT)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, 0)
    }

    private fun subText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(COLOR_MUTED)
    }

    private fun smallButton(text: String) = Button(this).apply {
        this.text = text
        textSize = 11f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setTextColor(COLOR_TEXT)
        setPadding(dp(9), dp(4), dp(9), dp(4))
        background = rounded(COLOR_CARD_ALT, dp(9).toFloat(), COLOR_BORDER)
    }

    private fun card(color: Int = COLOR_CARD) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = rounded(color, dp(15).toFloat(), COLOR_BORDER)
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun rounded(color: Int, radius: Float, strokeColor: Int? = null) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
