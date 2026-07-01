package com.spop.poverlay.sensor.interfaces

import com.spop.poverlay.util.compensateBackpedalPower
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.shareIn

/**
 * Wraps another [SensorInterface] and, while the rider is backpedaling, substitutes an estimated
 * forward-equivalent power for the bike's real (~0 W) reading.
 *
 * The Bike+ load cell only measures torque from forward pedaling, so a backward spin reports real
 * cadence but ~0 W — which drags the power gauge, graph, speed, and recorded output all to zero
 * (speed/graph are pure functions of power). Detecting the unambiguous "cadence present, power ~0"
 * signature and synthesizing a plausible easy-spin wattage makes recovery backpedaling behave like
 * forward pedaling everywhere, because every downstream consumer reads [power].
 *
 * [cadence] and [resistance] are passed through untouched — only [power] is compensated.
 */
class BackpedalCompensatingSensorInterface(
    private val delegate: SensorInterface,
) : SensorInterface {
    companion object {
        // Keep the shared upstream alive briefly after the last subscriber to avoid restarting the
        // underlying sensor threads across recompositions.
        private const val ShareStopTimeoutMs = 5_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override val cadence: Flow<Float> get() = delegate.cadence
    override val resistance: Flow<Float> get() = delegate.resistance

    override val power: Flow<Float> = combine(
        delegate.power,
        delegate.cadence,
        delegate.resistance,
    ) { power, cadence, resistance ->
        compensateBackpedalPower(power, cadence, resistance)
    }.shareIn(scope, SharingStarted.WhileSubscribed(ShareStopTimeoutMs), replay = 1)

    fun stop() {
        scope.cancel()
    }
}
