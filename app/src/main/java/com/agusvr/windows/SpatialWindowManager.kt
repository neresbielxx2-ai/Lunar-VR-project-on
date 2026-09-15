package com.agusvr.windows

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.agusvr.settings.SettingsRepo
import com.agusvr.spatial.CurvedSurface
import com.agusvr.spatial.SpatialMath
import com.agusvr.spatial.WorldPose
import com.agusvr.util.Logx
import com.agusvr.util.Ui
import com.agusvr.util.clamp
import com.agusvr.util.dp
import kotlin.math.max

/** One open spatial window (module: AgusWindowSystem). */
class WindowEntity(
    val id: String,
    val app: VrApp,
    val frame: WindowFrameView,
    val pose: WorldPose,
    var wdp: Int,
    var hdp: Int,
    val param: String?
) {
    var minimized = false
    var anim: Float = 0f
    var contentView: View? = null
    var dispose: (() -> Unit)? = null
    var surface: com.agusvr.spatial.CurvedSurface? = null
    val restorePose = WorldPose()
}

/** Creates and destroys window content views; provided by the VR activity. */
interface PanelFactory {
    fun create(app: VrApp, param: String?, selfClose: () -> Unit): View?
    fun dispose(app: VrApp, content: View?)
}

/**
 * Manages the floating windows of the VR world: open/close/focus/minimize,
 * dragging through space (touch or hand-grab), resizing and depth layout.
 */
