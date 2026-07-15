# AlarmClock

An Android alarm clock app built with Kotlin and Jetpack Compose. Requires
Android 8.0+ (API 26).

The app exists mainly for one feature: **alarm series** — batches of fully
independent alarms generated from a single definition.

## Alarm series

A series is defined by a start time, an interval, and a duration — "from 07:00,
every 5 minutes, for 45 minutes" — and expands into ten real alarms. Each is
fully independent: dismissing 07:00 does nothing to 07:05, so there is no
snooze chain to accidentally kill half asleep. Because all ten come from one
definition, changing the start time regenerates every alarm, and one switch
toggles the whole series.

## Timers

Saved, reusable presets — duration, label, sound, vibration. A running timer
counts down on its card and in a notification with **+30 s**, **−30 s**, and
**Stop**; the ring at the end is as loud as any alarm. Running timers survive
reboots.

## Reminders

Notification reminders: a note plus a date and time, with **Done** and
**Snooze** buttons (Snooze opens a floating menu with time-of-day-aware
suggestions). Repeats cover daily, weekly, monthly by date or weekday
(including "first weekday", "last Friday", "the last day of the month"), and
yearly by date or weekday — each optionally every Nth period, with a live
preview of the next three occurrences while configuring. Each reminder decides
how insistent it is: re-alert on a schedule until marked done, come back after
being swiped away (up to instantly), both, or neither ("one and done" — then a
swipe marks it done). There is always exactly one notification per reminder,
and it survives reboots. Completed and deleted reminders remain as faded
history; Clear history lives in Settings.

## Other features

- Single alarms with weekday repeat, per-alarm sound, snooze, vibration, and a
  volume ramp.
- Pause until a date (vacation mode) with automatic resume; skip next
  occurrence; "rings in" labels showing the time to the next real ring.
- Bedtime reminder N hours before the next alarm.
- Settings with per-type defaults and apply-to-all; JSON backup and restore of
  everything.
- Full-screen ringing UI over the lock screen; everything is rescheduled after
  reboots and app updates; failures degrade rather than go silent.
- No network permission — the app cannot phone home.

## Install

Download the APK from the [latest release](../../releases/latest) and install
it; builds are signed, so updates install over the previous version. On
Android 13+, "display over other apps" is behind *Allow restricted settings*
(Settings → Apps → AlarmClock → three-dot menu); without it, alarms still ring
but show as a heads-up notification while the phone is in use.

## Architecture

`data/` holds the Room entities, DAOs, repository, and migrations; `alarm/` the
schedulers, receivers, and the foreground ring service; `ui/` the Compose
screens; `widget/` the home screen widget. Design history and working notes
live in [PROJECT_NOTES.md](PROJECT_NOTES.md).
