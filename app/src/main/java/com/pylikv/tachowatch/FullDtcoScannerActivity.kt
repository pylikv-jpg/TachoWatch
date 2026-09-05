package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Read-only DTCO scanner.
 *
 * It deliberately avoids activity changes, card writes and meaningful Download-FIFO
 * commands. The only writes are BLE CCCD subscription writes, flow-control credits,
 * the Remote-HMI open/status routine and UDS ReadDataByIdentifier (0x22).
 */
class FullDtcoScannerActivity : AppCompatActivity(), FullDtcoScanner.Listener {

    private lateinit var statusView: TextView
    private lateinit var progressView: TextView
    private lateinit var logView: TextView
    private lateinit var pickButton: Button
    private lateinit var fastButton: Button
    private lateinit var fullButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveButton: Button

    private var selectedDevice: BluetoothDevice? = null
    private var scanner: FullDtcoScanner? = null
    private var latestLog = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshButtons()
        }

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(latestLog) }
                statusView.text = "Отчёт сохранён"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "DTCO Full Scanner"
        setContentView(buildUi())
        scanner = FullDtcoScanner(this, this)
        requestNeededPermissions()
        refreshButtons()
    }

    override fun onDestroy() {
        scanner?.stop()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val title = TextView(this).apply {
            text = "DTCO FULL READ-ONLY SCANNER"
            textSize = 20f
        }
        statusView = TextView(this).apply {
            text = "Не подключено"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(4))
        }
        progressView = TextView(this).apply {
            text = "Выбери сопряжённый DTCO"
            setPadding(0, 0, 0, dp(8))
        }

        pickButton = Button(this).apply {
            text = "Выбрать DTCO"
            setOnClickListener { chooseBondedDevice() }
        }
        fastButton = Button(this).apply {
            text = "Быстрый тест F900–F9FF"
            setOnClickListener { startScan(0xF900, 0xF9FF) }
        }
        fullButton = Button(this).apply {
            text = "ПОЛНЫЙ ТЕСТ F000–FFFF"
            setOnClickListener { startScan(0xF000, 0xFFFF) }
        }
        stopButton = Button(this).apply {
            text = "Остановить"
            setOnClickListener { scanner?.stop() }
        }
        saveButton = Button(this).apply {
            text = "Сохранить отчёт"
            setOnClickListener {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                saveLauncher.launch("DTCO_full_scan_$stamp.txt")
            }
        }

        val info = TextView(this).apply {
            text = "Тестер сначала перечисляет ВСЕ GATT-сервисы и характеристики, читает все разрешённые READ, подписывается на NOTIFY/INDICATE, затем открывает Remote HMI и перебирает DID. Данные карты и режимы водителя не изменяет."
            setPadding(0, dp(8), 0, dp(8))
        }

        logView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(title)
        root.addView(statusView)
        root.addView(progressView)
        root.addView(pickButton)
        root.addView(fastButton)
        root.addView(fullButton)
        root.addView(stopButton)
        root.addView(saveButton)
        root.addView(info)
        root.addView(scroll)
        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun requestNeededPermissions() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing += Manifest.permission.BLUETOOTH_SCAN
            }
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun chooseBondedDevice() {
        if (!hasConnectPermission()) {
            requestNeededPermissions()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val devices = adapter?.bondedDevices?.sortedBy { safeName(it) }.orEmpty()
        val preferred = devices.filter { safeName(it).contains("DTCO", ignoreCase = true) }
        val list = if (preferred.isNotEmpty()) preferred else devices
        if (list.isEmpty()) {
            statusView.text = "Нет сопряжённых Bluetooth-устройств"
            return
        }
        val labels = list.map { "${safeName(it)}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выбери тахограф")
            .setItems(labels) { _, which ->
                selectedDevice = list[which]
                statusView.text = "Выбран: ${safeName(list[which])}"
                refreshButtons()
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String =
        try { device.name ?: "Без имени" } catch (_: Throwable) { "Нет доступа" }

    private fun startScan(startDid: Int, endDid: Int) {
        val device = selectedDevice
        if (device == null) {
            chooseBondedDevice()
            return
        }
        latestLog = ""
        logView.text = ""
        scanner?.start(device, startDid, endDid)
    }

    private fun refreshButtons() {
        val ready = hasConnectPermission() && selectedDevice != null
        fastButton.isEnabled = ready
        fullButton.isEnabled = ready
        stopButton.isEnabled = true
        saveButton.isEnabled = latestLog.isNotBlank()
    }

    override fun onScannerLog(fullLog: String) {
        latestLog = fullLog
        logView.text = fullLog.takeLast(120_000)
        saveButton.isEnabled = fullLog.isNotBlank()
    }

    override fun onScannerStatus(status: String) {
        statusView.text = status
    }

    override fun onScannerProgress(text: String) {
        progressView.text = text
    }
}

class FullDtcoScanner(
    private val activity: AppCompatActivity,
    private val listener: Listener
) {
    interface Listener {
        fun onScannerLog(fullLog: String)
        fun onScannerStatus(status: String)
        fun onScannerProgress(text: String)
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val DIAG_SERVICE = UUID.fromString("fa213def-aef4-475c-bcea-0a8d69073efc")
        private val DIAG_FIFO = UUID.fromString("e413960c-75ba-4ca9-8a67-99bc052a1b13")
        private val DIAG_CREDITS = UUID.fromString("e168d1a6-304f-42b4-ab96-4cd1d4efebd9")
        private val DOWNLOAD_SERVICE = UUID.fromString("eef90782-55dd-4388-b80b-695aba7a69b5")
        private val DOWNLOAD_FIFO = UUID.fromString("29d3a479-1592-47df-80a4-afa742d369bb")
        private val DOWNLOAD_CREDITS = UUID.fromString("db9c4128-bff3-41fe-a306-fb6f9a8aeb2d")
        private const val DID_TIMEOUT_MS = 900L
        private const val STEP_DELAY_MS = 70L
        private const val MAX_LOG_LINES = 12000
    }

    private enum class Phase { IDLE, SUBSCRIBE, GATT_READ, HANDSHAKE, DID_SCAN, DONE }
    private data class Sub(val characteristic: BluetoothGattCharacteristic, val value: ByteArray)

    private val handler = Handler(Looper.getMainLooper())
    private val lines = CopyOnWriteArrayList<String>()
    private val subscribeQueue = ArrayDeque<Sub>()
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val lastDidData = linkedMapOf<Int, ByteArray>()
    private val positiveDids = linkedMapOf<Int, ByteArray>()
    private val nrcCounts = linkedMapOf<Int, Int>()

    private var gatt: BluetoothGatt? = null
    private var phase = Phase.IDLE
    private var running = false
    private var txCredits = 0
    private var openSent = false
    private var statusSent = false
    private var rhmiOpen = false
    private var startDid = 0xF900
    private var endDid = 0xF9FF
    private var currentDid = 0xF900
    private var waitingDid = false
    private var didToken = 0L
    private var positiveCount = 0
    private var negativeCount = 0
    private var timeoutCount = 0

    private val knownNames = mapOf(
        0xF902 to "TachographVehicleSpeed",
        0xF903 to "Driver1WorkingState",
        0xF904 to "Driver2WorkingState",
        0xF905 to "DriveRecognize",
        0xF906 to "Driver1TimeRelatedStates",
        0xF907 to "DriverCardDriver1",
        0xF923 to "Driver1ContinuousDrivingTime",
        0xF925 to "Driver1CumulativeBreakTime",
        0xF927 to "Driver1CurrentDurationOfSelectedActivity",
        0xF931 to "Driver1Name",
        0xF938 to "Driver1CumulatedDrivingTimePreviousAndCurrentWeek",
        0xF997 to "Driver1EndOfLastDailyRestPeriod",
        0xF998 to "Driver1EndOfLastWeeklyRestPeriod",
        0xF999 to "Driver1EndOfSecondLastWeeklyRestPeriod",
        0xF99A to "Driver1CurrentDailyDrivingTime",
        0xF99B to "Driver1CurrentWeeklyDrivingTime",
        0xF99C to "Driver1TimeLeftUntilNewDailyRestPeriod",
        0xF9A0 to "Driver1NumberOfTimes9hDailyDrivingTimesExceeded",
        0xF9A1 to "Driver1TimeLeftUntilNewWeeklyRestPeriod",
        0xF9A2 to "Driver1CumulativeUninterruptedRestTime",
        0xF9A3 to "Driver1MinimumDailyRest",
        0xF9A4 to "Driver1MinimumWeeklyRest",
        0xF9A5 to "Driver1MaximumDailyPeriod",
        0xF9A6 to "Driver1MaximumDailyDrivingTime",
        0xF9AB to "Driver1NumberOfUsedReducedDailyRestPeriods",
        0xF9AD to "Driver1RemainingCurrentDrivingTime",
        0xF9AF to "Driver1RemainingDrivingTimeOnCurrentShift",
        0xF9B1 to "Driver1RemainingDrivingTimeOfCurrentWeek",
        0xF9B3 to "Driver1Remaining2WeeksDrivingTime",
        0xF9B5 to "Driver1TimeLeftUntilNextDrivingPeriod",
        0xF9B7 to "Driver1DurationOfNextDrivingPeriod",
        0xF9B9 to "Driver1DurationOfNextBreakRest",
        0xF9C0 to "Driver1RemainingTimeOfCurrentBreakRest",
        0xF9C2 to "Driver1RemainingTimeUntilNextBreakOrRest"
    )

    @SuppressLint("MissingPermission")
    fun start(device: BluetoothDevice, start: Int, end: Int) {
        stop()
        lines.clear()
        lastDidData.clear()
        positiveDids.clear()
        nrcCounts.clear()
        startDid = start.coerceIn(0, 0xFFFF)
        endDid = end.coerceIn(startDid, 0xFFFF)
        currentDid = startDid
        positiveCount = 0
        negativeCount = 0
        timeoutCount = 0
        txCredits = 0
        openSent = false
        statusSent = false
        rhmiOpen = false
        waitingDid = false
        running = true
        phase = Phase.IDLE
        log("============================================================")
        log("DTCO FULL READ-ONLY SCANNER")
        log("DID RANGE: ${h4(startDid)}-${h4(endDid)} (${endDid - startDid + 1} identifiers)")
        log("SAFE MODE: UDS 0x22 only; no activity/card/download commands")
        log("============================================================")
        listener.onScannerStatus("Подключение к ${safeName(device)}")
        gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                device.connectGatt(activity, false, callback, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(activity, false, callback)
        } catch (t: Throwable) {
            log("connectGatt ERROR: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        waitingDid = false
        didToken++
        handler.removeCallbacksAndMessages(null)
        val old = gatt
        gatt = null
        try { old?.disconnect() } catch (_: Throwable) {}
        try { old?.close() } catch (_: Throwable) {}
        if (phase != Phase.IDLE && phase != Phase.DONE) log("SCAN STOPPED")
        phase = Phase.IDLE
        listener.onScannerStatus("Остановлено")
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("GATT STATE status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                listener.onScannerStatus("GATT подключён")
                try {
                    if (!g.requestMtu(512)) g.discoverServices()
                } catch (_: Throwable) {
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onScannerStatus("Связь разорвана")
                running = false
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU=$mtu status=$status")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("SERVICE DISCOVERY status=$status services=${g.services.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            prepareGattInventory(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("CCCD ${descriptor.characteristic.uuid} status=$status")
            if (phase == Phase.SUBSCRIBE) handler.postDelayed({ subscribeNext(g) }, STEP_DELAY_MS)
        }

        @Deprecated("legacy")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION") val v = c.value ?: byteArrayOf()
                handleGattRead(g, c, v, status)
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleGattRead(g, c, value, status)
        }

        @Deprecated("legacy")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION") incoming(g, c, c.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming(g, c, value)
        }
    }

    private fun prepareGattInventory(g: BluetoothGatt) {
        subscribeQueue.clear()
        readQueue.clear()
        log("---------------- GATT INVENTORY ----------------")
        g.services.forEach { service ->
            log("SERVICE ${service.uuid}")
            service.characteristics.forEach { c ->
                val p = c.properties
                val flags = buildList {
                    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
                    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
                    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                }.joinToString("|")
                log("  CHAR ${c.uuid} props=$flags descriptors=${c.descriptors.size}")
                if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) readQueue.addLast(c)
                val cccd = c.getDescriptor(CCCD)
                if (cccd != null) {
                    when {
                        p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                            subscribeQueue.addLast(Sub(c, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
                        p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                            subscribeQueue.addLast(Sub(c, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
                    }
                }
            }
        }
        log("GATT readable=${readQueue.size}, subscribable=${subscribeQueue.size}")
        phase = Phase.SUBSCRIBE
        subscribeNext(g)
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNext(g: BluetoothGatt) {
        if (!running || phase != Phase.SUBSCRIBE) return
        val item = if (subscribeQueue.isEmpty()) null else subscribeQueue.removeFirst()
        if (item == null) {
            phase = Phase.GATT_READ
            readNextGatt(g)
            return
        }
        val c = item.characteristic
        val d = c.getDescriptor(CCCD)
        if (d == null || !g.setCharacteristicNotification(c, true)) {
            handler.postDelayed({ subscribeNext(g) }, STEP_DELAY_MS)
            return
        }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, item.value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") d.value = item.value
            @Suppress("DEPRECATION") g.writeDescriptor(d)
        }
        if (!ok) handler.postDelayed({ subscribeNext(g) }, STEP_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun readNextGatt(g: BluetoothGatt) {
        if (!running || phase != Phase.GATT_READ) return
        val c = if (readQueue.isEmpty()) null else readQueue.removeFirst()
        if (c == null) {
            log("---------------- GATT READ COMPLETE ----------------")
            beginHandshake(g)
            return
        }
        val ok = try { g.readCharacteristic(c) } catch (_: Throwable) { false }
        if (!ok) {
            log("GATT READ START FAILED ${c.uuid}")
            handler.postDelayed({ readNextGatt(g) }, STEP_DELAY_MS)
        }
    }

    private fun handleGattRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, status: Int) {
        log("GATT READ ${c.uuid} status=$status len=${v.size} hex=${hex(v)} ascii=${ascii(v)}")
        if (phase == Phase.GATT_READ) handler.postDelayed({ readNextGatt(g) }, STEP_DELAY_MS)
    }

    private fun beginHandshake(g: BluetoothGatt) {
        phase = Phase.HANDSHAKE
        val diag = g.getService(DIAG_SERVICE)
        if (diag == null) {
            log("DIAGNOSTICS SERVICE NOT FOUND")
            finish()
            return
        }
        log("---------------- REMOTE HMI HANDSHAKE ----------------")
        sendCredit(g, DIAG_SERVICE, DIAG_CREDITS)
        if (g.getService(DOWNLOAD_SERVICE) != null) {
            sendCredit(g, DOWNLOAD_SERVICE, DOWNLOAD_CREDITS)
            log("Download service detected; only flow-control/notifications, no download command")
        }
    }

    private fun incoming(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
        val label = when (c.uuid) {
            DIAG_FIFO -> "DIAG_FIFO"
            DIAG_CREDITS -> "DIAG_CREDITS"
            DOWNLOAD_FIFO -> "DOWNLOAD_FIFO"
            DOWNLOAD_CREDITS -> "DOWNLOAD_CREDITS"
            else -> "NOTIFY"
        }
        log("RX $label uuid=${c.uuid} len=${v.size} hex=${hex(v)}")

        if (c.uuid == DIAG_CREDITS) {
            if (v.isNotEmpty()) {
                txCredits += u(v[0])
                if (!openSent && txCredits > 0) sendOpen(g)
                else if (phase == Phase.DID_SCAN && !waitingDid && txCredits > 0) sendCurrentDid(g)
            }
            return
        }
        if (c.uuid != DIAG_FIFO || v.size < 3) return
        val a = v.copyOfRange(2, v.size)

        if (a.size >= 4 && u(a[0]) == 0x71 && u(a[1]) == 0x01 && u(a[2]) == 0xF2 && u(a[3]) == 0x11) {
            log("OPEN RHMI POSITIVE")
            sendCredit(g, DIAG_SERVICE, DIAG_CREDITS)
            handler.postDelayed({ sendStatus(g) }, 120L)
            return
        }
        if (a.size >= 5 && u(a[0]) == 0x71 && u(a[1]) == 0x03 && u(a[2]) == 0xF2 && u(a[3]) == 0x11) {
            val s = u(a[4])
            log("RHMI STATUS=0x${h2(s)}")
            if (s == 0x10) {
                rhmiOpen = true
                phase = Phase.DID_SCAN
                listener.onScannerStatus("Remote HMI открыт — идёт DID scan")
                sendCredit(g, DIAG_SERVICE, DIAG_CREDITS)
            } else {
                log("RHMI NOT OPEN; scan cannot continue")
                finish()
            }
            return
        }
        if (a.size >= 3 && u(a[0]) == 0x62) {
            val did = (u(a[1]) shl 8) or u(a[2])
            val data = if (a.size > 3) a.copyOfRange(3, a.size) else byteArrayOf()
            handlePositiveDid(g, did, data)
            return
        }
        if (a.size >= 3 && u(a[0]) == 0x7F && u(a[1]) == 0x22) {
            handleNegativeDid(g, u(a[2]))
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCredit(g: BluetoothGatt, serviceUuid: UUID, charUuid: UUID) {
        val c = g.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        writeNoResponse(g, c, byteArrayOf(1))
    }

    @SuppressLint("MissingPermission")
    private fun sendOpen(g: BluetoothGatt) {
        if (openSent || txCredits <= 0) return
        val fifo = g.getService(DIAG_SERVICE)?.getCharacteristic(DIAG_FIFO) ?: return
        val packet = byteArrayOf(1, 1, 0x31, 0x01, 0xF2.toByte(), 0x11)
        if (writeNoResponse(g, fifo, packet)) {
            openSent = true
            txCredits--
            log("TX OPEN RHMI: ${hex(packet)}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendStatus(g: BluetoothGatt) {
        if (statusSent || txCredits <= 0) {
            if (!statusSent) {
                sendCredit(g, DIAG_SERVICE, DIAG_CREDITS)
                handler.postDelayed({ sendStatus(g) }, 180L)
            }
            return
        }
        val fifo = g.getService(DIAG_SERVICE)?.getCharacteristic(DIAG_FIFO) ?: return
        val packet = byteArrayOf(1, 1, 0x31, 0x03, 0xF2.toByte(), 0x11)
        if (writeNoResponse(g, fifo, packet)) {
            statusSent = true
            txCredits--
            log("TX RHMI STATUS: ${hex(packet)}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCurrentDid(g: BluetoothGatt) {
        if (!running || !rhmiOpen || phase != Phase.DID_SCAN || waitingDid) return
        if (currentDid > endDid) {
            finish()
            return
        }
        if (txCredits <= 0) {
            sendCredit(g, DIAG_SERVICE, DIAG_CREDITS)
            return
        }
        val fifo = g.getService(DIAG_SERVICE)?.getCharacteristic(DIAG_FIFO) ?: return
        val did = currentDid
        val packet = byteArrayOf(1, 1, 0x22, (did shr 8).toByte(), did.toByte())
        if (writeNoResponse(g, fifo, packet)) {
            txCredits--
            waitingDid = true
            val token = ++didToken
            val done = did - startDid
            val total = endDid - startDid + 1
            if (done % 16 == 0) {
                listener.onScannerProgress("DID ${h4(did)} • ${done + 1}/$total • +$positiveCount / NRC $negativeCount / timeout $timeoutCount")
            }
            handler.postDelayed({
                if (running && phase == Phase.DID_SCAN && waitingDid && token == didToken) {
                    timeoutCount++
                    log("DID ${h4(did)} TIMEOUT")
                    waitingDid = false
                    currentDid++
                    sendCredit(g, DIAG_SERVICE, DIAG_CREDITS)
                }
            }, DID_TIMEOUT_MS)
        } else {
            handler.postDelayed({ sendCurrentDid(g) }, 120L)
        }
    }

    private fun handlePositiveDid(g: BluetoothGatt, did: Int, data: ByteArray) {
        positiveCount++
        positiveDids[did] = data.copyOf()
        val old = lastDidData.put(did, data.copyOf())
        val changed = if (old == null) "NEW" else if (old.contentEquals(data)) "SAME" else "CHANGED:${diffBytes(old, data)}"
        log("DID ${h4(did)} POS ${knownNames[did]?.let { \"[$it] \" } ?: \"\"}len=${data.size} hex=${hex(data)} | ${decodeGeneric(data)} | $changed")
        if (waitingDid && did == currentDid) {
            waitingDid = false
            didToken++
            currentDid++
            handler.postDelayed({
                sendCredit(g, DIAG_SERVICE, DIAG_CREDITS)
                handler.postDelayed({ sendCurrentDid(g) }, STEP_DELAY_MS)
            }, STEP_DELAY_MS)
        }
    }

    private fun handleNegativeDid(g: BluetoothGatt, nrc: Int) {
        negativeCount++
        nrcCounts[nrc] = (nrcCounts[nrc] ?: 0) + 1
        if (waitingDid) {
            val did = currentDid
            log("DID ${h4(did)} NRC=0x${h2(nrc)} ${nrcName(nrc)}")
            waitingDid = false
            didToken++
            currentDid++
            handler.postDelayed({
                sendCredit(g, DIAG_SERVICE, DIAG_CREDITS)
                handler.postDelayed({ sendCurrentDid(g) }, STEP_DELAY_MS)
            }, STEP_DELAY_MS)
        }
    }

    private fun finish() {
        phase = Phase.DONE
        running = false
        listener.onScannerStatus("Сканирование завершено")
        listener.onScannerProgress("Готово: +$positiveCount / NRC $negativeCount / timeout $timeoutCount")
        log("============================================================")
        log("SCAN COMPLETE")
        log("Positive DIDs: $positiveCount")
        log("Negative responses: $negativeCount")
        log("Timeouts: $timeoutCount")
        log("NRC summary: ${nrcCounts.entries.joinToString { \"0x${h2(it.key)}=${it.value}\" }}")
        log("---------------- POSITIVE DID SUMMARY ----------------")
        positiveDids.forEach { (did, data) ->
            log("${h4(did)} ${knownNames[did] ?: \"UNKNOWN\"} len=${data.size} hex=${hex(data)} | ${decodeGeneric(data)}")
        }
        log("============================================================")
    }

    @SuppressLint("MissingPermission")
    private fun writeNoResponse(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION") c.value = data
            @Suppress("DEPRECATION") g.writeCharacteristic(c)
        }
    }

    private fun decodeGeneric(data: ByteArray): String {
        if (data.isEmpty()) return "EMPTY"
        val parts = mutableListOf<String>()
        parts += "u8=${u(data[0])}"
        if (data.size >= 2) {
            val be = (u(data[0]) shl 8) or u(data[1])
            val le = u(data[0]) or (u(data[1]) shl 8)
            parts += "u16BE=$be"
            parts += "u16LE=$le"
            if (be < 0xFB00) parts += "BEtime=${be / 60}:${String.format(Locale.US, \"%02d\", be % 60)}"
        }
        if (data.size >= 4) {
            val be32 = (u(data[0]).toLong() shl 24) or (u(data[1]).toLong() shl 16) or (u(data[2]).toLong() shl 8) or u(data[3]).toLong()
            parts += "u32BE=$be32"
        }
        val text = ascii(data)
        if (text.any { it.isLetterOrDigit() }) parts += "ascii=$text"
        return parts.joinToString(" ")
    }

    private fun diffBytes(a: ByteArray, b: ByteArray): String {
        val max = maxOf(a.size, b.size)
        return (0 until max).filter { i -> i >= a.size || i >= b.size || a[i] != b[i] }
            .take(16).joinToString(",") { i -> "#$i:${if (i < a.size) h2(u(a[i])) else "--"}->${if (i < b.size) h2(u(b[i])) else "--"}" }
    }

    private fun nrcName(nrc: Int): String = when (nrc) {
        0x10 -> "generalReject"
        0x11 -> "serviceNotSupported"
        0x12 -> "subFunctionNotSupported"
        0x13 -> "incorrectLengthOrFormat"
        0x21 -> "busyRepeatRequest"
        0x22 -> "conditionsNotCorrect"
        0x31 -> "requestOutOfRange"
        0x33 -> "securityAccessDenied"
        0x7E -> "subFunctionNotSupportedInActiveSession"
        else -> "unknown"
    }

    private fun log(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        lines.add("[$ts] $message")
        while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
        val full = lines.joinToString("\n")
        handler.post { listener.onScannerLog(full) }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String = try { device.name ?: "DTCO" } catch (_: Throwable) { "DTCO" }
    private fun u(b: Byte): Int = b.toInt() and 0xFF
    private fun h2(v: Int): String = "%02X".format(Locale.US, v and 0xFF)
    private fun h4(v: Int): String = "%04X".format(Locale.US, v and 0xFFFF)
    private fun hex(v: ByteArray): String = if (v.isEmpty()) "(empty)" else v.joinToString(" ") { h2(u(it)) }
    private fun ascii(v: ByteArray): String = v.map { b -> val x = u(b); if (x in 32..126) x.toChar() else '.' }.joinToString("")
}
