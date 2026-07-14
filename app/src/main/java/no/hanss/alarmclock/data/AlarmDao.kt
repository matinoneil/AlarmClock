package no.hanss.alarmclock.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms WHERE seriesId IS NULL ORDER BY hour, minute")
    fun observeStandaloneAlarms(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE seriesId = :seriesId ORDER BY offsetMinutes")
    fun observeAlarmsForSeries(seriesId: Long): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE seriesId IS NOT NULL")
    fun observeSeriesChildAlarms(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE seriesId = :seriesId ORDER BY offsetMinutes")
    suspend fun getAlarmsForSeries(seriesId: Long): List<Alarm>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getAlarm(id: Long): Alarm?

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getAllEnabledAlarms(): List<Alarm>

    @Query("SELECT * FROM alarms WHERE seriesId IS NULL ORDER BY hour, minute")
    suspend fun getAllStandaloneAlarms(): List<Alarm>

    @Query("SELECT * FROM alarms")
    suspend fun getAllAlarms(): List<Alarm>

    @Query("UPDATE alarms SET soundUri = :soundUri, volumeRampSeconds = :rampSeconds, snoozeMinutes = :snoozeMinutes, vibrate = :vibrate WHERE seriesId IS NULL")
    suspend fun updateStandaloneAlarmDefaults(soundUri: String?, rampSeconds: Int, snoozeMinutes: Int, vibrate: Boolean)

    // Series children are what actually ring, so a series-wide apply must
    // hit them too, not just the alarm_series definition rows (#49).
    @Query("UPDATE alarms SET soundUri = :soundUri, volumeRampSeconds = :rampSeconds, snoozeMinutes = :snoozeMinutes, vibrate = :vibrate WHERE seriesId IS NOT NULL")
    suspend fun updateSeriesChildDefaults(soundUri: String?, rampSeconds: Int, snoozeMinutes: Int, vibrate: Boolean)

    @Query("DELETE FROM alarms")
    suspend fun deleteAllAlarms()

    @Insert
    suspend fun insert(alarm: Alarm): Long

    @Insert
    suspend fun insertAll(alarms: List<Alarm>): List<Long>

    @Update
    suspend fun update(alarm: Alarm)

    @Delete
    suspend fun delete(alarm: Alarm)

    @Query("DELETE FROM alarms WHERE seriesId = :seriesId")
    suspend fun deleteAlarmsForSeries(seriesId: Long)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TimerDao {
    @Query("SELECT * FROM timers ORDER BY durationSeconds, id")
    fun observeTimers(): Flow<List<TimerPreset>>

    @Query("SELECT * FROM timers ORDER BY durationSeconds, id")
    suspend fun getAllTimers(): List<TimerPreset>

    @Query("UPDATE timers SET soundUri = :soundUri, vibrate = :vibrate")
    suspend fun updateAllTimerDefaults(soundUri: String?, vibrate: Boolean)

    @Query("DELETE FROM timers")
    suspend fun deleteAllTimers()

    @Query("SELECT * FROM timers WHERE id = :id")
    suspend fun getTimer(id: Long): TimerPreset?

    @Query("SELECT * FROM timers WHERE runningUntilMillis IS NOT NULL")
    suspend fun getAllRunningTimers(): List<TimerPreset>

    @Insert
    suspend fun insert(timer: TimerPreset): Long

    @Update
    suspend fun update(timer: TimerPreset)

    @Delete
    suspend fun delete(timer: TimerPreset)
}

@Dao
interface ReminderDao {
    // Pending/active first by due time; done history sinks to the bottom,
    // most recently due first (list-screen order, done in one query).
    @Query("SELECT * FROM reminders ORDER BY CASE WHEN state = 2 THEN 1 ELSE 0 END, CASE WHEN state = 2 THEN -dueAtMillis ELSE dueAtMillis END")
    fun observeReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders ORDER BY dueAtMillis")
    suspend fun getAllReminders(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE state != 2")
    suspend fun getAllUndoneReminders(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getReminder(id: Long): Reminder?

    @Query("DELETE FROM reminders WHERE state = 2")
    suspend fun deleteDoneReminders()

    @Query("DELETE FROM reminders")
    suspend fun deleteAllReminders()

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Delete
    suspend fun delete(reminder: Reminder)
}

@Dao
interface AlarmSeriesDao {
    @Query("SELECT * FROM alarm_series WHERE pausedUntilMillis IS NOT NULL")
    suspend fun getAllPausedSeries(): List<AlarmSeries>

    @Query("SELECT * FROM alarm_series ORDER BY startHour, startMinute")
    suspend fun getAllSeries(): List<AlarmSeries>

    @Query("UPDATE alarm_series SET soundUri = :soundUri, volumeRampSeconds = :rampSeconds, snoozeMinutes = :snoozeMinutes, vibrate = :vibrate")
    suspend fun updateAllSeriesDefaults(soundUri: String?, rampSeconds: Int, snoozeMinutes: Int, vibrate: Boolean)

    @Query("DELETE FROM alarm_series")
    suspend fun deleteAllSeries()

    @Query("SELECT * FROM alarm_series ORDER BY startHour, startMinute")
    fun observeSeries(): Flow<List<AlarmSeries>>

    @Query("SELECT * FROM alarm_series WHERE id = :id")
    suspend fun getSeries(id: Long): AlarmSeries?

    @Query("SELECT * FROM alarm_series WHERE enabled = 1")
    suspend fun getAllEnabledSeries(): List<AlarmSeries>

    @Insert
    suspend fun insert(series: AlarmSeries): Long

    @Update
    suspend fun update(series: AlarmSeries)

    @Delete
    suspend fun delete(series: AlarmSeries)
}
