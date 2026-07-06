package no.hanss.alarmclock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.data.AlarmSeries
import no.hanss.alarmclock.viewmodel.AlarmViewModel

private fun dayLabel(days: Set<Int>): String {
    if (days.isEmpty()) return "One-time"
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    if (days.size == 7) return "Every day"
    return days.sorted().joinToString(", ") { names[it - 1] }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    viewModel: AlarmViewModel,
    onAddAlarm: () -> Unit,
    onEditAlarm: (Alarm) -> Unit,
    onAddSeries: () -> Unit,
    onEditSeries: (AlarmSeries) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showAddMenu by remember { mutableStateOf(false) }
    var pendingDeleteAlarm by remember { mutableStateOf<Alarm?>(null) }
    var pendingDeleteSeries by remember { mutableStateOf<AlarmSeries?>(null) }

    pendingDeleteAlarm?.let { alarm ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAlarm = null },
            title = { Text("Delete alarm?") },
            text = { Text("The ${String.format("%02d:%02d", alarm.hour, alarm.minute)} alarm will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAlarm(alarm)
                    pendingDeleteAlarm = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAlarm = null }) { Text("Cancel") }
            }
        )
    }

    pendingDeleteSeries?.let { series ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSeries = null },
            title = { Text("Delete alarm series?") },
            text = { Text("\"${series.name}\" and all ${series.expandTimes().size} of its alarms will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSeries(series)
                    pendingDeleteSeries = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSeries = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Alarms") }) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { showAddMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Single alarm") },
                        onClick = { showAddMenu = false; onAddAlarm() }
                    )
                    DropdownMenuItem(
                        text = { Text("Alarm series") },
                        onClick = { showAddMenu = false; onAddSeries() }
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.series.isNotEmpty()) {
                item {
                    Text(
                        "Alarm series",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(state.series, key = { "s${it.id}" }) { series ->
                    SeriesCard(
                        series = series,
                        onClick = { onEditSeries(series) },
                        onToggle = { viewModel.setSeriesEnabled(series, it) },
                        onDelete = { pendingDeleteSeries = series }
                    )
                }
                item { Spacer(modifier = Modifier.height(4.dp)) }
            }

            if (state.standaloneAlarms.isNotEmpty()) {
                item {
                    Text(
                        "Alarms",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(state.standaloneAlarms, key = { it.id }) { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        onClick = { onEditAlarm(alarm) },
                        onToggle = { viewModel.setAlarmEnabled(alarm, it) },
                        onDelete = { pendingDeleteAlarm = alarm }
                    )
                }
            }

            if (state.series.isEmpty() && state.standaloneAlarms.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No alarms yet.\nTap + to add a single alarm or an alarm series.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Alarm, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    String.format("%02d:%02d", alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    if (alarm.label.isNotBlank()) "${alarm.label} · ${dayLabel(alarm.daysOfWeek)}"
                    else dayLabel(alarm.daysOfWeek),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun SeriesCard(
    series: AlarmSeries,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val times = series.expandTimes()
    val startLabel = String.format("%02d:%02d", series.startHour, series.startMinute)
    val (endH, endM) = times.last()
    val endLabel = String.format("%02d:%02d", endH, endM)

    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Repeat, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(series.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "$startLabel – $endLabel, every ${series.intervalMinutes} min " +
                        "(${times.size} alarms) · ${dayLabel(series.daysOfWeek)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(checked = series.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}
