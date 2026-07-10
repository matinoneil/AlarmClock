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
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.data.AlarmDatabase
import no.hanss.alarmclock.data.formatTimerDuration
import no.hanss.alarmclock.ui.RingingActivity
import no.hanss.alarmclock.widget.AlarmWidgetUpdater

private const val CHANNEL_ID = "alarm_channel"
private const val NOTIFICATION_ID = 1001
private const val TAG = "AlarmRingtoneService"

const val ACTION_DISMISS = "no.hanss.alarmclock.action.DISMISS"
const val ACTION_SNOOZE = "no.hanss.alarmclock.action.SNOOZE"

// Marker for "an alarm is ringing right now", so an interrupted ring (process
// killed under memory pressure, crash, or a reboot mid-ring) can be resumed
// instead of silently vanishing. Especially important for one-shot alarms,
// which are disabled in the DB the moment they fire (entry 0.2) -- without
// this, any interruption meant they'd never ring again at all.
const val RINGING_PREFS = "alarm_ringing_state"
const val KEY_RINGING_ID = "ringing_alarm_id"
const val KEY_RINGING_SINCE = "ringing_since"
// Whether the marked ring is a timer (KEY_RINGING_ID is then a timer id, not an
// alarm id) -- the recovery paths must re-fire with the matching intent extra.
const val KEY_RINGING_IS_TIMER = "ringing_is_timer"
// Don't resurrect a ring older than this -- blasting an alarm long after its
// moment (e.g. the phone was off for hours) is worse than staying quiet.
const val RING_RESUME_GRACE_MILLIS = 30 * 60 * 1000L

