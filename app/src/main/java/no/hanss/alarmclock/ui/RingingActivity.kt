package no.hanss.alarmclock.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
import no.hanss.alarmclock.ui.theme.ClockTextStyle

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
    val primary = MaterialTheme.colorScheme.primary
    // Gradient is forced dark regardless of what the (possibly dynamic) scheme
    // hands us -- in dark dynamic schemes `primary` is light and `onPrimary`
    // is dark, so relying on that pair here would give dark-on-light-on-dark
    // soup. Hue-tinted near-black + white content is readable in every scheme.
    val gradient = Brush.verticalGradient(
        colors = listOf(
            lerp(primary, Color.Black, 0.45f),
            lerp(primary, Color.Black, 0.78f)
        )
    )
    val onColor = Color.White

    // Gentle breathing pulse on the alarm icon while ringing.
    val pulse by rememberInfiniteTransition(label = "ringPulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringPulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Alarm,
                    contentDescription = null,
                    tint = onColor,
                    modifier = Modifier
                        .size(56.dp)
                        .scale(pulse)
                )
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = time,
                    style = ClockTextStyle,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    color = onColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = label,
                    fontSize = 22.sp,
                    color = onColor.copy(alpha = 0.85f)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    shape = RoundedCornerShape(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = onColor,
                        contentColor = lerp(primary, Color.Black, 0.78f)
                    )
                ) {
                    Text("Dismiss", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(14.dp))
                TextButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(
                        "Snooze $snoozeMinutes min",
                        fontSize = 17.sp,
                        color = onColor.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}
