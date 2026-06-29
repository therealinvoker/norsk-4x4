package com.spop.poverlay.fourbyfour

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStopTest {

    private val timeout = 15 * 60 * 1000L
    private val now = 1_000_000_000L

    @Test
    fun `inactive session never auto-stops even when long idle`() {
        val result = shouldAutoStop(
            active = false,
            lastActiveMomentMs = now - 60 * 60 * 1000L,
            nowMs = now,
            timeoutMs = timeout,
        )

        assertFalse(result)
    }

    @Test
    fun `active session below timeout does not auto-stop`() {
        val result = shouldAutoStop(
            active = true,
            lastActiveMomentMs = now - 14 * 60 * 1000L,
            nowMs = now,
            timeoutMs = timeout,
        )

        assertFalse(result)
    }

    @Test
    fun `active session at timeout auto-stops`() {
        val result = shouldAutoStop(
            active = true,
            lastActiveMomentMs = now - timeout,
            nowMs = now,
            timeoutMs = timeout,
        )

        assertTrue(result)
    }

    @Test
    fun `active session beyond timeout auto-stops`() {
        val result = shouldAutoStop(
            active = true,
            lastActiveMomentMs = now - 20 * 60 * 1000L,
            nowMs = now,
            timeoutMs = timeout,
        )

        assertTrue(result)
    }
}
