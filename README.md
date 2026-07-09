# AlarmClock

An Android alarm clock app built with Kotlin and Jetpack Compose. Requires
Android 8.0+ (API 26).

The app exists mainly for one feature: **alarm series** — batches of fully
independent alarms generated from a single definition, which stock alarm apps
don't really offer.

## Alarm series

If you need several alarms to actually wake up, the usual options are bad. Snoozing
doesn't work — the moment you hit dismiss instead of snooze, half asleep, the chain
is dead and nothing else is coming. Setting ten separate alarms works, but now your
wake-up time is hardcoded ten times: the day your schedule shifts — working from
home instead of going in, an earlier meeting — you're editing ten alarms one by one,
and again when it shifts back.

A series solves both. It's defined by a start time, an interval, and a duration —
"from 07:00, every 5 minutes, for 45 minutes" — and expands into ten real alarms:
07:00, 07:05 ... 07:45. Each one is fully independent: dismissing 07:00 does nothing
to 07:05, which rings anyway, as does every alarm after it, until each is dismissed
on its own. There is no snooze chain to accidentally kill.

And because all ten are generated from one definition, moving the whole wake-up
routine to a different time is a single edit — change the start time and every
alarm regenerates around it. Wednesday at the office means 06:00; back home
Thursday, two taps and the same series rings from 07:00 again.

The series stays editable as one unit in other ways too:

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
