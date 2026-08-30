package com.pylikv.tachowatch

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DashboardActivity : AppCompatActivity() {

    companion object {

        private const val BG =
            0xFF0B1118.toInt()

        private const val CARD =
            0xFF141D27.toInt()

        private const val CARD_DARK =
            0xFF101821.toInt()

        private const val TEXT =
            0xFFF4F7FA.toInt()

        private const val MUTED =
            0xFF97A7B6.toInt()

        private const val BORDER =
            0xFF273849.toInt()

        private const val GREEN =
            0xFF45C97A.toInt()

        private const val YELLOW =
            0xFFFFD54F.toInt()

        private const val ORANGE =
            0xFFFFA726.toInt()

        private const val RED =
            0xFFEF5350.toInt()

        private const val CYAN =
            0xFF32B6C9.toInt()

        private const val BLUE =
            0xFF3298E8.toInt()
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        window.statusBarColor = BG
        window.navigationBarColor = BG

        showDashboard()
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    private fun showDashboard() {

        /*
         * ==========================================================
         * ВРЕМЕННЫЕ ТЕСТОВЫЕ ДАННЫЕ
         * ==========================================================
         *
         * Позже эти значения заменим данными DTCO
         * и локальными расчётами TachoWatch.
         */

        val driverName =
            "Василий Пылик"

        val currentActivity =
            "ВОЖДЕНИЕ"

        // Непрерывное вождение
        val continuousUsedMinutes =
            227 // 3:47

        val continuousLimitMinutes =
            270 // 4:30

        val continuousRemaining =
            continuousLimitMinutes -
                continuousUsedMinutes

        // Дневное вождение
        val dailyUsedMinutes =
            435 // 7:15

        /*
         * false = стандартный лимит 9 часов.
         * true = используется продление до 10 часов.
         */
        val tenHourDay =
            false

        val dailyLimitMinutes =
            if (tenHourDay) {
                600
            } else {
                540
            }

        val dailyRemaining =
            dailyLimitMinutes -
                dailyUsedMinutes

        // Текущая пауза
        val breakMinutes =
            18

        val requiredBreakMinutes =
            45

        val breakRemaining =
            (
                requiredBreakMinutes -
                    breakMinutes
                ).coerceAtLeast(0)

        // Отдых
        val dailyRestDeadline =
            "21:35"

        val weeklyRestDeadline =
            "Сб 18:00"

        // Вождение за неделю
        val weeklyUsedMinutes =
            51 * 60

        val weeklyLimitMinutes =
            56 * 60

        val weeklyRemaining =
            weeklyLimitMinutes -
                weeklyUsedMinutes

        // Вождение за две недели
        val twoWeekUsedMinutes =
            82 * 60

        val twoWeekLimitMinutes =
            90 * 60

        val twoWeekRemaining =
            twoWeekLimitMinutes -
                twoWeekUsedMinutes

        /*
         * ==========================================================
         * ЦВЕТОВАЯ ЛОГИКА
         * ==========================================================
         */

        val continuousColor =
            when {
                continuousRemaining <= 0 ->
                    RED

                continuousRemaining <= 15 ->
                    ORANGE

                continuousRemaining <= 30 ->
                    YELLOW

                else ->
                    GREEN
            }

        val dailyColor =
            when {
                dailyRemaining <= 0 ->
                    RED

                dailyRemaining <= 30 ->
                    ORANGE

                dailyRemaining <= 60 ->
                    YELLOW

                else ->
                    GREEN
            }

        val breakColor =
            when {
                breakMinutes >=
                    requiredBreakMinutes ->
                    GREEN

                breakRemaining <= 5 ->
                    YELLOW

                else ->
                    ORANGE
            }

        val weeklyColor =
            when {
                weeklyRemaining <=
                    2 * 60 ->
                    RED

                weeklyRemaining <=
                    5 * 60 ->
                    YELLOW

                else ->
                    GREEN
            }

        val twoWeekColor =
            when {
                twoWeekRemaining <=
                    2 * 60 ->
                    RED

                twoWeekRemaining <=
                    5 * 60 ->
                    YELLOW

                else ->
                    GREEN
            }

        /*
         * ==========================================================
         * ОСНОВНОЙ ЭКРАН
         * ==========================================================
         */

        val scroll =
            ScrollView(this).apply {

                setBackgroundColor(
                    BG
                )

                isFillViewport =
                    true
            }

        val root =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(16),
                    dp(16),
                    dp(16),
                    dp(28)
                )

                setBackgroundColor(
                    BG
                )
            }

        scroll.addView(
            root
        )

        /*
         * ШАПКА
         */

        val header =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val headerText =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL
            }

        val title =
            TextView(this).apply {

                text =
                    "TachoWatch"

                textSize =
                    28f

                setTextColor(
                    TEXT
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }

        val subtitle =
            TextView(this).apply {

                text =
                    "Режим труда и отдыха"

                textSize =
                    13f

                setTextColor(
                    CYAN
                )
            }

        headerText.addView(
            title
        )

        headerText.addView(
            subtitle
        )

        val diagnosticButton =
            createSmallButton(
                "DIAG",
                BLUE
            ) {

                startActivity(
                    Intent(
                        this,
                        MainActivity::class.java
                    )
                )
            }

        header.addView(
            headerText,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        header.addView(
            diagnosticButton
        )

        root.addView(
            header
        )

        addSpace(
            root,
            14
        )

        /*
         * ВОДИТЕЛЬ
         */

        val driverCard =
            createCard(
                accentColor = CYAN
            )

        addSmallLabel(
            driverCard,
            "ВОДИТЕЛЬ"
        )

        val driverText =
            TextView(this).apply {

                text =
                    driverName

                textSize =
                    21f

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    TEXT
                )

                setPadding(
                    0,
                    dp(7),
                    0,
                    0
                )
            }

        driverCard.addView(
            driverText
        )

        root.addView(
            driverCard
        )

        addSpace(
            root,
            10
        )

        /*
         * ТЕКУЩИЙ РЕЖИМ
         */

        val activityCard =
            createCard(
                accentColor = GREEN
            )

        val activityText =
            TextView(this).apply {

                text =
                    "●  $currentActivity"

                textSize =
                    24f

                gravity =
                    Gravity.CENTER

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    GREEN
                )

                setPadding(
                    0,
                    dp(6),
                    0,
                    dp(6)
                )
            }

        activityCard.addView(
            activityText
        )

        root.addView(
            activityCard
        )

        addSpace(
            root,
            10
        )

        /*
         * ОСТАЛОСЬ ДО ОБЯЗАТЕЛЬНОЙ ПАУЗЫ
         */

        val continuousCard =
            createCard(
                accentColor =
                    continuousColor,
                strongerAccent = true
            )

        addSmallLabel(
            continuousCard,
            "ОСТАЛОСЬ ДО ОБЯЗАТЕЛЬНОЙ ПАУЗЫ",
            continuousColor
        )

        val remainingBig =
            TextView(this).apply {

                text =
                    formatDuration(
                        continuousRemaining
                    )

                textSize =
                    46f

                gravity =
                    Gravity.CENTER

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    continuousColor
                )

                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(2)
                )
            }

        continuousCard.addView(
            remainingBig
        )

        val continuousDescription =
            TextView(this).apply {

                text =
                    "${formatDuration(continuousUsedMinutes)} из 4:30"

                textSize =
                    17f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    TEXT
                )
            }

        continuousCard.addView(
            continuousDescription
        )

        continuousCard.addView(
            createProgress(
                continuousUsedMinutes,
                continuousLimitMinutes,
                continuousColor
            )
        )

        root.addView(
            continuousCard
        )

        addSpace(
            root,
            10
        )

        /*
         * ДНЕВНОЕ ВОЖДЕНИЕ
         */

        val dailyCard =
            createCard(
                accentColor =
                    dailyColor
            )

        val dailyTop =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val dailyTitle =
            TextView(this).apply {

                text =
                    "ДНЕВНОЕ ВОЖДЕНИЕ"

                textSize =
                    12f

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    MUTED
                )
            }

        val dailyLimitBadge =
            TextView(this).apply {

                text =
                    if (tenHourDay) {
                        "ЛИМИТ 10 Ч"
                    } else {
                        "ЛИМИТ 9 Ч"
                    }

                textSize =
                    11f

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    dailyColor
                )

                gravity =
                    Gravity.END
            }

        dailyTop.addView(
            dailyTitle,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        dailyTop.addView(
            dailyLimitBadge
        )

        dailyCard.addView(
            dailyTop
        )

        val dailyValue =
            TextView(this).apply {

                text =
                    "${formatDuration(dailyUsedMinutes)} из " +
                        if (tenHourDay) {
                            "10:00"
                        } else {
                            "9:00"
                        }

                textSize =
                    29f

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    dailyColor
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            }

        dailyCard.addView(
            dailyValue
        )

        dailyCard.addView(
            createProgress(
                dailyUsedMinutes,
                dailyLimitMinutes,
                dailyColor
            )
        )

        val dailyRemainingText =
            TextView(this).apply {

                text =
                    "Осталось: " +
                        formatWords(
                            dailyRemaining
                        )

                textSize =
                    16f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    TEXT
                )

                setPadding(
                    0,
                    dp(8),
                    0,
                    0
                )
            }

        dailyCard.addView(
            dailyRemainingText
        )

        root.addView(
            dailyCard
        )

        addSpace(
            root,
            10
        )

        /*
         * ТЕКУЩАЯ ПАУЗА
         */

        val breakCard =
            createCard(
                accentColor =
                    breakColor
            )

        addSmallLabel(
            breakCard,
            "ТЕКУЩАЯ ПАУЗА",
            breakColor
        )

        val breakValue =
            TextView(this).apply {

                text =
                    formatDuration(
                        breakMinutes
                    )

                textSize =
                    34f

                gravity =
                    Gravity.CENTER

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    breakColor
                )

                setPadding(
                    0,
                    dp(8),
                    0,
                    0
                )
            }

        breakCard.addView(
            breakValue
        )

        breakCard.addView(
            createProgress(
                breakMinutes,
                requiredBreakMinutes,
                breakColor
            )
        )

        val breakStatus =
            TextView(this).apply {

                text =
                    if (
                        breakMinutes >=
                        requiredBreakMinutes
                    ) {

                        "Пауза состоялась"

                    } else {

                        "До полной паузы осталось " +
                            "$breakRemaining мин"
                    }

                textSize =
                    16f

                gravity =
                    Gravity.CENTER

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    breakColor
                )

                setPadding(
                    0,
                    dp(8),
                    0,
                    0
                )
            }

        breakCard.addView(
            breakStatus
        )

        root.addView(
            breakCard
        )

        addSpace(
            root,
            10
        )

        /*
         * СУТОЧНЫЙ / НЕДЕЛЬНЫЙ ОТДЫХ
         */

        val restRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        val dailyRestCard =
            createMiniCard(
                title =
                    "СУТОЧНЫЙ ОТДЫХ",
                value =
                    "Начать до\n$dailyRestDeadline",
                color =
                    CYAN
            )

        val weeklyRestCard =
            createMiniCard(
                title =
                    "НЕДЕЛЬНЫЙ ОТДЫХ",
                value =
                    "Начать до\n$weeklyRestDeadline",
                color =
                    BLUE
            )

        restRow.addView(
            dailyRestCard,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {

                setMargins(
                    0,
                    0,
                    dp(5),
                    0
                )
            }
        )

        restRow.addView(
            weeklyRestCard,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {

                setMargins(
                    dp(5),
                    0,
                    0,
                    0
                )
            }
        )

        root.addView(
            restRow
        )

        addSpace(
            root,
            10
        )

        /*
         * НЕДЕЛЯ / ДВЕ НЕДЕЛИ
         */

        val weekRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        val weekCard =
            createLimitCard(
                title =
                    "ЗА НЕДЕЛЮ",
                usedMinutes =
                    weeklyUsedMinutes,
                limitMinutes =
                    weeklyLimitMinutes,
                color =
                    weeklyColor,
                warningText =
                    "Осталось " +
                        formatWords(
                            weeklyRemaining
                        )
            )

        val twoWeekCard =
            createLimitCard(
                title =
                    "ЗА 2 НЕДЕЛИ",
                usedMinutes =
                    twoWeekUsedMinutes,
                limitMinutes =
                    twoWeekLimitMinutes,
                color =
                    twoWeekColor,
                warningText =
                    "Осталось " +
                        formatWords(
                            twoWeekRemaining
                        )
            )

        weekRow.addView(
            weekCard,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {

                setMargins(
                    0,
                    0,
                    dp(5),
                    0
                )
            }
        )

        weekRow.addView(
            twoWeekCard,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {

                setMargins(
                    dp(5),
                    0,
                    0,
                    0
                )
            }
        )

        root.addView(
            weekRow
        )

        addSpace(
            root,
            14
        )

        /*
         * НИЖНЯЯ СТРОКА
         */

        val connection =
            TextView(this).apply {

                text =
                    "DTCO: тестовый режим"

                textSize =
                    13f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    MUTED
                )

                setPadding(
                    0,
                    dp(5),
                    0,
                    dp(5)
                )
            }

        root.addView(
            connection
        )

        val note =
            TextView(this).apply {

                text =
                    "Сейчас показаны тестовые данные. " +
                        "После подключения к тахографу " +
                        "карточки будут обновляться автоматически."

                textSize =
                    11f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    0xFF718190.toInt()
                )
            }

        root.addView(
            note
        )

        setContentView(
            scroll
        )
    }

    /*
     * ==========================================================
     * КАРТОЧКИ
     * ==========================================================
     */

    private fun createCard(
        accentColor: Int,
        strongerAccent: Boolean = false
    ): LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(16),
                dp(14),
                dp(16),
                dp(14)
            )

            background =
                cardDrawable(
                    accentColor,
                    if (strongerAccent) {
                        dp(2)
                    } else {
                        dp(1)
                    }
                )
        }
    }

    private fun createMiniCard(
        title: String,
        value: String,
        color: Int
    ): LinearLayout {

        return createCard(
            accentColor = color
        ).apply {

            addSmallLabel(
                this,
                title,
                color
            )

            addView(
                TextView(
                    this@DashboardActivity
                ).apply {

                    text =
                        value

                    textSize =
                        18f

                    gravity =
                        Gravity.CENTER

                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )

                    setTextColor(
                        color
                    )

                    setPadding(
                        0,
                        dp(10),
                        0,
                        dp(4)
                    )
                }
            )
        }
    }

    private fun createLimitCard(
        title: String,
        usedMinutes: Int,
        limitMinutes: Int,
        color: Int,
        warningText: String
    ): LinearLayout {

        return createCard(
            accentColor = color
        ).apply {

            addSmallLabel(
                this,
                title,
                color
            )

            addView(
                TextView(
                    this@DashboardActivity
                ).apply {

                    text =
                        "${formatDuration(usedMinutes)} / " +
                            formatDuration(
                                limitMinutes
                            )

                    textSize =
                        18f

                    gravity =
                        Gravity.CENTER

                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )

                    setTextColor(
                        color
                    )

                    setPadding(
                        0,
                        dp(9),
                        0,
                        0
                    )
                }
            )

            addView(
                createProgress(
                    usedMinutes,
                    limitMinutes,
                    color
                )
            )

            addView(
                TextView(
                    this@DashboardActivity
                ).apply {

                    text =
                        warningText

                    textSize =
                        13f

                    gravity =
                        Gravity.CENTER

                    setTextColor(
                        color
                    )

                    setPadding(
                        0,
                        dp(7),
                        0,
                        0
                    )
                }
            )
        }
    }

    private fun cardDrawable(
        accentColor: Int,
        strokeWidth: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                dp(18).toFloat()

            setColor(
                CARD
            )

            setStroke(
                strokeWidth,
                accentColor
            )
        }
    }

    /*
     * ==========================================================
     * ПРОГРЕСС
     * ==========================================================
     */

    private fun createProgress(
        progress: Int,
        max: Int,
        color: Int
    ): ProgressBar {

        return ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {

            this.max =
                max

            this.progress =
                progress.coerceIn(
                    0,
                    max
                )

            progressTintList =
                ColorStateList.valueOf(
                    color
                )

            progressBackgroundTintList =
                ColorStateList.valueOf(
                    0xFF26333F.toInt()
                )

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(7)
                ).apply {

                    setMargins(
                        0,
                        dp(10),
                        0,
                        0
                    )
                }
        }
    }

    /*
     * ==========================================================
     * МЕЛКИЕ ЭЛЕМЕНТЫ
     * ==========================================================
     */

    private fun addSmallLabel(
        parent: LinearLayout,
        text: String,
        color: Int = MUTED
    ) {

        parent.addView(
            TextView(this).apply {

                this.text =
                    text

                textSize =
                    11f

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    color
                )
            }
        )
    }

    private fun addSpace(
        parent: LinearLayout,
        height: Int
    ) {

        parent.addView(
            View(this),
            LinearLayout.LayoutParams(
                1,
                dp(height)
            )
        )
    }

    private fun createSmallButton(
        text: String,
        color: Int,
        onClick: () -> Unit
    ): Button {

        return Button(this).apply {

            this.text =
                text

            textSize =
                12f

            isAllCaps =
                false

            setTextColor(
                Color.WHITE
            )

            setTypeface(
                typeface,
                Typeface.BOLD
            )

            setPadding(
                dp(10),
                0,
                dp(10),
                0
            )

            background =
                GradientDrawable().apply {

                    shape =
                        GradientDrawable.RECTANGLE

                    cornerRadius =
                        dp(14).toFloat()

                    setColor(
                        color
                    )
                }

            setOnClickListener {

                onClick()
            }
        }
    }

    /*
     * ==========================================================
     * ФОРМАТИРОВАНИЕ ВРЕМЕНИ
     * ==========================================================
     */

    private fun formatDuration(
        totalMinutes: Int
    ): String {

        val safe =
            totalMinutes.coerceAtLeast(0)

        val hours =
            safe / 60

        val minutes =
            safe % 60

        return String.format(
            "%d:%02d",
            hours,
            minutes
        )
    }

    private fun formatWords(
        totalMinutes: Int
    ): String {

        val safe =
            totalMinutes.coerceAtLeast(0)

        val hours =
            safe / 60

        val minutes =
            safe % 60

        return when {

            hours > 0 &&
                minutes > 0 ->
                "$hours ч $minutes мин"

            hours > 0 ->
                "$hours ч"

            else ->
                "$minutes мин"
        }
    }
}
