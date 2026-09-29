package com.pylikv.tachowatch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Reconcile persisted shift state only from a newly completed driver-card download. */
class ShiftStateRecoveryProvider : ContentProvider() {
    private var observer: FileObserver? = null

    override fun onCreate(): Boolean {
        val c = context ?: return false
        val prefs = c.getSharedPreferences(DriverLiveService.PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_RECOVERY_VERSION, 0) < RECOVERY_VERSION) {
            // Force one fresh card download after this recovery-model revision.
            // Reusing an older .ddd snapshot here could seed the new counters from stale data.
            prefs.edit().putBoolean(FIRST_READ_KEY, false).apply()
        }

        val dir = c.getExternalFilesDir(null)
        if (dir != null) {
            @Suppress("DEPRECATION")
            observer = object : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path?.endsWith(".ddd", ignoreCase = true) == true) {
                        try {
                            reconcileLatest(c)
                        } finally {
                            DriverLiveService.resumeAfterCardRecovery(c)
                        }
                    }
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
            val now = System.currentTimeMillis()
            val snapshotAgeMs = now - prefs.getLong(DriverLiveService.SNAP_UPDATED_AT, 0L)
            val liveSnapshotFresh = snapshotAgeMs in 0..LIVE_SNAPSHOT_MAX_AGE_MS
            val liveActivityAtCheckpoint = if (liveSnapshotFresh) {
                prefs.getString(DriverLiveService.SNAP_ACTIVITY, "—") ?: "—"
            } else {
                "—"
            }
            val liveRestingAtCheckpoint = liveSnapshotFresh && isRest(liveActivityAtCheckpoint)
            val shiftBoundaryMillis =
                prefs.getLong(DriverLiveService.SHIFT_STARTED_AFTER_DAILY_REST_AT, 0L)
                    .takeIf { boundary ->
                        val age = now - boundary
                        age in 0..MAX_SHIFT_BOUNDARY_AGE_MS
                    }
            val parsed = TlvInventory.parse(file)
            if (parsed.error != null) return
            val model = HistoryData.load(parsed)
            val ongoingRest = ongoingDailyRestMinutes(model)
            val seed = if (ongoingRest != null && ongoingRest >= DAILY_REST_MINUTES) {
                val latest = model.days.lastOrNull() ?: return
                // A fresh card read made during a confirmed daily rest is authoritative:
                // never seed the previous shift merely because the rest crosses midnight
                // and the current rest-only day is absent from the activity-day model.
                Seed(
                    id = "rest|${latest.date}|${latest.endTime ?: "?"}",
                    drivingMinutes = 0,
                    completedDrivingMinutes = 0,
                    liveDrivingSegmentMinutes = 0,
                    continuousDrivingCheckpointMinutes = 0,
                    continuousWorkMinutes = 0,
                    continuousOtherWorkMinutes = 0,
                    workMinutes = 0,
                    availabilityMinutes = 0,
                    splitDailyThreeHourPartTaken = false
                )
            } else {
                currentShiftSeed(model, shiftBoundaryMillis, liveRestingAtCheckpoint) ?: return
            }

