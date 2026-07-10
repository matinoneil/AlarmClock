package no.hanss.alarmclock.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.data.AlarmSeries
import no.hanss.alarmclock.data.AlarmRepository
import no.hanss.alarmclock.data.TimerPreset

data class AlarmListUiState(
    val standaloneAlarms: List<Alarm> = emptyList(),
    val series: List<AlarmSeries> = emptyList(),
    // Children of all series, for per-series "rings in" (a child's snooze or
    // skip-next can make the true next ring differ from the series definition).
    val seriesChildAlarms: List<Alarm> = emptyList(),
    val timers: List<TimerPreset> = emptyList()
)

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
        repository.observeTimers()
    ) { alarms, series, children, timers -> AlarmListUiState(alarms, series, children, timers) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlarmListUiState())

    fun canScheduleExactAlarms(): Boolean = repository.canScheduleExactAlarms()

    suspend fun getAlarm(id: Long): Alarm? = repository.getAlarm(id)
    suspend fun getSeries(id: Long): AlarmSeries? = repository.getSeries(id)

    fun saveAlarm(alarm: Alarm) = viewModelScope.launch {
        repository.saveStandaloneAlarm(alarm)
    }

    fun deleteAlarm(alarm: Alarm) = viewModelScope.launch {
        repository.deleteAlarm(alarm)
    }

    fun setAlarmEnabled(alarm: Alarm, enabled: Boolean) = viewModelScope.launch {
        repository.setAlarmEnabled(alarm, enabled)
    }

    fun saveSeries(series: AlarmSeries) = viewModelScope.launch {
        repository.saveSeries(series)
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
}
