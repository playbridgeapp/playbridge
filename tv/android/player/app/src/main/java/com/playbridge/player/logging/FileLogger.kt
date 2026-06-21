package com.playbridge.player.logging

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-backed logger that mirrors android.util.Log and persists entries to a rolling log file.
 *
 * Log files are stored in the app's internal storage under `logs/`.
 * When the current log exceeds [MAX_FILE_SIZE] it is rotated to `.log.1`.
 * At most [MAX_FILES] log files are kept.
 *
 * All file I/O happens on a dedicated [HandlerThread] so callers are never blocked.
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE_NAME = "playbridge.log"
    private const val MAX_FILE_SIZE = 5 * 1024 * 1024L // 5 MB
    private const val MAX_FILES = 2
    private const val RING_CAPACITY = 1500
    private const val PREFS = "browser_prefs"
    private const val PREF_LOGGING_ENABLED = "logging_enabled"

    private lateinit var logDir: File
    @Volatile private lateinit var logFile: File
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Logging is OFF by default: persisted logs can contain stream URLs and request headers
    // (incl. Debrid tokens) and are served over the LAN via GET /logs, so retention is opt-in.
    @Volatile private var enabled: Boolean = false
    private var prefs: android.content.SharedPreferences? = null

    /**
     * In-memory ring buffer of the most recent formatted log lines, for the on-TV log viewer.
     * Updated alongside the on-disk file; capped at [RING_CAPACITY] entries.
     */
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent

    private fun pushRecent(line: String) {
        val trimmed = line.trimEnd('\n')
        _recent.update { (it + trimmed).takeLast(RING_CAPACITY) }
    }

    private val handlerThread = HandlerThread("FileLoggerThread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * Must be called once from Application.onCreate() before any logging.
     */
    fun init(context: Context) {
        logDir = File(context.filesDir, LOG_DIR)
        logDir.mkdirs()
        logFile = File(logDir, LOG_FILE_NAME)
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs?.getBoolean(PREF_LOGGING_ENABLED, false) ?: false
        if (enabled) i(TAG, "FileLogger initialized — log path: ${logFile.absolutePath}")
    }

    /** Whether persistent logging is currently enabled. */
    fun isEnabled(): Boolean = enabled

    /**
     * Enables/disables persistent logging. Persists the choice. Turning it off also wipes any
     * existing log files so previously captured URLs/headers don't linger on disk.
     */
    fun setEnabled(value: Boolean) {
        enabled = value
        prefs?.edit()?.putBoolean(PREF_LOGGING_ENABLED, value)?.apply()
        if (!value) clearLogs()
    }

    // ── Public API (mirrors android.util.Log) ──────────────────────────

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        append("W", tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        append("E", tag, msg, tr)
    }

    /**
     * Records a fatal crash. Called from the uncaught exception handler before recovery.
     * Writes synchronously and skips rotation — we may not get another chance.
     */
    fun logCrash(thread: Thread, throwable: Throwable) {
        if (!enabled) return
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val line = buildString {
            append("${timestamp()} CRASH [${thread.name}] ${throwable.javaClass.name}: ${throwable.message}\n")
            append(sw.toString())
        }
        pushRecent(line)
        try {
            // Capture snapshot of logFile to avoid racing with the handler thread's rotation
            val file = if (::logFile.isInitialized) logFile else return
            file.appendText(line)
        } catch (_: Exception) {
            // Last resort; nothing we can do
        }
    }

    // ── File access for HTTP endpoint ──────────────────────────────────

    /** Returns all log files (current + rotated), newest first. */
    fun getLogFiles(): List<File> {
        if (!::logDir.isInitialized) return emptyList()
        return logDir.listFiles()
            ?.filter { it.name.startsWith(LOG_FILE_NAME.substringBefore('.')) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Deletes all log files. */
    fun clearLogs() {
        _recent.value = emptyList()
        handler.post {
            try {
                logDir.listFiles()?.forEach { it.delete() }
                // Re-create the main log file
                logFile.createNewFile()
                i(TAG, "Logs cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear logs", e)
            }
        }
    }

    // ── Internal ───────────────────────────────────────────────────────

    private fun append(level: String, tag: String, msg: String, tr: Throwable? = null) {
        // Gated: when logging is disabled nothing is retained on disk or in memory.
        if (!enabled) return
        val line = buildString {
            append("${timestamp()} $level/$tag: $msg")
            if (tr != null) {
                append("\n")
                val sw = StringWriter()
                tr.printStackTrace(PrintWriter(sw))
                append(sw.toString())
            }
            append("\n")
        }
        pushRecent(line)
        handler.post {
            try {
                rotateIfNeeded()
                logFile.appendText(line)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log", e)
            }
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_FILE_SIZE) return
        // Delete oldest
        val oldest = File(logDir, "$LOG_FILE_NAME.${MAX_FILES}")
        if (oldest.exists()) oldest.delete()
        // Shift existing rotated files
        for (i in MAX_FILES - 1 downTo 1) {
            val src = File(logDir, "$LOG_FILE_NAME.$i")
            val dst = File(logDir, "$LOG_FILE_NAME.${i + 1}")
            if (src.exists()) src.renameTo(dst)
        }
        // Rotate current
        logFile.renameTo(File(logDir, "$LOG_FILE_NAME.1"))
        logFile = File(logDir, LOG_FILE_NAME)
    }

    private fun timestamp(): String = dateFormat.format(Date())
}
