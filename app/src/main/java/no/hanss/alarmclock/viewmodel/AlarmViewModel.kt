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

data class AlarmListUiState(
    val standaloneAlarms: List<Alarm> = emptyList(),
    val series: List<AlarmSeries> = emptyList()
)

class AlarmViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AlarmRepository(application)

    val uiState = combine(
        repository.observeStandaloneAlarms(),
        repository.observeSeries()
    ) { alarms, series -> AlarmListUiState(alarms, series) }
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
}
