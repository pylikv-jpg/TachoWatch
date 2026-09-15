package com.pylikv.tachowatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restores DTCO monitoring after reboot/package replacement when a DTCO is already selected. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.MY_PACKAGE_REPLACED") return

        val prefs = context.getSharedPreferences(DriverLiveService.PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(DriverLiveService.SELECTED_DTCO, null).isNullOrBlank()) return
        ContextCompat.startForegroundService(context, Intent(context, DriverLiveService::class.java))
    }
}
