package com.pylikv.tachowatch

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DiagnosticReporter {
    private const val WINDOW_MS = 60L * 60L * 1000L
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val TRIM_TO_LINES = 7000
    private const val LOG_DIR = "diagnostics"
    private const val LOG_FILE = "rolling.log"

    @Synchronized
    fun record(context: Context, category: String, text: String) {
        if (text.isBlank()) return
        val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        val file = File(dir, LOG_FILE)
        val now = System.currentTimeMillis()
        val safeCategory = category.replace('\t', ' ').replace('\n', ' ')
        val payload = buildString {
            text.lineSequence().forEach { sourceLine ->
                val line = DiagnosticSanitizer.sanitizeLine(sourceLine)
                    .replace('\t', ' ')
                    .replace('\n', ' ')
                append(now)
                append('\t')
                append(safeCategory)
                append('\t')
                append(line)
                append('\n')
            }
        }
        runCatching { file.appendText(payload) }
        trimIfNeeded(file)
    }

    @Synchronized
    fun createReport(
        context: Context,
        description: String,
        connectionSummary: String
    ): File {
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_MS
        val source = File(File(context.filesDir, LOG_DIR), LOG_FILE)
        val recent = if (source.exists()) {
            runCatching {
                source.readLines()
                    .filter { line ->
                        line.substringBefore('\t').toLongOrNull()?.let { it >= cutoff } == true
                    }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val prefs = context.getSharedPreferences(DriverLiveService.PREFS, Context.MODE_PRIVATE)
        val report = buildString {
            appendLine("TACHOWATCH DIAGNOSTIC REPORT")
            appendLine("created_utc=${utc(now)}")
            appendLine("app_version=${BuildConfig.VERSION_NAME}")
            appendLine("app_version_code=${BuildConfig.VERSION_CODE}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("connection=$connectionSummary")
            appendLine("window_minutes=60")
            appendLine("privacy=F931 driver name is redacted; Bluetooth MAC and driver card number are not included")
            appendLine()
            appendLine("USER DESCRIPTION")
            appendLine(description.trim().ifBlank { "(no description)" })
            appendLine()
            appendLine("CURRENT COUNTERS")
            appendLine("activity=${prefs.getString(DriverLiveService.SNAP_ACTIVITY, "—")}")
            appendLine("activity_minutes=${prefs.getInt(DriverLiveService.SNAP_ACTIVITY_MIN, 0)}")
            appendLine("continuous_driving_minutes=${prefs.getInt(DriverLiveService.SNAP_CONTINUOUS_MIN, 0)}")
            appendLine("break_minutes=${prefs.getInt(DriverLiveService.SNAP_BREAK_MIN, 0)}")
            appendLine("shift_driving_minutes=${prefs.getInt(DriverLiveService.SHIFT_COMPLETED, 0)}")
            appendLine("continuous_work_minutes=${prefs.getInt(DriverLiveService.WORK_WINDOW, 0)}")
            appendLine("other_work_minutes=${prefs.getInt(DriverLiveService.WORK_ACC, 0)}")
            appendLine("availability_minutes=${prefs.getInt(DriverLiveService.AVAIL_ACC, 0)}")
            appendLine("two_week_driving_minutes=${prefs.getInt(DriverLiveService.SNAP_TWO_WEEK_MIN, 0)}")
            appendLine("snapshot_updated_at_utc=${utc(prefs.getLong(DriverLiveService.SNAP_UPDATED_AT, 0L))}")
            appendLine()
            appendLine("DTCO DIAGNOSTIC LOG — LAST 60 MINUTES")
            if (recent.isEmpty()) {
                appendLine("(no diagnostic cycles captured in the last 60 minutes)")
            } else {
                recent.forEach { raw ->
                    val first = raw.indexOf('\t')
                    val second = if (first >= 0) raw.indexOf('\t', first + 1) else -1
                    if (first <= 0 || second <= first) {
                        appendLine(DiagnosticSanitizer.sanitizeLine(raw))
                    } else {
                        val timestamp = raw.substring(0, first).toLongOrNull() ?: 0L
                        val category = raw.substring(first + 1, second)
                        val line = raw.substring(second + 1)
                        append(utc(timestamp))
                        append(" | ")
                        append(category)
                        append(" | ")
                        appendLine(DiagnosticSanitizer.sanitizeLine(line))
                    }
                }
            }
        }

        val outDir = File(context.cacheDir, "diagnostic_reports").apply { mkdirs() }
        return File(outDir, "tachowatch-report-${fileStamp(now)}.txt").apply {
            writeText(report)
        }
    }

    fun shareIntent(context: Context, report: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            report
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "TachoWatch diagnostic report")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun trimIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        runCatching {
            val keep = file.readLines().takeLast(TRIM_TO_LINES)
            file.writeText(keep.joinToString("\n", postfix = if (keep.isEmpty()) "" else "\n"))
        }
    }

    private fun utc(value: Long): String {
        if (value <= 0L) return "unknown"
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(value))
    }

    private fun fileStamp(value: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(value))
}
