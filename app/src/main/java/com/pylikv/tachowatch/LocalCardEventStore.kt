package com.pylikv.tachowatch

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object LocalCardEventStore {
    enum class Type { REMOVED, INSERTED }
    data class Event(val timestamp: Long, val type: Type) {
        val date: String get() = format(timestamp, "yyyy-MM-dd")
        val time: String get() = format(timestamp, "HH:mm")
    }

    private const val PREFS = "tachowatch_local_card_events"
    private const val KEY = "events_v1"
    private const val MAX_EVENTS = 200

    @Synchronized
    fun record(context: Context, type: Type, timestamp: Long = System.currentTimeMillis()) {
        val events = read(context).toMutableList()
        val last = events.lastOrNull()
        if (last != null && last.type == type && timestamp - last.timestamp < 120_000L) return
        events += Event(timestamp, type)
        val payload = events.takeLast(MAX_EVENTS).joinToString("\n") { "${it.timestamp}|${it.type.name}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, payload).commit()
    }

    fun read(context: Context): List<Event> {
        val payload = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return payload.lineSequence().mapNotNull { line ->
            val p = line.split('|', limit = 2)
            val ts = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val type = runCatching { Type.valueOf(p.getOrNull(1).orEmpty()) }.getOrNull() ?: return@mapNotNull null
            Event(ts, type)
        }.sortedBy { it.timestamp }.toList()
    }

    private fun format(timestamp: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timestamp))
}
