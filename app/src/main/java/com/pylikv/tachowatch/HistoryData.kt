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
        val endCountry: String?
    ) {
        val shiftMinutes: Int? get() {
            val s = startTime?.let(::clockMinutes) ?: return null
            val e = endTime?.let(::clockMinutes) ?: return null
            return if (e >= s) e - s else e + 1440 - s
        }
    }

    data class Model(
        val days: List<Day>,
        val previousWeekDrivingMinutes: Int,
        val currentWeekCardMinutes: Int
    )

    private data class Place(val date: String, val time: String, val type: String, val country: String)

    fun load(result: TlvInventory.Result): Model {
        val activity = TlvInventory.render(result)
        val placesText = PlacesDecoder.render(result)

        val dayMatches = Regex("(?ms)^DAY#\\d+.*?date=(\\d{4}-\\d{2}-\\d{2}).*?\\n(.*?)(?=^DAY#|^STATUS=)")
            .findAll(activity).toList()

        val totals = linkedMapOf<String, Triple<Int, Int, Int>>()
        dayMatches.forEach { m ->
            val date = m.groupValues[1]
            var driving = 0; var work = 0; var availability = 0
            m.groupValues[2].lines().forEach { line ->
                val dur = Regex("duration=(\\d+):(\\d{2})").find(line) ?: return@forEach
                val minutes = (dur.groupValues[1].toIntOrNull() ?: 0) * 60 + (dur.groupValues[2].toIntOrNull() ?: 0)
                when {
                    line.contains(" DRIVING ") -> driving += minutes
                    line.contains(" WORK ") -> work += minutes
                    line.contains(" AVAILABILITY ") -> availability += minutes
                }
            }
            totals[date] = Triple(driving, work, availability)
        }

        val places = Regex("(?m)^#\\d+ time=(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}):\\d{2} type=([^ ]+) country=([^ ]+)")
            .findAll(placesText)
            .map { Place(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4].substringBefore('[')) }
            .distinctBy { listOf(it.date, it.time, it.type, it.country) }
            .toList()

        val days = totals.map { (date, t) ->
            val dp = places.filter { it.date == date }
            val start = dp.firstOrNull { it.type.startsWith("BEGIN_") }
            val end = dp.lastOrNull { it.type.startsWith("END_") }
            Day(date, t.first, t.second, t.third, start?.time, start?.country, end?.time, end?.country)
        }.sortedBy { it.date }

        val utc = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(utc, Locale.US)
        val now = Date()
        cal.time = now
        val currentWeek = cal.get(Calendar.WEEK_OF_YEAR)
        val currentYear = cal.get(Calendar.WEEK_YEAR)
        var current = 0
        var previous = 0
        days.forEach { d ->
            val dc = Calendar.getInstance(utc, Locale.US)
            dc.time = parseDate(d.date) ?: return@forEach
            val w = dc.get(Calendar.WEEK_OF_YEAR)
            val y = dc.get(Calendar.WEEK_YEAR)
            if (w == currentWeek && y == currentYear) current += d.drivingMinutes
            else {
                val pc = Calendar.getInstance(utc, Locale.US).apply { time = now; add(Calendar.WEEK_OF_YEAR, -1) }
                if (w == pc.get(Calendar.WEEK_OF_YEAR) && y == pc.get(Calendar.WEEK_YEAR)) previous += d.drivingMinutes
            }
        }
        return Model(days, previous, current)
    }

    fun gapMinutes(a: Day, b: Day): Int? {
        val end = a.endTime ?: return null
        val start = b.startTime ?: return null
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val t1 = runCatching { sdf.parse("${a.date} $end") }.getOrNull() ?: return null
        val t2 = runCatching { sdf.parse("${b.date} $start") }.getOrNull() ?: return null
        return ((t2.time - t1.time) / 60000L).toInt().takeIf { it >= 0 }
    }

    fun fmt(min: Int): String = String.format(Locale.US, "%d:%02d", min / 60, min % 60)
    private fun clockMinutes(v: String): Int = v.substringBefore(':').toInt() * 60 + v.substringAfter(':').toInt()
    private fun parseDate(v: String): Date? = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(v) }.getOrNull()
}
