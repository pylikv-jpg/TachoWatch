from pathlib import Path

p = Path("app/src/main/java/com/pylikv/tachowatch/FullDtcoScannerV3Activity.kt")
s = p.read_text(encoding="utf-8")


def rep(old: str, new: str) -> None:
    global s
    if old not in s:
        raise SystemExit(f"required scanner fragment not found:\n{old[:240]}")
    s = s.replace(old, new, 1)

rep('title = "DTCO Full Scanner v3"', 'title = "DTCO F9 Discovery v4"')
rep('text = "DTCO FULL READ-ONLY SCANNER v3"', 'text = "DTCO F9 DISCOVERY READ-ONLY v4"')
rep('text = "Быстрый тест F900–F9FF"', 'text = "ПОИСК НЕИЗВЕСТНЫХ F900–F9FF"')
rep('saveLauncher.launch("DTCO_full_scan_v3_$stamp.txt")', 'saveLauncher.launch("DTCO_F9_discovery_v4_$stamp.txt")')
rep(
    'text = "v3: FIFO→CREDITS подписываются последовательно. DIAG_CREDITS=00 больше не блокирует исходящий FIFO. После подписок OPEN RHMI отправляется принудительно; затем STATUS и только UDS 0x22, по одному DID за раз."',
    'text = "v4: целевой поиск F900–F9FF. Только UDS 0x22. Каждый положительный DID перечитывается до 3 раз; итог показывает UNKNOWN и STATIC/DYNAMIC с S1/S2/S3 HEX."'
)

rep(
    'private const val MAX_LOG_LINES = 18000',
    'private const val MAX_LOG_LINES = 18000\n        private const val POSITIVE_SAMPLE_COUNT = 3\n        private const val POSITIVE_SAMPLE_DELAY_MS = 650L'
)

rep(
    'private val positiveDids = linkedMapOf<Int, ByteArray>()\n    private val nrcCounts = linkedMapOf<Int, Int>()',
    'private val positiveDids = linkedMapOf<Int, ByteArray>()\n    private val positiveSamples = linkedMapOf<Int, MutableList<ByteArray>>()\n    private val nrcCounts = linkedMapOf<Int, Int>()'
)

rep(
    'positiveDids.clear()\n        nrcCounts.clear()',
    'positiveDids.clear()\n        positiveSamples.clear()\n        nrcCounts.clear()'
)

rep(
    'log("DTCO FULL READ-ONLY SCANNER v3")',
    'log("DTCO F9 DISCOVERY READ-ONLY v4")'
)
rep(
    'log("FLOW FIX: forced OPEN RHMI after subscriptions; one outstanding DID at a time")',
    'log("FLOW FIX: forced OPEN RHMI after subscriptions; one outstanding DID at a time")\n        log("DISCOVERY v4: every positive DID is sampled up to $POSITIVE_SAMPLE_COUNT times")\n        log("DISCOVERY v4: final summary separates UNKNOWN channels and marks STATIC/DYNAMIC")'
)

old_positive = '''        if (a.size >= 3 && u(a[0]) == 0x62) {
            val did = (u(a[1]) shl 8) or u(a[2])
            val data = if (a.size > 3) a.copyOfRange(3, a.size) else byteArrayOf()
            positiveCount++
            positiveDids[did] = data.copyOf()
            log("DID ${h4(did)} POS ${knownNames[did]?.let { "[$it] " } ?: ""}len=${data.size} hex=${hex(data)} | ${decodeGeneric(data)}")
            if (waitingDid && did == currentDid) advance(g)
            return
        }'''
new_positive = '''        if (a.size >= 3 && u(a[0]) == 0x62) {
            val did = (u(a[1]) shl 8) or u(a[2])
            val data = if (a.size > 3) a.copyOfRange(3, a.size) else byteArrayOf()
            val samples = positiveSamples.getOrPut(did) { mutableListOf() }
            if (samples.isEmpty()) positiveCount++
            samples += data.copyOf()
            positiveDids[did] = data.copyOf()
            val sampleNo = samples.size
            log("DID ${h4(did)} POS S$sampleNo/$POSITIVE_SAMPLE_COUNT ${knownNames[did]?.let { "[$it] " } ?: "[UNKNOWN] "}len=${data.size} hex=${hex(data)} | ${decodeGeneric(data)}")
            if (waitingDid && did == currentDid) {
                waitingDid = false
                didToken++
                if (samples.size < POSITIVE_SAMPLE_COUNT) {
                    log("DID ${h4(did)} RESAMPLE ${samples.size + 1}/$POSITIVE_SAMPLE_COUNT after ${POSITIVE_SAMPLE_DELAY_MS}ms")
                    handler.postDelayed({ sendCurrentDid(g) }, POSITIVE_SAMPLE_DELAY_MS)
                } else {
                    currentDid++
                    handler.postDelayed({ sendCurrentDid(g) }, STEP_DELAY_MS)
                }
            }
            return
        }'''
rep(old_positive, new_positive)

old_finish = '''        log("---------------- POSITIVE DID SUMMARY ----------------")
        positiveDids.forEach { (did, data) ->
            log("${h4(did)} ${knownNames[did] ?: "UNKNOWN"} len=${data.size} hex=${hex(data)} | ${decodeGeneric(data)}")
        }
        log("============================================================")'''
new_finish = '''        log("---------------- POSITIVE DID SUMMARY ----------------")
        positiveDids.forEach { (did, data) ->
            val samples = positiveSamples[did].orEmpty()
            val distinct = samples.map { hex(it) }.distinct().size
            val motion = if (distinct > 1) "DYNAMIC" else "STATIC"
            log("${h4(did)} ${knownNames[did] ?: "UNKNOWN"} $motion samples=${samples.size} len=${data.size} hex=${hex(data)} | ${decodeGeneric(data)}")
            samples.forEachIndexed { i, sample -> log("  S${i + 1}=${hex(sample)} | ${decodeGeneric(sample)}") }
        }
        log("---------------- UNKNOWN POSITIVE DID SUMMARY ----------------")
        val unknown = positiveSamples.filterKeys { !knownNames.containsKey(it) }
        if (unknown.isEmpty()) {
            log("NONE")
        } else {
            unknown.forEach { (did, samples) ->
                val distinct = samples.map { hex(it) }.distinct().size
                val motion = if (distinct > 1) "DYNAMIC" else "STATIC"
                log("UNKNOWN ${h4(did)} $motion samples=${samples.size}")
                samples.forEachIndexed { i, sample -> log("  S${i + 1}=${hex(sample)} | ${decodeGeneric(sample)}") }
            }
        }
        log("============================================================")'''
rep(old_finish, new_finish)

p.write_text(s, encoding="utf-8")
print("Applied F9 discovery v4 sampling and unknown-channel summary")
