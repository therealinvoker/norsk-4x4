package com.spop.poverlay.history.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.fourbyfour.FourByFour
import com.spop.poverlay.history.WorkoutSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("EEE MMM d · h:mm a", Locale.getDefault())
private val GoldColor = Color(0xFFB8860B)

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val sessions by produceState<List<WorkoutSession>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { FourByFour.repository.loadAll() }
    }
    val bestId by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { FourByFour.repository.best()?.id }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back", fontSize = 18.sp) }
            Spacer(Modifier.width(8.dp))
            Text("Workout History", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        val current = sessions
        when {
            current == null -> CenterBox { CircularProgressIndicator() }
            current.isEmpty() -> CenterBox {
                Text("No 4×4 rides yet. Start one and it'll show up here.", color = Color.Gray)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(current, key = { it.id }) { session ->
                    SessionRow(session, isBest = session.id == bestId, onClick = { onOpenSession(session.id) })
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: WorkoutSession, isBest: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isBest) {
                        Text("★ ", color = GoldColor, fontWeight = FontWeight.Bold)
                    }
                    Text(dateFormat.format(Date(session.startedAtEpochMs)), fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "${DateUtils.formatElapsedTime((session.durationSec).toLong())} · " +
                        "${session.summary.avgWorkPower.toInt()} w avg in intervals" +
                        if (!session.completed) " · incomplete" else "",
                    color = Color.Gray,
                    fontSize = 14.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${"%.0f".format(session.summary.workOutputKj)} kJ", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("work output", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun SessionDetailScreen(
    sessionId: String?,
    onBack: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var deleted by remember { mutableStateOf(false) }

    val data by produceState<Pair<WorkoutSession?, WorkoutSession?>?>(initialValue = null, sessionId) {
        value = withContext(Dispatchers.IO) {
            val session = sessionId?.let { FourByFour.repository.get(it) }
            session to FourByFour.repository.best()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back", fontSize = 18.sp) }
            Spacer(Modifier.width(8.dp))
            Text("Ride detail", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        val pair = data
        val session = pair?.first
        when {
            deleted -> CenterBox { Text("Deleted.", color = Color.Gray) }
            pair == null -> CenterBox { CircularProgressIndicator() }
            session == null -> CenterBox { Text("Session not found.", color = Color.Gray) }
            else -> {
                val best = pair.second?.takeIf { it.id != session.id }
                Text(dateFormat.format(Date(session.startedAtEpochMs)), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))

                SessionChart(session = session, best = best)
                if (best != null) {
                    Spacer(Modifier.height(6.dp))
                    val delta = session.summary.workOutputKj - best.summary.workOutputKj
                    val ahead = delta >= 0
                    Text(
                        "${if (ahead) "▲" else "▼"} ${"%+.0f".format(delta)} kJ vs best ride (gray)",
                        color = if (ahead) Color(0xFF43A047) else Color(0xFFD9182B),
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.height(16.dp))
                StatGrid(session)

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { FourByFour.repository.delete(session.id) }
                            FourByFour.refreshBest()
                            deleted = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                ) { Text("Delete this ride", color = Color.White) }
            }
        }
    }
}

@Composable
private fun StatGrid(session: WorkoutSession) {
    val s = session.summary
    val stats = buildList {
        add("Work output" to "${"%.0f".format(s.workOutputKj)} kJ")
        add("Total output" to "${"%.0f".format(s.totalOutputKj)} kJ")
        add("Avg interval power" to "${s.avgWorkPower.toInt()} w")
        add("Max power" to "${s.maxPower.toInt()} w")
        add("Avg power" to "${s.avgPower.toInt()} w")
        add("Avg cadence" to "${s.avgCadence.toInt()} rpm")
        add("Avg resistance" to "${s.avgResistance.toInt()}")
        s.avgHr?.let { add("Avg HR" to "$it bpm") }
        s.maxHr?.let { add("Max HR" to "$it bpm") }
        add("Completed" to if (session.completed) "Yes" else "No")
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value) -> Stat(label, value, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Color.Gray, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
