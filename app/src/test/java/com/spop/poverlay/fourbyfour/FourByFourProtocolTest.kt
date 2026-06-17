package com.spop.poverlay.fourbyfour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FourByFourProtocolTest {

    private val config = ProtocolConfig()

    @Test
    fun `default protocol totals 40 minutes`() {
        assertEquals(2400, config.totalDurationSec)
    }

    @Test
    fun `recovery happens between intervals only`() {
        val recoveries = config.timeline.count { it.type == PhaseType.Recovery }
        val works = config.timeline.count { it.type == PhaseType.Work }
        assertEquals(4, works)
        assertEquals(3, recoveries)
        assertEquals(PhaseType.Work, config.timeline.last { it.intervalIndex > 0 }.type)
    }

    @Test
    fun `warmup is the first phase`() {
        val state = FourByFourState.running(config, elapsedSec = 0, paused = false)
        assertEquals(PhaseType.WarmUp, state.phase)
        assertEquals(PhaseType.Work, state.nextPhase)
        assertEquals(600, state.phaseRemainingSec)
        assertTrue(state.active)
    }

    @Test
    fun `first work interval starts after warmup`() {
        val state = FourByFourState.running(config, elapsedSec = 600, paused = false)
        assertEquals(PhaseType.Work, state.phase)
        assertEquals(1, state.intervalIndex)
        assertEquals(0, state.phaseElapsedSec)
        assertEquals(PhaseType.Recovery, state.nextPhase)
    }

    @Test
    fun `recovery follows first interval and keeps its interval index`() {
        val state = FourByFourState.running(config, elapsedSec = 900, paused = false)
        assertEquals(PhaseType.Recovery, state.phase)
        assertEquals(1, state.intervalIndex)
    }

    @Test
    fun `fourth interval has no following recovery`() {
        val state = FourByFourState.running(config, elapsedSec = 1860, paused = false)
        assertEquals(PhaseType.Work, state.phase)
        assertEquals(4, state.intervalIndex)
        assertEquals(PhaseType.CoolDown, state.nextPhase)
    }

    @Test
    fun `cooldown is the final timed phase`() {
        val state = FourByFourState.running(config, elapsedSec = 2100, paused = false)
        assertEquals(PhaseType.CoolDown, state.phase)
        assertEquals(PhaseType.Finished, state.nextPhase)
    }

    @Test
    fun `session finishes at total duration and clamps beyond`() {
        val atEnd = FourByFourState.running(config, elapsedSec = 2400, paused = false)
        assertTrue(atEnd.finished)
        assertFalse(atEnd.active)
        assertEquals(1f, atEnd.overallProgress, 0.001f)

        val beyond = FourByFourState.running(config, elapsedSec = 5000, paused = false)
        assertTrue(beyond.finished)
        assertEquals(2400, beyond.elapsedSec)
    }

    @Test
    fun `custom config rebuilds timeline`() {
        val custom = ProtocolConfig(warmUpSec = 60, workSec = 120, recoverySec = 60, coolDownSec = 60, intervals = 2)
        // 60 + (120*2) + (60*1) + 60
        assertEquals(420, custom.totalDurationSec)
        val state = FourByFourState.running(custom, elapsedSec = 60, paused = false)
        assertEquals(PhaseType.Work, state.phase)
        assertEquals(1, state.intervalIndex)
    }
}
