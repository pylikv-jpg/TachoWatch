from pathlib import Path

p = Path("app/src/main/java/com/pylikv/tachowatch/FullDtcoScannerV3Activity.kt")
s = p.read_text(encoding="utf-8")


def rep(old: str, new: str) -> None:
    global s
    if old not in s:
        raise SystemExit(f"required v4 scanner fragment not found:\n{old[:300]}")
    s = s.replace(old, new, 1)

rep('title = "DTCO F9 Discovery v4"', 'title = "DTCO REST Dynamic Monitor v5"')
rep('text = "DTCO F9 DISCOVERY READ-ONLY v4"', 'text = "DTCO REST DYNAMIC MONITOR v5"')
rep('text = "ПОИСК НЕИЗВЕСТНЫХ F900–F9FF"', 'text = "СТАРТ • 12 МИНУТ ОТДЫХА"')
rep('saveLauncher.launch("DTCO_F9_discovery_v4_$stamp.txt")', 'saveLauncher.launch("DTCO_REST_dynamic_v5_$stamp.txt")')
rep(
    'text = "v4: целевой поиск F900–F9FF. Только UDS 0x22. Каждый положительный DID перечитывается до 3 раз; итог показывает UNKNOWN и STATIC/DYNAMIC с S1/S2/S3 HEX."',
    'text = "v5: сначала контроль F900–F9FF, затем автоматически 12 минут циклического read-only наблюдения за найденными кандидатами на отдых/таймеры. Ничего переключать не нужно."'
)

rep(
    'private const val POSITIVE_SAMPLE_DELAY_MS = 650L',
    'private const val POSITIVE_SAMPLE_DELAY_MS = 650L\n        private const val REST_MONITOR_DURATION_MS = 12L * 60L * 1000L\n        private const val REST_MONITOR_STEP_MS = 650L\n        private const val REST_MONITOR_TIMEOUT_MS = 1200L'
)

rep(
    'private enum class Phase { IDLE, SUB_FIFO, SUB_CREDITS, HANDSHAKE, DID_SCAN, DONE }',
    'private enum class Phase { IDLE, SUB_FIFO, SUB_CREDITS, HANDSHAKE, DID_SCAN, REST_MONITOR, DONE }'
)

rep(
    'private val positiveSamples = linkedMapOf<Int, MutableList<ByteArray>>()\n    private val nrcCounts = linkedMapOf<Int, Int>()',
    '''private val positiveSamples = linkedMapOf<Int, MutableList<ByteArray>>()
    private val restMonitorSamples = linkedMapOf<Int, MutableList<ByteArray>>()
    private val restMonitorChanges = linkedMapOf<Int, Int>()
    private val nrcCounts = linkedMapOf<Int, Int>()'''
)

rep(
    'private var timeoutCount = 0\n\n    private val knownNames = mapOf(',
    '''private var timeoutCount = 0
    private var restMonitorIndex = 0
    private var restMonitorEndAt = 0L
    private var restMonitorCycle = 0

    private val restMonitorDids = intArrayOf(
        0xF90B, 0xF90D, 0xF90E, 0xF90F, 0xF912, 0xF913,
        0xF918, 0xF91A, 0xF91C, 0xF91D, 0xF91E,
        0xF920, 0xF921, 0xF922, 0xF924, 0xF925, 0xF926, 0xF927, 0xF928,
        0xF92C, 0xF930, 0xF933, 0xF936, 0xF937, 0xF939,
        0xF940, 0xF95A, 0xF960, 0xF979, 0xF97A, 0xF97B, 0xF97C, 0xF97D, 0xF97E, 0xF97F,
        0xF992, 0xF99A, 0xF99C, 0xF9A1, 0xF9A2, 0xF9AB, 0xF9AD, 0xF9AF, 0xF9B1, 0xF9B3,
        0xF9D0, 0xF9D1, 0xF9D2, 0xF9D3, 0xF9D4, 0xF9D5, 0xF9D6, 0xF9D7, 0xF9D8
    )

    private val knownNames = mapOf('''
)

