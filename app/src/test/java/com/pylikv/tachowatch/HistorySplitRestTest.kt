package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class HistorySplitRestTest {
    @Test
    fun uninterruptedNightRestDoesNotAddAnotherThreeHours() {
        val rest = history(
            listOf(0 to 0, 360 to 2, 1116 to 0, 1439 to 0),
            "18:36", "08:00"
        ).rests.single()
        assertEquals(804, rest.actualMinutes)
        assertFalse(rest.splitDaily)
        assertEquals(660, rest.creditedDailyMinutes)
        assertFalse(HistoryData.fmt(HistoryData.gapMinutes(
            lastModel.days[0], lastModel.days[1]
        )!!).contains("В смене засчитано: 3:00"))
    }

    @Test
    fun nineHourNightRestWithoutEarlierFirstPartRemainsReduced() {
        val rest = history(
            listOf(0 to 0, 360 to 2, 1200 to 0, 1439 to 0),
            "20:00", "05:00"
        ).rests.single()
        assertEquals(540, rest.actualMinutes)
        assertFalse(rest.splitDaily)
        assertEquals(540, rest.creditedDailyMinutes)
    }

    @Test
    fun actualThreeHourPartThenWorkThenNineHourRestRemainsSplit() {
        val rest = history(
            listOf(0 to 0, 360 to 2, 540 to 0, 720 to 2, 1200 to 0, 1439 to 0),
            "20:00", "05:00"
        ).rests.single()
        assertEquals(540, rest.actualMinutes)
        assertTrue(rest.splitDaily)
        assertEquals(660, rest.creditedDailyMinutes)
    }

    @Test
    fun aFirstPartOneMinuteShortCannotBorrowFromTheNightRest() {
        val rest = history(
            listOf(0 to 0, 360 to 2, 540 to 0, 719 to 2, 1200 to 0, 1439 to 0),
            "20:00", "05:00"
        ).rests.single()
        assertFalse(rest.splitDaily)
        assertEquals(540, rest.creditedDailyMinutes)
    }

    @Test
    fun firstPartFromBeforeAnEarlierDailyRestIsNotReused() {
        val rest = history(
            listOf(0 to 2, 60 to 0, 240 to 2, 300 to 0, 840 to 2, 1200 to 0, 1439 to 0),
            "20:00", "05:00"
        ).rests.single()
        assertFalse(rest.splitDaily)
        assertEquals(540, rest.creditedDailyMinutes)
    }

    private lateinit var lastModel: HistoryData.Model

    private fun history(
        firstChanges: List<Pair<Int, Int>>,
        firstEnd: String,
        nextStart: String
    ): HistoryData.Model {
        val nextMinute = nextStart.substringBefore(':').toInt() * 60 +
            nextStart.substringAfter(':').toInt()
        val firstRecord = dayRecord("2026-09-24", firstChanges)
        val nextRecord = dayRecord("2026-09-25", listOf(
            0 to 0, nextMinute to 2, (nextMinute + 30) to 0
        ))
        val activities = ByteArrayOutputStream().apply {
            write16(0)
            write16(firstRecord.size)
            write(firstRecord)
            write(nextRecord)
        }.toByteArray()
        val firstStartMinute = firstChanges.first { it.second != 0 }.first
        val firstStart = String.format(Locale.US, "%02d:%02d", firstStartMinute / 60, firstStartMinute % 60)
        val places = ByteArrayOutputStream().apply {
            write(2)
            place("2026-09-24 $firstStart", 0)
            place("2026-09-24 $firstEnd", 1)
            place("2026-09-25 $nextStart", 0)
        }.toByteArray()
        val file = File.createTempFile("tachowatch-split-rest", ".ddd")
        try {
            file.writeBytes(tlv(0x0504, 0x02, activities) + tlv(0x0506, 0x00, places))
            val parsed = TlvInventory.parse(file)
            assertEquals(null, parsed.error)
            val firstLoad = HistoryData.load(parsed)
            val secondLoad = HistoryData.load(parsed)
            assertEquals(firstLoad.rests, secondLoad.rests)
            assertEquals(2, secondLoad.days.size)
            lastModel = secondLoad
            return secondLoad
        } finally {
            file.delete()
        }
    }

    private fun dayRecord(date: String, changes: List<Pair<Int, Int>>): ByteArray =
        ByteArrayOutputStream().apply {
            write16(0)
            write16(12 + changes.size * 2)
            write32(epoch("$date 00:00"))
            write16(0)
            write16(0)
            changes.forEach { (minute, activity) -> write16(minute or (activity shl 11)) }
        }.toByteArray()

    private fun ByteArrayOutputStream.place(time: String, type: Int) {
        write32(epoch(time))
        write(type)
        write(0x20)
        write(0)
        repeat(3) { write(0) }
    }

    private fun tlv(fid: Int, suffix: Int, bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write16(fid)
            write(suffix)
            write16(bytes.size)
            write(bytes)
        }.toByteArray()

    private fun ByteArrayOutputStream.write16(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.write32(value: Long) {
        for (shift in listOf(24, 16, 8, 0)) write(((value ushr shift) and 0xFF).toInt())
    }

    private fun epoch(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(value)!!.time / 1000L
}
