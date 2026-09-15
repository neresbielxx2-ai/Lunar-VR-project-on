package com.agusvr.ui.viewers

import android.content.Context
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.dp
import com.agusvr.util.extOf
import com.agusvr.util.formatBytes
import com.agusvr.windows.DisposableView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Plain-text viewer window (module: AgusFileManager companion). */
class TextViewerPanel(context: Context, private val path: String?) : LinearLayout(context), DisposableView {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val body: TextView

    init {
        orientation = VERTICAL
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        val file = path?.let { File(it) }
        addView(AgusWidgets.panelTitle(context, file?.name ?: "Texto"),
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body = AgusWidgets.monoText(context, "", AgusWidgets.TEXT, 10f)
        body.setTextIsSelectable(true)
        val scroll = ScrollView(context).apply {
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(8) })

        if (file == null || !file.exists()) {
            body.text = "Arquivo não encontrado."
        } else {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        val max = 256 * 1024
                        if (file.length() > max) {
                            file.inputStream().use { inp ->
                                val buf = ByteArray(max.toInt())
                                var off = 0
                                while (off < buf.size) {
                                    val n = inp.read(buf, off, buf.size - off)
                                    if (n <= 0) break
                                    off += n
                                }
                                String(buf, 0, off, Charsets.UTF_8)
                            } +
                                "\n\n… conteúdo truncado (${formatBytes(file.length())} no total)"
                        } else file.readText()
                    }.getOrElse { "Não foi possível ler o arquivo: ${it.message}" }
                }
                body.text = text
            }
        }
    }

    override fun dispose() = scope.cancel()
}

/** Image viewer window: subsampled decode so big photos never OOM. */
class ImageViewerPanel(context: Context, private val path: String?) : LinearLayout(context), DisposableView {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val image: ImageView
    private val caption: TextView

    init {
        orientation = VERTICAL
        setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
        val file = path?.let { File(it) }
        addView(AgusWidgets.panelTitle(context, file?.name ?: "Imagem"),
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        addView(image, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(6) })
        caption = AgusWidgets.monoText(context, "carregando…")
        caption.gravity = Gravity.CENTER
        addView(caption, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(6)
        })

        if (file == null || !file.exists()) {
            caption.text = "Arquivo não encontrado."
        } else {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeFile(file.absolutePath, opts)
                    }.getOrNull()
                }
                if (bmp != null) {
                    image.setImageBitmap(bmp)
                    caption.text = "${extOf(file.name).uppercase()} · ${bmp.width}×${bmp.height} px · ${formatBytes(file.length())}"
                } else {
                    caption.text = "Formato não suportado pelo Agus VR."
                }
            }
        }
    }

    override fun dispose() {
        image.setImageDrawable(null)
        scope.cancel()
    }
}
