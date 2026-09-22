package com.pylikv.tachowatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class TachographDiscoveryTest {
    @Test
    fun recognizesKnownVdoAndStoneridgeNames() {
        assertTrue(TachographDiscovery.looksLikeTachographName("DTCO 2070-1187"))
        assertTrue(TachographDiscovery.looksLikeTachographName("SE5000-123456"))
        assertTrue(TachographDiscovery.looksLikeTachographName("Stoneridge Smart 2"))
        assertFalse(TachographDiscovery.looksLikeTachographName("Headphones"))
    }

    @Test
    fun recognizesDiagnosticsServiceFromBleAdvertisement() {
        val record = advertisementWith128BitService(TachographDiscovery.DIAGNOSTICS_SERVICE)

        assertTrue(TachographDiscovery.advertisesSmartTachoV2(record))
        assertTrue(TachographDiscovery.isCandidate("Unknown device", record))
        assertEquals(
            setOf(TachographDiscovery.DIAGNOSTICS_SERVICE),
            TachographDiscovery.advertisedServiceUuids(record)
        )
    }

    @Test
    fun recognizesDownloadServiceFromBleAdvertisement() {
        val record = advertisementWith128BitService(TachographDiscovery.DOWNLOAD_SERVICE)
        assertTrue(TachographDiscovery.advertisesSmartTachoV2(record))
    }

    @Test
    fun ignoresUnrelatedBleAdvertisement() {
        val record = advertisementWith128BitService(
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        )
        assertFalse(TachographDiscovery.advertisesSmartTachoV2(record))
    }

    private fun advertisementWith128BitService(uuid: UUID): ByteArray {
        val bigEndian = ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        val littleEndian = bigEndian.reversedArray()

        // AD length=17: one type byte (0x07, complete 128-bit UUID list) + 16 UUID bytes.
        return byteArrayOf(17, 0x07) + littleEndian + byteArrayOf(0)
    }
}
