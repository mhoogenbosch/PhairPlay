package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream

/** RTSP header names are case-insensitive; a lower-case Content-Length must still frame the body. */
class RtspRequestReaderTest {

    private val reader = RtspRequestReader(maxMessageBytes = 1024 * 1024, maxPhotoBytes = 1024 * 1024)

    @Test
    fun `lower-case content-length still reads the body and keeps the stream in sync`() {
        val wire = "SET_PARAMETER rtsp://x RTSP/1.0\r\ncseq: 7\r\ncontent-length: 5\r\n\r\nhello" +
            "OPTIONS * RTSP/1.0\r\nCSeq: 8\r\n\r\n"
        val input = ByteArrayInputStream(wire.toByteArray())

        val first = reader.read(input)
        assertNotNull(first)
        assertEquals("hello", first!!.body)
        assertEquals("7", first.headers["CSeq"])

        val second = reader.read(input)
        assertEquals("OPTIONS", second!!.method)
        assertEquals("8", second.headers["cseq"])
    }
}
