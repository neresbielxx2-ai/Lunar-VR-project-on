package com.agusvr.library

/** Library item types (module: AgusLibrary). */
object LibraryType {
    const val GAME = "GAME"          // runnable local web game (html or extracted zip)
    const val WEBAPP = "WEBAPP"      // internet shortcut opened in the Agus Browser
    const val MODEL = "MODEL"        // 3D model for the Model Lab
    const val IMAGE = "IMAGE"        // image usable as cover/art
}

/**
 * One entry of the local library, persisted in Library/library.json.
 * [entryFile] is the runnable html inside [dir] for extracted ZIP projects.
 */
data class LibraryItem(
    val id: String,
    val name: String,
    val type: String,
    /** Absolute path: a directory (zip game), a file (html/model/image) — or null for WEBAPP. */
    val path: String?,
    /** For zip/dir games: the detected main html file name (relative to [path]). */
    val entryFile: String? = null,
    /** For WEBAPP items. */
    val url: String? = null,
    val sizeBytes: Long = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    /** Cover file path (png/jpg) or null → auto-generated cover. */
    val cover: String? = null,
    val description: String? = null,
    /** store catalog id / "import" / "builtin" / "url" */
    val source: String = "import",
    /** original catalog id when installed from the AGUS STORE. */
    val originId: String? = null
) {
    val isRunnable: Boolean get() = type == LibraryType.GAME || type == LibraryType.WEBAPP
    val isModel: Boolean get() = type == LibraryType.MODEL
}
