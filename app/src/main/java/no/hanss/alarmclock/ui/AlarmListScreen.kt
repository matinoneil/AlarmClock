package no.hanss.alarmclock.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.data.AlarmSeries
import no.hanss.alarmclock.ui.theme.ClockTextStyle
import no.hanss.alarmclock.viewmodel.AlarmViewModel

private fun dayLabel(days: Set<Int>): String {
    if (days.isEmpty()) return "One-time"
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    if (days.size == 7) return "Every day"
    if (days == setOf(1, 2, 3, 4, 5)) return "Weekdays"
    if (days == setOf(6, 7)) return "Weekends"
    return days.sorted().joinToString(", ") { names[it - 1] }
}

/**
 * The Alarms tab body. Lives inside HomeScreen's Scaffold (which owns the tab
 * row and the + FAB), so there's no Scaffold here -- see HomeScreen.kt.
 */
@Composable
fun AlarmListContent(
    viewModel: AlarmViewModel,
    onEditAlarm: (Alarm) -> Unit,
    onEditSeries: (AlarmSeries) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            if (state.series.isNotEmpty()) {
                item {
                    Text(
                        "Alarm series",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                    )
                }
                items(state.series, key = { "s${it.id}" }) { series ->
                    SeriesCard(
                        series = series,
                        onClick = { onEditSeries(series) },
                        onToggle = { viewModel.setSeriesEnabled(series, it) }
                    )
                }
                item { Spacer(modifier = Modifier.height(4.dp)) }
            }

            if (state.standaloneAlarms.isNotEmpty()) {
                item {
                    Text(
                        "Alarms",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                    )
                }
                items(state.standaloneAlarms, key = { it.id }) { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        onClick = { onEditAlarm(alarm) },
                        onToggle = { viewModel.setAlarmEnabled(alarm, it) }
                    )
                }
            }

            if (state.series.isEmpty() && state.standaloneAlarms.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.Alarm,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No alarms yet",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap + to add a single alarm\nor an alarm series",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
    }
}

// (ListCard is shared with the Timers tab -- see TimerListScreen.kt.)
@Composable
internal fun ListCard(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val container by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "cardContainer"
    )
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        color = container
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    ListCard(enabled = alarm.enabled, onClick = onClick) {
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (alarm.enabled) 1f else 0.5f)
        ) {
            Text(
                String.format("%02d:%02d", alarm.hour, alarm.minute),
                style = MaterialTheme.typography.displaySmall.merge(ClockTextStyle)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                if (alarm.label.isNotBlank()) "${alarm.label} · ${dayLabel(alarm.daysOfWeek)}"
                else dayLabel(alarm.daysOfWeek),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = alarm.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun SeriesCard(
    series: AlarmSeries,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val times = series.expandTimes()
    val startLabel = String.format("%02d:%02d", series.startHour, series.startMinute)
    val (endH, endM) = times.last()
    val endLabel = String.format("%02d:%02d", endH, endM)

    ListCard(enabled = series.enabled, onClick = onClick) {
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (series.enabled) 1f else 0.5f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(series.name, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "$startLabel – $endLabel, every ${series.intervalMinutes} min " +
                    "(${times.size} alarms) · ${dayLabel(series.daysOfWeek)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = series.enabled, onCheckedChange = onToggle)
    }
}
