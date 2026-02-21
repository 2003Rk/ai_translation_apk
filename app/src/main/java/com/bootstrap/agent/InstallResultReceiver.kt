package com.bootstrap.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * InstallResultReceiver
 *
 * Receives the result broadcast from [PackageInstaller] after a silent
 * install session is committed. Logs success or failure for diagnostics.
 *
 * Registered in AndroidManifest — triggered by the PendingIntent passed
 * to PackageInstaller.Session.commit().
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(Constants.TAG,
                    "InstallResultReceiver: ✅ silent install SUCCESS for $packageName")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // On non-priv system apps, user confirmation may still be required
                // Launch the confirmation intent provided by the system
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                    Log.i(Constants.TAG,
                        "InstallResultReceiver: user confirmation required — launching dialog")
                }
            }
            else -> {
                Log.e(Constants.TAG,
                    "InstallResultReceiver: ❌ install FAILED status=$status " +
                            "package=$packageName message=$message")
            }
        }
    }
}
