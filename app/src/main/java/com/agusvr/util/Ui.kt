package com.agusvr.util

import android.content.Context
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.agusvr.R
import com.agusvr.settings.SettingsRepo
import kotlin.math.PI
import kotlin.math.sin

/** Agus VR typography + feedback helpers (haptics / UI blips). */
object Ui {

    val display: Typeface by lazy { loadFont(R.font.orbitron) }
    val ui: Typeface by lazy { loadFont(R.font.manrope) }
    val mono: Typeface by lazy { loadFont(R.font.jetbrainsmono) }

    private var appContext: Context? = null

    private fun loadFont(res: Int): Typeface =
        try {
            appContext?.let { ResourcesCompat.getFont(it, res) } ?: Typeface.DEFAULT
        } catch (t: Throwable) {
            Logx.w("Ui", "font load failed res=$res", t)
            Typeface.DEFAULT
        }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun TextView.displayStyle(letterSpacing: Float = 0.12f) {
        typeface = Ui.display
        this.letterSpacing = letterSpacing
    }

    fun TextView.monoStyle(letterSpacing: Float = 0.05f) {
        typeface = mono
        this.letterSpacing = letterSpacing
    }

    fun TextView.uiStyle() {
        typeface = ui
    }

    // ------------------------------------------------------------------
    // Haptics
    // ------------------------------------------------------------------
    fun tick(view: View?, strong: Boolean = false) {
        if (!SettingsRepo.haptics) return
        try {
            if (view != null) {
                view.performHapticFeedback(
                    if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY
                )
                return
            }
            val ctx = appContext ?: return
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                val ms = if (strong) 35L else 14L
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (t: Throwable) {
            Logx.w("Ui", "haptic failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Synthesized UI blip (no audio assets needed)
    // ------------------------------------------------------------------
    private var track: AudioTrack? = null
    private var blipData: ShortArray? = null

    @Synchronized
    fun blip(high: Boolean = false) {
        if (!SettingsRepo.audio) return
        try {
            val data = blipData ?: synthBlip().also { blipData = it }
            var t = track
            if (t == null) {
                val sampleRate = 44100
                t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(data.size * 2 * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                t.write(data, 0, data.size)
                track = t
            }
            val rate = if (high) 1.35f else 1.0f
            t.setPlaybackRate((44100 * rate).toInt().coerceIn(4000, 192000))
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.setVolume(0.35f)
            t.play()
        } catch (t: Throwable) {
            Logx.w("Ui", "blip failed", t)
        }
    }

    private fun synthBlip(): ShortArray {
        val sampleRate = 44100
        val duration = 0.055
        val n = (sampleRate * duration).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val env = kotlin.math.exp(-t * 55.0)
            val v = sin(2 * PI * 880 * t) * 0.6 + sin(2 * PI * 1320 * t) * 0.4
            out[i] = (v * env * Short.MAX_VALUE * 0.5).toInt().toShort()
        }
        return out
    }

    fun releaseAudio() {
        try {
            track?.release()
        } catch (_: Throwable) {
        }
        track = null
    }
}
