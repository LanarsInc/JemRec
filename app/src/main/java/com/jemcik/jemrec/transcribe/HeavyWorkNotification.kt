package com.jemcik.jemrec.transcribe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/**
 * Keeps a transcription or a summary alive by making it visible.
 *
 * Both run for minutes and hold a model of hundreds of megabytes to
 * gigabytes. As plain background work they run at cached-process priority,
 * the first thing Android reclaims when memory runs short - so the job would
 * be killed because it uses memory, halfway, having produced nothing
 * (CallVault's finding, on its phones). A foreground service is how Android
 * is told this work is worth keeping; the user is told too, which is right:
 * the phone is about to get warm.
 *
 * LOW, not CallVault's MIN: here the notification carries the progress and
 * the Cancel button, and MIN folds both away. Still no sound, no vibration,
 * no badge. Its own id, so it never takes the place of a recording's.
 */
object HeavyWorkNotification {

    const val CHANNEL_ID = "transcripts"
    private const val NOTIFICATION_ID = 4201

    /**
     * For `setForeground`: [title] says what is happening ("Transcribing a
     * call with Priya"), [percent] how far (null while it is still loading),
     * [cancel] stops it.
     */
    fun forWork(context: Context, title: String, percent: Int?, cancel: PendingIntent?): ForegroundInfo {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(if (percent == null) "Getting ready…" else "$percent%")
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply { if (cancel != null) addAction(0, "Cancel", cancel) }
            .build()
        // The type must match what the manifest declares for WorkManager's
        // service, or Android 14 and later refuse to start it. Before 14 there
        // is no special-use type and none is needed, as CallMonitorService does.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** Idempotent: creating it again changes nothing the user has set. */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Transcripts and summaries", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
