package com.pylikv.tachowatch

import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure Regulation (EC) 561/2006 + Directive 2002/15/EC counter state machine.
 *
 * Important separation of clocks:
 *  - continuous driving (4:30) comes from DTCO F923 and is never recalculated here;
 *  - daily driving (9:00 / 10:00) survives 45-minute driving breaks and resets only
 *    after a qualifying daily/weekly rest (>= 9h);
 *  - continuous work is DRIVING + OTHER_WORK only; AVAILABILITY is not working time;
 *  - a work break segment of at least 15 minutes interrupts the six-hour work period;
 *    total work-break entitlement is 30 min for >6..9h work and 45 min for >9h work;
 *  - reduced daily rests (9h..<11h) are counted between weekly rests; a 3h+9h
 *    split is regular daily rest, not reduced;
 *  - a weekly rest (>=24h) starts a new reduced-daily-rest allowance cycle.
 */
object RtoCore {
    const val DRIVING_PERIOD_LIMIT = 4 * 60 + 30
    const val DAILY_DRIVING_NORMAL = 9 * 60
    const val DAILY_DRIVING_EXTENDED = 10 * 60
    const val WEEKLY_DRIVING_LIMIT = 56 * 60
    const val TWO_WEEK_DRIVING_LIMIT = 90 * 60
    const val CONTINUOUS_WORK_LIMIT = 6 * 60
    const val DAILY_REST_REDUCED = 9 * 60
    const val DAILY_REST_REGULAR = 11 * 60
    const val SPLIT_REST_FIRST_PART = 3 * 60
    const val WEEKLY_REST_REDUCED = 24 * 60
    const val WEEKLY_REST_REGULAR = 45 * 60
    const val MAX_REDUCED_DAILY_RESTS_BETWEEN_WEEKLY_RESTS = 3
    const val MAX_TEN_HOUR_DRIVING_DAYS_PER_WEEK = 2

    enum class Activity { REST, AVAILABILITY, OTHER_WORK, DRIVING, UNKNOWN }

    data class State(
        val initialized: Boolean = false,
        val previousActivity: Activity = Activity.UNKNOWN,
        val previousActivityMinutes: Int = 0,
        val previousContinuousDrivingMinutes: Int = 0,
        val dailyDrivingCompletedMinutes: Int = 0,
        val dailyWorkCompletedMinutes: Int = 0,
        val continuousWorkCompletedMinutes: Int = 0,
        val otherWorkCompletedMinutes: Int = 0,
        val availabilityCompletedMinutes: Int = 0,
        val qualifyingWorkBreakCompletedMinutes: Int = 0,
        val splitThreeHourPartTaken: Boolean = false,
        val dailyRestLatched: Boolean = false,
        val dailyRestWasSplit: Boolean = false,
        val weeklyRestLatched: Boolean = false,
        val reducedDailyRestsUsed: Int = 0,
        val tenHourDrivingUsesThisWeek: Int = 0,
        val isoWeekKey: Int = -1
    )

    data class Sample(
        val activity: Activity,
        val activityMinutes: Int,
        val continuousDrivingMinutes: Int,
        val nowMillis: Long = System.currentTimeMillis()
    )

    data class Snapshot(
        val continuousDrivingMinutes: Int,
        val dailyDrivingMinutes: Int,
        val dailyDrivingLimitMinutes: Int,
        val continuousWorkMinutes: Int,
        val dailyWorkMinutes: Int,
        val otherWorkDailyMinutes: Int,
        val availabilityDailyMinutes: Int,
        val workBreakCreditedMinutes: Int,
        val workBreakRequiredMinutes: Int,
        val reducedDailyRestsUsed: Int,
        val reducedDailyRestsRemaining: Int,
        val tenHourDrivingUsesThisWeek: Int,
        val tenHourDrivingUsesRemaining: Int,
        val dailyRestActive: Boolean,
        val weeklyRestActive: Boolean
    )

