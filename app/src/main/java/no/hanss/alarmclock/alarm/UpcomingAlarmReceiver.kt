package no.hanss.alarmclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.AlarmDatabase

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
                            val alarm = AlarmDatabase.getInstance(context).alarmDao().getAlarm(alarmId)
                            if (alarm != null) {
                                AlarmScheduler(context).skipNextAndReschedule(alarm)
                            }
                        }
                        manager.cancelNotification()
                        manager.refresh()
                    }
                    ACTION_CHECK_UPCOMING -> {
                        manager.refresh()
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
