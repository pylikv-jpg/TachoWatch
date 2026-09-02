package com.pylikv.tachowatch

import java.io.File
import java.util.Locale

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

    fun findLatestDdd(dir: File?): File? {
        if (dir == null || !dir.exists()) return null
        return dir.listFiles()
            ?.filter { it.isFile && it.name.lowercase(Locale.US).endsWith(".ddd") }
            ?.maxByOrNull { it.lastModified() }
    }

    fun parse(file: File): Result {
        val data = try { file.readBytes() } catch (e: Throwable) {
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
                return Result(file, data.size, out, p,
                    "Неполная TLV-запись #$index: offset=$p FID=${hex4(fid)} suffix=${hex2(suffix)} len=$len, осталось=${data.size - p}")
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
            sb.appendLine(String.format(Locale.US,
                "#%02d offset=%06d FID=%04X suffix=%02X len=%d",
                e.index, e.offset, e.fid, e.suffix, e.length))
        }
        sb.appendLine("--------------------------------")
        val grouped = result.entries.groupBy { Pair(it.fid, it.suffix) }
        sb.appendLine("UniqueTags=${grouped.size}")
        grouped.toSortedMap(compareBy<Pair<Int,Int>>({ it.first }, { it.second })).forEach { (k, list) ->
            val total = list.sumOf { it.length }
            sb.appendLine(String.format(Locale.US,
                "TAG FID=%04X suffix=%02X count=%d dataBytes=%d",
                k.first, k.second, list.size, total))
        }
        sb.appendLine("===== END TEST-12 INVENTORY =====")
        return sb.toString()
    }

    private fun u(b: Byte) = b.toInt() and 0xFF
    private fun hex2(v: Int) = String.format(Locale.US, "%02X", v and 0xFF)
    private fun hex4(v: Int) = String.format(Locale.US, "%04X", v and 0xFFFF)
}
