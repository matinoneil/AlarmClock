package no.hanss.alarmclock.ui

import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import no.hanss.alarmclock.data.AlarmSeries
import no.hanss.alarmclock.viewmodel.AlarmViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesEditScreen(
    seriesId: Long,
    viewModel: AlarmViewModel,
    onDone: () -> Unit
) {
    var loaded by remember { mutableStateOf(seriesId == -1L) }
    var existing by remember { mutableStateOf<AlarmSeries?>(null) }

    LaunchedEffect(seriesId) {
        if (seriesId != -1L) {
            existing = viewModel.getSeries(seriesId)
        }
        loaded = true
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val context = LocalContext.current
    val timeState = rememberTimePickerState(
        initialHour = existing?.startHour ?: 7,
        initialMinute = existing?.startMinute ?: 0,
        is24Hour = true
    )
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var nameEdited by remember { mutableStateOf(existing != null) }
    var intervalText by remember { mutableStateOf((existing?.intervalMinutes ?: 5).toString()) }
    var durationText by remember { mutableStateOf((existing?.durationMinutes ?: 45).toString()) }
    val defaults = remember { no.hanss.alarmclock.data.SettingsStore(context) }
    var vibrate by remember { mutableStateOf(existing?.vibrate ?: (if (seriesId == -1L) defaults.defaultAlarmVibrate else true)) }
    var selectedDays by remember { mutableStateOf(existing?.daysOfWeek ?: emptySet()) }
    var soundUri by remember {
        mutableStateOf(
            if (seriesId == -1L) no.hanss.alarmclock.data.SettingsStore(context).defaultAlarmSoundUri
            else existing?.soundUri
        )
    }
    var rampText by remember { mutableStateOf((existing?.volumeRampSeconds ?: (if (seriesId == -1L) defaults.defaultVolumeRampSeconds else 0)).toString()) }
    var snoozeText by remember { mutableStateOf((existing?.snoozeMinutes ?: (if (seriesId == -1L) defaults.defaultSnoozeMinutes else 10)).toString()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pausedUntil by remember { mutableStateOf(existing?.pausedUntilMillis) }

    val interval = intervalText.toIntOrNull()?.coerceAtLeast(1) ?: 5
    val duration = durationText.toIntOrNull()?.coerceAtLeast(0) ?: 45
    val rampSeconds = rampText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val snoozeMinutes = snoozeText.toIntOrNull()?.coerceAtLeast(1) ?: 10

    // Auto-fill the name from the start time until the user edits it manually.
    val autoName = String.format("%02d:%02d Alarms", timeState.hour, timeState.minute)
    if (!nameEdited) name = autoName

    val previewSeries = AlarmSeries(
        name = name,
        startHour = timeState.hour,
        startMinute = timeState.minute,
        intervalMinutes = interval,
        durationMinutes = duration
    )
    val previewTimes = previewSeries.expandTimes()

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
            title = { Text("Delete alarm series?") },
            text = { Text("\"${existing!!.name}\" and all ${previewTimes.size} of its alarms will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSeries(existing!!)
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
                title = { Text(if (seriesId == -1L) "New alarm series" else "Edit alarm series") },
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

            EditSection(title = "Series") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameEdited = true },
                    label = { Text("Series name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter(Char::isDigit) },
                        label = { Text("Every") },
                        suffix = { Text("min") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it.filter(Char::isDigit) },
                        label = { Text("For") },
                        suffix = { Text("min") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "${previewTimes.size} alarms will be created:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(40.dp)) {
                    items(previewTimes) { (h, m) ->
                        AssistChip(onClick = {}, label = { Text(String.format("%02d:%02d", h, m)) })
                    }
                }
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

            PauseEditSection(
                pausedUntil = pausedUntil,
                noun = "series",
                onChange = { pausedUntil = it }
            )

            EditSection(title = "Sound & snooze") {
                OutlinedButton(
                    onClick = { launchRingtonePicker() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Outlined.MusicNote, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(soundLabel ?: "Default alarm sound", maxLines = 1)
                }
                Spacer(modifier = Modifier.height(12.dp))
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
                    val series = (existing ?: AlarmSeries(
                        name = name, startHour = 0, startMinute = 0,
                        intervalMinutes = interval, durationMinutes = duration
                    )).copy(
                        name = name,
                        startHour = timeState.hour,
                        startMinute = timeState.minute,
                        intervalMinutes = interval,
                        durationMinutes = duration,
                        daysOfWeek = selectedDays,
                        vibrate = vibrate,
                        soundUri = soundUri,
                        volumeRampSeconds = rampSeconds,
                        snoozeMinutes = snoozeMinutes,
                        // A pause rides along with the save; the repository
                        // treats an already-past date as "not paused".
                        pausedUntilMillis = pausedUntil,
                        // Saving always enables, matching single-alarm behavior
                        // (PROJECT_NOTES entry #18). Disabling is done from the
                        // list toggle. A pause doesn't contradict this: the
                        // series is enabled-but-silenced and resumes on its own.
                        enabled = true
                    )
                    viewModel.saveSeries(series)
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
