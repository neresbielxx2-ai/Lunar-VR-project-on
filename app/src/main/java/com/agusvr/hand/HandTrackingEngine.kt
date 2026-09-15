package com.agusvr.hand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.agusvr.R
import com.agusvr.runtime.AgusBus
import com.agusvr.runtime.AgusEvent
import com.agusvr.runtime.RuntimeCore
import com.agusvr.settings.SettingsRepo
import com.agusvr.util.Logx
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Real hand tracking on the rear-camera feed (module: AgusHandTracking).
 *
 * Pipeline: CameraX ImageAnalysis (RGBA) → rotated Bitmap → MediaPipe
 * HandLandmarker (LIVE_STREAM, GPU with CPU fallback) → 21 landmarks per hand
 * → gesture classification → screen-space [HandFrame] on the main thread.
 *
 * Handedness: MediaPipe assumes mirrored (selfie) input. The Agus rear camera
 * feed is NOT mirrored, so Left/Right labels are swapped to report the user's
 * actual hands.
 */
class HandTrackingEngine(private val context: Context) : com.agusvr.camera.CameraEngine.FrameListener {

    companion object {
        private const val MODEL_PATH = "models/hand_landmarker.task"
        private const val NO_HANDS_TIMEOUT_MS = 1600L
    }

    /** Screen mapping for landmarks (cover-fit, mirrors PreviewView FILL_CENTER). */
    class CoordMapper {
        @Volatile var screenW: Int = 1
        @Volatile var screenH: Int = 1

        fun update(w: Int, h: Int) {
            screenW = max(1, w)
            screenH = max(1, h)
        }

        fun map(nx: Float, ny: Float, srcW: Int, srcH: Int): PointF {
            val scale = max(screenW.toFloat() / srcW, screenH.toFloat() / srcH)
            val offX = (screenW - srcW * scale) / 2f
            val offY = (screenH - srcH * scale) / 2f
            return PointF(offX + nx * srcW * scale, offY + ny * srcH * scale)
        }
    }

    val mapper = CoordMapper()

    interface Listener {
        fun onHandFrame(frame: HandFrame)
    }

    private var executor: ExecutorService? = null
    private var landmarker: HandLandmarker? = null
    private val ready = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var listener: Listener? = null
    private var reusableBitmap: Bitmap? = null
    private val rotateMatrix = Matrix()

    private var frameCounter = 0L
    private var lastTimestampMs = 0L
    private var lastHandsSeenMs = 0L
    private var activeDelegate: Delegate? = null

    // tracking stats
    @Volatile var trackingFps: Float = 0f
        private set
    @Volatile var latencyMs: Long = 0
        private set
    private var resultCountWindow = 0
    private var resultWindowStart = 0L
    private val submitTimes = HashMap<Long, Long>()

    fun setListener(l: Listener?) {
        listener = l
    }

