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

    // Series defaults (#49). Until a series key is explicitly set, it reads
    // the corresponding alarm key -- so values configured before the split
    // seed both categories instead of silently resetting.
    var defaultSeriesSoundUri: String?
        get() = if (prefs.contains(KEY_SERIES_SOUND)) prefs.getString(KEY_SERIES_SOUND, null) else defaultAlarmSoundUri
        set(value) = prefs.edit().putString(KEY_SERIES_SOUND, value).apply()

    var defaultSeriesRampSeconds: Int
        get() = if (prefs.contains(KEY_SERIES_RAMP)) prefs.getInt(KEY_SERIES_RAMP, 0) else defaultVolumeRampSeconds
        set(value) = prefs.edit().putInt(KEY_SERIES_RAMP, value.coerceAtLeast(0)).apply()

    var defaultSeriesSnoozeMinutes: Int
        get() = if (prefs.contains(KEY_SERIES_SNOOZE)) prefs.getInt(KEY_SERIES_SNOOZE, 10) else defaultSnoozeMinutes
        set(value) = prefs.edit().putInt(KEY_SERIES_SNOOZE, value.coerceAtLeast(1)).apply()

    var defaultSeriesVibrate: Boolean
        get() = if (prefs.contains(KEY_SERIES_VIBRATE)) prefs.getBoolean(KEY_SERIES_VIBRATE, true) else defaultAlarmVibrate
        set(value) = prefs.edit().putBoolean(KEY_SERIES_VIBRATE, value).apply()

    // Timer defaults stay minimal (#49): sound + vibrate, the only two
    // per-timer settings that exist.
    var defaultTimerVibrate: Boolean
        get() = prefs.getBoolean(KEY_TIMER_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_TIMER_VIBRATE, value).apply()

    // Bedtime reminder (#47): a quiet notification N hours before the next
    // enabled alarm rings. Off by default.
    var bedtimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEDTIME_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BEDTIME_ENABLED, value).apply()

    var bedtimeHoursBefore: Int
        get() = prefs.getInt(KEY_BEDTIME_HOURS, 8)
        set(value) = prefs.edit().putInt(KEY_BEDTIME_HOURS, value.coerceIn(1, 24)).apply()

    // Custom bedtime notification text; blank = the default message.
    var bedtimeMessage: String
        get() = prefs.getString(KEY_BEDTIME_MESSAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BEDTIME_MESSAGE, value).apply()

    // How soon a swiped-away reminder notification comes back (#57). The
    // daily re-alert for still-visible notifications is separate and fixed.
    // #62: master switch for the swipe comeback; reminders set to
    // App default follow this. Per-reminder overrides ignore it.
    // #93: this pair now does DOUBLE DUTY -- it is (a) the swipe-protection
    // default copied into every NEW reminder, and (b) still what a legacy
    // RESHOW_FOLLOW_GLOBAL row resolves against, which is what lets those
    // rows keep their current behaviour with no data migration.
    var reminderReshowEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_RESHOW_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_RESHOW_ENABLED, value).apply()

    var reminderReshowMinutes: Int
        get() = prefs.getInt(KEY_REMINDER_RESHOW, 30)
        set(value) = prefs.edit().putInt(KEY_REMINDER_RESHOW, value.coerceAtLeast(0)).apply()

    // #93: the nag half of the new-reminder defaults, mirroring the pair
    // above. Both defaults reproduce the values #92's editor hardcoded, so a
    // fresh install behaves exactly as before until these are changed.
    var reminderDefaultNagEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_DEFAULT_NAG, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_DEFAULT_NAG, value).apply()

    // Minutes only -- "off" is the boolean above, never a 0 stored here, so
    // the floor is 1 rather than renotifyMinutes' 0.
    var reminderDefaultRenotifyMinutes: Int
        get() = prefs.getInt(KEY_REMINDER_DEFAULT_RENOTIFY, 1440)
        set(value) = prefs.edit().putInt(KEY_REMINDER_DEFAULT_RENOTIFY, value.coerceAtLeast(1)).apply()

    // #94: when on, swiping the upcoming-alarm notification away brings it
    // straight back, so it can only be cleared with its own "Dismiss next
    // alarm" action. Covers standalone AND series alarms -- there is only one
    // such notification. Only has any effect on Android 14+, where setOngoing
    // stopped blocking the swipe. Defaults ON: this is a requested behaviour,
    // not a preservation of the old one.
    var upcomingSwipeProtection: Boolean
        get() = prefs.getBoolean(KEY_UPCOMING_SWIPE_PROTECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_UPCOMING_SWIPE_PROTECTION, value).apply()

    // #95: the same for the running-timer countdown notification, so it can
    // only be cleared with its own Stop action. Like #94 this only bites on
    // Android 14+, and defaults ON as requested behaviour.
    var timerSwipeProtection: Boolean
        get() = prefs.getBoolean(KEY_TIMER_SWIPE_PROTECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_TIMER_SWIPE_PROTECTION, value).apply()

    // #91: user dismissed the "full-screen alarms are off" banner. Cleared
    // automatically once the permission is granted again, so a LATER revocation
    // still warns someone who has shown they want the feature.
    var fullScreenBannerDismissed: Boolean
        get() = prefs.getBoolean(KEY_FS_BANNER_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_FS_BANNER_DISMISSED, value).apply()

    private companion object {
        const val KEY_DEFAULT_ALARM_SOUND = "default_alarm_sound_uri"
        const val KEY_DEFAULT_TIMER_SOUND = "default_timer_sound_uri"
        const val KEY_DEFAULT_RAMP = "default_volume_ramp_seconds"
        const val KEY_DEFAULT_SNOOZE = "default_snooze_minutes"
        const val KEY_DEFAULT_VIBRATE = "default_alarm_vibrate"
        const val KEY_BEDTIME_ENABLED = "bedtime_enabled"
        const val KEY_BEDTIME_HOURS = "bedtime_hours_before"
        const val KEY_BEDTIME_MESSAGE = "bedtime_message"
        const val KEY_SERIES_SOUND = "default_series_sound_uri"
        const val KEY_SERIES_RAMP = "default_series_ramp_seconds"
        const val KEY_SERIES_SNOOZE = "default_series_snooze_minutes"
        const val KEY_SERIES_VIBRATE = "default_series_vibrate"
        const val KEY_TIMER_VIBRATE = "default_timer_vibrate"
        const val KEY_REMINDER_RESHOW = "reminder_reshow_minutes"
        const val KEY_REMINDER_RESHOW_ENABLED = "reminder_reshow_enabled"
        const val KEY_REMINDER_DEFAULT_NAG = "reminder_default_nag_enabled"
        const val KEY_REMINDER_DEFAULT_RENOTIFY = "reminder_default_renotify_minutes"
        const val KEY_UPCOMING_SWIPE_PROTECTION = "upcoming_swipe_protection"
        const val KEY_TIMER_SWIPE_PROTECTION = "timer_swipe_protection"
        const val KEY_FS_BANNER_DISMISSED = "full_screen_banner_dismissed"
    }
}
