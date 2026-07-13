package no.hanss.alarmclock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.TimeZone

/**
 * The pause-until-date editor section shared by SeriesEditScreen and
 * AlarmEditScreen (#29/#33/#44). One copy of the wording and, more
 * importantly, one copy of the UTC-to-local-midnight conversion: the
 * DatePicker's selection is UTC-midnight millis, while the pause must end at
 * LOCAL midnight of the chosen day so that day's alarms ring.
 *
 * [noun] is "series" or "alarm" for the explanatory text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseEditSection(
    pausedUntil: Long?,
    noun: String,
    onChange: (Long?) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = pausedUntil,
            selectableDates = object : SelectableDates {
                // Allow from tomorrow (local) onward: pausing "until today"
                // would be a no-op the save path nulls out anyway.
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val todayLocal = Calendar.getInstance()
                    val pickedUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        .apply { timeInMillis = utcTimeMillis }
                    val picked = (todayLocal.clone() as Calendar).apply {
                        set(
                            pickedUtc.get(Calendar.YEAR),
                            pickedUtc.get(Calendar.MONTH),
                            pickedUtc.get(Calendar.DAY_OF_MONTH),
                            0, 0
                        )
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    todayLocal.set(Calendar.HOUR_OF_DAY, 0)
                    todayLocal.set(Calendar.MINUTE, 0)
                    todayLocal.set(Calendar.SECOND, 0)
                    todayLocal.set(Calendar.MILLISECOND, 0)
                    return picked.timeInMillis > todayLocal.timeInMillis
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { utcMillis ->
                        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                            .apply { timeInMillis = utcMillis }
                        val local = Calendar.getInstance().apply {
                            clear()
                            set(
                                utc.get(Calendar.YEAR),
                                utc.get(Calendar.MONTH),
                                utc.get(Calendar.DAY_OF_MONTH),
                                0, 0, 0
                            )
                        }
                        onChange(local.timeInMillis.takeIf { it > System.currentTimeMillis() })
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        ) {
            // The title answers the ambiguity in-flow: the picked day is the
            // first day alarms RING, not the last silent day (#33).
            DatePicker(
                state = pickerState,
                title = {
                    Text(
                        "Pick the first day alarms ring again",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp)
                    )
                }
            )
        }
    }

    EditSection(title = "Pause") {
        val activePause = pausedUntil?.takeIf { it > System.currentTimeMillis() }
        Text(
            if (activePause != null)
                "Paused — alarms are silent and ring again ${pausedUntilLabel(activePause)}, automatically."
            else
                "Silence this $noun for a while. Pick the first day alarms should ring again — it resumes by itself that day.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { showPicker = true },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    if (activePause != null) "Rings again ${pausedUntilLabel(activePause)}"
                    else "Pause…",
                    maxLines = 1
                )
            }
            if (activePause != null) {
                OutlinedButton(
                    onClick = { onChange(null) },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Clear") }
            }
        }
    }
}
