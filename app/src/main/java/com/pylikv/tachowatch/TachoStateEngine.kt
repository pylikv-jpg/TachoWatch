package com.pylikv.tachowatch

/**
 * New counter engine written from scratch.
 *
 * Only raw DTCO facts enter this class:
 *  - activity: F903
 *  - current activity duration: F927
 *  - continuous driving: F923
 *  - accumulated break: F925 (display/helper only)
 *  - two-week driving: F938
 *
 * No UI counter is allowed to feed another counter. Every value is derived from
 * raw samples plus this engine's persisted state.
 */
object TachoStateEngine {
    const val CONTINUOUS_DRIVING_LIMIT = 270
    const val DAILY_DRIVING_NORMAL = 540
    const val DAILY_DRIVING_EXTENDED = 600
    const val CONTINUOUS_WORK_LIMIT = 360
    const val DRIVING_BREAK_RESET = 45
    const val DAILY_REST_REDUCED = 540
    const val DAILY_REST_REGULAR = 660

    enum class Activity { REST, AVAILABILITY, OTHER_WORK, DRIVING, UNKNOWN }

    data class RawSample(
        val activity: Activity,
        val activityMinutes: Int,
        val continuousDrivingMinutes: Int,
        val breakMinutes: Int,
        val twoWeekDrivingMinutes: Int,
        val nowMillis: Long = System.currentTimeMillis()
    )

    data class State(
        val initialized: Boolean = false,
        val previousActivity: Activity = Activity.UNKNOWN,
        val previousActivityMinutes: Int = 0,
        val previousContinuousDrivingMinutes: Int = 0,
        val lastSampleMillis: Long = 0L,

        // Current shift / working period accumulators.
        val completedDrivingBlocksMinutes: Int = 0,
        val continuousWorkMinutes: Int = 0,
        val otherWorkMinutes: Int = 0,
        val availabilityMinutes: Int = 0,

        // Long-rest boundary latch. Once REST reaches 9h all shift counters are
        // cleared exactly once and stay cleared until work starts again.
        val dailyRestQualified: Boolean = false,
        val currentRestMinutes: Int = 0
    )

    data class Snapshot(
        val activity: Activity,
        val activityMinutes: Int,
        val restMinutes: Int,
        val continuousDrivingMinutes: Int,
        val shiftDrivingMinutes: Int,
        val continuousWorkMinutes: Int,
        val otherWorkMinutes: Int,
        val availabilityMinutes: Int,
        val twoWeekDrivingMinutes: Int,
        val newShiftAfterDailyRest: Boolean,
        val dailyRestQualified: Boolean
    )

    data class Result(val state: State, val snapshot: Snapshot)

    fun activityFromText(text: String): Activity = when {
        text.contains("ВОЖДЕНИЕ", ignoreCase = true) -> Activity.DRIVING
        text.contains("ДРУГАЯ РАБОТА", ignoreCase = true) -> Activity.OTHER_WORK
        text.contains("ГОТОВНОСТЬ", ignoreCase = true) || text.contains("ОЖИДАНИЕ", ignoreCase = true) -> Activity.AVAILABILITY
        text.contains("ОТДЫХ", ignoreCase = true) || text.contains("ПЕРЕРЫВ", ignoreCase = true) -> Activity.REST
        else -> Activity.UNKNOWN
    }

