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

    // Alarm defaults beyond the sound (#45); the built-in fallbacks match the
    // hardcoded values the editors used before these settings existed.
    var defaultVolumeRampSeconds: Int
        get() = prefs.getInt(KEY_DEFAULT_RAMP, 0)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_RAMP, value.coerceAtLeast(0)).apply()

    var defaultSnoozeMinutes: Int
        get() = prefs.getInt(KEY_DEFAULT_SNOOZE, 10)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_SNOOZE, value.coerceAtLeast(1)).apply()

    var defaultAlarmVibrate: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_DEFAULT_VIBRATE, value).apply()

    // Bedtime reminder (#47): a quiet notification N hours before the next
    // enabled alarm rings. Off by default.
    var bedtimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEDTIME_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BEDTIME_ENABLED, value).apply()

    var bedtimeHoursBefore: Int
        get() = prefs.getInt(KEY_BEDTIME_HOURS, 8)
        set(value) = prefs.edit().putInt(KEY_BEDTIME_HOURS, value.coerceIn(1, 24)).apply()

    private companion object {
        const val KEY_DEFAULT_ALARM_SOUND = "default_alarm_sound_uri"
        const val KEY_DEFAULT_TIMER_SOUND = "default_timer_sound_uri"
        const val KEY_DEFAULT_RAMP = "default_volume_ramp_seconds"
        const val KEY_DEFAULT_SNOOZE = "default_snooze_minutes"
        const val KEY_DEFAULT_VIBRATE = "default_alarm_vibrate"
        const val KEY_BEDTIME_ENABLED = "bedtime_enabled"
        const val KEY_BEDTIME_HOURS = "bedtime_hours_before"
    }
}
