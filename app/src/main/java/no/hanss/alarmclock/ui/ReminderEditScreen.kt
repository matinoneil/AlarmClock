package no.hanss.alarmclock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import no.hanss.alarmclock.data.Reminder
import no.hanss.alarmclock.viewmodel.AlarmViewModel
import java.util.Calendar
import java.util.TimeZone

/**
 * Create/edit a reminder. Mirrors the other editors (EditSections, top-bar
 * delete per entry #24, full-width Save). The first-occurrence date/time is
 * picked with the Material date and time picker dialogs; the repeat section
 * derives its parameters FROM the picked date where possible (monthly-by-date
 * takes the date's day, monthly-by-weekday offers "3rd Tue"/"last Tue"
 * computed from it) so the pattern can never contradict the first occurrence.
 * Saving always re-arms as PENDING; repository semantics (entry #50).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditScreen(
    reminderId: Long,
    viewModel: AlarmViewModel,
    onDone: () -> Unit
) {
    var loaded by remember { mutableStateOf(reminderId == -1L) }
    var existing by remember { mutableStateOf<Reminder?>(null) }

    LaunchedEffect(reminderId) {
        if (reminderId != -1L) {
            existing = viewModel.getReminder(reminderId)
        }
        loaded = true
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Default for a new reminder: tomorrow 09:00 -- always in the future, so
    // Save works out of the box and the user only has to type the text.
    val initialDueAt = existing?.dueAtMillis ?: Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    var text by remember { mutableStateOf(existing?.text ?: "") }
    var dueAtMillis by remember { mutableLongStateOf(initialDueAt) }
    var repeatType by remember { mutableIntStateOf(existing?.repeatType ?: Reminder.REPEAT_NONE) }
    var intervalText by remember { mutableStateOf((existing?.repeatInterval ?: 1).toString()) }
    var daysOfWeek by remember { mutableStateOf(existing?.repeatDaysOfWeek ?: emptySet()) }
    // MONTHLY_WEEKDAY only: 1..4 or LAST_WEEK_OF_MONTH; weekday itself always
    // derives from the picked date.
    var weekOfMonth by remember { mutableIntStateOf(existing?.repeatWeekOfMonth ?: 1) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val interval = intervalText.toIntOrNull()?.coerceIn(1, 999) ?: 1
    val isRepeating = repeatType != Reminder.REPEAT_NONE
    val dueInPast = dueAtMillis <= System.currentTimeMillis()
    // A repeating reminder with a past first occurrence rolls forward on save;
    // a past one-shot would fire the instant it's saved, so it's blocked.
    val saveBlockedByPast = dueInPast && !isRepeating
    val canSave = text.isNotBlank() && !saveBlockedByPast

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete reminder?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteReminder(existing!!)
                    showDeleteConfirm = false
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = localMidnightToUtc(dueAtMillis)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { utc ->
                        dueAtMillis = combineDateTime(utcToLocalMidnightMillis(utc), dueAtMillis)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTimePicker) {
        val cal = Calendar.getInstance().apply { timeInMillis = dueAtMillis }
        val timeState = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Reminder time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    dueAtMillis = Calendar.getInstance().apply {
                        timeInMillis = dueAtMillis
                        set(Calendar.HOUR_OF_DAY, timeState.hour)
                        set(Calendar.MINUTE, timeState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (reminderId == -1L) "New reminder" else "Edit reminder") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EditSection(title = "Reminder") {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("What should I remind you about?") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            EditSection(title = if (isRepeating) "First occurrence" else "When") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1.4f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(dateLabel(dueAtMillis), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(timeLabel(dueAtMillis), maxLines = 1)
                    }
                }
                if (dueInPast) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isRepeating) "This time has passed; the reminder will start at the next repeat occurrence"
                        else "Pick a time in the future",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRepeating) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }
            }

            EditSection(title = "Repeat") {
                RepeatTypeDropdown(
                    repeatType = repeatType,
                    onSelect = { selected ->
                        repeatType = selected
                        if (selected == Reminder.REPEAT_WEEKLY && daysOfWeek.isEmpty()) {
                            // Seed with the picked date's weekday so the
                            // pattern always includes the first occurrence.
                            daysOfWeek = setOf(isoWeekdayOf(dueAtMillis))
                        }
                    }
                )

                if (isRepeating) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = { intervalText = it.filter(Char::isDigit).take(3) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(88.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(intervalUnit(repeatType, interval), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                when (repeatType) {
                    Reminder.REPEAT_WEEKLY -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        DayOfWeekSelector(
                            selectedDays = daysOfWeek,
                            onToggle = { day ->
                                daysOfWeek = if (day in daysOfWeek) daysOfWeek - day else daysOfWeek + day
                            }
                        )
                        if (daysOfWeek.isEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "No days selected; the reminder repeats on ${shortDayName(isoWeekdayOf(dueAtMillis))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Reminder.REPEAT_MONTHLY_DATE -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "On day ${dayOfMonthOf(dueAtMillis)} of the month (from the date above)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Reminder.REPEAT_MONTHLY_WEEKDAY -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        WeekOfMonthDropdown(
                            dueAtMillis = dueAtMillis,
                            weekOfMonth = weekOfMonth,
                            onSelect = { weekOfMonth = it }
                        )
                    }

                    Reminder.REPEAT_YEARLY -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "On ${dateLabel(dueAtMillis)} each time (from the date above)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = {
                    // Align the first occurrence with the weekly pattern: if
                    // the picked date's weekday isn't selected, shift forward
                    // to the first selected weekday so dueAt sits on-pattern
                    // (the invariant the occurrence roll relies on).
                    var effectiveDueAt = dueAtMillis
                    if (repeatType == Reminder.REPEAT_WEEKLY && daysOfWeek.isNotEmpty()) {
                        effectiveDueAt = alignToWeekdays(dueAtMillis, daysOfWeek)
                    }
                    val reminder = (existing ?: Reminder(text = "", dueAtMillis = 0L)).copy(
                        text = text.trim(),
                        dueAtMillis = effectiveDueAt,
                        repeatType = repeatType,
                        repeatInterval = interval,
                        repeatDaysOfWeek = if (repeatType == Reminder.REPEAT_WEEKLY) {
                            daysOfWeek.ifEmpty { setOf(isoWeekdayOf(effectiveDueAt)) }
                        } else emptySet(),
                        repeatDayOfMonth = if (repeatType == Reminder.REPEAT_MONTHLY_DATE) {
                            dayOfMonthOf(effectiveDueAt)
                        } else 0,
                        repeatWeekday = if (repeatType == Reminder.REPEAT_MONTHLY_WEEKDAY) {
                            isoWeekdayOf(effectiveDueAt)
                        } else 0,
                        repeatWeekOfMonth = if (repeatType == Reminder.REPEAT_MONTHLY_WEEKDAY) {
                            weekOfMonth
                        } else 0
                    )
                    viewModel.saveReminder(reminder)
                    onDone()
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Save", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RepeatTypeDropdown(repeatType: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        Reminder.REPEAT_NONE to "Doesn't repeat",
        Reminder.REPEAT_DAILY to "Daily",
        Reminder.REPEAT_WEEKLY to "Weekly, on chosen days",
        Reminder.REPEAT_MONTHLY_DATE to "Monthly, on a date",
        Reminder.REPEAT_MONTHLY_WEEKDAY to "Monthly, on a weekday",
        Reminder.REPEAT_YEARLY to "Yearly"
    )
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(labels[repeatType] ?: "Doesn't repeat", maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            labels.forEach { (type, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { expanded = false; onSelect(type) }
                )
            }
        }
    }
}

/**
 * "1st Tue of the month" ... "4th Tue" / "Last Tue" -- the weekday comes from
 * the picked first-occurrence date; this only chooses WHICH one of the month.
 * A 5th-weekday pick (e.g. the 29th falls on the 5th Tuesday) is offered only
 * as "Last", since a literal 5th doesn't exist every month.
 */
