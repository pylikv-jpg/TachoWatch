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
        val driving: Int,
        val work: Int,
        val availability: Int,
        val activeStart: String?,
        val restStartAfterWork: String?,
        val hasSplitDailyRest3h: Boolean
    )

    private data class Debt(
        val previousDate: String,
        val nextDate: String,
        val original: Int,
        var remaining: Int,
        var paidDate: String? = null,
        val dueDate: String
    )

    private val restMilestones = intArrayOf(15, 45, 3 * 60, 9 * 60, 11 * 60, 24 * 60, 45 * 60)

    fun load(result: TlvInventory.Result): Model {
        val activity = TlvInventory.render(result)
        val placesText = PlacesDecoder.render(result)
        val dayMatches = Regex("(?ms)^DAY#\\d+.*?date=(\\d{4}-\\d{2}-\\d{2}).*?\\n(.*?)(?=^DAY#|^STATUS=)").findAll(activity).toList()
        val activityDays = linkedMapOf<String, ActivityDay>()

        dayMatches.forEach { m ->
            val date = m.groupValues[1]
            val lines = m.groupValues[2].lines()
            var driving = 0
            var work = 0
            var availability = 0
            var activeStart: String? = null
            var restStartAfterWork: String? = null
            var seenActive = false
            var pendingSplitRest = false
            var hasSplitDailyRest3h = false

            lines.forEach { line ->
                val row = Regex("^\\s*(\\d{2}:\\d{2})\\s+(REST|AVAILABILITY|WORK|DRIVING)\\b").find(line) ?: return@forEach
                val time = row.groupValues[1]
                val kind = row.groupValues[2]
                val dur = Regex("duration=(\\d+):(\\d{2})").find(line)
                val minutes = if (dur != null) {
                    (dur.groupValues[1].toIntOrNull() ?: 0) * 60 + (dur.groupValues[2].toIntOrNull() ?: 0)
                } else 0

                when (kind) {
                    "DRIVING" -> {
                        if (pendingSplitRest) hasSplitDailyRest3h = true
                        pendingSplitRest = false
                        driving += minutes
                        if (activeStart == null) activeStart = time
                        seenActive = true
                        restStartAfterWork = null
                    }
                    "WORK" -> {
                        if (pendingSplitRest) hasSplitDailyRest3h = true
                        pendingSplitRest = false
                        work += minutes
                        if (activeStart == null) activeStart = time
                        seenActive = true
                        restStartAfterWork = null
                    }
                    "AVAILABILITY" -> {
                        if (pendingSplitRest) hasSplitDailyRest3h = true
                        pendingSplitRest = false
                        availability += minutes
                        if (activeStart == null) activeStart = time
                        seenActive = true
                        restStartAfterWork = null
                    }
                    "REST" -> {
                        if (seenActive) {
                            restStartAfterWork = time
                            pendingSplitRest = minutes >= 3 * 60
                        }
                    }
                }
            }

            activityDays[date] = ActivityDay(
                driving,
                work,
                availability,
                activeStart,
                restStartAfterWork,
                hasSplitDailyRest3h
            )
        }

        val places = Regex("(?m)^#\\d+ time=(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}):\\d{2} type=([^ ]+) country=([^ ]+)").findAll(placesText)
            .map { Place(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4].substringBefore('[')) }
            .distinctBy { listOf(it.date, it.time, it.type, it.country) }
            .toList()

        val days = activityDays.mapNotNull { (date, a) ->
            val dp = places.filter { it.date == date }
            val placeStart = dp.firstOrNull { it.type.startsWith("BEGIN_") }
            val placeEnd = dp.lastOrNull { it.type.startsWith("END_") }

            val startTime = placeStart?.time ?: a.activeStart
            val endTime = placeEnd?.time ?: a.restStartAfterWork
            val hasActivity = a.driving > 0 || a.work > 0 || a.availability > 0

            if (!hasActivity && startTime == null && endTime == null) null
            else Day(
                date = date,
                drivingMinutes = a.driving,
                workMinutes = a.work,
                availabilityMinutes = a.availability,
                startTime = startTime,
                startCountry = placeStart?.country,
                endTime = endTime,
                endCountry = placeEnd?.country,
                hasSplitDailyRest3h = a.hasSplitDailyRest3h
            )
        }.sortedBy { it.date }

        val now = Date()
        val currentCal = isoCalendar(now)
        val previousCal = isoCalendar(now).apply { add(Calendar.WEEK_OF_YEAR, -1) }
        val currentWeek = currentCal.get(Calendar.WEEK_OF_YEAR)
        val currentYear = currentCal.getWeekYear()
        val previousWeek = previousCal.get(Calendar.WEEK_OF_YEAR)
        val previousYear = previousCal.getWeekYear()
        var current = 0
        var previous = 0

        days.forEach { d ->
            val date = parseDate(d.date) ?: return@forEach
            val dc = isoCalendar(date)
            val week = dc.get(Calendar.WEEK_OF_YEAR)
            val weekYear = dc.getWeekYear()
            if (week == currentWeek && weekYear == currentYear) current += d.drivingMinutes
            if (week == previousWeek && weekYear == previousYear) previous += d.drivingMinutes
        }

        return Model(days, previous, current, buildRestInfo(days))
    }

    private fun buildRestInfo(days: List<Day>): List<RestInfo> {
        if (days.size < 2) return emptyList()
        val debts = mutableListOf<Debt>()
        val raw = mutableListOf<RestInfo>()

        for (i in 0 until days.lastIndex) {
            val a = days[i]
            val b = days[i + 1]
            val actual = actualGapMinutes(a, b) ?: continue
            val weekly = actual >= 24 * 60
            val created = if (weekly && actual < 45 * 60) 45 * 60 - actual else 0
            val due = if (created > 0) compensationDueDate(b.date) else null

            if (created > 0 && due != null) {
                debts += Debt(a.date, b.date, created, created, null, due)
            }

            var availableForCompensation = compensationSurplusMinutes(actual, weekly, a.hasSplitDailyRest3h)
            if (availableForCompensation > 0) {
                debts.filter { it.remaining > 0 && !(it.previousDate == a.date && it.nextDate == b.date) }.forEach { debt ->
                    if (availableForCompensation <= 0) return@forEach
                    val paid = minOf(debt.remaining, availableForCompensation)
                    debt.remaining -= paid
                    availableForCompensation -= paid
                    if (debt.remaining == 0 && debt.paidDate == null) debt.paidDate = b.date
                }
            }

            val dailyCredit = if (weekly) null else when {
                a.hasSplitDailyRest3h && actual >= 9 * 60 -> 11 * 60
                actual >= 11 * 60 -> 11 * 60
                actual >= 9 * 60 -> 9 * 60
                else -> null
            }

            raw += RestInfo(
                previousDate = a.date,
                nextDate = b.date,
                actualMinutes = actual,
                weekly = weekly,
                splitDaily = !weekly && a.hasSplitDailyRest3h && actual >= 9 * 60,
                creditedDailyMinutes = dailyCredit,
                compensationCreatedMinutes = created,
                compensationRemainingMinutes = created,
                compensationPaidDate = null,
                compensationDueDate = due
            )
        }

        return raw.map { rest ->
            if (rest.compensationCreatedMinutes <= 0) rest
            else {
                val debt = debts.firstOrNull { it.previousDate == rest.previousDate && it.nextDate == rest.nextDate }
                rest.copy(
                    compensationRemainingMinutes = debt?.remaining ?: rest.compensationCreatedMinutes,
                    compensationPaidDate = debt?.paidDate
                )
            }
        }
    }

    private fun compensationSurplusMinutes(actual: Int, weekly: Boolean, splitDaily: Boolean): Int {
        val base = when {
            weekly && actual >= 45 * 60 -> 45 * 60
            weekly -> 24 * 60
            splitDaily && actual >= 9 * 60 -> 9 * 60
            actual >= 11 * 60 -> 11 * 60
            else -> 9 * 60
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
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(cal.time)
    }

    fun actualGapMinutes(a: Day, b: Day): Int? {
        val end = a.endTime ?: return null
        val start = b.startTime ?: return null
        val t1 = parseDateTime("${a.date} $end") ?: return null
        val t2 = parseDateTime("${b.date} $start") ?: return null
        return ((t2.time - t1.time) / 60000L).toInt().takeIf { it >= 0 }
    }

    fun gapMinutes(a: Day, b: Day): Int? = actualGapMinutes(a, b)

    fun lastWeeklyRestEndMillis(days: List<Day>): Long? {
        var latest: Long? = null
        for (i in 0 until days.lastIndex) {
            val previous = days[i]
            val next = days[i + 1]
            if ((actualGapMinutes(previous, next) ?: continue) < 24 * 60) continue
            val start = next.startTime ?: continue
            val endOfRest = parseDateTime("${next.date} $start")?.time ?: continue
            if (latest == null || endOfRest > latest) latest = endOfRest
        }
        return latest
    }

    fun creditedRestMinutes(actualMinutes: Int): Int = restMilestones.lastOrNull { actualMinutes >= it } ?: 0
    fun nextRestMilestone(actualMinutes: Int): Int? = restMilestones.firstOrNull { actualMinutes < it }
    fun fmt(min: Int): String = String.format(Locale.US, "%d:%02d", min / 60, min % 60)
    fun prettyDate(date: String): String = runCatching {
        val p = date.split('-')
        "${p[2]}.${p[1]}.${p[0]}"
    }.getOrDefault(date)

    private fun parseDateTime(v: String): Date? = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(v)
    }.getOrNull()

    private fun isoCalendar(date: Date): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        firstDayOfWeek = Calendar.MONDAY
        minimalDaysInFirstWeek = 4
        time = date
    }

    private fun clockMinutes(v: String): Int = v.substringBefore(':').toInt() * 60 + v.substringAfter(':').toInt()
    private fun parseDate(v: String): Date? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(v)
    }.getOrNull()
}
