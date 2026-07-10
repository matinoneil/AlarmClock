package no.hanss.alarmclock.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved, reusable countdown timer preset (e.g. "5 min", "10 min tea").
 *
 * Unlike an [Alarm], a timer has no wall-clock schedule -- it's a duration.
 * The list-screen toggle starts/stops the countdown: starting stamps
 * [runningUntilMillis] with the exact epoch millis it should ring at and arms
 * AlarmManager via TimerScheduler; stopping (or the timer firing) clears it
 * back to null. Persisting the target time (rather than keeping the countdown
 * only in memory) is what lets BootReceiver re-arm a running timer after a
 * reboot or app update, mirroring how alarms are DB-driven.
 */
@Entity(tableName = "timers")
data class TimerPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val durationSeconds: Int,
    val label: String = "",
    val vibrate: Boolean = true,
    val soundUri: String? = null,
    // Epoch millis the running countdown ends at, or null when the timer is idle.
    val runningUntilMillis: Long? = null
) {
    val isRunning: Boolean get() = runningUntilMillis != null
}

/** "05:00" for 5 min, "1:05:00" for 65 min -- shared by list, edit, and ringing UIs. */
fun formatTimerDuration(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}
