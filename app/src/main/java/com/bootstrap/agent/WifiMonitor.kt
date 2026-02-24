package com.bootstrap.agent

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.util.Log

/**
 * WifiMonitor
 *
 * Thin helper that wraps ConnectivityManager queries.
 * Uses only APIs available on API 26 (Android 8.0) — no NetworkCallback
 * complexity needed here; the polling loop in UpdateService is sufficient
 * for an embedded device that isn't hot-swapping networks frequently.
 *
 * Supports two modes:
 *   - **Factory / embedded device**: Wi-Fi only ([isWifiConnected])
 *   - **Normal smartphone**: Any internet (Wi-Fi or mobile data) ([isNetworkConnected])
 *
 * The mode is auto-detected via [isSystemApp] — system/priv-app installs
 * use Wi-Fi only; sideloaded installs accept any network.
 *
 * All methods are synchronous and intentionally lightweight.
 */
@Suppress("DEPRECATION")
class WifiMonitor(private val context: Context) {

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    // ── Mode detection ────────────────────────────────────────────────────

    /**
     * Returns true when the app is installed as a system / priv-app
     * (factory device). Returns false for regular sideloaded installs
     * (normal smartphone).
     */
    fun isSystemApp(): Boolean {
        return try {
            val flags = context.applicationInfo.flags
            (flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            false
        }
    }

    // ── Connectivity checks ──────────────────────────────────────────────

    /**
     * Smart connectivity check — auto-selects the right strategy:
     *   - System app (factory device) → Wi-Fi only
     *   - Normal app (smartphone)     → any internet (Wi-Fi or mobile data)
     */
    fun isConnected(): Boolean {
        return if (isSystemApp()) isWifiConnected() else isNetworkConnected()
    }

    /**
     * Returns true if the device currently has an active, connected Wi-Fi
     * network with actual internet reachability as reported by the OS.
     * Used for factory / embedded devices.
     */
    fun isWifiConnected(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val info: NetworkInfo? = connectivityManager.activeNetworkInfo
            val connected = info != null
                && info.isConnected
                && info.type == ConnectivityManager.TYPE_WIFI
            Log.d(Constants.TAG, "WifiMonitor.isWifiConnected = $connected")
            connected
        } catch (e: Exception) {
            Log.e(Constants.TAG, "WifiMonitor error: ${e.message}")
            false
        }
    }

    /**
     * Returns true if the device has any active internet connection
     * (Wi-Fi, mobile data, ethernet, etc.).
     * Used for normal smartphone installs where mobile data is acceptable.
     */
    fun isNetworkConnected(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val info: NetworkInfo? = connectivityManager.activeNetworkInfo
            val connected = info != null && info.isConnected
            Log.d(Constants.TAG, "WifiMonitor.isNetworkConnected = $connected")
            connected
        } catch (e: Exception) {
            Log.e(Constants.TAG, "WifiMonitor error: ${e.message}")
            false
        }
    }

    /**
     * Returns a human-readable connection type description for status display.
     */
    fun connectionType(): String {
        return try {
            @Suppress("DEPRECATION")
            val info: NetworkInfo? = connectivityManager.activeNetworkInfo
            if (info == null || !info.isConnected) return "Not connected"
            @Suppress("DEPRECATION")
            when (info.type) {
                ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
                ConnectivityManager.TYPE_MOBILE -> "Mobile Data"
                ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                else -> "Connected"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Returns the SSID of the connected Wi-Fi network for diagnostic logging,
     * or null if not connected.
     *
     * Note: On API 26-28 this requires ACCESS_FINE_LOCATION or
     * ACCESS_COARSE_LOCATION to return the real SSID; when unavailable the
     * system returns "<unknown ssid>". We only use this for logging so a
     * missing SSID is harmless.
     */
    fun connectedSsid(): String? {
        return try {
            @Suppress("DEPRECATION")
            val wifiInfo = connectivityManager.activeNetworkInfo
            if (wifiInfo?.isConnected == true && wifiInfo.type == ConnectivityManager.TYPE_WIFI) {
                wifiInfo.extraInfo // contains SSID in quotes e.g. "\"MyNetwork\""
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
