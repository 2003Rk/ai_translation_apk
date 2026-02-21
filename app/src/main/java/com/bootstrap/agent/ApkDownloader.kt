package com.bootstrap.agent

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * ApkDownloader
 *
 * Downloads the translation APK via [DownloadManager] (system service —
 * no custom networking threads needed). Polls for completion and optionally
 * verifies a SHA-256 checksum to protect against corrupt or tampered files.
 *
 * All public methods run on [Dispatchers.IO] and are safe to call from a
 * coroutine scope. The class holds no long-lived references to Context beyond
 * the Application context stored at construction time.
 */
class ApkDownloader(context: Context) {

    // Use Application context to avoid leaking Activity/Service references
    private val appContext: Context = context.applicationContext

    private val downloadManager: DownloadManager by lazy {
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    /** Destination file in public Downloads directory. */
    val destinationFile: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Constants.APK_FILE_NAME
        )

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Downloads the APK from [apkUrl].
     *
     * @param apkUrl     Full HTTPS URL to the APK.
     * @param sha256     Optional expected SHA-256 hex digest. When non-null the
     *                   downloaded file is verified before returning success.
     * @return           [DownloadResult] indicating success or the failure reason.
     */
    suspend fun download(apkUrl: String, sha256: String? = null): DownloadResult =
        withContext(Dispatchers.IO) {
            try {
                // ── 1. Delete any leftover file from a previous attempt ──────
                deleteApkIfExists()

                // ── 2. Enqueue download ──────────────────────────────────────
                val downloadId = enqueueDownload(apkUrl)
                Log.i(Constants.TAG, "ApkDownloader: enqueued download id=$downloadId url=$apkUrl")

                // ── 3. Poll until complete or failed ─────────────────────────
                val status = waitForCompletion(downloadId)
                Log.i(Constants.TAG, "ApkDownloader: download finished with status=$status")

                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    deleteApkIfExists()
                    return@withContext DownloadResult.Failure("DownloadManager reported status=$status")
                }

                // ── 4. Verify file exists and is non-empty ───────────────────
                if (!destinationFile.exists() || destinationFile.length() == 0L) {
                    return@withContext DownloadResult.Failure("Downloaded file missing or empty")
                }

                // ── 5. Optional SHA-256 checksum verification ─────────────────
                if (!sha256.isNullOrBlank()) {
                    val actual = sha256Hex(destinationFile)
                    if (!actual.equals(sha256.trim(), ignoreCase = true)) {
                        Log.e(Constants.TAG,
                            "ApkDownloader: checksum mismatch! expected=$sha256 actual=$actual")
                        deleteApkIfExists()
                        return@withContext DownloadResult.Failure("SHA-256 checksum mismatch")
                    }
                    Log.i(Constants.TAG, "ApkDownloader: checksum verified OK")
                }

                Log.i(Constants.TAG, "ApkDownloader: APK ready at ${destinationFile.absolutePath}")
                DownloadResult.Success(destinationFile)

            } catch (e: Exception) {
                Log.e(Constants.TAG, "ApkDownloader: unexpected error: ${e.message}", e)
                deleteApkIfExists()
                DownloadResult.Failure(e.message ?: "Unknown error")
            }
        }

    /** Removes the APK file if it already exists (cleanup before/after install). */
    fun deleteApkIfExists() {
        try {
            if (destinationFile.exists()) {
                val deleted = destinationFile.delete()
                Log.d(Constants.TAG, "ApkDownloader: old APK deleted=$deleted")
            }
        } catch (e: Exception) {
            Log.w(Constants.TAG, "ApkDownloader: could not delete APK: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Registers a [DownloadManager.Request] and returns the download ID. */
    private fun enqueueDownload(apkUrl: String): Long {
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("Translation App Update")
            setDescription("Downloading latest version…")
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                Constants.APK_FILE_NAME
            )
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            // Restrict to unmetered (Wi-Fi) connections only
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            setAllowedOverRoaming(false)
            addRequestHeader("Cache-Control", "no-cache")
        }
        return downloadManager.enqueue(request)
    }

    /**
     * Polls [DownloadManager] every 2 seconds until the download is no longer
     * pending/running, then returns the final status code.
     *
     * Maximum wait: ~10 minutes (300 polls × 2 s). For a typical APK this is
     * more than enough even on a slow link.
     */
    private suspend fun waitForCompletion(downloadId: Long): Int {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val maxPolls = 300
        var polls = 0

        while (polls < maxPolls) {
            delay(2_000L)
            polls++

            var cursor: Cursor? = null
            try {
                cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusIdx)

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> return status
                        DownloadManager.STATUS_FAILED -> {
                            val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = cursor.getInt(reasonIdx)
                            Log.e(Constants.TAG, "ApkDownloader: download failed reason=$reason")
                            return status
                        }
                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_RUNNING,
                        DownloadManager.STATUS_PAUSED -> {
                            // Still in progress — log progress periodically
                            if (polls % 15 == 0) {
                                val downloadedIdx = cursor.getColumnIndex(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                val totalIdx = cursor.getColumnIndex(
                                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                val downloaded = cursor.getLong(downloadedIdx)
                                val total = cursor.getLong(totalIdx)
                                Log.d(Constants.TAG,
                                    "ApkDownloader: progress $downloaded / $total bytes")
                            }
                        }
                    }
                } else {
                    // Row disappeared — cancelled externally
                    Log.w(Constants.TAG, "ApkDownloader: download row not found, possibly cancelled")
                    return DownloadManager.STATUS_FAILED
                }
            } finally {
                cursor?.close()
            }
        }

        // Timed out — cancel the stalled download
        Log.e(Constants.TAG, "ApkDownloader: download timed out after ${maxPolls * 2}s")
        downloadManager.remove(downloadId)
        return DownloadManager.STATUS_FAILED
    }

    /** Returns the lowercase hex SHA-256 digest of [file]. */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytes = fis.read(buffer)
            while (bytes != -1) {
                digest.update(buffer, 0, bytes)
                bytes = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Result type
    // ──────────────────────────────────────────────────────────────────────

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Failure(val reason: String) : DownloadResult()
    }
}
