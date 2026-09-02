package com.pylikv.tachowatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object PlacesDecoder {

    private data class Place(
        val index: Int,
        val entryTime: Long,
        val entryType: Int,
        val country: Int,
        val region: Int,
        val odometerKm: Int,
        val gnssTime: Long? = null,
        val gnssAccuracy: Int? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val rawLat: Int? = null,
        val rawLon: Int? = null
    )

    fun render(result: TlvInventory.Result): String {
        val sb = StringBuilder()
        sb.appendLine("===== TEST-14 PLACES DECODER =====")
        sb.appendLine("Source=${result.file.name}")
        sb.appendLine("Purpose=decode FID 0506 CardPlaceDailyWorkPeriod")

        val allData = try {
            result.file.readBytes()
        } catch (e: Throwable) {
            sb.appendLine("ERROR=Cannot read DDD: ${e.message}")
            sb.appendLine("===== END TEST-14 =====")
            return sb.toString()
        }

        val entries = result.entries.filter { it.fid == 0x0506 && (it.suffix == 0x00 || it.suffix == 0x02) }
        if (entries.isEmpty()) {
            sb.appendLine("ERROR=FID 0506 suffix 00/02 not found")
            sb.appendLine("===== END TEST-14 =====")
            return sb.toString()
        }

        entries.forEach { entry ->
            sb.appendLine("--------------------------------")
            sb.appendLine("FID=0506 suffix=${hex2(entry.suffix)} TLVoffset=${entry.offset} dataLen=${entry.length}")

            val start = entry.offset + 5
            val end = start + entry.length
            if (start < 0 || end > allData.size) {
                sb.appendLine("STATUS=INVALID_TLV_RANGE")
                return@forEach
            }
            val payload = allData.copyOfRange(start, end)

            when (entry.suffix) {
                0x00 -> decodeG1(payload, sb)
                0x02 -> decodeG2(payload, sb)
            }
        }

        sb.appendLine("--------------------------------")
        sb.appendLine("EntryType: 0=BEGIN_INSERT/ENTRY, 1=END_WITHDRAW/ENTRY, 2=BEGIN_MANUAL, 3=END_MANUAL, 4=BEGIN_ASSUMED_BY_VU, 5=END_ASSUMED_BY_VU")
        sb.appendLine("Country uses tachograph NationNumeric; alpha code is shown when known.")
        sb.appendLine("G2 coordinates are decoded from signed 24-bit DDMM.M×10 format; 7FFFFF means unknown.")
        sb.appendLine("===== END TEST-14 =====")
        return sb.toString()
    }

    private fun decodeG1(payload: ByteArray, sb: StringBuilder) {
        if (payload.size < 11 || (payload.size - 1) % 10 != 0) {
            sb.appendLine("STATUS=UNEXPECTED_G1_SIZE expected=1+N*10 actual=${payload.size}")
            sb.appendLine("HeaderHex=${hexDump(payload, 0, minOf(48, payload.size))}")
            return
        }
        val newest = u(payload[0])
        val count = (payload.size - 1) / 10
        sb.appendLine("Generation=G1")
        sb.appendLine("NewestPointer=$newest")
        sb.appendLine("RecordCount=$count RecordSize=10 HeaderBytes=1")

        val places = ArrayList<Place>()
        for (i in 0 until count) {
            val p = 1 + i * 10
            val time = be32(payload, p)
            if (!validTime(time)) continue
            places += Place(
                index = i,
                entryTime = time,
                entryType = u(payload[p + 4]),
                country = u(payload[p + 5]),
                region = u(payload[p + 6]),
                odometerKm = be24u(payload, p + 7)
            )
        }
        renderPlaces(places, newest, count, sb, false)
    }

    private fun decodeG2(payload: ByteArray, sb: StringBuilder) {
        if (payload.size < 23 || (payload.size - 2) % 21 != 0) {
            sb.appendLine("STATUS=UNEXPECTED_G2_SIZE expected=2+N*21 actual=${payload.size}")
            sb.appendLine("HeaderHex=${hexDump(payload, 0, minOf(64, payload.size))}")
            return
        }
        val newest = be16(payload, 0)
        val count = (payload.size - 2) / 21
        sb.appendLine("Generation=G2")
        sb.appendLine("NewestPointer=$newest")
        sb.appendLine("RecordCount=$count RecordSize=21 HeaderBytes=2")

        val places = ArrayList<Place>()
        for (i in 0 until count) {
            val p = 2 + i * 21
            val time = be32(payload, p)
            if (!validTime(time)) continue
            val gnssTime = be32(payload, p + 10)
            val accuracy = u(payload[p + 14])
            val rawLatUnsigned = be24u(payload, p + 15)
            val rawLonUnsigned = be24u(payload, p + 18)
            val rawLat = signed24(rawLatUnsigned)
            val rawLon = signed24(rawLonUnsigned)
            val unknownLat = rawLatUnsigned == 0x7FFFFF
            val unknownLon = rawLonUnsigned == 0x7FFFFF

            places += Place(
                index = i,
                entryTime = time,
                entryType = u(payload[p + 4]),
                country = u(payload[p + 5]),
                region = u(payload[p + 6]),
                odometerKm = be24u(payload, p + 7),
                gnssTime = if (validTime(gnssTime)) gnssTime else null,
                gnssAccuracy = accuracy,
                latitude = if (unknownLat) null else coordToDegrees(rawLat),
                longitude = if (unknownLon) null else coordToDegrees(rawLon),
                rawLat = rawLatUnsigned,
                rawLon = rawLonUnsigned
            )
        }
        renderPlaces(places, newest, count, sb, true)
    }

    private fun renderPlaces(places: List<Place>, newest: Int, capacity: Int, sb: StringBuilder, g2: Boolean) {
        if (newest !in 0 until capacity) {
            sb.appendLine("STATUS=POINTER_OUT_OF_RANGE")
        }
        sb.appendLine("ValidRecords=${places.size}")
        if (places.isEmpty()) {
            sb.appendLine("STATUS=NO_VALID_RECORDS")
            return
        }

        val byIndex = places.associateBy { it.index }
        val chronological = ArrayList<Place>()
        if (newest in 0 until capacity) {
            for (step in 1..capacity) {
                val idx = (newest + step) % capacity
                byIndex[idx]?.let { chronological += it }
            }
        } else {
            chronological += places.sortedBy { it.entryTime }
        }

        val show = chronological.takeLast(30)
        sb.appendLine("ShowingNewest=${show.size}")
        show.forEach { place ->
            val alpha = nationAlpha(place.country)
            sb.append(
                "#${place.index} time=${formatDateTime(place.entryTime)} " +
                    "type=${entryTypeName(place.entryType)}(${place.entryType}) " +
                    "country=${alpha}[${hex2(place.country)}] region=${hex2(place.region)} " +
                    "odometerKm=${place.odometerKm}"
            )
            if (g2) {
                sb.append(" gnssTime=${place.gnssTime?.let { formatDateTime(it) } ?: "UNKNOWN"}")
                sb.append(" accuracy=${place.gnssAccuracy}")
                if (place.latitude != null && place.longitude != null) {
                    sb.append(String.format(Locale.US, " lat=%.6f lon=%.6f", place.latitude, place.longitude))
                } else {
                    sb.append(" lat/lon=UNKNOWN")
                }
                sb.append(" rawCoord=${place.rawLat?.let { hex6(it) }}/${place.rawLon?.let { hex6(it) }}")
            }
            sb.appendLine()
        }
        sb.appendLine("STATUS=OK")
    }

    private fun entryTypeName(v: Int): String = when (v) {
        0 -> "BEGIN_INSERT_OR_ENTRY"
        1 -> "END_WITHDRAW_OR_ENTRY"
        2 -> "BEGIN_MANUAL"
        3 -> "END_MANUAL"
        4 -> "BEGIN_ASSUMED_BY_VU"
        5 -> "END_ASSUMED_BY_VU"
        else -> "UNKNOWN"
    }

    private fun nationAlpha(v: Int): String = when (v) {
        0x00 -> "--"
        0x01 -> "A"
        0x02 -> "AL"
        0x03 -> "AND"
        0x04 -> "ARM"
        0x05 -> "AZ"
        0x06 -> "B"
        0x07 -> "BG"
        0x08 -> "BIH"
        0x09 -> "BY"
        0x0A -> "CH"
        0x0B -> "CY"
        0x0C -> "CZ"
        0x0D -> "D"
        0x0E -> "DK"
        0x0F -> "E"
        0x10 -> "EST"
        0x11 -> "F"
        0x12 -> "FIN"
        0x13 -> "FL"
        0x14 -> "FR"
        0x15 -> "UK"
        0x16 -> "GE"
        0x17 -> "GR"
        0x18 -> "H"
        0x19 -> "HR"
        0x1A -> "I"
        0x1B -> "IRL"
        0x1C -> "IS"
        0x1D -> "KZ"
        0x1E -> "L"
        0x1F -> "LT"
        0x20 -> "LV"
        0x21 -> "M"
        0x22 -> "MC"
        0x23 -> "MD"
        0x24 -> "MK"
        0x25 -> "N"
        0x26 -> "NL"
        0x27 -> "P"
        0x28 -> "PL"
        0x29 -> "RO"
        0x2A -> "RSM"
        0x2B -> "RUS"
        0x2C -> "S"
        0x2D -> "SK"
        0x2E -> "SLO"
        0x2F -> "TM"
        0x30 -> "TR"
        0x31 -> "UA"
        0x32 -> "V"
        0x33 -> "YU"
        0x34 -> "SRB"
        0xFD -> "EC"
        0xFE -> "EUR"
        0xFF -> "WLD"
        else -> "UNK"
    }

    private fun coordToDegrees(raw: Int): Double {
        val sign = if (raw < 0) -1.0 else 1.0
        val a = kotlin.math.abs(raw)
        val degrees = a / 1000
        val minutesTenths = a % 1000
        val minutes = minutesTenths / 10.0
        return sign * (degrees + minutes / 60.0)
    }

    private fun signed24(v: Int): Int = if ((v and 0x800000) != 0) v or -0x1000000 else v

    private fun validTime(seconds: Long): Boolean = seconds in 946684800L..4133980799L

    private fun formatDateTime(seconds: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return try { sdf.format(Date(seconds * 1000L)) } catch (_: Throwable) { "INVALID($seconds)" }
    }

    private fun be16(data: ByteArray, p: Int): Int = (u(data[p]) shl 8) or u(data[p + 1])

    private fun be24u(data: ByteArray, p: Int): Int =
        (u(data[p]) shl 16) or (u(data[p + 1]) shl 8) or u(data[p + 2])

    private fun be32(data: ByteArray, p: Int): Long =
        (u(data[p]).toLong() shl 24) or
            (u(data[p + 1]).toLong() shl 16) or
            (u(data[p + 2]).toLong() shl 8) or
            u(data[p + 3]).toLong()

    private fun hexDump(data: ByteArray, start: Int, length: Int): String {
        val end = minOf(data.size, start + length)
        return (start until end).joinToString(" ") { hex2(u(data[it])) }
    }

    private fun u(b: Byte) = b.toInt() and 0xFF
    private fun hex2(v: Int) = String.format(Locale.US, "%02X", v and 0xFF)
    private fun hex6(v: Int) = String.format(Locale.US, "%06X", v and 0xFFFFFF)
}
