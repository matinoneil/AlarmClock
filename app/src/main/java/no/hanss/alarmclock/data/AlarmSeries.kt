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
    val volumeRampSeconds: Int = 0
) {
    /** All (hour, minute) pairs this series expands to, in order, wrapping past midnight if needed. */
    fun expandTimes(): List<Pair<Int, Int>> {
        val times = mutableListOf<Pair<Int, Int>>()
        var totalStart = startHour * 60 + startMinute
        var offset = 0
        while (offset <= durationMinutes) {
            val total = (totalStart + offset).mod(24 * 60)
            times.add(total / 60 to total % 60)
            offset += intervalMinutes
        }
        return times
    }
}
