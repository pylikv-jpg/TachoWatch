package com.pylikv.tachowatch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

/**
 * Discovery helpers for EU Smart Tachograph V2 units.
 *
 * Compatibility is transport/capability based, not truck-brand based.
 * Name hints keep pairing UX practical for known VDO/Continental and Stoneridge
 * devices, while advertised Smart Tacho V2 GATT services allow other compliant
 * units to be discovered even when their Bluetooth name is unfamiliar.
 */
object TachographDiscovery {
    val DIAGNOSTICS_SERVICE: UUID =
        UUID.fromString("fa213def-aef4-475c-bcea-0a8d69073efc")
    val DOWNLOAD_SERVICE: UUID =
        UUID.fromString("eef90782-55dd-4388-b80b-695aba7a69b5")

    fun looksLikeTachographName(name: String?): Boolean {
        val n = name?.trim()?.uppercase(Locale.ROOT) ?: return false
        return n.contains("DTCO") ||
            n.contains("SE5000") ||
            n.contains("STONERIDGE") ||
            n.contains("VDO TACHO") ||
            n.contains("CONTINENTAL TACHO")
    }

    fun manufacturerHint(name: String?): String {
        val n = name?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return when {
            n.contains("SE5000") || n.contains("STONERIDGE") -> "Stoneridge"
            n.contains("DTCO") || n.contains("VDO") || n.contains("CONTINENTAL") -> "VDO/Continental"
            else -> "Smart Tachograph"
        }
    }

    fun advertisesSmartTachoV2(scanRecord: ByteArray?): Boolean {
        val services = advertisedServiceUuids(scanRecord)
        return DIAGNOSTICS_SERVICE in services || DOWNLOAD_SERVICE in services
    }

    fun isCandidate(name: String?, scanRecord: ByteArray?): Boolean =
        looksLikeTachographName(name) || advertisesSmartTachoV2(scanRecord)

    internal fun advertisedServiceUuids(scanRecord: ByteArray?): Set<UUID> {
        if (scanRecord == null || scanRecord.isEmpty()) return emptySet()
        val result = linkedSetOf<UUID>()
        var offset = 0

        while (offset < scanRecord.size) {
            val length = scanRecord[offset].toInt() and 0xFF
            if (length == 0) break
            val end = offset + 1 + length
            if (end > scanRecord.size || offset + 1 >= scanRecord.size) break

            val type = scanRecord[offset + 1].toInt() and 0xFF
            if (type == 0x06 || type == 0x07) {
                var p = offset + 2
                while (p + 16 <= end) {
                    result += uuid128FromLittleEndian(scanRecord, p)
                    p += 16
                }
            }
            offset = end
        }
        return result
    }

    private fun uuid128FromLittleEndian(bytes: ByteArray, offset: Int): UUID {
        val bigEndian = bytes.copyOfRange(offset, offset + 16).reversedArray()
        val bb = ByteBuffer.wrap(bigEndian).order(ByteOrder.BIG_ENDIAN)
        return UUID(bb.long, bb.long)
    }
}
