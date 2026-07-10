package no.hanss.alarmclock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import no.hanss.alarmclock.data.TimerPreset
import no.hanss.alarmclock.ui.theme.ClockTextStyle
import no.hanss.alarmclock.data.formatTimerDuration
import no.hanss.alarmclock.viewmodel.AlarmViewModel

/**
 * The Timers tab body: saved timer presets in the same card style as alarms.
 * The switch starts/stops the countdown (the timer-tab analogue of enabling an
 * alarm); a running card ticks its remaining time live. Lives inside
 * HomeScreen's Scaffold, same as AlarmListContent.
 */
@Composable
fun TimerListContent(
    viewModel: AlarmViewModel,
    onEditTimer: (TimerPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    // One shared clock for all running cards, ticking only while at least one
    // timer is actually running -- cheaper and simpler than a ticker per card.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anyRunning = state.timers.any { it.isRunning }
    LaunchedEffect(anyRunning) {
        while (anyRunning) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(state.timers, key = { "t${it.id}" }) { timer ->
            TimerCard(
                timer = timer,
                nowMillis = now,
                onClick = { onEditTimer(timer) },
                onToggle = { viewModel.setTimerRunning(timer, it) }
            )
        }

        if (state.timers.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.HourglassEmpty,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No timers yet",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Tap + to save a timer you can\nstart again and again",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerCard(
    timer: TimerPreset,
    nowMillis: Long,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val running = timer.isRunning
    // While running, the big figure counts down live; idle shows the preset's
    // full duration. When it reaches zero the receiver flips the row back to
    // idle in the DB, which flows straight back into this card.
    val bigText = if (running) {
        val remaining = (((timer.runningUntilMillis ?: 0L) - nowMillis) / 1000L).coerceAtLeast(0L)
        formatTimerDuration(remaining.toInt())
    } else {
        formatTimerDuration(timer.durationSeconds)
    }
    val subtitle = buildString {
        if (timer.label.isNotBlank()) {
            append(timer.label)
            append(" · ")
        }
        if (running) {
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = timer.runningUntilMillis ?: 0L
            }
            append(
                String.format(
                    "Rings at %02d:%02d",
                    cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE)
                )
            )
        } else {
            append("${formatTimerDuration(timer.durationSeconds)} timer")
        }
    }

    ListCard(enabled = running, onClick = onClick) {
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (running) 1f else 0.5f)
        ) {
            Text(
                bigText,
                style = MaterialTheme.typography.displaySmall.merge(ClockTextStyle)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = running, onCheckedChange = onToggle)
    }
}
