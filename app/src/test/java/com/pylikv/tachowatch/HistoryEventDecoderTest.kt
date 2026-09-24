package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class HistoryEventDecoderTest {

    @Test
    fun decodesCardPresenceSpecificConditionsAndLoadOperations() {
        val day = epoch("2026-09-24 00:00")
        val activity = ByteArrayOutputStream().apply {
            write16(0)
            write16(0)

            val record = ByteArrayOutputStream().apply {
                write16(0)
                write16(18)
                write32(day)
                write16(0)
                write16(0)
                write16(activityChange(0, cardInserted = false))
                write16(activityChange(6 * 60 + 15, cardInserted = true))
                write16(activityChange(18 * 60 + 17, cardInserted = false))
            }.toByteArray()
            write(record)
        }.toByteArray()

        val conditions = ByteArrayOutputStream().apply {
            write16(3)
            writeCondition(epoch("2026-09-24 07:00"), 0x01)
            writeCondition(epoch("2026-09-24 08:00"), 0x02)
            writeCondition(epoch("2026-09-24 09:00"), 0x03)
            writeCondition(epoch("2026-09-24 10:00"), 0x04)
        }.toByteArray()

        val operations = ByteArrayOutputStream().apply {
            write16(2)
            writeLoadUnload(epoch("2026-09-24 11:00"), 0x01, 123456)
            writeLoadUnload(epoch("2026-09-24 12:00"), 0x02, 123460)
            writeLoadUnload(epoch("2026-09-24 13:00"), 0x03, 123470)
        }.toByteArray()

        val file = File.createTempFile("tachowatch-history-events", ".ddd")
        try {
            file.writeBytes(
                tlv(0x0504, 0x02, activity) +
                    tlv(0x0522, 0x02, conditions) +
                    tlv(0x0529, 0x02, operations)
            )

            val parsed = TlvInventory.parse(file)
            assertEquals(null, parsed.error)

            val events = HistoryEventDecoder.decode(parsed)
            assertEquals(9, events.size)
            assertTrue(events.any { it.time == "06:15" && it.type == HistoryEventDecoder.Type.CARD_INSERTED })
            assertTrue(events.any { it.time == "18:17" && it.type == HistoryEventDecoder.Type.CARD_REMOVED })
            assertTrue(events.any { it.time == "07:00" && it.type == HistoryEventDecoder.Type.OUT_BEGIN })
            assertTrue(events.any { it.time == "08:00" && it.type == HistoryEventDecoder.Type.OUT_END })
            assertTrue(events.any { it.time == "09:00" && it.type == HistoryEventDecoder.Type.FERRY_TRAIN_BEGIN })
            assertTrue(events.any { it.time == "10:00" && it.type == HistoryEventDecoder.Type.FERRY_TRAIN_END })
            assertTrue(events.any { it.time == "11:00" && it.type == HistoryEventDecoder.Type.LOAD && it.odometerKm == 123456 })
            assertTrue(events.any { it.time == "12:00" && it.type == HistoryEventDecoder.Type.UNLOAD && it.odometerKm == 123460 })
            assertTrue(events.any { it.time == "13:00" && it.type == HistoryEventDecoder.Type.LOAD_UNLOAD && it.odometerKm == 123470 })
        } finally {
            file.delete()
        }
    }

    private fun activityChange(minute: Int, cardInserted: Boolean): Int {
        var value = minute and 0x07FF
        if (!cardInserted) value = value or 0x2000
        return value
    }

    private fun ByteArrayOutputStream.writeCondition(timestamp: Long, type: Int) {
        write32(timestamp)
        write(type)
    }

    private fun ByteArrayOutputStream.writeLoadUnload(timestamp: Long, type: Int, odometer: Int) {
        write32(timestamp)
        write(type)
        repeat(12) { write(0) }
        write24(odometer)
    }

    private fun tlv(fid: Int, suffix: Int, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write((fid ushr 8) and 0xFF)
            write(fid and 0xFF)
            write(suffix)
            write16(payload.size)
            write(payload)
        }.toByteArray()

    private fun ByteArrayOutputStream.write16(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.write24(value: Int) {
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.write32(value: Long) {
        write(((value ushr 24) and 0xFF).toInt())
        write(((value ushr 16) and 0xFF).toInt())
        write(((value ushr 8) and 0xFF).toInt())
        write((value and 0xFF).toInt())
    }

    private fun epoch(value: String): Long {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return format.parse(value)!!.time / 1000L
    }
}
