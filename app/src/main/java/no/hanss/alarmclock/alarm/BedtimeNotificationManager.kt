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
import no.hanss.alarmclock.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.AlarmDatabase
import no.hanss.alarmclock.data.SettingsStore

private const val TAG = "BedtimeManager"
private const val BEDTIME_CHANNEL_ID = "bedtime_channel"
private const val BEDTIME_NOTIFICATION_ID = 2002
private const val BEDTIME_CHECK_REQUEST_CODE = 999002
// How late a refresh may run and still treat this as the ON-TIME post for the
// occurrence. Beyond it, the short-notice branch below takes over.
private const val GRACE_MILLIS = 30 * 60 * 1000L

// #104: when the alarm is NEARER than the configured window -- created at 23:00
// to ring at 06:00 with a 9 h setting -- a reminder is still wanted, saying how
// long there actually is rather than the setting. That used to be silent on the
// grounds that "bed now for 9 h of sleep" would be a lie; the message now states
// real remaining time, so it cannot lie and there is nothing to suppress.
// The floor exists because #103 made every alarm firing call refresh(): mid-way
// through a series the next member is minutes away, and without it each ring
// would post "bed now for 5 min of sleep".
// TWO HOURS, raised from one in #105. A series whose interval clears the floor
// posts between its own members -- once per member, each one truthful but not
// wanted -- and the floor is the only thing standing in the way. Two hours
// covers any interval anyone would plausibly set while still being far less
// sleep than the reminder is ever configured to protect.
// NOTE this does NOT gate the on-time post. A bedtimeHoursBefore of 1 (the
// settings minimum) is reached through the `now < bedtimeAt` branch and its
// grace, above; the floor only ever suppresses the short-notice branch.
private const val SHORT_NOTICE_MIN_MILLIS = 2 * 60 * 60 * 1000L

const val ACTION_CHECK_BEDTIME = "no.hanss.alarmclock.action.CHECK_BEDTIME"

/**
 * A quiet, dismissible "go to bed" notification N hours before the next
 * enabled alarm rings (#47). Mirrors [UpcomingAlarmManager]: the next ring is
 * the soonest peekNextTriggerTime over all enabled alarms -- standalone and
 * series children alike -- which makes it pause-, snooze-, and skip-aware for
 * free; an AlarmManager check wakes [BedtimeReceiver] at the bedtime moment;
 * and [refresh] is called from every place alarms change (repository, boot,
 * the check itself, and -- since #103 -- [AlarmReceiver]), so the reminder fires
 * once per occurrence and re-arms for the next.
 *
 * THE RE-ARM IS NOT SELF-SUSTAINING ON ITS OWN, which is what #103 fixed: only
 * the "not bedtime yet" branch of [refresh] schedules a wake-up. Posting the
 * notification, or finding the window already missed, arms nothing. Something
 * external therefore has to call [refresh] again, and for a repeating alarm the
 * only event that reliably comes round every day is the alarm firing. Do not
 * remove the [AlarmReceiver] call believing this class re-arms itself.
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
            now < bedtimeAt + GRACE_MILLIS -> postNotification(triggerAt)
            // #104: past the window, but the alarm is still far enough out to be
            // worth saying so. Posts the real remaining time, not `hours`.
            // Re-posts for the same occurrence if something else calls refresh()
            // while the alarm sits in this band -- known and accepted (#105).
            triggerAt - now >= SHORT_NOTICE_MIN_MILLIS -> postNotification(triggerAt)
            else -> {
                // The alarm is imminent -- inside a series, or minutes away. A
                // bedtime message here is noise; stay silent for this occurrence.
                // The next refresh (after the alarm fires or anything changes)
                // arms the following one.
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

    /**
     * "9 h", "6 h 40 min", "45 min". Minutes round UP, so the figure never
     * understates the time left (the same reasoning as #39's countdown ceiling).
     */
    private fun remainingLabel(millis: Long): String {
        val totalMinutes = ((millis + 59_999L) / 60_000L).coerceAtLeast(0L)
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    private fun postNotification(triggerAtMillis: Long) {
        createChannel()

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        val timeLabel = String.format(
            "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )

        // #104: the sleep length quoted is what is ACTUALLY left, computed here
        // rather than taken from the setting. On the on-time post the two are the
        // same to within the grace; on a short-notice post they are not, and the
        // setting would overstate. Minutes round UP, per #39's rule about never
        // understating a remaining time.
        val remaining = remainingLabel(triggerAtMillis - System.currentTimeMillis())

        // A custom message (settings) replaces the default text. #48's principle
        // still holds -- the facts stay visible whatever the wording -- but #106
        // moved WHERE they live, because the slot #48 chose does not hold them.
        val custom = settings.bedtimeMessage.trim()
        val builder = NotificationCompat.Builder(context, BEDTIME_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bed)
            .setGroup("no.hanss.alarmclock.BEDTIME")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (custom.isNotEmpty()) {
            // #106: the facts are the TITLE and the message the body. They used
            // to be setSubText, which Android renders in the COLLAPSED HEADER ROW
            // beside the app name and the timestamp -- the first thing elided on
            // a narrow phone, so with a message set the one number this feature
            // exists for was invisible on device. Nothing load-bearing goes in
            // that row any more; "Bedtime" may, being short and merely a label.
            //
            // BigTextStyle lets a long message expand rather than truncate at one
            // line. No setBigContentTitle("") blanking here, unlike
            // ReminderNotificationManager (#53): there the title and the big text
            // were the SAME string and expanding showed it twice. Here the big
            // form's title defaults to the content title (the facts) and the body
            // is the message, so each string already appears exactly once --
            // blanking would throw the facts away when expanded.
            builder
                .setContentTitle("Alarm at $timeLabel · $remaining of sleep")
                .setContentText(custom)
                .setSubText("Bedtime")
                .setStyle(NotificationCompat.BigTextStyle().bigText(custom))
        } else {
            // Unchanged from V2.4: with no message the single body line already
            // carries everything, and the title has nothing to compete with.
            builder
                .setContentTitle("Bedtime")
                .setContentText("Alarm at $timeLabel — bed now for $remaining of sleep")
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
            } catch (e: Exception) {
                // No exception handler on a bare CoroutineScope: an escaping throw
                // would kill the process (entry #71a). Log and finish cleanly.
                Log.e(TAG, "Failed refreshing the bedtime reminder", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
