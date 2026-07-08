package no.hanss.alarmclock.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
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

    private val audioManager: AudioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    // The user's actual alarm-volume slider setting before a ramp touched it, so it
    // can be restored once the alarm stops. Only set while a ramp is in progress.
    private var savedAlarmStreamVolume: Int? = null

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

        // The ramp is a hybrid of two mechanisms, because neither one alone is
        // reliable across devices:
        //  - Real Alarm-stream volume steps ARE guaranteed audible (this is what
        //    the volume buttons control), but the stream only has a handful of
        //    discrete hardware levels (as few as 5-7 on many phones), so stepping
        //    it alone makes for a coarse, chunky ramp.
        //  - MediaPlayer's own per-track gain (setVolume) can give a perfectly
        //    smooth 0-100% curve, but several Android OEMs largely ignore or clamp
        //    it specifically for USAGE_ALARM audio, as a safety net so alarms can't
        //    be made silent -- on those devices a gain-only ramp is inaudible.
        // So: the real stream level is the floor/ceiling for each hardware step
        // (guarantees audibility), and per-track gain smooths the climb *within*
        // each step. The gain's starting value at each new step is chosen so the
        // perceived loudness is continuous across the step boundary -- no dip.
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val targetVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            .coerceAtLeast(1) // if the user had it at 0, ramp to a minimum audible level instead of silence
        val totalHardwareSteps = if (rampSeconds > 0) targetVolume.coerceIn(1, maxVolume) else 0

        if (rampSeconds > 0) {
            savedAlarmStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        }

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(this@AlarmRingtoneService, soundUri)
            isLooping = true
            // Start silent (or near it) if we're about to ramp, so there's no flash
            // of full volume before the ramp coroutine below kicks in.
            if (rampSeconds > 0) setVolume(0f, 0f)
            prepare()
            start()
        }

        if (rampSeconds > 0) {
            val subStepsPerHardwareStep = 10
            val totalSubSteps = totalHardwareSteps * subStepsPerHardwareStep
            val subStepDelayMillis = (rampSeconds * 1000L / totalSubSteps).coerceAtLeast(20L)

            rampJob = serviceScope.launch {
                var currentHwIndex = 1
                for (sub in 1..totalSubSteps) {
                    delay(subStepDelayMillis)
                    val hwStep = ((sub - 1) / subStepsPerHardwareStep + 1).coerceAtMost(totalHardwareSteps)
                    if (hwStep != currentHwIndex) {
                        currentHwIndex = hwStep
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, hwStep, 0)
                    }
                    val positionInStep = (sub - 1) % subStepsPerHardwareStep + 1
                    // Continuity: at the start of step k this evaluates to (k-1)/k,
                    // matching step (k-1)'s end value of 1.0 scaled down by the new,
                    // louder hardware ceiling -- so the actual audible output doesn't
                    // jump or dip at the boundary. Rises to exactly 1.0 by step's end.
                    val gain = ((hwStep - 1) + positionInStep.toFloat() / subStepsPerHardwareStep) / hwStep
                    mediaPlayer?.setVolume(gain, gain)
                }
                mediaPlayer?.setVolume(1f, 1f)
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

        // Put the user's alarm volume back to whatever it was before a ramp touched it.
        savedAlarmStreamVolume?.let { original ->
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, original, 0)
        }
        savedAlarmStreamVolume = null
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
            val snoozeHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val snoozeMinute = cal.get(java.util.Calendar.MINUTE)

            if (alarm.daysOfWeek.isEmpty()) {
                // One-shot alarms are disabled in the database the instant they start
                // ringing (see AlarmReceiver) since they have no future occurrence of
                // their own. That means by the time we get here, `alarm.enabled` is
                // already false -- so just copying it and handing it to the scheduler
                // silently did nothing, since schedule() bails out for disabled alarms.
                // Nothing was persisted either, so the upcoming-alarm notification and
                // widget (both database-driven) never saw it. Snoozing a one-shot alarm
                // *is* its next occurrence, so persist the new time and re-enable it;
                // AlarmReceiver already disables it again once it actually fires.
                val snoozedAlarm = alarm.copy(hour = snoozeHour, minute = snoozeMinute, enabled = true)
                dao.update(snoozedAlarm)
                AlarmScheduler(applicationContext).schedule(snoozedAlarm)
            } else {
                // Repeating alarms keep their real weekly schedule intact in the
                // database (AlarmReceiver already re-armed it for the next matching
                // weekday), so a snooze shouldn't overwrite that. Just re-point this
                // alarm's existing AlarmManager entry (same id) at the snooze time for
                // this one firing; it returns to its normal weekly schedule after that.
                val snoozedAlarm = alarm.copy(
                    hour = snoozeHour, minute = snoozeMinute,
                    daysOfWeek = emptySet(), enabled = true
                )
                AlarmScheduler(applicationContext).schedule(snoozedAlarm)
            }

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