            // The card parser intentionally omits the still-open activity because its duration
            // is reported as OPEN. Merge only the part of live F923 that is not already present
            // in the card's current continuous-driving window. This preserves an in-progress
            // driving segment without double-counting earlier driving before OTHER WORK.
            val rawLiveContinuousAtCheckpoint = if (liveSnapshotFresh) {
                prefs.getInt(DriverLiveService.SNAP_CONTINUOUS_MIN, seed.liveDrivingSegmentMinutes)
            } else {
                seed.liveDrivingSegmentMinutes
            }
            val liveActivityMinutesAtCheckpoint = if (liveSnapshotFresh) {
                prefs.getInt(DriverLiveService.SNAP_ACTIVITY_MIN, 0).coerceAtLeast(0)
            } else {
                0
            }
            // The one-shot card-read flag is cleared by the dashboard before download.
            // Keep using the durable shift boundary recorded by the live service. When
            // the card has no driving from this new shift yet, F923 can still be the
            // previous shift value and must not be merged back into the new shift.
            val freshShiftBoundaryWithNoCardDriving =
                shiftBoundaryMillis != null &&
                    seed.drivingMinutes == 0 &&
                    seed.continuousDrivingCheckpointMinutes == 0
            val confirmedDailyRestBoundary =
                (ongoingRest != null && ongoingRest >= DAILY_REST_MINUTES) ||
                    freshShiftBoundaryWithNoCardDriving
            val liveContinuousAtCheckpoint = ShiftRecoveryMath.liveContinuousCheckpoint(
                confirmedDailyRestBoundary = confirmedDailyRestBoundary,
                liveActivity = liveActivityAtCheckpoint,
                liveActivityMinutes = liveActivityMinutesAtCheckpoint,
                rawContinuousDrivingMinutes = rawLiveContinuousAtCheckpoint
            )
            if (shiftBoundaryMillis != null) {
                DiagnosticReporter.record(
                    context,
                    "CARD_RECOVERY",
                    "shiftBoundaryMillis=$shiftBoundaryMillis " +
                        "cardDriving=${seed.drivingMinutes} " +
                        "cardContinuousDriving=${seed.continuousDrivingCheckpointMinutes} " +
                        "rawF923=$rawLiveContinuousAtCheckpoint " +
                        "safeF923=$liveContinuousAtCheckpoint " +
                        "freshBoundaryNoCardDriving=$freshShiftBoundaryWithNoCardDriving"
                )
            }

            val liveBreakMinutesAtCheckpoint = if (liveSnapshotFresh) {
                prefs.getInt(DriverLiveService.SNAP_BREAK_MIN, 0).coerceAtLeast(0)
            } else {
                0
            }
            val liveRestMinutesAtCheckpoint = if (liveRestingAtCheckpoint) {
                maxOf(liveActivityMinutesAtCheckpoint, liveBreakMinutesAtCheckpoint)
            } else {
                0
            }
            val recoveredSplitState = SplitDailyRestTracker.State(
                firstPartTaken = seed.splitDailyThreeHourPartTaken,
                previousResting = liveRestingAtCheckpoint,
                previousRestMinutes = liveRestMinutesAtCheckpoint,
                completedAsSplit =
                    seed.splitDailyThreeHourPartTaken &&
                        liveRestMinutesAtCheckpoint >= SplitDailyRestTracker.SECOND_PART_MINUTES
            )

            // The current card activity is intentionally OPEN and therefore absent from
            // HistoryData totals. Merge that open live segment into the authoritative card
            // checkpoint before restarting live monitoring. Persist the matching source
            // checkpoint too, so the first cycle after reconnect adds only the new delta.
            val liveOpenOtherWork = if (isOtherWork(liveActivityAtCheckpoint)) {
                liveActivityMinutesAtCheckpoint
            } else {
                0
            }
            val liveOpenAvailability = if (isAvailability(liveActivityAtCheckpoint)) {
                liveActivityMinutesAtCheckpoint
            } else {
                0
            }
            val qualifyingContinuousWorkRest =
                liveRestingAtCheckpoint && liveRestMinutesAtCheckpoint >= CONTINUOUS_BREAK_MINUTES
            val mergedContinuousWork = ShiftRecoveryMath.mergeContinuousWorkMinutes(
                cardContinuousWorkMinutes = seed.continuousWorkMinutes,
                cardContinuousDrivingMinutes = seed.continuousDrivingCheckpointMinutes,
                liveActivity = liveActivityAtCheckpoint,
                liveActivityMinutes = liveActivityMinutesAtCheckpoint,
                liveContinuousDrivingMinutes = liveContinuousAtCheckpoint,
                qualifyingRestMinutes = liveRestMinutesAtCheckpoint
            )
            val mergedContinuousOtherWork =
                if (qualifyingContinuousWorkRest) 0
                else seed.continuousOtherWorkMinutes + liveOpenOtherWork
            if (qualifyingContinuousWorkRest) {
                DiagnosticReporter.record(
                    context,
                    "CARD_RECOVERY",
                    "Continuous work kept reset after qualifying live rest=${liveRestMinutesAtCheckpoint}m"
                )
            }
            val mergedShiftOtherWork = seed.workMinutes + liveOpenOtherWork
            val mergedAvailability = seed.availabilityMinutes + liveOpenAvailability
            val liveBookkeepingDuration = when {
                isDriving(liveActivityAtCheckpoint) -> liveContinuousAtCheckpoint
                liveSnapshotFresh -> liveActivityMinutesAtCheckpoint
                else -> 0
            }

