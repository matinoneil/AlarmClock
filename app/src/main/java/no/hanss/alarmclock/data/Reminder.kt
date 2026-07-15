package no.hanss.alarmclock.data

import android.util.Log
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Calendar

private const val TAG = "Reminder"

/**
 * A notification reminder: text plus a date/time. Unlike an alarm it never
 * rings -- it posts a (best-effort persistent) notification with Done and
 * Snooze actions, handled by ReminderReceiver/ReminderOps.
 *
 * Lifecycle is a three-state machine in [state]:
 *  - PENDING: scheduled; AlarmManager is armed at [snoozedUntilMillis] ?: [dueAtMillis].
 *  - ACTIVE:  fired; the notification is showing (and re-posting daily) until
 *             the user hits Done.
 *  - DONE:    finished one-shot; kept as faded history in the list. Repeating
 *             reminders never enter this state -- Done rolls them back to
 *             PENDING at the next occurrence.
 *
 * [dueAtMillis] is always an on-pattern occurrence and is what repeats roll
 * from; a snooze only sets [snoozedUntilMillis] (same schedule/override split
 * as alarm snoozes, entry #12) so postponing one occurrence never drifts the
 * repeat pattern.
 */
@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    // The current occurrence (epoch millis). Repeats roll this forward.
    val dueAtMillis: Long,
    val state: Int = STATE_PENDING,
    val repeatType: Int = REPEAT_NONE,
    // "Every N" days/weeks/months/years; 1 for plain daily/weekly/monthly/yearly.
    val repeatInterval: Int = 1,
    // REPEAT_WEEKLY only: ISO weekdays 1=Mon..7=Sun (reuses the alarm converter).
    val repeatDaysOfWeek: Set<Int> = emptySet(),
    // REPEAT_MONTHLY_DATE only: 1..31, clamped to the target month's length.
    val repeatDayOfMonth: Int = 0,
    // REPEAT_MONTHLY_WEEKDAY only: which weekday (ISO 1..7) and which one of
    // the month (1..4, or LAST_WEEK_OF_MONTH for "the last <weekday>").
    val repeatWeekday: Int = 0,
    val repeatWeekOfMonth: Int = 0,
    // How often the notification re-alerts while it sits unhandled (#59),
    // and the comeback pacing after an instant swipe re-post. Minutes;
    // 1440 = the original fixed daily nag; 0 = off, never re-alerts (#62).
    val renotifyMinutes: Int = 1440,
    // How quickly the notification returns after being swiped away (#60):
    // RESHOW_FOLLOW_GLOBAL follows the Settings value, RESHOW_OFF = a
    // swipe sticks (#62), 0 = instantly ("permanent"), N = after N minutes.
    val reshowMinutes: Int = RESHOW_FOLLOW_GLOBAL,
    // #61: OFF = one-and-done -- the notification posts once, dismissable
    // like any other, no re-alerts, and a swipe counts as Done.
    val persistent: Boolean = true,
    // Overrides dueAtMillis for scheduling while set; cleared when the
    // reminder fires. Never consulted by the repeat roll.
    val snoozedUntilMillis: Long? = null
) {
    val isRepeating: Boolean get() = repeatType != REPEAT_NONE

    /** What AlarmManager should actually be armed at while PENDING. */
    val effectiveDueAtMillis: Long get() = snoozedUntilMillis ?: dueAtMillis

    companion object {
        const val RESHOW_FOLLOW_GLOBAL = -1
        const val RESHOW_OFF = -2
        const val STATE_PENDING = 0
        const val STATE_ACTIVE = 1
        const val STATE_DONE = 2

        const val REPEAT_NONE = 0
        const val REPEAT_DAILY = 1
        const val REPEAT_WEEKLY = 2
        const val REPEAT_MONTHLY_DATE = 3
        const val REPEAT_MONTHLY_WEEKDAY = 4
        const val REPEAT_YEARLY = 5

        const val LAST_WEEK_OF_MONTH = -1
    }
}

/**
 * The single occurrence strictly after [fromMillis], rolled from the pattern.
 * Calendar arithmetic throughout so DST transitions keep the wall-clock time
 * (a 09:00 reminder stays 09:00 across the switch) instead of drifting by an
 * hour the way raw millis addition would.
 */
