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
                val dao = AlarmDatabase.getInstance(context).alarmDao()
                val scheduler = AlarmScheduler(context)
                dao.getAllEnabledAlarms().forEach { scheduler.schedule(it) }
                UpcomingAlarmManager(context).refresh()
                AlarmWidgetUpdater.updateAll(context)

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
                    prefs.edit().remove(KEY_RINGING_ID).remove(KEY_RINGING_SINCE).apply()
                    if (age in 0..RING_RESUME_GRACE_MILLIS) {
                        Log.w(TAG, "Resuming alarm $interruptedId that was interrupted mid-ring")
                        val serviceIntent = Intent(context, AlarmRingtoneService::class.java).apply {
                            putExtra(EXTRA_ALARM_ID, interruptedId)
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
