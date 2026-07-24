package no.hanss.alarmclock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import no.hanss.alarmclock.MainActivity
import no.hanss.alarmclock.data.TimerPreset

private const val TAG = "TimerScheduler"

const val EXTRA_TIMER_ID = "extra_timer_id"

/** Request-code namespace for the showIntent; see AlarmScheduler and entry #70. */
private const val SHOW_INTENT_REQUEST_BASE = 810000

private const val ACTION_SHOW_TIMER = "no.hanss.alarmclock.SHOW_TIMER"

/**
 * Arms/cancels running countdown timers with AlarmManager, mirroring
 * [AlarmScheduler] but at exact epoch-millis precision (timers care about
 * seconds; the alarm path only stores hour/minute, which is why timers don't
 * just reuse it). Request codes are the timer's DB id -- no collision with
 * alarms even for equal ids, because the PendingIntents target a different
 * receiver component ([TimerReceiver]), which Intent.filterEquals treats as
 * distinct.
 */
class TimerScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(timer: TimerPreset) {
        val triggerAtMillis = timer.runningUntilMillis ?: run {
            cancel(timer)
            return
        }
        val pendingIntent = createPendingIntent(timer.id)
        // Same guarded pattern as AlarmScheduler: setAlarmClock is exact even
        // under Doze but throws SecurityException if the user revokes "Alarms &
        // reminders" on Android 12/13 -- a timer must degrade to an inexact
        // set() rather than crash or silently not ring.
        try {
            // showIntent must open the app, never be the fire PendingIntent: the
            // Quick Settings chip launches it, and passing the fire intent made a tap
            // end the countdown early with a full ring (entry #70).
            val info = AlarmManager.AlarmClockInfo(triggerAtMillis, createShowIntent(timer.id))
            alarmManager.setAlarmClock(info, pendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "setAlarmClock failed for timer ${timer.id} (exact-alarm permission revoked?); falling back to inexact set()", e)
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback inexact set() also failed; timer ${timer.id} is NOT armed", e2)
            }
        }
    }

    /** Opens the app when the user taps the Quick Settings chip; see AlarmScheduler. */
    private fun createShowIntent(timerId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_SHOW_TIMER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, SHOW_INTENT_REQUEST_BASE + timerId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancel(timer: TimerPreset) = cancel(timer.id)

    fun cancel(timerId: Long) {
        val pendingIntent = findExistingPendingIntent(timerId) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun intentFor(timerId: Long): Intent =
        Intent(context, TimerReceiver::class.java).apply {
            putExtra(EXTRA_TIMER_ID, timerId)
        }

    private fun createPendingIntent(timerId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, timerId.toInt(), intentFor(timerId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun findExistingPendingIntent(timerId: Long): PendingIntent? =
        PendingIntent.getBroadcast(
            context, timerId.toInt(), intentFor(timerId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
}