rep(
    'positiveSamples.clear()\n        nrcCounts.clear()',
    'positiveSamples.clear()\n        restMonitorSamples.clear()\n        restMonitorChanges.clear()\n        nrcCounts.clear()'
)

rep(
    'log("DTCO F9 DISCOVERY READ-ONLY v4")',
    'log("DTCO REST DYNAMIC MONITOR v5")'
)
rep(
    'log("DISCOVERY v4: final summary separates UNKNOWN channels and marks STATIC/DYNAMIC")',
    'log("DISCOVERY v4: final summary separates UNKNOWN channels and marks STATIC/DYNAMIC")\n        log("REST v5: after discovery, monitor ${restMonitorDids.size} candidate DIDs for 12 minutes")\n        log("REST v5: tachograph may remain on REST for the entire test")'
)

# Insert REST_MONITOR response handling before v4's normal DID positive-response block.
marker = '''        if (a.size >= 3 && u(a[0]) == 0x62) {
            val did = (u(a[1]) shl 8) or u(a[2])
            val data = if (a.size > 3) a.copyOfRange(3, a.size) else byteArrayOf()
            val samples = positiveSamples.getOrPut(did) { mutableListOf() }'''
insert = '''        if (phase == Phase.REST_MONITOR && a.size >= 3 && u(a[0]) == 0x62) {
            val did = (u(a[1]) shl 8) or u(a[2])
            val expected = restMonitorDids[restMonitorIndex]
            if (waitingDid && did == expected) {
                val data = if (a.size > 3) a.copyOfRange(3, a.size) else byteArrayOf()
                val samples = restMonitorSamples.getOrPut(did) { mutableListOf() }
                val previous = samples.lastOrNull()
                val changed = previous != null && !previous.contentEquals(data)
                if (changed) restMonitorChanges[did] = (restMonitorChanges[did] ?: 0) + 1
                samples += data.copyOf()
                val diff = if (previous == null) "BASELINE" else byteDiff(previous, data)
                log("REST ${h4(did)} S${samples.size} ${if (changed) "CHANGED" else "same"} hex=${hex(data)} diff=$diff | ${decodeGeneric(data)}")
                advanceRestMonitor(g)
            }
            return
        }
        if (phase == Phase.REST_MONITOR && a.size >= 3 && u(a[0]) == 0x7F && u(a[1]) == 0x22) {
            val nrc = u(a[2])
            val expected = restMonitorDids[restMonitorIndex]
            log("REST ${h4(expected)} NRC=0x${h2(nrc)} ${nrcName(nrc)}")
            if (waitingDid) advanceRestMonitor(g)
            return
        }

''' + marker
rep(marker, insert)

# After ordinary F9 discovery, switch automatically into the timed monitor instead of finishing.
rep(
    '''        if (currentDid > endDid) {
            finish("complete")
            return
        }''',
    '''        if (currentDid > endDid) {
            startRestMonitor(g)
            return
        }'''
)

# Add monitor functions immediately before grantPeer().
marker2 = '''    @SuppressLint("MissingPermission")
    private fun grantPeer(g: BluetoothGatt) {'''
