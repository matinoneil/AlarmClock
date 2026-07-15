package no.hanss.alarmclock.alarm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.hanss.alarmclock.data.AlarmDatabase
import no.hanss.alarmclock.data.Reminder
import no.hanss.alarmclock.data.SettingsStore
import no.hanss.alarmclock.data.nextOccurrenceAfter

private const val TAG = "ReminderOps"

// While a reminder is ACTIVE, the same AlarmManager slot re-fires this often
// to re-post the notification -- both the requested daily nudge and the safety
// net for an accidental swipe-away (setOngoing is only best-effort on 14+).

/**
 * The one place a reminder changes state (same single-path spirit as
 * SeriesUnpauseOps): [fire], [markDone], and [snoozeTo] are called from the
 * receiver's broadcasts, the snooze dialog, the list screen, and boot -- all
 * funneled through one [Mutex] so a Done tap racing the daily re-remind (or
 * two taps racing each other) can't interleave read-modify-writes on the same
 * row (#35's lesson). Every method re-reads the row inside the lock, so the
 * race loser sees the truth and no-ops.
 */
object ReminderOps {

    private val mutex = Mutex()

    /**
     * The AlarmManager slot fired. PENDING -> ACTIVE with an alerting
     * notification; ACTIVE -> re-post (the daily re-remind). Both re-arm the
     * next re-remind, so an ACTIVE reminder is never without a comeback path.
     */
    suspend fun fire(context: Context, reminderId: Long) = mutex.withLock {
        val dao = AlarmDatabase.getInstance(context).reminderDao()
        val notifications = ReminderNotificationManager(context)
        val reminder = dao.getReminder(reminderId)
        if (reminder == null || reminder.state == Reminder.STATE_DONE) {
            // Deleted or completed between arming and firing (cancel is
            // best-effort): tidy up and stay quiet.
            notifications.cancel(reminderId)
            return@withLock
        }
        val active = reminder.copy(state = Reminder.STATE_ACTIVE, snoozedUntilMillis = null)
        if (active != reminder) dao.update(active)
        notifications.post(active)
        ReminderScheduler(context).schedule(reminderId, System.currentTimeMillis() + active.renotifyMinutes * 60_000L)
    }

    /**
     * Done (notification button, list checkmark, or edit screen). One-shots
     * become history; repeating reminders roll back to PENDING at the next
     * on-pattern occurrence -- computed from the SCHEDULED dueAt, so snoozes
     * and late completion never drift the pattern.
     */
    suspend fun markDone(context: Context, reminderId: Long) = mutex.withLock {
        val dao = AlarmDatabase.getInstance(context).reminderDao()
        val scheduler = ReminderScheduler(context)
        ReminderNotificationManager(context).cancel(reminderId)
        scheduler.cancel(reminderId)
        val reminder = dao.getReminder(reminderId) ?: return@withLock

        val next = if (reminder.isRepeating) nextOccurrenceAfter(reminder, System.currentTimeMillis()) else null
        if (next != null) {
            dao.update(reminder.copy(state = Reminder.STATE_PENDING, dueAtMillis = next, snoozedUntilMillis = null))
            scheduler.schedule(reminderId, next)
        } else {
            if (reminder.isRepeating) {
                // nextOccurrenceAfter only returns null for a repeating
                // reminder on its runaway guard -- degrade to done, loudly.
                Log.e(TAG, "Repeating reminder $reminderId had no computable next occurrence; marking done")
            }
            dao.update(reminder.copy(state = Reminder.STATE_DONE, snoozedUntilMillis = null))
        }
    }

    /**
     * The notification was dismissed without Done (#57). Rearm the reminder's
     * single scheduler slot at now + the configured re-show delay -- much
     * sooner than the daily re-alert it replaces in that slot. When it fires,
     * the normal ACTIVE re-post path runs (and re-arms the daily re-alert).
     * Done/snooze racing this resolve behind the mutex: if the row is no
     * longer ACTIVE by the time we run, the swipe means nothing.
     */
    suspend fun onSwipedAway(context: Context, reminderId: Long) = mutex.withLock {
        val dao = AlarmDatabase.getInstance(context).reminderDao()
        val reminder = dao.getReminder(reminderId) ?: return@withLock
        if (reminder.state != Reminder.STATE_ACTIVE) return@withLock
        val minutes = SettingsStore(context).reminderReshowMinutes
        if (minutes == 0) {
            // "Permanent" (#58): straight back at full volume -- the
            // maintainer wants the swipe to visibly and audibly not work.
            ReminderNotificationManager(context).post(reminder)
            ReminderScheduler(context).schedule(reminderId, System.currentTimeMillis() + reminder.renotifyMinutes * 60_000L)
        } else {
            ReminderScheduler(context).schedule(reminderId, System.currentTimeMillis() + minutes * 60_000L)
        }
    }

    /**
     * Delete per entry #55: a live reminder RETIRES to history (state DONE,
     * scheduling and notification cancelled, repeat fields kept so the faded
     * card still describes itself); one already IN history is erased for
     * real. Clear history remains the bulk erase.
     */
    suspend fun delete(context: Context, reminderId: Long) = mutex.withLock {
        val dao = AlarmDatabase.getInstance(context).reminderDao()
        ReminderNotificationManager(context).cancel(reminderId)
        ReminderScheduler(context).cancel(reminderId)
        val reminder = dao.getReminder(reminderId) ?: return@withLock
        if (reminder.state == Reminder.STATE_DONE) {
            dao.delete(reminder)
        } else {
            dao.update(reminder.copy(state = Reminder.STATE_DONE, snoozedUntilMillis = null))
        }
    }

    /**
     * Snooze to an explicit time (dialog preset or picked date/time). Back to
     * PENDING with only the override set -- dueAtMillis stays put as the
     * repeat pattern's reference (#12's schedule/override split).
     */
    suspend fun snoozeTo(context: Context, reminderId: Long, untilMillis: Long) = mutex.withLock {
        val dao = AlarmDatabase.getInstance(context).reminderDao()
        ReminderNotificationManager(context).cancel(reminderId)
        val reminder = dao.getReminder(reminderId) ?: run {
            ReminderScheduler(context).cancel(reminderId)
            return@withLock
        }
        dao.update(reminder.copy(state = Reminder.STATE_PENDING, snoozedUntilMillis = untilMillis))
        ReminderScheduler(context).schedule(reminderId, untilMillis)
    }

    /**
     * Boot/update pass for one reminder: re-arm a future PENDING, fire an
     * overdue one late (unlike an expired kitchen timer, a reminder that came
     * due while the phone was off is still wanted), and bring an ACTIVE one's
     * notification back (notifications don't survive reboots) with a fresh
     * re-remind armed.
     */
    suspend fun refresh(context: Context, reminderId: Long) {
        // No lock here: fire() takes it, and the PENDING-future branch is a
        // pure re-arm that overwrite-schedules the same slot.
        val dao = AlarmDatabase.getInstance(context).reminderDao()
        val reminder = dao.getReminder(reminderId) ?: return
        when (reminder.state) {
            Reminder.STATE_PENDING -> {
                val at = reminder.effectiveDueAtMillis
                if (at > System.currentTimeMillis()) {
                    ReminderScheduler(context).schedule(reminderId, at)
                } else {
                    Log.w(TAG, "Reminder $reminderId came due while nothing was armed; firing late")
                    fire(context, reminderId)
                }
            }
            Reminder.STATE_ACTIVE -> fire(context, reminderId)
            // STATE_DONE: nothing to do.
        }
    }
}
