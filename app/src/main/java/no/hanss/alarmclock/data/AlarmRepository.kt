package no.hanss.alarmclock.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import no.hanss.alarmclock.alarm.AlarmScheduler
import no.hanss.alarmclock.alarm.TimerScheduler
import no.hanss.alarmclock.alarm.UpcomingAlarmManager
import no.hanss.alarmclock.widget.AlarmWidgetUpdater

class AlarmRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AlarmDatabase.getInstance(context)
    private val alarmDao = db.alarmDao()
    private val seriesDao = db.alarmSeriesDao()
    private val timerDao = db.timerDao()
    private val scheduler = AlarmScheduler(context)
    private val timerScheduler = TimerScheduler(context)
    private val upcomingAlarmManager = UpcomingAlarmManager(context)

    fun observeStandaloneAlarms(): Flow<List<Alarm>> = alarmDao.observeStandaloneAlarms()
    fun observeSeries(): Flow<List<AlarmSeries>> = seriesDao.observeSeries()
    fun observeTimers(): Flow<List<TimerPreset>> = timerDao.observeTimers()
    fun observeAlarmsForSeries(seriesId: Long): Flow<List<Alarm>> = alarmDao.observeAlarmsForSeries(seriesId)

    suspend fun getSeries(id: Long): AlarmSeries? = seriesDao.getSeries(id)
    suspend fun getAlarm(id: Long): Alarm? = alarmDao.getAlarm(id)

    private suspend fun notifyChanged() {
        upcomingAlarmManager.refresh()
        AlarmWidgetUpdater.updateAll(appContext)
    }

    // --- Standalone alarms ---

    suspend fun saveStandaloneAlarm(alarm: Alarm): Long {
        // An edit invalidates any in-flight snooze -- ringing at a snooze time
        // computed against the pre-edit schedule would be wrong. (skipOccurrenceMillis
        // is deliberately kept: it only matches if the next occurrence is unchanged.)
        val toSave = alarm.copy(snoozeUntilMillis = null)
        val id = if (toSave.id == 0L) alarmDao.insert(toSave) else {
            alarmDao.update(toSave); toSave.id
        }
        val saved = toSave.copy(id = id)
        scheduler.schedule(saved)
        notifyChanged()
        return id
    }

    suspend fun deleteAlarm(alarm: Alarm) {
        scheduler.cancel(alarm)
        alarmDao.delete(alarm)
        notifyChanged()
    }

    suspend fun setAlarmEnabled(alarm: Alarm, enabled: Boolean) {
        // Toggling is a reset: any pending snooze or skipped occurrence belongs to
        // the alarm's previous life and shouldn't survive an off/on cycle.
        val updated = alarm.copy(enabled = enabled, snoozeUntilMillis = null, skipOccurrenceMillis = null)
        alarmDao.update(updated)
        if (enabled) scheduler.schedule(updated) else scheduler.cancel(updated)
        notifyChanged()
    }

    // --- Alarm series ---

    /**
     * Creates or updates an alarm series, regenerating its child alarms to match the
     * current start time / interval / duration, and (re)scheduling all of them.
     */
    suspend fun saveSeries(series: AlarmSeries): Long {
        val seriesId = if (series.id == 0L) seriesDao.insert(series) else {
            seriesDao.update(series); series.id
        }
        val saved = series.copy(id = seriesId)

        // Cancel + remove previous child alarms, then regenerate from scratch. Simplest
        // way to keep things consistent when start/interval/duration change.
        val existing = alarmDao.getAlarmsForSeries(seriesId)
        existing.forEach { scheduler.cancel(it) }
        alarmDao.deleteAlarmsForSeries(seriesId)

        val newAlarms = saved.expandTimes().mapIndexed { index, (h, m, dayShift) ->
            Alarm(
                seriesId = seriesId,
                offsetMinutes = index * saved.intervalMinutes,
                hour = h,
                minute = m,
                label = saved.name,
                // Times that wrapped past midnight belong to the *following* day: a
                // Monday series starting 23:50 with a 00:05 member must ring in the
                // night to Tuesday, not Monday 00:05 (almost a day early). Shift each
                // ISO weekday by the wrap distance. One-shot series (empty set) are
                // already correct: "next future occurrence" naturally lands wrapped
                // times on the following day.
                daysOfWeek = saved.daysOfWeek.map { d -> ((d - 1 + dayShift) % 7) + 1 }.toSet(),
                enabled = saved.enabled,
                vibrate = saved.vibrate,
                soundUri = saved.soundUri,
                volumeRampSeconds = saved.volumeRampSeconds,
                snoozeMinutes = saved.snoozeMinutes
            )
        }
        val ids = alarmDao.insertAll(newAlarms)
        if (saved.enabled) {
            newAlarms.zip(ids).forEach { (a, id) -> scheduler.schedule(a.copy(id = id)) }
        }
        notifyChanged()
        return seriesId
    }

    suspend fun deleteSeries(series: AlarmSeries) {
        alarmDao.getAlarmsForSeries(series.id).forEach { scheduler.cancel(it) }
        seriesDao.delete(series) // cascades to child alarms via foreign key
        notifyChanged()
    }

    suspend fun setSeriesEnabled(series: AlarmSeries, enabled: Boolean) {
        val updated = series.copy(enabled = enabled)
        seriesDao.update(updated)
        val children = alarmDao.getAlarmsForSeries(series.id)
        children.forEach { child ->
            val updatedChild = child.copy(enabled = enabled, snoozeUntilMillis = null, skipOccurrenceMillis = null)
            alarmDao.update(updatedChild)
            if (enabled) scheduler.schedule(updatedChild) else scheduler.cancel(updatedChild)
        }
        notifyChanged()
    }

    // --- Timer presets ---

    suspend fun getTimer(id: Long): TimerPreset? = timerDao.getTimer(id)

    /**
     * Creates or updates a timer preset. Editing always resets the timer to idle
     * (mirroring how editing an alarm clears an in-flight snooze): a countdown
     * started against the pre-edit duration would ring at a now-meaningless time.
     * Deliberately does NOT auto-start the timer (divergence from the alarms'
     * save-enables rule #18): the toggle is what starts a countdown, and
     * auto-running on save would be surprising when setting up several presets.
     */
    suspend fun saveTimer(timer: TimerPreset): Long {
        if (timer.id != 0L) timerScheduler.cancel(timer.id)
        val toSave = timer.copy(runningUntilMillis = null)
        val id = if (toSave.id == 0L) timerDao.insert(toSave) else {
            timerDao.update(toSave); toSave.id
        }
        return id
    }

    suspend fun deleteTimer(timer: TimerPreset) {
        timerScheduler.cancel(timer.id)
        timerDao.delete(timer)
    }

    /** Starts (running = true) or stops the preset's countdown. */
    suspend fun setTimerRunning(timer: TimerPreset, running: Boolean) {
        if (running) {
            val until = System.currentTimeMillis() + timer.durationSeconds.coerceAtLeast(1) * 1000L
            val updated = timer.copy(runningUntilMillis = until)
            timerDao.update(updated)
            timerScheduler.schedule(updated)
        } else {
            timerScheduler.cancel(timer.id)
            timerDao.update(timer.copy(runningUntilMillis = null))
        }
    }

    fun canScheduleExactAlarms(): Boolean = scheduler.canScheduleExactAlarms()
}
