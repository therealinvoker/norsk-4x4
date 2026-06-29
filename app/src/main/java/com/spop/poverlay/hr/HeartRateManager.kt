package com.spop.poverlay.hr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.os.ParcelUuid
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * device address is remembered in [SharedPreferences] so it can be auto-reconnected on launch.
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

    // Distinguishes a deliberate teardown (user disconnect/forget) from an unexpected drop, so the
    // auto-reconnect only fires when the strap goes away on its own.
    @Volatile
    private var userInitiatedDisconnect = false

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

    /** Connect to the remembered strap (if any). Safe to call before permissions are granted. */
    fun autoReconnect() {
        val address = prefs.getString(KEY_LAST_ADDRESS, null) ?: return
        connect(address)
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            Timber.w("Cannot scan for HR straps: no Bluetooth LE scanner available")
            return
        }
        this.scanner = scanner
        stopScan()
        mutableDiscovered.value = emptyList()
        mutableConnectionState.value = HrConnectionState.Scanning

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
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
        val scanner = scanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission to stop BLE scan")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val adapter = adapter ?: run {
            Timber.w("Cannot connect to HR strap: no Bluetooth adapter")
            return
        }
        stopScan()
        userInitiatedDisconnect = false
        mutableConnectionState.value = HrConnectionState.Connecting
        try {
            val device = adapter.getRemoteDevice(address)
            closeGatt()
            gatt = device.connectGatt(appContext, false, gattCallback)
            prefs.edit { putString(KEY_LAST_ADDRESS, address) }
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
        reconnectJob?.cancel()
        reconnectJob = null
        closeGatt()
        mutableBpm.value = null
        mutableConnectionState.value = HrConnectionState.Disconnected
    }

    /** Forget the remembered strap entirely and disconnect. */
    fun forget() {
        userInitiatedDisconnect = true
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

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
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
            val device = result?.device ?: return
            val address = device.address ?: return
            val name = try {
                device.name
            } catch (e: SecurityException) {
                null
            } ?: "Heart-rate monitor"
            // Dedupe by address; ignore repeated advertisements for an already-listed device.
            val existing = mutableDiscovered.value
            if (existing.none { it.address == address }) {
                mutableDiscovered.value = existing + HrDevice(name, address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("BLE scan failed with error $errorCode")
            mutableConnectionState.value = HrConnectionState.Disconnected
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
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
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(HEART_RATE_SERVICE_UUID) ?: run {
                Timber.w("Connected device exposes no Heart Rate Service")
                return
            }
            val characteristic = service.getCharacteristic(HEART_RATE_MEASUREMENT_UUID) ?: run {
                Timber.w("Heart Rate Service has no measurement characteristic")
                return
            }
            try {
                gatt.setCharacteristicNotification(characteristic, true)
                val cccd = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
                mutableConnectionState.value = HrConnectionState.Connected
            } catch (e: SecurityException) {
                Timber.e(e, "Missing permission to enable HR notifications")
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

    private const val SCAN_TIMEOUT_MS = 15_000L
    private const val RECONNECT_DELAY_MS = 5_000L

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
