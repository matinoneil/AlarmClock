package no.hanss.alarmclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Done is a broadcast (not an activity) so tapping it never opens anything;
// the fire path has no action at all -- ReminderOps decides initial-fire vs
// daily-re-remind from the row's state. Same component + request code as the
// Done PendingIntent, distinct action: filterEquals keeps them apart.
const val ACTION_REMINDER_DONE = "no.hanss.alarmclock.action.REMINDER_DONE"

/**
 * Entry point for reminder AlarmManager fires and the notification's Done
 * button. All real logic lives in [ReminderOps] behind its mutex, so a Done
 * tap racing the daily re-remind resolves cleanly no matter the order.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId == -1L) return
        val action = intent.action

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_REMINDER_DONE -> ReminderOps.markDone(context, reminderId)
                    else -> ReminderOps.fire(context, reminderId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
