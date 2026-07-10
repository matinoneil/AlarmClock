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
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import no.hanss.alarmclock.alarm.AlarmScheduler
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.data.AlarmSeries
import no.hanss.alarmclock.ui.theme.ClockTextStyle
import no.hanss.alarmclock.viewmodel.AlarmViewModel

/** "in 32 min", "in 7 h 5 min", "in 2 d 6 h" -- rounded UP so an alarm never
 * reads "in 0 min" while still pending, and the value flips exactly as each
 * whole minute of lead time is used up. */
private fun ringsInLabel(deltaMillis: Long): String {
    val totalMinutes = ((deltaMillis + 59_999) / 60_000L).coerceAtLeast(1)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return "in " + when {
        days > 0 -> if (hours > 0) "$days d $hours h" else "$days d"
        hours > 0 -> if (minutes > 0) "$hours h $minutes min" else "$hours h"
        else -> "$minutes min"
    }
}

/** "Mon 13 Jul" for the pause-until subtitle. */
internal fun pausedUntilLabel(untilMillis: Long): String =
    java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
        .format(java.util.Date(untilMillis))

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
    val context = LocalContext.current
    val scheduler = remember(context) { AlarmScheduler(context.applicationContext) }

    // Minute-granularity clock driving the "rings in" labels, aligned to wall
    // clock minute boundaries so every card flips in step with the real time.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            nowMillis = System.currentTimeMillis()
        }
    }

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
                    // The series' true next ring is the earliest next trigger of
                    // its enabled children -- child-level snoozes and skip-next
                    // are honored because peekNextTriggerTime reads them.
                    val nextTrigger = if (series.enabled && !series.isPausedAt(nowMillis)) {
                        remember(series, state.seriesChildAlarms, nowMillis) {
                            state.seriesChildAlarms
                                .filter { it.seriesId == series.id && it.enabled }
                                .minOfOrNull { scheduler.peekNextTriggerTime(it) }
                        }
                    } else null
                    SeriesCard(
                        series = series,
                        nextTriggerMillis = nextTrigger,
                        nowMillis = nowMillis,
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
                    // peekNextTriggerTime honors snooze, skip-next, and weekday
                    // repeats, so the label always matches what will really ring.
                    val nextTrigger = if (alarm.enabled) {
                        remember(alarm, nowMillis) { scheduler.peekNextTriggerTime(alarm) }
                    } else null
                    AlarmCard(
                        alarm = alarm,
                        nextTriggerMillis = nextTrigger,
                        nowMillis = nowMillis,
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
    nextTriggerMillis: Long?,
    nowMillis: Long,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val subtitle = buildString {
        if (alarm.label.isNotBlank()) {
            append(alarm.label)
            append(" · ")
        }
        append(dayLabel(alarm.daysOfWeek))
        if (nextTriggerMillis != null) {
            append(" · ")
            // A snoozed alarm's next ring IS the snooze time; say so, since
            // "07:00 ... in 4 min" at 09:26 would otherwise look like a bug.
            val snoozed = alarm.snoozeUntilMillis?.let { it > nowMillis } == true
            if (snoozed) append("snoozed, ")
            append(ringsInLabel(nextTriggerMillis - nowMillis))
        }
    }
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
                subtitle,
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
    nextTriggerMillis: Long?,
    nowMillis: Long,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val times = series.expandTimes()
    val startLabel = String.format("%02d:%02d", series.startHour, series.startMinute)
    val (endH, endM) = times.last()
    val endLabel = String.format("%02d:%02d", endH, endM)
    // A paused series won't ring, so the card reads as off: switch unchecked,
    // dimmed, low container. Flipping the switch on = resume now.
    val paused = series.isPausedAt(nowMillis)
    val effectiveOn = series.enabled && !paused

    ListCard(enabled = effectiveOn, onClick = onClick) {
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (effectiveOn) 1f else 0.5f)
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
                if (paused) {
                    "Paused until ${pausedUntilLabel(series.pausedUntilMillis!!)} · " +
                        "$startLabel – $endLabel · ${dayLabel(series.daysOfWeek)}"
                } else if (nextTriggerMillis != null) {
                    // "rings in" replaces the alarm count while live -- the
                    // count is static trivia, the countdown is what catches a
                    // wrongly-set series at a glance (#27, extended here).
                    "$startLabel – $endLabel, every ${series.intervalMinutes} min · " +
                        "${ringsInLabel(nextTriggerMillis - nowMillis)} · ${dayLabel(series.daysOfWeek)}"
                } else {
                    // Disabled, or a one-shot series whose alarms all fired.
                    "$startLabel – $endLabel, every ${series.intervalMinutes} min " +
                        "(${times.size} alarms) · ${dayLabel(series.daysOfWeek)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = effectiveOn, onCheckedChange = onToggle)
    }
}
