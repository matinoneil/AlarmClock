# AlarmClock

An Android alarm clock app built with Kotlin and Jetpack Compose. Requires
Android 8.0+ (API 26).

## Features

- **Single alarms** — a time, optional weekday repeat, per-alarm ringtone, snooze
  length, and vibration.
- **Alarm series** — one entry that expands into multiple independent alarms: every
  N minutes from a start time, for a set duration (07:00–07:45 every 5 minutes = 10
  separate alarms). Dismissing one doesn't affect the others; editing the series
  regenerates the whole set.
- **Volume ramp** — alarms can start quiet and climb to full volume over a
  configurable number of seconds.
- **Full-screen ringing UI** — shows over the lock screen, and (with the overlay
  permission) over other apps while the phone is in use, where Android otherwise
  downgrades alarms to a heads-up notification.
- **Skip next occurrence** — a repeating alarm's upcoming notification can dismiss
  just the next ring without disabling the alarm.
- **Reliability** — alarms are rescheduled after reboots and app updates, snoozes
  persist across both, and a ring interrupted by a crash or reboot resumes. Failure
  cases degrade rather than go silent: a broken ringtone URI falls back to the
  system default, and a blocked volume ramp rings at full volume instead.
- **Home screen widget** showing the next alarm, and a themed-icon (Material You)
  compatible launcher icon on Android 13+.

## Install

Download the APK from the [latest release](../../releases/latest) and sideload it.
Builds are signed release builds, so updates install over the previous version.

On first launches the app requests the permissions alarms depend on, one per
launch: exact alarms, notifications, full-screen intent, and "display over other
apps".

**Note on the overlay permission:** Android 13+ blocks "display over other apps"
for sideloaded apps behind "Restricted settings". To grant it: Settings → Apps →
AlarmClock → three-dot menu → *Allow restricted settings*, then grant the
permission normally. Without it everything still rings — only the screen-on,
in-use case falls back to a heads-up notification.

## Building

Open the project folder in Android Studio (Koala or newer) and run, or use the
GitHub Actions workflow at `.github/workflows/build-apk.yml`: every push to `main`
builds an installable APK, available under the Actions tab. When a build is
triggered by publishing a GitHub Release, the app's version name is derived from
the release tag (tag `V1.7` → version `1.7`) and the version code from the Actions
run number.

## Architecture

- **`data/`** — Room entities (`Alarm`, `AlarmSeries`), DAOs, and `AlarmRepository`,
  the single place that talks to both the database and the scheduler. Schema
  changes ship with real Room migrations.
- **`alarm/`** — `AlarmScheduler` (wraps `AlarmManager`, entries keyed by database
  id), `AlarmReceiver`, `BootReceiver` (rebuilds schedules after reboots and app
  updates), `AlarmRingtoneService` (foreground service owning sound, vibration,
  ramp, snooze, dismiss, and interrupted-ring recovery), and `OverlayAlarmWindow`.
- **`ui/`** — Compose screens: alarm list, single-alarm and series editors, and
  `RingingActivity` for the full-screen ringing UI.
- **`viewmodel/`** — `AlarmViewModel`, exposing a `StateFlow` of alarms and series.
- **`widget/`** — the next-alarm home screen widget.

Design history and working notes live in [PROJECT_NOTES.md](PROJECT_NOTES.md).
