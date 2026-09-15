package com.agusvr.util

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** dp → px using the real display density. */
fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dp(v: Int): Int = dp(v.toFloat())

fun Context.dpF(v: Float): Float = v * resources.displayMetrics.density

fun Context.dpF(v: Int): Float = dpF(v.toFloat())

fun View.dpF(v: Float): Float = v * resources.displayMetrics.density

fun View.dpF(v: Int): Float = dpF(v.toFloat())

fun View.dp(v: Float): Int = context.dp(v)

fun View.dp(v: Int): Int = context.dp(v)

fun Float.clamp(lo: Float, hi: Float): Float = max(lo, min(hi, this))

fun Int.clamp(lo: Int, hi: Int): Int = max(lo, min(hi, this))

/** Format byte counts in a human readable way. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var u = 0
    while (v >= 1024 && u < units.size - 1) {
        v /= 1024.0
        u++
    }
    return if (u == 0) "${v.toInt()} ${units[u]}" else String.format(Locale.US, "%.1f %s", v, units[u])
}

private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

fun formatDate(millis: Long): String = dateFmt.format(Date(millis))

/** Total size of a directory tree (never throws). */
fun dirSize(dir: java.io.File): Long {
    var total = 0L
    try {
        dir.walkBottomUp().forEach { if (it.isFile) total += it.length() }
    } catch (_: Exception) {
    }
    return total
}

/** Extension (lowercase, no dot) of a name or path. */
fun extOf(name: String): String {
    val i = name.lastIndexOf('.')
    return if (i in 0 until name.length - 1) name.substring(i + 1).lowercase(Locale.US) else ""
}

/**
 * Finds the deepest visible view under a screen point (used by the hand ray for
 * hover highlight). Prefers clickable/interactive views.
 */
fun findViewAt(root: ViewGroup, x: Float, y: Float): View? {
    val rect = Rect()
    val loc = IntArray(2)
    var best: View? = null
    var bestDepth = -1
    val pt = android.graphics.PointF(x, y)

    fun walk(v: View, depth: Int) {
        if (v.visibility != View.VISIBLE || v.alpha < 0.05f) return
        v.getLocationOnScreen(loc)
        rect.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        if (!rect.contains(pt.x.toInt(), pt.y.toInt())) return
        val interactive = v.isClickable || v.isLongClickable || v.hasOnClickListeners()
        if (interactive && depth >= bestDepth) {
            best = v
            bestDepth = depth
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
    }
    walk(root, 0)
    return best
}

/** Screen rect of a view (absolute pixels). */
fun View.screenRect(): Rect {
    val loc = IntArray(2)
    getLocationOnScreen(loc)
    return Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
}

fun colorWithAlpha(color: Int, alpha: Float): Int =
    Color.argb((alpha.clamp(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

/** Convenience for FrameLayout children with margins. */
fun FrameLayout.LayoutParams.withMargins(l: Int, t: Int, r: Int, b: Int): FrameLayout.LayoutParams {
    setMargins(l, t, r, b)
    return this
}

/** Smooth exponential approach used by overlays and parallax. */
fun approach(current: Float, target: Float, factor: Float): Float =
    current + (target - current) * factor.clamp(0f, 1f)

fun nearlyEqual(a: Float, b: Float, eps: Float = 0.001f) = abs(a - b) < eps
