package com.bootstrap.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * UpdateService — Core Bootstrap Agent
 *
 * Lifecycle:
 *   1. Started by [BootReceiver] on device boot (or manually from [MainActivity]).
 *   2. Immediately promotes itself to a foreground service (required on API 26+).
 *   3. Runs a coroutine loop that:
 *        a. Waits for Wi-Fi connectivity
 *        b. Fetches version.json from the update server
 *        c. Compares server version_code with the installed Translation App
 *        d. Downloads + installs the APK when an update is needed
 *        e. Cleans up the APK file after a successful install
 *        f. Stops itself — the next check will happen on the next boot
 *           (or the loop retries on failure).
 *
 * Memory safety:
 *   - All work uses a [CoroutineScope] tied to the Service lifecycle.
 *   - The scope is cancelled in [onDestroy] — no leaking coroutines.
 *   - Heavy helpers ([ApkDownloader], [ApkInstaller]) are created once and
 *     hold only Application context references.
 */
class UpdateService : Service() {

    // ── Coroutine scope — cancelled when the service is destroyed ──────────
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    // ── Helpers — short-lived, Application context only ───────────────────
    private lateinit var wifiMonitor: WifiMonitor
    private lateinit var apkDownloader: ApkDownloader
    private lateinit var apkInstaller: ApkInstaller

    // Track the running agent loop so we can cancel it if needed
    private var agentJob: Job? = null

    // ──────────────────────────────────────────────────────────────────────
    // Service Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        wifiMonitor = WifiMonitor(this)
        apkDownloader = ApkDownloader(this)
        apkInstaller = ApkInstaller(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground() within 5 seconds of startForegroundService()
        startForeground(Constants.NOTIFICATION_ID, buildNotification("Initialising…"))

        Log.i(Constants.TAG, "UpdateService: started")

        // Prevent duplicate agent loops on multiple start commands
        if (agentJob?.isActive != true) {
            agentJob = serviceScope.launch { runAgentLoop() }
        }

        // START_STICKY — if the OS kills the service it will be restarted
        // automatically (important on low-memory embedded devices).
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(Constants.TAG, "UpdateService: destroyed — cancelling coroutine scope")
        serviceScope.cancel()
        super.onDestroy()
    }

    /** This service is not designed to be bound. */
    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────────────────
    // Agent loop
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Main coroutine loop — runs until a successful update (or until the
     * service is destroyed). All catches prevent the coroutine from crashing.
     */
    private suspend fun runAgentLoop() {
        Log.i(Constants.TAG, "UpdateService: agent loop started")

        // ── Step 1: Wait for Wi-Fi ────────────────────────────────────────
        while (!wifiMonitor.isWifiConnected()) {
            Log.i(Constants.TAG, "UpdateService: no Wi-Fi — retrying in ${Constants.WIFI_RETRY_INTERVAL_MS / 1000}s")
            updateNotification("Waiting for Wi-Fi connection…")
            delay(Constants.WIFI_RETRY_INTERVAL_MS)
        }
        Log.i(Constants.TAG, "UpdateService: Wi-Fi connected (${wifiMonitor.connectedSsid()})")
        updateNotification("Wi-Fi connected — checking for updates…")

        // ── Step 2: Fetch version info from server ────────────────────────
        val versionInfo = fetchVersionInfo()
        if (versionInfo == null) {
            Log.e(Constants.TAG, "UpdateService: could not fetch version.json — will retry later")
            updateNotification("Server unreachable — will retry")
            scheduleRetry()
            return
        }

        Log.i(Constants.TAG, "UpdateService: server version=${versionInfo.versionCode}, " +
                "apkUrl=${versionInfo.apkUrl}")

        // ── Step 3: Check installed version ──────────────────────────────
        val installedVersion = getInstalledVersionCode(Constants.TARGET_PACKAGE)
        Log.i(Constants.TAG, "UpdateService: installed version=$installedVersion " +
                "(null = not installed)")

        val needsUpdate = installedVersion == null || installedVersion < versionInfo.versionCode
        if (!needsUpdate) {
            Log.i(Constants.TAG, "UpdateService: Translation App is up-to-date — nothing to do")
            updateNotification("Translation App is up-to-date")
            stopSelf()
            return
        }

        Log.i(Constants.TAG,
            "UpdateService: update needed (installed=$installedVersion, " +
                    "server=${versionInfo.versionCode}) — downloading APK…")
        updateNotification("Downloading update (v${versionInfo.versionCode})…")

        // ── Step 4: Download APK ─────────────────────────────────────────
        val downloadResult = apkDownloader.download(versionInfo.apkUrl, versionInfo.sha256)

        when (downloadResult) {
            is ApkDownloader.DownloadResult.Failure -> {
                Log.e(Constants.TAG, "UpdateService: download failed: ${downloadResult.reason}")
                updateNotification("Download failed — will retry")
                scheduleRetry()
                return
            }
            is ApkDownloader.DownloadResult.Success -> {
                Log.i(Constants.TAG, "UpdateService: download success — launching installer")
                updateNotification("Installing update…")

                // ── Step 5: Install APK ───────────────────────────────────
                val installed = apkInstaller.install(downloadResult.file)
                if (!installed) {
                    Log.e(Constants.TAG, "UpdateService: installer launch failed")
                    updateNotification("Install failed — will retry")
                    apkDownloader.deleteApkIfExists()
                    scheduleRetry()
                    return
                }

                // ── Step 6: Auto-delete APK after install ─────────────────
                // Small delay so the installer has time to copy the file
                // before we delete the source.
                delay(10_000L)
                apkDownloader.deleteApkIfExists()
                Log.i(Constants.TAG, "UpdateService: APK deleted after install")

                updateNotification("Update complete")
                Log.i(Constants.TAG, "UpdateService: update workflow complete — stopping service")
                stopSelf()
            }
        }
    }

