package com.agusvr.storage

import android.content.Context
import com.agusvr.util.Logx
import java.io.File

/**
 * Organized app-private storage tree (module: AgusStorage).
 *
 * Everything lives inside the Android sandbox:
 *   Android/data/com.agusvr/files/AgusVR/
 *       Library/   — library index + covers metadata
 *       Games/     — installed runnable web games (html / extracted zips)
 *       Apps/      — installed web-app shortcuts (meta json)
 *       Models/    — imported 3D models (obj/glb/gltf)
 *       Images/    — covers, imported images, project snapshots
 *       Downloads/ — temporary download area
 *       Projects/  — Model Lab projects
 *       Settings/  — exported settings snapshots
 *
 * No broad storage permission is used; user files come through the Storage
 * Access Framework (ACTION_OPEN_DOCUMENT) and are copied inside the sandbox.
 */
object AgusPaths {

    lateinit var root: File
        private set

    lateinit var library: File; private set
    lateinit var games: File; private set
    lateinit var apps: File; private set
    lateinit var models: File; private set
    lateinit var images: File; private set
    lateinit var downloads: File; private set
    lateinit var projects: File; private set
    lateinit var settings: File; private set
    lateinit var covers: File; private set
    lateinit var projectCovers: File; private set

    fun init(context: Context) {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        root = File(base, "AgusVR")
        library = File(root, "Library").also { it.mkdirs() }
        games = File(root, "Games").also { it.mkdirs() }
        apps = File(root, "Apps").also { it.mkdirs() }
        models = File(root, "Models").also { it.mkdirs() }
        images = File(root, "Images").also { it.mkdirs() }
        downloads = File(root, "Downloads").also { it.mkdirs() }
        projects = File(root, "Projects").also { it.mkdirs() }
        settings = File(root, "Settings").also { it.mkdirs() }
        covers = File(images, "covers").also { it.mkdirs() }
        projectCovers = File(images, "projects").also { it.mkdirs() }
        Logx.i("Storage", "AgusVR root: ${root.absolutePath}")
    }

    fun ensure(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** A non-colliding file inside [dir] for [name]. */
    fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val baseName = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (f.exists() && i < 1000) {
            f = File(dir, "$baseName ($i)$ext")
            i++
        }
        return f
    }

    fun freeSpaceBytes(): Long = try {
        root.usableSpace
    } catch (_: Throwable) {
        0L
    }

    fun pathLabel(file: File): String {
        val r = root.absolutePath
        val p = file.absolutePath
        return if (p.startsWith(r)) "AgusVR" + p.substring(r.length) else p
    }
}
