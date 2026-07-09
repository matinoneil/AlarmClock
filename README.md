# AlarmClock

An Android alarm clock app built with Kotlin and Jetpack Compose. Requires
Android 8.0+ (API 26).

The app exists mainly for one feature: **alarm series** — batches of fully
independent alarms generated from a single definition, which stock alarm apps
don't really offer.

## Alarm series

A series is defined by a start time, an interval, and a duration — for example
"from 07:00, every 5 minutes, for 45 minutes". That one definition expands into
ten real alarms: 07:00, 07:05, 07:10 ... 07:45.

The point is that each generated alarm is completely independent. Dismissing the
07:00 alarm does nothing to the 07:05 one — it will still ring, and so will every
one after it, until each is dismissed on its own. This is different from tapping
snooze repeatedly (which only ever gives you one upcoming alarm, and stops the
moment you hit dismiss instead), and different from creating ten alarms by hand
(which you'd have to edit ten times to change anything).

The series stays editable as one unit:

- Change the start time, interval, or duration and the whole set regenerates.
- One switch enables or disables every alarm in the series.
- The weekday repeat, ringtone, volume ramp, snooze length, and vibration setting
  apply to every alarm the series generates.
- The editor shows a live preview of exactly which times will be created.
- The series name auto-fills from the start time until you type your own.

## Other features

- **Single alarms** — a time, optional weekday repeat, per-alarm ringtone, snooze
  length, and vibration.
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

A series becomes real alarms via `AlarmSeries.expandTimes()`; saving a series
deletes its previously generated child `Alarm` rows and regenerates them, each
with its own database row and `AlarmManager` entry.

Design history and working notes live in [PROJECT_NOTES.md](PROJECT_NOTES.md).
