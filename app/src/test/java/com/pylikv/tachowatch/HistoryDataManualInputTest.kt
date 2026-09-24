package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class HistoryDataManualInputTest {

    @Test
    fun manuallyEnteredWorkWithCardOutIsCountedInHistory() {
        val day = epoch("2026-09-24 00:00")
        val record = ByteArrayOutputStream().apply {
            write16(0)
            write16(18)
            write32(day)
            write16(0)
            write16(0)
            write16(activityChange(0, activity = 0, cardInserted = false))
            write16(activityChange(6 * 60, activity = 2, cardInserted = false))
            write16(activityChange(6 * 60 + 30, activity = 0, cardInserted = true))
        }.toByteArray()

        val activityPayload = ByteArrayOutputStream().apply {
            write16(0)
            write16(0)
            write(record)
        }.toByteArray()

        val file = File.createTempFile("tachowatch-manual-history", ".ddd")
        try {
            file.writeBytes(tlv(0x0504, 0x02, activityPayload))

            val parsed = TlvInventory.parse(file)
            assertEquals(null, parsed.error)

            val history = HistoryData.load(parsed)
            val decodedDay = history.days.single()

            assertEquals("2026-09-24", decodedDay.date)
            assertEquals(30, decodedDay.workMinutes)
            assertEquals("06:00", decodedDay.startTime)
            assertTrue(
                decodedDay.periods.any {
                    it.startTime == "06:00" &&
                        it.type == "WORK" &&
                        it.minutes == 30
                }
            )
        } finally {
            file.delete()
        }
    }

    private fun activityChange(minute: Int, activity: Int, cardInserted: Boolean): Int {
        var value = (minute and 0x07FF) or ((activity and 0x03) shl 11)
        if (!cardInserted) value = value or 0x2000
        return value
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
