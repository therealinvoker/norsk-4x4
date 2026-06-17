package com.spop.poverlay.history

import com.spop.poverlay.fourbyfour.PhaseType
import com.spop.poverlay.fourbyfour.ProtocolConfig

/**
 * A single ~1 Hz telemetry sample taken during a session, tagged with the phase it belongs to.
 * [hr] is reserved for a future Bluetooth heart-rate strap and is null for power-only sessions.
 */
data class WorkoutSample(
    val tSec: Int,
    val power: Float,
    val cadence: Float,
    val resistance: Float,
    val phase: PhaseType,
    val intervalIndex: Int,
    val hr: Int? = null,
)

/** Aggregate metrics computed once when a session ends. */
data class WorkoutSummary(
    val totalOutputKj: Float,
    val workOutputKj: Float,
    val avgPower: Float,
    val avgWorkPower: Float,
    val maxPower: Float,
    val avgCadence: Float,
    val avgResistance: Float,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
)

/**
 * A completed (or abandoned) 4x4 ride. Persisted as JSON. [samples] are the full time series so
 * the detail screen and ride-vs-best ghost line can be reconstructed.
 */
data class WorkoutSession(
    val id: String,
    val startedAtEpochMs: Long,
    val config: ProtocolConfig,
    val durationSec: Int,
    val completed: Boolean,
    val samples: List<WorkoutSample>,
    val summary: WorkoutSummary,
) {
    /**
     * Cumulative output (kJ) at the end of each session-second, indexed by second. Used to align a
     * live ride against this session for the "vs best" delta. Index i = kJ produced through second i.
     */
    fun cumulativeOutputKjBySecond(): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val lastSec = samples.maxOf { it.tSec }
        val out = FloatArray(lastSec + 1)
        var runningJoules = 0.0
        var sampleIdx = 0
        for (sec in 0..lastSec) {
            while (sampleIdx < samples.size && samples[sampleIdx].tSec == sec) {
                runningJoules += samples[sampleIdx].power
                sampleIdx++
            }
            out[sec] = (runningJoules / 1000.0).toFloat()
        }
        return out
    }

    /** Average power (W) of each work interval, ordered by interval index. */
    fun workIntervalAverages(): List<Float> =
        samples.asSequence()
            .filter { it.phase == PhaseType.Work }
            .groupBy { it.intervalIndex }
            .toSortedMap()
            .map { (_, group) -> group.map { it.power }.average().toFloat() }

    companion object {
        fun summarize(samples: List<WorkoutSample>): WorkoutSummary {
            if (samples.isEmpty()) {
                return WorkoutSummary(0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
            // Each sample represents ~1 second, so power (W) summed over samples / 1000 = kJ.
            val totalJoules = samples.sumOf { it.power.toDouble() }
            val workSamples = samples.filter { it.phase == PhaseType.Work }
            val workJoules = workSamples.sumOf { it.power.toDouble() }
            val hrValues = samples.mapNotNull { it.hr }
            return WorkoutSummary(
                totalOutputKj = (totalJoules / 1000.0).toFloat(),
                workOutputKj = (workJoules / 1000.0).toFloat(),
                avgPower = samples.map { it.power }.average().toFloat(),
                avgWorkPower = if (workSamples.isEmpty()) 0f else workSamples.map { it.power }.average().toFloat(),
                maxPower = samples.maxOf { it.power },
                avgCadence = samples.map { it.cadence }.average().toFloat(),
                avgResistance = samples.map { it.resistance }.average().toFloat(),
                avgHr = if (hrValues.isEmpty()) null else hrValues.average().toInt(),
                maxHr = hrValues.maxOrNull(),
            )
        }
    }
}
