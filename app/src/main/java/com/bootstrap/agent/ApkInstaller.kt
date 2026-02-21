package com.bootstrap.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

/**
 * ApkInstaller
 *
 * Supports two install modes:
 *
 * 1. SILENT (system/priv-app): Uses PackageInstaller API — no user popup,
 *    no dialog, fully automatic. Requires the app to be placed in
 *    /system/priv-app and have INSTALL_PACKAGES privileged permission.
 *    This is the mode used when pre-installed on the embedded device.
 *
 * 2. NORMAL (sideload fallback): Uses ACTION_VIEW + FileProvider — shows
 *    the system install dialog. Used if silent install fails or app is
 *    not a system app yet (during development/testing).
 */
class ApkInstaller(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Installs [apkFile] silently if running as a privileged system app,
     * otherwise falls back to the standard install dialog.
     *
     * @return true if install was initiated without error.
     */
    fun install(apkFile: File): Boolean {
        if (!apkFile.exists()) {
            Log.e(Constants.TAG, "ApkInstaller: file not found: ${apkFile.absolutePath}")
            return false
        }

        // Try silent install first (works when pre-installed as system app)
        val silentOk = trySilentInstall(apkFile)
        if (silentOk) return true

        // Fallback: show system install dialog (development / non-priv-app)
        Log.w(Constants.TAG, "ApkInstaller: silent install not available — using dialog installer")
        return tryDialogInstall(apkFile)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Silent install via PackageInstaller (system / priv-app only)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Uses [PackageInstaller] to install without any user interaction.
     * Requires INSTALL_PACKAGES permission (granted only to priv-apps).
     * Returns false immediately if permission is not held.
     */
    private fun trySilentInstall(apkFile: File): Boolean {
        return try {
            val packageInstaller = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName(Constants.TARGET_PACKAGE)

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            // Write APK bytes into the session
            session.use { s ->
                FileInputStream(apkFile).use { fis ->
                    s.openWrite("package", 0, apkFile.length()).use { out ->
                        val buffer = ByteArray(65536)
                        var bytes = fis.read(buffer)
                        while (bytes != -1) {
                            out.write(buffer, 0, bytes)
                            bytes = fis.read(buffer)
                        }
                        s.fsync(out)
                    }
                }

                // Create a broadcast intent to receive install result
                val intent = Intent(appContext, InstallResultReceiver::class.java)
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE
                )
                s.commit(pendingIntent.intentSender)
            }

            Log.i(Constants.TAG, "ApkInstaller: silent install session committed (id=$sessionId)")
            true

        } catch (e: SecurityException) {
            // Not a privileged app — expected during development
            Log.w(Constants.TAG, "ApkInstaller: no INSTALL_PACKAGES permission — ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(Constants.TAG, "ApkInstaller: silent install error: ${e.message}", e)
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Dialog install via FileProvider (fallback / development)
    // ──────────────────────────────────────────────────────────────────────

    private fun tryDialogInstall(apkFile: File): Boolean {
        return try {
            val apkUri: Uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            appContext.startActivity(installIntent)
            Log.i(Constants.TAG, "ApkInstaller: dialog install intent dispatched")
            true
        } catch (e: Exception) {
            Log.e(Constants.TAG, "ApkInstaller: dialog install failed: ${e.message}", e)
            false
        }
    }

    fun canRequestInstall(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }
}
