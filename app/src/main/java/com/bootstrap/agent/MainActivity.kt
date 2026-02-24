package com.bootstrap.agent

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * MainActivity — minimal diagnostic screen.
 *
 * This app is NOT UI-driven. MainActivity exists solely to:
 *   1. Show agent status (connectivity state, installed version, deployment mode).
 *   2. Allow users/technicians to start [UpdateService] manually.
 *   3. Redirect to "Install unknown apps" Settings on API 26+ if permission
 *      is not yet granted (required once on first deploy).
 *   4. Request battery optimization exemption on normal smartphones so the
 *      agent survives in the background.
 *
 * Extends [Activity] directly (not AppCompatActivity) to avoid importing
 * the entire AppCompat library — keeps APK size down.
 */
class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var downloadProgressContainer: LinearLayout
    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var tvDownloadPercent: TextView
    private lateinit var tvDownloadLabel: TextView

    private lateinit var wifiMonitor: WifiMonitor

    // ── Download progress receiver ────────────────────────────────────────
    private val downloadProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Constants.ACTION_DOWNLOAD_PROGRESS) return

            val status = intent.getStringExtra(Constants.EXTRA_PROGRESS_STATUS) ?: return
            val percent = intent.getIntExtra(Constants.EXTRA_PROGRESS_PERCENT, 0)
            val bytesDownloaded = intent.getLongExtra(Constants.EXTRA_PROGRESS_BYTES, 0)
            val totalBytes = intent.getLongExtra(Constants.EXTRA_PROGRESS_TOTAL, 0)

            when (status) {
                "started" -> {
                    downloadProgressContainer.visibility = View.VISIBLE
                    downloadProgressBar.progress = 0
                    downloadProgressBar.isIndeterminate = true
                    tvDownloadLabel.text = "Downloading update…"
                    tvDownloadPercent.text = "Connecting…"
                }
                "downloading" -> {
                    downloadProgressContainer.visibility = View.VISIBLE
                    downloadProgressBar.isIndeterminate = false
                    if (percent >= 0) {
                        downloadProgressBar.progress = percent
                        val bytesText = formatBytes(bytesDownloaded)
                        val totalText = if (totalBytes > 0) " / ${formatBytes(totalBytes)}" else ""
                        tvDownloadPercent.text = "$percent%  ($bytesText$totalText)"
                    } else {
                        tvDownloadPercent.text = formatBytes(bytesDownloaded)
                    }
                    tvDownloadLabel.text = "Downloading from server…"
                }
                "done" -> {
                    downloadProgressBar.isIndeterminate = false
                    downloadProgressBar.progress = 100
                    tvDownloadLabel.text = "Download complete ✓"
                    tvDownloadPercent.text = "Installing…"
                }
                "failed" -> {
                    downloadProgressBar.isIndeterminate = false
                    downloadProgressBar.progress = 0
                    tvDownloadLabel.text = "Download failed ✗"
                    tvDownloadPercent.text = "Will retry automatically"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvDetail = findViewById(R.id.tvDetail)
        downloadProgressContainer = findViewById(R.id.downloadProgressContainer)
        downloadProgressBar = findViewById(R.id.downloadProgressBar)
        tvDownloadPercent = findViewById(R.id.tvDownloadPercent)
        tvDownloadLabel = findViewById(R.id.tvDownloadLabel)
        wifiMonitor = WifiMonitor(this)
    }

    override fun onResume() {
        super.onResume()

        // Register for download progress broadcasts
        val filter = IntentFilter(Constants.ACTION_DOWNLOAD_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadProgressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadProgressReceiver, filter)
        }

        refreshStatus()
        ensureInstallPermission()
        requestBatteryOptimizationExemption()
        startAgentService()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(downloadProgressReceiver)
        } catch (_: Exception) { /* not registered */ }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Status display
    // ──────────────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        val isFactory = wifiMonitor.isSystemApp()
        val modeLabel = if (isFactory) "Factory Device" else "Smartphone"
        val connType = wifiMonitor.connectionType()
        val ssid = wifiMonitor.connectedSsid()?.let { " ($it)" } ?: ""

        val installedVersion = getInstalledVersionCode(Constants.TARGET_PACKAGE)
        val appState = if (installedVersion != null) {
            "Translation App v$installedVersion installed"
        } else {
            "Translation App: NOT installed"
        }

        tvStatus.text = "Bootstrap Agent v${BuildConfig.VERSION_NAME}"
        tvDetail.text = "Mode: $modeLabel\n$connType$ssid\n$appState"

        Log.d(Constants.TAG, "MainActivity: mode=$modeLabel, conn=$connType, $appState")
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
    // Battery optimization (smartphones only)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * On normal smartphones, aggressive battery optimization (Doze) can kill
     * the foreground service. Request exemption so the agent stays alive.
     * Only runs on smartphones (not factory devices where this isn’t needed).
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        // Skip on factory/system apps — they don’t get battery-restricted
        if (wifiMonitor.isSystemApp()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.i(Constants.TAG, "MainActivity: requesting battery optimization exemption")
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(Constants.TAG, "MainActivity: battery opt request failed: ${e.message}")
                }
            }
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

    // ──────────────────────────────────────────────────────────────────────
    // Formatting helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Formats byte count into human-readable string (e.g. "12.3 MB"). */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
