package com.spop.poverlay.sensor.interfaces

import com.spop.poverlay.util.calculateSpeedFromPelotonV1Power
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SensorInterface {
    val power: Flow<Float>
    val cadence: Flow<Float>
    val resistance: Flow<Float>
    val speed
        get() = power.map(::calculateSpeedFromPelotonV1Power)

    /**
     * Re-establish the underlying sensor connection after it has stalled (e.g. following a sleep),
     * so the rider doesn't have to power-cycle the bike. Default is a no-op for sources that can't
     * or needn't reconnect (emulator); the Peloton interfaces rebind their service.
     */
    fun reconnect() {}
}