package com.pylikv.tachowatch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class Test14Activity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var selectedFileText: TextView
    private var rendered = ""

    private val openDddLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSelectedDdd(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        root.addView(TextView(this).apply {
            text = "TachoWatch TEST-14"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "0506 Places — начало/конец рабочего периода, страна, одометр и GNSS"
            textSize = 13f
        })

        root.addView(Button(this).apply {
            text = "Выбрать DDD-файл"
            setOnClickListener { openDddLauncher.launch(arrayOf("*/*")) }
        })

        selectedFileText = TextView(this).apply {
            text = currentImportedFile()?.let { "Импортирован: ${it.name}" } ?: "DDD-файл ещё не выбран"
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(selectedFileText)

        val analyze = Button(this).apply {
            text = "Анализ DDD"
            setOnClickListener { analyzeSelectedOrLatest() }
        }
        root.addView(analyze)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Копировать TEST-14"
            setOnClickListener { copyTest14() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Открыть основное приложение"
            setOnClickListener { startActivity(Intent(this@Test14Activity, DriverActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        output = TextView(this).apply {
            text = "Нажми «Выбрать DDD-файл», укажи любой TachoWatch_card_*.ddd, затем нажми «Анализ DDD». Выбранный файл будет скопирован внутрь TEST-14 и останется доступен для повторного анализа."
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            gravity = Gravity.START
            setPadding(0, dp(8), 0, dp(24))
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun importSelectedDdd(uri: Uri) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val target = File(dir, "TachoWatch_imported_TEST14.ddd")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Не удалось открыть выбранный файл")

            if (target.length() <= 0L) throw IllegalStateException("Выбранный файл пуст")

            selectedFileText.text = "Импортирован: ${target.name} (${target.length()} байт)"
            rendered = ""
            output.text = "DDD импортирован. Нажми «Анализ DDD»."
            Toast.makeText(this, "DDD импортирован", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Ошибка импорта DDD: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun currentImportedFile(): File? {
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "TachoWatch_imported_TEST14.ddd")
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    private fun analyzeSelectedOrLatest() {
        val file = currentImportedFile() ?: TlvInventory.findLatestDdd(getExternalFilesDir(null))
        if (file == null) {
            Toast.makeText(this, "DDD не найден. Нажми «Выбрать DDD-файл».", Toast.LENGTH_LONG).show()
            return
        }
        val result = TlvInventory.parse(file)
        rendered = PlacesDecoder.render(result)
        output.text = rendered
        Toast.makeText(this, "TEST-14 готов", Toast.LENGTH_SHORT).show()
    }

    private fun copyTest14() {
        if (rendered.isBlank()) {
            Toast.makeText(this, "Сначала выполни анализ", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TachoWatch TEST-14", rendered))
        Toast.makeText(this, "TEST-14 скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
