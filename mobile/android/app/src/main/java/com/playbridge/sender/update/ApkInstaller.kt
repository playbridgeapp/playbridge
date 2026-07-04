package com.playbridge.sender.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads a phone-app APK and hands it to the system package installer.
 *
 * We deliberately use the classic `ACTION_VIEW` + [FileProvider] flow (rather than a
 * silent `PackageInstaller` session): it shows the OS's own install screen that ends with
 * a **Done / Open** choice, so the user stays in control and can relaunch via "Open".
 */
class ApkInstaller(private val appContext: Context) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)      // GitHub → release-assets host hop
            .followSslRedirects(true)
            .build()
    }

    /**
     * Download [url] into `cacheDir/updates/update.apk`, reporting progress as a 0f..1f
     * fraction (or null when the server sends no Content-Length). Returns the file.
     */
    suspend fun download(url: String, onProgress: (Float?) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "update.apk")
            if (out.exists()) out.delete()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "PlayBridge-Phone-Updater")
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Download HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("Empty download body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(downloaded.toFloat() / total)
                                }
                            } else {
                                onProgress(null)
                            }
                        }
                        output.flush()
                    }
                }
            }
            Log.i(TAG, "download: saved ${out.length()} bytes to $out")
            out
        }

    /**
     * Hand [apk] to the system installer via `ACTION_VIEW`. The OS renders the standard
     * install UI (and its Done/Open completion screen). Requires the app to be allowed to
     * install unknown apps — check with `canRequestPackageInstalls()` first.
     */
    fun launchInstall(apk: File) {
        val authority = "${appContext.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(appContext, authority, apk)
        Log.i(TAG, "launchInstall: $uri")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    companion object {
        private const val TAG = "ApkInstaller"

        /**
         * Delete a leftover downloaded APK from a previous session once it's old enough
         * that any install has finished reading it. Called on app startup.
         *
         * We only remove files older than [maxAgeMillis] (default 1h) so we never race a
         * still-open system-installer session that's reading the file via its FileProvider
         * URI. The in-session case is already handled: [download] deletes the prior file
         * before writing a new one.
         */
        fun cleanupStaleApks(context: Context, maxAgeMillis: Long = 60 * 60 * 1000L) {
            val dir = File(context.cacheDir, "updates")
            val files = dir.listFiles() ?: return
            val now = System.currentTimeMillis()
            files.forEach { f ->
                if (now - f.lastModified() > maxAgeMillis) {
                    runCatching { f.delete() }
                        .onSuccess { if (it) Log.i(TAG, "cleanup: deleted stale ${f.name}") }
                }
            }
        }
    }
}