    data class Result(val state: State, val snapshot: Snapshot)

    fun activityFromText(text: String): Activity = when {
        text.contains("ВОЖДЕНИЕ", true) -> Activity.DRIVING
        text.contains("ДРУГАЯ РАБОТА", true) || text.contains("РАБОТА", true) -> Activity.OTHER_WORK
        text.contains("ГОТОВНОСТЬ", true) -> Activity.AVAILABILITY
        text.contains("ОТДЫХ", true) || text.contains("ПЕРЕРЫВ", true) -> Activity.REST
        else -> Activity.UNKNOWN
    }

    fun initialState(
        sample: Sample,
        cardDailyDrivingMinutes: Int = 0,
        cardDailyWorkMinutes: Int = 0,
        cardOtherWorkMinutes: Int = 0,
        cardAvailabilityMinutes: Int = 0,
        reducedDailyRestsUsed: Int = 0,
        tenHourUsesThisWeek: Int = 0
    ): State {
        val currentWork = if (sample.activity == Activity.DRIVING || sample.activity == Activity.OTHER_WORK) sample.activityMinutes else 0
        val currentOther = if (sample.activity == Activity.OTHER_WORK) sample.activityMinutes else 0
        val currentAvail = if (sample.activity == Activity.AVAILABILITY) sample.activityMinutes else 0
        return State(
            initialized = true,
            previousActivity = sample.activity,
            previousActivityMinutes = sample.activityMinutes,
            previousContinuousDrivingMinutes = sample.continuousDrivingMinutes,
            dailyDrivingCompletedMinutes = (cardDailyDrivingMinutes - sample.continuousDrivingMinutes).coerceAtLeast(0),
            dailyWorkCompletedMinutes = (cardDailyWorkMinutes - currentWork).coerceAtLeast(0),
            // Continuous work cannot be reconstructed perfectly from card aggregates.
            // F923 is a safe lower bound for prior driving in the current driving period.
            continuousWorkCompletedMinutes = when (sample.activity) {
                Activity.DRIVING -> (sample.continuousDrivingMinutes - sample.activityMinutes).coerceAtLeast(0)
                Activity.OTHER_WORK -> sample.continuousDrivingMinutes.coerceAtLeast(0)
                else -> 0
            },
            otherWorkCompletedMinutes = (cardOtherWorkMinutes - currentOther).coerceAtLeast(0),
            availabilityCompletedMinutes = (cardAvailabilityMinutes - currentAvail).coerceAtLeast(0),
            reducedDailyRestsUsed = reducedDailyRestsUsed.coerceIn(0, MAX_REDUCED_DAILY_RESTS_BETWEEN_WEEKLY_RESTS),
            tenHourDrivingUsesThisWeek = tenHourUsesThisWeek.coerceAtLeast(0),
            isoWeekKey = isoWeekKey(sample.nowMillis)
        )
    }

