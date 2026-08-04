package com.spop.poverlay.hr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/** Connection lifecycle of the Bluetooth heart-rate strap. */
enum class HrConnectionState { Disconnected, Scanning, Connecting, Connected }

/** A heart-rate-capable BLE peripheral discovered during a scan. */
data class HrDevice(val name: String, val address: String)

/**
 * Process-wide controller for the optional Bluetooth heart-rate monitor.
 *
 * Mirrors [com.spop.poverlay.fourbyfour.FourByFour]: a single shared instance owns the BLE
 * connection so both the overlay service (which reads [bpm] into the recorder) and the config
 * activity (which scans/picks/forgets a strap) observe the same source of truth. The last picked
 * device address is remembered in [SharedPreferences]. Auto-reconnect runs an active scan for that
 * exact address and does a direct connect the instant the strap advertises — the OS
 * `autoConnect=true` whitelist path is unreliable on this device (it arms but never fires), so we
 * find-then-connect ourselves.
 *
 * All BLE callbacks arrive on a binder thread; state is only mutated through the [MutableStateFlow]s
 * so observers always see consistent, thread-safe values.
 */
object HeartRateManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    private val mutableConnectionState = MutableStateFlow(HrConnectionState.Disconnected)
    val connectionState: StateFlow<HrConnectionState> = mutableConnectionState.asStateFlow()

    private val mutableBpm = MutableStateFlow<Int?>(null)
    val bpm: StateFlow<Int?> = mutableBpm.asStateFlow()

    private val mutableDeviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = mutableDeviceName.asStateFlow()

    private val mutableDiscovered = MutableStateFlow<List<HrDevice>>(emptyList())
    val discovered: StateFlow<List<HrDevice>> = mutableDiscovered.asStateFlow()

    private var appContext: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectScanJob: Job? = null
    private var connectTimeoutJob: Job? = null

    // Distinguishes a deliberate teardown (user disconnect/forget) from an unexpected drop, so the
    // auto-reconnect only fires when the strap goes away on its own.
    @Volatile
    private var userInitiatedDisconnect = false

    // Address the reconnect scan is hunting for. The dedicated reconnect scan callback only acts on a
    // result whose address matches this; cleared once we start connecting or give up.
    @Volatile
    private var reconnectTarget: String? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        adapter = context.applicationContext
            .getSystemService(BluetoothManager::class.java)
            ?.adapter
        mutableDeviceName.value = prefs.getString(KEY_LAST_NAME, null)
    }

    /**
     * Connect to the remembered strap (if any) and keep retrying until it is reachable. Safe to call
     * repeatedly (e.g. on app start and when a ride begins) and before permissions are granted; it
     * no-ops while a connection is already up or in progress.
     */
    fun autoReconnect() {
        when (mutableConnectionState.value) {
            HrConnectionState.Connected,
            HrConnectionState.Connecting,
            HrConnectionState.Scanning -> return
            HrConnectionState.Disconnected -> Unit
        }
        // A reconnect scan is already hunting for the strap.
        if (reconnectScanJob?.isActive == true) return
        val address = prefs.getString(KEY_LAST_ADDRESS, null) ?: return
        startReconnectScan(address)
    }

    /**
     * Actively scan for the remembered [address] and direct-connect the instant it advertises. This
     * replaces the OS `autoConnect=true` whitelist path, which arms but never completes on this
     * device. Scans in windows with short idle gaps (to avoid the Android continuous-scan throttle)
     * until the strap appears or a manual scan/connect/teardown supersedes it.
     */
    @SuppressLint("MissingPermission")
    private fun startReconnectScan(address: String) {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            Timber.w("Cannot auto-reconnect HR strap: no Bluetooth LE scanner available")
            return
        }
        this.scanner = scanner
        userInitiatedDisconnect = false
        reconnectTarget = address
        reconnectScanJob?.cancel()

        val filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        reconnectScanJob = scope.launch {
            Timber.i("HR reconnect scan started for %s", address)
            while (isActive &&
                reconnectTarget == address &&
                mutableConnectionState.value == HrConnectionState.Disconnected
            ) {
                try {
                    scanner.startScan(filters, settings, reconnectScanCallback)
                } catch (e: SecurityException) {
                    Timber.e(e, "Missing permission for HR reconnect scan")
                    reconnectTarget = null
                    return@launch
                }
                delay(RECONNECT_SCAN_WINDOW_MS)
                try {
                    scanner.stopScan(reconnectScanCallback)
                } catch (e: SecurityException) {
                    Timber.e(e, "Missing permission to stop HR reconnect scan")
                }
                // Still no strap — rest the radio briefly, then look again.
                if (reconnectTarget == address) delay(RECONNECT_SCAN_GAP_MS)
            }
        }
    }

    /** Cancel any in-progress reconnect scan (used when a manual scan/connect/teardown takes over). */
    @SuppressLint("MissingPermission")
    private fun stopReconnectScan() {
        reconnectTarget = null
        reconnectScanJob?.cancel()
        reconnectScanJob = null
        try {
            scanner?.stopScan(reconnectScanCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission to stop HR reconnect scan")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            Timber.w("Cannot scan for HR straps: no Bluetooth LE scanner available")
            return
        }
        this.scanner = scanner
        stopScan()
        // Tear down any pending auto-reconnect or half-open connection before scanning, so a stale
        // reconnect attempt can't kill the manual scan we're about to start.
        stopReconnectScan()
        reconnectJob?.cancel()
        reconnectJob = null
        userInitiatedDisconnect = true   // suppress reconnect from the teardown below
        closeGatt()                      // drop any in-flight/auto-reconnect attempt
        mutableDiscovered.value = emptyList()
        mutableConnectionState.value = HrConnectionState.Scanning

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            // No hardware ScanFilter: some HR straps advertise the 0x180D service UUID only in the
            // scan response (or not until connected), and the offloaded service-UUID filter silently
            // drops those. We surface candidates in onScanResult and confirm the Heart Rate service
            // after connecting (see onServicesDiscovered).
            scanner.startScan(null, settings, scanCallback)
            Timber.i("HR BLE scan started (no service filter)")
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission to start BLE scan")
            mutableConnectionState.value = HrConnectionState.Disconnected
            return
        }

        // Bound the scan so we don't keep the radio busy forever if nothing is selected.
        scanJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            stopScan()
            if (mutableConnectionState.value == HrConnectionState.Scanning) {
                mutableConnectionState.value =
                    if (gatt != null) HrConnectionState.Connected else HrConnectionState.Disconnected
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        Timber.i("HR BLE scan stopped")
        val scanner = scanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission to stop BLE scan")
        }
    }

    /** Stop an in-progress scan started from the UI and return to the disconnected state. */
    fun cancelScan() {
        stopScan()
        if (mutableConnectionState.value == HrConnectionState.Scanning) {
            mutableConnectionState.value = HrConnectionState.Disconnected
        }
        // startScan() set userInitiatedDisconnect to suppress reconnect; if a strap is still
        // remembered, resume auto-reconnecting to it now that the manual scan is cancelled.
        if (prefs.getString(KEY_LAST_ADDRESS, null) != null) {
            userInitiatedDisconnect = false
            scheduleReconnect()
        }
    }

    /**
     * Connect to [address]. Pass the friendly [name] discovered during scanning so it is remembered
     * even if the peripheral never reports its name over GATT. Always a direct connect
     * (autoConnect=false): the reconnect scan only calls this once the strap is confirmed
     * advertising, so a direct connect is both fast and reliable.
     */
    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String? = null) {
        val adapter = adapter ?: run {
            Timber.w("Cannot connect to HR strap: no Bluetooth adapter")
            return
        }
        stopScan()
        stopReconnectScan()
        userInitiatedDisconnect = false
        // Remember a readable label straight away, so the strap never shows only its MAC address.
        if (!name.isNullOrBlank() && name != address) {
            mutableDeviceName.value = name
            prefs.edit { putString(KEY_LAST_NAME, name) }
        }
        try {
            val device = adapter.getRemoteDevice(address)
            closeGatt()
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattCallback)
            }
            prefs.edit { putString(KEY_LAST_ADDRESS, address) }
            mutableConnectionState.value = HrConnectionState.Connecting
            startConnectTimeout()
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission to connect to HR strap")
            mutableConnectionState.value = HrConnectionState.Disconnected
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Invalid HR strap address: $address")
            mutableConnectionState.value = HrConnectionState.Disconnected
        }
    }

    /** Tear down the active connection without forgetting the remembered strap. */
    fun disconnect() {
        userInitiatedDisconnect = true
        stopReconnectScan()
        reconnectJob?.cancel()
        reconnectJob = null
        closeGatt()
        mutableBpm.value = null
        mutableConnectionState.value = HrConnectionState.Disconnected
    }

    /** Forget the remembered strap entirely and disconnect. */
    fun forget() {
        userInitiatedDisconnect = true
        stopReconnectScan()
        reconnectJob?.cancel()
        reconnectJob = null
        closeGatt()
        prefs.edit {
            remove(KEY_LAST_ADDRESS)
            remove(KEY_LAST_NAME)
        }
        mutableBpm.value = null
        mutableDeviceName.value = null
        mutableConnectionState.value = HrConnectionState.Disconnected
    }

    private fun startConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (mutableConnectionState.value == HrConnectionState.Connecting) {
                Timber.w("HR connect timed out after %d ms; will retry", CONNECT_TIMEOUT_MS)
                closeGatt()
                mutableConnectionState.value = HrConnectionState.Disconnected
                scheduleReconnect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        val current = gatt ?: return
        gatt = null
        try {
            current.disconnect()
            current.close()
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission to close GATT")
        }
    }

    private fun scheduleReconnect() {
        if (mutableConnectionState.value == HrConnectionState.Scanning ||
            mutableConnectionState.value == HrConnectionState.Connecting
        ) return
        if (userInitiatedDisconnect) return
        if (prefs.getString(KEY_LAST_ADDRESS, null) == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!userInitiatedDisconnect) autoReconnect()
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val result = result ?: return
            val device = result.device ?: return
            val address = device.address ?: return
            val advertisesHeartRate =
                result.scanRecord?.serviceUuids?.any { it.uuid == HEART_RATE_SERVICE_UUID } == true
            val name = (try {
                device.name
            } catch (e: SecurityException) {
                null
            }) ?: result.scanRecord?.deviceName
            Timber.v("HR scan result: addr=%s name=%s hrService=%s", address, name, advertisesHeartRate)
            // Surface every device (HR straps often advertise without a name or the 0x180D UUID until
            // connected); fall back to the MAC so it is still selectable. The Heart Rate service is
            // validated after connecting in onServicesDiscovered.
            val display = name?.takeIf { it.isNotBlank() } ?: address
            val existing = mutableDiscovered.value
            if (existing.none { it.address == address }) {
                mutableDiscovered.value = existing + HrDevice(display, address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("BLE scan failed with error $errorCode")
            mutableConnectionState.value = HrConnectionState.Disconnected
        }
    }

    // Separate from scanCallback: only reacts to the remembered strap and immediately direct-connects
    // it, without touching the UI discovered-list or the manual-scan state machine.
    private val reconnectScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val address = result?.device?.address ?: return
            val target = reconnectTarget ?: return
            if (!address.equals(target, ignoreCase = true)) return
            Timber.i("HR reconnect scan matched %s; direct-connecting", address)
            reconnectTarget = null
            reconnectScanJob?.cancel()
            try {
                scanner?.stopScan(this)
            } catch (e: SecurityException) {
                Timber.e(e, "Missing permission to stop HR reconnect scan")
            }
            val name = (try {
                result.device?.name
            } catch (e: SecurityException) {
                null
            }) ?: result.scanRecord?.deviceName
            connect(address, name)
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("HR reconnect scan failed with error $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Timber.i("HR onConnectionStateChange status=%d newState=%d", status, newState)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mutableConnectionState.value = HrConnectionState.Connecting
                    try {
                        val name = gatt.device?.name
                        if (name != null) {
                            mutableDeviceName.value = name
                            prefs.edit { putString(KEY_LAST_NAME, name) }
                        }
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Timber.e(e, "Missing permission to discover HR services")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    mutableBpm.value = null
                    mutableConnectionState.value = HrConnectionState.Disconnected
                    closeGatt()
                    // Retry whether the drop followed a good session or a failed initial attempt; a
                    // wrong/absent device is handled by clearing the remembered address elsewhere,
                    // which makes scheduleReconnect() self-stop.
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("HR service discovery failed status=%d; will retry", status)
                mutableConnectionState.value = HrConnectionState.Disconnected
                closeGatt()
                scheduleReconnect()
                return
            }
            val service = gatt.getService(HEART_RATE_SERVICE_UUID) ?: run {
                Timber.w("Connected device exposes no Heart Rate Service")
                mutableConnectionState.value = HrConnectionState.Disconnected
                prefs.edit { remove(KEY_LAST_ADDRESS) }
                mutableDeviceName.value = null
                return
            }
            val characteristic = service.getCharacteristic(HEART_RATE_MEASUREMENT_UUID) ?: run {
                Timber.w("Heart Rate Service has no measurement characteristic")
                mutableConnectionState.value = HrConnectionState.Disconnected
                prefs.edit { remove(KEY_LAST_ADDRESS) }
                mutableDeviceName.value = null
                return
            }
            try {
                gatt.setCharacteristicNotification(characteristic, true)
                val cccd = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
                connectTimeoutJob?.cancel()
                mutableConnectionState.value = HrConnectionState.Connected
                Timber.i("HR strap connected and notifications enabled")
            } catch (e: SecurityException) {
                Timber.e(e, "Missing permission to enable HR notifications")
                mutableConnectionState.value = HrConnectionState.Disconnected
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != HEART_RATE_MEASUREMENT_UUID) return
            parseHeartRate(characteristic.value)?.let { mutableBpm.value = it }
        }
    }

    private const val PREFS = "heartrate"
    private const val KEY_LAST_ADDRESS = "lastDeviceAddress"
    private const val KEY_LAST_NAME = "lastDeviceName"

    private const val SCAN_TIMEOUT_MS = 30_000L
    private const val RECONNECT_DELAY_MS = 5_000L
    private const val CONNECT_TIMEOUT_MS = 12_000L

    // Reconnect scan cadence: scan hard for a window, rest briefly, repeat until the strap appears.
    private const val RECONNECT_SCAN_WINDOW_MS = 20_000L
    private const val RECONNECT_SCAN_GAP_MS = 4_000L

    val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * Parse a Heart Rate Measurement characteristic payload (BLE spec 0x2A37) into beats-per-minute.
 *
 * The first byte is a flags field whose bit 0 selects the value format: clear -> the bpm is a
 * uint8 at index 1; set -> the bpm is a little-endian uint16 at indices 1..2. Returns null when the
 * payload is missing or too short to contain a value.
 */
fun parseHeartRate(bytes: ByteArray?): Int? {
    if (bytes == null || bytes.size < 2) return null
    val flags = bytes[0].toInt()
    return if (flags and 0x01 == 0) {
        bytes[1].toInt() and 0xFF
    } else {
        if (bytes.size < 3) return null
        (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
    }
}
