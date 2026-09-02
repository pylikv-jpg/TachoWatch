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

class Test14Activity : AppCompatActivity() {

    private lateinit var output: TextView
    private var rendered = ""

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

        val analyze = Button(this).apply {
            text = "Анализ последнего DDD"
            setOnClickListener { analyzeLatest() }
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
            text = "Нажми «Анализ последнего DDD». Файл от TEST-13 должен сохраниться после обновления."
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

    private fun analyzeLatest() {
        val file = TlvInventory.findLatestDdd(getExternalFilesDir(null))
        if (file == null) {
            Toast.makeText(this, "DDD не найден. Открой основное приложение и скачай карту.", Toast.LENGTH_LONG).show()
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
