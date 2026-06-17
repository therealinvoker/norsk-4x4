package com.spop.poverlay.history.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.spop.poverlay.fourbyfour.PhaseType
import com.spop.poverlay.history.WorkoutSession

private val WorkBand = Color(0x33D9182B)
private val GridColor = Color(0x22FFFFFF)
private val CurrentLine = Color(0xFFFF3348)
private val GhostLine = Color(0xFF8A8A8A)

/**
 * Power-over-time chart for a session. Work intervals are shaded red; if [best] is provided its
 * power curve is drawn as a gray "ghost" so the ride can be compared against the best one.
 */
@Composable
fun SessionChart(
    session: WorkoutSession,
    best: WorkoutSession?,
    modifier: Modifier = Modifier,
) {
    val totalSec = maxOf(
        session.durationSec,
        session.config.totalDurationSec,
        best?.durationSec ?: 0,
    ).coerceAtLeast(1)
    val maxPower = maxOf(
        session.summary.maxPower,
        best?.summary?.maxPower ?: 0f,
        1f,
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
    ) {
        // Shade the hard work intervals.
        session.config.timeline.filter { it.type == PhaseType.Work }.forEach { seg ->
            val x0 = seg.startOffsetSec.toFloat() / totalSec * size.width
            val x1 = seg.endOffsetSec.toFloat() / totalSec * size.width
            drawRect(color = WorkBand, topLeft = Offset(x0, 0f), size = androidx.compose.ui.geometry.Size(x1 - x0, size.height))
        }
        // Horizontal gridlines at 25/50/75%.
        for (frac in listOf(0.25f, 0.5f, 0.75f)) {
            val y = size.height * frac
            drawLine(GridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        best?.let { drawPowerPath(it, totalSec, maxPower, GhostLine, 3f) }
        drawPowerPath(session, totalSec, maxPower, CurrentLine, 4f)
    }
}

private fun DrawScope.drawPowerPath(
    session: WorkoutSession,
    totalSec: Int,
    maxPower: Float,
    color: Color,
    stroke: Float,
) {
    val samples = session.samples
    if (samples.isEmpty()) return
    val path = Path()
    samples.forEachIndexed { index, sample ->
        val x = sample.tSec.toFloat() / totalSec * size.width
        val y = size.height - (sample.power / maxPower).coerceIn(0f, 1f) * size.height
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
}
