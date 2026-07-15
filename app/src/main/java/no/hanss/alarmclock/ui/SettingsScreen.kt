package no.hanss.alarmclock.ui

import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.hanss.alarmclock.data.Reminder
import no.hanss.alarmclock.viewmodel.AlarmViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings: default sounds for alarms and timers (applied when CREATING new
 * ones), one-tap apply-to-all for each, and JSON backup/restore of all
 * alarms, series, and timers via the system file picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AlarmViewModel,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val uiForSettings by viewModel.uiState.collectAsState()
    var confirmClearHistory by remember { mutableStateOf(false) }

    if (confirmClearHistory) {
        val doneCount = uiForSettings.reminders.count { it.state == Reminder.STATE_DONE }
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear history?") },
            text = { Text("This removes all $doneCount completed reminders. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearDoneReminders()
                    confirmClearHistory = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
            }
        )
    }

    // Alarm (standalone) defaults
    var defaultAlarmSound by remember { mutableStateOf(viewModel.settings.defaultAlarmSoundUri) }
    var rampText by remember { mutableStateOf(viewModel.settings.defaultVolumeRampSeconds.toString()) }
    var snoozeText by remember { mutableStateOf(viewModel.settings.defaultSnoozeMinutes.toString()) }
    var defaultVibrate by remember { mutableStateOf(viewModel.settings.defaultAlarmVibrate) }
    // Series defaults (separate set, #49)
    var seriesSound by remember { mutableStateOf(viewModel.settings.defaultSeriesSoundUri) }
    var seriesRampText by remember { mutableStateOf(viewModel.settings.defaultSeriesRampSeconds.toString()) }
    var seriesSnoozeText by remember { mutableStateOf(viewModel.settings.defaultSeriesSnoozeMinutes.toString()) }
    var seriesVibrate by remember { mutableStateOf(viewModel.settings.defaultSeriesVibrate) }
    // Timer defaults (sound + vibrate)
    var timerVibrate by remember { mutableStateOf(viewModel.settings.defaultTimerVibrate) }
    var confirmApplySeries by remember { mutableStateOf(false) }
    var bedtimeEnabled by remember { mutableStateOf(viewModel.settings.bedtimeEnabled) }
    var bedtimeHoursText by remember { mutableStateOf(viewModel.settings.bedtimeHoursBefore.toString()) }
    var bedtimeMessage by remember { mutableStateOf(viewModel.settings.bedtimeMessage) }
    var defaultTimerSound by remember { mutableStateOf(viewModel.settings.defaultTimerSoundUri) }
    var confirmApplyAlarms by remember { mutableStateOf(false) }
    var confirmApplyTimers by remember { mutableStateOf(false) }
    var pendingRestoreJson by remember { mutableStateOf<String?>(null) }

    fun soundName(uri: String?): String = uri?.let { u ->
        runCatching {
            RingtoneManager.getRingtone(context, Uri.parse(u))?.getTitle(context)
        }.getOrNull()
    } ?: "System default"

    // --- Ringtone pickers (same pattern as the editors) ---
    var pickerTarget by remember { mutableStateOf("alarm") }
    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        val value = uri?.toString()
        when (pickerTarget) {
            "alarm" -> { defaultAlarmSound = value; viewModel.settings.defaultAlarmSoundUri = value }
            "series" -> { seriesSound = value; viewModel.settings.defaultSeriesSoundUri = value }
            else -> { defaultTimerSound = value; viewModel.settings.defaultTimerSoundUri = value }
        }
    }

    fun launchRingtonePicker(target: String, current: String?) {
        pickerTarget = target
        val intent = android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            val existingUri = current?.let { Uri.parse(it) }
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
        }
        ringtonePicker.launch(intent)
    }

    // --- Backup / restore via SAF ---
    val backupCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = try {
                val json = viewModel.exportBackupJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Couldn't open the file for writing")
                }
                "Backup saved"
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            }
            snackbar.showSnackbar(message)
        }
    }

    val restoreOpener = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("Couldn't read the file")
                }
                // Parse-check BEFORE offering the destructive confirm: a bad
                // file must fail here, never after the wipe.
                viewModel.parseBackupOrThrow(json)
                pendingRestoreJson = json
            } catch (e: Exception) {
                snackbar.showSnackbar("That file isn't a valid backup: ${e.message}")
            }
        }
    }

    // --- Confirm dialogs ---
    if (confirmApplyAlarms) {
        val rampNow = rampText.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val snoozeNow = snoozeText.toIntOrNull()?.coerceAtLeast(1) ?: 10
        AlertDialog(
            onDismissRequest = { confirmApplyAlarms = false },
            title = { Text("Apply to all single alarms?") },
            text = {
                Text(
                    "Every single (non-series) alarm will use \u201c${soundName(defaultAlarmSound)}\u201d, " +
                        "a $rampNow s volume ramp, $snoozeNow min snooze, and vibration ${if (defaultVibrate) "on" else "off"}. " +
                        "This can't be undone per-alarm."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmApplyAlarms = false
                    scope.launch {
                        viewModel.applyDefaultsToAllStandaloneAlarms()
                        snackbar.showSnackbar("Defaults applied to all single alarms")
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { confirmApplyAlarms = false }) { Text("Cancel") } }
        )
    }

    if (confirmApplySeries) {
        val rampNow = seriesRampText.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val snoozeNow = seriesSnoozeText.toIntOrNull()?.coerceAtLeast(1) ?: 10
        AlertDialog(
            onDismissRequest = { confirmApplySeries = false },
            title = { Text("Apply to all alarm series?") },
            text = {
                Text(
                    "Every alarm series and its alarms will use \u201c${soundName(seriesSound)}\u201d, " +
                        "a $rampNow s volume ramp, $snoozeNow min snooze, and vibration ${if (seriesVibrate) "on" else "off"}. " +
                        "This can't be undone per-series."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmApplySeries = false
                    scope.launch {
                        viewModel.applyDefaultsToAllSeries()
                        snackbar.showSnackbar("Defaults applied to all series")
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { confirmApplySeries = false }) { Text("Cancel") } }
        )
    }

    if (confirmApplyTimers) {
        AlertDialog(
            onDismissRequest = { confirmApplyTimers = false },
            title = { Text("Apply to all timers?") },
            text = { Text("Every saved timer will use \u201c${soundName(defaultTimerSound)}\u201d and vibration ${if (timerVibrate) "on" else "off"}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmApplyTimers = false
                    scope.launch {
                        viewModel.applyDefaultsToAllTimers()
                        snackbar.showSnackbar("Defaults applied to all timers")
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { confirmApplyTimers = false }) { Text("Cancel") } }
        )
    }

    pendingRestoreJson?.let { json ->
        AlertDialog(
            onDismissRequest = { pendingRestoreJson = null },
            title = { Text("Restore backup?") },
            text = { Text("This REPLACES all current alarms, alarm series, and timers with the backup's contents. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestoreJson = null
                    scope.launch {
                        val message = try {
                            val (a, s, t) = viewModel.restoreBackupJson(json)
                            defaultAlarmSound = viewModel.settings.defaultAlarmSoundUri
                            defaultTimerSound = viewModel.settings.defaultTimerSoundUri
                            "Restored $a alarms, $s series, $t timers"
                        } catch (e: Exception) {
                            "Restore failed: ${e.message}"
                        }
                        snackbar.showSnackbar(message)
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestoreJson = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EditSection(title = "Alarm series") {
                Text(
                    "Defaults for new alarm series. Existing series keep their settings unless applied below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { launchRingtonePicker("series", seriesSound) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Outlined.MusicNote, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(soundName(seriesSound), maxLines = 1)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = seriesRampText,
                        onValueChange = {
                            seriesRampText = it.filter(Char::isDigit).take(4)
                            viewModel.settings.defaultSeriesRampSeconds = seriesRampText.toIntOrNull() ?: 0
                        },
                        label = { Text("Volume ramp (s)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = seriesSnoozeText,
                        onValueChange = {
                            seriesSnoozeText = it.filter(Char::isDigit).take(3)
                            viewModel.settings.defaultSeriesSnoozeMinutes = seriesSnoozeText.toIntOrNull() ?: 10
                        },
                        label = { Text("Snooze (min)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Vibrate", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = seriesVibrate, onCheckedChange = {
                        seriesVibrate = it
                        viewModel.settings.defaultSeriesVibrate = it
                    })
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmApplySeries = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Apply these to all alarm series") }
            }

            EditSection(title = "Single alarms") {
                Text(
                    "Defaults for new single alarms. Existing ones keep their settings unless applied below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { launchRingtonePicker("alarm", defaultAlarmSound) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Outlined.MusicNote, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(soundName(defaultAlarmSound), maxLines = 1)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = rampText,
                        onValueChange = {
                            rampText = it.filter(Char::isDigit).take(4)
                            viewModel.settings.defaultVolumeRampSeconds = rampText.toIntOrNull() ?: 0
                        },
                        label = { Text("Volume ramp (s)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = snoozeText,
                        onValueChange = {
                            snoozeText = it.filter(Char::isDigit).take(3)
                            viewModel.settings.defaultSnoozeMinutes = snoozeText.toIntOrNull() ?: 10
                        },
                        label = { Text("Snooze (min)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Vibrate", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = defaultVibrate, onCheckedChange = {
                        defaultVibrate = it
                        viewModel.settings.defaultAlarmVibrate = it
                    })
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmApplyAlarms = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Apply these to all single alarms") }
            }

            EditSection(title = "Timers") {
                Text(
                    "Defaults for new timers. Existing ones keep their settings unless applied below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { launchRingtonePicker("timer", defaultTimerSound) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Outlined.MusicNote, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(soundName(defaultTimerSound), maxLines = 1)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Vibrate", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = timerVibrate, onCheckedChange = {
                        timerVibrate = it
                        viewModel.settings.defaultTimerVibrate = it
                    })
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmApplyTimers = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Apply these to all timers") }
            }

            EditSection(title = "Bedtime reminder") {
                Text(
                    "A quiet notification before your next alarm, reminding you to go to bed in time for a full night's sleep.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Remind me", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = bedtimeEnabled, onCheckedChange = {
                        bedtimeEnabled = it
                        viewModel.settings.bedtimeEnabled = it
                        scope.launch { viewModel.refreshBedtime() }
                    })
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = bedtimeHoursText,
                    onValueChange = {
                        bedtimeHoursText = it.filter(Char::isDigit).take(2)
                        bedtimeHoursText.toIntOrNull()?.let { h ->
                            viewModel.settings.bedtimeHoursBefore = h
                            scope.launch { viewModel.refreshBedtime() }
                        }
                    },
                    label = { Text("Hours of sleep before the alarm") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = bedtimeEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = bedtimeMessage,
                    onValueChange = {
                        bedtimeMessage = it
                        viewModel.settings.bedtimeMessage = it
                        scope.launch { viewModel.refreshBedtime() }
                    },
                    label = { Text("Message (empty = default)") },
                    singleLine = true,
                    enabled = bedtimeEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            EditSection(title = "Reminders") {
                val doneCount = uiForSettings.reminders.count { it.state == Reminder.STATE_DONE }
                var reshowText by remember { mutableStateOf(viewModel.settings.reminderReshowMinutes.toString()) }
                OutlinedTextField(
                    value = reshowText,
                    onValueChange = {
                        reshowText = it.filter(Char::isDigit).take(4)
                        reshowText.toIntOrNull()?.let { v ->
                            if (v >= 1) viewModel.settings.reminderReshowMinutes = v
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    label = { Text("Bring back a swiped-away reminder after (minutes)") },
                    supportingText = { Text("A reminder notification you dismiss without marking done returns after this long. One still showing re-alerts once a day.") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Completed and removed reminders collect as faded history at the bottom of the Reminders tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { confirmClearHistory = true },
                    enabled = doneCount > 0,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (doneCount > 0) "Clear history ($doneCount)…" else "Clear history")
                }
            }

            EditSection(title = "Backup") {
                Text(
                    "Everything — alarms, series, timers, reminders, and these settings — in one file. Restoring replaces what's on the phone. Sounds may fall back to the system default if the file is restored on another device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            backupCreator.launch("AlarmClock-backup-$stamp.json")
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("Back up…") }
                    OutlinedButton(
                        onClick = { restoreOpener.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("Restore…") }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
