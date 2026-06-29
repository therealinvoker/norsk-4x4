package com.spop.poverlay.hr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateMeasurementParserTest {

    @Test
    fun `uint8 format reads the single bpm byte`() {
        val bpm = parseHeartRate(byteArrayOf(0x00, 0x48))

        assertEquals(72, bpm)
    }

    @Test
    fun `uint16 format reads little-endian bpm`() {
        val bpm = parseHeartRate(byteArrayOf(0x01, 0x2C, 0x01))

        assertEquals(300, bpm)
    }

    @Test
    fun `null payload returns null`() {
        val bpm = parseHeartRate(null)

        assertNull(bpm)
    }

    @Test
    fun `empty payload returns null`() {
        val bpm = parseHeartRate(byteArrayOf())

        assertNull(bpm)
    }

    @Test
    fun `single byte payload returns null`() {
        val bpm = parseHeartRate(byteArrayOf(0x00))

        assertNull(bpm)
    }
}
