package com.agusvr.windows

import com.agusvr.R

/**
 * Registry of internal Agus VR applications (module: AgusWindowSystem).
 * Each app opens as a floating spatial window with a default size.
 */
enum class VrApp(
    val appId: String,
    val titleRes: Int,
    val iconRes: Int,
    val defWdp: Int,
    val defHdp: Int,
    val accentHex: String
) {
    STORE("store", R.string.app_store, R.drawable.ic_store, 470, 340, "#FFC24D"),
    LIBRARY("library", R.string.app_library, R.drawable.ic_library, 470, 340, "#4DE8FF"),
    BROWSER("browser", R.string.app_browser, R.drawable.ic_browser, 500, 360, "#5EFFB1"),
    FILES("files", R.string.app_files, R.drawable.ic_files, 440, 330, "#9D6BFF"),
    SETTINGS("settings", R.string.app_settings, R.drawable.ic_settings, 400, 380, "#4DE8FF"),
    PERFORMANCE("performance", R.string.app_performance, R.drawable.ic_performance, 400, 320, "#5EFFB1"),
    HANDLAB("handlab", R.string.app_handlab, R.drawable.ic_handlab, 440, 350, "#FF5EC7"),
    MODELLAB("modellab", R.string.app_modellab, R.drawable.ic_modellab, 520, 380, "#9D6BFF"),
    STATUS("status", R.string.app_status, R.drawable.ic_status, 380, 320, "#4DE8FF"),
    GAME("game", R.string.app_library, R.drawable.ic_play, 480, 340, "#4DE8FF"),
    TEXTVIEW("textview", R.string.app_files, R.drawable.ic_file, 430, 330, "#A9B8D8"),
    IMAGEVIEW("imageview", R.string.app_files, R.drawable.ic_image, 420, 320, "#FF5EC7");

    companion object {
        fun byId(id: String): VrApp? = entries.firstOrNull { it.appId == id }
    }
}
