package com.pylikv.tachowatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Decodes discrete driver-card events that belong in History but must never
 * participate in driving/work/rest counter arithmetic.
 *
 * Sources:
 *  - EF Specific_Conditions (0522): OUT and Ferry/Train
 *  - EF Load_Unload_Operations (0529, Gen2 v2): load/unload entries
 *
 * Card insertion/removal is intentionally NOT inferred here. Vehicle-use and
 * daily activity records can be split at midnight, which creates false
 * 23:59/00:00 insert/remove events even when the physical card stayed inserted.
 */
object HistoryEventDecoder {
    enum class Type {
        OUT_BEGIN,
        OUT_END,
        FERRY_TRAIN,
        FERRY_TRAIN_BEGIN,
        FERRY_TRAIN_END,
        LOAD,
        UNLOAD,
        LOAD_UNLOAD
    }

    data class Event(
        val date: String,
        val time: String,
        val type: Type,
        val odometerKm: Int? = null
    )

    fun decode(result: TlvInventory.Result): List<Event> {
        val allData = try {
            result.file.readBytes()
        } catch (_: Throwable) {
            return emptyList()
        }

        val events = ArrayList<Event>()

        result.entries.forEach { entry ->
            val start = entry.offset + 5
            val end = start + entry.length
            if (start < 0 || end > allData.size) return@forEach
            val payload = allData.copyOfRange(start, end)

            when {
                entry.fid == 0x0522 && (entry.suffix == 0x00 || entry.suffix == 0x02) ->
                    events += decodeSpecificConditions(payload, entry.suffix)

                entry.fid == 0x0529 && entry.suffix == 0x02 ->
                    events += decodeLoadUnload(payload)
            }
        }

        return events
            .distinctBy { listOf(it.date, it.time, it.type.name, it.odometerKm?.toString().orEmpty()) }
            .sortedWith(compareBy<Event>({ it.date }, { it.time }, { it.type.ordinal }))
    }

    private fun decodeSpecificConditions(payload: ByteArray, suffix: Int): List<Event> {
        val start = when {
            suffix == 0x02 && payload.size >= 7 && (payload.size - 2) % 5 == 0 -> 2
            suffix == 0x00 && payload.size >= 5 && payload.size % 5 == 0 -> 0
            else -> return emptyList()
        }

        val out = ArrayList<Event>()
        var p = start
        while (p + 4 < payload.size) {
            val timestamp = be32(payload, p)
            val code = u(payload[p + 4])
            if (validTime(timestamp)) {
                val type = when {
                    code == 0x01 -> Type.OUT_BEGIN
                    code == 0x02 -> Type.OUT_END
                    suffix == 0x00 && code == 0x03 -> Type.FERRY_TRAIN
                    suffix == 0x02 && code == 0x03 -> Type.FERRY_TRAIN_BEGIN
                    suffix == 0x02 && code == 0x04 -> Type.FERRY_TRAIN_END
                    else -> null
                }
                if (type != null) {
                    out += Event(
                        date = formatDate(timestamp),
                        time = formatClockSeconds(timestamp),
                        type = type
                    )
                }
            }
            p += 5
        }
        return out
    }

    private fun decodeLoadUnload(payload: ByteArray): List<Event> {
        if (payload.size < 22 || (payload.size - 2) % 20 != 0) return emptyList()

        val out = ArrayList<Event>()
        val count = (payload.size - 2) / 20
        for (i in 0 until count) {
            val p = 2 + i * 20
            val timestamp = be32(payload, p)
            if (!validTime(timestamp)) continue
            val type = when (u(payload[p + 4])) {
                0x01 -> Type.LOAD
                0x02 -> Type.UNLOAD
                0x03 -> Type.LOAD_UNLOAD
                else -> null
            } ?: continue

            out += Event(
                date = formatDate(timestamp),
                time = formatClockSeconds(timestamp),
                type = type,
                odometerKm = be24u(payload, p + 17)
            )
        }
        return out
    }

    private fun validTime(seconds: Long): Boolean = seconds in 946684800L..4133980799L

    private fun formatDate(seconds: Long): String =
        dateFormat("yyyy-MM-dd").format(Date(seconds * 1000L))

    private fun formatClockSeconds(seconds: Long): String =
        dateFormat("HH:mm").format(Date(seconds * 1000L))

    private fun dateFormat(pattern: String) =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun be24u(data: ByteArray, p: Int): Int =
        if (p < 0 || p + 2 >= data.size) 0
        else (u(data[p]) shl 16) or (u(data[p + 1]) shl 8) or u(data[p + 2])

    private fun be32(data: ByteArray, p: Int): Long =
        if (p < 0 || p + 3 >= data.size) 0L
        else (u(data[p]).toLong() shl 24) or
            (u(data[p + 1]).toLong() shl 16) or
            (u(data[p + 2]).toLong() shl 8) or
            u(data[p + 3]).toLong()

    private fun u(value: Byte): Int = value.toInt() and 0xFF
}
