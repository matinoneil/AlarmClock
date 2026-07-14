package no.hanss.alarmclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.AlarmDatabase
import no.hanss.alarmclock.widget.AlarmWidgetUpdater

private const val TAG = "BootReceiver"

/**
 * AlarmManager entries do not survive a reboot, so we re-schedule every enabled alarm
 * when the device boots or the app is updated.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AlarmDatabase.getInstance(context)
                val dao = db.alarmDao()
                val scheduler = AlarmScheduler(context)
                dao.getAllEnabledAlarms().forEach { scheduler.schedule(it) }
                UpcomingAlarmManager(context).refresh()
                BedtimeNotificationManager(context).refresh()
                AlarmWidgetUpdater.updateAll(context)

                // Running timers: AlarmManager entries died with the reboot too.
                // Re-arm any countdown still in the future; one that expired while
                // the device was off is quietly reset to idle instead of ringing
                // late -- blasting a kitchen timer long after its moment is noise,
                // not a wake-up (same reasoning as the alarm resume grace window).
                // Paused series: the resume AlarmManager entry died with the
                // reboot. Re-arm future resumes; run overdue ones right now --
                // a pause that fails to end is a missed wake-up, the single
                // worst failure this app can have.
                val now0 = System.currentTimeMillis()
                db.alarmSeriesDao().getAllPausedSeries().forEach { series ->
                    val until = series.pausedUntilMillis ?: return@forEach
                    if (until <= now0) {
                        SeriesUnpauseOps.unpause(context, series.id)
                    } else {
                        SeriesUnpauseScheduler(context).schedule(series.id, until)
                    }
                }

                val timerDao = db.timerDao()
                val timerScheduler = TimerScheduler(context)
                val timerNotifications = TimerNotificationManager(context)
                val now = System.currentTimeMillis()
                timerDao.getAllRunningTimers().forEach { timer ->
                    if ((timer.runningUntilMillis ?: 0L) > now) {
                        timerScheduler.schedule(timer)
                        // Notifications don't survive a reboot; bring the
                        // countdown notification back with the re-armed timer.
                        timerNotifications.post(timer)
                    } else {
                        Log.w(TAG, "Timer ${timer.id} expired while the device was off; resetting to idle")
                        timerDao.update(timer.copy(runningUntilMillis = null))
                    }
                }

                // Reminders: re-arm pending ones, fire overdue ones LATE (a
                // reminder that came due while the phone was off is still
                // wanted -- deliberate opposite of the expired-timer reset
                // above), and re-post the notification for active ones with a
                // fresh daily re-remind armed. All one path in ReminderOps.
                db.reminderDao().getAllUndoneReminders().forEach { reminder ->
                    ReminderOps.refresh(context, reminder.id)
                }

                // If an alarm was ringing when the device shut down (or the app was
                // updated mid-ring), resume it -- especially critical for one-shots,
                // which were already disabled in the DB when they fired and would
                // otherwise never ring again. Clear the marker before restarting so
                // a crash-loop in the service can't re-trigger from here forever;
                // the service re-sets it with a fresh timestamp when it rings.
                val prefs = context.getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE)
                val interruptedId = prefs.getLong(KEY_RINGING_ID, -1L)
                val age = System.currentTimeMillis() - prefs.getLong(KEY_RINGING_SINCE, 0L)
                if (interruptedId != -1L) {
                    val wasTimer = prefs.getBoolean(KEY_RINGING_IS_TIMER, false)
                    prefs.edit()
                        .remove(KEY_RINGING_ID)
                        .remove(KEY_RINGING_SINCE)
                        .remove(KEY_RINGING_IS_TIMER)
                        .apply()
                    if (age in 0..RING_RESUME_GRACE_MILLIS) {
                        Log.w(TAG, "Resuming ${if (wasTimer) "timer" else "alarm"} $interruptedId that was interrupted mid-ring")
                        val serviceIntent = Intent(context, AlarmRingtoneService::class.java).apply {
                            if (wasTimer) putExtra(EXTRA_TIMER_ID, interruptedId)
                            else putExtra(EXTRA_ALARM_ID, interruptedId)
                        }
                        context.startForegroundService(serviceIntent)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
