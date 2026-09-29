package com.spop.poverlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.spop.poverlay.ConfigurationRepository
import com.spop.poverlay.MainActivity
import com.spop.poverlay.R
import com.spop.poverlay.fourbyfour.FourByFour
import com.spop.poverlay.fourbyfour.PhaseType
import com.spop.poverlay.hr.HeartRateManager
import com.spop.poverlay.sensor.DeadSensorDetector
import com.spop.poverlay.sensor.interfaces.BackpedalCompensatingSensorInterface
import com.spop.poverlay.sensor.interfaces.DummySensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikeSensorInterfaceV1New
import com.spop.poverlay.sensor.interfaces.PelotonBikePlusSensorInterface
import com.spop.poverlay.util.IsBikePlus
import com.spop.poverlay.util.IsRunningOnPeloton
import com.spop.poverlay.util.LifecycleEnabledService
import com.spop.poverlay.util.disableAnimations
import kotlinx.coroutines.flow.combine
import timber.log.Timber
import java.util.*
import kotlin.math.roundToInt


/** Bundles the four telemetry streams fed into the recorder so they can be combined as one flow. */
private data class Sample(val power: Float, val cadence: Float, val resistance: Float, val hr: Int?)

class OverlayService : LifecycleEnabledService() {
    companion object {
        // FLAG_KEEP_SCREEN_ON keeps the tablet awake while the overlay is visible, so it can't doze
        // mid-ride and drop the serial cadence feed — a per-ride backstop on top of the system's
        // stay-on-while-plugged-in setting.
        private const val DefaultOverlayFlags = (LayoutParams.FLAG_NOT_TOUCH_MODAL
                or LayoutParams.FLAG_NOT_FOCUSABLE
                or LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or LayoutParams.FLAG_KEEP_SCREEN_ON)


        private const val OverlayServiceId = 2032

        val OverlayHeightDp = 110.dp

        //Increases the size of the touch target during the hidden state
        const val HiddenTouchTargetMarginPx = 40

        //The percentage up or down a vertical drag must go before the overlay is relocated
        //Defined relative to the height of the screen
        const val VerticalMoveDragThreshold = .5f

        // Replace with DeadSensorInterface to simulate a dead sensor
        val EmulatorSensorInterface by lazy { DummySensorInterface() }
    }


    override fun onCreate() {
        super.onCreate()

        val notificationManager = NotificationManagerCompat.from(this)
        startForeground(OverlayServiceId, prepareNotification(notificationManager))

        buildDialog()

        // A ride is starting and Bluetooth permissions are typically granted by now, so (re)connect
        // the remembered heart-rate strap without the user having to scan/pick again.
        HeartRateManager.autoReconnect()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("overlay service received intent")
        return START_STICKY
    }

    private fun buildDialog() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val screenSize = Size(
            resources.displayMetrics.widthPixels.toFloat(),
            resources.displayMetrics.heightPixels.toFloat()
        )