            // ongoingDailyRestMinutes() is derived from the last closed card shift. Just after
            // a new shift starts it can still report >=9h because the new current activity is
            // OPEN on the card. If live already says non-rest, the rest is a completed boundary,
            // not an ongoing rest: keep the zero card seed but merge the new live driving.
            val cardReadWhileStillResting =
                confirmedDailyRestBoundary &&
                    (!liveSnapshotFresh || isRest(liveActivityAtCheckpoint))

            val shiftCheckpoint = ShiftDrivingCounter.reconcileCheckpoint(
                cardShiftDrivingMinutes = seed.drivingMinutes,
                cardContinuousDrivingMinutes = seed.continuousDrivingCheckpointMinutes,
                liveContinuousDrivingMinutes = liveContinuousAtCheckpoint,
                dailyRestCompleted = cardReadWhileStillResting
            )

            prefs.edit()
                .putBoolean(DriverLiveService.SHIFT_INITIALIZED, shiftCheckpoint.initialized)
                .putInt(DriverLiveService.SHIFT_COMPLETED, shiftCheckpoint.totalMinutes)
                .putInt(
                    DriverLiveService.SHIFT_PREV_CONTINUOUS,
                    shiftCheckpoint.previousContinuousMinutes
                )
                .putInt(DriverLiveService.SHIFT_RESET_CANDIDATE, -1)
                .putInt(DriverLiveService.WORK_WINDOW, mergedContinuousWork)
                .putInt(DriverLiveService.CW_OTHER_WINDOW, mergedContinuousOtherWork)
                .putInt(DriverLiveService.WORK_ACC, mergedShiftOtherWork)
                .putInt(DriverLiveService.AVAIL_ACC, mergedAvailability)
                .putString(DriverLiveService.CW_PREV_ACTIVITY, liveActivityAtCheckpoint)
                .putInt(DriverLiveService.CW_PREV_DURATION, liveActivityMinutesAtCheckpoint)
                .putInt(DriverLiveService.CW_PREV_CONTINUOUS, liveContinuousAtCheckpoint)
                .putString(DriverLiveService.WORK_PREV_ACTIVITY, liveActivityAtCheckpoint)
                .putInt(DriverLiveService.WORK_PREV_DURATION, liveBookkeepingDuration)
                .putBoolean(
                    DriverLiveService.SPLIT_DAILY_3H_TAKEN,
                    recoveredSplitState.firstPartTaken
                )
                .putBoolean(
                    DriverLiveService.SPLIT_DAILY_PREV_RESTING,
                    recoveredSplitState.previousResting
                )
                .putInt(
                    DriverLiveService.SPLIT_DAILY_PREV_REST_MINUTES,
                    recoveredSplitState.previousRestMinutes
                )
                .putBoolean(
                    DriverLiveService.SPLIT_DAILY_COMPLETED_AS_SPLIT,
                    recoveredSplitState.completedAsSplit
                )
                .putString(KEY_CARD_FINGERPRINT, fingerprint)
                .putInt(KEY_RECOVERY_VERSION, RECOVERY_VERSION)
                .putString(KEY_SHIFT_ID, seed.id)
                .putLong(KEY_RECONCILED_AT, System.currentTimeMillis())
                .apply()

