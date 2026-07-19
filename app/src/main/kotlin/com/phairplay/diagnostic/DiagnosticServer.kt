package com.phairplay.diagnostic

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.ServerSocket

object DiagnosticServer {
    const val PORT = 8001
    const val TAIL_PORT = 8002
    @Volatile private var started = false
    private var dumpSocket: ServerSocket? = null
    private var tailSocket: ServerSocket? = null

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
                Logger.i("DiagnosticServer dump on :$PORT  tail on :$TAIL_PORT")
                while (true) {
                    val client = server.accept()
                    launch(Dispatchers.IO) {
                        runCatching {
                            val out = client.getOutputStream()
                            val body = LogBuffer.dump().toByteArray()
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
}
