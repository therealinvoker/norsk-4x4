package com.spop.poverlay.fourbyfour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spop.poverlay.fourbyfour.FourByFour
import com.spop.poverlay.fourbyfour.ProtocolConfig
import com.spop.poverlay.hr.ui.HeartRateSection

/**
 * Settings + start/stop controls for the Norwegian 4x4 session, shown on the config screen.
 * Reads and writes the shared [FourByFour] controller directly.
 */
@Composable
fun FourByFourControls(
    onStart: () -> Unit,
    onOpenHistory: () -> Unit,
    onConnectHrm: () -> Unit,
) {
    val config by FourByFour.config.collectAsStateWithLifecycle()
    val state by FourByFour.state.collectAsStateWithLifecycle()
    val cuesEnabled by FourByFour.cuesEnabled.collectAsStateWithLifecycle()
    val best by FourByFour.best.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Norwegian 4×4", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(
                protocolSummary(config),
                fontSize = 16.sp,
                color = Color.Gray,
            )
            Spacer(Modifier.height(16.dp))

            Stepper("Warm-up", "${config.warmUpSec / 60} min") { delta ->
                FourByFour.updateConfig(config.copy(warmUpSec = step(config.warmUpSec, delta, 60, 0, 1800)))
            }
            Stepper("Work interval", "${config.workSec / 60} min") { delta ->
                FourByFour.updateConfig(config.copy(workSec = step(config.workSec, delta, 60, 60, 600)))
            }
            Stepper("Recovery", "${config.recoverySec / 60} min") { delta ->
                FourByFour.updateConfig(config.copy(recoverySec = step(config.recoverySec, delta, 60, 0, 600)))
            }
            Stepper("Cool-down", "${config.coolDownSec / 60} min") { delta ->
                FourByFour.updateConfig(config.copy(coolDownSec = step(config.coolDownSec, delta, 60, 0, 1800)))
            }
            Stepper("Intervals", "${config.intervals}×") { delta ->
                FourByFour.updateConfig(config.copy(intervals = (config.intervals + delta).coerceIn(1, 8)))
            }

            Spacer(Modifier.height(8.dp))
            Text("Power targets (optional)", fontWeight = FontWeight.SemiBold)
            Stepper("Hard target", wattsLabel(config.hardTargetWatts)) { delta ->
                FourByFour.updateConfig(config.copy(hardTargetWatts = stepWatts(config.hardTargetWatts, delta)))
            }
            Stepper("Easy target", wattsLabel(config.easyTargetWatts)) { delta ->
                FourByFour.updateConfig(config.copy(easyTargetWatts = stepWatts(config.easyTargetWatts, delta)))
            }

            Spacer(Modifier.height(8.dp))
            Text("Heart rate", fontWeight = FontWeight.SemiBold)
            Stepper("Age", config.age?.let { "$it yr" } ?: "Off") { delta ->
                FourByFour.updateConfig(config.copy(age = stepAge(config.age, delta)))
            }
            config.estimatedHrMax?.let { hrMax ->
                Text(
                    "Max HR ≈ $hrMax bpm · interval target ${config.workHrLow}–${config.workHrHigh} bpm",
                    fontSize = 13.sp,
                    color = Color.Gray,
                )
            }

            Spacer(Modifier.height(8.dp))
            HeartRateSection(onRequestPermissions = onConnectHrm)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Beep on phase change", modifier = Modifier.weight(1f), fontSize = 16.sp)
                Switch(checked = cuesEnabled, onCheckedChange = { FourByFour.setCuesEnabled(it) })
            }

            best?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Best ride: ${"%.0f".format(it.summary.workOutputKj)} kJ work output",
                    color = Color(0xFFB8860B),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(16.dp))
            if (state.active) {
                Text("Session in progress (${state.phaseLabel})", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { FourByFour.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop session", color = Color.White) }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text("Start 4×4 ride", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text("Workout history")
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onStep: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
        OutlinedButton(onClick = { onStep(-1) }) { Text("−", fontSize = 18.sp) }
        Text(
            value,
            modifier = Modifier.width(96.dp),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(onClick = { onStep(1) }) { Text("+", fontSize = 18.sp) }
    }
}

private fun protocolSummary(c: ProtocolConfig): String {
    val total = c.totalDurationSec / 60
    return "${c.warmUpSec / 60}′ warm-up · ${c.intervals} × ${c.workSec / 60}′ hard / ${c.recoverySec / 60}′ easy · ${c.coolDownSec / 60}′ cool-down ≈ $total min"
}

private fun wattsLabel(watts: Int?) = if (watts == null) "Off" else "$watts w"

private fun step(current: Int, delta: Int, unit: Int, min: Int, max: Int) =
    (current + delta * unit).coerceIn(min, max)

private fun stepWatts(current: Int?, delta: Int): Int? {
    val next = (current ?: 0) + delta * 5
    return if (next <= 0) null else next.coerceAtMost(600)
}

private fun stepAge(current: Int?, delta: Int): Int? {
    if (current == null) return if (delta > 0) 30 else null
    val next = current + delta
    return if (next < 10) null else next.coerceAtMost(100)
}
