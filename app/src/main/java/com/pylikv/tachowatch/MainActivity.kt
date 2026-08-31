package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity :
    AppCompatActivity(),
    DtcoBluetoothDiagnostic.Listener {

    companion object {

        private const val COLOR_BG =
            0xFF0B1118.toInt()

        private const val COLOR_CARD =
            0xFF141D27.toInt()

        private const val COLOR_CARD_ALT =
            0xFF101821.toInt()

        private const val COLOR_TEXT =
            0xFFF3F7FA.toInt()

        private const val COLOR_MUTED =
            0xFF9BAAB8.toInt()

        private const val COLOR_BLUE =
            0xFF2196F3.toInt()

        private const val COLOR_CYAN =
            0xFF29B6C8.toInt()

        private const val COLOR_GREEN =
            0xFF42C77A.toInt()

        private const val COLOR_ORANGE =
            0xFFFFB547.toInt()

        private const val COLOR_RED =
            0xFFE85D5D.toInt()

        private const val COLOR_BORDER =
            0xFF263545.toInt()
    }

    private val bluetoothManager by lazy {

        getSystemService(
            Context.BLUETOOTH_SERVICE
        ) as BluetoothManager
    }

    private val bluetoothAdapter by lazy {

        bluetoothManager.adapter
    }

    private lateinit var diagnostic:
        DtcoBluetoothDiagnostic

    private lateinit var statusDot:
        View

    private lateinit var statusText:
        TextView

    private lateinit var deviceText:
        TextView

    private lateinit var devicesContainer:
        LinearLayout

    private lateinit var logText:
        TextView

    private lateinit var connectButton:
        Button

    private lateinit var manualGattButton:
        Button

    private lateinit var disconnectButton:
        Button

    private lateinit var refreshButton:
        Button

    private lateinit var clearButton:
        Button

    private var selectedDevice:
        BluetoothDevice? = null

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .RequestMultiplePermissions()
        ) { result ->

            val granted =
                result.values.all {
                    it
                }

            if (granted) {

                setStatus(
                    "Bluetooth готов",
                    COLOR_ORANGE
                )

                loadBondedDevices()

            } else {

                setStatus(
                    "Нет разрешения Bluetooth",
                    COLOR_RED
                )

                deviceText.text =
                    "Разрешение Bluetooth не предоставлено"
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        window.statusBarColor =
            COLOR_BG

        window.navigationBarColor =
            COLOR_BG

        diagnostic =
            DtcoBluetoothDiagnostic(
                applicationContext,
                this
            )

        createInterface()

        requestBluetoothPermission()
    }

    override fun onDestroy() {

        if (
            ::diagnostic.isInitialized
        ) {

            diagnostic.disconnect()
        }

        super.onDestroy()
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    private fun createInterface() {

        val root =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(16),
                    dp(18),
                    dp(16),
                    dp(18)
                )

                setBackgroundColor(
                    COLOR_BG
                )
            }

        val title =
            TextView(this).apply {

                text =
                    "TachoWatch"

                textSize =
                    27f

                setTextColor(
                    COLOR_TEXT
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }

        root.addView(
            title
        )

        val subtitle =
            TextView(this).apply {

                text =
                    "DTCO Bluetooth diagnostics — MANUAL GATT"

                textSize =
                    13f

                setTextColor(
                    COLOR_CYAN
                )

                setPadding(
                    0,
                    dp(2),
                    0,
                    0
                )
            }

        root.addView(
            subtitle
        )

        root.addView(
            verticalSpace(
                dp(14)
            )
        )

        val statusCard =
            createCard()

        statusCard.addView(
            labelText(
                "СОСТОЯНИЕ СОЕДИНЕНИЯ"
            )
        )

        val statusRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            }

        statusDot =
            View(this).apply {

                background =
                    roundedBackground(
                        COLOR_ORANGE,
                        dp(20).toFloat()
                    )
            }

        statusRow.addView(
            statusDot,
            LinearLayout.LayoutParams(
                dp(10),
                dp(10)
            )
        )

        statusText =
            TextView(this).apply {

                text =
                    "Запуск..."

                textSize =
                    18f

                setTextColor(
                    COLOR_TEXT
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setPadding(
                    dp(10),
                    0,
                    0,
                    0
                )
            }

        statusRow.addView(
            statusText
        )

        statusCard.addView(
            statusRow
        )

        deviceText =
            TextView(this).apply {

                text =
                    "DTCO пока не выбран"

                textSize =
                    14f

                setTextColor(
                    COLOR_MUTED
                )

                setPadding(
                    0,
                    dp(8),
                    0,
                    0
                )
            }

        statusCard.addView(
            deviceText
        )

        root.addView(
            statusCard
        )

        root.addView(
            verticalSpace(
                dp(12)
            )
        )

        val deviceCard =
            createCard()

        deviceCard.addView(
            labelText(
                "СОПРЯЖЁННЫЕ BLUETOOTH-УСТРОЙСТВА"
            )
        )

        val hint =
            TextView(this).apply {

                text =
                    "Выбери DTCO из списка сопряжённых устройств."

                textSize =
                    13f

                setTextColor(
                    COLOR_MUTED
                )

                setPadding(
                    0,
                    dp(7),
                    0,
                    dp(10)
                )
            }

        deviceCard.addView(
            hint
        )

        devicesContainer =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL
            }

        deviceCard.addView(
            devicesContainer
        )

        refreshButton =
            createButton(
                "Обновить список",
                COLOR_CARD_ALT
            )

        refreshButton.setOnClickListener {

            loadBondedDevices()
        }

        deviceCard.addView(
            verticalSpace(
                dp(8)
            )
        )

        deviceCard.addView(
            refreshButton
        )

        root.addView(
            deviceCard
        )

        root.addView(
            verticalSpace(
                dp(12)
            )
        )

        val controlCard =
            createCard()

        controlCard.addView(
            labelText(
                "ДИАГНОСТИКА BLE / GATT"
            )
        )

        val safeHint =
            TextView(this).apply {

                text =
                    "Без записи в карту водителя и без записи в DTCO."

                textSize =
                    13f

                setTextColor(
                    COLOR_GREEN
                )

                setPadding(
                    0,
                    dp(7),
                    0,
                    dp(4)
                )
            }

        controlCard.addView(
            safeHint
        )

        connectButton =
            createButton(
                "Подключиться и начать диагностику",
                COLOR_BLUE
            )

        connectButton.isEnabled =
            false

        connectButton.alpha =
            0.55f

        connectButton.setOnClickListener {

            val device =
                selectedDevice

            if (
                device == null
            ) {

                setStatus(
                    "Сначала выбери DTCO",
                    COLOR_ORANGE
                )

                return@setOnClickListener
            }

            setStatus(
                "Подключение...",
                COLOR_ORANGE
            )

            diagnostic.connect(
                device
            )
        }

        manualGattButton =
            createButton(
                "Проверить GATT сейчас",
                COLOR_CYAN
            )

        manualGattButton.isEnabled =
            false

        manualGattButton.alpha =
            0.55f

        manualGattButton.setOnClickListener {

            setStatus(
                "Ручная проверка GATT...",
                COLOR_ORANGE
            )

            diagnostic.manualGattCheck()
        }

        disconnectButton =
            createButton(
                "Отключиться",
                COLOR_CARD_ALT
            )

        disconnectButton.setOnClickListener {

            diagnostic.disconnect()

            setStatus(
                "Отключено",
                COLOR_ORANGE
            )
        }

        controlCard.addView(
            verticalSpace(
                dp(10)
            )
        )

        controlCard.addView(
            connectButton
        )

        controlCard.addView(
            verticalSpace(
                dp(8)
            )
        )

        controlCard.addView(
            manualGattButton
        )

        controlCard.addView(
            verticalSpace(
                dp(8)
            )
        )

        controlCard.addView(
            disconnectButton
        )

        root.addView(
            controlCard
        )

        root.addView(
            verticalSpace(
                dp(12)
            )
        )

        val logCard =
            createCard()

        val logHeader =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        logHeader.addView(
            labelText(
                "ДИАГНОСТИЧЕСКИЙ ЖУРНАЛ"
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        clearButton =
            createSmallButton(
                "Очистить"
            )

        clearButton.setOnClickListener {

            diagnostic.clearLog()
        }

        logHeader.addView(
            clearButton
        )

        logCard.addView(
            logHeader
        )

        val logScroll =
            ScrollView(this).apply {

                isFillViewport =
                    false

                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            }

        logText =
            TextView(this).apply {

                text =
                    "Ожидание запуска диагностики..."

                textSize =
                    12f

                setTextColor(
                    COLOR_TEXT
                )

                setTextIsSelectable(
                    true
                )

                typeface =
                    Typeface.MONOSPACE

                setLineSpacing(
                    0f,
                    1.12f
                )

                setPadding(
                    dp(12),
                    dp(12),
                    dp(12),
                    dp(12)
                )

                background =
                    roundedBackground(
                        COLOR_CARD_ALT,
                        dp(12).toFloat()
                    )
            }

        logScroll.addView(
            logText
        )

        logCard.addView(
            logScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
            )
        )

        root.addView(
            logCard
        )

        val pageScroll =
            ScrollView(this).apply {

                isFillViewport =
                    true

                setBackgroundColor(
                    COLOR_BG
                )
            }

        pageScroll.addView(
            root
        )

        setContentView(
            pageScroll
        )
    }

    private fun requestBluetoothPermission() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            val granted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) ==
                    PackageManager.PERMISSION_GRANTED

            if (
                !granted
            ) {

                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                )

                return
            }
        }

        loadBondedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun loadBondedDevices() {

        devicesContainer.removeAllViews()

        if (
            !hasBluetoothConnectPermission()
        ) {

            setStatus(
                "Нет разрешения Bluetooth",
                COLOR_RED
            )

            return
        }

        val adapter =
            bluetoothAdapter

        if (
            adapter == null
        ) {

            setStatus(
                "Bluetooth не поддерживается",
                COLOR_RED
            )

            return
        }

        if (
            !adapter.isEnabled
        ) {

            setStatus(
                "Bluetooth выключен",
                COLOR_RED
            )

            deviceText.text =
                "Включи Bluetooth на телефоне"

            return
        }

        val devices =
            try {

                adapter
                    .bondedDevices
                    .sortedBy {

                        try {

                            it.name ?: ""

                        } catch (
                            _: SecurityException
                        ) {

                            ""
                        }
                    }

            } catch (
                _: SecurityException
            ) {

                emptyList()
            }

        if (
            devices.isEmpty()
        ) {

            setStatus(
                "Нет сопряжённых устройств",
                COLOR_ORANGE
            )

            deviceText.text =
                "DTCO не найден среди сопряжённых устройств"

            return
        }

        var dtcoFound =
            false

        devices.forEach {
                device ->

            val name =
                safeDeviceName(
                    device
                )

            val isDtco =
                name.contains(
                    "DTCO",
                    ignoreCase = true
                )

            if (
                isDtco
            ) {

                dtcoFound =
                    true
            }

            val button =
                createButton(
                    if (
                        isDtco
                    ) {
                        "DTCO: $name"
                    } else {
                        name
                    },
                    if (
                        isDtco
                    ) {
                        COLOR_BLUE
                    } else {
                        COLOR_CARD_ALT
                    }
                )

            button.setOnClickListener {

                selectedDevice =
                    device

                deviceText.text =
                    "Выбран: $name\n${safeDeviceAddress(device)}"

                connectButton.isEnabled =
                    true

                connectButton.alpha =
                    1f

                manualGattButton.isEnabled =
                    true

                manualGattButton.alpha =
                    1f

                setStatus(
                    "DTCO выбран",
                    COLOR_ORANGE
                )
            }

            devicesContainer.addView(
                button
            )

            devicesContainer.addView(
                verticalSpace(
                    dp(6)
                )
            )
        }

        if (
            dtcoFound
        ) {

            setStatus(
                "DTCO найден",
                COLOR_ORANGE
            )

        } else {

            setStatus(
                "Сопряжённые устройства загружены",
                COLOR_ORANGE
            )
        }
    }

    override fun onLogChanged(
        fullLog: String
    ) {

        runOnUiThread {

            if (
                ::logText.isInitialized
            ) {

                logText.text =
                    if (
                        fullLog.isBlank()
                    ) {
                        "Журнал пуст."
                    } else {
                        fullLog
                    }
            }
        }
    }

    override fun onConnectionStateChanged(
        connected: Boolean,
        deviceName: String?
    ) {

        runOnUiThread {

            if (
                connected
            ) {

                setStatus(
                    "Подключено",
                    COLOR_GREEN
                )

                deviceText.text =
                    "Подключено к ${deviceName ?: "DTCO"}"

            } else {

                setStatus(
                    "Отключено",
                    COLOR_ORANGE
                )
            }
        }
    }

    private fun setStatus(
        text: String,
        color: Int
    ) {

        if (
            ::statusText.isInitialized
        ) {

            statusText.text =
                text
        }

        if (
            ::statusDot.isInitialized
        ) {

            statusDot.background =
                roundedBackground(
                    color,
                    dp(20).toFloat()
                )
        }
    }

    private fun hasBluetoothConnectPermission():
        Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) ==
                PackageManager.PERMISSION_GRANTED

        } else {

            true
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
            _: SecurityException
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
            _: SecurityException
        ) {

            "Нет доступа"
        }
    }

    private fun createCard():
        LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(16),
                dp(14),
                dp(16),
                dp(14)
            )

            background =
                cardBackground(
                    dp(18).toFloat()
                )
        }
    }

    private fun labelText(
        text: String
    ): TextView {

        return TextView(this).apply {

            this.text =
                text

            textSize =
                12f

            setTextColor(
                COLOR_MUTED
            )

            setTypeface(
                typeface,
                Typeface.BOLD
            )
        }
    }

    private fun createButton(
        text: String,
        backgroundColor: Int
    ): Button {

        return Button(this).apply {

            this.text =
                text

            textSize =
                14f

            setTextColor(
                COLOR_TEXT
            )

            isAllCaps =
                false

            background =
                roundedBackground(
                    backgroundColor,
                    dp(12).toFloat(),
                    COLOR_BORDER
                )

            setPadding(
                dp(12),
                dp(10),
                dp(12),
                dp(10)
            )
        }
    }

    private fun createSmallButton(
        text: String
    ): Button {

        return Button(this).apply {

            this.text =
                text

            textSize =
                12f

            setTextColor(
                COLOR_TEXT
            )

            isAllCaps =
                false

            minHeight =
                0

            minimumHeight =
                0

            minWidth =
                0

            minimumWidth =
                0

            setPadding(
                dp(10),
                dp(5),
                dp(10),
                dp(5)
            )

            background =
                roundedBackground(
                    COLOR_CARD_ALT,
                    dp(10).toFloat(),
                    COLOR_BORDER
                )
        }
    }

    private fun verticalSpace(
        height: Int
    ): View {

        return View(this).apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    1,
                    height
                )
        }
    }

    private fun cardBackground(
        radius: Float
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                radius

            setColor(
                COLOR_CARD
            )

            setStroke(
                dp(1),
                COLOR_BORDER
            )
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Float,
        strokeColor: Int? = null
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                radius

            setColor(
                color
            )

            if (
                strokeColor != null
            ) {

                setStroke(
                    dp(1),
                    strokeColor
                )
            }
        }
    }
}
