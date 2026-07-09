package no.hanss.alarmclock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar
import no.hanss.alarmclock.data.Alarm

private const val TAG = "AlarmScheduler"

const val EXTRA_ALARM_ID = "extra_alarm_id"

/**
 * Schedules and cancels individual alarms with the system AlarmManager.
 * Each [Alarm] (standalone or a member of an alarm series) is scheduled independently,
 * identified by its own request code (its database id).
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(alarm: Alarm) {
        if (!alarm.enabled) {
            cancel(alarm)
            return
        }
        val triggerAtMillis = nextTriggerTime(alarm)
        setAlarmManagerEntry(alarm.id, triggerAtMillis)
    }

    fun cancel(alarm: Alarm) {
        val pendingIntent = findExistingPendingIntent(alarm.id) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    fun canScheduleExactAlarms(): Boolean =
        alarmManager.canScheduleExactAlarms()

    /** Exposes the next trigger time without touching AlarmManager, for the upcoming-alarm notification. */
    fun peekNextTriggerTime(alarm: Alarm): Long = nextTriggerTime(alarm)

    private fun setAlarmManagerEntry(alarmId: Long, triggerAtMillis: Long) {
        val pendingIntent = createPendingIntent(alarmId)
        // setAlarmClock() shows the little alarm icon in the status bar and is the most
        // reliable way to fire exactly on time even under Doze, without needing the user
        // to grant battery-optimization exemptions.
        //
        // Guarded because on Android 12/13 the "Alarms & reminders" special access
        // (SCHEDULE_EXACT_ALARM) can be revoked by the user, after which this throws
        // SecurityException -- including from BootReceiver while rescheduling every
        // alarm at boot, which would crash the whole reschedule loop. Degrade to an
        // inexact set() (may fire minutes late under Doze) rather than not arming
        // the alarm at all.
        try {
            val info = AlarmManager.AlarmClockInfo(triggerAtMillis, pendingIntent)
            alarmManager.setAlarmClock(info, pendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "setAlarmClock failed (exact-alarm permission revoked?); falling back to inexact set()", e)
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback inexact set() also failed; alarm $alarmId is NOT armed", e2)
            }
        }
    }

    private fun intentFor(alarmId: Long): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
        }

    /** Always returns a real (non-null) PendingIntent, creating one if needed. */
    private fun createPendingIntent(alarmId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, alarmId.toInt(), intentFor(alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Returns an existing PendingIntent if one was already scheduled, or null otherwise. */
    private fun findExistingPendingIntent(alarmId: Long): PendingIntent? =
        PendingIntent.getBroadcast(
            context, alarmId.toInt(), intentFor(alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )

    /**
     * Computes the next millis this alarm should fire at, honoring repeat days if set,
     * and skipping past [Alarm.skipOccurrenceMillis] if the naive next occurrence would
     * otherwise land exactly on it.
     */
    private fun nextTriggerTime(alarm: Alarm, referenceMillis: Long = System.currentTimeMillis()): Long {
        // A persisted snooze overrides the regular schedule while it's still in the
        // future. If it's in the past (phone was off through the snooze time), fall
        // through to the normal computation rather than firing hours late;
        // AlarmReceiver clears the field whenever the alarm actually fires.
        alarm.snoozeUntilMillis?.let { snoozeUntil ->
            if (snoozeUntil > referenceMillis) return snoozeUntil
        }
        val candidate = rawNextTriggerTime(alarm, referenceMillis)
        val skip = alarm.skipOccurrenceMillis
        return if (skip != null && candidate == skip) {
            rawNextTriggerTime(alarm, candidate + 60_000L)
        } else {
            candidate
        }
    }

    private fun rawNextTriggerTime(alarm: Alarm, referenceMillis: Long): Long {
        val now = Calendar.getInstance().apply { timeInMillis = referenceMillis }

        fun candidateFor(dayOffset: Int): Calendar = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (alarm.daysOfWeek.isEmpty()) {
            // One-shot: today if still in the future, otherwise tomorrow.
            val today = candidateFor(0)
            return if (today.timeInMillis > now.timeInMillis) today.timeInMillis
            else candidateFor(1).timeInMillis
        }

        // Repeating: find the next day (0..7 from today) whose ISO weekday is in daysOfWeek
        // and, for day 0, whose time hasn't already passed.
        for (dayOffset in 0..7) {
            val candidate = candidateFor(dayOffset)
            val isoWeekday = ((candidate.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 // Mon=1..Sun=7
            if (isoWeekday in alarm.daysOfWeek) {
                if (dayOffset > 0 || candidate.timeInMillis > now.timeInMillis) {
                    return candidate.timeInMillis
                }
            }
        }
        // Fallback (shouldn't happen since we check a full week)
        return candidateFor(7).timeInMillis
    }
}
