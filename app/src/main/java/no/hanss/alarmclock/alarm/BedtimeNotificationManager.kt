package no.hanss.alarmclock.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.AlarmDatabase
import no.hanss.alarmclock.data.SettingsStore

private const val TAG = "BedtimeManager"
private const val BEDTIME_CHANNEL_ID = "bedtime_channel"
private const val BEDTIME_NOTIFICATION_ID = 2002
private const val BEDTIME_CHECK_REQUEST_CODE = 999002
// If the bedtime moment is already further in the past than this when a
// refresh runs (say an alarm was created only 3 h out with an 8 h window),
// no notification: "bed now for 8 h of sleep" would be a lie.
private const val GRACE_MILLIS = 30 * 60 * 1000L

const val ACTION_CHECK_BEDTIME = "no.hanss.alarmclock.action.CHECK_BEDTIME"

/**
 * A quiet, dismissible "go to bed" notification N hours before the next
 * enabled alarm rings (#47). Mirrors [UpcomingAlarmManager]: the next ring is
 * the soonest peekNextTriggerTime over all enabled alarms -- standalone and
 * series children alike -- which makes it pause-, snooze-, and skip-aware for
 * free; an AlarmManager check wakes [BedtimeReceiver] at the bedtime moment;
 * and [refresh] is called from every place alarms change (repository, boot,
 * the check itself), so the reminder fires once per occurrence and re-arms
 * for the next.
 */
class BedtimeNotificationManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val scheduler = AlarmScheduler(context)
    private val settings = SettingsStore(context)

    suspend fun refresh() {
        if (!settings.bedtimeEnabled) {
            cancelCheckAlarm()
            cancelNotification()
            return
        }

        val dao = AlarmDatabase.getInstance(context).alarmDao()
        val soonest = dao.getAllEnabledAlarms()
            .map { it to scheduler.peekNextTriggerTime(it) }
            .minByOrNull { it.second }

        cancelCheckAlarm()

        if (soonest == null) {
            cancelNotification()
            return
        }

        val (alarm, triggerAt) = soonest
        val hours = settings.bedtimeHoursBefore
        val bedtimeAt = triggerAt - hours * 60L * 60L * 1000L
        val now = System.currentTimeMillis()

        when {
            now < bedtimeAt -> {
                // Not bedtime yet: clear anything stale and wake up at the moment.
                cancelNotification()
                scheduleCheckAt(bedtimeAt)
            }
            now < bedtimeAt + GRACE_MILLIS -> postNotification(triggerAt, hours)
            else -> {
                // Missed the window by too much for the message to be true;
                // stay silent for this occurrence. The next refresh (after the
                // alarm fires or anything changes) arms the following one.
                cancelNotification()
            }
        }
    }

    private fun scheduleCheckAt(millis: Long) {
        val pendingIntent = checkPendingIntent(create = true) ?: return
        // Same guarded pattern as the upcoming-alarm check: this only decides
        // when a notification appears, so an inexact fallback is fine.
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Exact bedtime check denied; scheduling inexactly instead", e)
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Could not schedule bedtime check at all", e2)
            }
        }
    }

    private fun cancelCheckAlarm() {
        val pendingIntent = checkPendingIntent(create = false) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun checkPendingIntent(create: Boolean): PendingIntent? {
        val intent = Intent(context, BedtimeReceiver::class.java).apply {
            action = ACTION_CHECK_BEDTIME
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or
            if (!create) PendingIntent.FLAG_NO_CREATE else 0
        return PendingIntent.getBroadcast(context, BEDTIME_CHECK_REQUEST_CODE, intent, flags)
    }

    private fun postNotification(triggerAtMillis: Long, hours: Int) {
        createChannel()

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        val timeLabel = String.format(
            "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )

        // A custom message (settings) replaces the default text; the factual
        // alarm time moves to the header so it stays visible either way (#48).
        val custom = settings.bedtimeMessage.trim()
        val builder = NotificationCompat.Builder(context, BEDTIME_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Bedtime")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (custom.isNotEmpty()) {
            builder.setContentText(custom).setSubText("Alarm at $timeLabel")
        } else {
            builder.setContentText("Alarm at $timeLabel — bed now for $hours h of sleep")
        }
        val notification = builder.build()

        context.getSystemService(NotificationManager::class.java)
            .notify(BEDTIME_NOTIFICATION_ID, notification)
    }

    fun cancelNotification() {
        context.getSystemService(NotificationManager::class.java)
            .cancel(BEDTIME_NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Deliberately audible (#48): default notification sound and
            // vibration, unlike the app's other status channels. Editing the
            // channel in place (rather than a _v2 id per #26) was safe ONLY
            // because #47 never shipped -- the channel existed on no device.
            val channel = NotificationChannel(
                BEDTIME_CHANNEL_ID, "Bedtime reminder", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminds you to go to bed before your next alarm"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}

class BedtimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK_BEDTIME) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BedtimeNotificationManager(context).refresh()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
