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
        val defaultAlarmSoundUri: String?,
        val defaultTimerSoundUri: String?,
        val defaultVolumeRampSeconds: Int = 0,
        val defaultSnoozeMinutes: Int = 10,
        val defaultAlarmVibrate: Boolean = true,
        val bedtimeEnabled: Boolean = false,
        val bedtimeHoursBefore: Int = 8,
        val bedtimeMessage: String = ""
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

        return root.toString(2)
    }

    /** @throws org.json.JSONException / IllegalArgumentException on malformed input */
    fun fromJson(json: String): BackupData {
        val root = JSONObject(json)
        val version = root.getInt("version")
        require(version in 1..FORMAT_VERSION) { "Unsupported backup version $version" }

        fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else getString(key)

        fun JSONObject.daysOfWeek(): Set<Int> {
            val arr = getJSONArray("daysOfWeek")
            return (0 until arr.length()).map { arr.getInt(it) }
                .filter { it in 1..7 }.toSet()
        }

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

        return BackupData(
            standaloneAlarms = alarms,
            series = series,
            timers = timers,
            defaultAlarmSoundUri = settings.optStringOrNull("defaultAlarmSoundUri"),
            defaultTimerSoundUri = settings.optStringOrNull("defaultTimerSoundUri"),
            // Absent in pre-#45 backups: tolerant optional reads.
            defaultVolumeRampSeconds = settings.optInt("defaultVolumeRampSeconds", 0).coerceAtLeast(0),
            defaultSnoozeMinutes = settings.optInt("defaultSnoozeMinutes", 10).coerceAtLeast(1),
            defaultAlarmVibrate = settings.optBoolean("defaultAlarmVibrate", true),
            // Absent in pre-#47 backups: tolerant optional reads.
            bedtimeEnabled = settings.optBoolean("bedtimeEnabled", false),
            bedtimeHoursBefore = settings.optInt("bedtimeHoursBefore", 8).coerceIn(1, 24),
            bedtimeMessage = settings.optString("bedtimeMessage", "")
        )
    }
}
