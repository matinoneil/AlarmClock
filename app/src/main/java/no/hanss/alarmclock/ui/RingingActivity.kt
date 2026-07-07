package no.hanss.alarmclock.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.hanss.alarmclock.alarm.ACTION_DISMISS
import no.hanss.alarmclock.alarm.ACTION_SNOOZE
import no.hanss.alarmclock.alarm.AlarmRingtoneService
import no.hanss.alarmclock.alarm.EXTRA_ALARM_ID
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.data.AlarmDatabase
import no.hanss.alarmclock.ui.theme.AlarmClockTheme

class RingingActivity : ComponentActivity() {

    private var alarmId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)

        // Show over the lock screen and turn the screen on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContent {
            AlarmClockTheme {
                var alarm by remember { mutableStateOf<Alarm?>(null) }
                LaunchedEffect(alarmId) {
                    alarm = AlarmDatabase.getInstance(this@RingingActivity).alarmDao().getAlarm(alarmId)
                }
                RingingScreen(
                    label = alarm?.label?.takeIf { it.isNotBlank() } ?: "Alarm",
                    time = alarm?.let { String.format("%02d:%02d", it.hour, it.minute) } ?: "",
                    snoozeMinutes = alarm?.snoozeMinutes?.coerceAtLeast(1) ?: 10,
                    onDismiss = { sendServiceAction(ACTION_DISMISS) },
                    onSnooze = { sendServiceAction(ACTION_SNOOZE) }
                )
            }
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, AlarmRingtoneService::class.java).apply {
            this.action = action
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        startService(intent)
        finish()
    }
}

@Composable
fun RingingScreen(
    label: String,
    time: String,
    snoozeMinutes: Int,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primary) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Alarm,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = time,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = label,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Dismiss", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Filled.Snooze, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Snooze $snoozeMinutes min", fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}
