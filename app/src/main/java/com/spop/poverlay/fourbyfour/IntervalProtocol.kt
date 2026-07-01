package com.spop.poverlay.fourbyfour

import kotlin.math.roundToInt

/**
 * The phases of a Norwegian 4x4 interval session.
 *
 * The protocol (NTNU/CERG): a warm-up, then [ProtocolConfig.intervals] repeats of a hard
 * work interval followed by an active recovery, then a cool-down. Recovery happens *between*
 * work intervals, so there is no recovery after the final interval.
 */
enum class PhaseType {
    WarmUp,
    Work,
    Recovery,
    CoolDown,
    Finished;

    val isHard get() = this == Work

    /** Short label shown on the overlay banner. */
    fun label(intervalIndex: Int, totalIntervals: Int): String = when (this) {
        WarmUp -> "WARM UP"
        Work -> "INTERVAL $intervalIndex/$totalIntervals"
        Recovery -> "RECOVERY"
        CoolDown -> "COOL DOWN"
        Finished -> "DONE"
    }

    /** Compact label for the minimized stat strip, where horizontal space is tight. */
    fun shortLabel(intervalIndex: Int, totalIntervals: Int): String = when (this) {
        WarmUp -> "WARM"
        Work -> "INT $intervalIndex/$totalIntervals"
        Recovery -> "REC"
        CoolDown -> "COOL"
        Finished -> "DONE"
    }
}

/**
 * Configurable durations for a 4x4 session. Defaults match the CERG recommendation:
 * 10 min warm-up, 4x4 min work at high intensity, 3 min active recovery between intervals,
 * 5 min cool-down.
 *
 * [hardTargetWatts] / [easyTargetWatts] are optional power targets used to color-code effort
 * (the bike exposes power, not heart rate). Null means "guide by phase only, don't judge effort".
 */
data class ProtocolConfig(
    val warmUpSec: Int = DEFAULT_WARMUP_SEC,
    val workSec: Int = DEFAULT_WORK_SEC,
    val recoverySec: Int = DEFAULT_RECOVERY_SEC,
    val coolDownSec: Int = DEFAULT_COOLDOWN_SEC,
    val intervals: Int = DEFAULT_INTERVALS,
    val hardTargetWatts: Int? = null,
    val easyTargetWatts: Int? = null,
    val age: Int? = null,
) {
    init {
        require(intervals >= 1) { "A 4x4 session needs at least one interval" }
    }

    /** Age-estimated max heart rate (Tanaka 2001: 208 − 0.7 × age); null when age is unset. */
    val estimatedHrMax: Int? get() = age?.let { (208 - 0.7 * it).roundToInt() }

    /** Low edge of the 4x4 work-interval target band (85% HRmax); null when age is unset. */
    val workHrLow: Int? get() = estimatedHrMax?.let { (0.85 * it).roundToInt() }

    /** High edge of the 4x4 work-interval target band (95% HRmax); null when age is unset. */
    val workHrHigh: Int? get() = estimatedHrMax?.let { (0.95 * it).roundToInt() }

    /** Ordered, non-overlapping segments that make up the full session. */
    val timeline: List<PhaseSegment> get() = buildTimeline()

    val totalDurationSec: Int get() = timeline.sumOf { it.durationSec }

    private fun buildTimeline(): List<PhaseSegment> {
        val segments = mutableListOf<PhaseSegment>()
        var offset = 0

        fun add(type: PhaseType, durationSec: Int, intervalIndex: Int) {
            if (durationSec <= 0) return
            segments += PhaseSegment(type, durationSec, intervalIndex, offset)
            offset += durationSec
        }

        add(PhaseType.WarmUp, warmUpSec, 0)
        for (i in 1..intervals) {
            add(PhaseType.Work, workSec, i)
            if (i < intervals) add(PhaseType.Recovery, recoverySec, i)
        }
        add(PhaseType.CoolDown, coolDownSec, 0)
        return segments
    }

    companion object {
        const val DEFAULT_WARMUP_SEC = 10 * 60
        const val DEFAULT_WORK_SEC = 4 * 60
        const val DEFAULT_RECOVERY_SEC = 3 * 60
        const val DEFAULT_COOLDOWN_SEC = 5 * 60
        const val DEFAULT_INTERVALS = 4
    }
}

/**
 * One contiguous block of the session.
 *
 * @param intervalIndex 1-based work-interval number for [PhaseType.Work] (and the recovery that
 *   follows it); 0 for warm-up / cool-down.
 * @param startOffsetSec seconds from the start of the session at which this segment begins.
 */
data class PhaseSegment(
    val type: PhaseType,
    val durationSec: Int,
    val intervalIndex: Int,
    val startOffsetSec: Int,
) {
    val endOffsetSec get() = startOffsetSec + durationSec
}
