package com.pylikv.tachowatch

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object HistoryData {
    data class ActivityPeriod(
        val startTime: String,
        val type: String,
        val minutes: Int
    )

    data class HistoryEvent(
        val time: String,
        val type: HistoryEventDecoder.Type,
        val odometerKm: Int? = null
    )

    data class Day(
        val date: String,
        val drivingMinutes: Int,
        val workMinutes: Int,
        val availabilityMinutes: Int,
        val startTime: String?,
        val startCountry: String?,
        val endTime: String?,
        val endCountry: String?,
        val hasSplitDailyRest3h: Boolean = false,
        val periods: List<ActivityPeriod> = emptyList(),
        val events: List<HistoryEvent> = emptyList(),
        val startOdometerKm: Int? = null,
        val endOdometerKm: Int? = null
    ) {
        val shiftMinutes: Int? get() {
            val s = startTime?.let(::clockMinutes) ?: return null
            val e = endTime?.let(::clockMinutes) ?: return null
            return if (e >= s) e - s else e + 1440 - s
        }

        val shiftDistanceKm: Int? get() {
            val start = startOdometerKm ?: return null
            val end = endOdometerKm ?: return null
            return (end - start).takeIf { it >= 0 }
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
    private data class ShiftMileage(
        val startDate: String,
        val startTime: String,
        val endDate: String,
        val endTime: String,
        val startOdometerKm: Int,
        val endOdometerKm: Int
    )
    private data class ActivityDay(
        var driving: Int = 0,
        var work: Int = 0,
        var availability: Int = 0,
        var activeStart: String? = null,
        var restStartAfterWork: String? = null,
        var hasSplitDailyRest3h: Boolean = false,
        val periods: MutableList<ActivityPeriod> = mutableListOf()
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

    fun load(
        result: TlvInventory.Result,
        localCardEvents: List<LocalCardEventStore.Event> = emptyList()
    ): Model {
        val activityText = TlvInventory.render(result)
        val placesText = PlacesDecoder.render(result)
        val placeRecords = PlacesDecoder.records(result)
        val shiftMileage = pairShiftMileage(placeRecords).groupBy { it.startDate }
        val historyEvents = HistoryEventDecoder.decode(result).groupBy { it.date }
        val localEventsByDate = localCardEvents.groupBy { it.date }
        val activityDays = linkedMapOf<String, ActivityDay>()
        var splitRecoveryState = SplitDailyRestTracker.RecoveryState()
        var previousActivityDate: Date? = null

        Regex("(?ms)^DAY#\\d+.*?date=(\\d{4}-\\d{2}-\\d{2}).*?\\n(.*?)(?=^DAY#|^STATUS=)")
            .findAll(activityText)
            .forEach { match ->
                val date = match.groupValues[1]
                val parsedActivityDate = parseDate(date)
                if (previousActivityDate != null && parsedActivityDate != null &&
                    parsedActivityDate.time - previousActivityDate!!.time > 24L * 60L * 60L * 1000L
                ) {
                    splitRecoveryState = SplitDailyRestTracker.RecoveryState()
                }
                previousActivityDate = parsedActivityDate
                val day = ActivityDay()
                var seenActive = false

                match.groupValues[2].lines().forEach { line ->
                    val row = Regex("^\\s*(\\d{2}:\\d{2})\\s+(REST|AVAILABILITY|WORK|DRIVING)\\b").find(line) ?: return@forEach
                    val time = row.groupValues[1]
                    val kind = row.groupValues[2]
                    val duration = Regex("duration=(\\d+):(\\d{2})").find(line)
                    val minutes = duration?.let {
                        (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0)
                    } ?: 0
                    if (minutes > 0) day.periods += ActivityPeriod(time, kind, minutes)
                    splitRecoveryState =
                        SplitDailyRestTracker.updateRecoveryState(splitRecoveryState, kind, minutes)

                    when (kind) {
                        "DRIVING", "WORK", "AVAILABILITY" -> {
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
                        }
                    }
                }
                day.hasSplitDailyRest3h = splitRecoveryState.firstPartTaken
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
            // Manual entry can legitimately start the work period before card insertion.
            // Use the earliest active timestamp instead of always preferring BEGIN place time.
            val startTime = earliestClock(begin?.time, a.activeStart)
            val endTime = end?.time ?: a.restStartAfterWork
            val mileage = shiftMileage[date]
                .orEmpty()
                .minByOrNull { kotlin.math.abs(clockMinutes(it.startTime) - clockMinutes(startTime ?: it.startTime)) }
            val hasActivity = a.driving > 0 || a.work > 0 || a.availability > 0
            if (!hasActivity && startTime == null && endTime == null) null else Day(
                date, a.driving, a.work, a.availability,
                startTime, begin?.country, endTime, end?.country, a.hasSplitDailyRest3h, a.periods.toList(),
                (
                    historyEvents[date].orEmpty()
                        .map { HistoryEvent(it.time, it.type, it.odometerKm) } +
                        localEventsByDate[date].orEmpty().map {
                            HistoryEvent(
                                time = it.time,
                                type = when (it.type) {
                                    LocalCardEventStore.Type.REMOVED -> HistoryEventDecoder.Type.CARD_REMOVED
                                    LocalCardEventStore.Type.INSERTED -> HistoryEventDecoder.Type.CARD_INSERTED
                                }
                            )
                        }
                )
                    .distinctBy { listOf(it.time, it.type.name, it.odometerKm?.toString().orEmpty()) }
                    .sortedBy { it.time },
                startOdometerKm = mileage?.startOdometerKm,
                endOdometerKm = mileage?.endOdometerKm
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

    private fun pairShiftMileage(records: List<PlacesDecoder.Record>): List<ShiftMileage> {
        val out = ArrayList<ShiftMileage>()
        var pending: PlacesDecoder.Record? = null
        records.forEach { record ->
            when {
                record.isBegin -> pending = record
                record.isEnd -> {
                    val start = pending ?: return@forEach
                    if (record.timestampSeconds <= start.timestampSeconds) return@forEach
                    if (record.odometerKm < start.odometerKm) {
                        pending = null
                        return@forEach
                    }
                    out += ShiftMileage(
                        startDate = start.date,
                        startTime = start.time,
                        endDate = record.date,
                        endTime = record.time,
                        startOdometerKm = start.odometerKm,
                        endOdometerKm = record.odometerKm
                    )
                    pending = null
                }
            }
        }
        return out
    }

    fun mileageBetween(days: List<Day>, fromDate: String, toDate: String): Pair<Int, Int> {
        val selected = days.filter { it.date >= fromDate && it.date <= toDate }
        val known = selected.mapNotNull { it.shiftDistanceKm }
        return known.sum() to (selected.size - known.size)
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

            // Regulation 561/2006 requires each weekly-rest reduction to be compensated
            // by an equivalent period taken en bloc. Never chip away a debt across several
            // later rests. A debt changes from its full original amount to zero only when
            // one later uninterrupted rest contains enough surplus to cover it in full.
            val splitFirstPartBeforeGap = hasCompletedSplitFirstPartBeforeTrailingRest(previous.periods)
            var surplus = compensationSurplusMinutes(actual, weekly, splitFirstPartBeforeGap)
            debts
                .filter { it.remaining > 0 && !(it.previousDate == previous.date && it.nextDate == next.date) }
                .forEach { debt ->
                    if (surplus < debt.original) return@forEach
                    surplus -= debt.original
                    debt.remaining = 0
                    if (debt.paidDate == null) debt.paidDate = next.date
                }

            // A calendar day can end in the first 3..8:59 chunk of the *same* daily rest
            // that continues after midnight. The recovery tracker may then leave
            // hasSplitDailyRest3h=true on that day, which must not turn a plain overnight
            // rest into an invented 3:00 + 9:00 split. Require the 3h first part to be
            // completed before the trailing REST run that starts this between-shift gap.
            val dailyCredit = if (weekly) null else when {
                splitFirstPartBeforeGap && actual >= 540 -> 660
                actual >= 660 -> 660
                actual >= 540 -> 540
                else -> null
            }

            result += RestInfo(
                previous.date,
                next.date,
                actual,
                weekly,
                !weekly && splitFirstPartBeforeGap && actual >= 540,
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

    private fun hasCompletedSplitFirstPartBeforeTrailingRest(periods: List<ActivityPeriod>): Boolean {
        if (periods.isEmpty()) return false
        val trailingRestStart = periods.indexOfLast { it.type != "REST" } + 1
        val beforeGap = if (trailingRestStart > 0) periods.take(trailingRestStart) else emptyList()
        return SplitDailyRestTracker.recoverFirstPartFromClosedActivities(
            beforeGap.map { it.type to it.minutes }
        )
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
            val original = fmtPlain(rest.compensationCreatedMinutes)
            return if (rest.compensationRemainingMinutes <= 0 && rest.compensationPaidDate != null) {
                "$first\n✓ Компенсация $original выполнена одним блоком ${prettyDate(rest.compensationPaidDate)}"
            } else {
                "$first\n⚠ Компенсация: $original одним блоком • осталось $original • до ${rest.compensationDueDate?.let(::prettyDate) ?: "—"}"
            }
        }

        val actual = fmtPlain(rest.actualMinutes)
        val split = if (rest.splitDaily) "\nВ смене засчитано: 3:00" else ""
        val credited = rest.creditedDailyMinutes?.let { "\nЗасчитано суточного отдыха: ${fmtPlain(it)}" } ?: ""
        return actual + split + credited
    }

    private fun earliestClock(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        clockMinutes(a) <= clockMinutes(b) -> a
        else -> b
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
