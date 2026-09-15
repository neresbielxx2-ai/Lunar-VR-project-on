package com.agusvr.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import com.agusvr.storage.AgusPaths
import com.agusvr.util.Logx
import com.agusvr.util.Ui
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.random.Random

/**
 * Cover art for library/store items (module: AgusLibrary).
 *
 * When an item has no image, a nice procedural cover is generated from its
 * name (deterministic gradient + monogram + grid art), saved as PNG in
 * Images/covers and cached in a small LruCache to protect memory.
 */
object CoverFactory {

    private const val TAG = "Covers"
    private const val W = 360
    private const val H = 480

    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun coverFor(item: LibraryItem): Bitmap? = coverForId(item.id, item.name, item.cover, item.type)

    fun coverForId(id: String, name: String, coverPath: String?, type: String): Bitmap? {
        val key = coverPath ?: "auto:$id:$name"
        cache.get(key)?.let { return it }
        val bmp = if (coverPath != null && File(coverPath).exists()) {
            decodeSampled(File(coverPath), W, H) ?: generate(name, type, id).also { saveAuto(id, it) }
        } else {
            val auto = File(AgusPaths.covers, "$id.png")
            if (auto.exists()) decodeSampled(auto, W, H) ?: generate(name, type, id)
            else generate(name, type, id).also { saveAuto(id, it) }
        }
        cache.put(key, bmp)
        return bmp
    }

    fun invalidate(item: LibraryItem) {
        cache.remove(item.cover ?: "auto:${item.id}:${item.name}")
    }

