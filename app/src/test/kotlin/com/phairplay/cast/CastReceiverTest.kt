package com.phairplay.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastReceiverTest {

    @Test
    fun `blank Cast app ID is not configured`() {
        assertFalse(CastReceiver.isConfigured(""))
        assertFalse(CastReceiver.isConfigured("   "))
    }

    @Test
    fun `placeholder Cast app IDs are not configured`() {
        assertFalse(CastReceiver.isConfigured("TODO_REGISTER_YOUR_CAST_APP_ID"))
        assertFalse(CastReceiver.isConfigured("00000000"))
    }

    @Test
    fun `registered Cast app ID is configured`() {
        assertTrue(CastReceiver.isConfigured("ABCD1234"))
        assertTrue(CastReceiver.isConfigured("  ABCD1234  "))
    }
}
