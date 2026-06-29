package com.spop.poverlay

import com.spop.poverlay.fourbyfour.FourByFour
import com.spop.poverlay.hr.HeartRateManager
import com.spop.poverlay.util.IsRunningOnPeloton
import timber.log.Timber
import timber.log.Timber.*

class GrupettoApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        if(BuildConfig.DEBUG || IsRunningOnPeloton){
            Timber.plant(DebugTree())
        }
        FourByFour.init(this)
        HeartRateManager.init(this)
        // Reconnect to a previously paired strap if permissions are already granted.
        HeartRateManager.autoReconnect()
    }
}