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
    val volumeRampSeconds: Int = 0 // 0 = ring at full volume immediately
)