    fun reduce(input: State, sample: Sample): Result {
        var s = if (!input.initialized) initialState(sample) else input

        val weekKey = isoWeekKey(sample.nowMillis)
        if (s.isoWeekKey != weekKey) {
            s = s.copy(isoWeekKey = weekKey, tenHourDrivingUsesThisWeek = 0)
        }

        // Finalise the previous F927 activity segment exactly once on an activity change.
        if (sample.activity != s.previousActivity) {
            s = finaliseSegment(s, s.previousActivity, s.previousActivityMinutes)
        }

        // F923 is the DTCO continuous-driving clock. When it drops, preserve the
        // finished driving block in DAILY driving. This must NOT depend on F925 or 45m.
        if (s.previousContinuousDrivingMinutes > 0 &&
            sample.continuousDrivingMinutes < s.previousContinuousDrivingMinutes &&
            !s.dailyRestLatched
        ) {
            s = s.copy(
                dailyDrivingCompletedMinutes = s.dailyDrivingCompletedMinutes + s.previousContinuousDrivingMinutes
            )
        }

        // A qualifying work-break part is >=15 minutes. It interrupts the 6h work clock.
        if (sample.activity == Activity.REST && sample.activityMinutes >= 15 &&
            (s.previousActivity != Activity.REST || s.previousActivityMinutes < 15)
        ) {
            s = s.copy(continuousWorkCompletedMinutes = 0)
        }

        // A daily rest completes at 9h. Daily driving/work counters reset here, not at
        // a 45m break and not merely because a country/shift END was entered.
        if (sample.activity == Activity.REST && sample.activityMinutes >= DAILY_REST_REDUCED && !s.dailyRestLatched) {
            val finishedDailyDriving = currentDailyDriving(s, sample)
            val extensionUsed = if (finishedDailyDriving > DAILY_DRIVING_NORMAL) 1 else 0
            s = s.copy(
                dailyDrivingCompletedMinutes = 0,
                dailyWorkCompletedMinutes = 0,
                continuousWorkCompletedMinutes = 0,
                otherWorkCompletedMinutes = 0,
                availabilityCompletedMinutes = 0,
                qualifyingWorkBreakCompletedMinutes = 0,
                dailyRestLatched = true,
                dailyRestWasSplit = s.splitThreeHourPartTaken,
                tenHourDrivingUsesThisWeek = s.tenHourDrivingUsesThisWeek + extensionUsed
            )
        }

        // Any weekly rest (reduced or regular) starts a new "between weekly rests" cycle.
        if (sample.activity == Activity.REST && sample.activityMinutes >= WEEKLY_REST_REDUCED && !s.weeklyRestLatched) {
            s = s.copy(
                weeklyRestLatched = true,
                reducedDailyRestsUsed = 0
            )
        }

        s = s.copy(
            previousActivity = sample.activity,
            previousActivityMinutes = sample.activityMinutes,
            previousContinuousDrivingMinutes = sample.continuousDrivingMinutes
        )

        return Result(s, snapshot(s, sample))
    }

    private fun finaliseSegment(state: State, activity: Activity, minutes: Int): State {
        var s = state
        when (activity) {
            Activity.DRIVING -> s = s.copy(
                dailyWorkCompletedMinutes = s.dailyWorkCompletedMinutes + minutes,
                continuousWorkCompletedMinutes = s.continuousWorkCompletedMinutes + minutes
            )
            Activity.OTHER_WORK -> s = s.copy(
                dailyWorkCompletedMinutes = s.dailyWorkCompletedMinutes + minutes,
                continuousWorkCompletedMinutes = s.continuousWorkCompletedMinutes + minutes,
                otherWorkCompletedMinutes = s.otherWorkCompletedMinutes + minutes
            )
            Activity.AVAILABILITY -> s = s.copy(
                availabilityCompletedMinutes = s.availabilityCompletedMinutes + minutes
            )
            Activity.REST -> {
                // A short/ordinary work break belongs to the current workday and may be
                // credited. A completed >=9h daily rest starts a NEW workday, so that
                // long rest must never be carried forward as break credit.
                if (minutes >= 15 && !s.dailyRestLatched) {
                    s = s.copy(
                        qualifyingWorkBreakCompletedMinutes = s.qualifyingWorkBreakCompletedMinutes + minutes,
                        continuousWorkCompletedMinutes = 0
                    )
                }

                if (minutes in SPLIT_REST_FIRST_PART until DAILY_REST_REDUCED) {
                    s = s.copy(splitThreeHourPartTaken = true)
                }

                if (s.dailyRestLatched) {
                    // 9..<11h is reduced only when it was not the second 9h part of 3+9.
                    val reduced = minutes in DAILY_REST_REDUCED until DAILY_REST_REGULAR && !s.dailyRestWasSplit
                    s = s.copy(
                        reducedDailyRestsUsed = if (s.weeklyRestLatched) 0 else
                            (s.reducedDailyRestsUsed + if (reduced) 1 else 0)
                                .coerceAtMost(MAX_REDUCED_DAILY_RESTS_BETWEEN_WEEKLY_RESTS),
                        qualifyingWorkBreakCompletedMinutes = 0,
                        dailyRestLatched = false,
                        dailyRestWasSplit = false,
                        splitThreeHourPartTaken = false,
                        weeklyRestLatched = false
                    )
                }
            }
            Activity.UNKNOWN -> Unit
        }
        return s
    }

