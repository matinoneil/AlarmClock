package no.hanss.alarmclock.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import no.hanss.alarmclock.MainActivity
import no.hanss.alarmclock.R
import no.hanss.alarmclock.alarm.AlarmScheduler
import no.hanss.alarmclock.data.AlarmDatabase

/**
 * Keeps the home-screen widget in sync with whichever enabled alarm is soonest to
 * fire. Called both by the system (AlarmWidgetProvider.onUpdate, e.g. right after the
 * widget is placed, or its periodic fallback refresh) and explicitly by the app any
 * time an alarm changes, fires, or the device reboots -- the same trigger points as
 * UpcomingAlarmManager.
 */
object AlarmWidgetUpdater {

    suspend fun updateAll(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, AlarmWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val text = computeDisplayText(context)
        val views = buildViews(context, text)
        for (id in ids) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    private suspend fun computeDisplayText(context: Context): String {
        val dao = AlarmDatabase.getInstance(context).alarmDao()
        val scheduler = AlarmScheduler(context)
        val enabled = dao.getAllEnabledAlarms()
        val soonest = enabled
            .map { it to scheduler.peekNextTriggerTime(it) }
            .minByOrNull { it.second }
            ?: return "No alarm set"

        val (_, triggerAt) = soonest
        val dayLabel = dayLabelFor(triggerAt)
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAt }
        val timeLabel = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        return "$dayLabel\n$timeLabel"
    }

    private fun dayLabelFor(triggerAtMillis: Long): String {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }

        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        return when {
            sameDay(target, now) -> "Today"
            sameDay(target, tomorrow) -> "Tomorrow"
            else -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(triggerAtMillis))
        }
    }

    private fun buildViews(context: Context, text: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_alarm)
        views.setTextViewText(R.id.widget_text, text)

        val launchIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        return views
    }
}
