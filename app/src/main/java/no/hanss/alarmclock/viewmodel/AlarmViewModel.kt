package no.hanss.alarmclock.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.data.AlarmSeries
import no.hanss.alarmclock.data.AlarmRepository
import no.hanss.alarmclock.data.BackupSerializer
import no.hanss.alarmclock.data.Reminder
import no.hanss.alarmclock.data.TimerPreset

data class AlarmListUiState(
    val standaloneAlarms: List<Alarm> = emptyList(),
    val series: List<AlarmSeries> = emptyList(),
    // Children of all series, for per-series "rings in" (a child's snooze or
    // skip-next can make the true next ring differ from the series definition).
    val seriesChildAlarms: List<Alarm> = emptyList(),
    val timers: List<TimerPreset> = emptyList(),
    val reminders: List<Reminder> = emptyList()
)

/**
 * The row a list should scroll to after an editor saves it (#108). Variants are
 * named *Row because `Alarm` and `Reminder` in this file are the Room entities.
 *
 * This has to live in the ViewModel rather than in the list composable: the
 * NavHost DISPOSES the "list" destination while an editor is open, so anything
 * remembered inside it is gone by the time the save completes.
 */
sealed interface ScrollTarget {
    val id: Long
    data class AlarmRow(override val id: Long) : ScrollTarget
    data class SeriesRow(override val id: Long) : ScrollTarget
    data class ReminderRow(override val id: Long) : ScrollTarget
}

class AlarmViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AlarmRepository(application)

    init {
        // Safety net: end any pause that expired while nothing was alive to
        // end it (see AlarmRepository.reconcileExpiredPauses).
        viewModelScope.launch { repository.reconcileExpiredPauses() }
    }

    val uiState = combine(
        repository.observeStandaloneAlarms(),
        repository.observeSeries(),
        repository.observeSeriesChildAlarms(),
        repository.observeTimers(),
        repository.observeReminders()
    ) { alarms, series, children, timers, reminders ->
        AlarmListUiState(alarms, series, children, timers, reminders)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlarmListUiState())

    // #108: set by the save methods, read by the list screens, cleared once one
    // of them has scrolled. Null means "no pending scroll".
    private val _scrollTarget = MutableStateFlow<ScrollTarget?>(null)
    val scrollTarget: StateFlow<ScrollTarget?> = _scrollTarget.asStateFlow()

    fun consumeScrollTarget() {
        _scrollTarget.value = null
    }

    fun canScheduleExactAlarms(): Boolean = repository.canScheduleExactAlarms()

    suspend fun getAlarm(id: Long): Alarm? = repository.getAlarm(id)
    suspend fun getSeries(id: Long): AlarmSeries? = repository.getSeries(id)

    // The repository has always returned the row id -- correct for an insert and
    // for an update alike -- and it used to be discarded. #108 keeps it so the
    // list can scroll to what was just saved.
    fun saveAlarm(alarm: Alarm) = viewModelScope.launch {
        _scrollTarget.value = ScrollTarget.AlarmRow(repository.saveStandaloneAlarm(alarm))
    }

    fun deleteAlarm(alarm: Alarm) = viewModelScope.launch {
        repository.deleteAlarm(alarm)
    }

    fun setAlarmEnabled(alarm: Alarm, enabled: Boolean) = viewModelScope.launch {
        repository.setAlarmEnabled(alarm, enabled)
    }

    fun saveSeries(series: AlarmSeries) = viewModelScope.launch {
        _scrollTarget.value = ScrollTarget.SeriesRow(repository.saveSeries(series))
    }

    fun deleteSeries(series: AlarmSeries) = viewModelScope.launch {
        repository.deleteSeries(series)
    }

    fun setSeriesEnabled(series: AlarmSeries, enabled: Boolean) = viewModelScope.launch {
        repository.setSeriesEnabled(series, enabled)
    }

    suspend fun getTimer(id: Long): TimerPreset? = repository.getTimer(id)

    fun saveTimer(timer: TimerPreset) = viewModelScope.launch {
        repository.saveTimer(timer)
    }

    fun deleteTimer(timer: TimerPreset) = viewModelScope.launch {
        repository.deleteTimer(timer)
    }

    fun setTimerRunning(timer: TimerPreset, running: Boolean) = viewModelScope.launch {
        repository.setTimerRunning(timer, running)
    }

    // --- Reminders ---

    suspend fun getReminder(id: Long): Reminder? = repository.getReminder(id)

    fun saveReminder(reminder: Reminder) = viewModelScope.launch {
        _scrollTarget.value = ScrollTarget.ReminderRow(repository.saveReminder(reminder))
    }

    fun deleteReminder(reminder: Reminder) = viewModelScope.launch {
        repository.deleteReminder(reminder)
    }

    fun markReminderDone(reminder: Reminder) = viewModelScope.launch {
        repository.markReminderDone(reminder.id)
    }

    fun completeReminder(reminder: Reminder) = viewModelScope.launch {
        repository.completeReminder(reminder.id)
    }

    fun clearDoneReminders() = viewModelScope.launch {
        repository.clearDoneReminders()
    }

    // --- Settings / backup ---

    val settings get() = repository.settings

    suspend fun applyDefaultsToAllStandaloneAlarms() = repository.applyDefaultsToAllStandaloneAlarms()

    suspend fun applyDefaultsToAllSeries() = repository.applyDefaultsToAllSeries()

    suspend fun refreshBedtime() = repository.refreshBedtime()

    suspend fun refreshUpcoming() = repository.refreshUpcoming()

    suspend fun refreshRunningTimers() = repository.refreshRunningTimers()

    suspend fun applyDefaultsToAllTimers() = repository.applyDefaultsToAllTimers()

    suspend fun exportBackupJson(): String = repository.exportBackupJson()

    /** Validation only -- throws on malformed input, touches nothing. */
    fun parseBackupOrThrow(json: String) { BackupSerializer.fromJson(json) }

    suspend fun restoreBackupJson(json: String): Triple<Int, Int, Int> =
        repository.restoreBackupJson(json)
}
