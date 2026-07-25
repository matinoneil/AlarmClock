package no.hanss.alarmclock.ui

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/**
 * Shown when a stored sound URI cannot be resolved. Says what will actually
 * happen, because AlarmRingtoneService falls back to the device default when the
 * configured sound will not load.
 */
const val SOUND_UNAVAILABLE = "Sound unavailable \u2014 using default"

/**
 * Resolves a stored sound URI to a label that tells the truth.
 *
 * Replaces four near-identical copies of this logic that lived in
 * AlarmEditScreen / SeriesEditScreen / TimerEditScreen / SettingsScreen.
 *
 * The subtlety worth keeping (entries #81, #84): Ringtone.getTitle() does NOT
 * fail when it cannot read the media row -- per AOSP it swallows the
 * SecurityException and returns `uri.getLastPathSegment()` instead. For a
 * content://media/external/audio/media/1234 URI that is the bare string "1234",
 * which is why a missing or unreadable song used to display as a number. So an
 * exact match between the returned title and the URI's last path segment is the
 * signal that resolution FAILED, and it is the only signal available without
 * querying MediaStore separately.
 *
 * A real title colliding with its own numeric row id is not a realistic
 * collision. content://settings/system/alarm_alert resolves through a different
 * branch and returns the underlying sound's real name, so it is unaffected.
 *
 * @param defaultLabel what to show when [uriString] is null, i.e. when the app is
 *   deliberately set to the system default. Differs per screen.
 */
fun soundDisplayName(context: Context, uriString: String?, defaultLabel: String): String {
    if (uriString == null) return defaultLabel

    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return SOUND_UNAVAILABLE
    val title = runCatching {
        RingtoneManager.getRingtone(context, uri)?.getTitle(context)
    }.getOrNull() ?: return SOUND_UNAVAILABLE

    return if (title == uri.lastPathSegment) SOUND_UNAVAILABLE else title
}
