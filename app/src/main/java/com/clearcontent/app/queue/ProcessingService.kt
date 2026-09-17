package com.clearcontent.app.queue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clearcontent.app.AppGraph
import com.clearcontent.app.R
import com.clearcontent.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process running (and out of the cached-app freezer) while the queue has work,
 * so "Share → Back" still finishes large videos. Stops itself once the queue is idle.
 */
class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        try {
            // Called directly: ServiceCompat masks out types newer than API 34 (mediaProcessing) and sends "none".
            startForeground(NOTIFICATION_ID, notification(0, 0), type)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot enter foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            val queue = AppGraph.get(this).queue
            watcher = scope.launch {
                combine(queue.pending, queue.items) { pending, items -> pending to items }.collectLatest { (pending, items) ->
                    if (pending <= 0) {
                        ServiceCompat.stopForeground(this@ProcessingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val active = items.count { it.status == ItemStatus.QUEUED || it.status == ItemStatus.WORKING }
                        val done = items.count { it.status == ItemStatus.DONE || it.status == ItemStatus.FAILED }
                        if (canNotify()) {
                            getSystemService(NotificationManager::class.java)
                                ?.notify(NOTIFICATION_ID, notification(done, done + active))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    // Android 15 limits mediaProcessing/dataSync services; stop gracefully when the budget runs out.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Progress updates are optional; the foreground service itself runs without this permission. */
    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun notification(done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Очистка файлов")
            .setContentText(if (total > 0) "Готово $done из $total" else "Подготовка…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "ProcessingService"
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 42

        /** Called from a visible screen, where starting a foreground service is allowed. */
        fun start(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                nm?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Обработка файлов", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Показывается, пока очищаются фото и видео"
                        setShowBadge(false)
                    },
                )
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java))
            } catch (e: Exception) {
                // Background-start restrictions: processing still runs while the app is visible.
                Log.w(TAG, "Foreground service not started", e)
            }
        }
    }
}
