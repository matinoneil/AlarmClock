package no.hanss.alarmclock.data

import android.content.Context

/**
 * App settings (SharedPreferences-backed). The two default sound URIs are
 * applied at CREATION time: the new-alarm/series/timer editors prefill from
 * them. A null default (or a stored URI that later breaks) keeps the existing
 * behavior -- the ring path falls back to the system alarm sound.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var defaultAlarmSoundUri: String?
        get() = prefs.getString(KEY_DEFAULT_ALARM_SOUND, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_ALARM_SOUND, value).apply()

    var defaultTimerSoundUri: String?
        get() = prefs.getString(KEY_DEFAULT_TIMER_SOUND, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_TIMER_SOUND, value).apply()

    private companion object {
        const val KEY_DEFAULT_ALARM_SOUND = "default_alarm_sound_uri"
        const val KEY_DEFAULT_TIMER_SOUND = "default_timer_sound_uri"
    }
}
