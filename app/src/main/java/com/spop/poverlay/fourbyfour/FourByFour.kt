package com.spop.poverlay.fourbyfour

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.spop.poverlay.history.SessionRecorder
import com.spop.poverlay.history.WorkoutRepository
import com.spop.poverlay.history.WorkoutSession
import com.spop.poverlay.util.tickerFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Process-wide controller for the active 4x4 session and its settings.
 *
 * Both the overlay service (which owns the bike sensors and feeds [recordSample]) and the config
 * activity observe/drive the same instance, so there is a single source of truth for session state
 * regardless of which component is alive. Settings are persisted to [SharedPreferences].
 */
object FourByFour {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var prefs: SharedPreferences
    private lateinit var engine: FourByFourEngine
    lateinit var repository: WorkoutRepository
        private set

    private val mutableConfig = MutableStateFlow(ProtocolConfig())
    val config: StateFlow<ProtocolConfig> = mutableConfig.asStateFlow()

    private val mutableCuesEnabled = MutableStateFlow(true)
    val cuesEnabled: StateFlow<Boolean> = mutableCuesEnabled.asStateFlow()

    private val mutableBest = MutableStateFlow<WorkoutSession?>(null)
    val best: StateFlow<WorkoutSession?> = mutableBest.asStateFlow()

    /** Live cumulative-output delta (kJ) vs your best session at the same point; null if no best. */
    private val mutableLiveVsBestKj = MutableStateFlow<Float?>(null)
    val liveVsBestKj: StateFlow<Float?> = mutableLiveVsBestKj.asStateFlow()

    /** Highest single work-interval average power (W) ever recorded; the pacing reference line. */
    private val mutableBestIntervalAvgWatts = MutableStateFlow<Float?>(null)
    val bestIntervalAvgWatts: StateFlow<Float?> = mutableBestIntervalAvgWatts.asStateFlow()

    /** Running average power (W) of the work interval in progress; null outside a work interval. */
    private val mutableCurrentIntervalAvgWatts = MutableStateFlow<Float?>(null)
    val currentIntervalAvgWatts: StateFlow<Float?> = mutableCurrentIntervalAvgWatts.asStateFlow()

    /** Per-second power trace (W) of the work interval in progress; empty outside a work interval. */
    private val mutableCurrentIntervalTrace = MutableStateFlow<List<Float>>(emptyList())
    val currentIntervalTrace: StateFlow<List<Float>> = mutableCurrentIntervalTrace.asStateFlow()

    val state: StateFlow<FourByFourState> get() = engine.state
    val transitions: SharedFlow<PhaseTransition> get() = engine.transitions

    private var recorder: SessionRecorder? = null
    private var finalized = false
    private var initialized = false
    private var liveJoules = 0.0
    private var bestCumulativeKj: FloatArray? = null
    private var currentIntervalIndex = -1
    private val intervalPowers = ArrayList<Float>()

    // Wall-clock of the last moment the rider was actively pedaling (running, not paused). The
    // watchdog auto-stops the session if this goes stale, covering both "no pedaling" and "paused".
    @Volatile
    private var lastActiveMomentMs = 0L
    private var watchdogJob: Job? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        repository = WorkoutRepository(context.applicationContext)
        loadSettings()
        engine = FourByFourEngine(scope, mutableConfig.value)
        refreshBest()

