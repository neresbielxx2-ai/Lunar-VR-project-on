package com.agusvr.runtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Lightweight app-wide event bus (module: AgusVRRuntime). */
sealed class AgusEvent {

    enum class ToastKind { INFO, SUCCESS, ERROR }

    /** Discreet spatial notification shown inside the VR world. */
    data class Toast(
        val iconRes: Int,
        val title: String,
        val message: String? = null,
        val kind: ToastKind = ToastKind.INFO
    ) : AgusEvent()

    /** Request to open an internal app window. [param] carries a URL / path / id when relevant. */
    data class OpenApp(val appId: String, val param: String? = null) : AgusEvent()

    /** Library content changed — panels should refresh. */
    data object LibraryChanged : AgusEvent()

    /** Models directory changed. */
    data object ModelsChanged : AgusEvent()

    /** Settings changed (panels refresh their controls). */
    data object SettingsChanged : AgusEvent()
}

object AgusBus {
    private val _events = MutableSharedFlow<AgusEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<AgusEvent> = _events.asSharedFlow()

    fun post(event: AgusEvent) {
        _events.tryEmit(event)
    }

    fun toast(title: String, message: String? = null, iconRes: Int = 0, kind: AgusEvent.ToastKind = AgusEvent.ToastKind.INFO) {
        post(AgusEvent.Toast(iconRes, title, message, kind))
    }
}
