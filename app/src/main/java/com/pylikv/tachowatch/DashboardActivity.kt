package com.pylikv.tachowatch

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DashboardActivity : AppCompatActivity() {

    private val green = Color.parseColor("#2E7D32")
    private val yellow = Color.parseColor("#F9A825")
    private val red = Color.parseColor("#C62828")
    private val blue = Color.parseColor("#1565C0")
    private val gray = Color.parseColor("#616161")
    private val lightGray = Color.parseColor("#EEEEEE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showDashboard()
    }

    private fun showDashboard() {

        val scrollView = ScrollView(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 40)
        }

        scrollView.addView(root)

        val title = TextView(this).apply {
            text = "TachoWatch"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Контроль режима труда и отдыха"
            textSize = 15f
            setTextColor(gray)
            setPadding(0, 0, 0, 24)
        }

        root.addView(title)
        root.addView(subtitle)

        /*
         * ВРЕМЕННЫЕ ТЕСТОВЫЕ ДАННЫЕ.
         *
         * Позже вместо них будут реальные данные,
         * полученные из тахографа и локальной истории.
         */

        addSectionTitle(
            root,
            "ТЕКУЩИЙ РЕЖИМ"
        )

        val activityText = TextView(this).apply {
            text = "● ВОЖДЕНИЕ"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(green)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        root.addView(activityText)

        addSeparator(root)

        /*
         * НЕПРЕРЫВНОЕ ВОЖДЕНИЕ
         */

        addSectionTitle(
            root,
            "Непрерывное вождение"
        )

        val continuousDriving = TextView(this).apply {
            text = "3:47"
            textSize = 44f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val continuousLimit = TextView(this).apply {
            text = "из 4:30"
            textSize = 18f
            setTextColor(gray)
            gravity = Gravity.CENTER
        }

        root.addView(continuousDriving)
        root.addView(continuousLimit)

        val continuousProgress =
            createProgressBar(
                progress = 227,
                max = 270,
                color = yellow
            )

        root.addView(continuousProgress)

        val breakRemaining = TextView(this).apply {
            text = "До обязательной паузы осталось 43 мин"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(yellow)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 18)
        }

        root.addView(breakRemaining)

        addSeparator(root)

        /*
         * ДНЕВНОЕ ВОЖДЕНИЕ
         */

        addSectionTitle(
            root,
            "Вождение сегодня"
        )

        val dailyDriving = TextView(this).apply {
            text = "7:15 из 9:00"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        root.addView(dailyDriving)

        val dailyProgress =
            createProgressBar(
                progress = 435,
                max = 540,
                color = green
            )

        root.addView(dailyProgress)

        val dailyRemaining = TextView(this).apply {
            text = "Осталось сегодня: 1 ч 45 мин"
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 4)
        }

        val extendedDay = TextView(this).apply {
            text = "Обычный лимит: 9 часов"
            textSize = 14f
            setTextColor(gray)
            gravity = Gravity.CENTER
        }

        root.addView(dailyRemaining)
        root.addView(extendedDay)

        addSeparator(root)

        /*
         * ТЕКУЩАЯ ПАУЗА
         */

        addSectionTitle(
            root,
            "Пауза"
        )

        val breakTime = TextView(this).apply {
            text = "00:00"
            textSize = 32f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        root.addView(breakTime)

        val breakProgress =
            createProgressBar(
                progress = 0,
                max = 45,
                color = red
            )

        root.addView(breakProgress)

        val breakStatus = TextView(this).apply {
            text = "Для полного отдыха требуется 45 минут"
            textSize = 16f
            setTextColor(red)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 4)
        }

        root.addView(breakStatus)

        val splitBreakStatus = TextView(this).apply {
            text = "Разделённая пауза: пока не используется"
            textSize = 14f
            setTextColor(gray)
            gravity = Gravity.CENTER
        }

        root.addView(splitBreakStatus)

        addSeparator(root)

        /*
         * СУТОЧНЫЙ ОТДЫХ
         */

        addSectionTitle(
            root,
            "Суточный отдых"
        )

        val dailyRestDeadline = TextView(this).apply {
            text = "Начать суточный отдых\nне позднее 21:35"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(blue)
            setPadding(0, 12, 0, 12)
        }

        root.addView(dailyRestDeadline)

        val restType = TextView(this).apply {
            text = "План: нормальный суточный отдых"
            textSize = 15f
            setTextColor(gray)
            gravity = Gravity.CENTER
        }

        root.addView(restType)

        addSeparator(root)

        /*
         * СМЕНА
         */

        addSectionTitle(
            root,
            "Текущая смена"
        )

        val shiftStart = TextView(this).apply {
            text = "Смена открыта: 06:42"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val country = TextView(this).apply {
            text = "Страна начала: Германия"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(gray)
            setPadding(0, 6, 0, 4)
        }

        root.addView(shiftStart)
        root.addView(country)

        addSeparator(root)

        /*
         * НЕДЕЛЯ / ДВЕ НЕДЕЛИ
         */

        addSectionTitle(
            root,
            "Итоги"
        )

        addCompactRow(
            root,
            "Вождение за неделю",
            "31:20 / 56:00"
        )

        addCompactRow(
            root,
            "За две недели",
            "67:45 / 90:00"
        )

        addCompactRow(
            root,
            "Рабочее время сегодня",
            "08:03"
        )

        addCompactRow(
            root,
            "Готовность сегодня",
            "01:12"
        )

        addSeparator(root)

        /*
         * ПРЕДУПРЕЖДЕНИЯ
         */

        addSectionTitle(
            root,
            "Предупреждения"
        )

        val warning = TextView(this).apply {
            text = "Сейчас критических предупреждений нет"
            textSize = 16f
            setTextColor(green)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 16)
        }

        root.addView(warning)

        /*
         * BLUETOOTH / ДИАГНОСТИКА
         */

        val connectionStatus = TextView(this).apply {
            text = "DTCO: не подключён"
            textSize = 15f
            setTextColor(gray)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 8)
        }

        root.addView(connectionStatus)

        val diagnosticButton = Button(this).apply {
            text = "ДИАГНОСТИКА DTCO"

            setOnClickListener {

                val intent =
                    Intent(
                        this@DashboardActivity,
                        MainActivity::class.java
                    )

                startActivity(intent)
            }
        }

        root.addView(diagnosticButton)

        val testDataNote = TextView(this).apply {
            text =
                "Сейчас на экране используются тестовые данные. " +
                "После подключения к тахографу они будут заменены реальными."
            textSize = 12f
            setTextColor(gray)
            gravity = Gravity.CENTER
            setPadding(8, 18, 8, 10)
        }

        root.addView(testDataNote)

        setContentView(scrollView)
    }

    private fun addSectionTitle(
        parent: LinearLayout,
        text: String
    ) {

        val view = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(gray)
            setPadding(0, 12, 0, 6)
        }

        parent.addView(view)
    }

    private fun addSeparator(
        parent: LinearLayout
    ) {

        val separator =
            View(this).apply {
                setBackgroundColor(lightGray)
            }

        val params =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            )

        params.setMargins(
            0,
            20,
            0,
            20
        )

        parent.addView(
            separator,
            params
        )
    }

    private fun createProgressBar(
        progress: Int,
        max: Int,
        color: Int
    ): ProgressBar {

        return ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {

            this.max = max
            this.progress = progress

            progressTintList =
                ColorStateList.valueOf(
                    color
                )

            progressBackgroundTintList =
                ColorStateList.valueOf(
                    lightGray
                )

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    22
                ).apply {

                    setMargins(
                        0,
                        14,
                        0,
                        4
                    )
                }
        }
    }

    private fun addCompactRow(
        parent: LinearLayout,
        title: String,
        value: String
    ) {

        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    0,
                    10,
                    0,
                    10
                )
            }

        val titleView =
            TextView(this).apply {
                text = title
                textSize = 16f
            }

        val valueView =
            TextView(this).apply {
                text = value
                textSize = 16f
                setTypeface(
                    null,
                    Typeface.BOLD
                )

                gravity =
                    Gravity.END
            }

        row.addView(
            titleView,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        row.addView(
            valueView
        )

        parent.addView(row)
    }
}
