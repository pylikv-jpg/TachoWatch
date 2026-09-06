package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TargetCorrelatorActivity : AppCompatActivity(), TargetCorrelatorDiagnostic.Listener {
    companion object {
        private const val BG = 0xFF0B1118.toInt()
        private const val CARD = 0xFF141D27.toInt()
        private const val TEXT = 0xFFF3F7FA.toInt()
        private const val MUTED = 0xFF9BAAB8.toInt()
        private const val BLUE = 0xFF2196F3.toInt()
        private const val GREEN = 0xFF42C77A.toInt()
        private const val ORANGE = 0xFFFFB547.toInt()
        private const val RED = 0xFFE85D5D.toInt()
    }

    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private lateinit var diagnostic: TargetCorrelatorDiagnostic
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var save: Button
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var selected: BluetoothDevice? = null
    private var rendered = ""

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) loadDevices() else setStatus("Нет разрешения Bluetooth", RED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        diagnostic = TargetCorrelatorDiagnostic(applicationContext, this)
        createUi()
        requestPermissionsIfNeeded()
    }

    override fun onDestroy() {
        if (::diagnostic.isInitialized) diagnostic.stop()
        super.onDestroy()
    }

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(BG)
        }
        root.addView(TextView(this).apply {
            text = "DTCO TARGET CORRELATOR v8"
            textSize = 21f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Поиск: 09:12 • остаток до 24ч • остатки 9h=1 / 10h=2"
            textSize = 12f
            setTextColor(MUTED)
        })
        root.addView(space(7))

        status = TextView(this).apply {
            text = "Выбери тахограф"
            textSize = 14f
            setTextColor(ORANGE)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(CARD, dp(10).toFloat())
        }
        root.addView(status)
        root.addView(space(7))

        val deviceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(8), dp(9), dp(8))
            background = rounded(CARD, dp(10).toFloat())
        }
        deviceCard.addView(TextView(this).apply {
            text = "СОПРЯЖЁННЫЕ DTCO"
            textSize = 12f
            setTextColor(MUTED)
            setTypeface(typeface, Typeface.BOLD)
        })
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        deviceCard.addView(devices)
        root.addView(deviceCard)
        root.addView(space(7))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        start = button("Начать тест", BLUE).apply {
            isEnabled = false
            alpha = 0.55f
            setOnClickListener {
                val d = selected ?: return@setOnClickListener
                rendered = ""
                logText.text = ""
                setStatus("Подключение...", ORANGE)
                isEnabled = false
                diagnostic.start(d)
            }
        }
        stop = button("Стоп", RED).apply { setOnClickListener { diagnostic.stop() } }
        controls.addView(start, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(spaceH(6))
        controls.addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)
        root.addView(space(6))

        save = button("Сохранить отчёт в Download", GREEN).apply {
            setOnClickListener { saveReport(rendered.ifBlank { diagnostic.getLog() }) }
        }
        root.addView(save)
        root.addView(space(7))

        logScroll = ScrollView(this).apply {
            isFillViewport = false
            isNestedScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
        logText = TextView(this).apply {
            text = "После запуска: полный F900-F9FF один раз, затем 12 циклов только положительных DID.\nЭкран не будет автоматически прокручиваться вверх или вниз."
            textSize = 10.8f
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(5), dp(5), dp(5), dp(14))
        }
        logScroll.addView(logText)
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(CARD, dp(10).toFloat())
            addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(logCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        if (!hasBtPermission()) return
        devices.removeAllViews()
        val bonded = bluetoothAdapter?.bondedDevices?.sortedBy { safeName(it) } ?: emptyList()
        val dtcos = bonded.filter { safeName(it).contains("DTCO", true) }
        val list = if (dtcos.isNotEmpty()) dtcos else bonded
        if (list.isEmpty()) {
            devices.addView(TextView(this).apply { text = "Сопряжённых устройств нет"; setTextColor(MUTED) })
            return
        }
        list.forEach { d ->
            devices.addView(button(safeName(d), CARD).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    selected = d
                    setStatus("Выбран ${safeName(d)}", GREEN)
                    start.isEnabled = true
                    start.alpha = 1f
                }
            })
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (hasBtPermission()) loadDevices()
            else permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        } else loadDevices()
    }

    private fun hasBtPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun onLogChanged(fullLog: String) {
        runOnUiThread {
            val old = rendered
            val y = logScroll.scrollY
            if (fullLog.startsWith(old)) {
                val delta = fullLog.substring(old.length)
                if (delta.isNotEmpty()) logText.append(delta)
            } else {
                logText.text = fullLog
            }
            rendered = fullLog
            logScroll.post { logScroll.scrollTo(0, y) }
        }
    }

    override fun onConnectionChanged(connected: Boolean, deviceName: String?) {
        runOnUiThread {
            if (connected) setStatus("Подключено: ${deviceName ?: "DTCO"}", GREEN)
            else if (!rendered.contains("TARGET CORRELATOR FINISHED")) setStatus("Отключено", ORANGE)
        }
    }

    override fun onFinished(fullLog: String) {
        runOnUiThread {
            rendered = fullLog
            setStatus("Тест завершён — отчёт можно сохранить", GREEN)
            start.isEnabled = selected != null
            start.alpha = if (start.isEnabled) 1f else 0.55f
            // Auto-save as well: no file picker, so saving cannot collapse/kill the test UI.
            saveReport(fullLog, automatic = true)
        }
    }

    private fun saveReport(text: String, automatic: Boolean = false) {
        if (text.isBlank()) {
            if (!automatic) Toast.makeText(this, "Отчёт пока пуст", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (!automatic) Toast.makeText(this, "Автосохранение поддерживается на Android 10+", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "DTCO_TARGET_correlator_v8_$stamp.txt"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TachoWatch")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            contentResolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            setStatus("Отчёт сохранён: Download/TachoWatch/$name", GREEN)
            Toast.makeText(this, "Сохранено: $name", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            setStatus("Ошибка сохранения: ${t.javaClass.simpleName}", RED)
            if (!automatic) Toast.makeText(this, "Не удалось сохранить: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setStatus(s: String, color: Int) { status.text = s; status.setTextColor(color) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun space(v: Int) = TextView(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(v)) }
    private fun spaceH(v: Int) = TextView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(v), 1) }
    private fun button(title: String, color: Int) = Button(this).apply {
        text = title
        isAllCaps = false
        setTextColor(TEXT)
        textSize = 12f
        background = rounded(color, dp(9).toFloat())
        setPadding(dp(10), dp(7), dp(10), dp(7))
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice): String = try { d.name ?: d.address } catch (_: Throwable) { "Bluetooth device" }
}
