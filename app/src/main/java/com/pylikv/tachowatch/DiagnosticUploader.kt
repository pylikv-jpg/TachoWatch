package com.pylikv.tachowatch

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object DiagnosticUploader {
    private const val ENDPOINT =
        "https://whvdyxjopfwgzgqqvzaj.supabase.co/functions/v1/submit-diagnostic-report"

    private const val ANON_JWT =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndodmR5eGpvcGZ3Z3pncXF2emFqIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTAyNDU3MzksImV4cCI6MjEwNTgyMTczOX0.LaWuG5qKM5e5H_6yRjcQHW7ZH6kgWGU7VLHDCVv-l8g"

    data class Result(val ok: Boolean, val reportId: String? = null, val error: String? = null)

    fun submit(
        context: Context,
        reportFile: File,
        description: String,
        connectionSummary: String
    ): Result {
        return try {
            val prefs = context.getSharedPreferences(DriverLiveService.PREFS, Context.MODE_PRIVATE)
            var installId = prefs.getString("diagnostic_install_id", null)
            if (installId.isNullOrBlank()) {
                installId = UUID.randomUUID().toString()
                prefs.edit().putString("diagnostic_install_id", installId).apply()
            }

            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            val body = JSONObject()
                .put("install_id", installId)
                .put("app_version", packageInfo.versionName ?: "unknown")
                .put("app_version_code", versionCode)
                .put("android_version", "${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
                .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("connection_summary", connectionSummary)
                .put("user_description", description)
                .put("report_text", reportFile.readText())
                .put("metadata", JSONObject().put("source", "android_manual_problem_report"))

            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $ANON_JWT")
                setRequestProperty("apikey", ANON_JWT)
            }

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (code in 200..299) {
                val json = runCatching { JSONObject(response) }.getOrNull()
                Result(true, reportId = json?.optString("report_id")?.takeIf { it.isNotBlank() })
            } else {
                Result(false, error = "HTTP $code${if (response.isNotBlank()) ": $response" else ""}")
            }
        } catch (t: Throwable) {
            Result(false, error = t.message ?: t::class.java.simpleName)
        }
    }
}
