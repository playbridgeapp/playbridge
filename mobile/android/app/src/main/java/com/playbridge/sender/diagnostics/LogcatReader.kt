package com.playbridge.sender.diagnostics

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads this app's own logcat output. An app can always read the log lines for its own
 * process without the READ_LOGS permission, so we filter by [Process.myPid].
 *
 * We take periodic snapshots (`logcat -d`, dump-and-exit) rather than holding a long-lived
 * streaming process — that keeps process lifecycle trivial and is plenty responsive for an
 * in-app viewer that polls every second or two.
 */
object LogcatReader {

    enum class Level(val code: Char) {
        VERBOSE('V'), DEBUG('D'), INFO('I'), WARN('W'), ERROR('E'), ASSERT('A'), UNKNOWN('?');

        companion object {
            fun fromCode(c: Char): Level = entries.firstOrNull { it.code == c } ?: UNKNOWN
        }
    }

    data class LogLine(
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String,
        val raw: String,
    )

    // `06-21 14:48:01.123 D/SomeTag( 1234): the message text`
    private val LINE_REGEX =
        Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEAF])/(.+?)\(\s*\d+\):\s?(.*)$""")

    // Framework / OEM rendering & input plumbing that runs inside the app process and floods
    // logcat (frame-rate hints, surface/buffer churn, IME bookkeeping). These are never our app's
    // own logs, so they're hidden by default; the viewer's "System" toggle brings them back.
    private val NOISE_TAGS = setOf(
        "ViewRootImpl", "ViewRootImplStubImpl", "ViewRootImplExtImpl", "VRI",
        "OpenGLRenderer", "HWUI", "Choreographer", "BLASTBufferQueue", "Surface",
        "SurfaceControl", "BufferQueueProducer", "Gralloc4", "EGL_emulation", "libEGL",
        "InsetsController", "InsetsSourceConsumer", "ImeTracker", "InputMethodManager",
        "InputTransport", "WindowOnBackDispatcher", "SmartFps", "MiuiFrameRate",
    )
    // Specific enough not to hide legitimate app logs (e.g. PlayBridge's "frame rate matching").
    // Spaces are optional so the camelCase system message "setRequestedFrameRate" matches too.
    private val NOISE_MSG = Regex(
        """(requested\s*frame\s*rate|set\s*frame\s*rate)""",
        RegexOption.IGNORE_CASE
    )

    private fun isNoise(line: LogLine): Boolean {
        if (line.tag in NOISE_TAGS) return true
        // Tag-suffixed variants like "ViewRootImpl[MainActivity]" or "VRI[Activity]".
        if (NOISE_TAGS.any { line.tag.startsWith(it) }) return true
        return NOISE_MSG.containsMatchIn(line.message)
    }

    /**
     * Returns a snapshot of this process's recent log lines, oldest-first.
     *
     * The dump is bounded at the source with `logcat -t <tail>` — without this, a chatty
     * device returns the entire ring buffer (tens of thousands of lines), which then gets
     * filtered on the main thread during composition and freezes the UI. Keep [maxLines] modest.
     *
     * When [includeNoise] is false (default) framework/OEM noise is dropped, and we request a
     * larger tail so there are still ~[maxLines] real app lines left after filtering.
     */
    suspend fun snapshot(maxLines: Int = 1000, includeNoise: Boolean = false): List<LogLine> =
        withContext(Dispatchers.IO) {
        val tail = if (includeNoise) maxLines else (maxLines * 4).coerceAtMost(5000)
        val out = ArrayList<LogLine>(tail)
        var process: java.lang.Process? = null
        try {
            process = ProcessBuilder(
                "logcat", "-d", "-t", tail.toString(),
                "-v", "threadtime", "--pid", Process.myPid().toString()
            ).redirectErrorStream(false).start()

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    parse(line)?.let { out.add(it) }
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            out.add(
                LogLine(
                    timestamp = "",
                    level = Level.ERROR,
                    tag = "LogcatReader",
                    message = "Failed to read logcat: ${e.message}",
                    raw = "Failed to read logcat: ${e.message}"
                )
            )
        } finally {
            process?.destroy()
        }
        val filtered = if (includeNoise) out else out.filterNot { isNoise(it) }
        if (filtered.size > maxLines) filtered.subList(filtered.size - maxLines, filtered.size).toList()
        else filtered
    }

    /** Clears the log buffer. Returns true on success. */
    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        try {
            ProcessBuilder("logcat", "-c").start().waitFor()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun parse(line: String): LogLine? {
        if (line.isBlank()) return null
        // threadtime adds a pid/tid column we don't display; fall back to a forgiving parse.
        val tt = Regex(
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+([VDIWEAF])\s+(.+?):\s?(.*)$"""
        ).matchEntire(line)
        val m = tt ?: LINE_REGEX.matchEntire(line)
        if (m != null) {
            val (ts, lvl, tag, msg) = m.destructured
            return LogLine(
                timestamp = ts,
                level = Level.fromCode(lvl.first()),
                tag = tag.trim(),
                message = msg,
                raw = line,
            )
        }
        // Lines that don't match (e.g. multi-line stack traces) are kept as continuations.
        return LogLine(
            timestamp = "",
            level = Level.UNKNOWN,
            tag = "",
            message = line,
            raw = line,
        )
    }
}
