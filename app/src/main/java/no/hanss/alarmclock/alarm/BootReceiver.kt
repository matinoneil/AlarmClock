package no.hanss.alarmclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.AlarmDatabase

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
            } finally {
                pendingResult.finish()
            }
        }
    }
}
