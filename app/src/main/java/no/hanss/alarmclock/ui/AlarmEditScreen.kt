package no.hanss.alarmclock.ui

import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.viewmodel.AlarmViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarmId: Long,
    viewModel: AlarmViewModel,
    onDone: () -> Unit
) {
    var loaded by remember { mutableStateOf(alarmId == -1L) }
    var existing by remember { mutableStateOf<Alarm?>(null) }

    LaunchedEffect(alarmId) {
        if (alarmId != -1L) {
            existing = viewModel.getAlarm(alarmId)
        }
        loaded = true
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val context = LocalContext.current
    val timeState = rememberTimePickerState(
        initialHour = existing?.hour ?: 7,
        initialMinute = existing?.minute ?: 0,
        is24Hour = true
    )
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var vibrate by remember { mutableStateOf(existing?.vibrate ?: true) }
    var selectedDays by remember { mutableStateOf(existing?.daysOfWeek ?: emptySet()) }
    // New alarms start from the settings default; edits keep the alarm's own
    // choice (including "null = system default") untouched.
    var soundUri by remember {
        mutableStateOf(
            if (alarmId == -1L) no.hanss.alarmclock.data.SettingsStore(context).defaultAlarmSoundUri
            else existing?.soundUri
        )
    }
    var rampText by remember { mutableStateOf((existing?.volumeRampSeconds ?: 0).toString()) }
    var snoozeText by remember { mutableStateOf((existing?.snoozeMinutes ?: 10).toString()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val rampSeconds = rampText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val snoozeMinutes = snoozeText.toIntOrNull()?.coerceAtLeast(1) ?: 10

    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        soundUri = uri?.toString()
    }

    fun launchRingtonePicker() {
        val intent = android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            val existingUri = soundUri?.let { Uri.parse(it) }
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
        }
        ringtonePicker.launch(intent)
    }

    val soundLabel = remember(soundUri) {
        soundUri?.let { uriString ->
            runCatching {
                RingtoneManager.getRingtone(context, Uri.parse(uriString))?.getTitle(context)
            }.getOrNull()
        } ?: "Default alarm sound"
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete alarm?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAlarm(existing!!)
                    showDeleteConfirm = false
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (alarmId == -1L) "New alarm" else "Edit alarm") },
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
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = timeState)
            }

            EditSection(title = "Repeat") {
                DayOfWeekSelector(
                    selectedDays = selectedDays,
                    onToggle = { day ->
                        selectedDays = if (day in selectedDays) selectedDays - day
                        else selectedDays + day
                    }
                )
            }

            EditSection(title = "Details") {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { launchRingtonePicker() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Outlined.MusicNote, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(soundLabel ?: "Default alarm sound", maxLines = 1)
                }
            }

            EditSection(title = "Sound & snooze") {
                OutlinedTextField(
                    value = rampText,
                    onValueChange = { rampText = it.filter(Char::isDigit) },
                    label = { Text("Ramp to full volume") },
                    suffix = { Text("sec") },
                    supportingText = { Text("0 = full volume immediately") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = snoozeText,
                    onValueChange = { snoozeText = it.filter(Char::isDigit) },
                    label = { Text("Snooze length") },
                    suffix = { Text("min") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Vibrate", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = vibrate, onCheckedChange = { vibrate = it })
                }
            }

            Button(
                onClick = {
                    val alarm = (existing ?: Alarm(hour = 0, minute = 0)).copy(
                        hour = timeState.hour,
                        minute = timeState.minute,
                        label = label,
                        vibrate = vibrate,
                        daysOfWeek = selectedDays,
                        soundUri = soundUri,
                        volumeRampSeconds = rampSeconds,
                        snoozeMinutes = snoozeMinutes,
                        // Saving always enables: editing an alarm nearly always
                        // means intending to use it (see PROJECT_NOTES entry
                        // #18). Disabling is done from the list toggle.
                        enabled = true
                    )
                    viewModel.saveAlarm(alarm)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text("Save", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** Rounded tonal grouping container shared by the edit screens. */
@Composable
internal fun EditSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
