from pathlib import Path

p = Path("app/src/main/java/com/pylikv/tachowatch/FullDtcoScannerV3Activity.kt")
s = p.read_text(encoding="utf-8")


def rep(old: str, new: str) -> None:
    global s
    if old not in s:
        raise SystemExit(f"required v6 fragment not found:\n{old[:400]}")
    s = s.replace(old, new, 1)

# Recovery state: always keep a private rolling copy while the scan is running.
rep(
    '    private var latestLog = ""\n',
    '    private var latestLog = ""\n    private val recoveryFileName = "dtco_rest_recovery_v7.txt"\n    private var lastRecoverySaveAt = 0L\n    private var finalAutoSaved = false\n'
)

# Restore a previous recovery copy after process death/crash.
rep(
    '''        scanner = FullDtcoScannerV3(this, this)
        requestNeededPermissions()
        refreshButtons()
''',
    '''        scanner = FullDtcoScannerV3(this, this)
        try {
            val recovery = File(filesDir, recoveryFileName)
            if (recovery.exists() && recovery.length() > 0L) {
                latestLog = recovery.readText(Charsets.UTF_8)
                logView.text = latestLog.takeLast(120_000)
                statusView.text = "Найдена аварийная копия предыдущего теста"
            }
        } catch (_: Throwable) {}
        requestNeededPermissions()
        refreshButtons()
'''
)

# Make the save button wording unambiguous: it never opens a picker.
rep(
    '''        saveButton = Button(this).apply {
            text = "Сохранить отчёт"
            setOnClickListener { saveReportDirect() }
        }
''',
    '''        saveButton = Button(this).apply {
            text = "Сохранить копию в Загрузки"
            setOnClickListener { saveReportDirect(false) }
        }
'''
)

# Start a new recovery file for each scan.
rep(
    '''        latestLog = ""
        logView.text = ""
        scanner?.start(device, startDid, endDid)
''',
    '''        latestLog = ""
        logView.text = ""
        finalAutoSaved = false
        lastRecoverySaveAt = 0L
        try { File(filesDir, recoveryFileName).delete() } catch (_: Throwable) {}
        scanner?.start(device, startDid, endDid)
'''
)

# Rolling recovery snapshot, throttled to one write per 5 seconds.
rep(
    '''    override fun onScannerLog(fullLog: String) {
        latestLog = fullLog
        logView.text = fullLog.takeLast(120_000)
        saveButton.isEnabled = fullLog.isNotBlank()
    }

    override fun onScannerStatus(status: String) { statusView.text = status }
''',
    '''    override fun onScannerLog(fullLog: String) {
        latestLog = fullLog
        logView.text = fullLog.takeLast(120_000)
        saveButton.isEnabled = fullLog.isNotBlank()
        val now = android.os.SystemClock.elapsedRealtime()
        if (fullLog.isNotBlank() && (lastRecoverySaveAt == 0L || now - lastRecoverySaveAt >= 5000L)) {
            lastRecoverySaveAt = now
            try { File(filesDir, recoveryFileName).writeText(fullLog, Charsets.UTF_8) } catch (_: Throwable) {}
        }
    }

    override fun onScannerStatus(status: String) {
        statusView.text = status
        if (!finalAutoSaved && status.startsWith("Сканирование завершено") && latestLog.isNotBlank()) {
            finalAutoSaved = true
            try { File(filesDir, recoveryFileName).writeText(latestLog, Charsets.UTF_8) } catch (_: Throwable) {}
            saveReportDirect(true)
        }
    }
'''
)

# Auto-save or manual save both go straight through MediaStore. No SAF picker is involved.
rep(
    '    private fun saveReportDirect() {\n',
    '    private fun saveReportDirect(auto: Boolean) {\n'
)
rep(
    '        val fileName = "DTCO_REST_dynamic_v6_$stamp.txt"\n',
    '        val fileName = "DTCO_REST_dynamic_v7_$stamp.txt"\n'
)
rep(
    '''            statusView.text = "Сохранено: $savedPath"
            progressView.text = "Отчёт сохранён • можно отправлять TXT"
''',
    '''            statusView.text = if (auto) "Тест завершён • отчёт автоматически сохранён: $savedPath" else "Сохранено: $savedPath"
            progressView.text = if (auto) "АВТОСОХРАНЕНИЕ ГОТОВО • $savedPath" else "Отчёт сохранён • можно отправлять TXT"
'''
)

# Clearly identify this APK and describe the new behavior.
rep('title = "DTCO REST Dynamic Monitor v6"', 'title = "DTCO REST Dynamic Monitor v7"')
rep('text = "DTCO REST DYNAMIC MONITOR v6"', 'text = "DTCO REST DYNAMIC MONITOR v7"')
rep(
    'text = "v6: 12-минутный REST monitor. Отчёт сохраняется напрямую в Download/TachoWatch без системного окна; ориентация экрана заблокирована на время работы сканера."',
    'text = "v7: 12-минутный REST monitor. Во время теста каждые 5 секунд пишется аварийная копия. По завершении TXT автоматически сохраняется в Download/TachoWatch. Кнопка сохранения не открывает проводник."'
)
rep('log("DTCO REST DYNAMIC MONITOR v6")', 'log("DTCO REST DYNAMIC MONITOR v7")')
rep('log("REST v6: after discovery, monitor ${restMonitorDids.size} candidate DIDs for 12 minutes")',
    'log("REST v7: after discovery, monitor ${restMonitorDids.size} candidate DIDs for 12 minutes; auto-save enabled")')

p.write_text(s, encoding="utf-8")
print("Applied REST monitor v7 autosave/recovery fix")
