package com.bootstrap.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver
 *
 * Listens for BOOT_COMPLETED (and the HTC/Huawei QUICKBOOT_POWERON alias).
 * Immediately starts [UpdateService] as a foreground service so the OS
 * cannot kill it during the update workflow.
 *
 * Registered in AndroidManifest with priority=1000 so it fires early.
 * Requires: android.permission.RECEIVE_BOOT_COMPLETED
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // Guard — only react to known boot actions
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.i(Constants.TAG, "BootReceiver: device booted, starting UpdateService")

        val serviceIntent = Intent(context, UpdateService::class.java)

        // On API 26+ we must use startForegroundService; the service itself
        // calls startForeground() within 5 seconds to satisfy the OS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
