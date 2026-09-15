package com.agusvr.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.agusvr.settings.SettingsRepo
import com.agusvr.util.Logx
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Rear-camera passthrough (module: AgusCamera).
 *
 * Binds CameraX to a [PreviewView] (the "real environment" behind the spatial
 * UI) and streams RGBA analysis frames to the hand tracking engine. The FRONT
 * camera is never used. If permission or hardware is missing the engine simply
 * stays unbound and the VR activity falls back to the virtual environment.
 */
class CameraEngine {

    interface FrameListener {
        fun onAnalysisFrame(image: ImageProxy)
    }

    companion object {
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        fun cameraTiers(): Array<String> = arrayOf("low", "medium", "high")
    }

    private var provider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var bound = false
    private var frameListener: FrameListener? = null
    private var currentTier: String? = null

    @Volatile
    var lastError: String? = null
        private set

    fun setFrameListener(listener: FrameListener?) {
        frameListener = listener
    }

    fun isBound(): Boolean = bound

    /** Binds preview + analysis for the given lifecycle owner. Safe to call repeatedly. */
    fun start(context: Context, owner: LifecycleOwner, previewView: PreviewView) {
        val tier = SettingsRepo.cameraTier
        if (bound && tier == currentTier) return
        if (!hasPermission(context)) {
            lastError = "permissão de câmera não concedida"
            Logx.w("Camera", lastError ?: "")
            return
        }
        currentTier = tier
        val executor = analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = try {
                future.get()
            } catch (t: Throwable) {
                lastError = "provider indisponível: ${t.message}"
                Logx.e("Camera", "provider failed", t)
                return@addListener
            }
            this.provider = provider
            bind(provider, owner, previewView, tier)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(provider: ProcessCameraProvider, owner: LifecycleOwner, previewView: PreviewView, tier: String) {
        try {
            provider.unbindAll()

            val (previewSize, analysisSize) = sizesForTier(tier)

            val previewRes = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(previewSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            val p = Preview.Builder().setResolutionSelector(previewRes).build()
            p.setSurfaceProvider(previewView.surfaceProvider)

            val analysisRes = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(analysisSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            val a = ImageAnalysis.Builder()
                .setResolutionSelector(analysisRes)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            val listener = frameListener
            a.setAnalyzer(analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }) { proxy ->
                try {
                    listener?.onAnalysisFrame(proxy) ?: proxy.close()
                } catch (t: Throwable) {
                    Logx.w("Camera", "frame listener error", t)
                    runCatching { proxy.close() }
                }
            }

            // BACK camera ONLY — never the selfie camera (spec §2).
            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            provider.bindToLifecycle(owner, selector, p, a)
            preview = p
            analysis = a
            bound = true
            lastError = null
            Logx.i("Camera", "bound rear camera (tier=$tier preview=$previewSize analysis=$analysisSize)")
        } catch (t: Throwable) {
            bound = false
            lastError = t.message ?: "falha desconhecida"
            Logx.e("Camera", "bind failed", t)
        }
    }

    private fun sizesForTier(tier: String): Pair<Size, Size> = when (tier) {
        "low" -> Size(640, 480) to Size(320, 240)
        "high" -> Size(1600, 1200) to Size(640, 480)
        else -> Size(1280, 960) to Size(640, 480)
    }

    /** Rebind with the (possibly new) resolution tier from settings. */
    fun applyTierChange(context: Context, owner: LifecycleOwner, previewView: PreviewView) {
        if (!bound) return
        if (SettingsRepo.cameraTier != currentTier) {
            Logx.i("Camera", "tier change → rebind")
            start(context, owner, previewView)
        }
    }

    fun stop() {
        try {
            provider?.unbindAll()
        } catch (t: Throwable) {
            Logx.w("Camera", "unbind failed", t)
        }
        bound = false
    }

    fun release() {
        stop()
        analysisExecutor?.shutdown()
        analysisExecutor = null
        provider = null
        preview = null
        analysis = null
    }
}
