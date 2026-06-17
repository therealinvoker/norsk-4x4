package com.spop.poverlay.fourbyfour

/**
 * Immutable snapshot of a 4x4 session at a given moment, derived purely from elapsed time and
 * [ProtocolConfig]. Everything the UI needs to render the banner/progress is here.
 */
data class FourByFourState(
    val active: Boolean,
    val paused: Boolean,
    val finished: Boolean,
    val phase: PhaseType,
    /** 1-based current work-interval number (or the one a recovery follows); 0 otherwise. */
    val intervalIndex: Int,
    val totalIntervals: Int,
    val phaseElapsedSec: Int,
    val phaseDurationSec: Int,
    val elapsedSec: Int,
    val totalDurationSec: Int,
    /** The phase that comes next, or [PhaseType.Finished] if this is the last one. */
    val nextPhase: PhaseType,
) {
    val phaseRemainingSec: Int get() = (phaseDurationSec - phaseElapsedSec).coerceAtLeast(0)
    val overallProgress: Float
        get() = if (totalDurationSec <= 0) 0f else (elapsedSec.toFloat() / totalDurationSec).coerceIn(0f, 1f)
    val phaseProgress: Float
        get() = if (phaseDurationSec <= 0) 0f else (phaseElapsedSec.toFloat() / phaseDurationSec).coerceIn(0f, 1f)

    val phaseLabel: String get() = phase.label(intervalIndex, totalIntervals)
    val phaseShortLabel: String get() = phase.shortLabel(intervalIndex, totalIntervals)

    companion object {
        /** State before a session is started. */
        fun idle(config: ProtocolConfig) = FourByFourState(
            active = false,
            paused = false,
            finished = false,
            phase = PhaseType.WarmUp,
            intervalIndex = 0,
            totalIntervals = config.intervals,
            phaseElapsedSec = 0,
            phaseDurationSec = config.warmUpSec,
            elapsedSec = 0,
            totalDurationSec = config.totalDurationSec,
            nextPhase = config.timeline.getOrNull(1)?.type ?: PhaseType.Finished,
        )

        /**
         * Derive the running state for a given [elapsedSec] into the session. Pure and total:
         * any elapsed value maps to a well-defined snapshot, with [elapsedSec] >= total -> finished.
         */
        fun running(config: ProtocolConfig, elapsedSec: Int, paused: Boolean): FourByFourState {
            val total = config.totalDurationSec
            val clamped = elapsedSec.coerceIn(0, total)
            if (clamped >= total) {
                val last = config.timeline.last()
                return FourByFourState(
                    active = false,
                    paused = false,
                    finished = true,
                    phase = PhaseType.Finished,
                    intervalIndex = config.intervals,
                    totalIntervals = config.intervals,
                    phaseElapsedSec = last.durationSec,
                    phaseDurationSec = last.durationSec,
                    elapsedSec = total,
                    totalDurationSec = total,
                    nextPhase = PhaseType.Finished,
                )
            }

            val index = config.timeline.indexOfLast { clamped >= it.startOffsetSec }
            val segment = config.timeline[index]
            val next = config.timeline.getOrNull(index + 1)?.type ?: PhaseType.Finished
            return FourByFourState(
                active = true,
                paused = paused,
                finished = false,
                phase = segment.type,
                intervalIndex = segment.intervalIndex,
                totalIntervals = config.intervals,
                phaseElapsedSec = clamped - segment.startOffsetSec,
                phaseDurationSec = segment.durationSec,
                elapsedSec = clamped,
                totalDurationSec = total,
                nextPhase = next,
            )
        }
    }
}