@Composable
private fun WeekOfMonthDropdown(dueAtMillis: Long, weekOfMonth: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val day = shortDayName(isoWeekdayOf(dueAtMillis))
    val nth = ((dayOfMonthOf(dueAtMillis) - 1) / 7) + 1
    val options = buildList {
        if (nth <= 4) add(nth to "${ordinal(nth)} $day of the month")
        add(Reminder.LAST_WEEK_OF_MONTH to "Last $day of the month")
    }
    // The stored value may not match the picked date after a date change;
    // snap to the first offered option (as an effect, not a write mid-
    // composition) so the label and the saved value can't disagree.
    LaunchedEffect(dueAtMillis, weekOfMonth) {
        if (options.none { it.first == weekOfMonth }) onSelect(options.first().first)
    }
    val currentLabel = (options.firstOrNull { it.first == weekOfMonth } ?: options.first()).second
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(currentLabel, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { expanded = false; onSelect(value) }
                )
            }
        }
    }
}

private fun ordinal(n: Int): String = when (n) {
    1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th"
}

private fun intervalUnit(repeatType: Int, n: Int): String = when (repeatType) {
    Reminder.REPEAT_DAILY -> if (n == 1) "day" else "days"
    Reminder.REPEAT_WEEKLY -> if (n == 1) "week" else "weeks"
    Reminder.REPEAT_MONTHLY_DATE, Reminder.REPEAT_MONTHLY_WEEKDAY -> if (n == 1) "month" else "months"
    Reminder.REPEAT_YEARLY -> if (n == 1) "year" else "years"
    else -> ""
}

private fun isoWeekdayOf(millis: Long): Int {
    val d = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
    return if (d == Calendar.SUNDAY) 7 else d - 1
}

private fun dayOfMonthOf(millis: Long): Int =
    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_MONTH)

private fun shortDayName(isoDay: Int): String =
    listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[(isoDay - 1).coerceIn(0, 6)]

private fun dateLabel(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${shortDayName(isoWeekdayOf(millis))} ${c.get(Calendar.DAY_OF_MONTH)} ${months[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
}

private fun timeLabel(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(java.util.Locale.getDefault(), "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

/** New local-midnight date + existing time-of-day -> combined local millis. */
private fun combineDateTime(localMidnight: Long, timeSource: Long): Long {
    val time = Calendar.getInstance().apply { timeInMillis = timeSource }
    return Calendar.getInstance().apply {
        timeInMillis = localMidnight
        set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY))
        set(Calendar.MINUTE, time.get(Calendar.MINUTE))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** DatePicker selections are UTC midnight; the classic conversion (see PauseEditSection). */
private fun utcToLocalMidnightMillis(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

/** And back: local millis -> UTC midnight of the same calendar date, for prefilling the picker. */
private fun localMidnightToUtc(localMillis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

/**
 * If [millis]'s weekday is in [days], keep it; otherwise the same time of day
 * on the NEXT selected weekday. Keeps dueAt on-pattern, which the repeat roll
 * assumes (see Reminder.nextOccurrenceOnce's weekly branch).
 */
private fun alignToWeekdays(millis: Long, days: Set<Int>): Long {
    val current = isoWeekdayOf(millis)
    if (current in days) return millis
    val sorted = days.sorted()
    val next = sorted.firstOrNull { it > current } ?: sorted.first()
    val shift = if (next > current) next - current else 7 - current + next
    return Calendar.getInstance().apply {
        timeInMillis = millis
        add(Calendar.DAY_OF_YEAR, shift)
    }.timeInMillis
}
