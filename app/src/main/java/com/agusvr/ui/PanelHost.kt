package com.agusvr.ui

import android.content.Context
import android.net.Uri
import com.agusvr.performance.QualityProfile
import com.agusvr.windows.VrApp

/**
 * Bridge between floating panels and the VR activity (module: AgusSpatialUI).
 * Panels never hold an Activity reference directly; the activity installs
 * itself as [Panels.host] while it lives.
 */
interface PanelHost {
    fun hostContext(): Context
    fun pickFile(mimes: Array<String>, onPicked: (Uri?) -> Unit)
    fun pickImage(onPicked: (Uri?) -> Unit)
    fun requestNotificationPermission(onResult: (Boolean) -> Unit)
    fun openApp(app: VrApp, param: String? = null, title: String? = null)
    fun closeApp(app: VrApp)
    fun profile(): QualityProfile
}

object Panels {
    @Volatile
    var host: PanelHost? = null
}
