package no.hanss.alarmclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.AlarmDatabase
import no.hanss.alarmclock.widget.AlarmWidgetUpdater

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AlarmDatabase.getInstance(context).alarmDao()
                val alarm = dao.getAlarm(alarmId)
                if (alarm != null && alarm.enabled) {
                    // Clear any stale "skip this occurrence" marker and any consumed
                    // snooze now that we've reached a legitimate firing -- both have
                    // served their purpose, and a leftover snoozeUntilMillis would
                    // otherwise override the next scheduling computation.
                    val current = if (alarm.skipOccurrenceMillis != null || alarm.snoozeUntilMillis != null) {
                        val cleared = alarm.copy(skipOccurrenceMillis = null, snoozeUntilMillis = null)
                        dao.update(cleared)
                        cleared
                    } else {
                        alarm
                    }

                    // Launch the ringing UI / sound service.
                    val serviceIntent = Intent(context, AlarmRingtoneService::class.java).apply {
                        putExtra(EXTRA_ALARM_ID, current.id)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }

                    // If it repeats on certain weekdays, schedule the next occurrence.
                    // A one-shot alarm (empty daysOfWeek) has no next occurrence, so
                    // mark it disabled rather than leaving its toggle showing "on" for
                    // an alarm that will never actually ring again.
                    if (current.daysOfWeek.isNotEmpty()) {
                        AlarmScheduler(context).schedule(current)
                    } else {
                        dao.update(current.copy(enabled = false))
                    }

                    // This alarm is no longer "upcoming" -- it's ringing now. Recompute
                    // in case a different alarm (or this one's next occurrence) is next.
                    UpcomingAlarmManager(context).apply {
                        cancelNotification()
                        refresh()
                    }
                    AlarmWidgetUpdater.updateAll(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
