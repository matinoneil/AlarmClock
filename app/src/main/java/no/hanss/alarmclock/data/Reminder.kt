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
    // REPEAT_MONTHLY_DATE only: 1..31 (clamped to the target month's
    // length) or LAST_DAY_OF_MONTH for the true last day (#64).
    val repeatDayOfMonth: Int = 0,
    // REPEAT_MONTHLY_WEEKDAY and REPEAT_YEARLY_WEEKDAY: which day (ISO 1..7,
    // or the WEEKDAY_ANY/WORKDAY/WEEKEND pseudo-days, #64) and which one of
    // the month (1..4, or LAST_WEEK_OF_MONTH for "the last <day>").
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
        // #64: "the 4th Thursday of November" style -- ordinal x day-spec,
        // month anchored from dueAt.
        const val REPEAT_YEARLY_WEEKDAY = 6

        const val LAST_WEEK_OF_MONTH = -1
        // #64: repeatDayOfMonth sentinel -- the true last day, not a clamped 31.
        const val LAST_DAY_OF_MONTH = -1
        // #64: repeatWeekday pseudo-days beyond ISO 1..7 (Outlook's trio):
        // any calendar day, any Mon-Fri, any Sat/Sun.
        const val WEEKDAY_ANY = 8
        const val WEEKDAY_WORKDAY = 9
        const val WEEKDAY_WEEKEND = 10
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
            // Move to the 1st BEFORE adding months: adding a month while
            // sitting on the 31st clamps (Jan 31 -> Feb 28) and would silently
            // shift which month we land in.
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, interval)
            cal.set(Calendar.DAY_OF_MONTH, resolveDayOfMonth(cal, reminder.repeatDayOfMonth))
        }

        Reminder.REPEAT_MONTHLY_WEEKDAY -> {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, interval)
            setToNthDaySpecOfMonth(cal, reminder.repeatWeekday, reminder.repeatWeekOfMonth)
        }

        Reminder.REPEAT_YEARLY_WEEKDAY -> {
            // Month is anchored by dueAt (always on-pattern), so reading it
            // from `cal` -- which starts at fromMillis, itself on-pattern --
            // is stable roll to roll.
            val month = cal.get(Calendar.MONTH)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.YEAR, interval)
            cal.set(Calendar.MONTH, month)
            setToNthDaySpecOfMonth(cal, reminder.repeatWeekday, reminder.repeatWeekOfMonth)
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

/** Does cal's current day satisfy [spec] -- ISO 1..7 or a pseudo-day (#64)? */
private fun matchesDaySpec(cal: Calendar, spec: Int): Boolean = when (spec) {
    Reminder.WEEKDAY_ANY -> true
    Reminder.WEEKDAY_WORKDAY -> isoDayOf(cal) in 1..5
    Reminder.WEEKDAY_WEEKEND -> isoDayOf(cal) >= 6
    else -> isoDayOf(cal) == spec.coerceIn(1, 7)
}

/** Day 1..31 or the true last day for [Reminder.LAST_DAY_OF_MONTH], within cal's month. */
private fun resolveDayOfMonth(cal: Calendar, dayOfMonth: Int): Int {
    val max = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    return if (dayOfMonth == Reminder.LAST_DAY_OF_MONTH) max
    else dayOfMonth.coerceIn(1, 31).coerceAtMost(max)
}

/**
 * In-place: the [week]th day matching [spec] in cal's current month (or the
 * last such day, for [Reminder.LAST_WEEK_OF_MONTH]). The 4th of any spec
 * always exists (>= 4 of each ISO weekday, >= 8 weekend days, >= 20 workdays
 * in every month), so the counting loop can't run off the month's end.
 */
private fun setToNthDaySpecOfMonth(cal: Calendar, spec: Int, week: Int) {
    if (week == Reminder.LAST_WEEK_OF_MONTH) {
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        while (!matchesDaySpec(cal, spec)) cal.add(Calendar.DAY_OF_YEAR, -1)
    } else {
        val target = week.coerceIn(1, 4)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        var count = if (matchesDaySpec(cal, spec)) 1 else 0
        while (count < target) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            if (matchesDaySpec(cal, spec)) count++
        }
    }
}

