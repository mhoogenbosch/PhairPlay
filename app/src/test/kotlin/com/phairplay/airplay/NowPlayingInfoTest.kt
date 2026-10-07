package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spotify decorates the AirPlay artist with a music-video marker; the now-playing card drops it. */
class NowPlayingInfoTest {

    @Test
    fun `strips the Spotify video marker`() {
        assertEquals("Lady Gaga, Bruno Mars", NowPlayingInfo.cleanArtist("Lady Gaga, Bruno Mars • Video"))
        assertEquals("OneRepublic", NowPlayingInfo.cleanArtist("OneRepublic • Video beschikbaar"))
    }

    @Test
    fun `leaves ordinary artists alone`() {
        assertEquals("Video Kids", NowPlayingInfo.cleanArtist("Video Kids"))
        assertEquals("A • B", NowPlayingInfo.cleanArtist("A • B"))
    }

    @Test
    fun `null and blank become null`() {
        assertNull(NowPlayingInfo.cleanArtist(null))
        assertNull(NowPlayingInfo.cleanArtist("  "))
    }
}