monitor_funcs = '''    private fun startRestMonitor(g: BluetoothGatt) {
        if (!running || gatt != g) return
        phase = Phase.REST_MONITOR
        waitingDid = false
        didToken++
        restMonitorIndex = 0
        restMonitorCycle = 1
        restMonitorEndAt = android.os.SystemClock.elapsedRealtime() + REST_MONITOR_DURATION_MS
        log("============================================================")
        log("REST MONITOR v5 START duration=12min candidates=${restMonitorDids.size}")
        log("Keep tachograph on REST. No activity switching required.")
        listener.onScannerStatus("REST monitor • 12 минут")
        listener.onScannerProgress("REST monitor: цикл 1 • 0/${restMonitorDids.size}")
        handler.postDelayed({ sendRestMonitorDid(g) }, 300L)
    }

    private fun advanceRestMonitor(g: BluetoothGatt) {
        waitingDid = false
        didToken++
        restMonitorIndex++
        if (restMonitorIndex >= restMonitorDids.size) {
            restMonitorIndex = 0
            restMonitorCycle++
            val left = (restMonitorEndAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            log("REST MONITOR cycle=${restMonitorCycle - 1} complete; remaining=${left / 1000}s")
        }
        handler.postDelayed({ sendRestMonitorDid(g) }, REST_MONITOR_STEP_MS)
    }

    @SuppressLint("MissingPermission")
    private fun sendRestMonitorDid(g: BluetoothGatt) {
        if (!running || phase != Phase.REST_MONITOR || waitingDid || gatt != g) return
        if (android.os.SystemClock.elapsedRealtime() >= restMonitorEndAt) {
            finish("rest-monitor-complete")
            return
        }
        val did = restMonitorDids[restMonitorIndex]
        grantPeer(g)
        handler.postDelayed({
            if (!running || phase != Phase.REST_MONITOR || waitingDid || gatt != g) return@postDelayed
            val fifo = g.getService(DIAG_SERVICE)?.getCharacteristic(DIAG_FIFO) ?: return@postDelayed
            val packet = byteArrayOf(1, 1, 0x22, (did shr 8).toByte(), did.toByte())
            val ok = writeNoResponse(g, fifo, packet)
            log("TX REST DID ${h4(did)} cycle=$restMonitorCycle peerCredits=$lastPeerCredits started=$ok")
            if (!ok) {
                handler.postDelayed({ sendRestMonitorDid(g) }, 250L)
                return@postDelayed
            }
            waitingDid = true
            val token = ++didToken
            val left = (restMonitorEndAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            listener.onScannerProgress("REST ${left / 60000}:${String.format(Locale.US, "%02d", (left / 1000) % 60)} • cycle $restMonitorCycle • ${restMonitorIndex + 1}/${restMonitorDids.size}")
            handler.postDelayed({
                if (running && phase == Phase.REST_MONITOR && waitingDid && token == didToken && gatt == g) {
                    log("REST ${h4(did)} TIMEOUT")
                    advanceRestMonitor(g)
                }
            }, REST_MONITOR_TIMEOUT_MS)
        }, 80L)
    }

''' + marker2
rep(marker2, monitor_funcs)

# Add REST monitor summary before final separator.
summary_marker = '''        log("---------------- UNKNOWN POSITIVE DID SUMMARY ----------------")'''
summary_prefix = '''        log("---------------- REST MONITOR DYNAMIC SUMMARY ----------------")
        if (restMonitorSamples.isEmpty()) {
            log("NO REST MONITOR SAMPLES")
        } else {
            restMonitorSamples.forEach { (did, samples) ->
                val distinct = samples.map { hex(it) }.distinct().size
                val changes = restMonitorChanges[did] ?: 0
                val motion = if (distinct > 1) "DYNAMIC" else "STATIC"
                log("REST ${h4(did)} ${knownNames[did] ?: "UNKNOWN"} $motion reads=${samples.size} distinct=$distinct changes=$changes")
                if (samples.isNotEmpty()) {
                    log("  FIRST=${hex(samples.first())} | ${decodeGeneric(samples.first())}")
                    log("  LAST =${hex(samples.last())} | ${decodeGeneric(samples.last())}")
                    log("  DELTA=${byteDiff(samples.first(), samples.last())}")
                }
            }
        }
''' + summary_marker
rep(summary_marker, summary_prefix)

# Helper for per-byte changes.
helper_marker = '''    private fun nrcName(nrc: Int): String = when (nrc) {'''
helper = '''    private fun byteDiff(a: ByteArray, b: ByteArray): String {
        if (a.size != b.size) return "len ${a.size}->${b.size}"
        val changes = mutableListOf<String>()
        for (i in a.indices) {
            if (a[i] != b[i]) changes += "b$i:${h2(u(a[i]))}->${h2(u(b[i]))}"
        }
        return if (changes.isEmpty()) "none" else changes.joinToString(",")
    }

''' + helper_marker
rep(helper_marker, helper)

p.write_text(s, encoding="utf-8")
print("Applied REST dynamic monitor v5 (12-minute read-only correlation)")