class AlarmRingtoneService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var rampJob: Job? = null
    private var overlayWindow: OverlayAlarmWindow? = null
    private var currentAlarmId: Long = -1L
    // True while the current ring came from a timer preset rather than an alarm.
    // Timers are dismiss-only: snooze semantics don't map onto a countdown, and a
    // stray ACTION_SNOOZE must not resurrect a bogus alarm from a timer snapshot.
    private var isTimerRing: Boolean = false
    // In-memory copy of the Alarm as it was when ringing started. Exists so snooze
    // can still work if the DB row vanishes mid-ring -- a series edit regenerates
    // (deletes + reinserts) every child row, and the user can also delete an alarm
    // from the list while it rings. See PROJECT_NOTES entry #21.
    private var ringingSnapshot: Alarm? = null

    private val audioManager: AudioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    // The user's actual alarm-volume slider setting before a ramp touched it, so it
    // can be restored once the alarm stops. Only set while a ramp is in progress.
    private var savedAlarmStreamVolume: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                handleDismiss()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                // A ringing timer has no snooze surface, but a stale snooze
                // PendingIntent could still deliver this action; treat it as a
                // dismiss rather than resurrecting a nonsense alarm.
                if (isTimerRing) {
                    handleDismiss()
                } else {
                    // Prefer the id from the intent: after a process death the in-memory
                    // currentAlarmId is gone, but the notification/activity intents carry it.
                    handleSnooze(intent.getLongExtra(EXTRA_ALARM_ID, currentAlarmId))
                }
                return START_NOT_STICKY
            }
        }

        val timerIdExtra = intent?.getLongExtra(EXTRA_TIMER_ID, -1L) ?: -1L
        var isTimer = timerIdExtra != -1L
        var alarmId = if (isTimer) timerIdExtra else intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: -1L

        // A null intent means START_STICKY restarted us after the process was killed
        // mid-ring. Resume the interrupted alarm from the persisted marker (if it's
        // recent) rather than going silent -- the person is very likely still asleep.
        if (alarmId == -1L && intent == null) {
            val prefs = getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE)
            val storedId = prefs.getLong(KEY_RINGING_ID, -1L)
            val age = System.currentTimeMillis() - prefs.getLong(KEY_RINGING_SINCE, 0L)
            if (storedId != -1L && age in 0..RING_RESUME_GRACE_MILLIS) {
                Log.w(TAG, "Resuming ring $storedId after unexpected service restart")
                alarmId = storedId
                isTimer = prefs.getBoolean(KEY_RINGING_IS_TIMER, false)
            }
        }

        if (alarmId == -1L) {
            stopSelf()
            return START_NOT_STICKY
        }
        currentAlarmId = alarmId
        isTimerRing = isTimer

        // Persist the marker before anything can fail. On the null-intent resume
        // path keep the original timestamp, so the grace window counts from the
        // first firing rather than resetting on every service resurrection; any
        // explicit start (a real firing) stamps it fresh -- otherwise a leftover
        // timestamp from an old interrupted ring of this same alarm would wrongly
        // age out a brand-new ring. commit() rather than apply(): this marker exists
        // precisely to survive an abrupt process kill, and apply()'s asynchronous
        // disk write can be lost in exactly that case. One tiny write per firing.
        val isResume = intent == null
        val prefs = getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE)
        if (!(isResume && prefs.getLong(KEY_RINGING_ID, -1L) == alarmId)) {
            prefs.edit()
                .putLong(KEY_RINGING_ID, alarmId)
                .putLong(KEY_RINGING_SINCE, System.currentTimeMillis())
                .putBoolean(KEY_RINGING_IS_TIMER, isTimer)
                .commit()
        }

        createNotificationChannel()
        // Post the full-screen-intent notification. This is the mechanism Android
        // exempts from background-activity-launch restrictions, so it reliably shows
        // the ringing UI full-screen when the device is locked or the screen is off.
        // When the screen is already on and the phone is actively in use, Android
        // downgrades this to a heads-up notification instead (same as it does for
        // incoming calls) -- the overlay window below is what covers that case.
        startForeground(NOTIFICATION_ID, buildNotification(alarmId, isTimer))

        serviceScope.launch {
            try {
                if (isTimer) {
                    // Ring a timer through the exact same pipeline as an alarm by
                    // building a transient (never persisted) Alarm from the preset.
                    // A missing row (deleted mid-run) still rings with defaults --
                    // the countdown was started expecting a ring.
                    val timer = AlarmDatabase.getInstance(applicationContext).timerDao().getTimer(alarmId)
                    val synthetic = Alarm(
                        id = -1L,
                        hour = 0,
                        minute = 0,
                        label = timer?.label?.takeIf { it.isNotBlank() } ?: "Timer",
                        vibrate = timer?.vibrate ?: true,
                        soundUri = timer?.soundUri,
                        volumeRampSeconds = 0
                    )
                    val display = timer?.let { formatTimerDuration(it.durationSeconds) } ?: ""
                    startRinging(synthetic, isTimer = true, overrideTimeLabel = display)
                } else {
                    val alarm = AlarmDatabase.getInstance(applicationContext).alarmDao().getAlarm(alarmId)
                    startRinging(alarm)
                }
            } catch (e: Exception) {
                // The full-screen notification/dismiss action is already up at this
                // point regardless, so the person can still dismiss even if sound
                // setup failed for some reason -- an alarm should never take the
                // whole app down with it.
                Log.e(TAG, "startRinging failed for alarm $alarmId; notification is up but sound/vibration may be missing", e)
            }
        }

        return START_STICKY
    }

    /**
     * Builds, prepares, and starts a MediaPlayer for [uri]. Returns null instead of
     * throwing if anything fails -- most commonly a stale content:// URI whose
     * underlying MediaStore row was deleted or reassigned, which surfaces as an
     * IOException/IllegalArgumentException from setDataSource or prepare(). The
     * caller is expected to fall back to a known-good sound (the default ringtone)
     * when this returns null, rather than let the alarm ring with no sound at all.
     */
    private fun createPlayer(uri: Uri, rampSeconds: Int): MediaPlayer? {
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmRingtoneService, uri)
                isLooping = true
                // Start silent (or near it) if we're about to ramp, so there's no
                // flash of full volume before the ramp coroutine kicks in.
                if (rampSeconds > 0) setVolume(0f, 0f)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create/start MediaPlayer for $uri", e)
            null
        }
    }

    private suspend fun startRinging(alarm: Alarm?, isTimer: Boolean = false, overrideTimeLabel: String? = null) {
        // Timers keep no snapshot: the snapshot exists solely so alarm snooze can
        // resurrect a vanished row, and timers have no snooze.
        ringingSnapshot = if (isTimer) null else alarm
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
        //
        // Every AudioManager call touching the stream volume is wrapped in
        // try/catch: on several devices, changing stream volume while Do Not
        // Disturb/priority mode is active and this app lacks Notification Policy
        // Access throws a SecurityException. This is an alarm -- it must never
        // crash and fail to ring because of a cosmetic volume-ramp feature, so any
        // such failure just falls back to a plain (non-ramped) alarm at whatever
        // volume the stream is already at.
        var totalHardwareSteps = 0
        if (rampSeconds > 0) {
            totalHardwareSteps = try {
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val targetVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                    .coerceAtLeast(1) // if the user had it at 0, ramp to a minimum audible level instead of silence
                savedAlarmStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
                targetVolume.coerceIn(1, maxVolume)
            } catch (e: SecurityException) {
                Log.w(TAG, "Stream volume change denied (likely DND/notification policy access not granted); ramp disabled for this alarm", e)
                0
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set up volume ramp; ringing at normal volume instead", e)
                0
            }
        }
        val effectiveRampSeconds = if (totalHardwareSteps > 0) rampSeconds else 0

        mediaPlayer = createPlayer(soundUri, effectiveRampSeconds)
            ?: run {
                // The configured sound couldn't be played -- most likely a stale
                // content:// URI: a custom ringtone or song that was later deleted,
                // moved, or had its underlying MediaStore row reassigned by a
                // library rescan, silently invalidating the URI saved with the
                // alarm. Ring with the device's actual default alarm sound rather
                // than not ringing at all.
                Log.w(TAG, "Configured alarm sound failed to load, falling back to default ringtone")
                val fallbackUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                createPlayer(fallbackUri, effectiveRampSeconds)
            }

        if (mediaPlayer == null) {
            Log.e(TAG, "Failed to start any alarm sound, including the fallback default ringtone")
        }

        if (effectiveRampSeconds > 0) {
            val subStepsPerHardwareStep = 10
            val totalSubSteps = totalHardwareSteps * subStepsPerHardwareStep
            val subStepDelayMillis = (effectiveRampSeconds * 1000L / totalSubSteps).coerceAtLeast(20L)

            rampJob = serviceScope.launch {
                var currentHwIndex = 1
                for (sub in 1..totalSubSteps) {
                    delay(subStepDelayMillis)
                    val hwStep = ((sub - 1) / subStepsPerHardwareStep + 1).coerceAtMost(totalHardwareSteps)
                    if (hwStep != currentHwIndex) {
                        currentHwIndex = hwStep
                        try {
                            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, hwStep, 0)
                        } catch (e: Exception) {
                            // Ignore: gain-only smoothing below still applies, so the
                            // alarm keeps ramping (just without the hardware floor
                            // rising) rather than crashing mid-ring.
                        }
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
            val timeLabel = overrideTimeLabel
                ?: if (alarm != null) String.format("%02d:%02d", alarm.hour, alarm.minute) else ""
            val labelText = alarm?.label?.takeIf { it.isNotBlank() } ?: if (isTimer) "Timer" else "Alarm"
            val snoozeLabel = "Snooze ${(alarm?.snoozeMinutes ?: 10).coerceAtLeast(1)} min"
            withContext(Dispatchers.Main) {
                overlayWindow = OverlayAlarmWindow(applicationContext).also {
                    it.show(
                        timeLabel, labelText, snoozeLabel,
                        onDismiss = { handleDismiss() },
                        onSnooze = { handleSnooze() },
                        showSnooze = !isTimer
                    )
                }
            }
        }
    }

    private fun handleDismiss() {
        clearRingingMarker()
        stopRinging()
        stopSelf()
    }

    private fun handleSnooze(alarmId: Long = currentAlarmId) {
        clearRingingMarker()
        stopRinging()
        snooze(alarmId)
        stopSelf()
    }

    // Cleared ONLY on an explicit dismiss/snooze -- deliberately NOT in onDestroy,
    // since onDestroy also runs when the system kills the service, which is exactly
    // the case the marker exists to recover from. commit() so the clear can't be
    // lost to a kill right after dismissal -- a surviving stale marker would re-ring
    // an already-dismissed alarm at the next boot within the grace window.
    private fun clearRingingMarker() {
        getSharedPreferences(RINGING_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_RINGING_ID)
            .remove(KEY_RINGING_SINCE)
            .remove(KEY_RINGING_IS_TIMER)
            .commit()
    }

    private fun stopRinging() {
        rampJob?.cancel()
        rampJob = null
        // stop() throws IllegalStateException if the player slipped into the Error
        // state mid-ring (e.g. an async playback error); dismissing must not crash
        // over cleanup of a player that's already effectively dead.
        mediaPlayer?.let { player ->
            try {
                player.stop()
            } catch (e: Exception) {
                Log.w(TAG, "MediaPlayer.stop() failed during dismiss; releasing anyway", e)
            }
            try {
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "MediaPlayer.release() failed during dismiss", e)
            }
        }
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
        overlayWindow?.dismiss()
        overlayWindow = null

        // Put the user's alarm volume back to whatever it was before a ramp touched it.
        savedAlarmStreamVolume?.let { original ->
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, original, 0)
            } catch (e: Exception) {
                // Same DND/notification-policy restriction as elsewhere in this file --
                // not worth crashing over on the way out.
            }
        }
        savedAlarmStreamVolume = null
    }

    private fun snooze(alarmId: Long) {
        serviceScope.launch {
            val dao = AlarmDatabase.getInstance(applicationContext).alarmDao()
            val alarm = if (alarmId != -1L) dao.getAlarm(alarmId) else null
            if (alarm == null) {
                // The row is gone (a series edit regenerated its children mid-ring,
                // or the alarm was deleted from the list while ringing) or the id
                // itself was lost. Snooze must never silently do nothing -- the
                // person pressed it expecting to be woken again. Rebuild a one-shot
                // from the in-memory snapshot taken at ring start (deliberately
                // one-shot and detached from any series: the regenerated series
                // already covers the weekly schedule, so a repeating copy would
                // double-ring forever). If even the snapshot is gone (process death
                // plus a missing row), fall back to a bare 10-minute default.
                val snap = ringingSnapshot
                val minutes = (snap?.snoozeMinutes ?: 10).coerceAtLeast(1)
                val cal = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.MINUTE, minutes)
                }
                val resurrected = Alarm(
                    hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                    minute = cal.get(java.util.Calendar.MINUTE),
                    label = snap?.label ?: "",
                    daysOfWeek = emptySet(),
                    enabled = true,
                    vibrate = snap?.vibrate ?: true,
                    soundUri = snap?.soundUri,
                    volumeRampSeconds = snap?.volumeRampSeconds ?: 0,
                    snoozeMinutes = minutes
                )
                val newId = dao.insert(resurrected)
                AlarmScheduler(applicationContext).schedule(resurrected.copy(id = newId))
                Log.w(TAG, "Snoozed alarm $alarmId no longer exists; resurrected as one-shot alarm $newId")
                UpcomingAlarmManager(applicationContext).refresh()
                AlarmWidgetUpdater.updateAll(applicationContext)
                return@launch
            }
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
                // Repeating alarms keep their hour/minute/weekday schedule intact in
                // the database; the snooze is persisted separately as snoozeUntilMillis
                // so it survives a reboot or app update. (Previously the snooze only
                // existed as a re-pointed AlarmManager entry -- BootReceiver rebuilds
                // those purely from the database on BOOT_COMPLETED *and*
                // MY_PACKAGE_REPLACED, so a reboot or app update mid-snooze silently
                // reverted the alarm to its next weekly occurrence.) nextTriggerTime
                // honors the field while it's in the future; AlarmReceiver clears it
                // when the alarm fires, so it then returns to the weekly schedule.
                val snoozedAlarm = alarm.copy(snoozeUntilMillis = cal.timeInMillis)
                dao.update(snoozedAlarm)
                AlarmScheduler(applicationContext).schedule(snoozedAlarm)
            }

            UpcomingAlarmManager(applicationContext).refresh()
            AlarmWidgetUpdater.updateAll(applicationContext)
        }
    }

    private fun buildNotification(alarmId: Long, isTimer: Boolean = false): android.app.Notification {
        val fullScreenIntent = Intent(this, RingingActivity::class.java).apply {
            if (isTimer) putExtra(EXTRA_TIMER_ID, alarmId) else putExtra(EXTRA_ALARM_ID, alarmId)
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

        // Same component + extras but a different action, so Intent.filterEquals
        // treats it as distinct from the dismiss PendingIntent despite the shared
        // request code. Matters when the notification is all the user gets: without
        // overlay permission on an unlocked in-use phone, Android downgrades the
        // full-screen intent to a heads-up, and this row is the only snooze path.
        val snoozeIntent = Intent(this, AlarmRingtoneService::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        val snoozePendingIntent = PendingIntent.getService(
            this, alarmId.toInt(), snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (isTimer) "Timer" else "Alarm")
            .setContentText("Tap to open")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(0, "Dismiss", dismissPendingIntent)
            .setOngoing(true)
        // Timers are dismiss-only: "snooze" has no sensible countdown meaning, and
        // the snooze path is alarm-shaped (it persists to the alarms table).
        if (!isTimer) builder.addAction(0, "Snooze", snoozePendingIntent)
        return builder.build()
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
