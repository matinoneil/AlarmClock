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

                // ORDER MATTERS (entry #71f). All of this runs inside one goAsync
                // against a broadcast time budget of roughly ten seconds, and
                // BOOT_COMPLETED arrives while the device is at its busiest. If the
                // process is killed partway through, whatever ran first is what
                // survived -- so the list below is strictly most-critical-first, and
                // the cosmetic refreshes (notification, widget) come last.

                // 1. An alarm that was ringing when the device went down. Someone may
                // be asleep right now; this cannot wait behind a reminder sweep.
                // Clear the marker BEFORE restarting so a crash-looping service can't
                // re-trigger from boot forever; the service re-stamps it when it rings.
                val prefs = context.getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE)
                val interruptedId = prefs.getLong(KEY_RINGING_ID, -1L)
                val age = System.currentTimeMillis() - prefs.getLong(KEY_RINGING_SINCE, 0L)
                if (interruptedId != -1L) {
                    val wasTimer = prefs.getBoolean(KEY_RINGING_IS_TIMER, false)
                    prefs.edit()
                        .remove(KEY_RINGING_ID)
                        .remove(KEY_RINGING_SINCE)
                        .remove(KEY_RINGING_IS_TIMER)
                        // commit(), not apply(): entry #13's rule. A lost CLEAR is the
                        // dangerous direction -- it would re-ring a dismissed alarm at
                        // the next boot, and the service start below may kill the
                        // process before an async apply() ever reaches disk.
                        .commit()
                    if (age in 0..RING_RESUME_GRACE_MILLIS) {
                        Log.w(TAG, "Resuming ${if (wasTimer) "timer" else "alarm"} $interruptedId that was interrupted mid-ring")
                        val serviceIntent = Intent(context, AlarmRingtoneService::class.java).apply {
                            if (wasTimer) putExtra(EXTRA_TIMER_ID, interruptedId)
                            else putExtra(EXTRA_ALARM_ID, interruptedId)
                        }
                        context.startForegroundService(serviceIntent)
                    }
                }

                // 2. Re-arm every enabled alarm: AlarmManager entries do not survive a
                // reboot, and an unarmed alarm is the worst failure this app has.
                val scheduler = AlarmScheduler(context)
                dao.getAllEnabledAlarms().forEach { scheduler.schedule(it) }

                // 3. Paused series whose resume entry died with the reboot. Overdue
                // pauses end right now -- a pause that fails to end is a missed
                // wake-up; future ones get re-armed.
                val now0 = System.currentTimeMillis()
                db.alarmSeriesDao().getAllPausedSeries().forEach { series ->
                    val until = series.pausedUntilMillis ?: return@forEach
                    if (until <= now0) {
                        SeriesUnpauseOps.unpause(context, series.id)
                    } else {
                        SeriesUnpauseScheduler(context).schedule(series.id, until)
                    }
                }

                // 4. Running timers. Re-arm anything still in the future; a countdown
                // that expired while the device was off is quietly reset to idle rather
                // than ringing late -- a kitchen timer hours after its moment is noise,
                // not a wake-up (same reasoning as the ring grace window above).
                val timerDao = db.timerDao()
                val timerScheduler = TimerScheduler(context)
                val timerNotifications = TimerNotificationManager(context)
                val now = System.currentTimeMillis()
                timerDao.getAllRunningTimers().forEach { timer ->
                    if ((timer.runningUntilMillis ?: 0L) > now) {
                        timerScheduler.schedule(timer)
                        // Notifications don't survive a reboot; bring the countdown
                        // notification back with the re-armed timer.
                        timerNotifications.post(timer)
                    } else {
                        Log.w(TAG, "Timer ${timer.id} expired while the device was off; resetting to idle")
                        timerDao.update(timer.copy(runningUntilMillis = null))
                    }
                }

                // 5. Reminders: re-arm pending ones, fire overdue ones LATE (a reminder
                // that came due while the phone was off is still wanted -- the
                // deliberate opposite of the expired-timer reset above), and re-post
                // active notifications with a fresh re-alert armed. All one path.
                // Potentially the longest loop here, hence its position.
                db.reminderDao().getAllUndoneReminders().forEach { reminder ->
                    ReminderOps.refresh(context, reminder.id)
                }

                // 6. Purely presentational, and all recomputed from the DB whenever the
                // app is opened anyway -- safe to be the first casualty of a timeout.
                UpcomingAlarmManager(context).refresh()
                BedtimeNotificationManager(context).refresh()
                AlarmWidgetUpdater.updateAll(context)
            } catch (e: Exception) {
                // No exception handler on a bare CoroutineScope: an escaping throw
                // would kill the process (entry #71a). Log and finish cleanly.
                Log.e(TAG, "Failed during boot/update reschedule", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
