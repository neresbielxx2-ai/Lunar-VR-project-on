package com.agusvr.util

import android.util.Log

/** Central logging with the AGUS tag. Debug builds log everything; release keeps warnings. */
object Logx {
    private const val TAG = "AgusVR"
    var verboseEnabled: Boolean = BuildConfigHelper.DEBUG

    fun d(scope: String, msg: String) {
        if (verboseEnabled) Log.d(TAG, "[$scope] $msg")
    }

    fun i(scope: String, msg: String) {
        Log.i(TAG, "[$scope] $msg")
    }

    fun w(scope: String, msg: String, t: Throwable? = null) {
        Log.w(TAG, "[$scope] $msg", t)
    }

    fun e(scope: String, msg: String, t: Throwable? = null) {
        Log.e(TAG, "[$scope] $msg", t)
    }
}

internal object BuildConfigHelper {
    val DEBUG: Boolean get() = com.agusvr.BuildConfig.DEBUG
}
