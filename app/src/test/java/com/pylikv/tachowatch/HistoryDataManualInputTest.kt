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

    private fun place(hour: Int, type: Int, km: Int) = PlacesDecoder.Record(
        timestampSeconds = epoch("2026-09-24 00:00") + hour * 3600L,
        date = if (hour < 24) "2026-09-24" else "2026-09-25",
        time = "%02d:00".format(hour % 24), entryType = type,
        country = "E", odometerKm = km
    )

    @Test
    fun countryEndAndBeginDoNotSplitMileageWithoutDailyRest() {
        val mileage = HistoryData.pairShiftMileage(
            listOf(place(6, 0, 100000), place(14, 1, 100400),
                place(14, 0, 100400), place(18, 1, 100520)), emptyList()
        ).single()
        assertEquals(520, mileage.endOdometerKm - mileage.startOdometerKm)
    }

    @Test
    fun nineHourRestStartsNewMileagePeriodAcrossMidnight() {
        val midnight = epoch("2026-09-24 00:00")
        val mileage = HistoryData.pairShiftMileage(
            listOf(place(6, 0, 100000), place(18, 1, 100520),
                place(27, 0, 100520), place(35, 1, 100820)),
            listOf(midnight + 18 * 3600L to midnight + 27 * 3600L)
        )
        assertEquals(listOf(520, 300), mileage.map { it.endOdometerKm - it.startOdometerKm })
    }

    @Test
    fun eightHourRestDoesNotResetMileagePeriod() {
        val midnight = epoch("2026-09-24 00:00")
        val mileage = HistoryData.pairShiftMileage(
            listOf(place(6, 0, 100000), place(18, 1, 100400),
                place(26, 0, 100400), place(30, 1, 100520)),
            listOf(midnight + 18 * 3600L to midnight + 26 * 3600L)
        ).single()
        assertEquals(520, mileage.endOdometerKm - mileage.startOdometerKm)
    }

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

    @Test
    fun liveCardPresenceEventsAreMergedIntoMatchingHistoryDay() {
        val day = epoch("2026-09-24 00:00")
        val record = ByteArrayOutputStream().apply {
            write16(0)
            write16(18)
            write32(day)
            write16(0)
            write16(0)
            write16(activityChange(0, activity = 0, cardInserted = true))
            write16(activityChange(6 * 60, activity = 2, cardInserted = true))
            write16(activityChange(7 * 60, activity = 0, cardInserted = true))
        }.toByteArray()
        val activityPayload = ByteArrayOutputStream().apply {
            write16(0)
            write16(0)
            write(record)
        }.toByteArray()
        val file = File.createTempFile("tachowatch-card-events-history", ".ddd")
        try {
            file.writeBytes(tlv(0x0504, 0x02, activityPayload))
            val parsed = TlvInventory.parse(file)
            val localEvents = listOf(
                LocalCardEventStore.Event(
                    timestamp = epoch("2026-09-24 06:10") * 1000L,
                    type = LocalCardEventStore.Type.REMOVED
                ),
                LocalCardEventStore.Event(
                    timestamp = epoch("2026-09-24 06:40") * 1000L,
                    type = LocalCardEventStore.Type.INSERTED
                )
            )

            val history = HistoryData.load(parsed, localEvents)
            val events = history.days.single().events

            assertTrue(events.any { it.time == "06:10" && it.type == HistoryEventDecoder.Type.CARD_REMOVED })
            assertTrue(events.any { it.time == "06:40" && it.type == HistoryEventDecoder.Type.CARD_INSERTED })
        } finally {
            file.delete()
        }
    }

    @Test
    fun shiftMileageUsesOpeningAndClosingOdometer() {
        val day = epoch("2026-09-24 00:00")
        val activityRecord = ByteArrayOutputStream().apply {
            write16(0)
            write16(16)
            write32(day)
            write16(0)
            write16(0)
            write16(activityChange(6 * 60, activity = 2, cardInserted = true))
            write16(activityChange(7 * 60, activity = 0, cardInserted = true))
        }.toByteArray()
        val activityPayload = ByteArrayOutputStream().apply {
            write16(0)
            write16(0)
            write(activityRecord)
        }.toByteArray()
        val placesPayload = ByteArrayOutputStream().apply {
            write(1)
            write32(epoch("2026-09-24 06:00"))
            write(0)
            write(0x20)
            write(0)
            write24(100000)
            write32(epoch("2026-09-24 18:00"))
            write(1)
            write(0x20)
            write(0)
            write24(100420)
        }.toByteArray()
        val file = File.createTempFile("tachowatch-mileage-history", ".ddd")
        try {
            file.writeBytes(
                tlv(0x0504, 0x02, activityPayload) +
                    tlv(0x0506, 0x00, placesPayload)
            )
            val parsed = TlvInventory.parse(file)
            assertEquals(null, parsed.error)

            val history = HistoryData.load(parsed)
            val decodedDay = history.days.single()

            assertEquals(100000, decodedDay.startOdometerKm)
            assertEquals(100420, decodedDay.endOdometerKm)
            assertEquals(420, decodedDay.shiftDistanceKm)
            assertEquals(420, HistoryData.mileageBetween(history.days, "2026-09-24", "2026-09-24").first)
            assertEquals(0, HistoryData.mileageBetween(history.days, "2026-09-24", "2026-09-24").second)
        } finally {
            file.delete()
        }
    }

    @Test
    fun countryBeginInsideShiftDoesNotDiscardFirstFourHundredKilometres() {
        val day = epoch("2026-09-24 00:00")
        val activityRecord = ByteArrayOutputStream().apply {
            write16(0)
            write16(16)
            write32(day)
            write16(0)
            write16(0)
            write16(activityChange(6 * 60, activity = 2, cardInserted = true))
            write16(activityChange(7 * 60, activity = 0, cardInserted = true))
        }.toByteArray()
        val activityPayload = ByteArrayOutputStream().apply {
            write16(0)
            write16(0)
            write(activityRecord)
        }.toByteArray()
        val placesPayload = ByteArrayOutputStream().apply {
            write(2)
            write32(epoch("2026-09-24 06:00"))
            write(0)
            write(0x20)
            write(0)
            write24(100000)
            write32(epoch("2026-09-24 14:00"))
            write(0)
            write(0x0F)
            write(0)
            write24(100400)
            write32(epoch("2026-09-24 18:00"))
            write(1)
            write(0x20)
            write(0)
            write24(100520)
        }.toByteArray()
        val file = File.createTempFile("tachowatch-mileage-history", ".ddd")
        try {
            file.writeBytes(
                tlv(0x0504, 0x02, activityPayload) +
                    tlv(0x0506, 0x00, placesPayload)
            )
            val parsed = TlvInventory.parse(file)
            assertEquals(null, parsed.error)

            val history = HistoryData.load(parsed)
            val decodedDay = history.days.single()

            assertEquals(100000, decodedDay.startOdometerKm)
            assertEquals(100520, decodedDay.endOdometerKm)
            assertEquals(520, decodedDay.shiftDistanceKm)
            assertEquals(520, HistoryData.mileageBetween(history.days, "2026-09-24", "2026-09-24").first)
            assertEquals(0, HistoryData.mileageBetween(history.days, "2026-09-24", "2026-09-24").second)
        } finally {
            file.delete()
        }
    }
    @Test
    fun mileageRangeCountsMissingOdometerPairsWithoutInventingDistance() {
        val days = listOf(
            HistoryData.Day(
                date = "2026-09-24",
                drivingMinutes = 60,
                workMinutes = 0,
                availabilityMinutes = 0,
                startTime = "06:00",
                startCountry = "LV",
                endTime = "18:00",
                endCountry = "LV",
                startOdometerKm = 100000,
                endOdometerKm = 100420
            ),
            HistoryData.Day(
                date = "2026-09-25",
                drivingMinutes = 60,
                workMinutes = 0,
                availabilityMinutes = 0,
                startTime = "06:00",
                startCountry = "LV",
                endTime = "18:00",
                endCountry = "LV"
            )
        )

        val result = HistoryData.mileageBetween(days, "2026-09-24", "2026-09-25")
        assertEquals(420, result.first)
        assertEquals(1, result.second)
    }

    private fun ByteArrayOutputStream.write24(value: Int) {
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
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
