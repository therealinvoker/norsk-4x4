package com.spop.poverlay.overlay.composables

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.fourbyfour.FourByFourState
import com.spop.poverlay.fourbyfour.PhaseType
import com.spop.poverlay.fourbyfour.ProtocolConfig

private val WarmUpColor = Color(0xFFF9A825)
private val WorkColor = Color(0xFFD9182B)
private val RecoveryColor = Color(0xFF1E88E5)
private val CoolDownColor = Color(0xFF26A69A)
private val FinishedColor = Color(0xFFFFC107)
private val PauseColor = Color(0xFF455A64)

private fun PhaseType.accent() = when (this) {
    PhaseType.WarmUp -> WarmUpColor
    PhaseType.Work -> WorkColor
    PhaseType.Recovery -> RecoveryColor
    PhaseType.CoolDown -> CoolDownColor
    PhaseType.Finished -> FinishedColor
}

private fun formatMmSs(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * The live 4x4 banner shown on the overlay while a session is active (or just finished).
 * Tap or the inline button toggles pause (which only stops the timer, keeping the banner up);
 * long-press stops the session entirely.
 */
@Composable
fun FourByFourBanner(
    state: FourByFourState,
    config: ProtocolConfig,
    currentPower: Float,
    currentHr: Int?,
    vsBestKj: Float?,
    bestIntervalAvgWatts: Float?,
    currentIntervalAvgWatts: Float?,
    intervalTrace: List<Float>,
    hrTrace: List<Int>,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onPlayPause: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = state.phase.accent()
    Column(
        modifier = modifier
            .wrapContentWidth()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            }
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.width(150.dp)) {
                Text(
                    text = state.phaseLabel,
                    color = accent,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                )
                if (!state.finished) {
                    Text(
                        text = "NEXT: ${nextLabel(state)}",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
                if (currentHr != null) {
                    HeartRateChip(currentHr)
                }
            }

            Spacer(Modifier.width(16.dp))

            if (state.finished) {
                Text("✔", color = FinishedColor, fontSize = 40.sp, fontWeight = FontWeight.Black)
            } else {
                CountdownOrPaused(state)
            }

            Spacer(Modifier.width(16.dp))

            TargetIndicator(
                modifier = Modifier.width(120.dp),
                state = state,
                config = config,
                currentPower = currentPower,
            )

            Spacer(Modifier.width(12.dp))

            // These have their own clickable regions so taps hit the buttons, not the banner-level
            // tap handler. While paused we surface Resume + Reset; while running, just Pause.
            PauseControls(state = state, onPlayPause = onPlayPause, onReset = onReset)
        }

        Spacer(Modifier.height(6.dp))
        // During a work interval, pace against the best-ever interval average (the dashed line).
        // Otherwise fall back to the cumulative kJ-vs-best chip.
        if (state.phase.isHard && state.active) {
            val hrMax = config.estimatedHrMax
            val low = config.workHrLow
            val high = config.workHrHigh
            Row(verticalAlignment = Alignment.CenterVertically) {
                IntervalPaceRow(
                    bestAvg = bestIntervalAvgWatts,
                    currentAvg = currentIntervalAvgWatts,
                    trace = intervalTrace,
                )
                if (hrMax != null && low != null && high != null && currentHr != null) {
                    Spacer(Modifier.width(20.dp))
                    HrZoneRow(hrTrace = hrTrace, currentHr = currentHr, hrMax = hrMax, low = low, high = high)
                }
            }
            Spacer(Modifier.height(4.dp))
        } else if (vsBestKj != null && (state.active || state.finished)) {
            VsBestChip(vsBestKj)
            Spacer(Modifier.height(4.dp))
        }
        ProgressBar(progress = state.overallProgress, accent = accent)
    }
}

private val HeartColor = Color(0xFFD9182B)

/** Small live heart-rate readout shown in the banner only when a strap is connected. */
@Composable
private fun HeartRateChip(bpm: Int) {
    Text(
        text = "♥ $bpm",
        color = HeartColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * Session controls on the banner. While running, a single Pause (timer only — the banner stays up).
 * While paused, Resume + Reset; Reset restarts the workout from 0:00, discarding the current ride.
 * Each button owns its own tap region so it isn't swallowed by the banner gesture. Hidden once the
 * session is finished (dismiss via long-press).
 */
@Composable
private fun PauseControls(
    state: FourByFourState,
    onPlayPause: () -> Unit,
    onReset: () -> Unit,
) {
    if (!state.active) return
    if (state.paused) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BannerButton(label = "▶ RESUME", background = EffortGood, onClick = onPlayPause)
            Spacer(Modifier.width(8.dp))
            BannerButton(label = "↻ RESET", background = WorkColor, onClick = onReset)
        }
    } else {
        BannerButton(label = "❚❚ PAUSE", background = PauseColor, onClick = onPlayPause)
    }
}

