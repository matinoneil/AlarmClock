# AlarmClock

A Kotlin + Jetpack Compose Android alarm clock with two kinds of alarms:

- **Single alarms** - set at a specific time, optionally repeating on chosen weekdays.
- **Alarm series** - e.g. "07:00 Alarms": a series of independent alarms fired every
  N minutes starting at a chosen time, for a chosen total duration (e.g. every 5 min
  from 07:00 to 07:45, 10 separate alarms). Each one is a fully independent alarm;
  dismissing one has no effect on the others. Start time, interval, and duration are
  all editable after creation, and the series' name auto-fills from the start time
  until you type your own. The whole series can be turned on/off with one switch, and
  the day-of-week picker applies to every alarm it generates (e.g. Mon-Fri only).

## Features

- Custom ringtone per alarm or series, chosen via the system ringtone picker.
- Volume ramp: alarms can start quiet and climb to full volume over a configurable
  number of seconds, instead of ringing at full volume immediately.
- Full-screen ringing UI that reliably appears whether the screen is locked, unlocked,
  or already on, using a full-screen-intent notification rather than a plain
  heads-up one.
- Snooze (10 minutes) and dismiss from the ringing screen or its notification.
- Confirmation dialog before deleting any alarm or series, both from the list screen
  and from within the edit screens.
- Editing an alarm or series preserves its existing on/off state rather than
  re-enabling it.
- Alarms are rescheduled automatically after a device reboot.

## Opening the project

1. Open this folder (`AlarmClock/`) directly in Android Studio (Koala or newer recommended).
2. Let Android Studio sync Gradle - it will generate the Gradle wrapper for you if it's
   missing.
3. Run on a device or emulator with **API 26+**.

## Building without Android Studio

A GitHub Actions workflow at `.github/workflows/build-apk.yml` builds a debug APK on
every push to `main` (or via manual trigger) and uploads it as a downloadable artifact
under the Actions tab - useful if you don't have a PC or the Android SDK installed
locally. No signing key is configured, so this produces a debug build only, suitable
for installing on your own device.

## Architecture

- **`data/`** - Room entities (`Alarm`, `AlarmSeries`), DAOs, and `AlarmRepository`, which
  is the single place that talks to both the database and the alarm scheduler.
- **`alarm/`** - `AlarmScheduler` (wraps `AlarmManager`), `AlarmReceiver` (fires when an
  alarm goes off), `BootReceiver` (reschedules everything after a reboot - AlarmManager
  entries don't survive one), and `AlarmRingtoneService` (foreground service that plays
  the alarm sound with an optional volume ramp, handles vibration, and posts the
  full-screen ringing notification).
- **`ui/`** - Compose screens: the list, single-alarm editor, and alarm-series editor
  (with a live preview of every time that will be generated, a ringtone picker, and a
  volume-ramp field), plus `RingingActivity`, the full-screen UI shown when an alarm
  fires.
- **`viewmodel/`** - `AlarmViewModel`, exposing a `StateFlow` of alarms/series to the UI.

### How an alarm series becomes real alarms

`AlarmSeries.expandTimes()` turns (start, interval, duration) into a list of times.
`AlarmRepository.saveSeries()` deletes any previously generated child `Alarm` rows for
that series and regenerates them from scratch, so editing a series' interval or duration
later just re-expands and re-schedules - you don't need to touch individual alarms.
Each child alarm gets its own row, its own `AlarmManager` entry (keyed by its database
id), and rings completely independently, inheriting the series' ringtone, vibrate, and
volume-ramp settings.

### Why the full-screen UI relies only on the notification's full-screen intent

Calling `startActivity()` directly from a background service is blocked by Android
whenever the app isn't already in the foreground and the screen is on - so the ringing
activity only used to appear reliably when the phone was locked. A full-screen-intent
notification is specifically exempted from that restriction, so the service now relies
on it exclusively rather than also trying to launch the activity directly.

### Permissions you'll be prompted for

- **Exact alarms** (Android 12+): the app checks `canScheduleExactAlarms()` on launch and
  sends you to system settings if it's not granted yet - without this, alarms can be
  delayed by the OS.
- **Notifications** (Android 13+): needed to show the alarm notification/full-screen intent.
- **Full-screen intent** (Android 14+): a per-app toggle the user can revoke; the app
  checks `canUseFullScreenIntent()` on launch and sends you to settings if it's off.
- **Display over other apps**: needed only for the case where the screen is already on
  and you're actively using the phone when an alarm fires -- without it, that specific
  case falls back to a heads-up notification instead of the full-screen ringing UI.
  Alarm firing, sound, vibration, volume ramp, snooze, and dismiss all work regardless,
  and the locked-screen/screen-off case is unaffected either way, since that's handled
  by the full-screen-intent notification instead.

  On sideloaded installs (i.e. not from the Play Store), Android 13+ applies "Restricted
  Settings" to this specific permission and blocks granting it with a warning, regardless
  of what the app actually does. To grant it: Settings -> Apps -> AlarmClock -> three-dot
  menu (top right) -> "Allow restricted settings" -> then grant "Display over other apps"
  normally. This is standard Android behavior for any sideloaded app requesting this
  permission, not specific to this app.

## Known gaps / things you may want to extend

- Snooze always adds 10 minutes with no limit on how many times it can be used.
- The enable/disable switch is all-or-nothing (on indefinitely / off indefinitely) -
  there's no "skip just this one occurrence" option for a repeating alarm or series.
- No launcher app icon design beyond a simple placeholder vector - swap
  `ic_launcher_foreground.xml` / `ic_launcher_background.xml` for real artwork whenever
  you like.
- Widget/quick-settings tile, and Doze-proofing beyond `setAlarmClock()`, aren't included.
- The database currently falls back to a destructive migration on schema-version bumps
  (see `AlarmDatabase.kt`), meaning existing alarms are wiped when the schema changes.
  Fine while iterating; worth replacing with real Room migrations once the schema
  stabilizes.