    private fun saveAuto(id: String, bmp: Bitmap) {
        try {
            val f = File(AgusPaths.covers, "$id.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) }
        } catch (t: Throwable) {
            Logx.w(TAG, "cover save failed", t)
        }
    }

    /** Saves a user-picked image as the new cover. Returns the stored file. */
    fun storeUserCover(id: String, src: File): File {
        val ext = src.extension.lowercase().ifEmpty { "png" }
        val dst = File(AgusPaths.covers, "$id.$ext")
        com.agusvr.storage.FileOps.copyFile(src, dst)
        cache.evictAll()
        return dst
    }

    fun decodeSampled(file: File, reqW: Int, reqH: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > reqW * 2 || bounds.outHeight / sample > reqH * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    } catch (t: Throwable) {
        Logx.w(TAG, "decode failed ${file.name}", t)
        null
    }

    // ------------------------------------------------------------------
    // Procedural cover
    // ------------------------------------------------------------------

    private fun generate(name: String, type: String, seedKey: String): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(seedKey.hashCode().toLong())

        val palettes = listOf(
            intArrayOf(Color.parseColor("#0E2A47"), Color.parseColor("#123E5C"), Color.parseColor("#4DE8FF")),
            intArrayOf(Color.parseColor("#241548"), Color.parseColor("#3A2170"), Color.parseColor("#9D6BFF")),
            intArrayOf(Color.parseColor("#0C3327"), Color.parseColor("#12523C"), Color.parseColor("#5EFFB1")),
            intArrayOf(Color.parseColor("#3A1330"), Color.parseColor("#5C1F4A"), Color.parseColor("#FF5EC7")),
            intArrayOf(Color.parseColor("#33240E"), Color.parseColor("#57401A"), Color.parseColor("#FFC24D"))
        )
        val pal = palettes[rnd.nextInt(palettes.size)]

        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), pal[0], pal[1], Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)

        // grid art
        val grid = Paint().apply {
            color = Color.argb(28, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1.4f
        }
        val step = 26f
        var gx = -H * 0.2f
        while (gx < W) {
            c.drawLine(gx, H.toFloat(), gx + H * 0.35f, H * 0.42f, grid)
            gx += step
        }
        var gy = H * 0.42f
        while (gy < H) {
            c.drawLine(0f, gy, W.toFloat(), gy, grid)
            gy += step * (1.2f + (gy / H))
        }

        // glow orb
        val orb = Paint().apply {
            shader = android.graphics.RadialGradient(
                W * (0.25f + rnd.nextFloat() * 0.5f), H * 0.3f, W * 0.45f,
                intArrayOf(Color.argb(120, Color.red(pal[2]), Color.green(pal[2]), Color.blue(pal[2])), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), orb)

        // type glyph
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.argb(200, 255, 255, 255)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val cx = W / 2f
        val cy = H * 0.34f
        val r = 46f
        val path = Path()
        when (type) {
            LibraryType.MODEL -> {
                // iso cube
                for (i in 0..5) {
                    val a = Math.toRadians(60.0 * i - 90.0)
                    val px = cx + r * kotlin.math.cos(a).toFloat()
                    val py = cy + r * kotlin.math.sin(a).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                c.drawPath(path, glyph)
                c.drawLine(cx, cy - r, cx, cy, glyph)
                c.drawLine(cx, cy, cx + r * 0.87f, cy + r * 0.5f, glyph)
                c.drawLine(cx, cy, cx - r * 0.87f, cy + r * 0.5f, glyph)
            }
            LibraryType.WEBAPP -> {
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                c.drawOval(rect, glyph)
                c.drawLine(cx - r, cy, cx + r, cy, glyph)
                c.drawOval(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), glyph)
            }
            LibraryType.IMAGE -> {
                val rect = RectF(cx - r, cy - r * 0.75f, cx + r, cy + r * 0.75f)
                c.drawRoundRect(rect, 12f, 12f, glyph)
                c.drawCircle(cx - r * 0.4f, cy - r * 0.25f, r * 0.18f, glyph)
                path.moveTo(cx - r, cy + r * 0.5f); path.lineTo(cx - r * 0.2f, cy - r * 0.1f)
                path.lineTo(cx + r * 0.3f, cy + r * 0.35f); path.lineTo(cx + r * 0.65f, cy)
                path.lineTo(cx + r, cy + r * 0.45f)
                c.drawPath(path, glyph)
            }
            else -> {
                // game controller-ish hexagon with play triangle
                for (i in 0..5) {
                    val a = Math.toRadians(60.0 * i - 90.0)
                    val px = cx + r * kotlin.math.cos(a).toFloat()
                    val py = cy + r * kotlin.math.sin(a).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                c.drawPath(path, glyph)
                path.reset()
                path.moveTo(cx - r * 0.3f, cy - r * 0.45f)
                path.lineTo(cx + r * 0.5f, cy)
                path.lineTo(cx - r * 0.3f, cy + r * 0.45f)
                path.close()
                c.drawPath(path, glyph)
            }
        }

        // name plate
        val plate = Paint().apply {
            shader = LinearGradient(0f, H * 0.62f, 0f, H.toFloat(), Color.TRANSPARENT, Color.parseColor("#CC05070F"), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, H * 0.62f, W.toFloat(), H.toFloat(), plate)

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            letterSpacing = 0.04f
        }
        namePaint.typeface = try { Ui.display } catch (_: Throwable) { null }
        val shortened = if (name.length > 20) name.take(19) + "…" else name
        c.drawText(shortened, 24f, H - 62f, namePaint)

        val typePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal[2]
            textSize = 15f
            letterSpacing = 0.35f
        }
        typePaint.typeface = try { Ui.mono } catch (_: Throwable) { null }
        c.drawText(typeLabel(type).uppercase(), 24f, H - 32f, typePaint)

        return bmp
    }

    private fun typeLabel(type: String) = when (type) {
        LibraryType.MODEL -> "modelo 3d"
        LibraryType.WEBAPP -> "web app"
        LibraryType.IMAGE -> "imagem"
        else -> "jogo web"
    }

    fun trimMemory() {
        cache.evictAll()
    }

    @Suppress("unused")
    private fun minf(a: Int, b: Int) = min(a, b)
}
