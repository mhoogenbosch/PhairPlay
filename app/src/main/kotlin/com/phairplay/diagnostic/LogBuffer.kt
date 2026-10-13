package com.phairplay.diagnostic

import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer + persistent log file behind the diagnostic endpoints.
 *
 * The file is what survives a process death or a reboot, so it has to hold more than one
 * session's worth of chatter: a 14-minute mirror session writes ~900 lines, and with the old
 * 1000-line cap it wiped every trace of an incident that happened minutes before (2026-09-21:
 * the receiver had been silently invisible for an unknown time; by the time the log was read
 * the pre-reboot part was gone). Two changes:
 * - the file keeps [FILE_MAX_LINES] lines and trims down to [FILE_TRIM_TO] (hysteresis: the
 *   old code re-read and rewrote the whole file on *every* line once it was full);
 * - VERBOSE lines (per-packet noise such as "ignoring payload type 5") stay in the ring buffer
 *   for the live tail but are not written to the file, which was ~40% of it.
 *
 * A third (2026-10-13, #19): during mirroring the periodic stats and the sender's `/feedback`
 * keep-alive still filled the file. A 5000-line `?full=1` dump spanned only ~2 hours, ~80% of it
 * `RTSP POST /feedback` (every 2 s) and `Audio/Video stats` / `Mirror: sender` lines, so the
 * standby night we needed was gone again. See [fileDecision]: the keep-alive stays out of the file,
 * the stats go in at most once per [STATS_FILE_INTERVAL_MS] per kind. The live tail keeps all of it.
 */
object LogBuffer {
    private const val MAX = 500
    private const val FILE_MAX_LINES = 5000
    private const val FILE_TRIM_TO = 4000
    internal const val STATS_FILE_INTERVAL_MS = 60_000L

    /** Sender keep-alive: no information beyond "a session is open", which other lines already show. */
    private val RING_ONLY = listOf("RTSP POST /feedback")

    /** Periodic stats: useful as a trend, so one per minute per kind reaches the file. */
    private val THROTTLED = listOf("Audio stats:", "Video stats:", "Mirror: sender")
    private val lastFileWrite = HashMap<String, Long>()
    private val buf = mutableListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null
    private var fileLineCount = 0

    fun init(filesDir: File) {
        logFile = File(filesDir, "phairplay.log").also { f ->
            fileLineCount = if (f.exists()) f.readLines().size else 0
        }
    }

    fun add(msg: String, toFile: Boolean = true) {
        val line = "${fmt.format(Date())} $msg"
        synchronized(buf) {
            if (buf.size >= MAX) buf.removeAt(0)
            buf.add(line)
        }
        if (!toFile) return
        logFile?.let { f ->
            try {
                synchronized(f) {
                    f.appendText(line + "\n")
                    fileLineCount++
                    if (fileLineCount > FILE_MAX_LINES) {
                        val trimmed = f.readLines().takeLast(FILE_TRIM_TO)
                        f.writeText(trimmed.joinToString("\n") + "\n")
                        fileLineCount = trimmed.size
                    }
                }
            } catch (e: Exception) { /* non-fatal */ }
        }
    }

    /**
     * Whether [message] (without timestamp/level prefix) goes to the persistent file at [nowMs].
     * Records the write for throttled kinds, so call it once per line.
     */
    internal fun fileDecision(message: String, nowMs: Long): Boolean {
        if (RING_ONLY.any { message.startsWith(it) }) return false
        val kind = THROTTLED.firstOrNull { message.startsWith(it) } ?: return true
        synchronized(lastFileWrite) {
            val last = lastFileWrite[kind]
            if (last != null && nowMs - last < STATS_FILE_INTERVAL_MS) return false
            lastFileWrite[kind] = nowMs
            return true
        }
    }

    internal fun resetThrottle() = synchronized(lastFileWrite) { lastFileWrite.clear() }

    fun dump(): String = synchronized(buf) { buf.joinToString("\n") }

    fun size(): Int = synchronized(buf) { buf.size }

    fun dumpFrom(fromIndex: Int): Pair<List<String>, Int> = synchronized(buf) {
        val lines = if (fromIndex < buf.size) buf.subList(fromIndex, buf.size).toList() else emptyList()
        Pair(lines, buf.size)
    }

    fun readFile(): String = logFile?.takeIf { it.exists() }?.readText() ?: ""

    fun clearFile() { logFile?.delete() }

    class Tree : Timber.Tree() {
        private val levels = mapOf(2 to "V", 3 to "D", 4 to "I", 5 to "W", 6 to "E")
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val line = "[${levels[priority] ?: "?"}/${tag ?: "?"}] $message" +
                (t?.let { " | ${it.javaClass.simpleName}: ${it.message}\n${it.stackTraceToString()}" } ?: "")
            add(line, toFile = priority > Log.VERBOSE && fileDecision(message, System.currentTimeMillis()))
        }
    }
}
