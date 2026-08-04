# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android alarm clock app (Kotlin + Jetpack Compose), package `no.hanss.alarmclock`, minSdk 26 /
targetSdk 34. See README.md for the user-facing feature set (alarm series, timers, reminders,
vacation-mode pause, backup/restore). See PROJECT_NOTES.md for the full "why" history — read it
before starting any non-trivial change.

## No local build/test environment

**There is no Android toolchain on this machine — no JDK, no Android SDK, no `gradle`, no
`gradlew` wrapper in the repo, no emulator.** Claude cannot compile, run, lint, or test this app.
Changes are written from reading the code plus Android API docs, and are only verified once the
maintainer installs a real build on their phone via a GitHub Release.

There *is* a normal local git checkout with working SSH push access (this supersedes the "no local
git, generate a PAT" instructions in PROJECT_NOTES.md, which predate it). So changes can be
committed and reviewed locally before they reach GitHub — but that changes nothing about
verification: local git does not make the app buildable here.

Consequences that follow from this:

- Treat every change as "should work, unverified" until the maintainer confirms — never claim a
  fix works.
- Do not diagnose rendering/layout/gesture/performance issues by reading code alone; several past
  sessions did this and were wrong (see "Dead ends" in PROJECT_NOTES.md). Ask the maintainer for a
  precise on-device symptom instead.
- When mirroring an existing file to create a new one (e.g. a new Receiver), diff its imports
  against the file it mirrors before pushing — cross-package references are what silently break a
  CI build that can't be checked locally (PROJECT_NOTES #31).
- Double-hyphens (`--`) are illegal inside XML comments and fail the build (aapt2); fine in Kotlin
  comments (PROJECT_NOTES #38).
- Compose API stability must be checked against the *pinned* BOM version (2024.06.00 /
  foundation 1.6.8), not assumed from general Compose knowledge — add `@OptIn` when in doubt
  (PROJECT_NOTES #41).

## Build / CI

- `gradle assembleRelease` is what CI runs; there is no local build to run it against, so treat
  Gradle syntax/imports/XML carefully on every edit.
- CI (`.github/workflows/build-apk.yml`) builds on push to main/master, on release publish, and on
  manual dispatch. It only signs with the real release key (from repo secrets `KEYSTORE_B64`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) when those secrets are present; otherwise it
  falls back to AGP's own per-machine debug key, producing an APK that will not install over a
  real device (safe-by-default degrade, not a failure).
- App version comes from the release tag, not from `build.gradle.kts` — the workflow passes
  `-PversionNameOverride`/`-PversionCodeOverride` (tag → versionName stripped of leading v/V,
  Actions run number → versionCode). Plain pushes keep the hardcoded Gradle defaults. **Never
  manually bump versionName/versionCode for a release** — tagging handles it.
- Release tags are plain incremental `Vx.x.x` (capital V), pushed as full releases (no
  pre-release/beta convention in use). Published tags are immutable — a follow-up fix is a new
  patch tag, never a moved tag. **No two-digit segments** — after `V2.3.9` the next tag is `V2.4`,
  not `V2.3.10`; the maintainer asked for this explicitly (PROJECT_NOTES #105).
- **Run `git fetch --tags` before saying anything about what is or isn't released.** Releases are
  created on GitHub, not tagged locally, so this checkout's tag list is routinely behind. A session
  once told the maintainer six commits were unreleased when the tag for exactly those commits
  already existed on the remote. Note that `git rev-parse --short HEAD origin/main` (two refs, one
  call) fails in the maintainer's fish shell with "Needed a single revision" — resolve refs one per
  call.
- Room schema JSON is exported to `app/schemas/` at *build* time (`exportSchema = true`); commit it
  after a schema-changing build so migrations are reviewable.
- `R8`/minification is deliberately **off** (`isMinifyEnabled = false`) — treated as a separate risk
  in alarm-critical code, not bundled into other changes.
- CodeQL scanning was deliberately removed — do not re-add without asking (PROJECT_NOTES #99).

## Division of labor (important — read before assuming you can verify anything)

Claude makes code changes and pushes them; the maintainer creates the GitHub release (which
triggers the CI build) and live-tests the resulting APK on the phone. Do not poll GitHub Actions
after pushing — push, state what changed, and move on; the maintainer watches CI and reports back.
Write release notes unprompted after every push (see PROJECT_NOTES.md's "Standing working
agreements" for the exact format: `### Fixed`/`### Added`/`### Improved`/`### Note` sections,
user-visible language, one bullet per line with no manual wrapping, delivered as a fenced markdown
code block, and — per entry #28 — **no trailing tag/commit footer inside the release text itself**).

Commits are the maintainer's name alone. Attribution is disabled in their Claude Code settings
(`attribution: { commit: "", pr: "" }`), so no `Co-Authored-By: Claude` trailer is added — do not
hand-write one back into a commit message as a courtesy. Commits up to and including `4751e73`
predate this and still carry the old trailer; leave them, since rewriting would mean force-pushing
over an immutable published tag.

Edits land only in this local checkout. Committing and pushing are separate, explicit steps taken
when the maintainer asks — not automatically after a change. Nothing reaches the phone until they
tag a release on top of that.

## Session hygiene

Conversation context does not survive quitting the CLI; the repo is what carries state forward.
Two consequences:

- **Anything worth keeping goes in a file** — PROJECT_NOTES.md for the "why" of a change, this file
  for standing constraints. A conclusion that exists only in a transcript is lost the moment the
  session ends.
- **A prior session is often recoverable** — `claude --continue` resumes the most recent session in
  this directory, `claude --resume` offers a picker. Suggest this before re-deriving context or
  asking the maintainer to re-explain a bug they already described.

## Architecture

Everything lives under `app/src/main/java/no/hanss/alarmclock/`:

- **`data/`** — Room entities (`Alarm`, `AlarmSeries`, `TimerPreset`, `Reminder`), `AlarmDao`,
  `AlarmRepository`, `AlarmDatabase` (current version 14, with a real `Migration` object per
  version bump — see below), `BackupSerializer` (JSON backup/restore), `SettingsStore`
  (SharedPreferences-backed defaults).
- **`alarm/`** — schedulers (`AlarmScheduler`, `TimerScheduler`, `ReminderScheduler`,
  `SeriesUnpauseReceiver`'s companion), `BroadcastReceiver`s that AlarmManager fires into
  (`AlarmReceiver`, `TimerReceiver`, `ReminderReceiver`, `SeriesUnpauseReceiver`, `BootReceiver`),
  the foreground ringing service (`AlarmRingtoneService`, shared by alarms *and* timers via a
  synthetic `Alarm`), and notification managers (`UpcomingAlarmManager`, `TimerNotificationManager`,
  `ReminderNotificationManager`, `BedtimeNotificationManager`).
- **`ui/`** — Compose screens, one per concern (`AlarmListScreen`/`AlarmEditScreen`,
  `TimerListScreen`/`TimerEditScreen`, `ReminderListScreen`/`ReminderEditScreen`,
  `SeriesEditScreen`, `SettingsScreen`, `RingingActivity`, `ReminderSnoozeActivity`). `HomeScreen`
  owns the single Scaffold, the Alarms/Timers tab row (`HorizontalPager`-based swipe, requires
  `@OptIn(ExperimentalFoundationApi::class)` on this BOM), and the shared FAB.
- **`viewmodel/`** — single `AlarmViewModel` bridging all of the above to Compose via one
  combined `uiState` flow.
- **`widget/`** — home screen widget (`AlarmWidgetProvider`, `AlarmWidgetUpdater`).

### Standing invariants worth knowing before touching this code

- **Alarm series**: one definition (start, interval, duration) expands into N fully independent
  `Alarm` rows on save (`saveSeries` deletes and regenerates all children). Editing a series while
  one of its children is ringing deletes that child row out from under the ring — known,
  unresolved sharp edge (PROJECT_NOTES #20). Times wrapping past midnight rotate the stored ISO
  weekday per member (#14) — don't assume all children share the parent's weekday.
  Vacation-mode pause (`pausedUntilMillis`) keeps `enabled = true` but children are saved disabled
  and unscheduled; effective "is it live" is always `enabled && not paused` (#29/#33) — resume is
  triple-armed (inexact alarm at resume time, BootReceiver catch-up, and a reconcile in
  `AlarmViewModel` init) because a missed resume is a missed wake-up.
- **Timers** share the alarm ring path (`AlarmRingtoneService`) via `EXTRA_TIMER_ID` and a
  transient synthetic `Alarm`, but are dismiss-only (no snooze semantics). A running timer's
  countdown is rendered by the OS itself via a notification `Chronometer` — zero process time —
  guarded by a file-level `Mutex` in `TimerReceiver` serializing fire/adjust/stop so racing
  notification-button taps can't resurrect a timer that already fired (#35).
  Countdown displays **ceiling** remaining time, never floor — flooring shows 00:00 for a full
  second before the real deadline (#39).
- **Reminders** always have exactly one live notification per reminder; repeats (daily/weekly/
  monthly-by-date-or-weekday/yearly) recompute next-occurrence purely from the DB row, so any state
  change (skip, snooze, done) must be persisted or the notification logic won't see it. Checking a
  reminder off in the list completes it permanently, even if repeating; "Mark this one done" inside
  the reminder is the only path that advances a repeat while keeping it alive.
- **The "never crash or go silent rather than ring" rule** governs `AlarmRingtoneService` and
  anything in the alarm-firing path: wrap risky OS calls (`setStreamVolume`, `MediaPlayer` state
  transitions, exact-alarm scheduling) in try/catch close to the call site, log via `Log.w`/`Log.e`
  with a tag naming the component, and degrade to the next-best behavior (ring without ramp/
  vibration/custom sound; fall back to an inexact alarm) rather than throwing or no-op'ing
  silently. Nearly the entire numbered bug history in PROJECT_NOTES.md is a variation on this rule
  being violated and then restored — read it before changing this path.
- **One-shot alarms/timers** flip `enabled = false` (alarms) or reset to idle (timers) the instant
  they finish ringing. A `SharedPreferences` marker (`alarm_ringing_state`, written with `commit()`
  not `apply()`) records "currently ringing" so a process kill/reboot mid-ring can resume the ring
  within a 30-minute grace window instead of silently losing it (#13).
- **Room migrations are mandatory**, not optional, from v5 onward — `fallbackToDestructiveMigration()`
  is a last resort that wipes all data, and every version bump ships a real `Migration` object in
  `AlarmDatabase.kt`. Never bump the `@Database(version = ...)` without adding one.
- **Notification IDs are partitioned by feature** to allow simultaneous notifications: ringing
  1001, upcoming-alarm 2001, bedtime 2002, running timers `3000 + timerId`. Reminders and running
  timers/alarms were never designed to all ring at once on one id — know this before "fixing"
  apparent overlap.
- **No network permission** anywhere in the manifest — keep it that way; it's a stated feature
  ("the app cannot phone home").

## Security context (read before touching signing/CI/permissions)

The repo is public. A debug keystore was committed pre-V2.3.1 and its private key is considered
burned; release signing now comes from CI secrets only (see build.gradle.kts comments and
PROJECT_NOTES #89 for the full rotation story) — **never commit a keystore file to this repo
again**, and don't "restore" the old committed-keystore pattern even if an old comment in git
history argues for it. `V2.3.1` is the signing-key rotation boundary: anything at/before `V2.3` is
signed with a now-public key and should be treated as unauthenticated.

## Where the history lives

PROJECT_NOTES.md is a chronological, numbered bug/change log going back to the app's original
build-out, including several "dead end" investigations explicitly marked do-not-reopen. It exists
specifically so context doesn't need to be re-explained every session — when picking up a bug or
feature that sounds like it might have been touched before, search it first (entry numbers are
referenced by later entries when one fix caused another bug). It also documents the maintainer's
process preferences (release-note format, tagging convention, when Claude may cut a release
itself) in its "Standing working agreements" section — those override generic instincts about how
to interact with this repo's GitHub workflow.
