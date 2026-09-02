package com.pylikv.tachowatch

import java.util.Locale

object CardSnapshot {
    data class Snapshot(
        val fileName: String,
        val sessionOpen: String = "—",
        val vehicleNation: String = "—",
        val vehicleNumber: String = "—",
        val currentDay: String = "—",
        val lastCardActivity: String = "—",
        val dayDriving: String = "—",
        val dayWork: String = "—",
        val dayAvailability: String = "—",
        val dayRest: String = "—",
        val firstPlace: String = "—",
        val lastPlace: String = "—"
    )

    fun load(result: TlvInventory.Result): Snapshot {
        val usage = CurrentUsageDecoder.render(result)
        val places = PlacesDecoder.render(result)
        val activity = TlvInventory.render(result)

        val session = Regex("SessionOpenTimeUTC=([^\\n]+)").find(usage)?.groupValues?.get(1)?.trim() ?: "—"
        val nation = Regex("VehicleRegistrationNation=([^\\n]+)").find(usage)?.groupValues?.get(1)?.trim() ?: "—"
        val number = Regex("VehicleRegistrationNumber=([^\\n]+)").find(usage)?.groupValues?.get(1)?.trim() ?: "—"

        val dayMatches = Regex("(?ms)^DAY#\\d+.*?date=(\\d{4}-\\d{2}-\\d{2}).*?\\n(.*?)(?=^DAY#|^STATUS=)")
            .findAll(activity).toList()
        val latestDay = dayMatches.lastOrNull()
        val date = latestDay?.groupValues?.getOrNull(1) ?: "—"
        val dayBody = latestDay?.groupValues?.getOrNull(2).orEmpty()
        val activityLines = dayBody.lines().map { it.trim() }.filter { Regex("^\\d{2}:\\d{2} ").containsMatchIn(it) }
        val lastActivity = activityLines.lastOrNull()?.let(::prettyActivityLine) ?: "—"

        var driving = 0
        var work = 0
        var availability = 0
        var rest = 0
        activityLines.forEach { line ->
            val m = Regex("duration=(\\d+):(\\d{2})").find(line) ?: return@forEach
            val minutes = m.groupValues[1].toIntOrNull()?.times(60)?.plus(m.groupValues[2].toIntOrNull() ?: 0) ?: 0
            when {
                line.contains(" DRIVING ") -> driving += minutes
                line.contains(" WORK ") -> work += minutes
                line.contains(" AVAILABILITY ") -> availability += minutes
                line.contains(" REST ") -> rest += minutes
            }
        }

        val placeLines = Regex("(?m)^#\\d+ time=(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) type=([^ ]+) country=([^ ]+).*?odometerKm=(\\d+)")
            .findAll(places)
            .map { m -> Place(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) }
            .toList()
            .distinctBy { listOf(it.time, it.type, it.country, it.odo) }
        val currentDate = placeLines.lastOrNull()?.time?.take(10)
        val currentPlaces = if (currentDate != null) placeLines.filter { it.time.startsWith(currentDate) } else emptyList()
        val first = currentPlaces.firstOrNull()?.pretty() ?: placeLines.lastOrNull()?.pretty() ?: "—"
        val last = currentPlaces.lastOrNull()?.pretty() ?: placeLines.lastOrNull()?.pretty() ?: "—"

        return Snapshot(
            fileName = result.file.name,
            sessionOpen = session,
            vehicleNation = nation,
            vehicleNumber = number,
            currentDay = date,
            lastCardActivity = lastActivity,
            dayDriving = fmt(driving),
            dayWork = fmt(work),
            dayAvailability = fmt(availability),
            dayRest = fmt(rest),
            firstPlace = first,
            lastPlace = last
        )
    }

    private data class Place(val time: String, val type: String, val country: String, val odo: String) {
        fun pretty(): String = "${time.takeLast(8).take(5)} • $country • ${type.replace("BEGIN_INSERT_OR_ENTRY", "НАЧАЛО/ВВОД").replace("END_WITHDRAW_OR_ENTRY", "КОНЕЦ/ИЗВЛЕЧЕНИЕ")} • $odo км"
    }

    private fun prettyActivityLine(line: String): String {
        val clock = line.take(5)
        val state = when {
            line.contains("DRIVING") -> "ВОЖДЕНИЕ"
            line.contains("WORK") -> "ДРУГАЯ РАБОТА"
            line.contains("AVAILABILITY") -> "ГОТОВНОСТЬ"
            line.contains("REST") -> "ОТДЫХ / ПЕРЕРЫВ"
            else -> "—"
        }
        return "$clock • $state"
    }

    private fun fmt(minutes: Int): String = String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)
}
