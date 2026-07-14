package no.hanss.alarmclock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "ReminderScheduler"

const val EXTRA_REMINDER_ID = "extra_reminder_id"

/**
 * Arms/cancels reminder fire times with AlarmManager, mirroring
 * [TimerScheduler]. One PendingIntent per reminder id serves both the initial
 * fire and the daily re-remind while ACTIVE -- [ReminderReceiver] decides
 * which is which from the row's state, so re-arming is always a plain
 * overwrite of the same slot. Request codes are the reminder's DB id; no
 * collision with alarms/timers despite shared ids, because the receiver
 * component differs (Intent.filterEquals treats them as distinct).
 *
 * setExactAndAllowWhileIdle rather than setAlarmClock on purpose: this only
 * posts a notification, so it shouldn't claim the status bar alarm icon, and
 * a worst-case Doze delay of minutes is harmless. Guarded per the standing
 * rule -- a reminder must degrade to an inexact set() rather than crash or
 * silently never appear.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(reminderId: Long, triggerAtMillis: Long) {
        val pendingIntent = createPendingIntent(reminderId)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Exact schedule denied for reminder $reminderId; scheduling inexactly instead", e)
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback inexact set() also failed; reminder $reminderId is NOT armed", e2)
            }
        }
    }

    fun cancel(reminderId: Long) {
        val pendingIntent = findExistingPendingIntent(reminderId) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun intentFor(reminderId: Long): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }

    private fun createPendingIntent(reminderId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, reminderId.toInt(), intentFor(reminderId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun findExistingPendingIntent(reminderId: Long): PendingIntent? =
        PendingIntent.getBroadcast(
            context, reminderId.toInt(), intentFor(reminderId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
}
