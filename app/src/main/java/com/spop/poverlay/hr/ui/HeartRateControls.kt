package com.spop.poverlay.hr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spop.poverlay.hr.HrConnectionState
import com.spop.poverlay.hr.HeartRateManager

/**
 * Heart-rate-monitor controls rendered inline as a section of the Norwegian 4×4 settings card
 * (see [com.spop.poverlay.fourbyfour.ui.FourByFourControls]). Reads the shared [HeartRateManager]
 * flows and drives its scan/connect/forget API directly.
 *
 * @param onRequestPermissions invoked when the user asks to connect a strap; the host activity is
 * responsible for granting Bluetooth permissions and then calling [HeartRateManager.startScan].
 */
@Composable
fun HeartRateSection(onRequestPermissions: () -> Unit) {
    val connectionState by HeartRateManager.connectionState.collectAsStateWithLifecycle()
    val bpm by HeartRateManager.bpm.collectAsStateWithLifecycle()
    val deviceName by HeartRateManager.deviceName.collectAsStateWithLifecycle()
    val discovered by HeartRateManager.discovered.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        Text(
            statusLine(connectionState, bpm),
            fontSize = 14.sp,
            color = Color.Gray,
        )
        deviceName?.let {
            Text("Strap: $it", fontSize = 13.sp, color = Color.Gray)
        }

        Spacer(Modifier.height(8.dp))
        when (connectionState) {
            HrConnectionState.Connected -> {
                OutlinedButton(
                    onClick = { HeartRateManager.forget() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Forget device") }
            }

            HrConnectionState.Scanning -> {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Searching for straps…")
                    }
                    if (discovered.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        discovered.forEach { device ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { HeartRateManager.connect(device.address, device.name) }
                                    .padding(vertical = 12.dp),
                            ) {
                                Text(
                                    device.name,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    device.address,
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { HeartRateManager.cancelScan() }) { Text("Cancel") }
                }
            }

            HrConnectionState.Connecting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Connecting…")
                }
            }

            HrConnectionState.Disconnected -> {
                Button(
                    onClick = onRequestPermissions,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9182B)),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect heart-rate strap", color = Color.White) }
            }
        }
    }
}

private fun statusLine(state: HrConnectionState, bpm: Int?): String = when (state) {
    HrConnectionState.Connected -> bpm?.let { "Connected · $it bpm" } ?: "Connected"
    HrConnectionState.Connecting -> "Connecting…"
    HrConnectionState.Scanning -> "Scanning…"
    HrConnectionState.Disconnected -> "Not connected"
}
