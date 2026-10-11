package com.phairplay.diagnostic

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * Two plain-HTTP endpoints for reading the receiver's log without `adb`:
 *
 * - `:8001`         — the in-memory ring buffer (last [LogBuffer.MAX] lines, all levels).
 * - `:8001/?full=1` — the persistent log file (up to 5000 lines, no VERBOSE), which is the only
 *                     place a night of standby survives: the ring buffer holds ~40 minutes of a
 *                     TV that re-registers mDNS every 17 minutes (#19), so by morning the part
 *                     that mattered had scrolled out of it.
 * - `:8002`         — a live tail (chunked, never ends).
 */
object DiagnosticServer {
    const val PORT = 8001
    const val TAIL_PORT = 8002
    @Volatile private var started = false
    private var dumpSocket: ServerSocket? = null
    private var tailSocket: ServerSocket? = null

    /** True when the HTTP request line (`GET /?full=1 HTTP/1.1`) asks for the persistent file. */
    fun wantsFullLog(requestLine: String?): Boolean {
        val path = requestLine?.split(' ')?.getOrNull(1) ?: return false
        val query = path.substringAfter('?', "")
        return query.split('&').any { param ->
            val key = param.substringBefore('=')
            val value = param.substringAfter('=', "1")
            key == "full" && value !in setOf("0", "false", "no")
        }
    }

    fun stop() {
        started = false
        dumpSocket?.runCatching { close() }; dumpSocket = null
        tailSocket?.runCatching { close() }; tailSocket = null
    }

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        // Full dump server
        scope.launch(Dispatchers.IO) {
            runCatching {
                val server = ServerSocket(PORT).also { dumpSocket = it }
                Logger.i("DiagnosticServer dump on :$PORT (?full=1 for the file)  tail on :$TAIL_PORT")
                while (true) {
                    val client = server.accept()
                    launch(Dispatchers.IO) {
                        runCatching {
                            val full = wantsFullLog(readRequestLine(client))
                            val out = client.getOutputStream()
                            val body = (if (full) LogBuffer.readFile() else LogBuffer.dump()).toByteArray()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                            out.flush()
                            client.close()
                        }
                    }
                }
            }.onFailure { Logger.e("DiagnosticServer (dump) error", it) }
        }
        // Streaming tail server
        scope.launch(Dispatchers.IO) {
            runCatching {
                val server = ServerSocket(TAIL_PORT).also { tailSocket = it }
                while (true) {
                    val client = server.accept()
                    launch(Dispatchers.IO) {
                        runCatching {
                            val out = client.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                            out.flush()
                            var cursor = 0
                            while (true) {
                                val (lines, newSize) = LogBuffer.dumpFrom(cursor)
                                if (lines.isNotEmpty()) {
                                    val text = lines.joinToString("\n") + "\n"
                                    val bytes = text.toByteArray()
                                    out.write("${bytes.size.toString(16)}\r\n".toByteArray())
                                    out.write(bytes)
                                    out.write("\r\n".toByteArray())
                                    out.flush()
                                    cursor = newSize
                                }
                                Thread.sleep(100)
                            }
                        }.onFailure { client.runCatching { close() } }
                    }
                }
            }.onFailure { Logger.e("DiagnosticServer (tail) error", it) }
        }
    }

    /**
     * The first line of the HTTP request, or null when the client sent nothing within
     * [REQUEST_LINE_TIMEOUT_MS] (a bare `nc` probe) — the dump is then served as before.
     */
    private fun readRequestLine(client: Socket): String? = runCatching {
        client.soTimeout = REQUEST_LINE_TIMEOUT_MS
        BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1)).readLine()
    }.getOrNull().also { client.runCatching { soTimeout = 0 } }

    private const val REQUEST_LINE_TIMEOUT_MS = 2000
}
