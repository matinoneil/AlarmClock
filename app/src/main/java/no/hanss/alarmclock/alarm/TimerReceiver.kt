package no.hanss.alarmclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.AlarmDatabase

private const val TAG = "TimerReceiver"

class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L)
        if (timerId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AlarmDatabase.getInstance(context).timerDao()
                val timer = dao.getTimer(timerId)
                if (timer != null) {
                    // The countdown is over: flip the preset back to idle so the
                    // list toggle reflects reality (same DB-driven spirit as
                    // one-shot alarms disabling themselves on fire, entry 0.2).
                    dao.update(timer.copy(runningUntilMillis = null))
                } else {
                    // Row deleted but the AlarmManager entry survived (cancel is
                    // best-effort). Ring anyway with defaults -- the user started
                    // this countdown expecting a ring; the service handles a
                    // missing row gracefully.
                    Log.w(TAG, "Timer $timerId fired but its row is gone; ringing with defaults")
                }

                val serviceIntent = Intent(context, AlarmRingtoneService::class.java).apply {
                    putExtra(EXTRA_TIMER_ID, timerId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
