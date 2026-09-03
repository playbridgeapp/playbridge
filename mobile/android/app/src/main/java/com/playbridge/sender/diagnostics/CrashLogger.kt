package com.playbridge.sender.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists uncaught exceptions to a small on-disk file so phone crashes survive an app
 * restart (logcat only keeps a rolling in-memory buffer and is wiped on reboot).
 *
 * Mirrors the TV player's crash capture. Install once from Application.onCreate().
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val LOG_DIR = "logs"
    private const val CRASH_FILE = "phone_crash.log"
    private const val MAX_FILE_SIZE = 512 * 1024L // 512 KB

    @Volatile private var crashFile: File? = null
    // Perf/correctness: SimpleDateFormat is not thread-safe and write() runs on
    // arbitrary crashing threads — ThreadLocal confines one instance per thread.
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun install(context: Context) {
        val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        crashFile = File(dir, CRASH_FILE)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist crash", e)
            }
            // Always chain to the system handler so normal crash behaviour is preserved.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The persisted crash log file, or null if nothing has been written yet. */
    fun file(): File? = crashFile?.takeIf { it.exists() && it.length() > 0 }

    fun clear() {
        try {
            crashFile?.takeIf { it.exists() }?.delete()
        } catch (_: Exception) {
        }
    }

    private fun write(thread: Thread, throwable: Throwable) {
        val file = crashFile ?: return
        if (file.exists() && file.length() > MAX_FILE_SIZE) file.delete()
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val entry = buildString {
            append("${dateFormat.get()?.format(Date())} CRASH [${thread.name}] ")
            append("${throwable.javaClass.name}: ${throwable.message}\n")
            append(sw.toString())
            append("\n")
        }
        file.appendText(entry)
    }
}
