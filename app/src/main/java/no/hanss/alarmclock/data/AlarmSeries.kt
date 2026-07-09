package no.hanss.alarmclock.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Defines an alarm series, e.g. "7:00 Alarms": a series of alarms fired
 * independently every [intervalMinutes] starting at [startHour]:[startMinute],
 * spanning [durationMinutes] in total.
 *
 * Example: start 07:00, interval 5, duration 45 -> alarms at 07:00, 07:05, ..., 07:45
 * (10 alarms). Each is a fully independent [Alarm] row generated from this series.
 */
@Entity(tableName = "alarm_series")
data class AlarmSeries(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startHour: Int,
    val startMinute: Int,
    val intervalMinutes: Int,
    val durationMinutes: Int,
    val daysOfWeek: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val soundUri: String? = null,
    val volumeRampSeconds: Int = 0,
    val snoozeMinutes: Int = 10
) {
    /**
     * All (hour, minute, dayShift) triples this series expands to, in order. dayShift
     * is how many days past the start day the time lands on once the series wraps
     * past midnight: 0 for same-day times, 1 after the first wrap (e.g. start 23:50,
     * interval 15 -> (23,50,0), (0,5,1), (0,20,1)). Callers scheduling repeat days
     * must shift them by dayShift, or wrapped times fire almost a day early.
     */
    fun expandTimes(): List<Triple<Int, Int, Int>> {
        val times = mutableListOf<Triple<Int, Int, Int>>()
        val totalStart = startHour * 60 + startMinute
        var offset = 0
        while (offset <= durationMinutes) {
            val total = totalStart + offset
            times.add(Triple((total / 60) % 24, total % 60, total / (24 * 60)))
            offset += intervalMinutes
        }
        return times
    }
}
