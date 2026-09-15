package com.agusvr.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agusvr.R
import com.agusvr.util.Logx

/**
 * Android system notifications (module: AgusNotifications) — used for downloads
 * that keep running while the user does something else. In-VR feedback uses the
 * discreet [SpatialToasts] instead.
 */
object SystemNotifier {

    const val CHANNEL_DOWNLOADS = "agus_downloads"
    const val CHANNEL_SYSTEM = "agus_system"
    private const val BASE_ID = 4700

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        val dl = NotificationChannel(
            CHANNEL_DOWNLOADS, "Downloads e importações", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Progresso de downloads da AGUS STORE e importações" }
        val sys = NotificationChannel(
            CHANNEL_SYSTEM, "Sistema Agus VR", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Avisos do sistema espacial" }
        nm.createNotificationChannel(dl)
        nm.createNotificationChannel(sys)
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notificationIdFor(key: String): Int = BASE_ID + (key.hashCode() and 0xFFFF)

    /** Progress notification; pass progress < 0 for indeterminate, 100+ for "done" style. */
    fun updateDownload(id: Int, title: String, progress: Int, done: Boolean, failed: Boolean = false) {
        if (!hasPermission()) return
        try {
            val builder = NotificationCompat.Builder(appContext, CHANNEL_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setOngoing(!done && !failed)
                .setOnlyAlertOnce(true)
                .setSilent(true)
            if (failed) {
                builder.setContentText("Falha — veja o Agus VR para detalhes")
                    .setOngoing(false)
            } else if (done) {
                builder.setContentText("Concluído").setProgress(0, 0, false)
            } else if (progress < 0) {
                builder.setContentText("Iniciando…").setProgress(0, 0, true)
            } else {
                builder.setContentText("$progress%").setProgress(100, progress, false)
            }
            NotificationManagerCompat.from(appContext).notify(id, builder.build())
        } catch (t: Throwable) {
            Logx.w("Notifier", "notification failed", t)
        }
    }

    fun cancel(id: Int) {
        try {
            NotificationManagerCompat.from(appContext).cancel(id)
        } catch (_: Throwable) {
        }
    }
}
