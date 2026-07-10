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

// Actions for the running-timer notification's buttons. Broadcasts rather than
// service intents on purpose: while a timer counts down NOTHING is running --
// the notification chronometer is OS-rendered -- so a receiver is the only
// always-available entry point.
const val ACTION_TIMER_ADD_30 = "no.hanss.alarmclock.action.TIMER_ADD_30"
const val ACTION_TIMER_MINUS_30 = "no.hanss.alarmclock.action.TIMER_MINUS_30"
const val ACTION_TIMER_STOP = "no.hanss.alarmclock.action.TIMER_STOP"

private const val ADJUST_MILLIS = 30_000L

class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L)
        if (timerId == -1L) return
        val action = intent.action

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_TIMER_ADD_30, ACTION_TIMER_MINUS_30 -> adjust(
                        context, timerId,
                        if (action == ACTION_TIMER_ADD_30) ADJUST_MILLIS else -ADJUST_MILLIS
                    )
                    ACTION_TIMER_STOP -> stop(context, timerId)
                    else -> fire(context, timerId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** The countdown reached zero (AlarmManager fired): ring. */
    private suspend fun fire(context: Context, timerId: Long) {
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
        // The countdown notification's job is done; the ringing one takes over.
        TimerNotificationManager(context).cancel(timerId)

        val serviceIntent = Intent(context, AlarmRingtoneService::class.java).apply {
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    /**
     * +30 s / -30 s from the notification. Everything is re-derived from the
     * DB row, so a tap racing the natural fire (or a stop from the app) just
     * finds an idle row and tidies the notification instead of misfiring.
     */
    private suspend fun adjust(context: Context, timerId: Long, deltaMillis: Long) {
        val dao = AlarmDatabase.getInstance(context).timerDao()
        val notifications = TimerNotificationManager(context)
        val timer = dao.getTimer(timerId)
        val until = timer?.runningUntilMillis
        if (timer == null || until == null) {
            // Fired, stopped, or deleted between the tap and this broadcast.
            notifications.cancel(timerId)
            return
        }

        val newUntil = until + deltaMillis
        if (newUntil <= System.currentTimeMillis()) {
            // -30 s pushed the countdown past its end: it's over, ring now.
            // Reuse the exact fire path (DB reset + notification handoff) so
            // there's a single way a timer ends in a ring; just disarm the
            // now-stale AlarmManager entry first.
            TimerScheduler(context).cancel(timerId)
            fire(context, timerId)
            return
        }

        val updated = timer.copy(runningUntilMillis = newUntil)
        dao.update(updated)
        // Re-arm at the new time and redraw the notification's chronometer.
        TimerScheduler(context).schedule(updated)
        notifications.post(updated)
    }

    /** Stop from the notification: identical outcome to the list toggle off. */
    private suspend fun stop(context: Context, timerId: Long) {
        val dao = AlarmDatabase.getInstance(context).timerDao()
        TimerScheduler(context).cancel(timerId)
        TimerNotificationManager(context).cancel(timerId)
        dao.getTimer(timerId)?.let { timer ->
            if (timer.isRunning) dao.update(timer.copy(runningUntilMillis = null))
        }
    }
}