        val rawSensorInterface = if (IsRunningOnPeloton) {
            if (IsBikePlus) {
                PelotonBikePlusSensorInterface(this).also {
                    lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onDestroy(owner: LifecycleOwner) {
                            it.stop()
                        }
                    })
                }
            } else {
                PelotonBikeSensorInterfaceV1New(this).also {
                    lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onDestroy(owner: LifecycleOwner) {
                            it.stop()
                        }
                    })
                }
            }

        } else {
            EmulatorSensorInterface
        }

        // Compensate for backpedaling (real cadence, ~0 measured power) so the gauge/graph/speed and
        // recorded output treat a backward recovery spin like forward pedaling. Everything downstream
        // reads through this wrapper; only power is synthesized.
        val sensorInterface = BackpedalCompensatingSensorInterface(rawSensorInterface).also {
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    it.stop()
                }
            })
        }

        // Dead-sensor detection watches the raw hardware feed, not the compensated power.
        val deadSensorDetector = DeadSensorDetector(rawSensorInterface, this.coroutineContext)
        val sensorViewModel = OverlaySensorViewModel(
            application,
            sensorInterface,
            deadSensorDetector
        )

        // If the sensor feed stalls (e.g. the Peloton service dropped after the tablet slept),
        // rebind the sensor service automatically instead of making the rider unplug the bike. The
        // rebind re-emits a fresh binder, which transparently restarts every downstream sensor flow.
        lifecycleScope.launch(Dispatchers.IO) {
            deadSensorDetector.deadSensorDetected.collect {
                Timber.w("Dead sensor detected; attempting sensor service reconnect")
                rawSensorInterface.reconnect()
            }
        }

        val timerViewModel = OverlayTimerViewModel(
            application,
            ConfigurationRepository(applicationContext, this)
        )
        val dialogViewModel = OverlayDialogViewModel(screenSize, sensorViewModel.isMinimized)

        // Feed live telemetry into the active 4x4 recorder (samples are deduped to ~1 Hz). The HR
        // flow is a StateFlow seeded with null, so combine proceeds immediately even before (or
        // without) a connected strap.
        lifecycleScope.launch(Dispatchers.IO) {
            combine(
                sensorInterface.power,
                sensorInterface.cadence,
                sensorInterface.resistance,
                HeartRateManager.bpm,
            ) { power, cadence, resistance, hr -> Sample(power, cadence, resistance, hr) }
                .collect { (power, cadence, resistance, hr) ->
                    FourByFour.recordSample(power, cadence, resistance, hr)
                }
        }
        // Beep whenever the session crosses into a new phase (the tablet has no vibrator).
        lifecycleScope.launch {
            FourByFour.transitions.collect { transition ->
                if (FourByFour.cuesEnabled.value) beepForTransition(transition.to)
            }
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            LayoutParams.TYPE_SYSTEM_ALERT
        }


        val defaultFlags = (LayoutParams.FLAG_NOT_TOUCH_MODAL
                or LayoutParams.FLAG_NOT_FOCUSABLE
                or LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or LayoutParams.FLAG_KEEP_SCREEN_ON)

        val overlayParams = LayoutParams(
            200,
            LayoutParams.WRAP_CONTENT,
            layoutFlag,
            defaultFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            disableAnimations()
        }

        val touchTargetParams = LayoutParams().apply {
            copyFrom(overlayParams)
            disableAnimations()
        }

        val touchTargetView = FrameLayout(this).apply {
            lifecycleViaService()
            setOnClickListener {
                sensorViewModel.onOverlayPressed()
            }
            layoutParams = ViewGroup.LayoutParams(100, 100)
        }

        val overlayView = ComposeView(this).apply {
            lifecycleViaService()
            setViewCompositionStrategy(
                ViewCompositionStrategy
                    .DisposeOnLifecycleDestroyed(this@OverlayService)
            )
            setContent {
                Overlay(
                    sensorViewModel,
                    timerViewModel,
                    OverlayHeightDp,
                    dialogViewModel.dialogLocation.collectAsState(),
                    dialogViewModel::processHorizontalDrag,
                    dialogViewModel::processVerticalDrag,
                    dialogViewModel::processHideProgress,
                    dialogViewModel::onOverlayLayout,
                    dialogViewModel::onTimerOverlayLayout
                )
            }
            alpha = 0.9f
            isFocusable = false
            clipToPadding = false
            clipChildren = false
            clipToOutline = false
        }
        wm.addView(overlayView, overlayParams)

        wm.addView(touchTargetView, touchTargetParams)
        touchTargetView.clipChildren = false
        touchTargetView.clipToPadding = false
        //Subscribe to Dialog view model and update views
        lifecycleScope.launchWhenResumed {
            combine(
                dialogViewModel.dialogOrigin,
                dialogViewModel.dialogGravity,
                dialogViewModel.partialOverlayFlags,
                dialogViewModel.touchTargetHeight,
                dialogViewModel.dialogSizeParams,
                dialogViewModel.minimizedDialogSizeParams
            ) { values ->
                val origin = values[0] as Offset
                val gravity = values[1] as Int
                val overlayFlags = values[2] as Int
                val touchTargetHeight = values[3] as Float
                val (width, height)  = values[4] as Pair<Int,Int>
                val (mWidth, mHeight)  = values[5] as Pair<Int,Int>
                overlayParams.x = origin.x.roundToInt()
                overlayParams.y = origin.y.roundToInt()
                overlayParams.flags = DefaultOverlayFlags or overlayFlags
                overlayParams.gravity = gravity
                overlayParams.width = width
                overlayParams.height = if(sensorViewModel.isMinimized.value){
                    mHeight
                }else{
                    height
                }
                touchTargetParams.x = origin.x.roundToInt()
                touchTargetParams.y = origin.y.roundToInt()
                touchTargetParams.gravity = gravity
                touchTargetParams.width = mWidth
                touchTargetParams.height = touchTargetHeight.roundToInt()
                touchTargetView.visibility = if(touchTargetHeight > 0f){
                    View.VISIBLE
                }else{
                    View.GONE
                }
                disableClipOnParents(overlayView)
                wm.updateViewLayout(overlayView, overlayParams)
                wm.updateViewLayout(touchTargetView, touchTargetParams)
            }.collect {}
        }
    }

    /**
     * Audible cue on phase change, played on the media stream (respects the bike's volume). A short
     * urgent double/triple beep going into a hard interval, a single softer beep easing off, and a
     * distinct pattern when the session finishes. The [ToneGenerator] is released after the tone so
     * it doesn't hold the audio focus/native resource.
     */
    private fun beepForTransition(to: PhaseType) {
        val (toneType, durationMs) = when (to) {
            PhaseType.Work -> ToneGenerator.TONE_CDMA_ABBR_ALERT to 600
            PhaseType.Recovery, PhaseType.CoolDown, PhaseType.WarmUp -> ToneGenerator.TONE_PROP_BEEP to 250
            PhaseType.Finished -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD to 900
        }
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
            tone.startTone(toneType, durationMs)
            lifecycleScope.launch {
                kotlinx.coroutines.delay((durationMs + 250).toLong())
                tone.release()
            }
        } catch (e: RuntimeException) {
            // ToneGenerator can throw if the audio path is momentarily unavailable; a missed beep is
            // non-fatal (the vibration cue still fires).
            Timber.e(e, "Failed to play phase-change beep")
        }
    }

    fun disableClipOnParents(v: View) {
        if (v.parent == null) {
            return
        }
        if (v is ViewGroup) {
            v.clipChildren = false
        }
        if (v.parent is View) {
            disableClipOnParents(v.parent as View)
        }
    }

    private fun prepareNotification(notificationManager: NotificationManagerCompat): Notification {
        val channelId = UUID.randomUUID().toString()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notificationManager.getNotificationChannel(channelId) == null
        ) {
            val name: CharSequence = getString(R.string.overlay_notification)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance)
            channel.enableVibration(false)
            notificationManager.createNotificationChannel(channel)
        }
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val intentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            intentFlags
        )

        val notificationBuilder: NotificationCompat.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationCompat.Builder(this, channelId)
            } else {
                @Suppress("DEPRECATION")
                NotificationCompat.Builder(this)
            }

        notificationBuilder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        notificationBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        return notificationBuilder.build()
    }
}

