package com.pylikv.tachowatch

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object HistoryData {
    data class Day(
        val date: String,
        val drivingMinutes: Int,
        val workMinutes: Int,
        val availabilityMinutes: Int,
        val startTime: String?,
        val startCountry: String?,
        val endTime: String?,
        val endCountry: String?,
        val hasSplitDailyRest3h: Boolean = false
    ) {
        val shiftMinutes: Int? get() {
            val s = startTime?.let(::clockMinutes) ?: return null
            val e = endTime?.let(::clockMinutes) ?: return null
            return if (e >= s) e - s else e + 1440 - s
        }
    }

    data class Compensation(
        val sourcePreviousDate: String,
        val sourceNextDate: String,
        val originalMinutes: Int,
        val remainingMinutes: Int,
        val paidDate: String?,
        val dueDate: String
    )

    data class RestInfo(
        val previousDate: String,
        val nextDate: String,
        val actualMinutes: Int,
        val weekly: Boolean,
        val splitDaily: Boolean,
        val creditedDailyMinutes: Int?,
        val compensationCreatedMinutes: Int,
        val compensationRemainingMinutes: Int,
        val compensationPaidDate: String?,
        val compensationDueDate: String?
    )

    data class Model(
        val days: List<Day>,
        val previousWeekDrivingMinutes: Int,
        val currentWeekCardMinutes: Int,
        val rests: List<RestInfo>
    ) {
        fun restBetween(previous: Day, next: Day): RestInfo? =
            rests.firstOrNull { it.previousDate == previous.date && it.nextDate == next.date }
    }

    private data class Place(val date: String, val time: String, val type: String, val country: String)
    private data class ActivityDay(
        var driving: Int = 0,
        var work: Int = 0,
        var availability: Int = 0,
        var activeStart: String? = null,
        var restStartAfterWork: String? = null,
        var hasSplitDailyRest3h: Boolean = false
    )
    private data class Debt(
        val previousDate: String,
        val nextDate: String,
        val original: Int,
        var remaining: Int,
        var paidDate: String? = null,
        val dueDate: String
    )

    private val restMilestones = intArrayOf(15, 45, 180, 540, 660, 1440, 2700)
    private var latestRests: List<RestInfo> = emptyList()

    fun load(result: TlvInventory.Result): Model {
        val activityText = TlvInventory.render(result)
        val placesText = PlacesDecoder.render(result)
        val activityDays = linkedMapOf<String, ActivityDay>()

        Regex("(?ms)^DAY#\\d+.*?date=(\\d{4}-\\d{2}-\\d{2}).*?\\n(.*?)(?=^DAY#|^STATUS=)")
            .findAll(activityText)
            .forEach { match ->
                val date = match.groupValues[1]
                val day = ActivityDay()
                var seenActive = false
                var pendingThreeHours = false

                match.groupValues[2].lines().forEach { line ->
                    val row = Regex("^\\s*(\\d{2}:\\d{2})\\s+(REST|AVAILABILITY|WORK|DRIVING)\\b").find(line) ?: return@forEach
                    val time = row.groupValues[1]
                    val kind = row.groupValues[2]
                    val duration = Regex("duration=(\\d+):(\\d{2})").find(line)
                    val minutes = duration?.let {
                        (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0)
                    } ?: 0

                    when (kind) {
                        "DRIVING", "WORK", "AVAILABILITY" -> {
                            if (pendingThreeHours) day.hasSplitDailyRest3h = true
                            pendingThreeHours = false
                            when (kind) {
                                "DRIVING" -> day.driving += minutes
                                "WORK" -> day.work += minutes
                                else -> day.availability += minutes
                            }
                            if (day.activeStart == null) day.activeStart = time
                            seenActive = true
                            day.restStartAfterWork = null
                        }
                        "REST" -> if (seenActive) {
                            day.restStartAfterWork = time
                            pendingThreeHours = minutes >= 180
                        }
                    }
                }
                activityDays[date] = day
            }

        val places = Regex("(?m)^#\\d+ time=(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}):\\d{2} type=([^ ]+) country=([^ ]+)")
            .findAll(placesText)
            .map { Place(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4].substringBefore('[')) }
            .distinctBy { listOf(it.date, it.time, it.type, it.country) }
            .toList()

        val days = activityDays.mapNotNull { (date, a) ->
            val p = places.filter { it.date == date }
            val begin = p.firstOrNull { it.type.startsWith("BEGIN_") }
            val end = p.lastOrNull { it.type.startsWith("END_") }
            val startTime = begin?.time ?: a.activeStart
            val endTime = end?.time ?: a.restStartAfterWork
            val hasActivity = a.driving > 0 || a.work > 0 || a.availability > 0
            if (!hasActivity && startTime == null && endTime == null) null else Day(
                date, a.driving, a.work, a.availability,
                startTime, begin?.country, endTime, end?.country, a.hasSplitDailyRest3h
            )
        }.sortedBy { it.date }

        val now = isoCalendar(Date())
        val previousCal = isoCalendar(Date()).apply { add(Calendar.WEEK_OF_YEAR, -1) }
        var currentWeekMinutes = 0
        var previousWeekMinutes = 0
        days.forEach { day ->
            val date = parseDate(day.date) ?: return@forEach
            val c = isoCalendar(date)
            if (c.get(Calendar.WEEK_OF_YEAR) == now.get(Calendar.WEEK_OF_YEAR) && c.getWeekYear() == now.getWeekYear()) currentWeekMinutes += day.drivingMinutes
            if (c.get(Calendar.WEEK_OF_YEAR) == previousCal.get(Calendar.WEEK_OF_YEAR) && c.getWeekYear() == previousCal.getWeekYear()) previousWeekMinutes += day.drivingMinutes
        }

        val rests = buildRestInfo(days)
        latestRests = rests
        return Model(days, previousWeekMinutes, currentWeekMinutes, rests)
    }

    private fun buildRestInfo(days: List<Day>): List<RestInfo> {
        if (days.size < 2) return emptyList()
        val debts = mutableListOf<Debt>()
        val result = mutableListOf<RestInfo>()

        for (i in 0 until days.lastIndex) {
            val previous = days[i]
            val next = days[i + 1]
            val actual = actualGapMinutes(previous, next) ?: continue
            val weekly = actual >= 1440
            val created = if (weekly && actual < 2700) 2700 - actual else 0
            val due = if (created > 0) compensationDueDate(next.date) else null
            if (created > 0 && due != null) debts += Debt(previous.date, next.date, created, created, null, due)

            var surplus = compensationSurplusMinutes(actual, weekly, previous.hasSplitDailyRest3h)
            debts.filter { it.remaining > 0 && !(it.previousDate == previous.date && it.nextDate == next.date) }.forEach { debt ->
                if (surplus <= 0) return@forEach
                val paid = minOf(debt.remaining, surplus)
                debt.remaining -= paid
                surplus -= paid
                if (debt.remaining == 0 && debt.paidDate == null) debt.paidDate = next.date
            }

            val dailyCredit = if (weekly) null else when {
                previous.hasSplitDailyRest3h && actual >= 540 -> 660
                actual >= 660 -> 660
                actual >= 540 -> 540
                else -> null
            }

            result += RestInfo(
                previous.date,
                next.date,
                actual,
                weekly,
                !weekly && previous.hasSplitDailyRest3h && actual >= 540,
                dailyCredit,
                created,
                created,
                null,
                due
            )
        }

        return result.map { rest ->
            val debt = debts.firstOrNull { it.previousDate == rest.previousDate && it.nextDate == rest.nextDate }
            if (debt == null) rest else rest.copy(
                compensationRemainingMinutes = debt.remaining,
                compensationPaidDate = debt.paidDate
            )
        }
    }

    private fun compensationSurplusMinutes(actual: Int, weekly: Boolean, splitDaily: Boolean): Int {
        val base = when {
            weekly && actual >= 2700 -> 2700
            weekly -> 1440
            splitDaily && actual >= 540 -> 540
            actual >= 660 -> 660
            else -> 540
        }
        return (actual - base).coerceAtLeast(0)
    }

    private fun compensationDueDate(restStartDate: String): String {
        val date = parseDate(restStartDate) ?: return restStartDate
        val cal = isoCalendar(date).apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            add(Calendar.WEEK_OF_YEAR, 4)
            add(Calendar.DAY_OF_MONTH, -1)
        }
        return dateFormat("yyyy-MM-dd").format(cal.time)
    }

    fun actualGapMinutes(a: Day, b: Day): Int? {
        val end = a.endTime ?: return null
        val start = b.startTime ?: return null
        val t1 = parseDateTime("${a.date} $end") ?: return null
        val t2 = parseDateTime("${b.date} $start") ?: return null
        return ((t2.time - t1.time) / 60000L).toInt().takeIf { it >= 0 }
    }

    fun gapMinutes(a: Day, b: Day): Int? {
        val index = latestRests.indexOfFirst { it.previousDate == a.date && it.nextDate == b.date }
        if (index < 0) return actualGapMinutes(a, b)
        return if (latestRests[index].weekly) 1_000_000 + index else -1_000_000 - index
    }

    fun lastWeeklyRestEndMillis(days: List<Day>): Long? {
        var latest: Long? = null
        for (i in 0 until days.lastIndex) {
            val previous = days[i]
            val next = days[i + 1]
            if ((actualGapMinutes(previous, next) ?: continue) < 1440) continue
            val start = next.startTime ?: continue
            val end = parseDateTime("${next.date} $start")?.time ?: continue
            if (latest == null || end > latest) latest = end
        }
        return latest
    }

    fun creditedRestMinutes(actualMinutes: Int): Int = restMilestones.lastOrNull { actualMinutes >= it } ?: 0
    fun nextRestMilestone(actualMinutes: Int): Int? = restMilestones.firstOrNull { actualMinutes < it }

    fun fmt(min: Int): String {
        if (min >= 1_000_000) return latestRests.getOrNull(min - 1_000_000)?.let(::formatRestInfo) ?: "—"
        if (min <= -1_000_000) return latestRests.getOrNull(-min - 1_000_000)?.let(::formatRestInfo) ?: "—"
        return fmtPlain(min)
    }

    private fun formatRestInfo(rest: RestInfo): String {
        if (rest.weekly) {
            val first = fmtPlain(rest.actualMinutes)
            if (rest.compensationCreatedMinutes <= 0) return first
            return if (rest.compensationRemainingMinutes <= 0 && rest.compensationPaidDate != null) {
                "$first\n✓ Компенсация ${fmtPlain(rest.compensationCreatedMinutes)} отдана ${prettyDate(rest.compensationPaidDate)}"
            } else {
                val paid = rest.compensationCreatedMinutes - rest.compensationRemainingMinutes
                val paidPart = if (paid > 0) " • отдано ${fmtPlain(paid)}" else ""
                "$first\n⚠ Компенсация нужна ${fmtPlain(rest.compensationRemainingMinutes)}$paidPart • до ${rest.compensationDueDate?.let(::prettyDate) ?: "—"}"
            }
        }

        val actual = fmtPlain(rest.actualMinutes)
        val split = if (rest.splitDaily) "\nВ смене засчитано: 3:00" else ""
        val credited = rest.creditedDailyMinutes?.let { "\nЗасчитано суточного отдыха: ${fmtPlain(it)}" } ?: ""
        return actual + split + credited
    }

    private fun fmtPlain(min: Int): String = String.format(Locale.US, "%d:%02d", min / 60, min % 60)

    fun prettyDate(date: String): String = runCatching {
        val p = date.split('-')
        "${p[2]}.${p[1]}.${p[0]}"
    }.getOrDefault(date)

    private fun parseDateTime(v: String): Date? = runCatching { dateFormat("yyyy-MM-dd HH:mm").parse(v) }.getOrNull()
    private fun parseDate(v: String): Date? = runCatching { dateFormat("yyyy-MM-dd").parse(v) }.getOrNull()
    private fun dateFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun isoCalendar(date: Date): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        firstDayOfWeek = Calendar.MONDAY
        minimalDaysInFirstWeek = 4
        time = date
    }
    private fun clockMinutes(v: String): Int = v.substringBefore(':').toInt() * 60 + v.substringAfter(':').toInt()
}