package no.hanss.alarmclock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.hanss.alarmclock.data.nextOccurrenceAfter
import no.hanss.alarmclock.data.Reminder
import no.hanss.alarmclock.data.describeRepeat
import no.hanss.alarmclock.viewmodel.AlarmViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "in 32 min", "in 7 h 5 min", "in 2 d 6 h" -- rounded UP like the alarm cards' rings-in (#27). */
private fun dueInLabel(deltaMillis: Long): String {
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

/** "Today 09:00" / "Tue 21 Jul, 09:00" / "Tue 21 Jul 2027, 09:00" once it leaves this year. */
internal fun reminderWhenLabel(millis: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    val time = String.format(Locale.getDefault(), "%02d:%02d", then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today $time"
    val pattern = if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "EEE d MMM" else "EEE d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis)) + ", " + time
}

/**
 * The Reminders tab body: pending/active reminders up top (due-order), done
 * one-shots as a faded history section at the bottom with a Clear action.
 * Lives inside HomeScreen's Scaffold, same as the other tab contents.
 */
@Composable
fun ReminderListContent(
    viewModel: AlarmViewModel,
    onEditReminder: (Reminder) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    // Minute-granularity clock for the "in X" labels, aligned to wall clock
    // minute boundaries -- same shared-ticker pattern as the alarm list.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            nowMillis = System.currentTimeMillis()
        }
    }

    val (done, undone) = state.reminders.partition { it.state == Reminder.STATE_DONE }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(undone, key = { "r${it.id}" }) { reminder ->
            ReminderCard(
                reminder = reminder,
                nowMillis = nowMillis,
                onClick = { onEditReminder(reminder) },
                onDone = {
                    // A repeating reminder rolls forward and STAYS in the
                    // list (by design -- completing this week's must not
                    // kill the series), which without feedback reads as a
                    // no-op (entry #54). Announce where it went.
                    if (reminder.isRepeating) {
                        val next = nextOccurrenceAfter(reminder, System.currentTimeMillis())
                        if (next != null) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Done — next ${reminderWhenLabel(next)}")
                            }
                        }
                    }
                    viewModel.markReminderDone(reminder)
                }
            )
        }

        if (done.isNotEmpty()) {
            // History is pure content: faded cards, no heading (#51), no
            // actions -- Clear history lives in Settings (#56).
            items(done, key = { "r${it.id}" }) { reminder ->
                ReminderCard(
                    reminder = reminder,
                    nowMillis = nowMillis,
                    onClick = { onEditReminder(reminder) },
                    onDone = null
                )
            }
        }

        if (state.reminders.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.NotificationsNone,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No reminders yet",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Tap + to get a notification\nat a date and time",
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
private fun ReminderCard(
    reminder: Reminder,
    nowMillis: Long,
    onClick: () -> Unit,
    onDone: (() -> Unit)?
) {
    val isDone = reminder.state == Reminder.STATE_DONE
    val isActive = reminder.state == Reminder.STATE_ACTIVE
    // What's actually armed: a snooze overrides the pattern time (#12 split).
    val effectiveAt = reminder.effectiveDueAtMillis
    val snoozed = reminder.snoozedUntilMillis != null

    val subtitle = buildString {
        append(reminderWhenLabel(effectiveAt))
        if (!isDone && !isActive && effectiveAt > nowMillis) {
            append(" · ")
            if (snoozed) append("snoozed, ")
            append(dueInLabel(effectiveAt - nowMillis))
        }
        if (reminder.isRepeating) {
            append(" · ")
            append(describeRepeat(reminder))
        }
    }

    ListCard(enabled = !isDone, onClick = onClick) {
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (isDone) 0.5f else 1f)
        ) {
            // Same enabled-but-needs-attention treatment as a paused alarm's
            // tertiary line (#33): an active reminder is visibly different
            // from one still waiting.
            if (isActive) {
                Text(
                    "Reminding — waiting for Done",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                reminder.text,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onDone != null) {
            FilledTonalIconButton(onClick = onDone) {
                Icon(Icons.Filled.Check, contentDescription = "Mark done")
            }
        }
    }
}
