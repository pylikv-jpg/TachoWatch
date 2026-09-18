package com.pylikv.tachowatch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver

/** Reconcile persisted shift state only from a newly completed driver-card download. */
class ShiftStateRecoveryProvider : ContentProvider() {
    private var observer: FileObserver? = null

    override fun onCreate(): Boolean {
        val c = context ?: return false
        val dir = c.getExternalFilesDir(null)
        if (dir != null) {
            observer = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
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
            val seed = currentShiftSeed(HistoryData.load(parsed)) ?: return

            prefs.edit()
                .putBoolean(DriverLiveService.SHIFT_INITIALIZED, true)
                .putInt(DriverLiveService.SHIFT_COMPLETED, seed.completedDrivingMinutes)
                .putInt(DriverLiveService.SHIFT_PREV_CONTINUOUS, seed.liveDrivingSegmentMinutes)
                .putInt(DriverLiveService.WORK_WINDOW, seed.activeWorkWindowMinutes)
                .putInt(DriverLiveService.WORK_ACC, seed.workMinutes)
                .putInt(DriverLiveService.AVAIL_ACC, seed.availabilityMinutes)
                .putString(DriverLiveService.WORK_PREV_ACTIVITY, "—")
                .putInt(DriverLiveService.WORK_PREV_DURATION, 0)
                .putString(KEY_CARD_FINGERPRINT, fingerprint)
                .putString(KEY_SHIFT_ID, seed.id)
                .putLong(KEY_RECONCILED_AT, System.currentTimeMillis())
                .apply()

            // The service may still hold pre-read counters in RAM. Recreate it after the card
            // read so the authoritative card seed is loaded before live polling resumes.
            context.stopService(Intent(context, DriverLiveService::class.java))
        }
    }

    private data class Seed(
        val id: String,
        val drivingMinutes: Int,
        val completedDrivingMinutes: Int,
        val liveDrivingSegmentMinutes: Int,
        val activeWorkWindowMinutes: Int,
        val workMinutes: Int,
        val availabilityMinutes: Int
    )

    private fun currentShiftSeed(model: HistoryData.Model): Seed? {
        val days = model.days
        val latest = days.lastOrNull() ?: return null
        val periods = latest.periods
        if (periods.isEmpty()) return null

        val lastDailyRestIndex = periods.indexOfLast { it.type == "REST" && it.minutes >= DAILY_REST_MINUTES }
        val active = if (lastDailyRestIndex >= 0) periods.drop(lastDailyRestIndex + 1) else periods
        val firstActive = active.firstOrNull { it.type != "REST" }

        if (lastDailyRestIndex < 0 && days.size >= 2) {
            val previous = days[days.lastIndex - 1]
            val gap = HistoryData.actualGapMinutes(previous, latest)
            if (gap != null && gap < DAILY_REST_MINUTES && latest.startTime == null) return null
        }

        val driving = active.filter { it.type == "DRIVING" }.sumOf { it.minutes }
        val lastDrivingIndex = active.indexOfLast { it.type == "DRIVING" }
        val lastDrivingMinutes = active.getOrNull(lastDrivingIndex)?.minutes ?: 0
        val tailAfterDriving = if (lastDrivingIndex >= 0) active.drop(lastDrivingIndex + 1) else emptyList()
        val tailRestMinutes = tailAfterDriving.filter { it.type == "REST" }.sumOf { it.minutes }
        val hasActiveNonRestAfterDriving = tailAfterDriving.any { it.type != "REST" }

        val lastSegmentStillLive = lastDrivingIndex >= 0 && !hasActiveNonRestAfterDriving && tailRestMinutes < CONTINUOUS_BREAK_MINUTES
        val liveSegment = if (lastSegmentStillLive) lastDrivingMinutes else 0
        val completed = (driving - liveSegment).coerceAtLeast(0)

        return Seed(
            id = "${latest.date}|${firstActive?.startTime ?: latest.startTime ?: "?"}",
            drivingMinutes = driving,
            completedDrivingMinutes = completed,
            liveDrivingSegmentMinutes = liveSegment,
            activeWorkWindowMinutes = activeWorkWindow(active),
            workMinutes = active.filter { it.type == "WORK" }.sumOf { it.minutes },
            availabilityMinutes = active.filter { it.type == "AVAILABILITY" }.sumOf { it.minutes }
        )
    }

    /**
     * Rebuild the six-hour working-time window from card periods instead of seeding it with
     * all work since the start of the shift. Driving and OTHER WORK count; availability does
     * not. Working-time breaks are separate from the 4:30 driving-break state machine.
     */
    private fun activeWorkWindow(periods: List<HistoryData.ActivityPeriod>): Int {
        var workWindow = 0
        var qualifyingBreak = 0
        for (period in periods) {
            when (period.type) {
                "DRIVING", "WORK" -> {
                    workWindow += period.minutes
                }
                "REST" -> {
                    if (period.minutes >= MIN_BREAK_PART_MINUTES) {
                        qualifyingBreak += period.minutes
                        if (qualifyingBreak >= WORK_BREAK_MINUTES) {
                            workWindow = 0
                            qualifyingBreak = 0
                        }
                    }
                }
                // Availability is excluded from working time and is not itself a break.
                "AVAILABILITY" -> Unit
            }
        }
        return workWindow
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val DAILY_REST_MINUTES = 9 * 60
        private const val CONTINUOUS_BREAK_MINUTES = 45
        private const val MIN_BREAK_PART_MINUTES = 15
        private const val WORK_BREAK_MINUTES = 30
        const val KEY_RECONCILED_AT = "recovery_reconciled_at"
        private const val KEY_CARD_FINGERPRINT = "recovery_card_fingerprint"
        private const val KEY_SHIFT_ID = "recovery_shift_id"
    }
}
