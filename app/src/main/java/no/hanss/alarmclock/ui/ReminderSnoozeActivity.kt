package no.hanss.alarmclock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import no.hanss.alarmclock.alarm.EXTRA_REMINDER_ID
import no.hanss.alarmclock.alarm.ReminderOps
import no.hanss.alarmclock.ui.theme.AlarmClockTheme
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The notification's Snooze button lands here: a translucent, dialog-themed
 * activity (Theme.AlarmClock.SnoozeDialog) that floats a small menu over
 * whatever app is in front -- Google Tasks style -- instead of yanking the
 * user into the alarm app. Options adapt to the time of day (today's slots
 * drop off as they pass and tomorrow's carry the load); "Pick date & time"
 * chains the Material date and time pickers as dialogs in the same floating
 * window. Picking anything (or dismissing) finishes immediately; the activity
 * is excluded from recents and holds no state worth restoring.
 */
class ReminderSnoozeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId == -1L) {
            finish()
            return
        }

        setContent {
            AlarmClockTheme {
                SnoozeDialogContent(
                    onPick = { untilMillis ->
                        lifecycleScope.launch {
                            ReminderOps.snoozeTo(applicationContext, reminderId, untilMillis)
                            finish()
                        }
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }
}

private data class SnoozeOption(val label: String, val untilMillis: Long)

/** "18:54" */
private fun hhmm(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(Locale.getDefault(), "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private fun todayAt(hour: Int): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, hour)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * The preset list, computed at open time. Today's 09/12/18 slots appear only
 * while they're still at least [MIN_LEAD_MILLIS] away ("snooze until one
 * minute from now" is a misfire waiting to happen); tomorrow's always show.
 */
private fun buildOptions(now: Long): List<SnoozeOption> {
    val dayMillis = 24L * 60 * 60 * 1000
    val options = mutableListOf(SnoozeOption("In 1 hour (${hhmm(now + 3_600_000L)})", now + 3_600_000L))
    // Five today slots (#67) so one is always within ~3 h; tomorrow keeps
    // three to hold the menu's length down.
    val todaySlots = listOf(
        9 to "This morning", 12 to "Midday", 15 to "This afternoon",
        18 to "This evening", 21 to "Tonight"
    )
    for ((hour, label) in todaySlots) {
        val at = todayAt(hour)
        if (at - now >= MIN_LEAD_MILLIS) options += SnoozeOption("$label (${"%02d:00".format(hour)})", at)
    }
    for ((hour, name) in listOf(9 to "morning", 12 to "afternoon", 18 to "evening")) {
        options += SnoozeOption("Tomorrow $name (${"%02d:00".format(hour)})", todayAt(hour) + dayMillis)
    }
    options += SnoozeOption("In 24 hours (${hhmm(now + dayMillis)})", now + dayMillis)
    return options
}

private const val MIN_LEAD_MILLIS = 10L * 60 * 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnoozeDialogContent(
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // presets -> date -> time, each its own dialog in this floating window.
    var step by remember { mutableStateOf("presets") }
    var pickedDateLocalMidnight by remember { mutableStateOf(0L) }
    val options = remember { buildOptions(System.currentTimeMillis()) }

    when (step) {
        "presets" -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Snooze until") },
            text = {
                Column {
                    options.forEach { option ->
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(option.untilMillis) }
                                .padding(vertical = 12.dp)
                        )
                    }
                    Text(
                        "Pick date & time",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { step = "date" }
                            .padding(vertical = 12.dp)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        )

        "date" -> {
            val dateState = rememberDatePickerState(
                selectableDates = object : SelectableDates {
                    // From today (local) onward; the time step below rejects a
                    // past time on today itself.
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                        val today = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }
                        return utcToLocalMidnight(utcTimeMillis) >= today.timeInMillis
                    }
                }
            )
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(onClick = {
                        dateState.selectedDateMillis?.let { utc ->
                            pickedDateLocalMidnight = utcToLocalMidnight(utc)
                            step = "time"
                        }
                    }) { Text("Next") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
            ) {
                DatePicker(state = dateState)
            }
        }

        "time" -> {
            val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Snooze until") },
                text = { TimePicker(state = timeState) },
                confirmButton = {
                    TextButton(onClick = {
                        val until = Calendar.getInstance().apply {
                            timeInMillis = pickedDateLocalMidnight
                            set(Calendar.HOUR_OF_DAY, timeState.hour)
                            set(Calendar.MINUTE, timeState.minute)
                        }.timeInMillis
                        // A moment already behind us can't be snoozed to;
                        // nudge a hair ahead so the fire is immediate-but-sane
                        // rather than armed in the past.
                        onPick(maxOf(until, System.currentTimeMillis() + 60_000L))
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
            )
        }
    }
}

/**
 * The DatePicker's selection is UTC-midnight millis; convert to LOCAL
 * midnight of the same calendar date (the classic pitfall, one copy of the
 * conversion in PauseEditSection and one here since this file must work
 * without the edit-screen machinery).
 */
private fun utcToLocalMidnight(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}
