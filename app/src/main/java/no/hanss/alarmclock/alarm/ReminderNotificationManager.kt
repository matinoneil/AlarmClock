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

    fun post(reminder: Reminder) {
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

        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(reminder.text)
            .setContentText(dueLabel(reminder.dueAtMillis))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            // Best-effort "persistent": survives accidental shade swipes on
            // pre-14, and everywhere it's non-dismissable-by-tap. Not
            // autoCancel -- only Done/Snooze should clear it.
            .setOngoing(true)
            .addAction(0, "Done", doneIntent)
            .addAction(0, "Snooze", snoozeIntent)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(reminder.id), notification)
    }

    fun cancel(reminderId: Long) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(reminderId))
    }

    private fun notificationId(reminderId: Long): Int =
        REMINDER_NOTIFICATION_BASE + reminderId.toInt()

    /** "Due today 09:00" / "Due Tue 21 Jul, 09:00" -- date included once it isn't today. */
    private fun dueLabel(dueAtMillis: Long): String {
        val due = Calendar.getInstance().apply { timeInMillis = dueAtMillis }
        val now = Calendar.getInstance()
        val sameDay = due.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            due.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val time = String.format(Locale.getDefault(), "%02d:%02d", due.get(Calendar.HOUR_OF_DAY), due.get(Calendar.MINUTE))
        return if (sameDay) "Due today $time"
        else "Due " + SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(dueAtMillis)) + ", " + time
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
