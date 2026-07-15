package no.hanss.alarmclock.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import no.hanss.alarmclock.MainActivity
import no.hanss.alarmclock.data.Reminder
import no.hanss.alarmclock.ui.ReminderSnoozeActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// IMPORTANCE_HIGH deliberately (channel importance is a one-shot decision,
// #26): a reminder's whole job is to interrupt, so it heads-up with the
// default notification sound and vibration, like the Tasks/Calendar apps.
private const val REMINDER_CHANNEL_ID = "reminders_channel"
// One notification per reminder so several can be active at once. Base keeps
// clear of ringing (1001), upcoming (2001), bedtime (2002), timers (3000+).
private const val REMINDER_NOTIFICATION_BASE = 4000

/**
 * The notification for a fired (ACTIVE) reminder: heads-up, best-effort
 * persistent (setOngoing -- Android 14+ lets the user swipe it away anyway,
 * which is why ReminderOps keeps a daily re-remind armed while ACTIVE), with
 * a Done action (broadcast to [ReminderReceiver]) and a Snooze action that
 * opens the small [ReminderSnoozeActivity] dialog over whatever app is in
 * front.
 */
class ReminderNotificationManager(private val context: Context) {

    fun post(reminder: Reminder, alert: Boolean = true) {
        createChannel()

        val contentIntent = PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                // Same flag set as the widget tap (entry #8): without these a
                // tap stacks a second MainActivity on any already-open one.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val doneIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_REMINDER_DONE
                putExtra(EXTRA_REMINDER_ID, reminder.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            Intent(context, ReminderSnoozeActivity::class.java).apply {
                putExtra(EXTRA_REMINDER_ID, reminder.id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismissal hook (#57): swiping the notification away rearms a much
        // sooner re-post than the daily re-alert. Same component + request
        // code as Done/fire, distinct action -- filterEquals as usual.
        val swipedIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_REMINDER_SWIPED
                putExtra(EXTRA_REMINDER_ID, reminder.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(reminder.text)
            // The reminder text IS the substance; BigTextStyle lets a long
            // one expand fully. The big-form title is blanked so the expanded
            // view shows the text ONCE as the body -- without this the
            // default keeps the content title above the big text and the
            // same text appears twice (entry #53).
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(reminder.text)
                    .setBigContentTitle("")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            // Best-effort "persistent": survives accidental shade swipes on
            // pre-14, and everywhere it's non-dismissable-by-tap. Not
            // autoCancel -- only Done/Snooze should clear it.
            .setOngoing(true)
            .setDeleteIntent(swipedIntent)
            // Silent for the instant swipe re-post (#58): the notification
            // returns without a fresh ding for an accidental swipe.
            .setSilent(!alert)
            .addAction(0, "Done", doneIntent)
            .addAction(0, "Snooze", snoozeIntent)

        // No "Due today HH:MM" line: the notification arriving IS the due
        // signal (entry #51). The due stamp survives only in the small header
        // slot, and only once the reminder is overdue from an earlier day
        // (the daily re-remind), where it's genuinely informative.
        overdueLabel(reminder.dueAtMillis)?.let { builder.setSubText(it) }

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(reminder.id), builder.build())
    }

    fun cancel(reminderId: Long) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(reminderId))
    }

    private fun notificationId(reminderId: Long): Int =
        REMINDER_NOTIFICATION_BASE + reminderId.toInt()

    /**
     * "Due Tue 21 Jul, 09:00" -- but only once the due DAY has passed (the
     * re-remind case); null on the day itself, where a due stamp is noise.
     */
    private fun overdueLabel(dueAtMillis: Long): String? {
        val due = Calendar.getInstance().apply { timeInMillis = dueAtMillis }
        val now = Calendar.getInstance()
        val sameDay = due.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            due.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (sameDay || dueAtMillis > now.timeInMillis) return null
        val time = String.format(Locale.getDefault(), "%02d:%02d", due.get(Calendar.HOUR_OF_DAY), due.get(Calendar.MINUTE))
        return "Due " + SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(dueAtMillis)) + ", " + time
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                REMINDER_CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminder notifications"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
