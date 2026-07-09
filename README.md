# AlarmClock

**An Android alarm clock that treats ringing as sacred.** Kotlin + Jetpack Compose,
developed entirely from a phone — no laptop, no local SDK, just GitHub Actions as the
build machine.

Every design decision follows one rule: **an alarm must never crash or go silent
instead of ringing.** Broken ringtone URI? It falls back to the system default. Volume
ramp blocked by Do Not Disturb? It rings at full volume instead. Phone died mid-ring?
The alarm comes back after reboot. The full bug-by-bug history behind that rule lives
in [PROJECT_NOTES.md](PROJECT_NOTES.md).

## What it does

- **Single alarms** — a time, optional weekday repeat, per-alarm ringtone, snooze
  length, and vibration.
- **Alarm series** — one entry that expands into many independent alarms: every N
  minutes from a start time, for a set duration (07:00–07:45 every 5 minutes = 10
  separate alarms). Dismissing one never touches the others; edit the series and the
  whole set regenerates. For the heavy sleepers.
- **Volume ramp** — start quiet and climb to full volume over as many seconds as you
  like, instead of a heart attack at 07:00 sharp.
- **Ringing UI that actually appears** — full-screen over the lock screen, and (with
  the overlay permission) over whatever you're doing when the screen is already on,
  where stock Android silently downgrades alarms to a heads-up notification.
- **Survives real life** — reboots, app updates, and interrupted rings all reschedule
  or resume; snoozes persist across all of it. Skip just the next occurrence of a
  repeating alarm straight from its upcoming notification.
- **Home screen widget** showing your next alarm, plus themed (Material You) icon
  support on Android 13+.

## Install

Grab the APK from the [latest release](../../releases/latest) and sideload it. Builds
are signed release builds; updates install straight over the previous version.

Android 8.0+ (API 26). On first launches the app walks you through the permissions
alarms genuinely need, one per launch: exact alarms, notifications, full-screen
intent, and "display over other apps".

> **Sideload quirk:** Android 13+ blocks the overlay permission for non-Play-Store
> apps behind "Restricted settings". To grant it: Settings → Apps → AlarmClock →
> three-dot menu → *Allow restricted settings*, then grant normally. Everything rings
> fine without it — you just get a heads-up instead of the full takeover when the
> screen is already on.

## Build it yourself

Open the folder in Android Studio (Koala+) and run, or fork it and let
[the workflow](.github/workflows/build-apk.yml) build for you — every push produces
an installable APK under the Actions tab. Publishing a GitHub Release tags the build
with the release's version automatically (tag `V1.7` → version `1.7` in app settings).

## Under the hood

Room + a repository as the single source of truth, a `StateFlow`-driven Compose UI,
and `AlarmManager` entries keyed by database id. A foreground service owns the ring:
sound, vibration, ramp, snooze, dismiss, and crash recovery via a persisted
ringing-state marker. `BootReceiver` rebuilds everything after reboots and app
updates. Schema changes ship with real Room migrations — your alarms survive updates.

Curious why something is built the way it is? [PROJECT_NOTES.md](PROJECT_NOTES.md)
is the honest engineering log: every bug, every fix that caused the next bug, and the
working agreements that keep a phone-only project shippable.
