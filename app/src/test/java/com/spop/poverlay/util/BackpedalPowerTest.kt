package com.spop.poverlay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackpedalPowerTest {

    @Test
    fun `zero cadence estimates zero power`() {
        val watts = estimateForwardPower(cadence = 0f, resistance = 30f)

        assertEquals(0f, watts, 0f)
    }

    @Test
    fun `easy recovery spin estimates a believable wattage`() {
        val watts = estimateForwardPower(cadence = 60f, resistance = 30f)

        // 60 * (0.4 + 1.6 * 0.30) = 52.8
        assertEquals(52.8f, watts, 0.01f)
    }

    @Test
    fun `higher resistance and cadence estimate more power`() {
        val easy = estimateForwardPower(cadence = 60f, resistance = 20f)
        val harder = estimateForwardPower(cadence = 90f, resistance = 50f)

        assertTrue(harder > easy)
    }

    @Test
    fun `estimate is capped at the max wattage`() {
        val watts = estimateForwardPower(cadence = 500f, resistance = 100f)

        assertEquals(300f, watts, 0f)
    }

    @Test
    fun `out-of-range resistance is clamped`() {
        val overRange = estimateForwardPower(cadence = 60f, resistance = 250f)
        val atMax = estimateForwardPower(cadence = 60f, resistance = 100f)

        assertEquals(atMax, overRange, 0f)
    }

    @Test
    fun `forward pedaling passes real power through untouched`() {
        val compensated = compensateBackpedalPower(power = 180f, cadence = 85f, resistance = 40f)

        assertEquals(180f, compensated, 0f)
    }

    @Test
    fun `coasting with no cadence stays at zero`() {
        val compensated = compensateBackpedalPower(power = 0f, cadence = 0f, resistance = 30f)

        assertEquals(0f, compensated, 0f)
    }

    @Test
    fun `backpedaling substitutes an estimated forward power`() {
        val compensated = compensateBackpedalPower(power = 0f, cadence = 60f, resistance = 30f)

        val expected = estimateForwardPower(cadence = 60f, resistance = 30f)
        assertEquals(expected, compensated, 0f)
        assertTrue(compensated > 0f)
    }
}
