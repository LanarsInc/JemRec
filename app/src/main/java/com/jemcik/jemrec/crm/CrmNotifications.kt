package com.jemcik.jemrec.crm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.crm.WorkCallMachine.Local
import com.jemcik.jemrec.crm.api.ApiModels
import com.jemcik.jemrec.crm.api.CallStatus
import com.jemcik.jemrec.crm.api.PendingReason
import com.jemcik.jemrec.crm.db.WorkCallEntry
import org.json.JSONObject

/**
 * The Sales Engine's notifications (contracts/ui.md): one per work call,
 * replaced as the call moves - with Change or Choose for the two minutes it is
 * held before it is sent (FR-020), and Choose or "Which lead?" while it waits
 * for a pick - and the prompts that need the seller.
 *
 * Its own channel, so the after-call recording notification (AfterCall) and
 * this one can each be switched off without the other.
 */
object CrmNotifications {

    private const val CHANNEL_ID = "sales_engine"
    private const val PROMPT_ID = 4_399
    private const val CALL_ID_BASE = 4_400

    fun events(context: Context): CrmSync.Events {
        val app = context.applicationContext
        return object : CrmSync.Events {
            override fun changed(call: WorkCallEntry) = show(app, call)
            override fun needsSeller(state: SessionStore.State, noticeOutdated: Boolean) = prompt(app, state, noticeOutdated)
        }
    }

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sales Engine", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "After a work call: its deal, and what reached LANARS's CRM"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    /** The work call's notification as its state is now; gone when there is nothing to say. */
    fun show(context: Context, c: WorkCallEntry) {
        if (!allowed(context)) return
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = CALL_ID_BASE + (c.id % 10_000).toInt()
        val lookup = c.lookupJson?.let { ApiModels.lookup(JSONObject(it)) }
        val server = c.serverJson?.let { ApiModels.call(JSONObject(it)) }
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open(context, DeepLink.Kind.CRM_CALL, c.id.toString(), c.id.toInt()))
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
        when (Local.of(c.localState)) {
            Local.NEW, Local.WAITING -> {
                val minutes = ((c.dueAtMs - System.currentTimeMillis()).coerceAtLeast(0) + 59_999) / 60_000
                val inMinutes = if (minutes <= 1) "in a minute" else "in $minutes min"
                when (val s = CallDecision.shown(lookup)) {
                    is CallDecision.Shown.OneDeal -> builder
                        .setContentTitle(s.title)
                        .setContentText(listOfNotNull("Logging $inMinutes", s.orgName).joinToString(" · "))
                        .addAction(action(context, "Change", open(context, DeepLink.Kind.CRM_CALL, c.id.toString(), c.id.toInt() + 1)))
                    is CallDecision.Shown.Choose -> builder
                        .setContentTitle("Choose a deal")
                        .setContentText("${s.count} deals for this number")
                        .addAction(action(context, "Choose", open(context, DeepLink.Kind.CRM_CALL, c.id.toString(), c.id.toInt() + 1)))
                    else -> builder
                        .setContentTitle("Call to the Sales Engine")
                        .setContentText(if (c.phone == null) "Hidden number · sending $inMinutes" else "Sending $inMinutes")
                }
                builder.setTimeoutAfter((c.dueAtMs - System.currentTimeMillis()).coerceAtLeast(0) + 5 * 60_000)
            }
            Local.SENT -> when (server?.status) {
                CallStatus.LOGGED -> builder.setContentTitle("Logged on ${server.dealTitle}").setTimeoutAfter(10_000)
                CallStatus.PENDING -> builder
                    .setContentTitle(if (server.pendingReason == PendingReason.NO_MATCH) "Which lead?" else "Choose a deal")
                    .setContentText(if (server.pendingReason == PendingReason.NO_MATCH) "Pick the deal for this call, or search for it" else "Not logged yet")
                    .addAction(action(context, "Choose", open(context, DeepLink.Kind.CRM_CALL, c.id.toString(), c.id.toInt() + 1)))
                else -> return manager.cancel(id)
            }
            Local.INTERNAL, Local.NOT_SENT -> return manager.cancel(id)
        }
        manager.notify(id, builder.build())
    }

    /** "Sign in again", "Read the new notice", "Update": each opens Settings > Sales Engine. */
    fun prompt(context: Context, state: SessionStore.State, noticeOutdated: Boolean) {
        if (!allowed(context)) return
        ensureChannel(context)
        val (title, text) = when {
            noticeOutdated -> "Read the Sales Engine's new notice" to "Calls wait until you agree"
            state == SessionStore.State.UPGRADE_REQUIRED -> "Update JemRec to keep logging calls" to "Calls wait until it is updated"
            else -> "Sign in to the Sales Engine again" to "Calls wait until you do"
        }
        context.getSystemService(NotificationManager::class.java).notify(
            PROMPT_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open(context, DeepLink.Kind.SALES_ENGINE, null, 0))
                .build(),
        )
    }

    fun cancelPrompt(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(PROMPT_ID)

    /** Notifications are allowed; on Android 12 they need no permission, and the manager says whether they are on. */
    private fun allowed(context: Context) = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun action(context: Context, label: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(null, label, intent).build()

    private fun open(context: Context, kind: DeepLink.Kind, name: String?, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_BASE + request * 2,
            DeepLink.intent(context, kind, name).setIdentifier("crm-$kind-$name-$request"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private const val REQUEST_BASE = 7700
}
