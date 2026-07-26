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
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
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
    var showPermissions by remember { mutableStateOf(false) }

    if (showPermissions) PermissionStatusDialog(onDismiss = { showPermissions = false })

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
    // Hoisted out of the "Reminders" EditSection so the restore handler below can
    // refresh them; nested state was unreachable from its scope (#90).
    // #93: four values now, mirroring the reminder editor's two toggles.
    // All FOUR must stay in this top-level block and in the post-restore
    // refresh below -- #90 is the entry about what happens when they don't.
    var reshowEnabled by remember { mutableStateOf(viewModel.settings.reminderReshowEnabled) }
    var reshowMinutes by remember { mutableIntStateOf(viewModel.settings.reminderReshowMinutes) }
    var nagEnabled by remember { mutableStateOf(viewModel.settings.reminderDefaultNagEnabled) }
    var renotifyMinutes by remember { mutableIntStateOf(viewModel.settings.reminderDefaultRenotifyMinutes) }
    var upcomingProtection by remember { mutableStateOf(viewModel.settings.upcomingSwipeProtection) }
    var confirmApplyAlarms by remember { mutableStateOf(false) }
    var confirmApplyTimers by remember { mutableStateOf(false) }
    var pendingRestoreJson by remember { mutableStateOf<String?>(null) }

    fun soundName(uri: String?): String = soundDisplayName(context, uri, "System default")

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
        // Only overwrite on an ACTUAL pick. Cancelling the picker returns no URI,
        // and null is the app's value for "use the system default" -- so the old
        // unconditional assignment made backing out indistinguishable from a
        // deliberate reset, silently wiping the chosen song (#83). Safe while
        // EXTRA_RINGTONE_SHOW_SILENT stays false; if Silent is ever offered, this
        // guard has to tell a cancel apart from a real silent pick.
        if (uri != null) {
            val value = uri.toString()
            when (pickerTarget) {
                "alarm" -> { defaultAlarmSound = value; viewModel.settings.defaultAlarmSoundUri = value }
                "series" -> { seriesSound = value; viewModel.settings.defaultSeriesSoundUri = value }
                else -> { defaultTimerSound = value; viewModel.settings.defaultTimerSoundUri = value }
            }
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
                            // EVERY settings-backed field must be re-read here. Only
                            // the two sound URIs were, so after a restore this screen
                            // still displayed the PRE-restore values -- which looked
                            // like the backup had lost them, and worse, touching any
                            // stale control wrote that stale value straight back over
                            // what had just been restored (#90). If a setting is added
                            // to SettingsStore, add it here too.
                            defaultAlarmSound = viewModel.settings.defaultAlarmSoundUri
                            defaultTimerSound = viewModel.settings.defaultTimerSoundUri
                            rampText = viewModel.settings.defaultVolumeRampSeconds.toString()
                            snoozeText = viewModel.settings.defaultSnoozeMinutes.toString()
                            defaultVibrate = viewModel.settings.defaultAlarmVibrate
                            seriesSound = viewModel.settings.defaultSeriesSoundUri
                            seriesRampText = viewModel.settings.defaultSeriesRampSeconds.toString()
                            seriesSnoozeText = viewModel.settings.defaultSeriesSnoozeMinutes.toString()
                            seriesVibrate = viewModel.settings.defaultSeriesVibrate
                            timerVibrate = viewModel.settings.defaultTimerVibrate
                            bedtimeEnabled = viewModel.settings.bedtimeEnabled
                            bedtimeHoursText = viewModel.settings.bedtimeHoursBefore.toString()
                            bedtimeMessage = viewModel.settings.bedtimeMessage
                            reshowEnabled = viewModel.settings.reminderReshowEnabled
                            reshowMinutes = viewModel.settings.reminderReshowMinutes
                            nagEnabled = viewModel.settings.reminderDefaultNagEnabled
                            renotifyMinutes = viewModel.settings.reminderDefaultRenotifyMinutes
                            upcomingProtection = viewModel.settings.upcomingSwipeProtection
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { launchRingtonePicker("series", seriesSound) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(soundName(seriesSound), maxLines = 1)
                    }
                    // Must persist as well as clear the local state, mirroring the
                    // picker result handler above (#82).
                    if (seriesSound != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = {
                            seriesSound = null
                            viewModel.settings.defaultSeriesSoundUri = null
                        }) {
                            Icon(
                                Icons.Outlined.Clear,
                                contentDescription = "Use the default alarm sound"
                            )
                        }
                    }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { launchRingtonePicker("alarm", defaultAlarmSound) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(soundName(defaultAlarmSound), maxLines = 1)
                    }
                    // Must persist as well as clear the local state, mirroring the
                    // picker result handler above (#82).
                    if (defaultAlarmSound != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = {
                            defaultAlarmSound = null
                            viewModel.settings.defaultAlarmSoundUri = null
                        }) {
                            Icon(
                                Icons.Outlined.Clear,
                                contentDescription = "Use the default alarm sound"
                            )
                        }
                    }
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

            // #94: its own section rather than a copy in each of the two alarm
            // sections above, because there is only ONE upcoming notification
            // and it already covers standalone and series alarms alike.
            EditSection(title = "Upcoming alarm notification") {
                Text(
                    "The silent notification that appears an hour before your next alarm, for single alarms and series alike.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bring it back if I swipe it away", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (upcomingProtection)
                                "A swipe won't clear it — use Dismiss next alarm instead. It leaves on its own once the alarm rings."
                            else "A swipe clears it until your next alarm comes up.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = upcomingProtection,
                        onCheckedChange = {
                            upcomingProtection = it
                            viewModel.settings.upcomingSwipeProtection = it
                            // A notification already on screen was built with
                            // the old setting baked in; re-post it now.
                            scope.launch { viewModel.refreshUpcoming() }
                        }
                    )
                }
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
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    singleLine = true,
                    enabled = bedtimeEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            EditSection(title = "Timers") {
                Text(
                    "Defaults for new timers. Existing ones keep their settings unless applied below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { launchRingtonePicker("timer", defaultTimerSound) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(soundName(defaultTimerSound), maxLines = 1)
                    }
                    // Must persist as well as clear the local state, mirroring the
                    // picker result handler above (#82).
                    if (defaultTimerSound != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = {
                            defaultTimerSound = null
                            viewModel.settings.defaultTimerSoundUri = null
                        }) {
                            Icon(
                                Icons.Outlined.Clear,
                                contentDescription = "Use the default alarm sound"
                            )
                        }
                    }
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

            EditSection(title = "Reminders") {
                val doneCount = uiForSettings.reminders.count { it.state == Reminder.STATE_DONE }
                // #93: deliberately the SAME two switches and the SAME two
                // dropdowns as the reminder editor, in the same order and
                // wording, so the two screens read as one idea instead of two
                // vocabularies. Only the framing line below differs, and each
                // label carries its own situation since there are no separate
                // section headings to do it here.
                Text(
                    "What a new reminder starts with. Every reminder can change these in its own editor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Remind me again if I don't press Done", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (nagEnabled) "It alerts again, as often as chosen below"
                            else "It alerts once and then stays quiet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = nagEnabled,
                        onCheckedChange = {
                            nagEnabled = it
                            viewModel.settings.reminderDefaultNagEnabled = it
                        }
                    )
                }
                if (nagEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    RenotifyDropdown(
                        renotifyMinutes = renotifyMinutes,
                        onSelect = {
                            renotifyMinutes = it
                            viewModel.settings.reminderDefaultRenotifyMinutes = it
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bring it back if I swipe it away", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                reshowEnabled -> "It comes back on its own, after the delay below"
                                nagEnabled -> "It stays gone until the next reminder above"
                                else -> "It stays gone, and the reminder counts as done"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = reshowEnabled,
                        onCheckedChange = {
                            reshowEnabled = it
                            viewModel.settings.reminderReshowEnabled = it
                        }
                    )
                }
                if (reshowEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ReshowDropdown(
                        reshowMinutes = reshowMinutes,
                        onSelect = {
                            reshowMinutes = it
                            viewModel.settings.reminderReshowMinutes = it
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { confirmClearHistory = true },
                    enabled = doneCount > 0,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (doneCount > 0) "Clear history ($doneCount)…" else "Clear history")
                }
            }

            EditSection(title = "Permissions") {
                OutlinedButton(
                    onClick = { showPermissions = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Check permissions\u2026") }
            }

            EditSection(title = "Backup") {
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
