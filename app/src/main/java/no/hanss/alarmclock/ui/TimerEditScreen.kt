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
import no.hanss.alarmclock.data.TimerPreset
import no.hanss.alarmclock.viewmodel.AlarmViewModel

/**
 * Create/edit a saved timer preset. Mirrors AlarmEditScreen's structure
 * (EditSections, full-width Save) but takes a duration (h/m/s) instead of a
 * wall-clock time. Saving never auto-starts the countdown -- that's the list
 * toggle's job (deliberate divergence from the alarms' save-enables rule; see
 * PROJECT_NOTES entry #23).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerEditScreen(
    timerId: Long,
    viewModel: AlarmViewModel,
    onDone: () -> Unit
) {
    var loaded by remember { mutableStateOf(timerId == -1L) }
    var existing by remember { mutableStateOf<TimerPreset?>(null) }

    LaunchedEffect(timerId) {
        if (timerId != -1L) {
            existing = viewModel.getTimer(timerId)
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
    val initialTotal = existing?.durationSeconds ?: (5 * 60) // sensible default: 5 min
    var hoursText by remember { mutableStateOf((initialTotal / 3600).toString()) }
    var minutesText by remember { mutableStateOf(((initialTotal % 3600) / 60).toString()) }
    var secondsText by remember { mutableStateOf((initialTotal % 60).toString()) }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var vibrate by remember { mutableStateOf(existing?.vibrate ?: true) }
    var soundUri by remember { mutableStateOf(existing?.soundUri) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val hours = hoursText.toIntOrNull()?.coerceIn(0, 99) ?: 0
    val minutes = minutesText.toIntOrNull()?.coerceIn(0, 59) ?: 0
    val seconds = secondsText.toIntOrNull()?.coerceIn(0, 59) ?: 0
    val totalSeconds = hours * 3600 + minutes * 60 + seconds

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
            title = { Text("Delete timer?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTimer(existing!!)
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
                title = { Text(if (timerId == -1L) "New timer" else "Edit timer") },
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
            EditSection(title = "Duration") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { hoursText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Hours") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Min") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = secondsText,
                        onValueChange = { secondsText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Sec") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (totalSeconds == 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Duration must be longer than 0 seconds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
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
                    val timer = (existing ?: TimerPreset(durationSeconds = 0)).copy(
                        durationSeconds = totalSeconds,
                        label = label,
                        vibrate = vibrate,
                        soundUri = soundUri
                    )
                    viewModel.saveTimer(timer)
                    onDone()
                },
                enabled = totalSeconds > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text("Save", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
