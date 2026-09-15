package com.agusvr.home

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.agusvr.R
import com.agusvr.settings.SettingsRepo
import com.agusvr.spatial.SpatialMath
import com.agusvr.spatial.WorldPose
import com.agusvr.util.Ui
import com.agusvr.util.dp
import com.agusvr.windows.VrApp

/**
 * The VR home screen (module: AgusHome): eight custom-branded app tiles
 * floating on a cylinder arc in front of the user, around the central AGUS
 * hologram (drawn by the overlay). Tiles are real views — hover, dwell
 * selection and pinch all resolve through the same hit-testing as windows.
 */
class HomeController(
    private val layer: FrameLayout,
    private val onOpen: (VrApp) -> Unit
) {

    private class TileSpec(val app: VrApp, val yaw: Float, val pitch: Float, val dist: Float)

    private val specs = listOf(
        // top arc
        TileSpec(VrApp.MODELLAB, -26f, 16f, 1.06f),
        TileSpec(VrApp.LIBRARY, 0f, 20f, 1.00f),
        TileSpec(VrApp.HANDLAB, 26f, 16f, 1.06f),
        // middle arc (center stays free for the hologram)
        TileSpec(VrApp.STORE, -34f, -4f, 1.10f),
        TileSpec(VrApp.BROWSER, 34f, -4f, 1.10f),
        // bottom arc
        TileSpec(VrApp.PERFORMANCE, -26f, -24f, 1.06f),
        TileSpec(VrApp.FILES, 0f, -27f, 1.02f),
        TileSpec(VrApp.SETTINGS, 26f, -24f, 1.06f)
    )

    private val tiles = mutableListOf<Pair<TileView, TileSpec>>()
    private var visible = false
    private var fade = 0f
    private var fadeAnim: android.animation.ValueAnimator? = null

    // gyro parallax offsets applied to every tile pose
    var parallaxYaw = 0f
    var parallaxPitch = 0f

    fun build() {
        val context: Context = layer.context
        for (spec in specs) {
            val label = context.getString(spec.app.titleRes)
            val accent = Color.parseColor(spec.app.accentHex)
            val tile = TileView(context, label, spec.app.iconRes, accent)
            tile.setOnClickListener {
                Ui.tick(it, strong = true)
                onOpen(spec.app)
            }
            val size = context.dp(130)
            val lp = FrameLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            tile.layoutParams = lp
            tile.tag = 0f // SpatialMath reads the tag as the fade factor
            tile.visibility = View.GONE
            layer.addView(tile)
            tiles += tile to spec
        }
        relayout()
    }

    fun setVisible(visible: Boolean, animate: Boolean = true) {
        if (this.visible == visible) return
        this.visible = visible
        fadeAnim?.cancel()
        val target = if (visible) 1f else 0f
        if (visible) for ((tile, _) in tiles) tile.visibility = View.VISIBLE
        if (animate) {
            fadeAnim = android.animation.ValueAnimator.ofFloat(fade, target).apply {
                duration = if (visible) 320 else 180
                addUpdateListener {
                    fade = it.animatedValue as Float
                    relayout()
                }
            }
            fadeAnim?.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!visible) for ((tile, _) in tiles) tile.visibility = View.GONE
                }
            })
            fadeAnim?.start()
        } else {
            fade = target
            if (!visible) for ((tile, _) in tiles) tile.visibility = View.GONE
            relayout()
        }
    }

    fun isVisible(): Boolean = visible

    /** Reapply spatial transforms (resize, settings change, parallax update). */
    fun relayout() {
        val w = layer.width
        val h = layer.height
        if (w <= 0 || h <= 0) return
        val spread = SpatialMath.spreadFactor(w)
        for ((tile, spec) in tiles) {
            if (fade <= 0.001f && !visible) continue
            val pose = WorldPose(
                yaw = spec.yaw * spread + parallaxYaw,
                pitch = spec.pitch * spread + parallaxPitch,
                dist = spec.dist
            )
            pose.clamp()
            tile.tag = fade
            SpatialMath.applyCylinder(tile, pose, w, h, 1.12f, 1f, SettingsRepo.shadows)
        }
    }

    fun anyTileHovered(): Boolean = tiles.any { it.first.isHovered }

    fun destroy() {
        for ((tile, _) in tiles) layer.removeView(tile)
        tiles.clear()
    }
}