    /** (Re)creates the landmarker honoring settings. Must be called before frames flow. */
    fun start() {
        if (closed.get()) return
        if (!SettingsRepo.handEnabled) {
            RuntimeCore.setHandState(RuntimeCore.HandEngineState.DISABLED)
            return
        }
        if (!modelPresent()) {
            RuntimeCore.setHandState(RuntimeCore.HandEngineState.UNSUPPORTED)
            AgusBus.post(
                AgusEvent.Toast(
                    R.drawable.ic_handlab,
                    "Hand tracking indisponível",
                    "Modelo não encontrado no APK — interação por toque continua ativa.",
                    AgusEvent.ToastKind.ERROR
                )
            )
            return
        }
        RuntimeCore.setHandState(RuntimeCore.HandEngineState.INITIALIZING)
        val exec = executor ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "agus-hand").apply { priority = Thread.NORM_PRIORITY + 1 }
        }.also { executor = it }
        exec.execute {
            if (closed.get()) return@execute
            val wanted = SettingsRepo.handDelegate
            val order = when (wanted) {
                "gpu" -> listOf(Delegate.GPU, Delegate.CPU)
                "cpu" -> listOf(Delegate.CPU)
                else -> listOf(Delegate.GPU, Delegate.CPU) // auto
            }
            var created: HandLandmarker? = null
            for (delegate in order) {
                created = tryCreate(delegate) ?: continue
                activeDelegate = delegate
                break
            }
            landmarker = created
            if (created != null) {
                ready.set(true)
                lastHandsSeenMs = SystemClock.uptimeMillis()
                RuntimeCore.setHandState(RuntimeCore.HandEngineState.NO_HANDS)
                Logx.i("Hand", "landmarker ready (delegate=$activeDelegate)")
            } else {
                ready.set(false)
                RuntimeCore.setHandState(RuntimeCore.HandEngineState.ERROR)
                AgusBus.post(
                    AgusEvent.Toast(
                        R.drawable.ic_handlab,
                        "Hand tracking falhou ao iniciar",
                        "GPU e CPU indisponíveis neste aparelho — use o controle por toque.",
                        AgusEvent.ToastKind.ERROR
                    )
                )
            }
        }
    }

    private fun modelPresent(): Boolean = try {
        context.assets.open(MODEL_PATH).use { it.available() > 1_000_000 }
    } catch (_: Throwable) {
        false
    }

    private fun tryCreate(delegate: Delegate): HandLandmarker? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_PATH)
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { err -> onError(err) }
            .build()
        HandLandmarker.createFromOptions(context, options)
    } catch (t: Throwable) {
        Logx.w("Hand", "delegate $delegate failed: ${t.message}")
        null
    }

    // ------------------------------------------------------------------
    // Camera frames
    // ------------------------------------------------------------------

    override fun onAnalysisFrame(image: ImageProxy) {
        try {
            val lm = landmarker
            if (closed.get() || !ready.get() || lm == null || !SettingsRepo.handEnabled) {
                image.close()
                return
            }
            frameCounter++
            val divisor = max(1, RuntimeProfileDivisor.divisor())
            if (frameCounter % divisor != 0L) {
                image.close()
                return
            }
            val w = image.width
            val h = image.height
            if (w <= 0 || h <= 0) {
                image.close()
                return
            }
            var bmp = reusableBitmap
            if (bmp == null || bmp.width != w || bmp.height != h) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                reusableBitmap = bmp
            }
            bmp.copyPixelsFromBuffer(image.planes[0].buffer)
            val rotation = image.imageInfo.rotationDegrees
            lastRotationDeg = rotation
            image.close()

            val rotated = if (rotation % 360 == 0) {
                bmp
            } else {
                rotateMatrix.reset()
                rotateMatrix.postRotate(rotation.toFloat())
                val r = Bitmap.createBitmap(bmp, 0, 0, w, h, rotateMatrix, true)
                if (r != bmp) r else bmp
            }

            val mpImage: MPImage = BitmapImageBuilder(rotated).build()

            val now = SystemClock.uptimeMillis()
            var ts = now
            if (ts <= lastTimestampMs) ts = lastTimestampMs + 1
            lastTimestampMs = ts
            submitTimes[ts] = now
            lm.detectAsync(mpImage, ts)
        } catch (t: Throwable) {
            Logx.w("Hand", "frame processing failed", t)
            runCatching { image.close() }
        }
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    private fun onResult(result: HandLandmarkerResult) {
        if (closed.get()) return
        try {
            val now = SystemClock.uptimeMillis()
            val tsList = result.timestampMs()
            val submitted = submitTimes.remove(tsList)
            if (submitted != null) latencyMs = now - submitted

            resultCountWindow++
            if (resultWindowStart == 0L) resultWindowStart = now
            if (now - resultWindowStart >= 1000L) {
                trackingFps = resultCountWindow * 1000f / (now - resultWindowStart)
                resultCountWindow = 0
                resultWindowStart = now
            }

            val hands = ArrayList<HandSnapshot>(2)
            val landmarksAll = result.landmarks()
            val handednessAll = result.handednesses()
            var srcW = 1
            var srcH = 1

            for ((idx, lmNorm) in landmarksAll.withIndex()) {
                if (lmNorm.size < 21) continue
                // Rotated source dimensions inferred from bitmap at submission time.
                val bmp = reusableBitmap
                val rot = lastRotationDeg
                srcW = if (rot % 180 == 0) (bmp?.width ?: 640) else (bmp?.height ?: 480)
                srcH = if (rot % 180 == 0) (bmp?.height ?: 480) else (bmp?.width ?: 640)

                val norm = ArrayList<PointF>(21)
                val screen = ArrayList<PointF>(21)
                for (l in lmNorm) {
                    norm += PointF(l.x(), l.y())
                    screen += mapper.map(l.x(), l.y(), srcW, srcH)
                }
                val fingers = GestureRecognizer.fingerStates(norm)
                val gesture = GestureRecognizer.classify(norm, fingers)
                val handed = handednessAll.getOrNull(idx)
                val label = handed?.firstOrNull()?.categoryName() ?: ""
                val score = handed?.firstOrNull()?.score() ?: 0f
                // Rear camera is not mirrored → swap MediaPipe's selfie labels.
                val handedness = when (label) {
                    "Left" -> Handedness.RIGHT
                    "Right" -> Handedness.LEFT
                    else -> Handedness.UNKNOWN
                }
                val tip = screen.getOrElse(HandSkeleton.INDEX_TIP) { PointF() }
                val pip = screen.getOrElse(HandSkeleton.INDEX_PIP) { tip }
                val dir = GestureRecognizer.rayDirection(pip, tip)
                hands += HandSnapshot(
                    handedness = handedness,
                    handednessScore = score,
                    landmarksNorm = norm,
                    landmarksScreen = screen,
                    fingersExtended = fingers,
                    gesture = gesture,
                    confidence = if (!handed.isNullOrEmpty()) score else 0.5f,
                    fingertipScreen = PointF(tip.x, tip.y),
                    rayDirScreen = dir,
                    proximity = GestureRecognizer.proximity(norm),
                    timestampMs = tsList
                )
            }

            val frame = HandFrame(hands, srcW, srcH, latencyMs, tsList)
            if (hands.isNotEmpty()) lastHandsSeenMs = now

            val state = when {
                !SettingsRepo.handEnabled -> RuntimeCore.HandEngineState.DISABLED
                hands.isNotEmpty() -> RuntimeCore.HandEngineState.RUNNING
                now - lastHandsSeenMs > NO_HANDS_TIMEOUT_MS -> RuntimeCore.HandEngineState.NO_HANDS
                else -> RuntimeCore.HandEngineState.RUNNING
            }
            RuntimeCore.setHandState(state)

            mainHandler.post {
                if (!closed.get()) listener?.onHandFrame(frame)
            }
        } catch (t: Throwable) {
            Logx.w("Hand", "result handling failed", t)
        }
    }

    @Volatile
    private var lastRotationDeg = 0

    private fun onError(err: RuntimeException) {
        Logx.e("Hand", "landmarker error: ${err.message}")
        RuntimeCore.setHandState(RuntimeCore.HandEngineState.ERROR)
        mainHandler.post {
            AgusBus.post(
                AgusEvent.Toast(
                    R.drawable.ic_handlab, "Erro no hand tracking",
                    err.message ?: "falha desconhecida", AgusEvent.ToastKind.ERROR
                )
            )
        }
    }

    /** Called from the analysis path to remember rotation for dimension mapping. */
    fun noteRotation(deg: Int) {
        lastRotationDeg = deg
    }

    fun restartIfSettingsChanged() {
        val wanted = SettingsRepo.handDelegate
        val enabled = SettingsRepo.handEnabled
        if (!enabled) {
            closeInternal()
            RuntimeCore.setHandState(RuntimeCore.HandEngineState.DISABLED)
            return
        }
        val current = when (activeDelegate) {
            Delegate.GPU -> "gpu"
            Delegate.CPU -> "cpu"
            null -> null
            else -> null
        }
        if (current == null || (wanted != "auto" && wanted != current)) {
            Logx.i("Hand", "recreating landmarker (delegate=$wanted)")
            closeInternal()
            start()
        } else if (!ready.get()) {
            start()
        }
    }

    private fun closeInternal() {
        ready.set(false)
        val lm = landmarker
        landmarker = null
        val exec = executor
        if (lm != null && exec != null) {
            exec.execute {
                try {
                    lm.close()
                } catch (t: Throwable) {
                    Logx.w("Hand", "close failed", t)
                }
            }
        }
    }

    fun stop() {
        closeInternal()
        RuntimeCore.setHandState(RuntimeCore.HandEngineState.IDLE)
    }

    fun release() {
        closed.set(true)
        closeInternal()
        executor?.shutdown()
        executor = null
        mainHandler.removeCallbacksAndMessages(null)
        reusableBitmap?.recycle()
        reusableBitmap = null
        listener = null
    }

    fun describeDelegate(): String = when (activeDelegate) {
        Delegate.GPU -> "GPU"
        Delegate.CPU -> "CPU"
        null -> "—"
        else -> "—"
    }
}

/** Indirection so the divisor comes from the live quality profile without a hard dependency cycle. */
internal object RuntimeProfileDivisor {
    var provider: () -> Int = { 2 }
    fun divisor(): Int = try {
        provider().coerceIn(1, 6)
    } catch (_: Throwable) {
        2
    }
}