            // The FileObserver resumes the paused service only after this reconciliation
            // returns. DriverLiveService then reloads these preferences before reconnecting BLE,
            // so stale in-memory counters cannot overwrite the card checkpoint.
        }
    }

    private data class Seed(
        val id: String,
        val drivingMinutes: Int,
        val completedDrivingMinutes: Int,
        val liveDrivingSegmentMinutes: Int,
        val continuousDrivingCheckpointMinutes: Int,
        val continuousWorkMinutes: Int,
        val continuousOtherWorkMinutes: Int,
        val workMinutes: Int,
        val availabilityMinutes: Int,
        val splitDailyThreeHourPartTaken: Boolean
    )

    private data class DatedActivityPeriod(
        val date: String,
        val period: HistoryData.ActivityPeriod
    )

    private fun currentShiftSeed(
        model: HistoryData.Model,
        shiftBoundaryMillis: Long?,
        currentRestContinues: Boolean
    ): Seed? {
        val days = model.days
        val latest = days.lastOrNull() ?: return null

        // Build the complete current shift, including a shift that crosses midnight.
        // Stop at an explicit >=9h REST period or an inter-day gap of >=9h.
        val collected = mutableListOf<DatedActivityPeriod>()
        var dayIndex = days.lastIndex
        while (dayIndex >= 0) {
            val day = days[dayIndex]
            val periods = day.periods
            val lastDailyRestIndex = periods.indexOfLast {
                it.type == "REST" && it.minutes >= DAILY_REST_MINUTES
            }
            val part = if (lastDailyRestIndex >= 0) periods.drop(lastDailyRestIndex + 1) else periods
            if (part.isNotEmpty()) {
                collected.addAll(0, part.map { DatedActivityPeriod(day.date, it) })
            }
            if (lastDailyRestIndex >= 0 || dayIndex == 0) break

            val gap = HistoryData.actualGapMinutes(days[dayIndex - 1], day)
            if (gap == null || gap >= DAILY_REST_MINUTES) break
            dayIndex--
        }

        // A just-opened activity is encoded as OPEN and omitted by HistoryData.
        // The persisted live boundary prevents older closed periods from the previous
        // shift leaking back in before that OPEN activity appears in the card history.
        val scoped = if (shiftBoundaryMillis != null) {
            collected.filter { dated ->
                activityStartMillis(dated.date, dated.period.startTime)
                    ?.let { it >= shiftBoundaryMillis } == true
            }
        } else {
            collected
        }
        val active = scoped.map { it.period }
        val firstActiveDate = scoped.firstOrNull()?.date ?: latest.date

        val firstActive = active.firstOrNull { it.type != "REST" }
        val driving = active.filter { it.type == "DRIVING" }.sumOf { it.minutes }
        val work = active.filter { it.type == "WORK" }.sumOf { it.minutes }
        val availability = active.filter { it.type == "AVAILABILITY" }.sumOf { it.minutes }
        val closedActivities = active.map { it.type to it.minutes }
        val splitDailyThreeHourPartTaken = if (currentRestContinues) {
            // Card chunks of the current REST are not a separate earlier 3h part.
            SplitDailyRestTracker.recoverFirstPartBeforeTrailingRest(closedActivities)
        } else {
            SplitDailyRestTracker.recoverFirstPartFromClosedActivities(
                closedActivities
            )
        }

        var firstSplitPartSeen = false
        var lastQualifyingBreakIndex = -1
        active.forEachIndexed { index, period ->
            if (period.type != "REST") return@forEachIndexed
            when {
                period.minutes >= CONTINUOUS_BREAK_MINUTES -> {
                    lastQualifyingBreakIndex = index
                    firstSplitPartSeen = false
                }
                period.minutes >= 30 && firstSplitPartSeen -> {
                    lastQualifyingBreakIndex = index
                    firstSplitPartSeen = false
                }
                period.minutes >= 15 -> firstSplitPartSeen = true
            }
        }
        val workWindow = if (lastQualifyingBreakIndex >= 0) {
            active.drop(lastQualifyingBreakIndex + 1)
        } else {
            active
        }
        val continuousDrivingCheckpoint = workWindow
            .filter { it.type == "DRIVING" }
            .sumOf { it.minutes }
        val continuousWork = workWindow
            .filter { it.type == "DRIVING" || it.type == "WORK" }
            .sumOf { it.minutes }
        val continuousOtherWork = workWindow
            .filter { it.type == "WORK" }
            .sumOf { it.minutes }

        val lastDrivingIndex = active.indexOfLast { it.type == "DRIVING" }
        val lastDrivingMinutes = active.getOrNull(lastDrivingIndex)?.minutes ?: 0
        val tailAfterDriving = if (lastDrivingIndex >= 0) active.drop(lastDrivingIndex + 1) else emptyList()
        val tailRestMinutes = tailAfterDriving.filter { it.type == "REST" }.sumOf { it.minutes }
        val hasActiveNonRestAfterDriving = tailAfterDriving.any { it.type != "REST" }

        val lastSegmentStillLive =
            lastDrivingIndex >= 0 &&
                !hasActiveNonRestAfterDriving &&
                tailRestMinutes < CONTINUOUS_BREAK_MINUTES
        val liveSegment = if (lastSegmentStillLive) lastDrivingMinutes else 0
        val completed = (driving - liveSegment).coerceAtLeast(0)

        return Seed(
            id = if (shiftBoundaryMillis != null) {
                "boundary|$shiftBoundaryMillis"
            } else {
                "$firstActiveDate|${firstActive?.startTime ?: latest.startTime ?: "?"}"
            },
            drivingMinutes = driving,
            completedDrivingMinutes = completed,
            liveDrivingSegmentMinutes = liveSegment,
            continuousDrivingCheckpointMinutes = continuousDrivingCheckpoint,
            continuousWorkMinutes = continuousWork,
            continuousOtherWorkMinutes = continuousOtherWork,
            workMinutes = work,
            availabilityMinutes = availability,
            splitDailyThreeHourPartTaken = splitDailyThreeHourPartTaken
        )
    }

    private fun activityStartMillis(date: String, time: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse("$date $time")?.time
    }.getOrNull()

    private fun ongoingDailyRestMinutes(model: HistoryData.Model): Int? {
        val latest = model.days.lastOrNull() ?: return null
        val restStart = latest.endTime ?: return null
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse("${latest.date} $restStart")
        }.getOrNull() ?: return null
        val minutes = ((System.currentTimeMillis() - parsed.time) / 60000L).toInt()
        return minutes.takeIf { it >= 0 }
    }

    private fun isDriving(v: String) = v.contains("ВОЖДЕНИЕ", true)

    private fun isOtherWork(v: String) =
        v.contains("РАБОТА", true) && !isDriving(v)

    private fun isAvailability(v: String) = v.contains("ГОТОВНОСТЬ", true)

    private fun isRest(v: String) =
        v.contains("ОТДЫХ", true) || v.contains("ПЕРЕРЫВ", true)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val DAILY_REST_MINUTES = 9 * 60
        private const val CONTINUOUS_BREAK_MINUTES = 45
        private const val FIRST_READ_KEY = "first_card_read_done"
        private const val KEY_RECOVERY_VERSION = "recovery_model_version"
        private const val RECOVERY_VERSION = 9
        private const val LIVE_SNAPSHOT_MAX_AGE_MS = 30L * 60L * 1000L
        private const val MAX_SHIFT_BOUNDARY_AGE_MS = 25L * 60L * 60L * 1000L
        const val KEY_RECONCILED_AT = "recovery_reconciled_at"
        private const val KEY_CARD_FINGERPRINT = "recovery_card_fingerprint"
        private const val KEY_SHIFT_ID = "recovery_shift_id"
    }
}
