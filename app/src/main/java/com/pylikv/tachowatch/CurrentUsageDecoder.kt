package com.pylikv.tachowatch

import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CurrentUsageDecoder {

    fun render(result: TlvInventory.Result): String {
        val sb = StringBuilder()
        sb.appendLine("===== TEST-15 CURRENT USAGE DECODER =====")
        sb.appendLine("Source=${result.file.name}")
        sb.appendLine("Purpose=decode FID 0507 CardCurrentUse")

        val allData = try {
            result.file.readBytes()
        } catch (e: Throwable) {
            sb.appendLine("ERROR=Cannot read DDD: ${e.message}")
            sb.appendLine("===== END TEST-15 =====")
            return sb.toString()
        }

        val entries = result.entries.filter { it.fid == 0x0507 && (it.suffix == 0x00 || it.suffix == 0x02) }
        if (entries.isEmpty()) {
            sb.appendLine("ERROR=FID 0507 suffix 00/02 not found")
            sb.appendLine("===== END TEST-15 =====")
            return sb.toString()
        }

        entries.forEach { entry ->
            sb.appendLine("--------------------------------")
            sb.appendLine("FID=0507 suffix=${hex2(entry.suffix)} TLVoffset=${entry.offset} dataLen=${entry.length}")

            val start = entry.offset + 5
            val end = start + entry.length
            if (start < 0 || end > allData.size || entry.length < 19) {
                sb.appendLine("STATUS=INVALID_TLV_RANGE_OR_LENGTH")
                return@forEach
            }

            val p = allData.copyOfRange(start, end)
            val sessionOpenSeconds = be32(p, 0)
            val nation = u(p[4])
            val codePage = u(p[5])
            val vrnRaw = p.copyOfRange(6, 19)
            val vrn = decodeVrn(vrnRaw, codePage)

            sb.appendLine("Generation=${if (entry.suffix == 0x00) "G1" else "G2"}")
            sb.appendLine("SessionOpenTimeRaw=$sessionOpenSeconds")
            if (sessionOpenSeconds == 0L) {
                sb.appendLine("SessionOpenTime=ZERO_CARD_NOT_IN_CURRENT_USE")
            } else {
                sb.appendLine("SessionOpenTimeUTC=${formatUtc(sessionOpenSeconds)}")
            }
            sb.appendLine("VehicleRegistrationNation=${country(nation)}[${hex2(nation)}]")
            sb.appendLine("VehicleRegistrationCodePage=$codePage")
            sb.appendLine("VehicleRegistrationNumber=${if (vrn.isBlank()) "EMPTY" else vrn}")
            sb.appendLine("VehicleRegistrationRaw=${hex(vrnRaw)}")
            sb.appendLine("PayloadHex=${hex(p)}")
            sb.appendLine("STATUS=OK")
        }

        sb.appendLine("--------------------------------")
        sb.appendLine("Spec: CardCurrentUse = sessionOpenTime(TimeReal, 4 bytes) + sessionOpenVehicle(VehicleRegistrationIdentification, 15 bytes).")
        sb.appendLine("TimeReal=seconds since 1970-01-01 00:00:00 UTC; zero means the card is not currently inserted for a session.")
        sb.appendLine("===== END TEST-15 =====")
        return sb.toString()
    }

    private fun decodeVrn(raw: ByteArray, codePage: Int): String {
        val bytes = raw.takeWhile { it.toInt() != 0 }.toByteArray()
        val charset = try {
            if (codePage in 1..16) Charset.forName("ISO-8859-$codePage") else Charsets.ISO_8859_1
        } catch (_: Throwable) {
            Charsets.ISO_8859_1
        }
        return try {
            String(bytes, charset).trim().trimEnd('\u0000', '\u00FF', ' ')
        } catch (_: Throwable) {
            String(bytes, Charsets.ISO_8859_1).trim().trimEnd('\u0000', '\u00FF', ' ')
        }
    }

    private fun formatUtc(seconds: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return try { sdf.format(Date(seconds * 1000L)) } catch (_: Throwable) { "INVALID($seconds)" }
    }

    private fun country(v: Int): String = when (v) {
        0x00 -> "NO_INFORMATION"
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
        0x14 -> "FO"
        0x15 -> "GE"
        0x16 -> "GR"
        0x17 -> "H"
        0x18 -> "HR"
        0x19 -> "I"
        0x1A -> "IRL"
        0x1B -> "IS"
        0x1C -> "KZ"
        0x1D -> "L"
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
        0x32 -> "UK"
        0x33 -> "V"
        0x34 -> "YU"
        0x35 -> "EC"
        0x36 -> "EUR"
        0x37 -> "WLD"
        else -> "UNKNOWN"
    }

    private fun be32(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 3 >= data.size) return 0L
        return (u(data[offset]).toLong() shl 24) or
            (u(data[offset + 1]).toLong() shl 16) or
            (u(data[offset + 2]).toLong() shl 8) or
            u(data[offset + 3]).toLong()
    }

    private fun hex(data: ByteArray): String = data.joinToString(" ") { hex2(u(it)) }
    private fun hex2(v: Int) = String.format(Locale.US, "%02X", v and 0xFF)
    private fun u(b: Byte) = b.toInt() and 0xFF
}
