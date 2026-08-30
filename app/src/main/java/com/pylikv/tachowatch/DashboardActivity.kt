package com.pylikv.tachowatch

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

class DashboardActivity : AppCompatActivity() {

    companion object {

        private const val BG =
            0xFF0B1118.toInt()

        private const val CARD =
            0xFF141D27.toInt()

        private const val TEXT =
            0xFFF4F7FA.toInt()

        private const val MUTED =
            0xFF97A7B6.toInt()

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

        private const val PURPLE =
            0xFFAB7DF6.toInt()

        private const val SWIPE_DISTANCE =
            100
    }

    private lateinit var flipper: ViewFlipper
    private lateinit var gestureDetector: GestureDetector

    private var pageNowLabel: TextView? = null
    private var pageHistoryLabel: TextView? = null

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BG
        window.navigationBarColor = BG

        gestureDetector =
            GestureDetector(
                this,
                object :
                    GestureDetector.SimpleOnGestureListener() {

                    override fun onDown(
                        e: MotionEvent
                    ): Boolean {
                        return true
                    }

                    override fun onFling(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        velocityX: Float,
                        velocityY: Float
                    ): Boolean {

                        if (e1 == null) {
                            return false
                        }

                        val dx =
                            e2.x - e1.x

                        val dy =
                            e2.y - e1.y

                        if (
                            abs(dx) >
                            abs(dy) &&
                            abs(dx) >
                            SWIPE_DISTANCE
                        ) {

                            if (dx < 0) {
                                showPage(1)
                            } else {
                                showPage(0)
                            }

                            return true
                        }

                        return false
                    }
                }
            )

