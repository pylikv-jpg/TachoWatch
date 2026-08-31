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

class MainActivity : AppCompatActivity(),
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

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var logText: TextView

    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var refreshButton: Button
    private lateinit var clearButton: Button
    private lateinit var manualLogRefreshButton: Button

    private var selectedDevice:
        BluetoothDevice? = null

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .RequestMultiplePermissions()
        ) { result ->

            val granted =
                result.values.all { it }

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

        super.onCreate(savedInstanceState)

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

        diagnostic.disconnect()

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
                    dp(14)
                )

                setBackgroundColor(
                    COLOR_BG
                )
            }

        /*
         * HEADER
         */

        val titleRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val titleBlock =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL
            }

        val title =
            TextView(this).apply {

                text =
                    "TachoWatch"

                textSize =
                    26f

                setTextColor(
                    COLOR_TEXT
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }

        val subtitle =
            TextView(this).apply {

                text =
                    "DTCO Bluetooth diagnostics"

                textSize =
                    13f

                setTextColor(
                    COLOR_CYAN
                )
            }

        titleBlock.addView(
            title
        )

        titleBlock.addView(
            subtitle
        )

        val badge =
            TextView(this).apply {

                text =
                    "DTCO"

                textSize =
                    12f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    COLOR_TEXT
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setPadding(
                    dp(12),
                    dp(7),
                    dp(12),
                    dp(7)
                )

                background =
                    roundedBackground(
                        COLOR_BLUE,
                        dp(14).toFloat()
                    )
            }

        titleRow.addView(
            titleBlock,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        titleRow.addView(
            badge
        )

        root.addView(
            titleRow
        )

        root.addView(
            verticalSpace(
                dp(14)
            )
        )

        /*
         * STATUS
         */

        val statusCard =
            LinearLayout(this).apply {

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
                    dp(9),
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
            statusDot,
            LinearLayout.LayoutParams(
                dp(10),
                dp(10)
            )
        )

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

        /*
         * BLUETOOTH DEVICES
         */

        val deviceCard =
            LinearLayout(this).apply {

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

        deviceCard.addView(
            labelText(
                "СОПРЯЖЁННЫЕ BLUETOOTH-УСТРОЙСТВА"
            )
        )

        val hint =
            TextView(this).apply {

                text =
                    "Выбери DTCO. Если тахографа нет в списке, сначала выполни сопряжение в настройках Bluetooth Android."

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

        /*
         * CONTROLS
         */

        val controlCard =
            LinearLayout(this).apply {

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

        controlCard.addView(
            labelText(
                "ДИАГНОСТИКА"
            )
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

            if (device == null) {

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

        val controls =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            }

        controls.addView(
            connectButton
        )

        controls.addView(
            verticalSpace(
                dp(8)
            )
        )

        controls.addView(
            disconnectButton
        )

        controlCard.addView(
            controls
        )

        root.addView(
            controlCard
        )

        root.addView(
            verticalSpace(
                dp(12)
            )
        )

        /*
         * DIAGNOSTIC LOG
         */

        val logCard =
            LinearLayout(this).apply {

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

        val logHeader =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val logLabel =
            labelText(
                "ДИАГНОСТИЧЕСКИЙ ЖУРНАЛ"
            )

        logHeader.addView(
            logLabel,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        /*
         * НОВАЯ КНОПКА.
         *
         * Она вручную читает diagnostic.getLog()
         * прямо из памяти диагностического модуля.
         */

        manualLogRefreshButton =
            createSmallButton(
                "Обновить журнал"
            )

        manualLogRefreshButton.setOnClickListener {

            refreshLogManually()
        }

        logHeader.addView(
            manualLogRefreshButton
        )

        clearButton =
            createSmallButton(
                "Очистить"
            )

        clearButton.setOnClickListener {

            diagnostic.clearLog()

            logText.text =
                "Журнал очищен."
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
                dp(330)
            )
        )

        root.addView(
            logCard
        )

        val pageScroll =
            ScrollView(this).apply {

                isFillViewport =
                    true
            }

        pageScroll.addView(
            root
        )

        setContentView(
            pageScroll
        )
    }

    /*
     * НОВАЯ ФУНКЦИЯ.
     *
     * Никакого callback.
     * Никакого ожидания автоматического обновления.
     *
     * Просто берём текущее содержимое памяти.
     */
    private fun refreshLogManually() {

        val currentLog =
            try {

                diagnostic.getLog()

            } catch (
                e: Throwable
            ) {

                "ОШИБКА ручного чтения журнала:\n" +
                    "${e.javaClass.simpleName}: ${e.message}"
            }

        logText.text =
            if (
                currentLog.isBlank()
            ) {

                "Журнал пока пуст."

            } else {

                currentLog
            }
    }

    private fun requestBluetoothPermission() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            val connectGranted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) ==
                    PackageManager.PERMISSION_GRANTED

            if (
                !connectGranted
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

        if (
            bluetoothAdapter == null
        ) {

            setStatus(
                "Bluetooth не поддерживается",
                COLOR_RED
            )

            return
        }

        if (
            !bluetoothAdapter.isEnabled
        ) {

            setStatus(
                "Bluetooth выключен",
                COLOR_RED
            )

            deviceText.text =
                "Включи Bluetooth на телефоне"

            return
        }

        val bonded =
            try {

                bluetoothAdapter
                    .bondedDevices
                    .toList()

            } catch (
                e: SecurityException
            ) {

                emptyList()
            }

        if (
            bonded.isEmpty()
        ) {

            setStatus(
                "Нет сопряжённых устройств",
                COLOR_ORANGE
            )

            deviceText.text =
                "Сначала сопряги телефон с DTCO"

            val empty =
                TextView(this).apply {

                    text =
                        "Сопряжённые Bluetooth-устройства не найдены."

                    textSize =
                        14f

                    setTextColor(
                        COLOR_MUTED
                    )

                    setPadding(
                        0,
                        dp(8),
                        0,
                        dp(8)
                    )
                }

            devicesContainer.addView(
                empty
            )

            return
        }

        val sorted =
            bonded.sortedWith(
                compareByDescending<BluetoothDevice> {

                    val name =
                        safeDeviceName(
                            it
                        ).uppercase()

                    name.contains(
                        "DTCO"
                    ) ||
                        name.contains(
                            "VDO"
                        )

                }.thenBy {

                    safeDeviceName(
                        it
                    )
                }
            )

        sorted.forEach {
                device ->

            addDeviceButton(
                device
            )
        }

        val dtco =
            sorted.firstOrNull {

                val name =
                    safeDeviceName(
                        it
                    ).uppercase()

                name.contains(
                    "DTCO"
                ) ||
                    name.contains(
                        "VDO"
                    )
            }

        if (
            dtco != null
        ) {

            selectDevice(
                dtco
            )

            setStatus(
                "DTCO найден",
                COLOR_GREEN
            )

        } else {

            setStatus(
                "Выбери устройство",
                COLOR_ORANGE
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun addDeviceButton(
        device: BluetoothDevice
    ) {

        val name =
            safeDeviceName(
                device
            )

        val address =
            try {

                device.address

            } catch (
                e: SecurityException
            ) {

                "Адрес недоступен"
            }

        val type =
            when (
                device.type
            ) {

                BluetoothDevice.DEVICE_TYPE_CLASSIC ->
                    "Classic"

                BluetoothDevice.DEVICE_TYPE_LE ->
                    "BLE"

                BluetoothDevice.DEVICE_TYPE_DUAL ->
                    "Dual"

                else ->
                    "Unknown"
            }

        val isDtco =
            name.uppercase()
                .contains(
                    "DTCO"
                ) ||
                name.uppercase()
                    .contains(
                        "VDO"
                    )

        val button =
            createButton(
                "$name\n$address  •  $type",
                if (
                    isDtco
                ) {
                    COLOR_BLUE
                } else {
                    COLOR_CARD_ALT
                }
            )

        button.gravity =
            Gravity.START or
                Gravity.CENTER_VERTICAL

        button.setOnClickListener {

            selectDevice(
                device
            )
        }

        devicesContainer.addView(
            button
        )

        devicesContainer.addView(
            verticalSpace(
                dp(7)
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun selectDevice(
        device: BluetoothDevice
    ) {

        selectedDevice =
            device

        val name =
            safeDeviceName(
                device
            )

        val address =
            try {

                device.address

            } catch (
                e: SecurityException
            ) {

                "Адрес недоступен"
            }

        val bond =
            when (
                device.bondState
            ) {

                BluetoothDevice.BOND_BONDED ->
                    "сопряжено"

                BluetoothDevice.BOND_BONDING ->
                    "сопряжение..."

                else ->
                    "не сопряжено"
            }

        deviceText.text =
            "$name\n$address • $bond"

        connectButton.isEnabled =
            true

        connectButton.alpha =
            1f

        setStatus(
            "Устройство выбрано",
            COLOR_CYAN
        )
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
                ?: "Bluetooth без имени"

        } catch (
            e: SecurityException
        ) {

            "Bluetooth-устройство"
        }
    }

    /*
     * Автоматическое обновление оставляем.
     *
     * Но теперь оно не является единственным
     * способом увидеть настоящий журнал.
     */
    override fun onLogChanged(
        fullLog: String
    ) {

        runOnUiThread {

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

    override fun onConnectionStateChanged(
        connected: Boolean,
        deviceName: String?
    ) {

        runOnUiThread {

            if (
                connected
            ) {

                setStatus(
                    "DTCO подключён",
                    COLOR_GREEN
                )

                if (
                    !deviceName.isNullOrBlank()
                ) {

                    deviceText.text =
                        "$deviceName\nGATT соединение активно"
                }

            } else {

                setStatus(
                    "DTCO отключён",
                    COLOR_ORANGE
                )
            }
        }
    }

    private fun setStatus(
        text: String,
        color: Int
    ) {

        runOnUiThread {

            statusText.text =
                text

            statusDot.background =
                roundedBackground(
                    color,
                    dp(20).toFloat()
                )
        }
    }

    private fun labelText(
        value: String
    ): TextView {

        return TextView(this).apply {

            text =
                value

            textSize =
                11f

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
        title: String,
        color: Int
    ): Button {

        return Button(this).apply {

            text =
                title

            textSize =
                14f

            isAllCaps =
                false

            gravity =
                Gravity.CENTER

            setTextColor(
                COLOR_TEXT
            )

            setTypeface(
                typeface,
                Typeface.BOLD
            )

            setPadding(
                dp(14),
                dp(10),
                dp(14),
                dp(10)
            )

            background =
                roundedBackground(
                    color,
                    dp(13).toFloat(),
                    COLOR_BORDER
                )

            minHeight =
                dp(48)
        }
    }

    private fun createSmallButton(
        title: String
    ): Button {

        return Button(this).apply {

            text =
                title

            textSize =
                11f

            isAllCaps =
                false

            setTextColor(
                COLOR_TEXT
            )

            setPadding(
                dp(10),
                dp(4),
                dp(10),
                dp(4)
            )

            minHeight =
                0

            minimumHeight =
                0

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

        return roundedBackground(
            COLOR_CARD,
            radius,
            COLOR_BORDER
        )
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
