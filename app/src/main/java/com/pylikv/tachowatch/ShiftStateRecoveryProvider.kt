package com.pylikv.tachowatch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper

/**
 * Repairs persisted live counters from authoritative driver-card history.
 *
 * DriverLiveService persists its counters so UI/process recreation does not lose a shift.
 * If Android kills the process during a qualifying daily/weekly rest, however, live polling
 * can miss the rest boundary and later restore the previous shift. A completed DDD download
 * is authoritative for boundaries that happened while live monitoring was absent.
 *
 * The provider starts before the launcher Activity and also watches the DDD directory, so a
 * newly downloaded card reconciles the live state exactly once without coupling the card
 * reader UI to the live service.
 */
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

            // F923 is the current continuous-driving segment. Keep only earlier driving in
            // SHIFT_COMPLETED; otherwise the dashboard adds the same segment twice.
            val liveContinuous = prefs.getInt(DriverLiveService.SNAP_CONTINUOUS_MIN, 0).coerceAtLeast(0)
            val completedDriving = (seed.drivingMinutes - liveContinuous).coerceAtLeast(0)

            prefs.edit()
                .putBoolean(DriverLiveService.SHIFT_INITIALIZED, true)
                .putInt(DriverLiveService.SHIFT_COMPLETED, completedDriving)
                .putInt(DriverLiveService.SHIFT_PREV_CONTINUOUS, liveContinuous)
                .putInt(DriverLiveService.WORK_WINDOW, seed.drivingMinutes + seed.workMinutes)
                .putInt(DriverLiveService.WORK_ACC, seed.workMinutes)
                .putInt(DriverLiveService.AVAIL_ACC, seed.availabilityMinutes)
                // Do not carry an F927 delta baseline across a process death/card download.
                .putString(DriverLiveService.WORK_PREV_ACTIVITY, "—")
                .putInt(DriverLiveService.WORK_PREV_DURATION, 0)
                .putString(KEY_CARD_FINGERPRINT, fingerprint)
                .putString(KEY_SHIFT_ID, seed.id)
                .putLong(KEY_RECONCILED_AT, System.currentTimeMillis())
                .apply()

            // SharedPreferences are now authoritative, but a running service keeps its own
            // in-memory accumulator. Restart it after the completed DDD write so onCreate()
            // reloads the reconciled values instead of overwriting them on the next live cycle.
            // This does NOT trigger another card read; it only resumes the existing live DTCO
            // connection from the saved address.
            val address = prefs.getString(DriverLiveService.SELECTED_DTCO, null)
            if (DriverLiveService.isRunning() && !address.isNullOrBlank()) {
                DriverLiveService.stop(context)
                Handler(Looper.getMainLooper()).postDelayed({
                    DriverLiveService.start(context.applicationContext, address)
                }, LIVE_RESTART_DELAY_MS)
            }
        }
    }

    private data class Seed(
        val id: String,
        val drivingMinutes: Int,
        val workMinutes: Int,
        val availabilityMinutes: Int
    )

    /** Build the current shift from card periods, not from calendar-day totals. */
    private fun currentShiftSeed(model: HistoryData.Model): Seed? {
        val days = model.days
        val latest = days.lastOrNull() ?: return null
        val periods = latest.periods

        if (periods.isEmpty()) {
            // Legacy fallback: only trust day totals if a cross-day qualifying rest is proven.
            val previous = days.getOrNull(days.lastIndex - 1)
            val gap = previous?.let { HistoryData.actualGapMinutes(it, latest) }
            if (previous != null && (gap == null || gap < DAILY_REST_MINUTES)) return null
            return Seed(
                id = "${latest.date}|${latest.startTime ?: "?"}",
                drivingMinutes = latest.drivingMinutes,
                workMinutes = latest.workMinutes,
                availabilityMinutes = latest.availabilityMinutes
            )
        }

        // A >=9h REST inside the latest card day is a real shift boundary. Everything after
        // it belongs to the new shift. This also handles two shifts that start on one date.
        val lastDailyRestIndex = periods.indexOfLast { it.type == "REST" && it.minutes >= DAILY_REST_MINUTES }
        val active = if (lastDailyRestIndex >= 0) periods.drop(lastDailyRestIndex + 1) else periods
        val firstActive = active.firstOrNull { it.type != "REST" }

        if (lastDailyRestIndex < 0 && days.size >= 2) {
            val previous = days[days.lastIndex - 1]
            val gap = HistoryData.actualGapMinutes(previous, latest)
            if (gap != null && gap < DAILY_REST_MINUTES && latest.startTime == null) return null
        }

        return Seed(
            id = "${latest.date}|${firstActive?.startTime ?: latest.startTime ?: "?"}",
            drivingMinutes = active.filter { it.type == "DRIVING" }.sumOf { it.minutes },
            workMinutes = active.filter { it.type == "WORK" }.sumOf { it.minutes },
            availabilityMinutes = active.filter { it.type == "AVAILABILITY" }.sumOf { it.minutes }
        )
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val DAILY_REST_MINUTES = 9 * 60
        private const val LIVE_RESTART_DELAY_MS = 1500L
        private const val KEY_CARD_FINGERPRINT = "recovery_card_fingerprint"
        private const val KEY_SHIFT_ID = "recovery_shift_id"
        private const val KEY_RECONCILED_AT = "recovery_reconciled_at"
    }
}