        // Auto-save when a session reaches its natural end.
        scope.launch {
            engine.state.collect { state ->
                if (state.finished && !finalized) finalize(completed = true)
            }
        }
    }

    fun start() {
        finalized = false
        liveJoules = 0.0
        mutableLiveVsBestKj.value = null
        resetIntervalTracking()
        recorder = SessionRecorder(mutableConfig.value)
        engine.start(mutableConfig.value)
        startWatchdog()
    }

    /**
     * Auto-stop (and save) the ride if the rider is idle or paused for [AUTO_STOP_TIMEOUT_MS]. Any
     * previously running watchdog is cancelled first so a restart never leaves two watchdogs racing.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        lastActiveMomentMs = System.currentTimeMillis()
        watchdogJob = scope.launch {
            tickerFlow(period = 30.seconds).collect {
                if (shouldAutoStop(
                        active = state.value.active,
                        lastActiveMomentMs = lastActiveMomentMs,
                        nowMs = System.currentTimeMillis(),
                        timeoutMs = AUTO_STOP_TIMEOUT_MS,
                    )
                ) {
                    stop()
                    return@collect
                }
            }
        }
    }

    private fun resetIntervalTracking() {
        currentIntervalIndex = -1
        intervalPowers.clear()
        mutableCurrentIntervalTrace.value = emptyList()
        mutableCurrentIntervalAvgWatts.value = null
    }

    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun togglePause() = engine.togglePause()
    fun skipPhase() = engine.skipPhase()

    /** Abandon the current session, saving whatever was ridden if it has any work data. */
    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        if (state.value.active) finalize(completed = false)
        engine.stop()
    }

    fun recordSample(power: Float, cadence: Float, resistance: Float, hr: Int? = null) {
        val state = engine.state.value
        // Treat any real pedaling in a running (non-paused) session as activity; idle or paused
        // time is never counted, so both feed the inactivity watchdog.
        if (state.active && !state.paused && (power > 1f || cadence > 1f)) {
            lastActiveMomentMs = System.currentTimeMillis()
        }
        val recorded = recorder?.record(state, power, cadence, resistance, hr) ?: false
        if (recorded) {
            liveJoules += power.coerceAtLeast(0f)
            val best = bestCumulativeKj
            if (best != null && best.isNotEmpty()) {
                val bestAtNow = best[state.elapsedSec.coerceIn(0, best.size - 1)]
                mutableLiveVsBestKj.value = (liveJoules / 1000.0).toFloat() - bestAtNow
            }
            updateIntervalPace(state, power.coerceAtLeast(0f))
        }
    }

    /** Track the current work interval's power trace and running average for the pacing line. */
    private fun updateIntervalPace(state: FourByFourState, power: Float) {
        if (state.phase == PhaseType.Work) {
            if (state.intervalIndex != currentIntervalIndex) {
                currentIntervalIndex = state.intervalIndex
                intervalPowers.clear()
            }
            intervalPowers.add(power)
            mutableCurrentIntervalTrace.value = intervalPowers.toList()
            mutableCurrentIntervalAvgWatts.value = intervalPowers.average().toFloat()
        } else if (currentIntervalIndex != -1) {
            resetIntervalTracking()
        }
    }

    private fun finalize(completed: Boolean) {
        if (finalized) return
        finalized = true
        val recorder = recorder ?: return
        if (recorder.hasWorkData) {
            val session = recorder.finish(completed)
            repository.save(session)
            refreshBest()
        }
        this.recorder = null
    }

    fun refreshBest() {
        scope.launch {
            val best = repository.best()
            mutableBest.value = best
            bestCumulativeKj = best?.cumulativeOutputKjBySecond()
            mutableBestIntervalAvgWatts.value = repository.bestIntervalAverageWatts()
        }
    }

    fun updateConfig(config: ProtocolConfig) {
        mutableConfig.value = config
        prefs.edit {
            putInt(KEY_WARMUP, config.warmUpSec)
            putInt(KEY_WORK, config.workSec)
            putInt(KEY_RECOVERY, config.recoverySec)
            putInt(KEY_COOLDOWN, config.coolDownSec)
            putInt(KEY_INTERVALS, config.intervals)
            putInt(KEY_HARD_WATTS, config.hardTargetWatts ?: -1)
            putInt(KEY_EASY_WATTS, config.easyTargetWatts ?: -1)
        }
    }

    fun setCuesEnabled(enabled: Boolean) {
        mutableCuesEnabled.value = enabled
        prefs.edit { putBoolean(KEY_CUES, enabled) }
    }

    private fun loadSettings() {
        val default = ProtocolConfig()
        mutableConfig.value = ProtocolConfig(
            warmUpSec = prefs.getInt(KEY_WARMUP, default.warmUpSec),
            workSec = prefs.getInt(KEY_WORK, default.workSec),
            recoverySec = prefs.getInt(KEY_RECOVERY, default.recoverySec),
            coolDownSec = prefs.getInt(KEY_COOLDOWN, default.coolDownSec),
            intervals = prefs.getInt(KEY_INTERVALS, default.intervals),
            hardTargetWatts = prefs.getInt(KEY_HARD_WATTS, -1).takeIf { it > 0 },
            easyTargetWatts = prefs.getInt(KEY_EASY_WATTS, -1).takeIf { it > 0 },
        )
        mutableCuesEnabled.value = prefs.getBoolean(KEY_CUES, true)
    }

    private const val AUTO_STOP_TIMEOUT_MS = 15 * 60 * 1000L

    private const val PREFS = "fourbyfour"
    private const val KEY_WARMUP = "warmupSec"
    private const val KEY_WORK = "workSec"
    private const val KEY_RECOVERY = "recoverySec"
    private const val KEY_COOLDOWN = "cooldownSec"
    private const val KEY_INTERVALS = "intervals"
    private const val KEY_HARD_WATTS = "hardTargetWatts"
    private const val KEY_EASY_WATTS = "easyTargetWatts"
    private const val KEY_CUES = "cuesEnabled"
}

/**
 * Whether an active session should be auto-stopped: true only when the session is [active] and the
 * rider has been idle (or paused) for at least [timeoutMs]. Pure helper so the rule is unit-testable.
 */
fun shouldAutoStop(active: Boolean, lastActiveMomentMs: Long, nowMs: Long, timeoutMs: Long): Boolean =
    active && (nowMs - lastActiveMomentMs) >= timeoutMs