        buildApp()
    }

    override fun dispatchTouchEvent(
        event: MotionEvent
    ): Boolean {

        gestureDetector.onTouchEvent(
            event
        )

        return super.dispatchTouchEvent(
            event
        )
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    /*
     * ============================================================
     * ОСНОВНАЯ СТРУКТУРА
     * ============================================================
     */

    private fun buildApp() {

        val outer =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    BG
                )
            }

        flipper =
            ViewFlipper(this).apply {

                setBackgroundColor(
                    BG
                )
            }

        flipper.addView(
            createNowScreen()
        )

        flipper.addView(
            createHistoryScreen()
        )

        outer.addView(
            flipper,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        outer.addView(
            createBottomNavigation()
        )

        setContentView(
            outer
        )

        updateNavigation()
    }

    /*
     * ============================================================
     * ЭКРАН №1 — СЕЙЧАС
     * ============================================================
     */

    private fun createNowScreen(): View {

        /*
         * ВРЕМЕННЫЕ ТЕСТОВЫЕ ДАННЫЕ.
         *
         * Позже сюда подключим реальные данные DTCO.
         */

        val driverName =
            "Василий Пылик"

        val currentActivity =
            "ВОЖДЕНИЕ"

        /*
         * Непрерывное вождение
         */

        val continuousUsedMinutes =
            227

        val continuousLimitMinutes =
            270

        val continuousRemaining =
            continuousLimitMinutes -
                continuousUsedMinutes

        /*
         * Разделённая пауза 15 + 30.
         *
         * Здесь для примера уже выполнена
         * первая часть 15 минут.
         */

        val firstBreakPartCompleted =
            true

        val firstBreakMinutes =
            15

        val currentSecondBreakMinutes =
            18

        val secondBreakRequired =
            30

        /*
         * Дневное вождение
         */

        val dailyUsedMinutes =
            435

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

        /*
         * Суточный отдых 3 + 9.
         *
         * Для примера приложение уже
         * запомнило 3-часовую часть.
         */

        val threeHourRestCompleted =
            true

        val firstDailyRestMinutes =
            3 * 60 + 8

        /*
         * Дедлайны отдыха
         */

        val dailyRestDeadline =
            "21:35"

        val weeklyRestDeadline =
            "Сб 18:00"

        /*
         * Неделя
         */

        val weeklyUsedMinutes =
            51 * 60

        val weeklyLimitMinutes =
            56 * 60

        val weeklyRemaining =
            weeklyLimitMinutes -
                weeklyUsedMinutes

        /*
         * Две недели
         */

        val twoWeekUsedMinutes =
            82 * 60

        val twoWeekLimitMinutes =
            90 * 60

        val twoWeekRemaining =
            twoWeekLimitMinutes -
                twoWeekUsedMinutes

        /*
         * Цвет непрерывного вождения
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

        /*
         * Цвет дневного вождения
         */

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

        /*
         * Цвет разделённой паузы
         */

        val secondBreakRemaining =
            (
                secondBreakRequired -
                    currentSecondBreakMinutes
                ).coerceAtLeast(0)

        val breakColor =
            when {

                currentSecondBreakMinutes >=
                    secondBreakRequired ->
                    GREEN

                firstBreakPartCompleted &&
                    secondBreakRemaining <= 5 ->
                    YELLOW

                firstBreakPartCompleted ->
                    ORANGE

                else ->
                    RED
            }

        /*
         * Цвет недельного вождения
         */

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

        /*
         * Цвет двухнедельного вождения
         */

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
                CYAN
            )

        addSmallLabel(
            driverCard,
            "ВОДИТЕЛЬ"
        )

        driverCard.addView(
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
                GREEN
            )

        activityCard.addView(
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
        )

        root.addView(
            activityCard
        )

        addSpace(
            root,
            10
        )

        /*
         * ОБЯЗАТЕЛЬНАЯ ПАУЗА
         */

        val continuousCard =
            createCard(
                continuousColor,
                true
            )

        addSmallLabel(
            continuousCard,
            "ОСТАЛОСЬ ДО ОБЯЗАТЕЛЬНОЙ ПАУЗЫ",
            continuousColor
        )

        continuousCard.addView(
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
        )

        continuousCard.addView(
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
                dailyColor
            )

        val dailyHeader =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        dailyHeader.addView(
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
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        dailyHeader.addView(
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
            }
        )

        dailyCard.addView(
            dailyHeader
        )

        dailyCard.addView(
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

                gravity =
                    Gravity.CENTER

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    dailyColor
                )

                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            }
        )

        dailyCard.addView(
            createProgress(
                dailyUsedMinutes,
                dailyLimitMinutes,
                dailyColor
            )
        )

        dailyCard.addView(
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
        )

        root.addView(
            dailyCard
        )

        addSpace(
            root,
            10
        )

        /*
         * ПАУЗА 15 + 30
         */

        val breakCard =
            createCard(
                breakColor,
                true
            )

        addSmallLabel(
            breakCard,
            "ПАУЗА В ВОЖДЕНИИ • 15 + 30",
            breakColor
        )

        if (
            firstBreakPartCompleted
        ) {

            breakCard.addView(
                TextView(this).apply {

                    text =
                        "✓ Первая часть: " +
                            "$firstBreakMinutes мин"

                    textSize =
                        17f

                    gravity =
                        Gravity.CENTER

                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )

                    setTextColor(
                        ORANGE
                    )

                    setPadding(
                        0,
                        dp(8),
                        0,
                        dp(2)
                    )
                }
            )

            breakCard.addView(
                TextView(this).apply {

                    text =
                        "Вторая часть"

                    textSize =
                        12f

                    gravity =
                        Gravity.CENTER

                    setTextColor(
                        MUTED
                    )
                }
            )

            breakCard.addView(
                TextView(this).apply {

                    text =
                        formatDuration(
                            currentSecondBreakMinutes
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
                }
            )

            breakCard.addView(
                createProgress(
                    currentSecondBreakMinutes,
                    secondBreakRequired,
                    breakColor
                )
            )

            breakCard.addView(
                TextView(this).apply {

                    text =
                        if (
                            secondBreakRemaining == 0
                        ) {
                            "Пауза 15 + 30 выполнена"
                        } else {
                            "До выполнения осталось " +
                                "$secondBreakRemaining мин"
                        }

                    textSize =
                        15f

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
            )

        } else {

            breakCard.addView(
                TextView(this).apply {

                    text =
                        "Первая цель: 15 минут"

                    textSize =
                        18f

                    gravity =
                        Gravity.CENTER

                    setTextColor(
                        RED
                    )
                }
            )
        }

        root.addView(
            breakCard
        )

        addSpace(
            root,
            10
        )

        /*
         * СУТОЧНЫЙ ОТДЫХ 3 + 9
         */

        val dailyRestCard =
            createCard(
                if (
                    threeHourRestCompleted
                ) {
                    ORANGE
                } else {
                    CYAN
                }
            )

        addSmallLabel(
            dailyRestCard,
            "СУТОЧНЫЙ ОТДЫХ",
            if (
                threeHourRestCompleted
            ) {
                ORANGE
            } else {
                CYAN
            }
        )

        dailyRestCard.addView(
            TextView(this).apply {

                text =
                    "Начать до $dailyRestDeadline"

                textSize =
                    22f

                gravity =
                    Gravity.CENTER

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    CYAN
                )

                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(4)
                )
            }
        )

        if (
            threeHourRestCompleted
        ) {

            dailyRestCard.addView(
                TextView(this).apply {

                    text =
                        "✓ Засчитана первая часть: " +
                            formatDuration(
                                firstDailyRestMinutes
                            )

                    textSize =
                        16f

                    gravity =
                        Gravity.CENTER

                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )

                    setTextColor(
                        ORANGE
                    )

                    setPadding(
                        0,
                        dp(4),
                        0,
                        dp(3)
                    )
                }
            )

            dailyRestCard.addView(
                TextView(this).apply {

                    text =
                        "Для разделённого суточного отдыха " +
                            "осталась часть не менее 9:00"

                    textSize =
                        14f

                    gravity =
                        Gravity.CENTER

                    setTextColor(
                        MUTED
                    )
                }
            )
        }

        root.addView(
            dailyRestCard
        )

        addSpace(
            root,
            10
        )

        /*
         * НЕДЕЛЬНЫЙ ОТДЫХ
         */

        val weeklyRestCard =
            createCard(
                BLUE
            )

        addSmallLabel(
            weeklyRestCard,
            "НЕДЕЛЬНЫЙ ОТДЫХ",
            BLUE
        )

        weeklyRestCard.addView(
            TextView(this).apply {

                text =
                    "Начать до $weeklyRestDeadline"

                textSize =
                    22f

                gravity =
                    Gravity.CENTER

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    BLUE
                )

                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(4)
                )
            }
        )

        root.addView(
            weeklyRestCard
        )

        addSpace(
            root,
            10
        )

        /*
         * НЕДЕЛЯ / 2 НЕДЕЛИ
         */

        val weekRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        weekRow.addView(
            createLimitCard(
                "ЗА НЕДЕЛЮ",
                weeklyUsedMinutes,
                weeklyLimitMinutes,
                weeklyColor,
                "Осталось " +
                    formatWords(
                        weeklyRemaining
                    )
            ),
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
            createLimitCard(
                "ЗА 2 НЕДЕЛИ",
                twoWeekUsedMinutes,
                twoWeekLimitMinutes,
                twoWeekColor,
                "Осталось " +
                    formatWords(
                        twoWeekRemaining
                    )
            ),
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
            18
        )

        root.addView(
            TextView(this).apply {

                text =
                    "←  Свайп влево: история 56 дней"

                textSize =
                    12f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    MUTED
                )

                setPadding(
                    0,
                    dp(4),
                    0,
                    dp(8)
                )
            }
        )

        root.addView(
            TextView(this).apply {

                text =
                    "Сейчас используются тестовые данные"

                textSize =
                    11f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    0xFF718190.toInt()
                )
            }
        )

        return scroll
    }

    /*
     * ============================================================
     * ЭКРАН №2 — ИСТОРИЯ 56 ДНЕЙ
     * ============================================================
     */

    private fun createHistoryScreen(): View {

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

        val title =
            TextView(this).apply {

                text =
                    "История"

                textSize =
                    28f

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    TEXT
                )
            }

        val subtitle =
            TextView(this).apply {

                text =
                    "Журнал работы • последние 56 дней"

                textSize =
                    13f

                setTextColor(
                    PURPLE
                )
            }

        root.addView(
            title
        )

        root.addView(
            subtitle
        )

        addSpace(
            root,
            14
        )

        /*
         * ТЕСТОВЫЕ ДАННЫЕ ИСТОРИИ
         */

        val period1 =
            listOf(
                ShiftRecord(
                    "Пн 17.08",
                    "06:42",
                    "18:27",
                    "7:38",
                    "1:26",
                    "0:44",
                    "11:12"
                ),
                ShiftRecord(
                    "Вт 18.08",
                    "05:39",
                    "17:46",
                    "8:21",
                    "0:58",
                    "1:03",
                    "11:53"
                ),
                ShiftRecord(
                    "Ср 19.08",
                    "05:39",
                    "17:12",
                    "6:47",
                    "2:05",
                    "0:36",
                    "12:18"
                ),
                ShiftRecord(
                    "Чт 20.08",
                    "05:30",
                    "18:10",
                    "8:54",
                    "1:12",
                    "0:51",
                    "9:24"
                ),
                ShiftRecord(
                    "Пт 21.08",
                    "03:34",
                    "16:44",
                    "7:55",
                    "2:11",
                    "0:48",
                    "11:06"
                )
            )

        val period2 =
            listOf(
                ShiftRecord(
                    "Пн 24.08",
                    "06:15",
                    "18:33",
                    "8:08",
                    "1:47",
                    "0:38",
                    "11:26"
                ),
                ShiftRecord(
                    "Вт 25.08",
                    "05:59",
                    "18:02",
                    "7:46",
                    "1:54",
                    "0:49",
                    "3:08 + 9:14"
                ),
                ShiftRecord(
                    "Ср 26.08",
                    "06:24",
                    "18:10",
                    "8:32",
                    "1:06",
                    "0:52",
                    "11:41"
                ),
                ShiftRecord(
                    "Чт 27.08",
                    "05:51",
                    "17:45",
                    "7:18",
                    "2:02",
                    "0:46",
                    "9:37"
                ),
                ShiftRecord(
                    "Пт 28.08",
                    "04:58",
                    "17:31",
                    "8:44",
                    "1:22",
                    "0:59",
                    "12:05"
                )
            )

        val period3 =
            listOf(
                ShiftRecord(
                    "Пн 31.08",
                    "06:07",
                    "18:04",
                    "7:53",
                    "1:31",
                    "0:42",
                    "11:19"
                ),
                ShiftRecord(
                    "Вт 01.09",
                    "05:23",
                    "17:58",
                    "8:36",
                    "1:17",
                    "0:51",
                    "3:02 + 9:27"
                ),
                ShiftRecord(
                    "Ср 02.09",
                    "06:25",
                    "17:49",
                    "7:11",
                    "1:49",
                    "0:43",
                    "11:35"
                )
            )

        /*
         * ПЕРИОД 1
         */

        addWorkPeriod(
            root,
            "РАБОЧИЙ ПЕРИОД",
            "17.08 — 21.08",
            period1
        )

        addWeeklyRest(
            root,
            duration =
                "45:32",
            description =
                "Нормальный недельный отдых",
            color =
                GREEN
        )

        /*
         * ПЕРИОД 2
         */

        addWorkPeriod(
            root,
            "РАБОЧИЙ ПЕРИОД",
            "24.08 — 28.08",
            period2
        )

        addWeeklyRest(
            root,
            duration =
                "24:18",
            description =
                "Сокращённый недельный отдых",
            color =
                ORANGE
        )

        /*
         * ПЕРИОД 3
         */

        addWorkPeriod(
            root,
            "РАБОЧИЙ ПЕРИОД",
            "31.08 — 02.09",
            period3
        )

        addWeeklyRest(
            root,
            duration =
                "56:10",
            description =
                "Недельный отдых",
            color =
                CYAN
        )

        addSpace(
            root,
            14
        )

        root.addView(
            TextView(this).apply {

                text =
                    "Свайп вправо → вернуться к экрану «Сейчас»"

                textSize =
                    12f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    MUTED
                )

                setPadding(
                    0,
                    dp(6),
                    0,
                    dp(8)
                )
            }
        )

        root.addView(
            TextView(this).apply {

                text =
                    "Пока история заполнена тестовыми сменами. " +
                        "Позже журнал будет формироваться автоматически " +
                        "из данных тахографа и хранить последние 56 дней."

                textSize =
                    11f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    0xFF718190.toInt()
                )
            }
        )

        return scroll
    }

    /*
     * ============================================================
     * РАБОЧИЙ ПЕРИОД МЕЖДУ НЕДЕЛЬНЫМИ ОТДЫХАМИ
     * ============================================================
     */

    private fun addWorkPeriod(
        parent: LinearLayout,
        title: String,
        dates: String,
        records: List<ShiftRecord>
    ) {

        val periodHeader =
            createCard(
                PURPLE
            )

        addSmallLabel(
            periodHeader,
            title,
            PURPLE
        )

        periodHeader.addView(
            TextView(this).apply {

                text =
                    dates

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
                    dp(5),
                    0,
                    0
                )
            }
        )

        parent.addView(
            periodHeader
        )

        addSpace(
            parent,
            8
        )

        for (
            record in records
        ) {

            parent.addView(
                createShiftCard(
                    record
                )
            )

            addSpace(
                parent,
                7
            )
        }
    }

    /*
     * ============================================================
     * СТРОКА / КАРТОЧКА СМЕНЫ
     * ============================================================
     */

    private fun createShiftCard(
        record: ShiftRecord
    ): LinearLayout {

        val card =
            createCard(
                BLUE
            )

        val top =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        top.addView(
            TextView(this).apply {

                text =
                    record.date

                textSize =
                    17f

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    TEXT
                )
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        top.addView(
            TextView(this).apply {

                text =
                    "${record.open} — ${record.close}"

                textSize =
                    16f

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    CYAN
                )
            }
        )

        card.addView(
            top
        )

        addSpace(
            card,
            10
        )

        /*
         * Первая строка таблицы
         */

        val row1 =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        row1.addView(
            createHistoryCell(
                "СМЕНА ОТКРЫТА",
                record.open,
                CYAN
            ),
            weightedCellParams()
        )

        row1.addView(
            createHistoryCell(
                "ВОЖДЕНИЕ",
                record.driving,
                GREEN
            ),
            weightedCellParams()
        )

        row1.addView(
            createHistoryCell(
                "МОЛОТКИ",
                record.work,
                ORANGE
            ),
            weightedCellParams()
        )

        card.addView(
            row1
        )

        addSpace(
            card,
            8
        )

        /*
         * Вторая строка таблицы
         */

        val row2 =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        row2.addView(
            createHistoryCell(
                "ГОТОВНОСТЬ",
                record.availability,
                BLUE
            ),
            weightedCellParams()
        )

        row2.addView(
            createHistoryCell(
                "СМЕНА ЗАКРЫТА",
                record.close,
                PURPLE
            ),
            weightedCellParams()
        )

        row2.addView(
            createHistoryCell(
                "СУТОЧНАЯ ПАУЗА",
                record.dailyRest,
                dailyRestHistoryColor(
                    record.dailyRest
                )
            ),
            weightedCellParams()
        )

        card.addView(
            row2
        )

        /*
         * Если была пауза 3 + 9,
         * отдельно делаем отметку.
         */

        if (
            record.dailyRest.contains(
                "+"
            )
        ) {

            card.addView(
                TextView(this).apply {

                    text =
                        "✓ Разделённый суточный отдых 3 + 9"

                    textSize =
                        12f

                    gravity =
                        Gravity.CENTER

                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )

                    setTextColor(
                        ORANGE
                    )

                    setPadding(
                        0,
                        dp(9),
                        0,
                        0
                    )
                }
            )
        }

        return card
    }

    /*
     * ============================================================
     * НЕДЕЛЬНЫЙ ОТДЫХ — РАЗДЕЛИТЕЛЬ ТАБЛИЦ
     * ============================================================
     */

    private fun addWeeklyRest(
        parent: LinearLayout,
        duration: String,
        description: String,
        color: Int
    ) {

        addSpace(
            parent,
            5
        )

        val restCard =
            createCard(
                color,
                true
            )

        restCard.addView(
            TextView(this).apply {

                text =
                    "НЕДЕЛЬНЫЙ ОТДЫХ"

                textSize =
                    12f

                gravity =
                    Gravity.CENTER

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setTextColor(
                    color
                )
            }
        )

        restCard.addView(
            TextView(this).apply {

                text =
                    duration

                textSize =
                    34f

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
                    dp(5),
                    0,
                    dp(2)
                )
            }
        )

        restCard.addView(
            TextView(this).apply {

                text =
                    description

                textSize =
                    14f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    TEXT
                )
            }
        )

        parent.addView(
            restCard
        )

        addSpace(
            parent,
            14
        )
    }

    /*
     * ============================================================
     * ЯЧЕЙКА ЖУРНАЛА
     * ============================================================
     */

    private fun createHistoryCell(
        title: String,
        value: String,
        color: Int
    ): LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            gravity =
                Gravity.CENTER

            setPadding(
                dp(3),
                dp(5),
                dp(3),
                dp(5)
            )

            addView(
                TextView(
                    this@DashboardActivity
                ).apply {

                    text =
                        title

                    textSize =
                        9f

                    gravity =
                        Gravity.CENTER

                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )

                    setTextColor(
                        MUTED
                    )
                }
            )

            addView(
                TextView(
                    this@DashboardActivity
                ).apply {

                    text =
                        value

                    textSize =
                        15f

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
                        dp(4),
                        0,
                        0
                    )
                }
            )
        }
    }

    private fun weightedCellParams():
        LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )
    }

    /*
     * ============================================================
     * НИЖНЯЯ НАВИГАЦИЯ
     * ============================================================
     */

    private fun createBottomNavigation():
        LinearLayout {

        val bar =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    dp(12),
                    dp(8),
                    dp(12),
                    dp(10)
                )

                setBackgroundColor(
                    0xFF101821.toInt()
                )
            }

        pageNowLabel =
            createNavigationItem(
                "СЕЙЧАС"
            ) {
                showPage(0)
            }

        pageHistoryLabel =
            createNavigationItem(
                "ИСТОРИЯ 56 ДНЕЙ"
            ) {
                showPage(1)
            }

        bar.addView(
            pageNowLabel,
            LinearLayout.LayoutParams(
                0,
                dp(42),
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

        bar.addView(
            pageHistoryLabel,
            LinearLayout.LayoutParams(
                0,
                dp(42),
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

        return bar
    }

    private fun createNavigationItem(
        title: String,
        click: () -> Unit
    ): TextView {

        return TextView(this).apply {

            text =
                title

            textSize =
                12f

            gravity =
                Gravity.CENTER

            setTypeface(
                typeface,
                Typeface.BOLD
            )

            setTextColor(
                MUTED
            )

            setOnClickListener {
                click()
            }
        }
    }

    private fun showPage(
        page: Int
    ) {

        if (
            page ==
            flipper.displayedChild
        ) {
            return
        }

        val goingLeft =
            page >
                flipper.displayedChild

        val distance =
            if (goingLeft) {
                1f
            } else {
                -1f
            }

        val inAnimation =
            TranslateAnimation(
                TranslateAnimation.RELATIVE_TO_PARENT,
                distance,
                TranslateAnimation.RELATIVE_TO_PARENT,
                0f,
                TranslateAnimation.RELATIVE_TO_PARENT,
                0f,
                TranslateAnimation.RELATIVE_TO_PARENT,
                0f
            ).apply {

                duration =
                    220

                interpolator =
                    AccelerateDecelerateInterpolator()
            }

        val outAnimation =
            TranslateAnimation(
                TranslateAnimation.RELATIVE_TO_PARENT,
                0f,
                TranslateAnimation.RELATIVE_TO_PARENT,
                -distance,
                TranslateAnimation.RELATIVE_TO_PARENT,
                0f,
                TranslateAnimation.RELATIVE_TO_PARENT,
                0f
            ).apply {

                duration =
                    220

                interpolator =
                    AccelerateDecelerateInterpolator()
            }

        flipper.inAnimation =
            inAnimation

        flipper.outAnimation =
            outAnimation

        flipper.displayedChild =
            page

        updateNavigation()
    }

    private fun updateNavigation() {

        val current =
            if (
                ::flipper.isInitialized
            ) {
                flipper.displayedChild
            } else {
                0
            }

        pageNowLabel?.apply {

            setTextColor(
                if (current == 0) {
                    CYAN
                } else {
                    MUTED
                }
            )

            background =
                navigationDrawable(
                    current == 0,
                    CYAN
                )
        }

        pageHistoryLabel?.apply {

            setTextColor(
                if (current == 1) {
                    PURPLE
                } else {
                    MUTED
                }
            )

            background =
                navigationDrawable(
                    current == 1,
                    PURPLE
                )
        }
    }

    private fun navigationDrawable(
        selected: Boolean,
        color: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                dp(14).toFloat()

            setColor(
                if (selected) {
                    0xFF172431.toInt()
                } else {
                    0xFF101821.toInt()
                }
            )

            if (selected) {

                setStroke(
                    dp(1),
                    color
                )
            }
        }
    }

    /*
     * ============================================================
     * ОБЩИЕ КАРТОЧКИ
     * ============================================================
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
                GradientDrawable().apply {

                    shape =
                        GradientDrawable.RECTANGLE

                    cornerRadius =
                        dp(18).toFloat()

                    setColor(
                        CARD
                    )

                    setStroke(
                        if (
                            strongerAccent
                        ) {
                            dp(2)
                        } else {
                            dp(1)
                        },
                        accentColor
                    )
                }
        }
    }

    private fun createLimitCard(
        title: String,
        usedMinutes: Int,
        limitMinutes: Int,
        color: Int,
        status: String
    ): LinearLayout {

        return createCard(
            color
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
                        status

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

    /*
     * ============================================================
     * ПРОГРЕСС-БАР
     * ============================================================
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
     * ============================================================
     * ВСПОМОГАТЕЛЬНЫЕ ЭЛЕМЕНТЫ
     * ============================================================
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
        click: () -> Unit
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
                click()
            }
        }
    }

    /*
     * ============================================================
     * ЦВЕТ СУТОЧНОГО ОТДЫХА В ИСТОРИИ
     * ============================================================
     */

    private fun dailyRestHistoryColor(
        text: String
    ): Int {

        return when {

            text.contains("+") ->
                ORANGE

            else ->
                GREEN
        }
    }

    /*
     * ============================================================
     * ФОРМАТИРОВАНИЕ
     * ============================================================
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

    /*
     * ============================================================
     * МОДЕЛЬ ОДНОЙ СМЕНЫ
     * ============================================================
     */

    private data class ShiftRecord(
        val date: String,
        val open: String,
        val close: String,
        val driving: String,
        val work: String,
        val availability: String,
        val dailyRest: String
    )
}
