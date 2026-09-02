package com.pylikv.tachowatch

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TlvInventory {
    data class Entry(
        val index: Int,
        val offset: Int,
        val fid: Int,
        val suffix: Int,
        val length: Int
    )

    data class Result(
        val file: File,
        val bytes: Int,
        val entries: List<Entry>,
        val parsedBytes: Int,
        val error: String? = null
    )

    private data class ActivityChange(
        val raw: Int,
        val minute: Int,
        val activity: Int,
        val slot: Int,
        val crew: Boolean,
        val cardInserted: Boolean
    )

    private data class DailyRecord(
        val offset: Int,
        val previousLength: Int,
        val recordLength: Int,
        val dateSeconds: Long,
        val presenceCounter: Int,
        val distanceKm: Int,
        val changes: List<ActivityChange>
    )

    fun findLatestDdd(dir: File?): File? {
        if (dir == null || !dir.exists()) return null
        return dir.listFiles()
            ?.filter { it.isFile && it.name.lowercase(Locale.US).endsWith(".ddd") }
            ?.maxByOrNull { it.lastModified() }
    }

    fun parse(file: File): Result {
        val data = try {
            file.readBytes()
        } catch (e: Throwable) {
            return Result(file, 0, emptyList(), 0, "Не удалось прочитать файл: ${e.message}")
        }

        val out = ArrayList<Entry>()
        var p = 0
        var index = 1
        while (p + 5 <= data.size) {
            val fid = (u(data[p]) shl 8) or u(data[p + 1])
            val suffix = u(data[p + 2])
            val len = (u(data[p + 3]) shl 8) or u(data[p + 4])
            val end = p + 5 + len
            if (end > data.size) {
                return Result(
                    file, data.size, out, p,
                    "Неполная TLV-запись #$index: offset=$p FID=${hex4(fid)} suffix=${hex2(suffix)} len=$len, осталось=${data.size - p}"
                )
            }
            out += Entry(index, p, fid, suffix, len)
            p = end
            index++
        }

        val err = if (p != data.size) "После TLV осталось ${data.size - p} байт" else null
        return Result(file, data.size, out, p, err)
    }

    fun render(result: Result): String {
        val sb = StringBuilder()
        sb.appendLine("===== TEST-12 TLV INVENTORY =====")
        sb.appendLine("File=${result.file.name}")
        sb.appendLine("Bytes=${result.bytes}")
        sb.appendLine("TLVRecords=${result.entries.size}")
        sb.appendLine("ParsedBytes=${result.parsedBytes}")
        sb.appendLine("ParseStatus=${if (result.error == null && result.parsedBytes == result.bytes) "OK" else "CHECK"}")
        if (result.error != null) sb.appendLine("ParseError=${result.error}")
        sb.appendLine("--------------------------------")
        result.entries.forEach { e ->
            sb.appendLine(
                String.format(
                    Locale.US,
                    "#%02d offset=%06d FID=%04X suffix=%02X len=%d",
                    e.index, e.offset, e.fid, e.suffix, e.length
                )
            )
        }
        sb.appendLine("--------------------------------")
        val grouped = result.entries.groupBy { Pair(it.fid, it.suffix) }
        sb.appendLine("UniqueTags=${grouped.size}")
        grouped.toSortedMap(compareBy<Pair<Int, Int>>({ it.first }, { it.second })).forEach { (k, list) ->
            val total = list.sumOf { it.length }
            sb.appendLine(
                String.format(
                    Locale.US,
                    "TAG FID=%04X suffix=%02X count=%d dataBytes=%d",
                    k.first, k.second, list.size, total
                )
            )
        }
        sb.appendLine("===== END TEST-12 INVENTORY =====")
        sb.appendLine()
        sb.append(renderDriverActivity(result))
        return sb.toString()
    }

    private fun renderDriverActivity(result: Result): String {
        val sb = StringBuilder()
        sb.appendLine("===== TEST-13 CARD ACTIVITY DECODER =====")
        sb.appendLine("Source=${result.file.name}")

        val allData = try {
            result.file.readBytes()
        } catch (e: Throwable) {
            sb.appendLine("ERROR=Cannot read DDD: ${e.message}")
            sb.appendLine("===== END TEST-13 =====")
            return sb.toString()
        }

        val activityEntries = result.entries.filter { it.fid == 0x0504 && (it.suffix == 0x00 || it.suffix == 0x02) }
        if (activityEntries.isEmpty()) {
            sb.appendLine("ERROR=FID 0504 suffix 00/02 not found")
            sb.appendLine("===== END TEST-13 =====")
            return sb.toString()
        }

        activityEntries.forEach { entry ->
            sb.appendLine("--------------------------------")
            sb.appendLine("FID=0504 suffix=${hex2(entry.suffix)} TLVoffset=${entry.offset} dataLen=${entry.length}")

            val dataStart = entry.offset + 5
            val dataEnd = dataStart + entry.length
            if (dataStart < 0 || dataEnd > allData.size || entry.length < 16) {
                sb.appendLine("STATUS=INVALID_TLV_RANGE")
                return@forEach
            }

            val payload = allData.copyOfRange(dataStart, dataEnd)
            val oldest = be16(payload, 0)
            val newest = be16(payload, 2)
            val buffer = payload.copyOfRange(4, payload.size)

            sb.appendLine("OldestPointer=$oldest")
            sb.appendLine("NewestPointer=$newest")
            sb.appendLine("ActivityBufferBytes=${buffer.size}")
            sb.appendLine("HeaderHex=${hexDump(payload, 0, minOf(payload.size, 32))}")

            if (oldest !in buffer.indices || newest !in buffer.indices) {
                sb.appendLine("STATUS=POINTER_OUT_OF_RANGE")
                return@forEach
            }

            val records = decodeCircularRecords(buffer, oldest, newest)
            if (records.isEmpty()) {
                sb.appendLine("STATUS=NO_VALID_DAILY_RECORDS")
                return@forEach
            }

            sb.appendLine("DecodedDailyRecords=${records.size}")
            sb.appendLine("ShowingNewest=${minOf(14, records.size)}")
            sb.appendLine()

            records.takeLast(14).forEachIndexed { idx, record ->
                val absoluteNo = records.size - minOf(14, records.size) + idx + 1
                sb.appendLine(
                    "DAY#$absoluteNo offset=${record.offset} date=${formatDate(record.dateSeconds)} " +
                        "prevLen=${record.previousLength} len=${record.recordLength} " +
                        "presence=${record.presenceCounter} distanceKm=${record.distanceKm} changes=${record.changes.size}"
                )

                if (record.changes.isEmpty()) {
                    sb.appendLine("  (no activity changes)")
                } else {
                    record.changes.forEachIndexed { changeIndex, change ->
                        val nextMinute = record.changes.getOrNull(changeIndex + 1)?.minute
                        val durationText = if (nextMinute != null && nextMinute >= change.minute) {
                            " duration=${formatMinutes(nextMinute - change.minute)}"
                        } else {
                            " duration=OPEN"
                        }
                        sb.appendLine(
                            "  ${formatClock(change.minute)} ${activityName(change.activity)}" +
                                " slot=${change.slot} ${if (change.crew) "CREW" else "SINGLE"}" +
                                " card=${if (change.cardInserted) "IN" else "OUT"}" +
                                " raw=${hex4(change.raw)}$durationText"
                        )
                    }
                }
                sb.appendLine()
            }
            sb.appendLine("STATUS=OK")
        }

        sb.appendLine("Legend: REST=отдых/перерыв, AVAILABILITY=готовность, WORK=другая работа, DRIVING=вождение")
        sb.appendLine("NOTE=duration for the last change of each day is OPEN intentionally; TEST-13 does not invent an end time.")
        sb.appendLine("===== END TEST-13 =====")
        return sb.toString()
    }

    private fun decodeCircularRecords(buffer: ByteArray, oldest: Int, newest: Int): List<DailyRecord> {
        val out = ArrayList<DailyRecord>()
        var offset = oldest
        val visited = HashSet<Int>()

        repeat(500) {
            if (offset !in buffer.indices || !visited.add(offset)) return out

            val header = readCyclic(buffer, offset, 12) ?: return out
            val previousLength = be16(header, 0)
            val recordLength = be16(header, 2)

            if (recordLength < 12 || recordLength > buffer.size || recordLength % 2 != 0) return out

            val rawRecord = readCyclic(buffer, offset, recordLength) ?: return out
            val dateSeconds = be32(rawRecord, 4)
            val presenceCounter = be16(rawRecord, 8)
            val distanceKm = be16(rawRecord, 10)

            // TimeReal=0 marks unused/erased space, not a real daily record.
            if (dateSeconds == 0L) return out

            val changes = ArrayList<ActivityChange>()
            var p = 12
            var lastMinute = -1
            while (p + 1 < rawRecord.size) {
                val raw = be16(rawRecord, p)
                val minute = raw and 0x07FF
                val activity = (raw ushr 11) and 0x03
                val cardOut = ((raw ushr 13) and 0x01) != 0
                val crew = ((raw ushr 14) and 0x01) != 0
                val slot = if (((raw ushr 15) and 0x01) == 0) 1 else 2

                if (minute !in 0..1439) break
                // Within one daily record changes are chronological. A backwards minute
                // usually means we have hit padding/corrupt bytes and should stop safely.
                if (lastMinute > minute) break

                changes += ActivityChange(
                    raw = raw,
                    minute = minute,
                    activity = activity,
                    slot = slot,
                    crew = crew,
                    cardInserted = !cardOut
                )
                lastMinute = minute
                p += 2
            }

            out += DailyRecord(
                offset = offset,
                previousLength = previousLength,
                recordLength = recordLength,
                dateSeconds = dateSeconds,
                presenceCounter = presenceCounter,
                distanceKm = distanceKm,
                changes = changes
            )

            if (offset == newest) return out
            offset = (offset + recordLength) % buffer.size
        }

        return out
    }

    private fun readCyclic(buffer: ByteArray, offset: Int, length: Int): ByteArray? {
        if (buffer.isEmpty() || offset !in buffer.indices || length < 0 || length > buffer.size) return null
        val out = ByteArray(length)
        for (i in 0 until length) {
            out[i] = buffer[(offset + i) % buffer.size]
        }
        return out
    }

    private fun formatDate(seconds: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return try {
            sdf.format(Date(seconds * 1000L))
        } catch (_: Throwable) {
            "INVALID($seconds)"
        }
    }

    private fun formatClock(minute: Int): String = String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)

    private fun formatMinutes(minutes: Int): String = String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)

    private fun activityName(activity: Int): String = when (activity) {
        0 -> "REST"
        1 -> "AVAILABILITY"
        2 -> "WORK"
        3 -> "DRIVING"
        else -> "UNKNOWN"
    }

    private fun hexDump(data: ByteArray, start: Int, length: Int): String {
        if (start !in 0..data.size || length <= 0) return ""
        val end = minOf(data.size, start + length)
        return (start until end).joinToString(" ") { hex2(u(data[it])) }
    }

    private fun be16(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= data.size) return 0
        return (u(data[offset]) shl 8) or u(data[offset + 1])
    }

    private fun be32(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 3 >= data.size) return 0L
        return (u(data[offset]).toLong() shl 24) or
            (u(data[offset + 1]).toLong() shl 16) or
            (u(data[offset + 2]).toLong() shl 8) or
            u(data[offset + 3]).toLong()
    }

    private fun u(b: Byte) = b.toInt() and 0xFF
    private fun hex2(v: Int) = String.format(Locale.US, "%02X", v and 0xFF)
    private fun hex4(v: Int) = String.format(Locale.US, "%04X", v and 0xFFFF)
}
