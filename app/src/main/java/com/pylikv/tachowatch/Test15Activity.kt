package com.pylikv.tachowatch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class Test15Activity : AppCompatActivity() {

    private lateinit var output: TextView
    private var rendered = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        root.addView(TextView(this).apply {
            text = "TachoWatch TEST-15"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "0507 • Current Usage"
            textSize = 13f
        })

        val analyze = Button(this).apply {
            text = "Анализ последнего DDD"
            isAllCaps = false
            setOnClickListener { analyzeLatest() }
        }
        root.addView(analyze)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copy = Button(this).apply {
            text = "Копировать TEST-15"
            isAllCaps = false
            setOnClickListener { copyResult() }
        }
        val main = Button(this).apply {
            text = "Открыть TachoWatch"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@Test15Activity, MainActivity::class.java)) }
        }
        row.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(main, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        output = TextView(this).apply {
            text = "Нажми «Анализ последнего DDD». Повторно скачивать карту не требуется."
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(16))
            gravity = Gravity.START
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        analyzeLatest()
    }

    private fun analyzeLatest() {
        val file = TlvInventory.findLatestDdd(getExternalFilesDir(null))
        if (file == null) {
            rendered = "DDD не найден в памяти TachoWatch.\nОткрой основной TachoWatch, скачай карту один раз и вернись в TEST-15."
            output.text = rendered
            Toast.makeText(this, "DDD не найден", Toast.LENGTH_LONG).show()
            return
        }

        val result = TlvInventory.parse(file)
        rendered = CurrentUsageDecoder.render(result)
        output.text = rendered
        Toast.makeText(this, "TEST-15 готов", Toast.LENGTH_SHORT).show()
    }

    private fun copyResult() {
        if (rendered.isBlank()) {
            Toast.makeText(this, "Копировать пока нечего", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TachoWatch TEST-15", rendered))
        Toast.makeText(this, "TEST-15 скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