    private fun snapshot(state: State, sample: Sample): Snapshot {
        val dailyDriving = if (state.dailyRestLatched) 0 else currentDailyDriving(state, sample)
        val currentWork = if (!state.dailyRestLatched &&
            (sample.activity == Activity.DRIVING || sample.activity == Activity.OTHER_WORK)
        ) sample.activityMinutes else 0
        val currentOther = if (!state.dailyRestLatched && sample.activity == Activity.OTHER_WORK) sample.activityMinutes else 0
        val currentAvail = if (!state.dailyRestLatched && sample.activity == Activity.AVAILABILITY) sample.activityMinutes else 0

        val continuousWork = if (sample.activity == Activity.REST && sample.activityMinutes >= 15) 0
        else state.continuousWorkCompletedMinutes +
            if (sample.activity == Activity.DRIVING || sample.activity == Activity.OTHER_WORK) sample.activityMinutes else 0

        val dailyWork = state.dailyWorkCompletedMinutes + currentWork
        val workBreakCredit = if (state.dailyRestLatched) 0 else state.qualifyingWorkBreakCompletedMinutes +
            if (sample.activity == Activity.REST && sample.activityMinutes >= 15 && sample.activityMinutes < DAILY_REST_REDUCED) sample.activityMinutes else 0
        val workBreakRequired = when {
            dailyWork > 9 * 60 -> 45
            dailyWork > 6 * 60 -> 30
            else -> 0
        }

        val dailyDrivingLimit = if (state.tenHourDrivingUsesThisWeek >= MAX_TEN_HOUR_DRIVING_DAYS_PER_WEEK)
            DAILY_DRIVING_NORMAL else DAILY_DRIVING_EXTENDED

        return Snapshot(
            continuousDrivingMinutes = sample.continuousDrivingMinutes,
            dailyDrivingMinutes = dailyDriving,
            dailyDrivingLimitMinutes = dailyDrivingLimit,
            continuousWorkMinutes = continuousWork,
            dailyWorkMinutes = dailyWork,
            otherWorkDailyMinutes = state.otherWorkCompletedMinutes + currentOther,
            availabilityDailyMinutes = state.availabilityCompletedMinutes + currentAvail,
            workBreakCreditedMinutes = workBreakCredit,
            workBreakRequiredMinutes = workBreakRequired,
            reducedDailyRestsUsed = state.reducedDailyRestsUsed,
            reducedDailyRestsRemaining = (MAX_REDUCED_DAILY_RESTS_BETWEEN_WEEKLY_RESTS - state.reducedDailyRestsUsed).coerceAtLeast(0),
            tenHourDrivingUsesThisWeek = state.tenHourDrivingUsesThisWeek,
            tenHourDrivingUsesRemaining = (MAX_TEN_HOUR_DRIVING_DAYS_PER_WEEK - state.tenHourDrivingUsesThisWeek).coerceAtLeast(0),
            dailyRestActive = state.dailyRestLatched,
            weeklyRestActive = state.weeklyRestLatched
        )
    }

    private fun currentDailyDriving(state: State, sample: Sample): Int =
        state.dailyDrivingCompletedMinutes + sample.continuousDrivingMinutes

    fun isoWeekKey(nowMillis: Long): Int {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            time = Date(nowMillis)
        }
        return c.getWeekYear() * 100 + c.get(Calendar.WEEK_OF_YEAR)
    }
}
