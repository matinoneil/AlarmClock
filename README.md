# AlarmClock

An Android alarm clock app built with Kotlin and Jetpack Compose. Requires
Android 8.0+ (API 26).

The app exists mainly for one feature: **alarm series** — batches of fully
independent alarms generated from a single definition.

## Alarm series

A series is defined by a start time, an interval, and a duration — "from 07:00,
every 5 minutes, for 45 minutes" — and expands into ten real alarms. Each is
fully independent: dismissing 07:00 does nothing to 07:05, so there is no
snooze chain to accidentally kill half asleep. And because all ten come from
one definition, moving the whole wake-up routine is a single edit — change the
start time and every alarm regenerates. One switch toggles the whole series;
weekday repeat, sound, ramp, snooze, and vibration apply to every alarm in it.

## Timers

The Timers tab holds saved, reusable presets — duration, label, sound,
vibration — started and stopped with the same switch alarms use. A running
timer counts down live on its card and in a notification with **+30 s**,
**−30 s**, and **Stop** buttons. The notification is silent; the ring at the
end is as loud as any alarm, with the same full-screen UI. Running timers
survive reboots; one that would have ended while the phone was off is quietly
reset instead of ringing hours late.

## Reminders

The Reminders tab holds notification reminders: a note plus a date and time.
When it's due, the phone shows a notification — no ringing — with **Done** and
**Snooze** buttons. Snooze opens a small floating menu over whatever app is in
front, with options that adapt to the time of day (in 1 hour, this/tomorrow
morning, afternoon, evening, in 24 hours, or any picked date and time).
Reminders can repeat: daily, weekly on chosen days, monthly on a date, monthly
on a weekday ("the 2nd Tuesday", "the last Friday"), or yearly — each
optionally every Nth day/week/month/year. Marking a repeating reminder done
completes only that occurrence; it comes back at the next one. An unhandled
reminder re-notifies once a day, survives reboots, and one that came due while
the phone was off still shows up afterwards. Completed one-time reminders stay
as faded history at the bottom of the list.

## Other features

- **Single alarms** with weekday repeat, per-alarm sound, snooze, vibration,
  and a volume ramp that climbs from quiet to full over configurable seconds.
- **Pause until a date** — silence an alarm or a whole series (vacation mode);
  it resumes by itself on the chosen day, so re-enabling can't be forgotten.
- **"Rings in" labels** — every enabled alarm and series shows how far away
  its next real ring is, snoozes and skips included.
- **Skip next occurrence** from the upcoming-alarm notification, without
  disabling the alarm.
- **Bedtime reminder** — an optional notification N hours before the next
  alarm, with a customizable message.
- **Settings** — separate defaults for series, single alarms, and timers, each
  with a one-tap apply-to-all; plus JSON backup and restore of everything.
- **Full-screen ringing UI** over the lock screen and (with the overlay
  permission) over other apps.
- **Reliability** — everything is rescheduled after reboots and app updates,
  interrupted rings resume, and failures degrade rather than go silent (broken
  ringtone URI → system sound; blocked ramp → full volume).
- Swipe or tap between the Alarms, Timers, and Reminders tabs; next-alarm home
  screen widget; Material You launcher icon. The app requests no network
  permission — it cannot phone home.

## Install

Download the APK from the [latest release](../../releases/latest) and install
it. Builds are signed, so updates install over the previous version. The app
requests its permissions on first launches. On Android 13+, "display over
other apps" is behind *Allow restricted settings* (Settings → Apps →
AlarmClock → three-dot menu); without it, alarms still ring but show as a
heads-up notification while the phone is in use.

## Architecture

`data/` holds the Room entities, DAOs, repository, and real migrations; `alarm/`
holds the schedulers, receivers, and the foreground ring service (timers ring
through the same service; their countdown notification is OS-rendered, so a
running timer costs no process time); `ui/` the Compose screens; `widget/` the
home screen widget. A series becomes real alarms via `expandTimes()` — saving
regenerates its child rows, each with its own `AlarmManager` entry.

Design history and working notes live in [PROJECT_NOTES.md](PROJECT_NOTES.md).
