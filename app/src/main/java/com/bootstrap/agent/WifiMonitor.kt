package com.bootstrap.agent

import android.content.Context
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
 * All methods are synchronous and intentionally lightweight.
 */
class WifiMonitor(private val context: Context) {

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    /**
     * Returns true if the device currently has an active, connected Wi-Fi
     * network with actual internet reachability as reported by the OS.
     */
    fun isWifiConnected(): Boolean {
        return try {
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
