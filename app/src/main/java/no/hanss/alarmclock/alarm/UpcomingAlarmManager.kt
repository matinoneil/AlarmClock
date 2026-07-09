package no.hanss.alarmclock.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.data.AlarmDatabase

private const val TAG = "UpcomingAlarmManager"
private const val UPCOMING_CHANNEL_ID = "upcoming_alarm_channel"
private const val UPCOMING_NOTIFICATION_ID = 2001
private const val UPCOMING_CHECK_REQUEST_CODE = 999001
private const val WINDOW_MILLIS = 60 * 60 * 1000L // show once the alarm is within an hour out

const val ACTION_CHECK_UPCOMING = "no.hanss.alarmclock.action.CHECK_UPCOMING"
const val ACTION_DISMISS_NEXT_ALARM = "no.hanss.alarmclock.action.DISMISS_NEXT_ALARM"

/**
 * Keeps a single silent, ongoing notification in sync with whichever enabled alarm is
 * soonest to fire, showing it once that alarm is within an hour out, with an action to
 * skip just that occurrence (without disabling the alarm itself). Call [refresh] any
 * time an alarm is added, edited, deleted, toggled, or fires, so both the notification
 * and the AlarmManager check that will show it later stay accurate.
 */
class UpcomingAlarmManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val scheduler = AlarmScheduler(context)

    suspend fun refresh() {
        val dao = AlarmDatabase.getInstance(context).alarmDao()
        val enabled = dao.getAllEnabledAlarms()

        val soonest = enabled
            .map { it to scheduler.peekNextTriggerTime(it) }
            .minByOrNull { it.second }

        cancelCheckAlarm()

        if (soonest == null) {
            cancelNotification()
            return
        }

        val (alarm, triggerAt) = soonest
        val now = System.currentTimeMillis()
        val showAt = triggerAt - WINDOW_MILLIS

        if (showAt <= now) {
            postNotification(alarm)
        } else {
            cancelNotification()
            scheduleCheckAt(showAt)
        }
    }

    private fun scheduleCheckAt(millis: Long) {
        val pendingIntent = checkPendingIntent(create = true) ?: return
        // Same exact-alarm permission caveat as AlarmScheduler: revocable on
        // Android 12/13. This one only decides when the *notification* appears,
        // so an inexact fallback (possibly a few minutes late) is perfectly fine.
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Exact upcoming-check denied; scheduling inexactly instead", e)
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Could not schedule upcoming-alarm check at all", e2)
            }
        }
    }

    private fun cancelCheckAlarm() {
        val pendingIntent = checkPendingIntent(create = false) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun checkPendingIntent(create: Boolean): PendingIntent? {
        val intent = Intent(context, UpcomingAlarmReceiver::class.java).apply {
            action = ACTION_CHECK_UPCOMING
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or
            if (!create) PendingIntent.FLAG_NO_CREATE else 0
        return PendingIntent.getBroadcast(context, UPCOMING_CHECK_REQUEST_CODE, intent, flags)
    }

    fun postNotification(alarm: Alarm) {
        createChannel()

        val dismissIntent = Intent(context, UpcomingAlarmReceiver::class.java).apply {
            action = ACTION_DISMISS_NEXT_ALARM
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, alarm.id.toInt(), dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeLabel = String.format("%02d:%02d", alarm.hour, alarm.minute)
        val title = if (alarm.label.isNotBlank()) "${alarm.label} at $timeLabel" else "Alarm at $timeLabel"

        val notification = NotificationCompat.Builder(context, UPCOMING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Upcoming alarm")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Dismiss next alarm", dismissPendingIntent)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(UPCOMING_NOTIFICATION_ID, notification)
    }

    fun cancelNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(UPCOMING_NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPCOMING_CHANNEL_ID, "Upcoming alarm", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when your next alarm is within an hour"
                setSound(null, null)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
