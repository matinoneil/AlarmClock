# PROJECT_NOTES.md

Working notes for whoever (human or Claude) picks up this codebase next.
Purpose: skip re-discovering context that isn't obvious from reading the
current code/diffs alone. File structure, class names, etc. are NOT
duplicated here deliberately — read the actual repo for that, it's always
accurate and cheap to do via a clone. This file is for the *why*, the
history, and standing working agreements that a fresh read of the code won't
surface.

**Maintenance rule: every push that fixes a bug or changes behavior should
add a short entry to the "Bug/change history" section below**, following the
existing entries' format (what broke, why, what the fix actually was, any
rejected approaches worth remembering). Keep entries terse — a paragraph, not
an essay. This file is what replaces re-explaining context in chat every
session, so it needs to stay current or that purpose is defeated.

## Who's doing what

Martin develops this entirely from his phone (Termux + git + GitHub Actions),
no local Android SDK, no emulator. Claude (via this tool environment) also
has no Android SDK/emulator access and cannot compile or run the app —
changes are written from reading the code + Android API docs/source comments
via web search, and are only actually verified once Martin installs a real
build on his phone. Treat any "this should work" as unverified until Martin
confirms.

## Standing working agreements

- **Don't wait on CI after pushing.** Martin verifies the build himself; no
  need to poll `actions/runs` or sleep-and-check after `git push`. Push and
  move on to the next thing (including release text — see below).
- **Write release text immediately after every push, unprompted.** Don't wait
  to be asked. Cover everything changed since the last release tag. Match
  existing style: `### Fixed` / `### Added` / `### Improved` / `### Note`
  sections, plain-language user-visible symptoms (not implementation
  detail), and a closing note on whether DB fields changed (existing alarms
  should never silently get wiped by a schema change — flag it loudly if one
  is ever needed). State the tag name and commit hash it's meant for.
- **Release tagging: plain incremental `Vx.x.x` only** (`V1.5.6`, `V1.5.7`,
  ...) pushed straight as full releases. Pre-release tags
  (`V1.5.x-beta1`/`-beta2`, marked as GitHub pre-releases, promoted to the
  plain tag only after surviving real use) were discussed as worth adopting
  given how often fixes this project has shipped introduced their own
  follow-up bugs, but **this is not yet in use** — don't assume it's active.
  The CI workflow already builds for any release regardless of the
  pre-release flag, so switching later needs no workflow changes.
- **Any GitHub token pasted into a chat is burned the moment it's sent.**
  Always tell Martin to revoke it (Settings → Developer settings → Personal
  access tokens) after the session, regardless of whether anything looks
  wrong. Tokens only need `repo` scope for this repository.
- **Alarm-critical code must never crash or go silent rather than ring.**
  This project's whole bug history (below) is essentially variations on this
  principle being violated and then restored. When touching
  `AlarmRingtoneService` specifically: wrap risky OS calls in try/catch close
  to the call site, log via `Log.w`/`Log.e` (tag `"AlarmRingtoneService"`)
  instead of swallowing silently, and degrade to the next-best behavior
  (ring without a ramp; ring with the default sound; ring without vibration)
  rather than doing nothing or throwing.

## Bug/change history

Chronological. Read top-to-bottom for the reasoning trail — several entries
exist specifically because an earlier fix in this list caused them.

1. **Volume ramp inaudible/duration had no effect.** Original ramp stepped
   real `AudioManager` `STREAM_ALARM` volume index. Devices often have only
   5–15 discrete steps total, so at a low configured alarm volume,
   `totalSteps` could be 1 — "ramp" was a silent wait then an abrupt jump to
   full volume, same for any duration setting.

2. **Fix attempt: pure software gain (`MediaPlayer.setVolume`) — regression.**
   Smooth and duration-accurate, but several Android OEMs ignore/clamp
   per-track gain specifically for `USAGE_ALARM` audio (safety measure so
   alarms can't be silenced by an app). Made the ramp inaudible on Martin's
   device. Lesson: don't trust `setVolume()` alone for alarm audio.

3. **Snooze bug.** One-shot alarms get `enabled = false` in the DB the
   instant they start ringing (so they don't linger "on" forever). `snooze()`
   was reading that already-disabled row, copying it, handing it to
   `AlarmScheduler` — which no-ops for disabled alarms. Nothing was persisted
   either, so the DB-driven upcoming-alarm notification never saw the
   snooze. Net effect: snoozing a one-shot alarm made it vanish. Fix:
   snoozing a one-shot alarm now re-enables + persists its new time to the
   DB. Repeating alarms are handled differently on purpose — NOT persisted,
   since their real weekly schedule must stay intact; only re-pointed via
   `AlarmScheduler` directly for that one occurrence.

4. **Real ramp fix: hybrid approach.** Real stream-volume steps as the
   audible floor/ceiling (guarantees audibility) + per-track gain layered on
   top *within* each hardware step to smooth the climb. Gain math at each
   step boundary is continuity-preserving:
   `gain = ((step-1) + fractionalProgress) / step` — avoids an audible dip
   at step boundaries. An earlier draft that reset gain to near-zero at each
   boundary would have caused a repeating dip-then-rise stutter; caught
   before shipping. If revisiting the ramp, preserve this continuity
   property or re-derive it — it's not obvious from a naive implementation.

5. **Crash on every ramped alarm firing.** The hybrid ramp's
   `setStreamVolume()` calls were unguarded. Confirmed via Android's own
   `AudioManager` source docs: changing stream volume while Do Not
   Disturb/focus mode is active throws `SecurityException` unless the app has
   Notification Policy Access. Ran inside a coroutine with no exception
   handling → crashed the whole process on every ramped alarm. Fix: every
   stream-volume touchpoint wrapped in try/catch, falling back to a
   non-ramped (but still ringing) alarm on failure.

6. **Silent alarm — unrelated root cause, surfaced right after #5.** A
   `content://` URI for a custom sound isn't permanently stable — deleted
   file, moved file, or a library rescan reassigning the numeric MediaStore
   row ID all invalidate it. `MediaPlayer.setDataSource`/`prepare()` then
   throws. The generic catch added in #5 was also swallowing *this* failure
   with no fallback — alarm's notification showed, nothing played. Fix:
   dedicated `createPlayer()` helper returning null instead of throwing; on
   failure, retries once with the device's actual default alarm ringtone
   before giving up.

7. **DND permission gap.** Even after #5's graceful fallback, the ramp itself
   still silently no-op'd during DND because the app never actually
   requested Notification Policy Access — it only handled not having it.
   Added a permission-request flow in `MainActivity`, matching the existing
   pattern for the overlay/full-screen-intent permissions: check
   `NotificationManager.isNotificationPolicyAccessGranted`, launch
   `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` if false.

8. **Widget double back-press.** Widget's launch `Intent` for `MainActivity`
   had no `FLAG_ACTIVITY_NEW_TASK`/`CLEAR_TOP`/`SINGLE_TOP`, so tapping it
   stacked a second `MainActivity` instance on top of any already-open one.
   Fixed by adding those flags to the widget's `PendingIntent`.

## Restarting this project in a new chat

Generate a brand-new GitHub PAT first (repo scope, `matinoneil/AlarmClock`
only) — treat any previously-pasted token as compromised. Then:

> Continuing work on matinoneil/AlarmClock. Fresh token: `<token>`. Clone the
> repo and read PROJECT_NOTES.md for context before starting. [bug/request]

That's it — no need to paste history into the chat itself; it lives in the
repo now.
