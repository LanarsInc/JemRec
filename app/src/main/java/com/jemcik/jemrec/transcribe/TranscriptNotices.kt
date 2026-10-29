package com.jemcik.jemrec.transcribe

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.jemcik.jemrec.DeepLink

/**
 * What a transcription nobody was watching says when it ends: one started
 * by a setting (after a call, overnight) rather than a tap on the screen.
 * A tap on the notice opens the call's transcript. One notice per call,
 * replaced by the next word about the same call. Quiet, on the transcripts'
 * channel (HeavyWorkNotification) - a finished transcript is not news to
 * ring for.
 */
object TranscriptNotices {

    fun ready(context: Context, recording: String, contact: String?, lines: Int) = post(
        context, recording,
        title = "Transcript ready",
        text = if (lines == 0) "No speech was found in ${contact ?: "the call"}." else "${contact ?: "Your call"}: $lines lines. Tap to read it.",
    )

    fun failed(context: Context, recording: String, contact: String?, reason: String) = post(
        context, recording,
        title = "Transcript not made",
        text = "${contact ?: "A call"}: $reason",
    )

    private fun post(context: Context, recording: String, title: String, text: String) {
        HeavyWorkNotification.ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            recording.hashCode(),
            DeepLink.intent(context, DeepLink.Kind.TRANSCRIPT, recording).setIdentifier(recording),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, HeavyWorkNotification.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(TAG_PREFIX + recording, NOTICE_ID, notification) }
            .onFailure { Log.w("JR:Transcribe", "could not post the transcript notice", it) }
    }

    private const val TAG_PREFIX = "transcript:"
    private const val NOTICE_ID = 4202
}