/**
 * The pattern is the source of truth (#64): the editor's picked datetime
 * aligns FORWARD to the nearest on-pattern occurrence at save, keeping the
 * time of day. Daily/weekly-with-empty-days/yearly-on-date are aligned by
 * construction; the rest resolve within the picked month (or the picked
 * month of the picked year) and step one period if that already passed.
 */
fun alignDueAtToPattern(reminder: Reminder): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = reminder.dueAtMillis }
    when (reminder.repeatType) {
        Reminder.REPEAT_WEEKLY -> {
            val days = reminder.repeatDaysOfWeek
            if (days.isEmpty() || isoDayOf(cal) in days) return reminder.dueAtMillis
            val sorted = days.sorted()
            val current = isoDayOf(cal)
            val next = sorted.firstOrNull { it > current } ?: sorted.first()
            val shift = if (next > current) next - current else 7 - current + next
            cal.add(Calendar.DAY_OF_YEAR, shift)
        }
        Reminder.REPEAT_MONTHLY_DATE -> {
            val picked = cal.timeInMillis
            cal.set(Calendar.DAY_OF_MONTH, resolveDayOfMonth(cal, reminder.repeatDayOfMonth))
            if (cal.timeInMillis < picked) {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, 1)
                cal.set(Calendar.DAY_OF_MONTH, resolveDayOfMonth(cal, reminder.repeatDayOfMonth))
            }
        }
        Reminder.REPEAT_MONTHLY_WEEKDAY -> {
            val picked = cal.timeInMillis
            setToNthDaySpecOfMonth(cal, reminder.repeatWeekday, reminder.repeatWeekOfMonth)
            if (cal.timeInMillis < picked) {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, 1)
                setToNthDaySpecOfMonth(cal, reminder.repeatWeekday, reminder.repeatWeekOfMonth)
            }
        }
        Reminder.REPEAT_YEARLY_WEEKDAY -> {
            val picked = cal.timeInMillis
            setToNthDaySpecOfMonth(cal, reminder.repeatWeekday, reminder.repeatWeekOfMonth)
            if (cal.timeInMillis < picked) {
                val month = cal.get(Calendar.MONTH)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.YEAR, 1)
                cal.set(Calendar.MONTH, month)
                setToNthDaySpecOfMonth(cal, reminder.repeatWeekday, reminder.repeatWeekOfMonth)
            }
        }
        else -> return reminder.dueAtMillis
    }
    return cal.timeInMillis
}

/** Human name for an ISO weekday or a pseudo-day spec (#64). */
fun daySpecName(spec: Int): String = when (spec) {
    Reminder.WEEKDAY_ANY -> "day"
    Reminder.WEEKDAY_WORKDAY -> "weekday"
    Reminder.WEEKDAY_WEEKEND -> "weekend day"
    else -> listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[(spec - 1).coerceIn(0, 6)]
}

/** "1st".."4th" or "last" (#64). */
fun ordinalName(week: Int): String = when (week) {
    Reminder.LAST_WEEK_OF_MONTH -> "last"
    1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${week.coerceIn(1, 4)}th"
}

/** "Daily", "Every 2 weeks · Mon, Thu", "Monthly on the last day", "Every 3 months on the 1st weekday", "Yearly on the last Sun of Mar" ... */
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
            if (reminder.repeatDayOfMonth == Reminder.LAST_DAY_OF_MONTH)
                "${unit("Monthly", "months")} on the last day"
            else "${unit("Monthly", "months")} on day ${reminder.repeatDayOfMonth}"
        Reminder.REPEAT_MONTHLY_WEEKDAY ->
            "${unit("Monthly", "months")} on the ${ordinalName(reminder.repeatWeekOfMonth)} ${daySpecName(reminder.repeatWeekday)}"
        Reminder.REPEAT_YEARLY -> unit("Yearly", "years")
        Reminder.REPEAT_YEARLY_WEEKDAY -> {
            val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val m = Calendar.getInstance().apply { timeInMillis = reminder.dueAtMillis }.get(Calendar.MONTH)
            "${unit("Yearly", "years")} on the ${ordinalName(reminder.repeatWeekOfMonth)} ${daySpecName(reminder.repeatWeekday)} of ${months[m]}"
        }
        else -> ""
    }
}
