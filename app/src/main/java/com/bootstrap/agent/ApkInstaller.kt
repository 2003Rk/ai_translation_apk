package com.bootstrap.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * ApkInstaller
 *
 * Launches the system package installer for a locally stored APK using
 * [FileProvider] to safely share the file URI (required on API 24+).
 *
 * On API 26+ the manifest must declare:
 *   android:name="android.permission.REQUEST_INSTALL_PACKAGES"
 *
 * The install dialog is shown to the user. For fully silent install the app
 * must be a system (privileged) app — see README for instructions.
 */
class ApkInstaller(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Triggers the system installer for [apkFile].
     *
     * Must be called from a context that has a valid task stack so the
     * installer Activity can be started. [UpdateService] passes itself as
     * the context and adds FLAG_ACTIVITY_NEW_TASK accordingly.
     *
     * @return true if the intent was dispatched without error.
     */
    fun install(apkFile: File): Boolean {
        if (!apkFile.exists()) {
            Log.e(Constants.TAG, "ApkInstaller: file not found: ${apkFile.absolutePath}")
            return false
        }

        return try {
            val apkUri: Uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                // FLAG_GRANT_READ_URI_PERMISSION lets the installer read the file
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Required when starting an Activity from a non-Activity context
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Clear the back stack so pressing Back doesn't return to installer
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            appContext.startActivity(installIntent)
            Log.i(Constants.TAG, "ApkInstaller: install intent dispatched for ${apkFile.name}")
            true

        } catch (e: Exception) {
            Log.e(Constants.TAG, "ApkInstaller: failed to launch installer: ${e.message}", e)
            false
        }
    }

    /**
     * Returns true if [REQUEST_INSTALL_PACKAGES] is granted on this device.
     * On API 26+ the user must explicitly allow installs from unknown sources
     * for this app via Settings → Special app access → Install unknown apps.
     *
     * The check here is informational only — the actual enforcement is by the
     * system installer, and missing permission will surface as a user-visible
     * Settings redirect.
     */
    fun canRequestInstall(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            // Below API 26, "Unknown sources" is a global toggle — assume granted
            true
        }
    }
}
