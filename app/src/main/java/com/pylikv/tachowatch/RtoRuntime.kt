package com.pylikv.tachowatch

import android.content.Context

class RtoRuntime(context: Context) {
    private val prefs = context.getSharedPreferences("tachowatch_rto_v2", Context.MODE_PRIVATE)
    private var state = load()

    fun seedFromCard(history: HistoryData.Model?) {
        if (history == null) return
        prefs.edit()
            .putInt("seed_driving", history.days.lastOrNull()?.drivingMinutes ?: 0)
            .putInt("seed_work", history.days.lastOrNull()?.let { it.drivingMinutes + it.workMinutes } ?: 0)
            .putInt("seed_other", history.days.lastOrNull()?.workMinutes ?: 0)
            .putInt("seed_avail", history.days.lastOrNull()?.availabilityMinutes ?: 0)
            .apply()
    }

    fun update(
        activityText: String,
        activityMinutes: Int,
        continuousDrivingMinutes: Int,
        history: HistoryData.Model?
    ): RtoCore.Snapshot {
        val sample = RtoCore.Sample(
            activity = RtoCore.activityFromText(activityText),
            activityMinutes = activityMinutes.coerceAtLeast(0),
            continuousDrivingMinutes = continuousDrivingMinutes.coerceAtLeast(0)
        )

        if (!state.initialized) {
            val latest = history?.days?.lastOrNull()
            val seedDriving = if (prefs.contains("seed_driving")) prefs.getInt("seed_driving", 0) else latest?.drivingMinutes ?: 0
            val seedWork = if (prefs.contains("seed_work")) prefs.getInt("seed_work", 0) else latest?.let { it.drivingMinutes + it.workMinutes } ?: 0
            val seedOther = if (prefs.contains("seed_other")) prefs.getInt("seed_other", 0) else latest?.workMinutes ?: 0
            val seedAvail = if (prefs.contains("seed_avail")) prefs.getInt("seed_avail", 0) else latest?.availabilityMinutes ?: 0
            state = RtoCore.initialState(
                sample = sample,
                cardDailyDrivingMinutes = seedDriving,
                cardDailyWorkMinutes = seedWork,
                cardOtherWorkMinutes = seedOther,
                cardAvailabilityMinutes = seedAvail,
                reducedDailyRestsUsed = reducedDailyRestsFromHistory(history),
                tenHourUsesThisWeek = tenHourUsesFromHistory(history)
            )
        }

        val result = RtoCore.reduce(state, sample)
        state = result.state
        save(state)
        return result.snapshot
    }

    fun currentState(): RtoCore.State = state

    private fun reducedDailyRestsFromHistory(history: HistoryData.Model?): Int {
        val rests = history?.rests.orEmpty()
        val lastWeeklyIndex = rests.indexOfLast { it.weekly }
        return rests.drop(lastWeeklyIndex + 1).count {
            !it.weekly && !it.splitDaily && it.creditedDailyMinutes == RtoCore.DAILY_REST_REDUCED
        }.coerceAtMost(RtoCore.MAX_REDUCED_DAILY_RESTS_BETWEEN_WEEKLY_RESTS)
    }

    private fun tenHourUsesFromHistory(history: HistoryData.Model?): Int {
        // Calendar-day aggregation is only a fallback seed. Once the app is running,
        // extensions are counted at the qualifying daily-rest boundary by RtoCore.
        return history?.days.orEmpty().count { day ->
            val date = runCatching { java.time.LocalDate.parse(day.date) }.getOrNull() ?: return@count false
            val now = java.time.LocalDate.now(java.time.Clock.systemUTC())
            val wf = java.time.temporal.WeekFields.ISO
            date.get(wf.weekOfWeekBasedYear()) == now.get(wf.weekOfWeekBasedYear()) &&
                date.get(wf.weekBasedYear()) == now.get(wf.weekBasedYear()) &&
                day.drivingMinutes > RtoCore.DAILY_DRIVING_NORMAL
        }.coerceAtMost(RtoCore.MAX_TEN_HOUR_DRIVING_DAYS_PER_WEEK)
    }

    private fun load(): RtoCore.State = RtoCore.State(
        initialized = prefs.getBoolean("initialized", false),
        previousActivity = runCatching {
            RtoCore.Activity.valueOf(prefs.getString("prev_activity", RtoCore.Activity.UNKNOWN.name)!!)
        }.getOrDefault(RtoCore.Activity.UNKNOWN),
        previousActivityMinutes = prefs.getInt("prev_activity_min", 0),
        previousContinuousDrivingMinutes = prefs.getInt("prev_cont_drive", 0),
        dailyDrivingCompletedMinutes = prefs.getInt("daily_drive_done", 0),
        dailyWorkCompletedMinutes = prefs.getInt("daily_work_done", 0),
        continuousWorkCompletedMinutes = prefs.getInt("cont_work_done", 0),
        otherWorkCompletedMinutes = prefs.getInt("other_work_done", 0),
        availabilityCompletedMinutes = prefs.getInt("avail_done", 0),
        qualifyingWorkBreakCompletedMinutes = prefs.getInt("work_break_done", 0),
        splitThreeHourPartTaken = prefs.getBoolean("split3", false),
        dailyRestLatched = prefs.getBoolean("daily_rest_latch", false),
        dailyRestWasSplit = prefs.getBoolean("daily_rest_split", false),
        weeklyRestLatched = prefs.getBoolean("weekly_rest_latch", false),
        reducedDailyRestsUsed = prefs.getInt("reduced_daily_uses", 0),
        tenHourDrivingUsesThisWeek = prefs.getInt("ten_hour_uses", 0),
        isoWeekKey = prefs.getInt("week_key", -1)
    )

    private fun save(s: RtoCore.State) {
        prefs.edit()
            .putBoolean("initialized", s.initialized)
            .putString("prev_activity", s.previousActivity.name)
            .putInt("prev_activity_min", s.previousActivityMinutes)
            .putInt("prev_cont_drive", s.previousContinuousDrivingMinutes)
            .putInt("daily_drive_done", s.dailyDrivingCompletedMinutes)
            .putInt("daily_work_done", s.dailyWorkCompletedMinutes)
            .putInt("cont_work_done", s.continuousWorkCompletedMinutes)
            .putInt("other_work_done", s.otherWorkCompletedMinutes)
            .putInt("avail_done", s.availabilityCompletedMinutes)
            .putInt("work_break_done", s.qualifyingWorkBreakCompletedMinutes)
            .putBoolean("split3", s.splitThreeHourPartTaken)
            .putBoolean("daily_rest_latch", s.dailyRestLatched)
            .putBoolean("daily_rest_split", s.dailyRestWasSplit)
            .putBoolean("weekly_rest_latch", s.weeklyRestLatched)
            .putInt("reduced_daily_uses", s.reducedDailyRestsUsed)
            .putInt("ten_hour_uses", s.tenHourDrivingUsesThisWeek)
            .putInt("week_key", s.isoWeekKey)
            .apply()
    }
}
