from pathlib import Path

p = Path("app/src/main/java/com/pylikv/tachowatch/FullDtcoScannerV3Activity.kt")
s = p.read_text(encoding="utf-8")


def rep(old: str, new: str) -> None:
    global s
    if old not in s:
        raise SystemExit(f"required fragment not found:\n{old[:300]}")
    s = s.replace(old, new, 1)

# Imports for direct Downloads saving and orientation lock.
rep(
    'import android.content.pm.PackageManager\n',
    'import android.content.pm.ActivityInfo\nimport android.content.pm.PackageManager\nimport android.content.ContentValues\nimport android.provider.MediaStore\nimport android.os.Environment\nimport java.io.File\n'
)

# Remove external document picker. It was leaving the scanner Activity during a live BLE test.
old_launcher = '''    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(latestLog) }
            statusView.text = "Отчёт сохранён"
        }
    }
'''
rep(old_launcher, '')

# Lock the scanner to its current orientation so Android cannot recreate the Activity mid-test.
rep(
    '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
''',
    '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
'''
)

# Direct save: no picker, no Activity switch, no BLE interruption.
rep(
    '''            setOnClickListener {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                saveLauncher.launch("DTCO_REST_dynamic_v5_$stamp.txt")
            }
''',
    '''            setOnClickListener { saveReportDirect() }
'''
)

# Add robust save helper before dp().
marker = '''    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
'''
helper = '''    private fun saveReportDirect() {
        if (latestLog.isBlank()) {
            statusView.text = "Нет данных для сохранения"
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "DTCO_REST_dynamic_v6_$stamp.txt"
        saveButton.isEnabled = false
        try {
            val savedPath: String
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TachoWatch")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert returned null")
                try {
                    contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(latestLog)
                        it.flush()
                    } ?: error("Cannot open output stream")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    savedPath = "Download/TachoWatch/$fileName"
                } catch (t: Throwable) {
                    contentResolver.delete(uri, null, null)
                    throw t
                }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                file.writeText(latestLog, Charsets.UTF_8)
                savedPath = file.absolutePath
            }
            statusView.text = "Сохранено: $savedPath"
            progressView.text = "Отчёт сохранён • можно отправлять TXT"
        } catch (t: Throwable) {
            statusView.text = "Ошибка сохранения: ${t.javaClass.simpleName}: ${t.message ?: "без сообщения"}"
        } finally {
            saveButton.isEnabled = latestLog.isNotBlank()
        }
    }

''' + marker
rep(marker, helper)

# Rename visible version so the user can distinguish the fixed APK.
rep('title = "DTCO REST Dynamic Monitor v5"', 'title = "DTCO REST Dynamic Monitor v6"')
rep('text = "DTCO REST DYNAMIC MONITOR v5"', 'text = "DTCO REST DYNAMIC MONITOR v6"')
rep('text = "v5: сначала контроль F900–F9FF, затем автоматически 12 минут циклического read-only наблюдения за найденными кандидатами на отдых/таймеры. Ничего переключать не нужно."',
    'text = "v6: 12-минутный REST monitor. Отчёт сохраняется напрямую в Download/TachoWatch без системного окна; ориентация экрана заблокирована на время работы сканера."')
rep('log("DTCO REST DYNAMIC MONITOR v5")', 'log("DTCO REST DYNAMIC MONITOR v6")')
rep('log("REST v5: after discovery, monitor ${restMonitorDids.size} candidate DIDs for 12 minutes")',
    'log("REST v6: after discovery, monitor ${restMonitorDids.size} candidate DIDs for 12 minutes")')

p.write_text(s, encoding="utf-8")
print("Applied REST monitor v6 save/orientation fix")
