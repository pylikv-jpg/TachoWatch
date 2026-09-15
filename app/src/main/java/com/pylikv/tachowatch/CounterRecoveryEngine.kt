package com.pylikv.tachowatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, timestamped recovery journal for derived driver counters.
 *
 * Rules:
 *  - DTCO live values are facts, never extrapolated while disconnected.
 *  - Card history is an authoritative historical checkpoint.
 *  - Persisted derived totals are cache only; they are rebuilt/reconciled from facts.
 *  - A pre-disconnect checkpoint is retained until at least one complete post-reconnect
 *    live cycle has been compared with it.
 */
class CounterRecoveryEngine(context: Context) {
    data class Point(
        val at: Long,
        val kind: String,
        val activity: String,
        val activityMin: Int,
        val continuousMin: Int,
        val breakMin: Int,
        val shiftDrivingMin: Int,
        val workWindowMin: Int,
        val connected: Boolean
    )

    data class ReconnectDecision(
        val sameContinuousDrivingSegment: Boolean,
        val qualifyingDrivingBreakObserved: Boolean,
        val dailyRestObserved: Boolean,
        val gapMinutes: Int
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun recordLive(
        activity: String,
        activityMin: Int,
        continuousMin: Int,
        breakMin: Int,
        shiftDrivingMin: Int,
        workWindowMin: Int,
        at: Long = System.currentTimeMillis()
    ) {
        append(Point(at, "LIVE", activity, activityMin, continuousMin, breakMin, shiftDrivingMin, workWindowMin, true))
        prefs.edit().putLong(KEY_LAST_LIVE_AT, at).apply()
    }

    @Synchronized
    fun recordDisconnect(
        activity: String,
        activityMin: Int,
        continuousMin: Int,
        breakMin: Int,
        shiftDrivingMin: Int,
        workWindowMin: Int,
        at: Long = System.currentTimeMillis()
    ) {
        val p = Point(at, "DISCONNECT", activity, activityMin, continuousMin, breakMin, shiftDrivingMin, workWindowMin, false)
        append(p)
        prefs.edit()
            .putString(KEY_PENDING_DISCONNECT, encode(p).toString())
            .putLong(KEY_DISCONNECTED_AT, at)
            .apply()
    }

    /**
     * Compare the first complete live cycle after reconnect with the last point before loss.
     * No phone-clock extrapolation is performed. A gap only proves elapsed wall time; DTCO
     * counters/activity prove what may safely be inferred from that gap.
     */
    @Synchronized
    fun reconcileFirstLiveAfterReconnect(now: Point): ReconnectDecision? {
        val old = prefs.getString(KEY_PENDING_DISCONNECT, null)?.let(::decode) ?: return null
        val gap = ((now.at - old.at).coerceAtLeast(0L) / 60_000L).toInt()

        val nowRest = isRest(now.activity)
        val qualifyingBreak = nowRest && maxOf(now.activityMin, now.breakMin) >= 45
        val dailyRest = nowRest && maxOf(now.activityMin, now.breakMin) >= 9 * 60
        val sameSegment = !qualifyingBreak &&
            isDriving(old.activity) && isDriving(now.activity) &&
            now.continuousMin >= old.continuousMin

        append(now.copy(kind = "RECONNECT"))
        prefs.edit().remove(KEY_PENDING_DISCONNECT).apply()
        return ReconnectDecision(sameSegment, qualifyingBreak, dailyRest, gap)
    }

    /** Card history confirms the past. It becomes a new base checkpoint, never a delta. */
    @Synchronized
    fun recordCardVerified(
        activity: String,
        continuousMin: Int,
        shiftDrivingMin: Int,
        workWindowMin: Int,
        at: Long = System.currentTimeMillis()
    ) {
        append(Point(at, "CARD_VERIFIED", activity, 0, continuousMin, 0, shiftDrivingMin, workWindowMin, true))
        prefs.edit()
            .putLong(KEY_CARD_VERIFIED_AT, at)
            .putInt(KEY_CARD_SHIFT_BASE, shiftDrivingMin.coerceAtLeast(0))
            .putInt(KEY_CARD_WORK_BASE, workWindowMin.coerceAtLeast(0))
            .apply()
    }

    fun lastPoints(limit: Int = 40): List<Point> = load().takeLast(limit.coerceIn(1, MAX_POINTS))

    private fun append(point: Point) {
        val list = load().toMutableList()
        val previous = list.lastOrNull()
        // Avoid writing identical snapshots repeatedly. Time remains recorded whenever a
        // relevant DTCO fact or connection state changes.
        if (previous != null && previous.kind == point.kind && previous.activity == point.activity &&
            previous.activityMin == point.activityMin && previous.continuousMin == point.continuousMin &&
            previous.breakMin == point.breakMin && previous.shiftDrivingMin == point.shiftDrivingMin &&
            previous.workWindowMin == point.workWindowMin && previous.connected == point.connected) return
        list += point
        while (list.size > MAX_POINTS) list.removeAt(0)
        val array = JSONArray()
        list.forEach { array.put(encode(it)) }
        prefs.edit().putString(KEY_JOURNAL, array.toString()).apply()
    }

    private fun load(): List<Point> = runCatching {
        val raw = prefs.getString(KEY_JOURNAL, null) ?: return emptyList()
        val a = JSONArray(raw)
        buildList {
            for (i in 0 until a.length()) decode(a.getJSONObject(i))?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun encode(p: Point) = JSONObject()
        .put("at", p.at).put("kind", p.kind).put("activity", p.activity)
        .put("activityMin", p.activityMin).put("continuousMin", p.continuousMin)
        .put("breakMin", p.breakMin).put("shiftDrivingMin", p.shiftDrivingMin)
        .put("workWindowMin", p.workWindowMin).put("connected", p.connected)

    private fun decode(raw: String): Point? = runCatching { decode(JSONObject(raw)) }.getOrNull()
    private fun decode(o: JSONObject): Point? = runCatching {
        Point(
            o.getLong("at"), o.getString("kind"), o.optString("activity", "—"),
            o.optInt("activityMin"), o.optInt("continuousMin"), o.optInt("breakMin"),
            o.optInt("shiftDrivingMin"), o.optInt("workWindowMin"), o.optBoolean("connected")
        )
    }.getOrNull()

    private fun isDriving(v: String) = v.contains("DRIV", true) || v.contains("вожд", true)
    private fun isRest(v: String) = v.contains("REST", true) || v.contains("PAUSE", true) || v.contains("отдых", true) || v.contains("кроват", true)

    companion object {
        private const val PREFS = "tachowatch_auto_card"
        private const val KEY_JOURNAL = "counter_recovery_journal_v2"
        private const val KEY_PENDING_DISCONNECT = "counter_pending_disconnect_v2"
        private const val KEY_DISCONNECTED_AT = "counter_disconnected_at_v2"
        private const val KEY_LAST_LIVE_AT = "counter_last_live_at_v2"
        private const val KEY_CARD_VERIFIED_AT = "counter_card_verified_at_v2"
        private const val KEY_CARD_SHIFT_BASE = "counter_card_shift_base_v2"
        private const val KEY_CARD_WORK_BASE = "counter_card_work_base_v2"
        private const val MAX_POINTS = 720
    }
}
