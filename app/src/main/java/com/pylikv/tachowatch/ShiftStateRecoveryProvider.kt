package com.pylikv.tachowatch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver

/** Reconciles persisted derived counters from authoritative driver-card history. */
class ShiftStateRecoveryProvider : ContentProvider() {
    private var observer: FileObserver? = null

    override fun onCreate(): Boolean {
        val c = context ?: return false
        reconcileLatest(c)
        val dir = c.getExternalFilesDir(null)
        if (dir != null) {
            observer = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO or CREATE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path?.endsWith(".ddd", ignoreCase = true) == true) reconcileLatest(c)
                }
            }.also { it.startWatching() }
        }
        return true
    }

    private fun reconcileLatest(context: Context) {
        runCatching {
            val file = TlvInventory.findLatestDdd(context.getExternalFilesDir(null)) ?: return
            val fingerprint = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
            val prefs = context.getSharedPreferences(DriverLiveService.PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_CARD_FINGERPRINT, null) == fingerprint) return

            val parsed = TlvInventory.parse(file)
            if (parsed.error != null) return
            val history = HistoryData.load(parsed)
            val seed = currentShiftSeed(history) ?: return

            val liveContinuous = prefs.getInt(DriverLiveService.SNAP_CONTINUOUS_MIN, 0).coerceAtLeast(0)
            val completedDriving = (seed.drivingMinutes - liveContinuous).coerceAtLeast(0)

            prefs.edit()
                .putBoolean(DriverLiveService.SHIFT_INITIALIZED, true)
                .putInt(DriverLiveService.SHIFT_COMPLETED, completedDriving)
                .putInt(DriverLiveService.SHIFT_PREV_CONTINUOUS, liveContinuous)
                // Work window is NOT the whole shift. Rebuild it only from activity after
                // the last >=30 min qualifying work break. This prevents stale values such
                // as 6:04 being resurrected after Android kills the process.
                .putInt(DriverLiveService.WORK_WINDOW, seed.workWindowMinutes)
                .putInt(DriverLiveService.WORK_ACC, seed.workWindowOtherMinutes)
                .putInt(DriverLiveService.AVAIL_ACC, seed.availabilityMinutes)
                .putString(DriverLiveService.WORK_PREV_ACTIVITY, "—")
                .putInt(DriverLiveService.WORK_PREV_DURATION, 0)
                .putString(KEY_CARD_FINGERPRINT, fingerprint)
                .putString(KEY_SHIFT_ID, seed.id)
                .putLong(KEY_RECONCILED_AT, System.currentTimeMillis())
                .apply()

            CounterRecoveryEngine(context).recordCardVerified(
                activity = prefs.getString(DriverLiveService.SNAP_ACTIVITY, "—") ?: "—",
                continuousMin = liveContinuous,
                shiftDrivingMin = seed.drivingMinutes,
                workWindowMin = seed.workWindowMinutes
            )
        }
    }

    private data class Seed(
        val id: String,
        val drivingMinutes: Int,
        val workMinutes: Int,
        val availabilityMinutes: Int,
        val workWindowMinutes: Int,
        val workWindowOtherMinutes: Int
    )

    /** Build current shift and current work window from card periods, not calendar totals. */
    private fun currentShiftSeed(model: HistoryData.Model): Seed? {
        val days = model.days
        val latest = days.lastOrNull() ?: return null
        val periods = latest.periods

        if (periods.isEmpty()) {
            val previous = days.getOrNull(days.lastIndex - 1)
            val gap = previous?.let { HistoryData.actualGapMinutes(it, latest) }
            if (previous != null && (gap == null || gap < DAILY_REST_MINUTES)) return null
            return Seed(
                id = "${latest.date}|${latest.startTime ?: "?"}",
                drivingMinutes = latest.drivingMinutes,
                workMinutes = latest.workMinutes,
                availabilityMinutes = latest.availabilityMinutes,
                workWindowMinutes = latest.drivingMinutes + latest.workMinutes,
                workWindowOtherMinutes = latest.workMinutes
            )
        }

        val lastDailyRestIndex = periods.indexOfLast { it.type == "REST" && it.minutes >= DAILY_REST_MINUTES }
        val active = if (lastDailyRestIndex >= 0) periods.drop(lastDailyRestIndex + 1) else periods
        val firstActive = active.firstOrNull { it.type != "REST" }

        if (lastDailyRestIndex < 0 && days.size >= 2) {
            val previous = days[days.lastIndex - 1]
            val gap = HistoryData.actualGapMinutes(previous, latest)
            if (gap != null && gap < DAILY_REST_MINUTES && latest.startTime == null) return null
        }

        val lastWorkBreak = active.indexOfLast { it.type == "REST" && it.minutes >= WORK_BREAK_MINUTES }
        val workWindow = if (lastWorkBreak >= 0) active.drop(lastWorkBreak + 1) else active

        return Seed(
            id = "${latest.date}|${firstActive?.startTime ?: latest.startTime ?: "?"}",
            drivingMinutes = active.filter { it.type == "DRIVING" }.sumOf { it.minutes },
            workMinutes = active.filter { it.type == "WORK" }.sumOf { it.minutes },
            availabilityMinutes = active.filter { it.type == "AVAILABILITY" }.sumOf { it.minutes },
            workWindowMinutes = workWindow.filter { it.type == "DRIVING" || it.type == "WORK" }.sumOf { it.minutes },
            workWindowOtherMinutes = workWindow.filter { it.type == "WORK" }.sumOf { it.minutes }
        )
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val DAILY_REST_MINUTES = 9 * 60
        private const val WORK_BREAK_MINUTES = 30
        private const val KEY_CARD_FINGERPRINT = "recovery_card_fingerprint"
        private const val KEY_SHIFT_ID = "recovery_shift_id"
        private const val KEY_RECONCILED_AT = "recovery_reconciled_at"
    }
}
