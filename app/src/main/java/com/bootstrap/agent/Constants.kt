package com.bootstrap.agent

/**
 * App-wide constants — single source of truth.
 * Change server URLs here only.
 */
object Constants {

    // ── Server ────────────────────────────────────────────────────────────
    /** Returns JSON: {"version_code": 5, "apk_url": "...", "sha256": "..."} */
    // GitHub Pages hosts version.json — update after enabling Pages on your repo
    const val VERSION_URL = "https://2003rk.github.io/ai_translation_apk/version.json"

    // ── Target application ────────────────────────────────────────────────
    const val TARGET_PACKAGE = "com.client.translation"

    // ── Download ──────────────────────────────────────────────────────────
    const val APK_FILE_NAME = "translation.apk"

    // ── Timing ────────────────────────────────────────────────────────────
    /** How long to wait before rechecking Wi-Fi when offline (ms). */
    const val WIFI_RETRY_INTERVAL_MS = 30_000L

    /** How long to wait before retrying a failed network/install cycle (ms). */
    const val UPDATE_RETRY_INTERVAL_MS = 60_000L

    /** HTTP connect + read timeout (ms). */
    const val HTTP_TIMEOUT_MS = 15_000

    // ── Notifications ─────────────────────────────────────────────────────
    const val NOTIFICATION_CHANNEL_ID = "bootstrap_agent_channel"
    const val NOTIFICATION_ID = 1001

    // ── Logging ───────────────────────────────────────────────────────────
    const val TAG = "BootstrapAgent"
}
