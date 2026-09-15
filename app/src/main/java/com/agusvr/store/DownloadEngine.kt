package com.agusvr.store

import com.agusvr.notifications.SystemNotifier
import com.agusvr.storage.AgusPaths
import com.agusvr.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads internet content into the sandbox with progress, retries and
 * integrity checks (module: AgusStore). Downloads run on an app-scoped IO
 * dispatcher and keep a system notification so they survive screen changes.
 */
object DownloadEngine {

    private const val TAG = "Download"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Result(val file: File?, val error: String?)

    private data class Active(val job: Job, val title: String)

    private val active = mutableMapOf<String, Active>()

    fun isDownloading(key: String): Boolean = active.containsKey(key)

    fun cancel(key: String) {
        active.remove(key)?.job?.cancel()
    }

    /**
     * Downloads [url] into Downloads/ with the given file name.
     * [onProgress] (0..1, bytesDone) is invoked on the main thread.
     */
    fun download(
        key: String,
        url: String,
        fileName: String,
        title: String,
        onProgress: (Float, Long) -> Unit,
        onDone: (Result) -> Unit
    ) {
        if (active.containsKey(key)) {
            onDone(Result(null, "Download já em andamento para este item."))
            return
        }
        val job = scope.launch {
            val notifId = SystemNotifier.notificationIdFor(key)
            var tmp: File? = null
            try {
                SystemNotifier.updateDownload(notifId, title, -1, done = false)
                val target = AgusPaths.unique(AgusPaths.downloads, fileName)
                tmp = File(target.absolutePath + ".part")
                downloadInternal(url, tmp, notifId, title, onProgress)

                if (!isActive) throw kotlinx.coroutines.CancellationException("cancelado")
                if (!tmp.exists() || tmp.length() == 0L) {
                    throw java.io.IOException("arquivo baixado está vazio")
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    com.agusvr.storage.FileOps.copyFile(tmp, target)
                    tmp.delete()
                }
                withContext(Dispatchers.Main) {
                    SystemNotifier.updateDownload(notifId, title, 100, done = true)
                    onDone(Result(target, null))
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                tmp?.delete()
                withContext(Dispatchers.Main) {
                    SystemNotifier.cancel(notifId)
                    onDone(Result(null, "Download cancelado."))
                }
            } catch (t: Throwable) {
                Logx.e(TAG, "download failed: $url", t)
                tmp?.delete()
                withContext(Dispatchers.Main) {
                    SystemNotifier.updateDownload(notifId, title, 0, done = false, failed = true)
                    onDone(Result(null, describeError(t)))
                }
            } finally {
                active.remove(key)
            }
        }
        active[key] = Active(job, title)
    }

    private suspend fun downloadInternal(
        url: String, dest: File, notifId: Int, title: String, onProgress: (Float, Long) -> Unit
    ) {
        var conn: HttpURLConnection? = null
        try {
            var current = URL(url)
            var redirects = 0
            while (true) {
                conn = current.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "AgusVR/1.0 (Android)")
                val code = conn.responseCode
                if (code in 301..308) {
                    val loc = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    if (++redirects > 6) throw java.io.IOException("muitos redirecionamentos")
                    current = if (loc.startsWith("http")) URL(loc) else URL(current, loc)
                    continue
                }
                break
            }
            val code = conn?.responseCode ?: -1
            if (code !in 200..299) throw java.io.IOException("servidor respondeu HTTP $code")
            val total = conn.contentLengthLong.let { if (it <= 0) conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L else it }

            dest.parentFile?.mkdirs()
            var done = 0L
            var lastPublish = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastPublish > 220) {
                            lastPublish = now
                            val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
                            withContext(Dispatchers.Main) {
                                if (frac >= 0) SystemNotifier.updateDownload(notifId, title, (frac * 100).toInt(), false)
                                onProgress(frac, done)
                            }
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { onProgress(1f, done) }
        } finally {
            conn?.disconnect()
        }
    }

    private fun describeError(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "Sem conexão ou endereço inválido (${t.message})."
        is java.net.SocketTimeoutException -> "O servidor demorou demais para responder."
        is java.net.ConnectException -> "Não foi possível conectar ao servidor."
        is javax.net.ssl.SSLException -> "Falha de segurança TLS: ${t.message}"
        is java.io.IOException -> t.message ?: "Erro de rede."
        else -> t.message ?: t.javaClass.simpleName
    }

    fun cancelAll() {
        for ((_, a) in active.toList()) a.job.cancel()
        active.clear()
    }

    @Suppress("unused")
    private suspend fun idle() = delay(1)
}
