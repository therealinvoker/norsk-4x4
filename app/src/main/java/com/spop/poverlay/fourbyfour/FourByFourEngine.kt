package com.spop.poverlay.fourbyfour

import com.spop.poverlay.util.tickerFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** A phase boundary the user just crossed, emitted so the UI can fire a cue (vibrate/flash). */
data class PhaseTransition(val from: PhaseType, val to: PhaseType)

/**
 * Drives a 4x4 session forward in real time. Owns the elapsed-second clock and exposes the
 * derived [FourByFourState] as a [StateFlow]. All state derivation lives in [FourByFourState];
 * this class only manages the clock and start/pause/stop/skip controls.
 */
class FourByFourEngine(
    private val scope: CoroutineScope,
    config: ProtocolConfig,
) {
    var config: ProtocolConfig = config
        private set

    private var elapsedSec = 0
    private var tickerJob: Job? = null

    private val mutableState = MutableStateFlow(FourByFourState.idle(config))
    val state: StateFlow<FourByFourState> = mutableState.asStateFlow()

    private val mutableTransitions = MutableSharedFlow<PhaseTransition>(extraBufferCapacity = 4)
    val transitions: SharedFlow<PhaseTransition> = mutableTransitions.asSharedFlow()

    val isRunning get() = state.value.active && !state.value.paused

    /** Start (or restart) a session, optionally swapping in a fresh config. */
    fun start(config: ProtocolConfig = this.config) {
        this.config = config
        elapsedSec = 0
        mutableState.value = FourByFourState.running(config, elapsedSec = 0, paused = false)
        startTicker()
    }

    fun pause() {
        if (!state.value.active) return
        stopTicker()
        mutableState.value = mutableState.value.copy(paused = true)
    }

    fun resume() {
        if (!state.value.active || !state.value.paused) return
        mutableState.value = mutableState.value.copy(paused = false)
        startTicker()
    }

    fun togglePause() = if (state.value.paused) resume() else pause()

    /** Stop and reset to idle; the in-progress session is abandoned. */
    fun stop() {
        stopTicker()
        elapsedSec = 0
        mutableState.value = FourByFourState.idle(config)
    }

    /** Jump to the start of the next phase (useful to trim a long warm-up). */
    fun skipPhase() {
        if (!state.value.active) return
        val current = state.value
        elapsedSec = (current.elapsedSec - current.phaseElapsedSec + current.phaseDurationSec)
            .coerceAtMost(config.totalDurationSec)
        publish()
    }

    private fun startTicker() {
        stopTicker()
        tickerJob = scope.launch {
            tickerFlow(period = 1.seconds, initialDelay = 1.seconds).collect {
                elapsedSec += 1
                publish()
                if (state.value.finished) stopTicker()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun publish() {
        val previous = mutableState.value
        val next = FourByFourState.running(config, elapsedSec, paused = previous.paused)
        mutableState.value = next
        if (previous.phase != next.phase) {
            mutableTransitions.tryEmit(PhaseTransition(previous.phase, next.phase))
        }
    }
}