private fun nextOccurrenceOnce(reminder: Reminder, fromMillis: Long): Long {
    val interval = reminder.repeatInterval.coerceAtLeast(1)
    val cal = Calendar.getInstance().apply { timeInMillis = fromMillis }
    when (reminder.repeatType) {
        Reminder.REPEAT_DAILY -> cal.add(Calendar.DAY_OF_YEAR, interval)

        Reminder.REPEAT_WEEKLY -> {
            // dueAt always sits in an "on" week by construction, so the next
            // valid slot is either a later selected weekday in this same week,
            // or the first selected weekday of the week `interval` weeks on.
            // No stored anchor needed -- validity is preserved roll to roll.
            val days = reminder.repeatDaysOfWeek.ifEmpty { setOf(isoDayOf(cal)) }.sorted()
            val currentIso = isoDayOf(cal)
            val laterThisWeek = days.firstOrNull { it > currentIso }
            if (laterThisWeek != null) {
                cal.add(Calendar.DAY_OF_YEAR, laterThisWeek - currentIso)
            } else {
                cal.add(Calendar.WEEK_OF_YEAR, interval)
                cal.add(Calendar.DAY_OF_YEAR, days.first() - currentIso)
            }
        }

        Reminder.REPEAT_MONTHLY_DATE -> {
            val day = reminder.repeatDayOfMonth.coerceIn(1, 31)
            // Move to the 1st BEFORE adding months: adding a month while
            // sitting on the 31st clamps (Jan 31 -> Feb 28) and would silently
            // shift which month we land in.
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, interval)
            cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }

        Reminder.REPEAT_MONTHLY_WEEKDAY -> {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, interval)
            setToNthWeekdayOfMonth(cal, reminder.repeatWeekday, reminder.repeatWeekOfMonth)
        }

        Reminder.REPEAT_YEARLY -> {
            val month = cal.get(Calendar.MONTH)
            val day = cal.get(Calendar.DAY_OF_MONTH)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.YEAR, interval)
            cal.set(Calendar.MONTH, month)
            // Feb 29 lands on Feb 28 in non-leap years.
            cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }

        else -> return fromMillis // REPEAT_NONE: caller shouldn't ask.
    }
    return cal.timeInMillis
}

/**
 * The next on-pattern occurrence strictly after [nowMillis], rolled from the
 * reminder's current [Reminder.dueAtMillis]. Loops because a reminder can be
 * completed long after several occurrences passed (daily reminder, Done three
 * days late); each step stays on-pattern so the catch-up never drifts the
 * schedule. Returns null for non-repeating reminders and on the (should-be-
 * impossible) runaway guard, which callers must treat as "no next occurrence".
 */
fun nextOccurrenceAfter(reminder: Reminder, nowMillis: Long): Long? {
    if (!reminder.isRepeating) return null
    var t = reminder.dueAtMillis
    var guard = 0
    while (t <= nowMillis) {
        val next = nextOccurrenceOnce(reminder, t)
        if (next <= t) {
            // A repeat pattern must strictly advance; anything else would spin
            // this loop forever. Log-never-swallow, degrade to "no repeat".
            Log.e(TAG, "Repeat pattern for reminder ${reminder.id} failed to advance past $t; dropping the repeat")
            return null
        }
        t = next
        if (++guard > 5000) {
            Log.e(TAG, "Repeat catch-up for reminder ${reminder.id} exceeded 5000 steps; dropping the repeat")
            return null
        }
    }
    return t
}

private fun isoDayOf(cal: Calendar): Int {
    // Calendar: Sunday=1..Saturday=7 -> ISO: Monday=1..Sunday=7.
    val d = cal.get(Calendar.DAY_OF_WEEK)
    return if (d == Calendar.SUNDAY) 7 else d - 1
}

/** In-place: the [week]th [isoWeekday] of cal's current month (or the last, for [Reminder.LAST_WEEK_OF_MONTH]). */
private fun setToNthWeekdayOfMonth(cal: Calendar, isoWeekday: Int, week: Int) {
    val target = isoWeekday.coerceIn(1, 7)
    if (week == Reminder.LAST_WEEK_OF_MONTH) {
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        while (isoDayOf(cal) != target) cal.add(Calendar.DAY_OF_YEAR, -1)
    } else {
        cal.set(Calendar.DAY_OF_MONTH, 1)
        while (isoDayOf(cal) != target) cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.add(Calendar.DAY_OF_YEAR, 7 * (week.coerceIn(1, 4) - 1))
        // A 5th week request clamped to 4 can't overflow the month; weeks 1-4
        // of any weekday always exist.
    }
}

/** "Daily", "Every 2 weeks · Mon, Thu", "Monthly on day 15", "Every 3 months on the last Fri", "Yearly" ... */
fun describeRepeat(reminder: Reminder): String {
    val n = reminder.repeatInterval.coerceAtLeast(1)
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    fun unit(one: String, many: String) = if (n == 1) one else "Every $n $many"
    return when (reminder.repeatType) {
        Reminder.REPEAT_DAILY -> unit("Daily", "days")
        Reminder.REPEAT_WEEKLY -> {
            val days = reminder.repeatDaysOfWeek.sorted().joinToString(", ") { dayNames[it - 1] }
            val base = unit("Weekly", "weeks")
            if (days.isBlank()) base else "$base · $days"
        }
        Reminder.REPEAT_MONTHLY_DATE ->
            "${unit("Monthly", "months")} on day ${reminder.repeatDayOfMonth}"
        Reminder.REPEAT_MONTHLY_WEEKDAY -> {
            val which = when (reminder.repeatWeekOfMonth) {
                Reminder.LAST_WEEK_OF_MONTH -> "last"
                1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${reminder.repeatWeekOfMonth}th"
            }
            "${unit("Monthly", "months")} on the $which ${dayNames[(reminder.repeatWeekday - 1).coerceIn(0, 6)]}"
        }
        Reminder.REPEAT_YEARLY -> unit("Yearly", "years")
        else -> ""
    }
}
