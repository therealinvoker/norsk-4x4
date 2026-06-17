package com.spop.poverlay.history

import com.spop.poverlay.fourbyfour.FourByFourState
import com.spop.poverlay.fourbyfour.ProtocolConfig
import java.util.UUID

/**
 * Accumulates one telemetry sample per session-second. The overlay feeds it the latest sensor
 * readings on each engine tick via [record]; [finish] builds the immutable [WorkoutSession].
 *
 * Not thread-safe: drive it from a single coroutine (the overlay's recording loop).
 */
class SessionRecorder(
    private val config: ProtocolConfig,
    private val startedAtEpochMs: Long = System.currentTimeMillis(),
    private val id: String = UUID.randomUUID().toString(),
) {
    private val samples = ArrayList<WorkoutSample>()
    private var lastRecordedSec = -1

    val sampleCount get() = samples.size
    /** True once at least one hard work-interval sample exists (worth saving). */
    val hasWorkData get() = samples.any { it.phase == com.spop.poverlay.fourbyfour.PhaseType.Work }

    /** Returns true if a new per-second sample was stored (false for duplicate/inactive ticks). */
    fun record(state: FourByFourState, power: Float, cadence: Float, resistance: Float, hr: Int? = null): Boolean {
        if (!state.active || state.paused) return false
        // One sample per whole second of session time; ignore duplicate ticks.
        if (state.elapsedSec == lastRecordedSec) return false
        lastRecordedSec = state.elapsedSec
        samples += WorkoutSample(
            tSec = state.elapsedSec,
            power = power.coerceAtLeast(0f),
            cadence = cadence.coerceAtLeast(0f),
            resistance = resistance.coerceAtLeast(0f),
            phase = state.phase,
            intervalIndex = state.intervalIndex,
            hr = hr,
        )
        return true
    }

    fun finish(completed: Boolean): WorkoutSession {
        val snapshot = samples.toList()
        return WorkoutSession(
            id = id,
            startedAtEpochMs = startedAtEpochMs,
            config = config,
            durationSec = snapshot.lastOrNull()?.tSec?.plus(1) ?: 0,
            completed = completed,
            samples = snapshot,
            summary = WorkoutSession.summarize(snapshot),
        )
    }
}
