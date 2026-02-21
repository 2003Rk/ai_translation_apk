package com.bootstrap.agent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView

/**
 * MainActivity — minimal diagnostic screen.
 *
 * This app is NOT UI-driven. MainActivity exists solely to:
 *   1. Show agent status (Wi-Fi state, installed version).
 *   2. Allow users/technicians to start [UpdateService] manually.
 *   3. Redirect to "Install unknown apps" Settings on API 26+ if permission
 *      is not yet granted (required once on first deploy).
 *
 * Extends [Activity] directly (not AppCompatActivity) to avoid importing
 * the entire AppCompat library — keeps APK size down.
 */
class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView

    private lateinit var wifiMonitor: WifiMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvDetail = findViewById(R.id.tvDetail)
        wifiMonitor = WifiMonitor(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        ensureInstallPermission()
        startAgentService()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Status display
    // ──────────────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        val wifiState = if (wifiMonitor.isWifiConnected()) "Wi-Fi: Connected" else "Wi-Fi: Not connected"
        val ssid = wifiMonitor.connectedSsid()?.let { " ($it)" } ?: ""

        val installedVersion = getInstalledVersionCode(Constants.TARGET_PACKAGE)
        val appState = if (installedVersion != null) {
            "Translation App v$installedVersion installed"
        } else {
            "Translation App: NOT installed"
        }

        tvStatus.text = "Bootstrap Agent v${BuildConfig.VERSION_NAME}"
        tvDetail.text = "$wifiState$ssid\n$appState"

        Log.d(Constants.TAG, "MainActivity: $wifiState, $appState")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Service management
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Ensures [UpdateService] is running. Safe to call multiple times —
     * the service guards against duplicate loops internally.
     */
    private fun startAgentService() {
        val intent = Intent(this, UpdateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.i(Constants.TAG, "MainActivity: UpdateService start requested")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Install unknown apps permission (API 26+)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * On API 26+ the user must grant "Install unknown apps" for this specific
     * app once. If not granted, we open the relevant Settings page so a
     * technician can enable it. After granting, the user returns here via Back
     * and [onResume] fires again.
     */
    private fun ensureInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Log.w(Constants.TAG,
                    "MainActivity: REQUEST_INSTALL_PACKAGES not granted — opening Settings")
                tvDetail.append("\n\n⚠ Enable 'Install unknown apps' in Settings to allow updates.")
                openInstallPermissionSettings()
            }
        }
    }

    private fun openInstallPermissionSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "MainActivity: could not open install settings: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Package helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun getInstalledVersionCode(packageName: String): Long? {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            null
        }
    }
}
