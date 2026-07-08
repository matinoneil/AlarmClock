package no.hanss.alarmclock.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.data.AlarmDatabase
import no.hanss.alarmclock.ui.RingingActivity
import no.hanss.alarmclock.widget.AlarmWidgetUpdater

private const val CHANNEL_ID = "alarm_channel"
private const val NOTIFICATION_ID = 1001

const val ACTION_DISMISS = "no.hanss.alarmclock.action.DISMISS"
const val ACTION_SNOOZE = "no.hanss.alarmclock.action.SNOOZE"

class AlarmRingtoneService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var rampJob: Job? = null
    private var overlayWindow: OverlayAlarmWindow? = null
    private var currentAlarmId: Long = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: -1L

        when (intent?.action) {
            ACTION_DISMISS -> {
                handleDismiss()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                handleSnooze()
                return START_NOT_STICKY
            }
        }

        if (alarmId == -1L) {
            stopSelf()
            return START_NOT_STICKY
        }
        currentAlarmId = alarmId

        createNotificationChannel()
        // Post the full-screen-intent notification. This is the mechanism Android
        // exempts from background-activity-launch restrictions, so it reliably shows
        // the ringing UI full-screen when the device is locked or the screen is off.
        // When the screen is already on and the phone is actively in use, Android
        // downgrades this to a heads-up notification instead (same as it does for
        // incoming calls) -- the overlay window below is what covers that case.
        startForeground(NOTIFICATION_ID, buildNotification(alarmId))

        serviceScope.launch {
            val alarm = AlarmDatabase.getInstance(applicationContext).alarmDao().getAlarm(alarmId)
            startRinging(alarm)
        }

        return START_STICKY
    }

    private suspend fun startRinging(alarm: Alarm?) {
        val soundUri: Uri = alarm?.soundUri?.let { Uri.parse(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
        val rampSeconds = alarm?.volumeRampSeconds ?: 0

        // The ramp is done as a software gain curve on the MediaPlayer itself
        // (setVolume, 0f..1f), not by stepping the phone's Alarm stream volume.
        // The stream only has a handful of discrete hardware steps (as few as
        // 5-7 on many devices), so driving the ramp off that made the "seconds"
        // setting meaningless -- with few steps available it degenerated into a
        // single jump from silence to full volume partway through, the same for
        // any duration. A per-track gain curve gives a true continuous 0-100%
        // ramp over exactly the configured number of seconds, independent of
        // hardware step count, and it doesn't touch the user's system volume at all.
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(this@AlarmRingtoneService, soundUri)
            isLooping = true
            // Start silent if we're about to ramp, so there's no flash of full
            // volume before the ramp coroutine below kicks in.
            if (rampSeconds > 0) setVolume(0f, 0f)
            prepare()
            start()
        }

        if (rampSeconds > 0) {
            // 10 updates per second is smooth to the ear; independent of duration.
            val stepIntervalMillis = 100L
            val totalSteps = (rampSeconds * 1000L / stepIntervalMillis).toInt().coerceAtLeast(1)

            rampJob = serviceScope.launch {
                for (step in 1..totalSteps) {
                    delay(stepIntervalMillis)
                    val gain = (step.toFloat() / totalSteps).coerceAtMost(1f)
                    mediaPlayer?.setVolume(gain, gain)
                }
            }
        }

        val vibrateEnabled = alarm?.vibrate ?: true
        if (vibrateEnabled) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 800, 500)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }

        if (Settings.canDrawOverlays(applicationContext)) {
            val timeLabel = if (alarm != null) String.format("%02d:%02d", alarm.hour, alarm.minute) else ""
            val labelText = alarm?.label?.takeIf { it.isNotBlank() } ?: "Alarm"
            val snoozeLabel = "Snooze ${(alarm?.snoozeMinutes ?: 10).coerceAtLeast(1)} min"
            withContext(Dispatchers.Main) {
                overlayWindow = OverlayAlarmWindow(applicationContext).also {
                    it.show(timeLabel, labelText, snoozeLabel, onDismiss = { handleDismiss() }, onSnooze = { handleSnooze() })
                }
            }
        }
    }

    private fun handleDismiss() {
        stopRinging()
        stopSelf()
    }

    private fun handleSnooze() {
        stopRinging()
        snooze(currentAlarmId)
        stopSelf()
    }

    private fun stopRinging() {
        rampJob?.cancel()
        rampJob = null
        mediaPlayer?.apply { stop(); release() }
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
        overlayWindow?.dismiss()
        overlayWindow = null
    }

    private fun snooze(alarmId: Long) {
        if (alarmId == -1L) return
        serviceScope.launch {
            val dao = AlarmDatabase.getInstance(applicationContext).alarmDao()
            val alarm = dao.getAlarm(alarmId) ?: return@launch
            val snoozeMinutes = alarm.snoozeMinutes.coerceAtLeast(1)
            val cal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.MINUTE, snoozeMinutes)
            }
            // A snooze fires once, without touching the alarm's own repeat schedule,
            // by scheduling a one-shot copy at the same id-space via AlarmManager directly.
            val snoozedAlarm = alarm.copy(hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                minute = cal.get(java.util.Calendar.MINUTE), daysOfWeek = emptySet())
            AlarmScheduler(applicationContext).schedule(snoozedAlarm)
            UpcomingAlarmManager(applicationContext).refresh()
            AlarmWidgetUpdater.updateAll(applicationContext)
        }
    }

    private fun buildNotification(alarmId: Long): android.app.Notification {
        val fullScreenIntent = Intent(this, RingingActivity::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, alarmId.toInt(), fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, AlarmRingtoneService::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        val dismissPendingIntent = PendingIntent.getService(
            this, alarmId.toInt(), dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm")
            .setContentText("Tap to open")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(0, "Dismiss", dismissPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarm notifications"
                setSound(null, null) // sound is handled by our own MediaPlayer
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }
}
