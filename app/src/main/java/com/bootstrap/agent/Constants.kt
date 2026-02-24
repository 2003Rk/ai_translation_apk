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
    const val TARGET_PACKAGE = "com.example.ai_translation_system"

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
    /** Separate high-priority channel for "Tap to install" on smartphones. */
    const val INSTALL_CHANNEL_ID = "bootstrap_install_channel"
    const val INSTALL_NOTIFICATION_ID = 1002
    // ── Logging ───────────────────────────────────────────────────────────
    const val TAG = "BootstrapAgent"

    // ── Broadcast actions (download progress → UI) ────────────────────────
    const val ACTION_DOWNLOAD_PROGRESS = "com.bootstrap.agent.DOWNLOAD_PROGRESS"
    const val EXTRA_PROGRESS_PERCENT = "progress_percent"
    const val EXTRA_PROGRESS_BYTES = "progress_bytes"
    const val EXTRA_PROGRESS_TOTAL = "progress_total"
    const val EXTRA_PROGRESS_STATUS = "progress_status"  // "started", "downloading", "done", "failed"
}
