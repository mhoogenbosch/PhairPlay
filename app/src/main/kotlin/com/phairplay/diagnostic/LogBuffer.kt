package com.phairplay.diagnostic

import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogBuffer {
    private const val MAX = 500
    private const val FILE_MAX_LINES = 1000
    private val buf = mutableListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null
    private var fileLineCount = 0

    fun init(filesDir: File) {
        logFile = File(filesDir, "phairplay.log").also { f ->
            fileLineCount = if (f.exists()) f.readLines().size else 0
        }
    }

    fun add(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        synchronized(buf) {
            if (buf.size >= MAX) buf.removeAt(0)
            buf.add(line)
        }
        logFile?.let { f ->
            try {
                f.appendText(line + "\n")
                fileLineCount++
                if (fileLineCount > FILE_MAX_LINES) {
                    val trimmed = f.readLines().takeLast(FILE_MAX_LINES)
                    f.writeText(trimmed.joinToString("\n") + "\n")
                    fileLineCount = trimmed.size
                }
            } catch (e: Exception) { /* non-fatal */ }
        }
    }

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
            add(line)
        }
    }
}
