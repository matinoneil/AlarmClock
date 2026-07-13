package no.hanss.alarmclock.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single alarm instance.
 *
 * Standalone alarms have [seriesId] == null.
 * Alarms that belong to an alarm series (e.g. "7:00 Alarms" firing every 5 min
 * for 45 minutes) have [seriesId] pointing at the owning [AlarmSeries], and [offsetMinutes]
 * is that alarm's position within the series (0, 5, 10, ...). Each one is still an
 * independent alarm as far as AlarmManager and the ringing UI are concerned -- dismissing
 * one has no effect on the others.
 */
@Entity(
    tableName = "alarms",
    foreignKeys = [
        ForeignKey(
            entity = AlarmSeries::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("seriesId")]
)
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long? = null,
    val offsetMinutes: Int = 0, // position within the series; 0 for standalone alarms
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val daysOfWeek: Set<Int> = emptySet(), // 1=Mon .. 7=Sun (ISO-8601), empty = one-shot
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val soundUri: String? = null,
    val volumeRampSeconds: Int = 0, // 0 = ring at full volume immediately
    val snoozeMinutes: Int = 10,
    // Exact epoch millis of an occurrence that should be skipped (from the "dismiss
    // next alarm" action on the upcoming-alarm notification), or null if none is
    // pending. Recomputing "when's the next occurrence" without this would just find
    // the same occurrence again, since nothing else about the alarm changed.
    val skipOccurrenceMillis: Long? = null,
    // Exact epoch millis a snoozed repeating alarm should ring at, or null if not
    // snoozed. Persisted (rather than only re-pointing AlarmManager) so the snooze
    // survives a reboot or app update -- BootReceiver rebuilds AlarmManager entries
    // purely from the database, so anything not in a row here doesn't exist to it.
    // Overrides the weekly schedule while in the future; cleared when the alarm
    // fires, is toggled, or is edited. One-shot snoozes don't use this: they persist
    // a new hour/minute directly instead (see entry #3 in PROJECT_NOTES).
    val snoozeUntilMillis: Long? = null,
    // Epoch millis (LOCAL midnight of the resume day) before which no occurrence
    // may ring; null = not paused. Unlike a series pause there is NO resume
    // machinery: nextTriggerTime simply floors its reference here, so
    // AlarmManager is armed at the first post-pause occurrence and the pause
    // ends passively (see entry #44).
    val pausedUntilMillis: Long? = null
) {
    fun isPausedAt(nowMillis: Long): Boolean =
        pausedUntilMillis?.let { it > nowMillis } == true
}
