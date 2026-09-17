package com.pylikv.tachowatch

/**
 * Pure counter engine. Every counter owns its state; an activity event is the only
 * shared input. No counter is allowed to mutate another counter's accumulator.
 */
object IndependentCounterEngine {
    enum class Kind { DRIVING, WORK, REST, AVAILABILITY }

    data class Event(val kind: Kind, val minutes: Int)

    data class Snapshot(
        val shiftDriving: Int,
        val continuousDriving: Int,
        val continuousWork: Int,
        val otherWork: Int,
        val availability: Int,
        val currentRest: Int
    )

    fun fromHistory(events: List<Event>): Snapshot {
        var shiftDriving = 0
        var continuousDriving = 0
        var continuousWork = 0
        var otherWork = 0
        var availability = 0
        var currentRest = 0
        var split15Qualified = false

        events.forEach { e ->
            if (e.minutes <= 0) return@forEach
            when (e.kind) {
                Kind.DRIVING -> {
                    shiftDriving += e.minutes
                    continuousDriving += e.minutes
                    continuousWork += e.minutes
                    currentRest = 0
                }
                Kind.WORK -> {
                    otherWork += e.minutes
                    continuousWork += e.minutes
                    currentRest = 0
                }
                Kind.AVAILABILITY -> {
                    availability += e.minutes
                    currentRest = 0
                }
                Kind.REST -> {
                    currentRest = e.minutes
                    // Continuous work has its own reset and is not tied to shift driving.
                    if (e.minutes >= 45) continuousWork = 0

                    // Continuous driving: full 45 or qualified split 15+30.
                    if (e.minutes >= 45 || (split15Qualified && e.minutes >= 30)) {
                        continuousDriving = 0
                        split15Qualified = false
                    } else if (e.minutes >= 15) {
                        split15Qualified = true
                    }

                    // Shift driving is intentionally NOT reset by 15/30/45 minute breaks.
                    // A >=9h rest is the boundary of a new shift.
                    if (e.minutes >= 9 * 60) {
                        shiftDriving = 0
                        continuousDriving = 0
                        continuousWork = 0
                        otherWork = 0
                        availability = 0
                        split15Qualified = false
                    }
                }
            }
        }
        return Snapshot(shiftDriving, continuousDriving, continuousWork, otherWork, availability, currentRest)
    }

    fun fromHistoryDay(day: HistoryData.Day): Snapshot = fromHistory(day.periods.mapNotNull { p ->
        val kind = when (p.type) {
            "DRIVING" -> Kind.DRIVING
            "WORK" -> Kind.WORK
            "REST" -> Kind.REST
            "AVAILABILITY" -> Kind.AVAILABILITY
            else -> null
        } ?: return@mapNotNull null
        Event(kind, p.minutes)
    })
}
