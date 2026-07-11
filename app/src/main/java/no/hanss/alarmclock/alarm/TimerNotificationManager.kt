package no.hanss.alarmclock.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import no.hanss.alarmclock.MainActivity
import no.hanss.alarmclock.R
import no.hanss.alarmclock.data.TimerPreset

// "_v2": the first release of this feature (V1.9.0) shipped an IMPORTANCE_LOW
// channel, which Android files under the collapsed "Silent" section with no
// status bar icon -- exactly what the countdown notification shouldn't be.
// Channels are immutable once created on a device, so raising the importance
// required a NEW channel id; the old one is deleted in createChannel() so it
// doesn't linger as a dead entry in the app's notification settings.
private const val RUNNING_TIMER_CHANNEL_ID = "running_timer_channel_v2"
private const val LEGACY_RUNNING_TIMER_CHANNEL_ID = "running_timer_channel"
// One notification per running timer so several countdowns can coexist.
// Base offset keeps clear of the ringing (1001) and upcoming (2001) ids.
private const val RUNNING_TIMER_NOTIFICATION_BASE = 3000

/**
 * Silent, ongoing notification shown while a timer preset is counting down,
 * with +30 s / -30 s / Stop actions (handled by [TimerReceiver]).
 *
 * The visible countdown uses the notification chronometer
 * (setWhen(endMillis) + setChronometerCountDown), which the OS renders and
 * ticks itself -- no service, coroutine, or repeated re-posting needed, so a
 * running timer still costs nothing while the app's process is dead. That's
 * also why the actions are broadcasts rather than service intents: nothing is
 * running to receive anything else.
 */
class TimerNotificationManager(private val context: Context) {

    fun post(timer: TimerPreset) {
        val until = timer.runningUntilMillis ?: return
        createChannel()

        val contentIntent = PendingIntent.getActivity(
            context,
            timer.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                // Same flag set as the widget tap (entry #8): without these a
                // tap stacks a second MainActivity on any already-open one.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Same component + same request code, distinct actions: filterEquals
        // treats them as three separate PendingIntents (same trick as the
        // ringing notification's dismiss/snooze pair).
        fun actionIntent(action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            timer.id.toInt(),
            Intent(context, TimerReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_TIMER_ID, timer.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = until }
        val ringsAt = String.format(
            "Rings at %02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )

        // The countdown is the notification's point, so it gets the big text
        // slot via a custom layout whose Chronometer the OS ticks itself (still
        // zero process time); "Rings at HH:MM" moves to the small header slot
        // (subText) where the chronometer used to sit. Chronometer runs on the
        // elapsedRealtime clock, hence the base conversion.
        val chronoBase = SystemClock.elapsedRealtime() + (until - System.currentTimeMillis())
        fun contentView(): RemoteViews =
            RemoteViews(context.packageName, R.layout.notification_timer).apply {
                setChronometerCountDown(R.id.timer_chronometer, true)
                setChronometer(R.id.timer_chronometer, chronoBase, null, true)
            }

        val notification = NotificationCompat.Builder(context, RUNNING_TIMER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(contentView())
            .setCustomBigContentView(contentView())
            // A custom label joins "Rings at" in the header; unlabeled timers
            // show no word at all -- the countdown speaks for itself (#37).
            .setSubText(
                timer.label.takeIf { it.isNotBlank() }?.let { "$ringsAt · $it" } ?: ringsAt
            )
            // Default priority so the notification gets a status bar icon and
            // sits in the main shade section instead of the collapsed "Silent"
            // one. No setSilent(): that flag would demote it right back on
            // Android 10+. Soundlessness comes from the channel having no
            // sound/vibration, and onlyAlertOnce keeps the +-30 s re-posts
            // from causing any repeated alert behavior.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "+30 s", actionIntent(ACTION_TIMER_ADD_30))
            .addAction(0, "\u221230 s", actionIntent(ACTION_TIMER_MINUS_30))
            .addAction(0, "Stop", actionIntent(ACTION_TIMER_STOP))
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(timer.id), notification)
    }

    fun cancel(timerId: Long) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(timerId))
    }

    private fun notificationId(timerId: Long): Int =
        RUNNING_TIMER_NOTIFICATION_BASE + timerId.toInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            // Remove the V1.9.0 IMPORTANCE_LOW channel on devices that have it.
            manager.deleteNotificationChannel(LEGACY_RUNNING_TIMER_CHANNEL_ID)
            val channel = NotificationChannel(
                RUNNING_TIMER_CHANNEL_ID, "Running timer", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows while a timer is counting down"
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