    /**
     * Schedules a retry of the full agent loop after [Constants.UPDATE_RETRY_INTERVAL_MS].
     * Uses a new coroutine so the current one exits cleanly.
     */
    private fun scheduleRetry() {
        serviceScope.launch {
            Log.i(Constants.TAG,
                "UpdateService: scheduling retry in ${Constants.UPDATE_RETRY_INTERVAL_MS / 1000}s")
            delay(Constants.UPDATE_RETRY_INTERVAL_MS)
            runAgentLoop()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Network — version.json fetch
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Makes a simple GET request to [Constants.VERSION_URL] and parses the result.
     *
     * Expected JSON:
     * ```json
     * {
     *   "version_code": 5,
     *   "apk_url": "https://yourserver.com/translation.apk",
     *   "sha256": "abc123..."   // optional but recommended
     * }
     * ```
     *
     * Uses HttpURLConnection — no third-party HTTP libraries needed.
     * Runs on [Dispatchers.IO].
     */
    private suspend fun fetchVersionInfo(): VersionInfo? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(Constants.VERSION_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = Constants.HTTP_TIMEOUT_MS
                readTimeout = Constants.HTTP_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(Constants.TAG, "UpdateService: version.json HTTP $responseCode")
                return@withContext null
            }

            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }

            parseVersionInfo(body)

        } catch (e: Exception) {
            Log.e(Constants.TAG, "UpdateService: fetchVersionInfo error: ${e.message}", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Parses version JSON safely — returns null on any parse failure. */
    private fun parseVersionInfo(json: String): VersionInfo? {
        return try {
            val obj = JSONObject(json)
            val versionCode = obj.getInt("version_code")
            val apkUrl = obj.getString("apk_url").trim()
            val sha256 = if (obj.has("sha256")) obj.getString("sha256").trim() else null

            if (versionCode <= 0 || apkUrl.isBlank()) {
                Log.e(Constants.TAG, "UpdateService: invalid version.json content")
                return null
            }

            VersionInfo(versionCode, apkUrl, sha256).also {
                Log.d(Constants.TAG, "UpdateService: parsed VersionInfo=$it")
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "UpdateService: JSON parse error: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Package manager helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Returns the installed [versionCode] of [packageName], or null if the
     * package is not installed.
     */
    private fun getInstalledVersionCode(packageName: String): Long? {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            // Use longVersionCode on API 28+ but we target 27, so versionCode is fine
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            null  // Not installed
        } catch (e: Exception) {
            Log.e(Constants.TAG, "UpdateService: getInstalledVersionCode error: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // LOW importance — no sound, no heads-up; just status bar icon
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bootstrap Agent background service"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(Constants.NOTIFICATION_ID, buildNotification(status))
    }

    // ──────────────────────────────────────────────────────────────────────
    // Data classes
    // ──────────────────────────────────────────────────────────────────────

    private data class VersionInfo(
        val versionCode: Int,
        val apkUrl: String,
        val sha256: String?          // null = no checksum required by server
    )
}
