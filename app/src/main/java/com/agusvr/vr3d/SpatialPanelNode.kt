package com.agusvr.vr3d

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.Matrix
import com.agusvr.util.Logx
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.VertexAttribute
import com.google.android.filament.filamat.MaterialBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/** Shared Filament material + shadow texture for all 3D panels. */
class PanelMaterialLibrary(private val engine: Engine) {

    val panelMaterial: Material? = try {
        val pkg = MaterialBuilder()
            .name("agus_panel3d")
            .shading(MaterialBuilder.Shading.UNLIT)
            .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
            .doubleSided(true)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .samplerParameter(
                MaterialBuilder.SamplerType.SAMPLER_2D,
                MaterialBuilder.SamplerFormat.FLOAT,
                MaterialBuilder.ParameterPrecision.MEDIUM,
                "baseColor"
            )
            .material(
                """
                void material(inout MaterialInputs material) {
                    prepareMaterial(material);
                    vec4 c = texture(materialParams_baseColor, materialInputs.uv0);
                    material.baseColor = c * materialParams.alpha;
                }
                """
            )
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "alpha")
            .build()
        if (pkg.isValid) {
            Material.Builder().payload(pkg.buffer, pkg.buffer.remaining()).build(engine)
        } else {
            Logx.e("Panel3D", "panel material package invalid")
            null
        }
    } catch (t: Throwable) {
        Logx.e("Panel3D", "panel material build failed", t)
        null
    }

    /** Soft radial shadow sprite shared by every panel. */
    val shadowTexture: Texture? = try {
        val n = 128
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(n / 2f, n / 2f, n / 2f,
            Color.argb(120, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), p)
        val buf = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf)
        buf.rewind()
        val tex = Texture.Builder().width(n).height(n).levels(1)
            .format(Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.UPLOADABLE or Texture.Usage.SAMPLEABLE).build(engine)
        tex.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
        bmp.recycle()
        tex
    } catch (t: Throwable) {
        Logx.w("Panel3D", "shadow texture failed", t)
        null
    }

    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE
    )

    fun panelInstance(tex: Texture): MaterialInstance? {
        val m = panelMaterial ?: return null
        val mi = m.createInstance()
        mi.setParameter("baseColor", tex, sampler)
        mi.setParameter("alpha", 1f)
        return mi
    }

    fun shadowInstance(): MaterialInstance? {
        val t = shadowTexture ?: return null
        val m = panelMaterial ?: return null
        val mi = m.createInstance()
        mi.setParameter("baseColor", t, sampler)
        mi.setParameter("alpha", 0.55f)
        return mi
    }

    fun destroy() {
        runCatching { shadowTexture?.let { engine.destroyTexture(it) } }
        runCatching { panelMaterial?.let { engine.destroyMaterial(it) } }
    }
}

/**
 * One floating 3D menu/panel node (module: AgusWindowSystem/3D): a curved
 * textured quad + a soft shadow quad, both children of the WORLD ROOT (never
 * of the camera). Transform = spherical position around the user + orientation
 * facing the user + scale; head rotation moves only the camera rig, so the
 * panel stays put in the virtual reference frame.
 */
