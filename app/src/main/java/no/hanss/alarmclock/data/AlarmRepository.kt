package no.hanss.alarmclock.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import no.hanss.alarmclock.alarm.AlarmScheduler
import no.hanss.alarmclock.alarm.BedtimeNotificationManager
import no.hanss.alarmclock.alarm.ReminderNotificationManager
import no.hanss.alarmclock.alarm.ReminderOps
import no.hanss.alarmclock.alarm.ReminderScheduler
import no.hanss.alarmclock.alarm.SeriesUnpauseOps
import no.hanss.alarmclock.alarm.SeriesUnpauseScheduler
import no.hanss.alarmclock.alarm.TimerNotificationManager
import no.hanss.alarmclock.alarm.TimerScheduler
import no.hanss.alarmclock.alarm.UpcomingAlarmManager
import no.hanss.alarmclock.widget.AlarmWidgetUpdater

class AlarmRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AlarmDatabase.getInstance(context)
    private val alarmDao = db.alarmDao()
    private val seriesDao = db.alarmSeriesDao()
    private val timerDao = db.timerDao()
    private val reminderDao = db.reminderDao()
    private val scheduler = AlarmScheduler(context)
    private val reminderScheduler = ReminderScheduler(context)
    private val reminderNotifications = ReminderNotificationManager(context)
    private val timerScheduler = TimerScheduler(context)
    private val timerNotifications = TimerNotificationManager(context)
    private val unpauseScheduler = SeriesUnpauseScheduler(context)
    val settings = SettingsStore(context)
    private val upcomingAlarmManager = UpcomingAlarmManager(context)

    fun observeStandaloneAlarms(): Flow<List<Alarm>> = alarmDao.observeStandaloneAlarms()
    fun observeSeries(): Flow<List<AlarmSeries>> = seriesDao.observeSeries()
    fun observeSeriesChildAlarms(): Flow<List<Alarm>> = alarmDao.observeSeriesChildAlarms()
    fun observeTimers(): Flow<List<TimerPreset>> = timerDao.observeTimers()
    fun observeReminders(): Flow<List<Reminder>> = reminderDao.observeReminders()
    fun observeAlarmsForSeries(seriesId: Long): Flow<List<Alarm>> = alarmDao.observeAlarmsForSeries(seriesId)

    suspend fun getSeries(id: Long): AlarmSeries? = seriesDao.getSeries(id)
    suspend fun getAlarm(id: Long): Alarm? = alarmDao.getAlarm(id)

    private val bedtimeManager = BedtimeNotificationManager(appContext)

    private suspend fun notifyChanged() {
        upcomingAlarmManager.refresh()
        bedtimeManager.refresh()
        AlarmWidgetUpdater.updateAll(appContext)
    }

    /** For the settings screen after toggling/adjusting the bedtime reminder. */
    suspend fun refreshBedtime() = bedtimeManager.refresh()

    // --- Standalone alarms ---

    suspend fun saveStandaloneAlarm(alarm: Alarm): Long {
        // An edit invalidates any in-flight snooze -- ringing at a snooze time
        // computed against the pre-edit schedule would be wrong. (skipOccurrenceMillis
        // is deliberately kept: it only matches if the next occurrence is unchanged.)
        // A pause date already behind us means "not paused" (same rule as series).
        val stalePause = alarm.pausedUntilMillis?.let { it <= System.currentTimeMillis() } == true
        val toSave = alarm.copy(
            snoozeUntilMillis = null,
            pausedUntilMillis = if (stalePause) null else alarm.pausedUntilMillis
        )
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
        // The switch also clears a pause, same rule as series (#33): ON while
        // paused = resume now; OFF makes the pause moot.
        val updated = alarm.copy(enabled = enabled, snoozeUntilMillis = null, skipOccurrenceMillis = null, pausedUntilMillis = null)
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
        // A pause date that's already behind us means "not paused": store null
        // rather than a stale timestamp every reader must re-interpret.
        val effective = if (series.pausedUntilMillis?.let { it <= System.currentTimeMillis() } == true) {
            series.copy(pausedUntilMillis = null)
        } else series
        val seriesId = if (effective.id == 0L) seriesDao.insert(effective) else {
            seriesDao.update(effective); effective.id
        }
        val saved = effective.copy(id = seriesId)
        val paused = saved.isPausedAt(System.currentTimeMillis())

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
        // While paused, children exist but sit disabled until an unpause path
        // (AlarmManager entry, boot, or app-open reconcile) re-enables them.
        val childrenEnabled = saved.enabled && !paused
        val toInsert = if (childrenEnabled) newAlarms else newAlarms.map { it.copy(enabled = false) }
        val ids = alarmDao.insertAll(toInsert)
        if (childrenEnabled) {
            toInsert.zip(ids).forEach { (a, id) -> scheduler.schedule(a.copy(id = id)) }
        }
        if (paused) {
            unpauseScheduler.schedule(seriesId, saved.pausedUntilMillis!!)
        } else {
            unpauseScheduler.cancel(seriesId)
        }
        notifyChanged()
        return seriesId
    }

    suspend fun deleteSeries(series: AlarmSeries) {
        unpauseScheduler.cancel(series.id)
        alarmDao.getAlarmsForSeries(series.id).forEach { scheduler.cancel(it) }
        seriesDao.delete(series) // cascades to child alarms via foreign key
        notifyChanged()
    }

    suspend fun setSeriesEnabled(series: AlarmSeries, enabled: Boolean) {
        // The switch always clears a pause: toggling ON while paused means
        // "resume now"; toggling OFF makes the auto-resume moot -- a plainly
        // disabled series must never spring back to life on its own.
        val updated = series.copy(enabled = enabled, pausedUntilMillis = null)
        unpauseScheduler.cancel(series.id)
        seriesDao.update(updated)
        val children = alarmDao.getAlarmsForSeries(series.id)
        children.forEach { child ->
            val updatedChild = child.copy(enabled = enabled, snoozeUntilMillis = null, skipOccurrenceMillis = null)
            alarmDao.update(updatedChild)
            if (enabled) scheduler.schedule(updatedChild) else scheduler.cancel(updatedChild)
        }
        notifyChanged()
    }

    /**
     * Safety net for pauses that should have ended while nothing was around to
     * end them (force-stop killed the AlarmManager entry, clock jumped, ...).
     * Called on app open; BootReceiver runs the same logic for reboots.
     */
    suspend fun reconcileExpiredPauses() {
        val now = System.currentTimeMillis()
        seriesDao.getAllPausedSeries().forEach { series ->
            if ((series.pausedUntilMillis ?: 0L) <= now) {
                SeriesUnpauseOps.unpause(appContext, series.id)
            }
        }
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
        if (timer.id != 0L) {
            timerScheduler.cancel(timer.id)
            timerNotifications.cancel(timer.id)
        }
        val toSave = timer.copy(runningUntilMillis = null)
        val id = if (toSave.id == 0L) timerDao.insert(toSave) else {
            timerDao.update(toSave); toSave.id
        }
        return id
    }

    suspend fun deleteTimer(timer: TimerPreset) {
        timerScheduler.cancel(timer.id)
        timerNotifications.cancel(timer.id)
        timerDao.delete(timer)
    }

    /** Starts (running = true) or stops the preset's countdown. */
    suspend fun setTimerRunning(timer: TimerPreset, running: Boolean) {
        if (running) {
            val until = System.currentTimeMillis() + timer.durationSeconds.coerceAtLeast(1) * 1000L
            val updated = timer.copy(runningUntilMillis = until)
            timerDao.update(updated)
            timerScheduler.schedule(updated)
            timerNotifications.post(updated)
        } else {
            timerScheduler.cancel(timer.id)
            timerNotifications.cancel(timer.id)
            timerDao.update(timer.copy(runningUntilMillis = null))
        }
    }

    // --- Settings: default sounds & apply-to-all ---

    // Bulk applies (#45/#49): none of these fields affect scheduling, so
    // nothing is re-armed by any of them.

    /** Standalone alarms only (WHERE seriesId IS NULL). */
    suspend fun applyDefaultsToAllStandaloneAlarms() {
        val s = settings
        alarmDao.updateStandaloneAlarmDefaults(
            s.defaultAlarmSoundUri, s.defaultVolumeRampSeconds, s.defaultSnoozeMinutes, s.defaultAlarmVibrate
        )
    }

    /** Series definitions AND their child alarms -- the children are what ring. */
    suspend fun applyDefaultsToAllSeries() {
        val s = settings
        seriesDao.updateAllSeriesDefaults(
            s.defaultSeriesSoundUri, s.defaultSeriesRampSeconds, s.defaultSeriesSnoozeMinutes, s.defaultSeriesVibrate
        )
        alarmDao.updateSeriesChildDefaults(
            s.defaultSeriesSoundUri, s.defaultSeriesRampSeconds, s.defaultSeriesSnoozeMinutes, s.defaultSeriesVibrate
        )
    }

    suspend fun applyDefaultsToAllTimers() {
        timerDao.updateAllTimerDefaults(settings.defaultTimerSoundUri, settings.defaultTimerVibrate)
    }

    suspend fun countAlarmsAndSeries(): Pair<Int, Int> =
        alarmDao.getAllAlarms().size to seriesDao.getAllSeries().size

    suspend fun countTimers(): Int = timerDao.getAllTimers().size

    // --- Reminders ---

    suspend fun getReminder(id: Long): Reminder? = reminderDao.getReminder(id)

    /**
     * Save from the editor. Semantics mirror alarm saves: the reminder always
     * comes back PENDING (editing an active or done one re-arms it -- the
     * editor's Save means "remind me as configured"), and any in-flight
     * snooze is cleared, since a snooze computed against the pre-edit
     * schedule shouldn't survive the edit (#12). A repeating reminder whose
     * dueAt already passed rolls forward to the next occurrence; the editor
     * blocks a past one-shot, but a stale row is scheduled as-is and fires
     * immediately, which beats silently never firing.
     */
    suspend fun saveReminder(reminder: Reminder): Long {
        val now = System.currentTimeMillis()
        var toSave = reminder.copy(state = Reminder.STATE_PENDING, snoozedUntilMillis = null)
        if (toSave.isRepeating && toSave.dueAtMillis <= now) {
            nextOccurrenceAfter(toSave, now)?.let { toSave = toSave.copy(dueAtMillis = it) }
        }
        val id = if (toSave.id == 0L) reminderDao.insert(toSave)
        else {
            reminderDao.update(toSave); toSave.id
        }
        // An edited reminder may have been ACTIVE with its notification up.
        reminderNotifications.cancel(id)
        reminderScheduler.schedule(id, toSave.dueAtMillis)
        return id
    }

    // Delete = retire to history for live reminders, real erase for history
    // rows (#55); the semantics and the mutex both live in ReminderOps.
    suspend fun deleteReminder(reminder: Reminder) = ReminderOps.delete(appContext, reminder.id)

    suspend fun markReminderDone(reminderId: Long) = ReminderOps.markDone(appContext, reminderId)

    suspend fun clearDoneReminders() = reminderDao.deleteDoneReminders()

    // --- Backup / restore ---

    suspend fun exportBackupJson(): String = BackupSerializer.toJson(
        BackupSerializer.BackupData(
            standaloneAlarms = alarmDao.getAllStandaloneAlarms(),
            series = seriesDao.getAllSeries(),
            timers = timerDao.getAllTimers(),
            reminders = reminderDao.getAllReminders(),
            defaultAlarmSoundUri = settings.defaultAlarmSoundUri,
            defaultTimerSoundUri = settings.defaultTimerSoundUri,
            defaultVolumeRampSeconds = settings.defaultVolumeRampSeconds,
            defaultSnoozeMinutes = settings.defaultSnoozeMinutes,
            defaultAlarmVibrate = settings.defaultAlarmVibrate,
            bedtimeEnabled = settings.bedtimeEnabled,
            bedtimeHoursBefore = settings.bedtimeHoursBefore,
            bedtimeMessage = settings.bedtimeMessage,
            defaultSeriesSoundUri = settings.defaultSeriesSoundUri,
            defaultSeriesRampSeconds = settings.defaultSeriesRampSeconds,
            defaultSeriesSnoozeMinutes = settings.defaultSeriesSnoozeMinutes,
            defaultSeriesVibrate = settings.defaultSeriesVibrate,
            reminderReshowMinutes = settings.reminderReshowMinutes,
            reminderReshowEnabled = settings.reminderReshowEnabled,
            defaultTimerVibrate = settings.defaultTimerVibrate
        )
    )

    /**
     * REPLACES everything with the backup's contents (callers must confirm
     * with the user first). Every scheduled entry is cancelled before the
     * wipe so nothing orphaned can ring, then series go back in through
     * saveSeries -- children regenerate and pauses re-arm (or null out, if
     * the resume date passed while the backup sat on disk) through the one
     * normal path. Returns (alarms, series, timers) counts restored.
     */
    suspend fun restoreBackupJson(json: String): Triple<Int, Int, Int> {
        val data = BackupSerializer.fromJson(json) // throws on malformed input BEFORE any destruction

        // Disarm the world.
        alarmDao.getAllAlarms().forEach { scheduler.cancel(it) }
        timerDao.getAllTimers().forEach {
            timerScheduler.cancel(it.id)
            timerNotifications.cancel(it.id)
        }
        reminderDao.getAllReminders().forEach {
            reminderScheduler.cancel(it.id)
            reminderNotifications.cancel(it.id)
        }
        seriesDao.getAllSeries().forEach { unpauseScheduler.cancel(it.id) }

        alarmDao.deleteAllAlarms()
        seriesDao.deleteAllSeries()
        timerDao.deleteAllTimers()
        reminderDao.deleteAllReminders()

        data.series.forEach { saveSeries(it) }
        data.standaloneAlarms.forEach { alarm ->
            val id = alarmDao.insert(alarm)
            // Unlike saveStandaloneAlarm (editor path, always-enabled), restore
            // must honor the backed-up enabled flag.
            if (alarm.enabled) scheduler.schedule(alarm.copy(id = id))
        }
        data.timers.forEach { timerDao.insert(it) }
        data.reminders.forEach { reminder ->
            val id = reminderDao.insert(reminder)
            // Pending reminders re-arm through the boot-style refresh, which
            // also fires one that came due while the backup sat on disk --
            // late beats lost, same as the boot path.
            ReminderOps.refresh(appContext, id)
        }

        settings.defaultAlarmSoundUri = data.defaultAlarmSoundUri
        settings.defaultTimerSoundUri = data.defaultTimerSoundUri
        settings.defaultVolumeRampSeconds = data.defaultVolumeRampSeconds
        settings.defaultSnoozeMinutes = data.defaultSnoozeMinutes
        settings.defaultAlarmVibrate = data.defaultAlarmVibrate
        settings.bedtimeEnabled = data.bedtimeEnabled
        settings.bedtimeHoursBefore = data.bedtimeHoursBefore
        settings.bedtimeMessage = data.bedtimeMessage
        settings.defaultSeriesSoundUri = data.defaultSeriesSoundUri
        settings.defaultSeriesRampSeconds = data.defaultSeriesRampSeconds
        settings.defaultSeriesSnoozeMinutes = data.defaultSeriesSnoozeMinutes
        settings.defaultSeriesVibrate = data.defaultSeriesVibrate
        settings.reminderReshowMinutes = data.reminderReshowMinutes
        settings.reminderReshowEnabled = data.reminderReshowEnabled
        settings.defaultTimerVibrate = data.defaultTimerVibrate

        notifyChanged()
        return Triple(data.standaloneAlarms.size, data.series.size, data.timers.size)
    }

    fun canScheduleExactAlarms(): Boolean = scheduler.canScheduleExactAlarms()
}
