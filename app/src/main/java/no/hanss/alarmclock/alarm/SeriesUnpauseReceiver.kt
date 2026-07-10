package no.hanss.alarmclock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.AlarmDatabase

private const val TAG = "SeriesUnpause"

const val EXTRA_SERIES_ID = "extra_series_id"

/**
 * Arms/cancels the automatic resume of a paused series.
 *
 * Uses setAndAllowWhileIdle rather than setAlarmClock on purpose: it's exempt
 * from the "Alarms & reminders" permission (no SecurityException path to
 * guard), doesn't put an alarm icon in the status bar for what is invisible
 * bookkeeping, and its worst case -- a few minutes of Doze delay -- is
 * harmless when the resume fires at midnight and the first real alarm is
 * hours later. The reboot gap (AlarmManager entries die with the phone) is
 * covered by BootReceiver, and app-open reconciliation catches anything else.
 */
class SeriesUnpauseScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(seriesId: Long, untilMillis: Long) {
        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, untilMillis, pendingIntent(seriesId)
            )
        } catch (e: Exception) {
            // Shouldn't be possible for this alarm type, but a paused series
            // failing to resume is a missed wake-up: never let this throw.
            // BootReceiver/app-open reconciliation remain as safety nets.
            Log.e(TAG, "Failed to arm unpause for series $seriesId", e)
        }
    }

    fun cancel(seriesId: Long) {
        val existing = PendingIntent.getBroadcast(
            context, seriesId.toInt(), intentFor(seriesId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        ) ?: return
        alarmManager.cancel(existing)
        existing.cancel()
    }

    private fun intentFor(seriesId: Long): Intent =
        Intent(context, SeriesUnpauseReceiver::class.java).apply {
            putExtra(EXTRA_SERIES_ID, seriesId)
        }

    private fun pendingIntent(seriesId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, seriesId.toInt(), intentFor(seriesId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/**
 * The one true unpause: clears the pause, re-enables and reschedules every
 * child alarm, and refreshes the upcoming notification + widget. Shared by
 * the receiver, BootReceiver, and AlarmRepository so a series can only ever
 * resume one way.
 */
object SeriesUnpauseOps {
    suspend fun unpause(context: Context, seriesId: Long) {
        val db = AlarmDatabase.getInstance(context)
        val series = db.alarmSeriesDao().getSeries(seriesId) ?: run {
            Log.w(TAG, "Unpause fired for series $seriesId but it's gone")
            return
        }
        SeriesUnpauseScheduler(context).cancel(seriesId)
        if (series.pausedUntilMillis == null) return // already resumed elsewhere

        db.alarmSeriesDao().update(series.copy(pausedUntilMillis = null, enabled = true))
        val scheduler = AlarmScheduler(context)
        db.alarmDao().getAlarmsForSeries(seriesId).forEach { child ->
            val updated = child.copy(enabled = true)
            db.alarmDao().update(updated)
            scheduler.schedule(updated)
        }
        UpcomingAlarmManager(context).refresh()
        AlarmWidgetUpdater.updateAll(context)
        Log.i(TAG, "Series $seriesId resumed from pause")
    }
}

class SeriesUnpauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val seriesId = intent.getLongExtra(EXTRA_SERIES_ID, -1L)
        if (seriesId == -1L) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SeriesUnpauseOps.unpause(context, seriesId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
