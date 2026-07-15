package no.hanss.alarmclock.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned JSON backup of everything a user would rebuild by hand: series
 * (including an active pause), standalone alarms, timer presets, and the two
 * default-sound settings. Transient state is deliberately excluded -- snoozes,
 * skip-next, and running countdowns are moments, not configuration, and
 * restoring them later would resurrect stale timestamps (#13's philosophy).
 * Series CHILDREN aren't serialized at all: they regenerate from the series
 * definition on restore through the normal saveSeries path.
 *
 * Sound URIs are content:// references that may not resolve on another device
 * or after a factory reset; that's fine -- the ring path already falls back to
 * the system alarm sound on a broken URI, so a stale backup degrades safely.
 */
object BackupSerializer {

    const val FORMAT_VERSION = 1

    data class BackupData(
        val standaloneAlarms: List<Alarm>,
        val series: List<AlarmSeries>,
        val timers: List<TimerPreset>,
        val reminders: List<Reminder> = emptyList(),
        val defaultAlarmSoundUri: String?,
        val defaultTimerSoundUri: String?,
        val defaultVolumeRampSeconds: Int = 0,
        val defaultSnoozeMinutes: Int = 10,
        val defaultAlarmVibrate: Boolean = true,
        val bedtimeEnabled: Boolean = false,
        val bedtimeHoursBefore: Int = 8,
        val bedtimeMessage: String = "",
        val defaultSeriesSoundUri: String? = null,
        val defaultSeriesRampSeconds: Int = 0,
        val defaultSeriesSnoozeMinutes: Int = 10,
        val defaultSeriesVibrate: Boolean = true,
        val defaultTimerVibrate: Boolean = true,
        val reminderReshowMinutes: Int = 30,
        val reminderReshowEnabled: Boolean = true
    )

    fun toJson(data: BackupData): String {
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("exportedAtMillis", System.currentTimeMillis())

        val settings = JSONObject()
        settings.put("defaultAlarmSoundUri", data.defaultAlarmSoundUri ?: JSONObject.NULL)
        settings.put("defaultTimerSoundUri", data.defaultTimerSoundUri ?: JSONObject.NULL)
        settings.put("defaultVolumeRampSeconds", data.defaultVolumeRampSeconds)
        settings.put("defaultSnoozeMinutes", data.defaultSnoozeMinutes)
        settings.put("defaultAlarmVibrate", data.defaultAlarmVibrate)
        settings.put("bedtimeEnabled", data.bedtimeEnabled)
        settings.put("bedtimeHoursBefore", data.bedtimeHoursBefore)
        settings.put("bedtimeMessage", data.bedtimeMessage)
        settings.put("defaultSeriesSoundUri", data.defaultSeriesSoundUri ?: JSONObject.NULL)
        settings.put("defaultSeriesRampSeconds", data.defaultSeriesRampSeconds)
        settings.put("defaultSeriesSnoozeMinutes", data.defaultSeriesSnoozeMinutes)
        settings.put("defaultSeriesVibrate", data.defaultSeriesVibrate)
        settings.put("reminderReshowMinutes", data.reminderReshowMinutes)
        settings.put("reminderReshowEnabled", data.reminderReshowEnabled)
        settings.put("defaultTimerVibrate", data.defaultTimerVibrate)
        root.put("settings", settings)

        root.put("standaloneAlarms", JSONArray().apply {
            data.standaloneAlarms.forEach { a ->
                put(JSONObject().apply {
                    put("hour", a.hour)
                    put("minute", a.minute)
                    put("label", a.label)
                    put("daysOfWeek", JSONArray(a.daysOfWeek.sorted()))
                    put("enabled", a.enabled)
                    put("vibrate", a.vibrate)
                    put("soundUri", a.soundUri ?: JSONObject.NULL)
                    put("volumeRampSeconds", a.volumeRampSeconds)
                    put("snoozeMinutes", a.snoozeMinutes)
                    put("pausedUntilMillis", a.pausedUntilMillis ?: JSONObject.NULL)
                })
            }
        })

        root.put("series", JSONArray().apply {
            data.series.forEach { s ->
                put(JSONObject().apply {
                    put("name", s.name)
                    put("startHour", s.startHour)
                    put("startMinute", s.startMinute)
                    put("intervalMinutes", s.intervalMinutes)
                    put("durationMinutes", s.durationMinutes)
                    put("daysOfWeek", JSONArray(s.daysOfWeek.sorted()))
                    put("enabled", s.enabled)
                    put("vibrate", s.vibrate)
                    put("soundUri", s.soundUri ?: JSONObject.NULL)
                    put("volumeRampSeconds", s.volumeRampSeconds)
                    put("snoozeMinutes", s.snoozeMinutes)
                    put("pausedUntilMillis", s.pausedUntilMillis ?: JSONObject.NULL)
                })
            }
        })

        root.put("timers", JSONArray().apply {
            data.timers.forEach { t ->
                put(JSONObject().apply {
                    put("durationSeconds", t.durationSeconds)
                    put("label", t.label)
                    put("vibrate", t.vibrate)
                    put("soundUri", t.soundUri ?: JSONObject.NULL)
                })
            }
        })

        root.put("reminders", JSONArray().apply {
            data.reminders.forEach { r ->
                put(JSONObject().apply {
                    put("text", r.text)
                    put("dueAtMillis", r.dueAtMillis)
                    // State IS carried (done history is worth keeping); the
                    // snooze override is a moment, not configuration, and is
                    // deliberately excluded like alarm snoozes.
                    put("state", r.state)
                    put("repeatType", r.repeatType)
                    put("repeatInterval", r.repeatInterval)
                    put("repeatDaysOfWeek", JSONArray(r.repeatDaysOfWeek.sorted()))
                    put("repeatDayOfMonth", r.repeatDayOfMonth)
                    put("repeatWeekday", r.repeatWeekday)
                    put("repeatWeekOfMonth", r.repeatWeekOfMonth)
                    put("renotifyMinutes", r.renotifyMinutes)
                    put("reshowMinutes", r.reshowMinutes)
                    put("persistent", r.persistent)
                })
            }
        })

        return root.toString(2)
    }

    /** @throws org.json.JSONException / IllegalArgumentException on malformed input */
    fun fromJson(json: String): BackupData {
        val root = JSONObject(json)
        val version = root.getInt("version")
        require(version in 1..FORMAT_VERSION) { "Unsupported backup version $version" }

        fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else getString(key)

        fun JSONObject.daysOfWeekField(key: String): Set<Int> {
            val arr = getJSONArray(key)
            return (0 until arr.length()).map { arr.getInt(it) }
                .filter { it in 1..7 }.toSet()
        }

        fun JSONObject.daysOfWeek(): Set<Int> = daysOfWeekField("daysOfWeek")

        val settings = root.optJSONObject("settings") ?: JSONObject()

        val alarms = root.getJSONArray("standaloneAlarms").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Alarm(
                    hour = o.getInt("hour").coerceIn(0, 23),
                    minute = o.getInt("minute").coerceIn(0, 59),
                    label = o.optString("label", ""),
                    daysOfWeek = o.daysOfWeek(),
                    enabled = o.optBoolean("enabled", true),
                    vibrate = o.optBoolean("vibrate", true),
                    soundUri = o.optStringOrNull("soundUri"),
                    volumeRampSeconds = o.optInt("volumeRampSeconds", 0).coerceAtLeast(0),
                    snoozeMinutes = o.optInt("snoozeMinutes", 10).coerceAtLeast(1),
                    // Absent in pre-#44 backups: tolerant optional read.
                    pausedUntilMillis = if (!o.has("pausedUntilMillis") || o.isNull("pausedUntilMillis")) null else o.getLong("pausedUntilMillis")
                )
            }
        }

        val series = root.getJSONArray("series").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AlarmSeries(
                    name = o.optString("name", ""),
                    startHour = o.getInt("startHour").coerceIn(0, 23),
                    startMinute = o.getInt("startMinute").coerceIn(0, 59),
                    intervalMinutes = o.getInt("intervalMinutes").coerceAtLeast(1),
                    durationMinutes = o.getInt("durationMinutes").coerceAtLeast(0),
                    daysOfWeek = o.daysOfWeek(),
                    enabled = o.optBoolean("enabled", true),
                    vibrate = o.optBoolean("vibrate", true),
                    soundUri = o.optStringOrNull("soundUri"),
                    volumeRampSeconds = o.optInt("volumeRampSeconds", 0).coerceAtLeast(0),
                    snoozeMinutes = o.optInt("snoozeMinutes", 10).coerceAtLeast(1),
                    // saveSeries nulls a pause already in the past and re-arms
                    // a future one, so the raw value is safe to carry over.
                    pausedUntilMillis = if (o.isNull("pausedUntilMillis")) null else o.getLong("pausedUntilMillis")
                )
            }
        }

        val timers = root.getJSONArray("timers").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TimerPreset(
                    durationSeconds = o.getInt("durationSeconds").coerceAtLeast(1),
                    label = o.optString("label", ""),
                    vibrate = o.optBoolean("vibrate", true),
                    soundUri = o.optStringOrNull("soundUri")
                    // runningUntilMillis intentionally absent: timers restore idle.
                )
            }
        }

        // Absent in pre-#50 backups: tolerant optional read of the whole array.
        val reminders = (root.optJSONArray("reminders") ?: JSONArray()).let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reminder(
                    text = o.optString("text", ""),
                    dueAtMillis = o.getLong("dueAtMillis"),
                    // An ACTIVE reminder restores as PENDING: its overdue
                    // dueAt makes the restore path re-fire it, so it comes
                    // back showing rather than stuck active-with-no-alarm.
                    state = when (o.optInt("state", Reminder.STATE_PENDING)) {
                        Reminder.STATE_DONE -> Reminder.STATE_DONE
                        else -> Reminder.STATE_PENDING
                    },
                    repeatType = o.optInt("repeatType", Reminder.REPEAT_NONE)
                        .coerceIn(Reminder.REPEAT_NONE, Reminder.REPEAT_YEARLY),
                    repeatInterval = o.optInt("repeatInterval", 1).coerceAtLeast(1),
                    repeatDaysOfWeek = if (o.has("repeatDaysOfWeek")) o.daysOfWeekField("repeatDaysOfWeek") else emptySet(),
                    repeatDayOfMonth = o.optInt("repeatDayOfMonth", 0).coerceIn(0, 31),
                    repeatWeekday = o.optInt("repeatWeekday", 0).coerceIn(0, 7),
                    repeatWeekOfMonth = o.optInt("repeatWeekOfMonth", 0).coerceIn(Reminder.LAST_WEEK_OF_MONTH, 4),
                    renotifyMinutes = o.optInt("renotifyMinutes", 1440).coerceAtLeast(0),
                    reshowMinutes = o.optInt("reshowMinutes", Reminder.RESHOW_FOLLOW_GLOBAL).coerceAtLeast(Reminder.RESHOW_OFF),
                    persistent = o.optBoolean("persistent", true)
                )
            }
        }

        return BackupData(
            standaloneAlarms = alarms,
            series = series,
            timers = timers,
            reminders = reminders,
            defaultAlarmSoundUri = settings.optStringOrNull("defaultAlarmSoundUri"),
            defaultTimerSoundUri = settings.optStringOrNull("defaultTimerSoundUri"),
            // Absent in pre-#45 backups: tolerant optional reads.
            defaultVolumeRampSeconds = settings.optInt("defaultVolumeRampSeconds", 0).coerceAtLeast(0),
            defaultSnoozeMinutes = settings.optInt("defaultSnoozeMinutes", 10).coerceAtLeast(1),
            defaultAlarmVibrate = settings.optBoolean("defaultAlarmVibrate", true),
            // Absent in pre-#47 backups: tolerant optional reads.
            bedtimeEnabled = settings.optBoolean("bedtimeEnabled", false),
            bedtimeHoursBefore = settings.optInt("bedtimeHoursBefore", 8).coerceIn(1, 24),
            bedtimeMessage = settings.optString("bedtimeMessage", ""),
            // Absent in pre-#49 backups: tolerant optional reads; series
            // values fall back to the alarm values, matching SettingsStore.
            defaultSeriesSoundUri = if (settings.has("defaultSeriesSoundUri") && !settings.isNull("defaultSeriesSoundUri")) settings.getString("defaultSeriesSoundUri") else settings.optStringOrNull("defaultAlarmSoundUri"),
            defaultSeriesRampSeconds = settings.optInt("defaultSeriesRampSeconds", settings.optInt("defaultVolumeRampSeconds", 0)).coerceAtLeast(0),
            defaultSeriesSnoozeMinutes = settings.optInt("defaultSeriesSnoozeMinutes", settings.optInt("defaultSnoozeMinutes", 10)).coerceAtLeast(1),
            defaultSeriesVibrate = settings.optBoolean("defaultSeriesVibrate", settings.optBoolean("defaultAlarmVibrate", true)),
            reminderReshowMinutes = settings.optInt("reminderReshowMinutes", 30).coerceAtLeast(0),
            reminderReshowEnabled = settings.optBoolean("reminderReshowEnabled", true),
            defaultTimerVibrate = settings.optBoolean("defaultTimerVibrate", true)
        )
    }
}