class SpatialPanelNode(
    private val engine: Engine,
    private val materials: PanelMaterialLibrary,
    val bridge: PanelTextureBridge,
    val widthM: Float,
    val heightM: Float,
    val qualityCols: Int = 10,
    shadows: Boolean = true
) {
    // spherical placement around the user (world origin)
    var azimuth = 0f        // deg, + right
    var elevation = -2f     // deg, + up
    var distance = 1.7f     // meters
    var scale = 1f

    var animT = 0f          // 0 closed → 1 open
    var opening = true
    var closing = false
    var hovered = false
    var onClosed: (() -> Unit)? = null
    /** Content-space px height of the title bar region (drag handle). */
    var dragRegionPx = 0f

    val quaternion: FloatArray = QMath.identity()
    val position: FloatArray = floatArrayOf(0f, 0f, -distance)

    private val panelEntity: Int
    private val shadowEntity: Int
    private val panelInstance: MaterialInstance?
    private val shadowInstance: MaterialInstance?
    private val vb: VertexBuffer?
    private val ib: IndexBuffer?
    private val shadowVb: VertexBuffer?
    private val shadowIb: IndexBuffer?
    private val tcm get() = engine.transformManager
    private val panelInst: Int
    private val shadowInstT: Int
    private val matrix = FloatArray(16)
    private val qYaw = FloatArray(4)
    private val qPitch = FloatArray(4)
    private val conj = FloatArray(4)
    private var alive = true

    init {
        panelInstance = materials.panelInstance(bridge.texture)
        shadowInstance = if (shadows) materials.shadowInstance() else null

        val cols = qualityCols
        val rows = if (qualityCols >= 10) 6 else 4
        val (verts, idx) = curvedGrid(widthM, heightM, widthM * 1.45f, cols, rows)
        vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(verts.size / 5)
            .attribute(VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 20)
            .attribute(VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, 20)
            .build(engine)
        vb.setBufferAt(engine, 0, direct(verts))
        ib = IndexBuffer.Builder()
            .indexCount(idx.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        ib.setBuffer(engine, direct(idx))

        panelEntity = com.google.android.filament.EntityManager.get().create()
        panelInst = tcm.create(panelEntity)
        val rmb = RenderableManager.Builder(panelEntity)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
        panelInstance?.let { rmb.material(0, it) }
        rmb.build(engine, panelEntity)

        // shadow quad (flat, slightly behind)
        val (sverts, sidx) = flatGrid(widthM * 1.22f, heightM * 1.30f)
        shadowVb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(sverts.size / 5)
            .attribute(VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 20)
            .attribute(VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, 20)
            .build(engine)
        shadowVb.setBufferAt(engine, 0, direct(sverts))
        shadowIb = IndexBuffer.Builder()
            .indexCount(sidx.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        shadowIb.setBuffer(engine, direct(sidx))
        shadowEntity = com.google.android.filament.EntityManager.get().create()
        shadowInstT = tcm.create(shadowEntity)
        val smb = RenderableManager.Builder(shadowEntity)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, shadowVb, shadowIb)
        shadowInstance?.let { smb.material(0, it) }
        smb.build(engine, shadowEntity)

        updateTransform()
    }

    // ------------------------------------------------------------------
    // Geometry builders (interleaved pos3+uv2)
    // ------------------------------------------------------------------

    private fun curvedGrid(w: Float, h: Float, radius: Float, cols: Int, rows: Int): Pair<FloatArray, ShortArray> {
        val verts = FloatArray((cols + 1) * (rows + 1) * 5)
        var p = 0
        for (r in 0..rows) {
            val v = r / rows.toFloat()
            for (c in 0..cols) {
                val u = c / cols.toFloat()
                val phi = (u - 0.5f) * w / radius
                verts[p++] = sin(phi) * radius
                verts[p++] = (0.5f - v) * h
                verts[p++] = (1f - cos(phi)) * radius * 0.42f   // edges wrap toward viewer
                verts[p++] = u
                verts[p++] = v
            }
        }
        val idx = ShortArray(cols * rows * 6)
        var i = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val a = (r * (cols + 1) + c).toShort()
                val b = (a + 1).toShort()
                val d = (a + cols + 1).toShort()
                val e = (d + 1).toShort()
                idx[i++] = a; idx[i++] = d; idx[i++] = b
                idx[i++] = b; idx[i++] = d; idx[i++] = e
            }
        }
        return verts to idx
    }

    private fun flatGrid(w: Float, h: Float): Pair<FloatArray, ShortArray> {
        val verts = floatArrayOf(
            -w / 2, h / 2, 0f, 0f, 0f,
            w / 2, h / 2, 0f, 1f, 0f,
            -w / 2, -h / 2, 0f, 0f, 1f,
            w / 2, -h / 2, 0f, 1f, 1f
        )
        val idx = shortArrayOf(0, 2, 1, 1, 2, 3)
        return verts to idx
    }

    private fun direct(data: FloatArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        b.asFloatBuffer().put(data)
        b.rewind()
        return b
    }

    private fun direct(data: ShortArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
        b.asShortBuffer().put(data)
        b.rewind()
        return b
    }

    // ------------------------------------------------------------------
    // Transform / animation
    // ------------------------------------------------------------------

    fun placeInFront(azDeg: Float, elDeg: Float, distM: Float) {
        azimuth = azDeg.coerceIn(-62f, 62f)
        elevation = elDeg.coerceIn(-34f, 34f)
        distance = distM.coerceIn(0.9f, 3.2f)
        updateTransform()
    }

    fun updateTransform() {
        if (!alive) return
        val azR = Math.toRadians(azimuth.toDouble())
        val elR = Math.toRadians(elevation.toDouble())
        position[0] = (sin(azR) * cos(elR) * distance).toFloat()
        position[1] = (sin(elR) * distance).toFloat()
        position[2] = (-cos(azR) * cos(elR) * distance).toFloat()

        // orientation facing the user: RotY(az) ⊗ RotX(el)
        val ya = Math.toRadians(azimuth.toDouble()) / 2.0
        val pa = Math.toRadians(elevation.toDouble()) / 2.0
        qYaw[0] = 0f; qYaw[1] = sin(ya).toFloat(); qYaw[2] = 0f; qYaw[3] = cos(ya).toFloat()
        qPitch[0] = sin(pa).toFloat(); qPitch[1] = 0f; qPitch[2] = 0f; qPitch[3] = cos(pa).toFloat()
        QMath.multiply(qYaw, qPitch, quaternion)

        val ease = animT * animT * (3f - 2f * animT)
        val s = scale * (0.92f + 0.08f * ease)
        val approach = (1f - ease) * 0.16f

        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, position[0], position[1], position[2] - approach)
        val rot = FloatArray(16)
        QMath.toMatrix(quaternion, rot)
        Matrix.multiplyMM(matrix, 0, matrix, 0, rot, 0)
        Matrix.scaleM(matrix, 0, s, s, s)
        tcm.setTransform(panelInst, matrix)

        // shadow: same frame, pushed back and enlarged
        Matrix.translateM(matrix, 0, 0f, -heightM * 0.03f, -0.055f)
        Matrix.scaleM(matrix, 0, 1.06f, 1.10f, 1f)
        tcm.setTransform(shadowInstT, matrix)
    }

    /** Advances open/close animation; returns false when fully closed. */
    fun tick(dt: Float): Boolean {
        if (!alive) return false
        val target = if (closing) 0f else if (opening) 1f else animT
        val speed = dt / 0.22f
        animT = when {
            animT < target -> (animT + speed).coerceAtMost(target)
            animT > target -> (animT - speed).coerceAtLeast(target)
            else -> animT
        }
        panelInstance?.setParameter("alpha", animT * if (hovered) 1f else 0.96f)
        shadowInstance?.setParameter("alpha", 0.55f * animT)
        updateTransform()
        if (closing && animT <= 0.001f) {
            onClosed?.invoke()
            return false
        }
        return true
    }

    fun close() {
        closing = true
        opening = false
    }

    // ------------------------------------------------------------------
    // Picking (ray in world space → content px)
    // ------------------------------------------------------------------

    /** Returns content-space px coords of the ray/panel-plane hit, or null. */
    fun pick(rayO: FloatArray, rayD: FloatArray): FloatArray? {
        if (!alive || animT < 0.35f) return null
        val n = QMath.rotateVec(quaternion, floatArrayOf(0f, 0f, 1f))
        val denom = rayD[0] * n[0] + rayD[1] * n[1] + rayD[2] * n[2]
        if (Math.abs(denom) < 1e-5) return null
        val t = ((position[0] - rayO[0]) * n[0] + (position[1] - rayO[1]) * n[1] +
            (position[2] - rayO[2]) * n[2]) / denom
        if (t <= 0.02f) return null
        val hx = rayO[0] + rayD[0] * t - position[0]
        val hy = rayO[1] + rayD[1] * t - position[1]
        val hz = rayO[2] + rayD[2] * t - position[2]
        QMath.conjugate(quaternion, conj)
        val local = QMath.rotateVec(conj, floatArrayOf(hx, hy, hz))
        val ease = animT * animT * (3f - 2f * animT)
        val s = scale * (0.92f + 0.08f * ease)
        val lx = local[0] / s
        val ly = local[1] / s
        if (Math.abs(lx) > widthM / 2f || Math.abs(ly) > heightM / 2f) return null
        val u = lx / widthM + 0.5f
        val v = 0.5f - ly / heightM
        return floatArrayOf(u * bridge.contentW, v * bridge.contentH)
    }

    fun destroy() {
        if (!alive) return
        alive = false
        runCatching {
            engine.destroyEntity(panelEntity)
            engine.destroyEntity(shadowEntity)
            vb?.let { engine.destroyVertexBuffer(it) }
            ib?.let { engine.destroyIndexBuffer(it) }
            shadowVb?.let { engine.destroyVertexBuffer(it) }
            shadowIb?.let { engine.destroyIndexBuffer(it) }
            panelInstance?.let { engine.destroyMaterialInstance(it) }
            shadowInstance?.let { engine.destroyMaterialInstance(it) }
        }
        bridge.release()
    }
}
