package com.spop.poverlay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.spop.poverlay.history.ui.HistoryScreen
import com.spop.poverlay.history.ui.SessionDetailScreen
import com.spop.poverlay.hr.HeartRateManager
import com.spop.poverlay.releases.ReleaseChecker
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


private enum class Screen { Home, History, Detail }

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ConfigurationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel =
            ConfigurationViewModel(
                application, ConfigurationRepository(applicationContext, this),
                ReleaseChecker()
            )
        viewModel.finishActivity.observe(this) {
            finish()
        }
        viewModel.requestOverlayPermission.observe(this) {
            requestScreenPermission()
        }
        viewModel.requestRestart.observe(this) {
            restartGrupetto()
        }
        viewModel.infoPopup.observe(this) {
            Toast.makeText(
                this,
                it,
                Toast.LENGTH_LONG
            ).show()
        }
        setContent {
            PTONOverlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var screen by rememberSaveable { mutableStateOf(Screen.Home.name) }
                    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
                    when (Screen.valueOf(screen)) {
                        Screen.Home -> ConfigurationPage(
                            viewModel,
                            onOpenHistory = { screen = Screen.History.name },
                            onConnectHrm = { requestHeartRatePermissionsThenScan() },
                        )
                        Screen.History -> HistoryScreen(
                            onBack = { screen = Screen.Home.name },
                            onOpenSession = { id -> detailId = id; screen = Screen.Detail.name },
                        )
                        Screen.Detail -> SessionDetailScreen(
                            sessionId = detailId,
                            onBack = { screen = Screen.History.name },
                        )
                    }
                }
            }
        }
        lifecycleScope.launchWhenResumed {
            viewModel.onResume()
        }
    }

    private fun restartGrupetto() {
        Toast.makeText(
            this@MainActivity,
            HtmlCompat.fromHtml("<big>Restarting Grupetto</big>", HtmlCompat.FROM_HTML_MODE_LEGACY),
            Toast.LENGTH_LONG
        )
            .apply { setGravity(Gravity.CENTER, 0, 0) }
            .show()

        CoroutineScope(Dispatchers.IO).launch {
            delay(1500L)
            val pm: PackageManager = applicationContext.packageManager
            val intent = pm.getLaunchIntentForPackage(applicationContext.packageName)
            val mainIntent = Intent.makeRestartActivityTask(intent!!.component)
            applicationContext.startActivity(mainIntent)
            Runtime.getRuntime().exit(0)
        }
    }

    private val overlayPermissionRequest =
        registerForActivityResult(StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= 23) {
                viewModel.onOverlayPermissionRequestCompleted(
                    Settings.canDrawOverlays(this)
                )
            }
        }

    private fun requestScreenPermission() = Intent(
        "android.settings.action.MANAGE_OVERLAY_PERMISSION",
        Uri.parse("package:${packageName}")
    ).apply {
        overlayPermissionRequest.launch(this)
    }

    private val heartRatePermissionRequest =
        registerForActivityResult(RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startHeartRateScanOrEnableBluetooth()
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth permission is required to connect a heart-rate strap",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    private val enableBluetoothRequest =
        registerForActivityResult(StartActivityForResult()) {
            // Regardless of the user's choice, try to scan; startScan no-ops if BT is still off.
            HeartRateManager.startScan()
        }

    /** Ensure the needed Bluetooth permissions (and adapter) are present, then start scanning. */
    fun requestHeartRatePermissionsThenScan() {
        val required = heartRatePermissions()
        val missing = required.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startHeartRateScanOrEnableBluetooth()
        } else {
            heartRatePermissionRequest.launch(missing.toTypedArray())
        }
    }

    private fun heartRatePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun startHeartRateScanOrEnableBluetooth() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter != null && !adapter.isEnabled) {
            enableBluetoothRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            HeartRateManager.startScan()
        }
    }
}


@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    PTONOverlayTheme {
        Configuration()
    }
}