# AlarmClock

A Kotlin + Jetpack Compose Android alarm clock with two kinds of alarms:

- **Single alarms** — set at a specific time, optionally repeating on chosen weekdays.
- **Alarm series** — e.g. "07:00 Alarms": a series of independent alarms fired every
  N minutes starting at a chosen time, for a chosen total duration (e.g. every 5 min
  from 07:00 to 07:45 → 10 separate alarms). Each one is a fully independent alarm;
  dismissing one has no effect on the others, as requested. Start time, interval, and
  duration are all editable after creation, and the series' name auto-fills from the
  start time until you type your own. The whole series can be turned on/off with one
  switch, and the day-of-week picker applies to every alarm it generates (e.g. Mon–Fri
  only).

## Opening the project

1. Open this folder (`AlarmClock/`) directly in Android Studio (Koala or newer recommended).
2. Let Android Studio sync Gradle — it will generate the Gradle wrapper for you if it's
   missing (I didn't include `gradlew`/`gradle-wrapper.jar` binaries since I can't fetch
   them from this environment; Android Studio's "Sync Now" prompt handles this automatically
   on first open).
3. Run on a device or emulator with **API 26+**.

**Note on my sandbox:** I built this without access to Google's Maven repo or the Android
SDK, so I could not run an actual Gradle build to compile-check it. The code follows
standard, current APIs (Room 2.6, Compose BOM 2024.06, AGP 8.5, Kotlin 1.9.24), but if
Android Studio flags something on first sync (e.g. a dependency version bump it suggests),
that's expected — just accept the suggested fix or let me know and I'll adjust.

## Architecture

- **`data/`** — Room entities (`Alarm`, `AlarmSeries`), DAOs, and `AlarmRepository`, which
  is the single place that talks to both the database and the alarm scheduler.
- **`alarm/`** — `AlarmScheduler` (wraps `AlarmManager`), `AlarmReceiver` (fires when an
  alarm goes off), `BootReceiver` (reschedules everything after a reboot — AlarmManager
  entries don't survive one), and `AlarmRingtoneService` (foreground service that plays
  sound/vibration and shows the full-screen notification).
- **`ui/`** — Compose screens: the list, single-alarm editor, and alarm-series editor
  (with a live preview of every time that will be generated), plus `RingingActivity`,
  the full-screen lock-screen UI shown when an alarm fires.
- **`viewmodel/`** — `AlarmViewModel`, exposing a `StateFlow` of alarms/series to the UI.

### How an alarm series becomes real alarms

`AlarmSeries.expandTimes()` turns (start, interval, duration) into a list of times.
`AlarmRepository.saveSeries()` deletes any previously generated child `Alarm` rows for
that series and regenerates them from scratch, so editing a series' interval or duration
later just re-expands and re-schedules — you don't need to touch individual alarms.
Each child alarm gets its own row, its own `AlarmManager` entry (keyed by its database
id), and rings completely independently.

### Permissions you'll be prompted for

- **Exact alarms** (Android 12+): the app checks `canScheduleExactAlarms()` on launch and
  sends you to system settings if it's not granted yet — without this, alarms can be
  delayed by the OS.
- **Notifications** (Android 13+): needed to show the alarm notification/full-screen intent.
- **Full-screen intent**: used so the ringing screen can appear over the lock screen.

## Known gaps / things you may want to extend

- No custom per-alarm sound picker yet — it uses the device's default alarm sound
  (`soundUri` is already modeled on `Alarm`/`AlarmSeries`, so wiring up a `RingtoneManager`
  picker dialog is a small addition).
- No snooze-count limit — snooze always adds 10 minutes and can be repeated indefinitely.
- The enable/disable switch is all-or-nothing (on indefinitely / off indefinitely) — there's
  no "skip just this one occurrence" option yet for a repeating alarm or series.
- No launcher app icon design beyond a simple placeholder vector — swap
  `ic_launcher_foreground.xml` / `ic_launcher_background.xml` for real artwork whenever
  you like.
- Widget/quick-settings tile, and Doze-proofing beyond `setAlarmClock()`, aren't included.
