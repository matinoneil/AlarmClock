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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.viewmodel.AlarmViewModel

private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

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
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
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
    var soundUri by remember { mutableStateOf(existing?.soundUri) }
    var rampText by remember { mutableStateOf((existing?.volumeRampSeconds ?: 0).toString()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val rampSeconds = rampText.toIntOrNull()?.coerceAtLeast(0) ?: 0

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
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            TimePicker(state = timeState)

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (optional)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Repeat", style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(androidx.compose.ui.Alignment.Start))
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(dayNames.size) { index ->
                    val dayNumber = index + 1 // Mon=1..Sun=7
                    FilterChip(
                        selected = dayNumber in selectedDays,
                        onClick = {
                            selectedDays = if (dayNumber in selectedDays) selectedDays - dayNumber
                            else selectedDays + dayNumber
                        },
                        label = { Text(dayNames[index]) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { launchRingtonePicker() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.MusicNote, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(soundLabel ?: "Default alarm sound")
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = rampText,
                onValueChange = { rampText = it.filter(Char::isDigit) },
                label = { Text("Ramp up to full volume over (sec, 0 = instant)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("Vibrate")
                Switch(checked = vibrate, onCheckedChange = { vibrate = it })
            }

            Spacer(modifier = Modifier.height(24.dp))

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
                        // Preserve whatever the enabled state already was when editing;
                        // only default to "on" for a brand-new alarm.
                        enabled = existing?.enabled ?: true
                    )
                    viewModel.saveAlarm(alarm)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Save")
            }
        }
    }
}
