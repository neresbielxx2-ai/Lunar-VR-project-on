package com.agusvr.runtime

import android.content.Context
import com.agusvr.BuildConfig
import com.agusvr.util.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Central runtime state (module: AgusVRRuntime).
 * Holds device capabilities, hand tracking status and engine lifecycle flags so
 * every screen can gate features honestly instead of pretending they work.
 */
object RuntimeCore {

    const val VERSION = BuildConfig.VERSION_NAME

    private lateinit var appContext: Context

    var capabilities: DeviceCapabilities? = null
        private set

    private val _handState = MutableStateFlow(HandEngineState.IDLE)
    val handState: StateFlow<HandEngineState> = _handState

    private val _cameraActive = MutableStateFlow(false)
    val cameraActive: StateFlow<Boolean> = _cameraActive

    private val _vrRunning = MutableStateFlow(false)
    val vrRunning: StateFlow<Boolean> = _vrRunning

    /** Set when the user chose to enter VR without camera permission/hardware. */
    @Volatile
    var forceVirtualEnvironment: Boolean = false

    enum class HandEngineState(val label: String) {
        IDLE("inativo"),
        INITIALIZING("inicializando"),
        RUNNING("ativo"),
        NO_HANDS("procurando mãos"),
        DISABLED("desativado"),
        UNSUPPORTED("não suportado"),
        ERROR("erro")
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        capabilities = DeviceProbe.probe(appContext)
        Logx.i(
            "Runtime", "capabilities=$capabilities sdk=${BuildConfig.VERSION_NAME}"
        )
    }

    fun context(): Context = appContext

    fun setHandState(state: HandEngineState) {
        if (_handState.value != state) _handState.value = state
    }

    fun setCameraActive(active: Boolean) {
        _cameraActive.value = active
    }

    fun setVrRunning(running: Boolean) {
        _vrRunning.value = running
    }

    /** Whether the VR world should render the virtual environment instead of the camera feed. */
    fun useVirtualEnvironment(): Boolean =
        forceVirtualEnvironment || capabilities?.hasRearCamera != true

    fun describe(): String = buildString {
        val c = capabilities
        append("Agus VR v").append(VERSION).append(" (").append(BuildConfig.BUILD_TYPE).append(")\n")
        if (c != null) {
            append("Android ").append(c.androidSdk).append(" · tela ").append(c.screenClass)
                .append(" (").append(c.screenW).append("×").append(c.screenH).append(")\n")
            append("RAM: ").append(c.totalRamMb).append(" MB")
            if (c.lowRamDevice) append(" (modo baixo RAM)")
            append('\n')
            append("Câmera traseira: ").append(if (c.hasRearCamera) "OK" else "ausente").append('\n')
            append("Giroscópio: ").append(if (c.hasGyroscope) "OK" else "ausente").append('\n')
            append("Vibração: ").append(if (c.hasVibrator) "OK" else "ausente").append('\n')
            append("WebView: ").append(if (c.hasWebView) "OK" else "ausente").append('\n')
            append("Modelo hand tracking: ").append(if (c.handModelPresent) "presente" else "ausente (baixe no build)")
        } else {
            append("runtime não inicializado")
        }
    }
}
