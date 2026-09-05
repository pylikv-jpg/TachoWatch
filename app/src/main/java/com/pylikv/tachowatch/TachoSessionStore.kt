package com.pylikv.tachowatch

import android.content.Context

/** Persists only the new engine state. Old counter preferences are intentionally ignored. */
class TachoSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("tachowatch_engine_v3", Context.MODE_PRIVATE)

    fun load(): TachoStateEngine.State = TachoStateEngine.State(
        initialized = prefs.getBoolean("initialized", false),
        previousActivity = runCatching {
            TachoStateEngine.Activity.valueOf(
                prefs.getString("previous_activity", TachoStateEngine.Activity.UNKNOWN.name)
                    ?: TachoStateEngine.Activity.UNKNOWN.name
            )
        }.getOrDefault(TachoStateEngine.Activity.UNKNOWN),
        previousActivityMinutes = prefs.getInt("previous_activity_minutes", 0),
        previousContinuousDrivingMinutes = prefs.getInt("previous_continuous_driving_minutes", 0),
        lastSampleMillis = prefs.getLong("last_sample_millis", 0L),
        completedDrivingBlocksMinutes = prefs.getInt("completed_driving_blocks_minutes", 0),
        continuousWorkMinutes = prefs.getInt("continuous_work_minutes", 0),
        otherWorkMinutes = prefs.getInt("other_work_minutes", 0),
        availabilityMinutes = prefs.getInt("availability_minutes", 0),
        dailyRestQualified = prefs.getBoolean("daily_rest_qualified", false),
        currentRestMinutes = prefs.getInt("current_rest_minutes", 0)
    )

    fun save(state: TachoStateEngine.State) {
        prefs.edit()
            .putBoolean("initialized", state.initialized)
            .putString("previous_activity", state.previousActivity.name)
            .putInt("previous_activity_minutes", state.previousActivityMinutes)
            .putInt("previous_continuous_driving_minutes", state.previousContinuousDrivingMinutes)
            .putLong("last_sample_millis", state.lastSampleMillis)
            .putInt("completed_driving_blocks_minutes", state.completedDrivingBlocksMinutes)
            .putInt("continuous_work_minutes", state.continuousWorkMinutes)
            .putInt("other_work_minutes", state.otherWorkMinutes)
            .putInt("availability_minutes", state.availabilityMinutes)
            .putBoolean("daily_rest_qualified", state.dailyRestQualified)
            .putInt("current_rest_minutes", state.currentRestMinutes)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
