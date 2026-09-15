package com.agusvr.windows

/** Panels implement this to release resources (WebView, Filament, scopes…) when their window closes. */
interface DisposableView {
    fun dispose()
}
