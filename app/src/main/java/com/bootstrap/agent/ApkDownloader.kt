package com.bootstrap.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * ApkDownloader
 *
 * Downloads the translation APK directly via [HttpURLConnection] into the
 * app's private cache directory. This avoids all scoped storage / permission
 * issues on Android 11+ (API 30+) and Android 14+ (API 34+) where
 * DownloadManager's public Downloads directory is not readable by the app.
 *
 * The file is saved to `{cacheDir}/translation.apk` which the app always
 * has full read/write access to — no runtime permissions needed.
 *
 * All public methods run on [Dispatchers.IO] and are safe to call from a
 * coroutine scope. The class holds no long-lived references to Context beyond
 * the Application context stored at construction time.
 */
class ApkDownloader(context: Context) {

    // Use Application context to avoid leaking Activity/Service references
    private val appContext: Context = context.applicationContext

    /** Destination file in app-private cache directory — always readable. */
    val destinationFile: File
        get() = File(appContext.cacheDir, Constants.APK_FILE_NAME)

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Downloads the APK from [apkUrl].
     *
     * @param apkUrl     Full HTTPS URL to the APK.
     * @param sha256     Optional expected SHA-256 hex digest. When non-null the
     *                   downloaded file is verified before returning success.
     * @param wifiOnly   Unused in direct download mode but kept for API compat.
     *                   Network type checking is handled by [WifiMonitor] before
     *                   this method is called.
     * @return           [DownloadResult] indicating success or the failure reason.
     */
    /**
     * Progress callback: (bytesDownloaded, totalBytes, percentComplete) → Unit.
     * Called on IO thread — callers must switch to main thread for UI updates.
     */
    suspend fun download(
        apkUrl: String,
        sha256: String? = null,
        wifiOnly: Boolean = true,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long, percent: Int) -> Unit)? = null
    ): DownloadResult =
        withContext(Dispatchers.IO) {
            try {
                // ── 1. Delete any leftover file from a previous attempt ──────
                deleteApkIfExists()

                Log.i(Constants.TAG, "ApkDownloader: starting download url=$apkUrl")

                // ── 2. Download via HttpURLConnection ────────────────────────
                val downloadOk = downloadToFile(apkUrl, destinationFile, onProgress)
                if (!downloadOk) {
                    deleteApkIfExists()
                    return@withContext DownloadResult.Failure("Download failed")
                }

                // ── 3. Verify file exists and is non-empty ───────────────────
                if (!destinationFile.exists() || destinationFile.length() == 0L) {
                    return@withContext DownloadResult.Failure("Downloaded file missing or empty")
                }

                Log.i(Constants.TAG,
                    "ApkDownloader: downloaded ${destinationFile.length()} bytes")

                // ── 4. Optional SHA-256 checksum verification ────────────────
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

    /**
     * Downloads [url] to [outputFile] using [HttpURLConnection].
     * Follows redirects (up to 5 hops). Returns true on success.
     */
    private fun downloadToFile(
        url: String,
        outputFile: File,
        onProgress: ((Long, Long, Int) -> Unit)? = null
    ): Boolean {
        var currentUrl = url
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = Constants.HTTP_TIMEOUT_MS
                    readTimeout = 60_000  // 60s read timeout for large APK files
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("Cache-Control", "no-cache")
                }

                val responseCode = connection.responseCode

                // Handle redirects manually (some servers redirect from HTTPS→HTTP
                // which HttpURLConnection won't follow automatically)
                if (responseCode in 301..308) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        Log.e(Constants.TAG, "ApkDownloader: redirect with no Location header")
                        return false
                    }
                    Log.d(Constants.TAG, "ApkDownloader: redirect $responseCode → $location")
                    currentUrl = location
                    redirects++
                    connection.disconnect()
                    continue
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(Constants.TAG, "ApkDownloader: HTTP $responseCode from $currentUrl")
                    return false
                }

                val totalBytes = connection.contentLength.toLong()
                Log.i(Constants.TAG, "ApkDownloader: content-length=$totalBytes, downloading...")

                // Stream to file
                connection.inputStream.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastReportedPct = -1

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            // Calculate progress percentage
                            val pct = if (totalBytes > 0) (totalRead * 100 / totalBytes).toInt() else -1

                            // Report progress every 1% change (or every ~256KB if size unknown)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress?.invoke(totalRead, totalBytes, pct)
                            }

                            // Log progress every ~5MB
                            if (totalBytes > 0 && totalRead % (5 * 1024 * 1024) < 8192) {
                                Log.d(Constants.TAG,
                                    "ApkDownloader: progress $totalRead / $totalBytes ($pct%)")
                            }
                        }
                        output.flush()

                        // Final 100% callback
                        onProgress?.invoke(totalRead, totalRead, 100)
                    }
                }

                Log.i(Constants.TAG, "ApkDownloader: download complete → ${outputFile.absolutePath}")
                return true

            } catch (e: Exception) {
                Log.e(Constants.TAG, "ApkDownloader: download error: ${e.message}", e)
                return false
            } finally {
                connection?.disconnect()
            }
        }

        Log.e(Constants.TAG, "ApkDownloader: too many redirects ($maxRedirects)")
        return false
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
