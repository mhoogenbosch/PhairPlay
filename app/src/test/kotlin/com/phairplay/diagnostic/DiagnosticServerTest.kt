package com.phairplay.diagnostic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DiagnosticServerTest — the request-line parsing that decides whether `:8001` serves the
 * in-memory ring buffer or the persistent file.
 *
 * WHY: the file is the only place an overnight incident survives; a parsing slip would silently
 * hand out the 500-line ring buffer again, and nobody would notice until the next standby log
 * came back without the night in it.
 */
class DiagnosticServerTest {

    @Test
    fun `plain GET serves the ring buffer`() {
        assertFalse(DiagnosticServer.wantsFullLog("GET / HTTP/1.1"))
        assertFalse(DiagnosticServer.wantsFullLog("GET /log HTTP/1.1"))
    }

    @Test
    fun `full=1 serves the file`() {
        assertTrue(DiagnosticServer.wantsFullLog("GET /?full=1 HTTP/1.1"))
        assertTrue(DiagnosticServer.wantsFullLog("GET /?full=true HTTP/1.0"))
    }

    @Test
    fun `bare full flag serves the file`() {
        assertTrue(DiagnosticServer.wantsFullLog("GET /?full HTTP/1.1"))
        assertTrue(DiagnosticServer.wantsFullLog("GET /?lines=all&full HTTP/1.1"))
    }

    @Test
    fun `full=0 serves the ring buffer`() {
        assertFalse(DiagnosticServer.wantsFullLog("GET /?full=0 HTTP/1.1"))
        assertFalse(DiagnosticServer.wantsFullLog("GET /?full=false HTTP/1.1"))
    }

    @Test
    fun `other parameters do not count`() {
        assertFalse(DiagnosticServer.wantsFullLog("GET /?fullscreen=1 HTTP/1.1"))
        assertFalse(DiagnosticServer.wantsFullLog("GET /?x=full HTTP/1.1"))
    }

    @Test
    fun `no request line (bare socket probe) serves the ring buffer`() {
        assertFalse(DiagnosticServer.wantsFullLog(null))
        assertFalse(DiagnosticServer.wantsFullLog(""))
        assertFalse(DiagnosticServer.wantsFullLog("GET"))
    }
}
