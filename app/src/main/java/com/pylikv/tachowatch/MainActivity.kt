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

    private lateinit var logScroll:
        ScrollView

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

    /*
     * Последняя версия полного журнала,
     * которую мы уже вывели на экран.
     *
     * Нужна для того, чтобы НЕ заменять
     * весь TextView при каждом сообщении,
     * а добавлять только новые строки.
     */
    private var renderedLog =
        ""

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
                    dp(12),
                    dp(10),
                    dp(12),
                    dp(10)
                )

                setBackgroundColor(
                    COLOR_BG
                )
            }

        /*
         * ЗАГОЛОВОК
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
                    24f

                setTextColor(
                    COLOR_TEXT
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }

        titleBlock.addView(
            title
        )

        val subtitle =
            TextView(this).apply {

                text =
                    "DTCO Bluetooth diagnostics — AUTO GATT"

                textSize =
                    12f

                setTextColor(
                    COLOR_CYAN
                )
            }

        titleBlock.addView(
            subtitle
        )

        titleRow.addView(
            titleBlock,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(
            titleRow
        )

        root.addView(
            verticalSpace(
                dp(8)
            )
        )

        /*
         * СТАТУС
         */

        val statusCard =
            createCard(
                dp(12)
            )

        val statusTopRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        statusTopRow.addView(
            labelText(
                "СОЕДИНЕНИЕ"
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        statusDot =
            View(this).apply {

                background =
                    roundedBackground(
                        COLOR_ORANGE,
                        dp(20).toFloat()
                    )
            }

        statusTopRow.addView(
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
                    15f

                setTextColor(
                    COLOR_TEXT
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setPadding(
                    dp(8),
                    0,
                    0,
                    0
                )
            }

        statusTopRow.addView(
            statusText
        )

        statusCard.addView(
            statusTopRow
        )

        deviceText =
            TextView(this).apply {

                text =
                    "DTCO пока не выбран"

                textSize =
                    12f

                setTextColor(
                    COLOR_MUTED
                )

                setPadding(
                    0,
                    dp(5),
                    0,
                    0
                )

                maxLines =
                    2
            }

        statusCard.addView(
            deviceText
        )

        root.addView(
            statusCard
        )

        root.addView(
            verticalSpace(
                dp(7)
            )
        )

        /*
         * СОПРЯЖЁННЫЕ УСТРОЙСТВА
         */

        val deviceCard =
            createCard(
                dp(10)
            )

        val deviceHeader =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        deviceHeader.addView(
            labelText(
                "СОПРЯЖЁННЫЕ УСТРОЙСТВА"
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        refreshButton =
            createSmallButton(
                "Обновить"
            )

        refreshButton.setOnClickListener {

            loadBondedDevices()
        }

        deviceHeader.addView(
            refreshButton
        )

        deviceCard.addView(
            deviceHeader
        )

        val devicesScroll =
            ScrollView(this).apply {

                isFillViewport =
                    false

                isNestedScrollingEnabled =
                    true

                setPadding(
                    0,
                    dp(5),
                    0,
                    0
                )
            }

        devicesContainer =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL
            }

        devicesScroll.addView(
            devicesContainer
        )

        deviceCard.addView(
            devicesScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(145)
            )
        )

        root.addView(
            deviceCard
        )

        root.addView(
            verticalSpace(
                dp(7)
            )
        )

        /*
         * УПРАВЛЕНИЕ
         */

        val controlCard =
            createCard(
                dp(10)
            )

        val controlHeader =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        controlHeader.addView(
            labelText(
                "ДИАГНОСТИКА BLE / GATT"
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val safeText =
            TextView(this).apply {

                text =
                    "БЕЗ ЗАПИСИ"

                textSize =
                    11f

                setTextColor(
                    COLOR_GREEN
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }

        controlHeader.addView(
            safeText
        )

        controlCard.addView(
            controlHeader
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

            /*
             * Начинается новый диагностический журнал.
             * Сбрасываем локальную копию.
             */
            renderedLog =
                ""

            logText.text =
                ""

            logScroll.scrollTo(
                0,
                0
            )

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
                dp(6)
            )
        )

        controlCard.addView(
            connectButton
        )

        controlCard.addView(
            verticalSpace(
                dp(5)
            )
        )

        val secondButtonRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        secondButtonRow.addView(
            manualGattButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        secondButtonRow.addView(
            horizontalSpace(
                dp(6)
            )
        )

        secondButtonRow.addView(
            disconnectButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        controlCard.addView(
            secondButtonRow
        )

        root.addView(
            controlCard
        )

        root.addView(
            verticalSpace(
                dp(7)
            )
        )

        /*
         * ДИАГНОСТИЧЕСКИЙ ЖУРНАЛ
         */

        val logCard =
            createCard(
                dp(10)
            )

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

            /*
             * Сбрасываем также локальную копию,
             * чтобы следующий журнал строился заново.
             */
            renderedLog =
                ""

            logText.text =
                ""

            diagnostic.clearLog()
        }

        logHeader.addView(
            clearButton
        )

        logCard.addView(
            logHeader
        )

        logScroll =
            ScrollView(this).apply {

                isFillViewport =
                    false

                isNestedScrollingEnabled =
                    true

                isVerticalScrollBarEnabled =
                    true

                isScrollbarFadingEnabled =
                    false

                isSmoothScrollingEnabled =
                    false

                setPadding(
                    0,
                    dp(5),
                    0,
                    0
                )
            }

        logText =
            TextView(this).apply {

                text =
                    "Ожидание запуска диагностики..."

                textSize =
                    11.5f

                setTextColor(
                    COLOR_TEXT
                )

                /*
                 * ВАЖНО:
                 * отключаем выделение текста.
                 *
                 * Selectable TextView внутри ScrollView
                 * может перехватывать жесты и менять
                 * положение прокрутки.
                 */
                setTextIsSelectable(
                    false
                )

                isFocusable =
                    false

                isFocusableInTouchMode =
                    false

                typeface =
                    Typeface.MONOSPACE

                setLineSpacing(
                    0f,
                    1.08f
                )

                setPadding(
                    dp(10),
                    dp(10),
                    dp(10),
                    dp(10)
                )

                background =
                    roundedBackground(
                        COLOR_CARD_ALT,
                        dp(10).toFloat()
                    )
            }

        logScroll.addView(
            logText
        )

        logCard.addView(
            logScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            logCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(
            root
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
                "Включи Bluetooth"

            return
        }

        val devices =
            try {

                adapter
                    .bondedDevices
                    .sortedWith(
                        compareByDescending<BluetoothDevice> {

                            val name =
                                try {
                                    it.name ?: ""
                                } catch (
                                    _: SecurityException
                                ) {
                                    ""
                                }

                            name.contains(
                                "DTCO",
                                ignoreCase = true
                            )
                        }.thenBy {

                            try {
                                it.name ?: ""
                            } catch (
                                _: SecurityException
                            ) {
                                ""
                            }
                        }
                    )

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
                "DTCO не найден"

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
                createDeviceButton(
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
                    "Выбран: $name   ${safeDeviceAddress(device)}"

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
                    dp(4)
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
                "Устройства загружены",
                COLOR_ORANGE
            )
        }
    }

    /*
     * КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ ЖУРНАЛА.
     *
     * DtcoBluetoothDiagnostic присылает каждый раз
     * весь полный журнал.
     *
     * Раньше мы делали:
     *
     *     logText.text = fullLog
     *
     * То есть Android полностью пересоздавал layout
     * большого TextView на каждом сообщении.
     * Из-за этого ScrollView мог сбрасываться.
     *
     * Теперь при нормальном росте журнала
     * мы вычисляем только ДОБАВИВШУЮСЯ часть
     * и вставляем её через append().
     *
     * Старый текст вообще не меняется.
     */
    override fun onLogChanged(
        fullLog: String
    ) {

        runOnUiThread {

            if (
                !::logText.isInitialized ||
                !::logScroll.isInitialized
            ) {

                return@runOnUiThread
            }

            val newLog =

                if (
                    fullLog.isBlank()
                ) {

                    "Журнал пуст."

                } else {

                    fullLog
                }

            /*
             * Проверяем, был ли пользователь
             * у самого низа ДО добавления текста.
             */
            val child =
                logScroll.getChildAt(
                    0
                )

            val maxScroll =

                if (
                    child != null
                ) {

                    (
                        child.height -
                            logScroll.height
                        ).coerceAtLeast(
                        0
                    )

                } else {

                    0
                }

            val wasAtBottom =
                maxScroll <= 0 ||
                    logScroll.scrollY >=
                    maxScroll - dp(24)

            /*
             * Обычный случай:
             * новый журнал является продолжением старого.
             */
            if (
                renderedLog.isNotEmpty() &&
                newLog.startsWith(
                    renderedLog
                )
            ) {

                val addition =
                    newLog.substring(
                        renderedLog.length
                    )

                if (
                    addition.isNotEmpty()
                ) {

                    logText.append(
                        addition
                    )
                }

                renderedLog =
                    newLog

            } else {

                /*
                 * Журнал был очищен,
                 * запущена новая диагностика
                 * или произошла другая смена содержимого.
                 *
                 * Только в этом случае заменяем
                 * TextView полностью.
                 */
                renderedLog =
                    newLog

                logText.text =
                    newLog
            }

            /*
             * Если пользователь сам находится
             * где-то в середине журнала —
             * НИЧЕГО НЕ ДЕЛАЕМ.
             *
             * Его положение ScrollView не меняется.
             *
             * Автопрокрутка работает только тогда,
             * когда пользователь уже был внизу.
             */
            if (
                wasAtBottom
            ) {

                logScroll.post {

                    logScroll.fullScroll(
                        View.FOCUS_DOWN
                    )
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

    private fun createCard(
        padding: Int
    ): LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                padding,
                padding,
                padding,
                padding
            )

            background =
                cardBackground(
                    dp(16).toFloat()
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
                11.5f

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
                13f

            setTextColor(
                COLOR_TEXT
            )

            isAllCaps =
                false

            minHeight =
                0

            minimumHeight =
                0

            background =
                roundedBackground(
                    backgroundColor,
                    dp(10).toFloat(),
                    COLOR_BORDER
                )

            setPadding(
                dp(10),
                dp(7),
                dp(10),
                dp(7)
            )
        }
    }

    private fun createDeviceButton(
        text: String,
        backgroundColor: Int
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

            background =
                roundedBackground(
                    backgroundColor,
                    dp(9).toFloat(),
                    COLOR_BORDER
                )

            setPadding(
                dp(8),
                dp(4),
                dp(8),
                dp(4)
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
                11f

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
                dp(9),
                dp(4),
                dp(9),
                dp(4)
            )

            background =
                roundedBackground(
                    COLOR_CARD_ALT,
                    dp(9).toFloat(),
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

    private fun horizontalSpace(
        width: Int
    ): View {

        return View(this).apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    width,
                    1
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
