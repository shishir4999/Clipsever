package app.clipsaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that works through the download queue one job at a time,
 * so downloads keep running when the app is closed or the screen is off.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var lastStartId = 0
    private var lastNotify = 0L
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        startForeground(NOTIF_ID, progressNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker?.isActive != true) {
            worker = scope.launch { runQueue() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runQueue() {
        while (true) {
            val job = Downloads.nextQueued() ?: break
            runJob(job)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private suspend fun runJob(job: DownloadJob) {
        Downloads.modify(job.id) { it.copy(status = JobStatus.RUNNING, progress = 0f, etaSeconds = -1L) }
        refresh(force = true)
        try {
            val uri = Downloader.download(this, job) { refresh() }
            if (Downloads.get(job.id)?.status == JobStatus.CANCELLED) return
            Downloads.modify(job.id) {
                it.copy(status = JobStatus.DONE, progress = 1f, savedUri = uri.toString(), message = null)
            }
            notifyDone(job, uri)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (Downloads.get(job.id)?.status == JobStatus.CANCELLED) return
            val reason = (t.message ?: t.javaClass.simpleName).trim().take(400)
            Downloads.modify(job.id) { it.copy(status = JobStatus.FAILED, message = reason) }
        } finally {
            refresh(force = true)
        }
    }

    // --------------------------------------------------------- notifications

    private fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 700) return
        lastNotify = now
        nm.notify(NOTIF_ID, progressNotification())
    }

    private fun progressNotification(): Notification {
        val jobs = Downloads.jobs.value
        val running = jobs.firstOrNull { it.status == JobStatus.RUNNING }
        val waiting = jobs.count { it.status == JobStatus.QUEUED }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(running?.title ?: getString(R.string.notif_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (running != null) {
            val pct = (running.progress * 100).toInt()
            val text = if (waiting > 0) "$pct% · +$waiting" else "$pct%"
            builder.setContentText(text).setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notifyDone(job: DownloadJob, uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pending = PendingIntent.getActivity(
            this,
            job.id.hashCode(),
            view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notif_done))
            .setContentText(job.title)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(job.id.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIF_ID = 1
    }
}
