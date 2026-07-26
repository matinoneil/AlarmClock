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

private const val RECEIVER_TAG = "UpcomingAlarmReceiver"

class UpcomingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = UpcomingAlarmManager(context)
                when (intent.action) {
                    ACTION_DISMISS_NEXT_ALARM -> {
                        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                        if (alarmId != -1L) {
                            val dao = AlarmDatabase.getInstance(context).alarmDao()
                            val alarm = dao.getAlarm(alarmId)
                            if (alarm != null) {
                                val scheduler = AlarmScheduler(context)
                                if (alarm.daysOfWeek.isEmpty()) {
                                    // One-shot: there's no future occurrence to skip
                                    // forward to, so "dismiss" means disable it outright.
                                    // Cancelling only the AlarmManager entry isn't enough --
                                    // the alarm's row would still say enabled=true with its
                                    // original time untouched, so the upcoming-alarm refresh
                                    // right below would just find that same time again and
                                    // repost immediately.
                                    val updated = alarm.copy(enabled = false)
                                    dao.update(updated)
                                    scheduler.cancel(updated)
                                } else {
                                    val updated = if (alarm.snoozeUntilMillis != null) {
                                        // The alarm is currently snoozed, so its "next
                                        // occurrence" IS the pending snooze -- dismissing
                                        // it means dropping the snooze and returning to
                                        // the regular weekly schedule. (A skip marker
                                        // wouldn't work here: the snooze override is
                                        // consulted before skip during scheduling.)
                                        alarm.copy(snoozeUntilMillis = null)
                                    } else {
                                        // Persist exactly which occurrence to skip, so the
                                        // scheduler (and any later recomputation, like the
                                        // upcoming-alarm refresh right below) actually knows
                                        // to look past it rather than finding the same one
                                        // again and effectively doing nothing.
                                        val upcoming = scheduler.peekNextTriggerTime(alarm)
                                        alarm.copy(skipOccurrenceMillis = upcoming)
                                    }
                                    dao.update(updated)
                                    scheduler.schedule(updated)
                                }
                            }
                        }
                        manager.cancelNotification()
                        manager.refresh()
                    }
                    ACTION_CHECK_UPCOMING -> {
                        manager.refresh()
                    }
                    // #94: swiped away while protection is on. refresh() is the
                    // whole handler -- it re-posts if the alarm is still
                    // upcoming and cancels if it has already rung, so the
                    // swipe simply doesn't take.
                    ACTION_UPCOMING_SWIPED -> {
                        Log.i(RECEIVER_TAG, "Upcoming-alarm notification swiped away; restoring")
                        manager.refresh()
                    }
                }
                AlarmWidgetUpdater.updateAll(context)
            } catch (e: Exception) {
                // No exception handler on a bare CoroutineScope: an escaping throw
                // would kill the process (entry #71a). Log and finish cleanly.
                Log.e(RECEIVER_TAG, "Failed handling upcoming-alarm action ${intent.action}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
