package com.agusvr

import android.app.Application
import com.agusvr.notifications.SystemNotifier
import com.agusvr.performance.PerformanceMonitor
import com.agusvr.runtime.RuntimeCore
import com.agusvr.settings.SettingsRepo
import com.agusvr.storage.AgusPaths

/**
 * AGUS VR application entry point (module: AgusVRRuntime).
 * Initializes the storage tree, settings, notifications and the runtime core.
 */
class AgusApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.agusvr.util.Ui.init(this)
        AgusPaths.init(this)
        SettingsRepo.init(this)
        RuntimeCore.init(this)
        SystemNotifier.init(this)
        PerformanceMonitor.init(this)
    }

    override fun onTerminate() {
        PerformanceMonitor.shutdown()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: Application
            private set
    }
}
