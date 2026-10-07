package com.phairplay.airplay.handshake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decides when the mirror decoder must be rebound to the streaming surface. The bug this guards:
 * `SurfaceHolder.getSurface()` returns the SAME object after Home/screensaver, so an identity check
 * alone missed a destroyed-and-recreated surface and the decoder kept rendering into a dead one.
 */
class MirrorSurfaceRebuildTest {

    private val s = Any()
    private val other = Any()

    @Test
    fun `steady state with a live decoder does nothing`() {
        assertFalse(surfaceNeedsRebuild(s, s, liveValid = true, hasDecoder = true, hasConfig = true))
    }

    @Test
    fun `surface lost releases the decoder`() {
        assertTrue(surfaceNeedsRebuild(null, s, liveValid = false, hasDecoder = true, hasConfig = true))
    }

    @Test
    fun `still gone after release does nothing`() {
        assertFalse(surfaceNeedsRebuild(null, null, liveValid = false, hasDecoder = false, hasConfig = true))
    }

    @Test
    fun `different surface object rebuilds`() {
        assertTrue(surfaceNeedsRebuild(other, s, liveValid = true, hasDecoder = true, hasConfig = true))
    }

    @Test
    fun `same object turned invalid releases a running decoder once`() {
        assertTrue(surfaceNeedsRebuild(s, s, liveValid = false, hasDecoder = true, hasConfig = true))
        assertFalse(surfaceNeedsRebuild(s, s, liveValid = false, hasDecoder = false, hasConfig = true))
    }

    @Test
    fun `same object valid again without decoder rebuilds from the cached config`() {
        assertTrue(surfaceNeedsRebuild(s, s, liveValid = true, hasDecoder = false, hasConfig = true))
    }

    @Test
    fun `no rebuild loop before the first SPS-PPS arrived`() {
        assertFalse(surfaceNeedsRebuild(s, s, liveValid = true, hasDecoder = false, hasConfig = false))
    }
}
