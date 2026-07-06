package no.hanss.alarmclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.AlarmDatabase

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
                    // Launch the ringing UI / sound service.
                    val serviceIntent = Intent(context, AlarmRingtoneService::class.java).apply {
                        putExtra(EXTRA_ALARM_ID, alarm.id)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }

                    // If it repeats on certain weekdays, schedule the next occurrence.
                    // One-shot alarms (empty daysOfWeek) are left as fired; the UI can
                    // re-enable them manually if desired.
                    if (alarm.daysOfWeek.isNotEmpty()) {
                        AlarmScheduler(context).schedule(alarm)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
