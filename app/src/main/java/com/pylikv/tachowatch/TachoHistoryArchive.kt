package com.pylikv.tachowatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent append/update archive of card history. Re-reading the same card data is idempotent. */
object TachoHistoryArchive {
    private const val PREFS = "tacho_history_archive_v1"
    private const val KEY = "days_json"

    data class MergeResult(val total: Int, val added: Int, val updated: Int)

    fun merge(context: Context, model: HistoryData.Model): MergeResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = load(prefs.getString(KEY, null))
        var added = 0
        var updated = 0
        model.days.forEach { day ->
            val key = dayKey(day)
            val encoded = encode(day)
            val old = root.optJSONObject(key)
            if (old == null) {
                root.put(key, encoded); added++
            } else if (old.toString() != encoded.toString()) {
                root.put(key, encoded); updated++
            }
        }
        prefs.edit().putString(KEY, root.toString()).apply()
        return MergeResult(root.length(), added, updated)
    }

    fun count(context: Context): Int = load(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    ).length()

    private fun dayKey(d: HistoryData.Day): String = listOf(
        d.date, d.startTime ?: "?", d.startCountry ?: "?"
    ).joinToString("|")

    private fun encode(d: HistoryData.Day) = JSONObject().apply {
        put("date", d.date); put("startTime", d.startTime); put("startCountry", d.startCountry)
        put("endTime", d.endTime); put("endCountry", d.endCountry)
        put("driving", d.drivingMinutes); put("work", d.workMinutes); put("availability", d.availabilityMinutes)
        put("periods", JSONArray().apply { d.periods.forEach { p -> put(JSONObject().apply {
            put("startTime", p.startTime); put("type", p.type); put("minutes", p.minutes)
        }) } })
    }

    private fun load(raw: String?): JSONObject = try {
        if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Exception) { JSONObject() }
}