    fun reduce(input: State, raw: RawSample): Result {
        val sample = raw.copy(
            activityMinutes = raw.activityMinutes.coerceAtLeast(0),
            continuousDrivingMinutes = raw.continuousDrivingMinutes.coerceAtLeast(0),
            breakMinutes = raw.breakMinutes.coerceAtLeast(0),
            twoWeekDrivingMinutes = raw.twoWeekDrivingMinutes.coerceAtLeast(0)
        )

        if (!input.initialized) {
            val first = State(
                initialized = true,
                previousActivity = sample.activity,
                previousActivityMinutes = sample.activityMinutes,
                previousContinuousDrivingMinutes = sample.continuousDrivingMinutes,
                lastSampleMillis = sample.nowMillis,
                continuousWorkMinutes = if (sample.activity.isWork()) sample.activityMinutes else 0,
                otherWorkMinutes = if (sample.activity == Activity.OTHER_WORK) sample.activityMinutes else 0,
                availabilityMinutes = if (sample.activity == Activity.AVAILABILITY) sample.activityMinutes else 0,
                dailyRestQualified = sample.activity == Activity.REST && sample.activityMinutes >= DAILY_REST_REDUCED,
                currentRestMinutes = if (sample.activity == Activity.REST) sample.activityMinutes else 0
            )
            return Result(first, snapshot(first, sample, false))
        }

        var s = input
        var newShift = false

        // 1. Daily rest is a hard boundary. At >=9h the previous shift is finished.
        if (sample.activity == Activity.REST && sample.activityMinutes >= DAILY_REST_REDUCED) {
            if (!s.dailyRestQualified) {
                s = s.copy(
                    completedDrivingBlocksMinutes = 0,
                    continuousWorkMinutes = 0,
                    otherWorkMinutes = 0,
                    availabilityMinutes = 0,
                    dailyRestQualified = true
                )
            }
        }

        // 2. Leaving a qualified daily rest starts a clean new shift.
        if (s.dailyRestQualified && s.previousActivity == Activity.REST && sample.activity != Activity.REST) {
            s = s.copy(
                completedDrivingBlocksMinutes = 0,
                continuousWorkMinutes = 0,
                otherWorkMinutes = 0,
                availabilityMinutes = 0,
                dailyRestQualified = false
            )
            newShift = true
        }

        // 3. A 45-minute rest resets only the 4:30 period and the 6h continuous-work
        // window. It must NEVER reset driving-for-shift.
        val restReached45Now = sample.activity == Activity.REST && sample.activityMinutes >= DRIVING_BREAK_RESET
        val restReached45Before = s.previousActivity == Activity.REST && s.previousActivityMinutes >= DRIVING_BREAK_RESET
        if (restReached45Now && !restReached45Before && !s.dailyRestQualified) {
            s = s.copy(
                continuousWorkMinutes = 0,
                otherWorkMinutes = 0,
                availabilityMinutes = 0
            )
        }

        // 4. Detect a completed 4:30 driving block when F923 falls. Preserve that
        // block in shift driving unless this is a daily-rest reset.
        if (!s.dailyRestQualified && !newShift &&
            s.previousContinuousDrivingMinutes > 0 &&
            sample.continuousDrivingMinutes < s.previousContinuousDrivingMinutes
        ) {
            s = s.copy(
                completedDrivingBlocksMinutes = s.completedDrivingBlocksMinutes + s.previousContinuousDrivingMinutes
            )
        }

        // 5. Add ONLY the incremental part of F927. This is the key protection from
        // the old double-counting bug where a whole activity duration was repeatedly
        // copied into another counter every cycle.
        if (!s.dailyRestQualified) {
            val delta = activityDelta(s.previousActivity, s.previousActivityMinutes, sample.activity, sample.activityMinutes)
            if (delta > 0) {
                s = when (sample.activity) {
                    Activity.DRIVING -> s.copy(continuousWorkMinutes = s.continuousWorkMinutes + delta)
                    Activity.OTHER_WORK -> s.copy(
                        continuousWorkMinutes = s.continuousWorkMinutes + delta,
                        otherWorkMinutes = s.otherWorkMinutes + delta
                    )
                    Activity.AVAILABILITY -> s.copy(availabilityMinutes = s.availabilityMinutes + delta)
                    else -> s
                }
            }
        }

        s = s.copy(
            previousActivity = sample.activity,
            previousActivityMinutes = sample.activityMinutes,
            previousContinuousDrivingMinutes = sample.continuousDrivingMinutes,
            lastSampleMillis = sample.nowMillis,
            currentRestMinutes = if (sample.activity == Activity.REST) sample.activityMinutes else 0
        )

        return Result(s, snapshot(s, sample, newShift))
    }

    private fun activityDelta(
        previousActivity: Activity,
        previousMinutes: Int,
        activity: Activity,
        minutes: Int
    ): Int {
        if (activity == Activity.UNKNOWN || activity == Activity.REST) return 0
        return if (activity == previousActivity) {
            if (minutes >= previousMinutes) minutes - previousMinutes else minutes
        } else {
            minutes
        }.coerceAtLeast(0)
    }

    private fun snapshot(state: State, sample: RawSample, newShift: Boolean): Snapshot {
        val shiftDriving = if (state.dailyRestQualified) 0
        else state.completedDrivingBlocksMinutes + sample.continuousDrivingMinutes

        return Snapshot(
            activity = sample.activity,
            activityMinutes = sample.activityMinutes,
            restMinutes = if (sample.activity == Activity.REST) sample.activityMinutes else 0,
            continuousDrivingMinutes = sample.continuousDrivingMinutes,
            shiftDrivingMinutes = shiftDriving,
            continuousWorkMinutes = if (state.dailyRestQualified) 0 else state.continuousWorkMinutes,
            otherWorkMinutes = if (state.dailyRestQualified) 0 else state.otherWorkMinutes,
            availabilityMinutes = if (state.dailyRestQualified) 0 else state.availabilityMinutes,
            twoWeekDrivingMinutes = sample.twoWeekDrivingMinutes,
            newShiftAfterDailyRest = newShift,
            dailyRestQualified = state.dailyRestQualified
        )
    }

    private fun Activity.isWork(): Boolean = this == Activity.DRIVING || this == Activity.OTHER_WORK
}