class SpatialWindowManager(
    private val layer: FrameLayout,
    private val factory: PanelFactory,
    private val contentHost: FrameLayout
) : WindowFrameView.WindowCallbacks {

    private val context: Context get() = layer.context
    private val windows = LinkedHashMap<String, WindowEntity>()
    private var openCount = 0
    private var focusedId: String? = null

    val openWindows: List<WindowEntity> get() = windows.values.toList()
    fun hasWindows(): Boolean = windows.isNotEmpty()
    fun focused(): WindowEntity? = windows[focusedId]

    fun isOpen(app: VrApp): Boolean = windows.values.any { it.app == app }

    // ------------------------------------------------------------------
    // Open / close
    // ------------------------------------------------------------------

    fun open(app: VrApp, param: String? = null, title: String? = null): WindowEntity? {
        // Single instance per app type — reopen focuses the existing window.
        windows.values.firstOrNull { it.app == app && (app != VrApp.GAME || it.param == param) }?.let {
            focus(it.id)
            if (it.minimized) restore(it)
            return it
        }
        if (windows.size >= 6) {
            // keep memory sane: close the least recently focused window
            val oldest = windows.values.firstOrNull { it.id != focusedId }
            oldest?.let { close(it.id) }
        }
        return try {
            val id = "${app.appId}_${System.currentTimeMillis()}"
            val accent = Color.parseColor(app.accentHex)
            val frame = WindowFrameView(context, app.appId, app.iconRes, title ?: context.getString(app.titleRes), accent)
            frame.callbacks = this

            val entity = WindowEntity(id, app, frame, defaultPose(), app.defWdp, app.defHdp, param)
            val content = factory.create(app, param) { close(id) }
            if (content == null) {
                Logx.w("Windows", "panel factory returned null for ${app.appId}")
                return null
            }
            entity.contentView = content
            frame.addContentView(content)

            val density = context.resources.displayMetrics.density
            val wPx = max(context.dp(240), (entity.wdp * density).toInt())
            val hPx = max(context.dp(160), (entity.hdp * density).toInt())
            // content stays attached (invisible) so WebView-backed panels work;
            // the curved surface presents it on a cylinder in the world layer.
            contentHost.addView(frame, FrameLayout.LayoutParams(wPx, hPx))
            val surface = CurvedSurface(context, frame, wPx, hPx)
            entity.surface = surface
            surface.tag = 0f
            layer.addView(surface, FrameLayout.LayoutParams(wPx, hPx, android.view.Gravity.CENTER))
            windows[id] = entity
            openCount++
            focus(id)
            applyPose(entity)

            // open animation
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    entity.anim = it.animatedValue as Float
                    surface.tag = entity.anim
                    applyPose(entity)
                }
            }
            animator.start()
            surface.markDirty()
            Logx.i("Windows", "opened ${app.appId} (id=$id)")
            entity
        } catch (t: Throwable) {
            Logx.e("Windows", "open failed for ${app.appId}", t)
            com.agusvr.runtime.AgusBus.toast(
                "Erro ao abrir ${context.getString(app.titleRes)}", t.message,
                com.agusvr.R.drawable.ic_close, com.agusvr.runtime.AgusEvent.ToastKind.ERROR
            )
            null
        }
    }

    fun close(id: String) {
        val entity = windows[id] ?: return
        if (focusedId == id) {
            focusedId = null
            windows.keys.lastOrNull { it != id }?.let { focus(it) }
        }
        windows.remove(id)
        val frame = entity.frame
        val animator = ValueAnimator.ofFloat(entity.anim, 0f).apply {
            duration = 180
            addUpdateListener {
                entity.anim = it.animatedValue as Float
                entity.surface?.tag = entity.anim
                frame.tag = entity.anim
                applyPose(entity)
            }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                try {
                    entity.dispose?.invoke()
                    factory.dispose(entity.app, entity.contentView)
                } catch (t: Throwable) {
                    Logx.w("Windows", "dispose failed", t)
                }
                frame.clearContent()
                contentHost.removeView(frame)
                entity.surface?.release()
                layer.removeView(entity.surface)
                entity.surface = null
            }
        })
        animator.start()
        Logx.i("Windows", "closed $id")
    }

    fun closeAll() {
        for (id in windows.keys.toList()) close(id)
    }

    fun focus(id: String) {
        focusedId = id
        val entity = windows[id] ?: return
        entity.surface?.bringToFront()
        entity.surface?.elevation = context.dpF(20f)
        for (other in windows.values) {
            if (other.id != id) other.surface?.elevation = context.dpF(10f)
        }
        entity.surface?.markDirty()
    }

    fun minimize(id: String) {
        val entity = windows[id] ?: return
        if (entity.minimized) return
        entity.restorePose.copyFrom(entity.pose)
        entity.minimized = true
        animatePoseTo(entity, WorldPose(entity.pose.yaw * 0.6f, -30f, 1.45f))
    }

    fun restore(entity: WindowEntity) {
        if (!entity.minimized) return
        entity.minimized = false
        animatePoseTo(entity, WorldPose(entity.restorePose.yaw, entity.restorePose.pitch, entity.restorePose.dist))
    }

    // ------------------------------------------------------------------
    // WindowFrameView.WindowCallbacks
    // ------------------------------------------------------------------

    override fun onDragDelta(view: WindowFrameView, dxPx: Float, dyPx: Float) {
        val entity = windows.values.firstOrNull { it.frame === view } ?: return
        if (entity.minimized) restore(entity)
        val w = max(1, layer.width)
        val h = max(1, layer.height)
        // degrees per pixel, scaled by the user's pointing sensitivity
        val kx = 78f / w * SettingsRepo.pointSensitivity
        val ky = 60f / h * SettingsRepo.pointSensitivity
        entity.pose.yaw = (entity.pose.yaw + dxPx * kx).clamp(-60f, 60f)
        entity.pose.pitch = (entity.pose.pitch + dyPx * ky).clamp(-32f, 32f)
        applyPose(entity)
    }

    override fun onResizeDelta(view: WindowFrameView, dxPx: Float, dyPx: Float) {
        val entity = windows.values.firstOrNull { it.frame === view } ?: return
        val density = context.resources.displayMetrics.density
        entity.wdp = (entity.wdp + (dxPx / density) * 0.8f).toInt().clamp(240, 760)
        entity.hdp = (entity.hdp + (dyPx / density) * 0.8f).toInt().clamp(160, 560)
        val wPx = (entity.wdp * density).toInt()
        val hPx = (entity.hdp * density).toInt()
        val flp = entity.frame.layoutParams as? FrameLayout.LayoutParams
        if (flp != null) {
            flp.width = wPx
            flp.height = hPx
            entity.frame.layoutParams = flp
        }
        entity.surface?.resize(wPx, hPx)
        val lp = entity.surface?.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = wPx
        lp.height = hPx
        entity.surface?.layoutParams = lp
    }

    override fun onClose(view: WindowFrameView) {
        windows.values.firstOrNull { it.frame === view }?.let { close(it.id) }
    }

    override fun onFocus(view: WindowFrameView) {
        windows.values.firstOrNull { it.frame === view }?.let {
            focus(it.id)
            if (it.minimized) restore(it)
        }
    }

    override fun onMinimize(view: WindowFrameView) {
        windows.values.firstOrNull { it.frame === view }?.let { minimize(it.id) }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun defaultPose(): WorldPose {
        val n = openCount % 5
        val pose = WorldPose(
            yaw = (n - 2) * 7f + (if (windows.isEmpty()) 0f else 4f),
            pitch = -2f - (n % 3) * 3f,
            dist = 0.95f + (n % 2) * 0.08f
        )
        pose.clamp()
        return pose
    }

    private fun animatePoseTo(entity: WindowEntity, to: WorldPose) {
        to.clamp()
        val from = WorldPose(entity.pose.yaw, entity.pose.pitch, entity.pose.dist)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                entity.pose.yaw = SpatialMath.lerp(from.yaw, to.yaw, f)
                entity.pose.pitch = SpatialMath.lerp(from.pitch, to.pitch, f)
                entity.pose.dist = SpatialMath.lerp(from.dist, to.dist, f)
                applyPose(entity)
            }
        }.start()
    }

    fun applyPose(entity: WindowEntity) {
        val w = layer.width
        val h = layer.height
        if (w <= 0 || h <= 0) return
        val view = entity.surface ?: return
        SpatialMath.applyCylinder(view, entity.pose, w, h, 1.34f, 1f, SettingsRepo.shadows)
        if (entity.minimized) view.alpha *= 0.6f
    }

    /** Reapply all windows (screen rotation/size change, settings change). */
    fun relayout() {
        for (e in windows.values) applyPose(e)
    }

    private var tickCounter = 0

    /** Called from the VR frame loop: keeps curved surfaces alive. */
    fun tick() {
        tickCounter++
        for (e in windows.values) {
            val s = e.surface ?: continue
            if (!e.minimized && e.anim > 0.5f) {
                if (e.id == focusedId) {
                    if (tickCounter % 2 == 0) s.markDirty()
                } else if (tickCounter % 20 == 0) {
                    s.markDirty()
                }
            }
            s.tick()
        }
    }

    fun setEntityTitle(id: String, title: String) {
        windows[id]?.frame?.setTitle(title)
    }

    fun visibleWindows(): List<WindowEntity> = windows.values.filter { it.anim > 0.05f }

    fun screenStats(): String {
        val count = windows.size
        val min = windows.values.count { it.minimized }
        return "janelas=$count minimizadas=$min foco=${focusedId ?: "-"}"
    }

    private fun Context.dpF(v: Float): Float = v * resources.displayMetrics.density
}