@Composable
private fun BannerButton(label: String, background: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

/**
 * Compact 4x4 readout for the minimized stat strip: the phase (colored) and the phase countdown,
 * sized to sit inline with the power/cadence/speed/resistance fields. Keeps the interval info
 * visible even when the full banner is collapsed.
 */
@Composable
fun FourByFourMinimizedField(
    state: FourByFourState,
    modifier: Modifier = Modifier,
) {
    val accent = state.phase.accent()
    val value = when {
        state.finished -> "✔"
        state.paused -> "❚❚"
        else -> formatMmSs(state.phaseRemainingSec)
    }
    Row(
        modifier = modifier.wrapContentHeight(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = state.phaseShortLabel,
            color = accent,
            fontSize = 19.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val ReferenceLineColor = Color(0xFFB0BEC5)

/**
 * Pacing readout for the current work interval: the live power trace plotted against a dashed
 * reference line at the best-ever interval average. The running interval average is shown on the
 * left, colored by how it compares to that reference.
 */
@Composable
private fun IntervalPaceRow(
    bestAvg: Float?,
    currentAvg: Float?,
    trace: List<Float>,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.width(86.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = currentAvg?.let { "${it.toInt()}" } ?: "–",
                color = paceColor(currentAvg, bestAvg),
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "W interval avg",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        IntervalPaceChart(
            trace = trace,
            bestAvg = bestAvg,
            modifier = Modifier.width(170.dp).height(42.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.width(58.dp)) {
            Text("best int.", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            Text(
                text = bestAvg?.let { "${it.toInt()} W" } ?: "—",
                color = ReferenceLineColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun IntervalPaceChart(
    trace: List<Float>,
    bestAvg: Float?,
    modifier: Modifier = Modifier,
) {
    val maxRef = (maxOf(bestAvg ?: 0f, trace.maxOrNull() ?: 0f)).coerceAtLeast(1f) * 1.08f
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        fun y(p: Float) = h - (p / maxRef) * h
        if (bestAvg != null) {
            val by = y(bestAvg)
            drawLine(
                color = ReferenceLineColor,
                start = Offset(0f, by),
                end = Offset(w, by),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }
        if (trace.size >= 2) {
            val dx = w / (trace.size - 1)
            val path = Path().apply {
                trace.forEachIndexed { i, p ->
                    val px = i * dx
                    val py = y(p)
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            drawPath(path, color = WorkColor, style = Stroke(width = 3f))
        }
    }
}

private fun paceColor(currentAvg: Float?, bestAvg: Float?): Color {
    if (currentAvg == null || bestAvg == null || bestAvg <= 0f) return Color.White
    val ratio = currentAvg / bestAvg
    return when {
        ratio >= 1f -> EffortGood
        ratio >= 0.9f -> EffortClose
        else -> EffortOff
    }
}

private val HrZoneBandFill = Color(0x3343A047)
private val HrZoneEdgeColor = Color(0xFF66BB6A)
private val HrTraceColor = Color(0xFFD9182B)

/**
 * Live heart-rate readout for a work interval: the per-second HR trace plotted against the shaded
 * 85–95% HRmax target band. Current bpm on the left is colored by whether it sits in the band.
 */
@Composable
private fun HrZoneRow(hrTrace: List<Int>, currentHr: Int?, hrMax: Int, low: Int, high: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(modifier = Modifier.width(86.dp), horizontalAlignment = Alignment.End) {
            Text(
                text = currentHr?.let { "$it" } ?: "–",
                color = hrZoneColor(currentHr, low, high),
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
            )
            Text("bpm", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        HrZoneChart(
            trace = hrTrace,
            hrMax = hrMax,
            low = low,
            high = high,
            modifier = Modifier.width(170.dp).height(42.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.width(58.dp)) {
            Text("target", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            Text(
                text = "$low–$high",
                color = HrZoneEdgeColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HrZoneChart(
    trace: List<Int>,
    hrMax: Int,
    low: Int,
    high: Int,
    modifier: Modifier = Modifier,
) {
    // Fixed window (50%–102% of HRmax) so the 85–95% band sits high in the frame regardless of trace.
    val yMin = hrMax * 0.5f
    val yMax = hrMax * 1.02f
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        fun y(v: Float) = h - ((v - yMin) / (yMax - yMin)).coerceIn(0f, 1f) * h
        val bandTop = y(high.toFloat())
        val bandBottom = y(low.toFloat())
        drawRect(
            color = HrZoneBandFill,
            topLeft = Offset(0f, bandTop),
            size = androidx.compose.ui.geometry.Size(w, (bandBottom - bandTop).coerceAtLeast(0f)),
        )
        drawLine(HrZoneEdgeColor, Offset(0f, bandTop), Offset(w, bandTop), strokeWidth = 1.5f)
        drawLine(HrZoneEdgeColor, Offset(0f, bandBottom), Offset(w, bandBottom), strokeWidth = 1.5f)
        if (trace.size >= 2) {
            val dx = w / (trace.size - 1)
            val path = Path().apply {
                trace.forEachIndexed { i, v ->
                    val px = i * dx
                    val py = y(v.toFloat())
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            drawPath(path, color = HrTraceColor, style = Stroke(width = 3f))
        }
    }
}

private fun hrZoneColor(hr: Int?, low: Int, high: Int): Color = when {
    hr == null -> Color.White
    hr in low..high -> EffortGood
    hr > high -> EffortClose
    hr >= low - 8 -> EffortClose
    else -> EffortOff
}

@Composable
private fun VsBestChip(deltaKj: Float) {
    val ahead = deltaKj >= 0f
    val arrow = if (ahead) "▲" else "▼"
    val color = if (ahead) EffortGood else EffortOff
    Text(
        text = "$arrow ${"%+.1f".format(deltaKj)} kJ vs best",
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun CountdownOrPaused(state: FourByFourState) {
    if (state.paused) {
        val transition = rememberInfiniteTransition()
        val pulse by transition.animateFloatAlpha()
        Text(
            text = "PAUSED",
            color = Color.White.copy(alpha = pulse),
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(140.dp),
        )
    } else {
        Text(
            text = formatMmSs(state.phaseRemainingSec),
            color = Color.White,
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(140.dp),
        )
    }
}

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateFloatAlpha() =
    animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

@Composable
private fun TargetIndicator(
    modifier: Modifier,
    state: FourByFourState,
    config: ProtocolConfig,
    currentPower: Float,
) {
    val target: Int? = when (state.phase) {
        PhaseType.Work -> config.hardTargetWatts
        PhaseType.Recovery, PhaseType.WarmUp, PhaseType.CoolDown -> config.easyTargetWatts
        PhaseType.Finished -> null
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (target == null) {
            Text("${currentPower.toInt()}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("watts", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        } else {
            val color = effortColor(state.phase, currentPower, target)
            Text("${currentPower.toInt()}", color = color, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("target $target w", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

private val EffortGood = Color(0xFF43A047)
private val EffortClose = Color(0xFFF9A825)
private val EffortOff = Color(0xFFD9182B)

/**
 * Color the current power against the phase target. In a hard interval you want to be at/above the
 * target; in recovery/warm-up/cool-down you want to be at/below it.
 */
private fun effortColor(phase: PhaseType, current: Float, target: Int): Color {
    val ratio = if (target <= 0) 1f else current / target
    return if (phase.isHard) {
        when {
            ratio >= 0.97f -> EffortGood
            ratio >= 0.85f -> EffortClose
            else -> EffortOff
        }
    } else {
        when {
            ratio <= 1.05f -> EffortGood
            ratio <= 1.2f -> EffortClose
            else -> EffortOff
        }
    }
}

@Composable
private fun ProgressBar(progress: Float, accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.15f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
    }
}

private fun nextLabel(state: FourByFourState): String = when (state.nextPhase) {
    PhaseType.WarmUp -> "Warm up"
    PhaseType.Work -> "Interval"
    PhaseType.Recovery -> "Recovery"
    PhaseType.CoolDown -> "Cool down"
    PhaseType.Finished -> "Finish"
}
