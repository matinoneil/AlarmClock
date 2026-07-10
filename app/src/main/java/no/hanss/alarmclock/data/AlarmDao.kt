package no.hanss.alarmclock.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms WHERE seriesId IS NULL ORDER BY hour, minute")
    fun observeStandaloneAlarms(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE seriesId = :seriesId ORDER BY offsetMinutes")
    fun observeAlarmsForSeries(seriesId: Long): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE seriesId = :seriesId ORDER BY offsetMinutes")
    suspend fun getAlarmsForSeries(seriesId: Long): List<Alarm>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getAlarm(id: Long): Alarm?

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getAllEnabledAlarms(): List<Alarm>

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
interface AlarmSeriesDao {
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
