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
- **Release-note bullets are single unwrapped lines.** GitHub's release
  renderer joins manually-wrapped lines and keeps the continuation indent,
  producing stray multi-space gaps mid-sentence. One line per bullet, however
  long; let the renderer wrap.
- **Published release tags are immutable.** Never move/re-point a tag that
  has shipped — a follow-up fix gets a patch tag (`V1.5.1` after `V1.5`),
  not a rewritten `V1.5`. Moving tags silently changes what a version means
  depending on download date, and the workflow only fires on release
  *creation* anyway, so a moved tag wouldn't even rebuild.
- **Never bulk-overwrite a fresh clone from an older session workspace.** The
  repo routinely advances between sessions; a session-local working copy may
  be several fixes behind. Apply changes onto a fresh clone. (A wholesale
  rm+cp sync from a stale workspace very nearly reverted entries #3–#8's
  fixes once.)
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

Entries 0.x are backfilled from the original build-out chat and predate
entry #1.

0.1. **Full-screen ringing only worked when the phone was locked.** Two
   platform behaviors, not bugs in the app: `startActivity()` from a
   background service is silently blocked when the app isn't foregrounded
   with the screen on (the original code did exactly this and appeared to
   work only because the locked case took a different path), and
   full-screen-intent notifications are *deliberately* downgraded by Android
   to heads-up when the device is unlocked and in active use (same as
   incoming calls). The only reliable "take over the screen while in use"
   path is an overlay window (`SYSTEM_ALERT_WINDOW`). Sideload caveat:
   Android 13+ blocks granting that permission behind "Restricted
   settings" (app info → ⋮ → Allow restricted settings) for any
   non-Play-Store install. Without the permission everything still rings —
   only the screen-on case degrades to heads-up.

0.2. **"Dismiss next alarm" (upcoming-alarm notification) no-op'd — twice.**
   Root cause both times: the notification recomputes "what's next" purely
   from the DB row, so any fix that doesn't persist state is invisible to it.
   First attempt only re-armed AlarmManager for a later time → recompute
   found the same untouched occurrence and re-posted immediately. Real fix:
   persist `skipOccurrenceMillis` (exact epoch of the skipped occurrence),
   honor it inside next-trigger computation, clear it when a legitimate
   occurrence fires. Second gap: one-shot alarms (no repeat days) have no
   next occurrence to skip *to* — dismiss must set `enabled = false` in the
   DB. Same session also made one-shots flip to disabled after ringing at
   all (previously the list toggle stayed "on" for an alarm that would never
   ring again).

0.3. **Debug keystore is committed on purpose.** AGP generates a different
   debug key per machine/CI-run, so every Actions build was
   signature-mismatched with the installed app (uninstall required per
   update). Fixed keystore in `keystore/`, standard `android`/`android`
   credentials — not secrets. Public-repo trade-off, consciously accepted:
   anyone can extract the key and sign an APK Android treats as "same app",
   but exploiting that requires targeted sideloading onto the specific
   device; there's no update channel to poison. Landing this required one
   manual uninstall on-device.

0.4. **App version comes from the release tag.** `versionName`/`versionCode`
   are Gradle properties (`-PversionNameOverride`/`-PversionCodeOverride`);
   the workflow injects them only for release-triggered builds (tag →
   versionName, Actions run number → versionCode, which must be a
   monotonically increasing int and can't come from the tag). Plain pushes
   keep the hardcoded gradle defaults, so artifact builds from `main` show a
   stale version — expected. Known nit: the workflow strips the tag prefix
   with `${TAG#v}`, which only matches lowercase `v`; the capital-`V`
   tagging convention above passes through unstripped, so the app would show
   "V1.5.6" verbatim. Fixed in entry #9.

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

9. **App displayed the raw tag ("V1.5.7") as its version.** The known nit
   from 0.4: the workflow's `${TAG#v}` prefix-strip only matches lowercase
   `v`, but the tagging convention is capital `V`, so every release since
   the version-override mechanism landed shipped with the `V` embedded in
   `versionName`. Fix: `${TAG#[vV]}` — glob bracket pattern strips either
   case. Workflow-only change; takes effect from the next release tag, no
   app code touched.

10. **App missing from the DND access list — #7 was incomplete.** The
   permission-request flow correctly opened
    `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`, but Android only lists
    apps there that declare `android.permission.ACCESS_NOTIFICATION_POLICY`
    in their manifest — which was never added. So the settings screen
    opened, but the app couldn't appear in it and the grant was impossible;
    `isNotificationPolicyAccessGranted` stayed false forever and the ramp
    kept silently degrading during DND. Fix: declare the permission in
    `AndroidManifest.xml` (it's a normal-protection manifest declaration,
    not a runtime dialog — the manual settings toggle is still how it
    actually gets granted). Lesson for future "special access" permissions:
    the settings-launch code is only half the flow; the manifest
    declaration is what makes the app eligible.

11. **Preventive guards from a full-codebase review (no reported symptom).**
    Three unguarded calls violated the "never crash rather than ring" rule:
    `setAlarmClock()`/`setExactAndAllowWhileIdle()` throw SecurityException if
    the user revokes "Alarms & reminders" on Android 12/13 (would have crashed
    the boot-time reschedule loop, silently killing every alarm); both now
    degrade to an inexact `set()`. And `MediaPlayer.stop()` in `stopRinging()`
    throws IllegalStateException if the player hit an async error mid-ring,
    crashing the dismiss; now guarded, release() attempted regardless.

12. **Snoozed repeating alarms evaporated on reboot or app update.** The
    deliberate design from #3 (repeating snooze = AlarmManager-only, never
    persisted) collided with BootReceiver, which rebuilds AlarmManager purely
    from the DB on BOOT_COMPLETED *and* MY_PACKAGE_REPLACED -- so a reboot or
    an app update mid-snooze silently reverted the alarm to its next weekly
    occurrence. Given how often this app ships updates, the update path was a
    live oversleep risk. Fix: new nullable `snoozeUntilMillis` column;
    scheduling honors it while it's in the future, AlarmReceiver clears it on
    fire, and edits/toggles clear it too (a snooze computed against a
    pre-edit schedule shouldn't survive the edit). "Dismiss next alarm" on a
    snoozed alarm now clears the snooze instead of setting a skip marker,
    since the snooze override is consulted before skip. The upcoming
    notification now formats its time from the actual trigger millis rather
    than the stored hour/minute, which for a snoozed alarm would be wrong.
    First schema change shipped with a real Room Migration (4->5); see the
    policy note in AlarmDatabase -- destructive fallback is now last-resort
    only, and any future version bump without a Migration wipes all alarms.

## Restarting this project in a new chat

Generate a brand-new GitHub PAT first (repo scope, `matinoneil/AlarmClock`
only) — treat any previously-pasted token as compromised. Then:

> Continuing work on matinoneil/AlarmClock. Fresh token: `<token>`. Clone the
> repo and read PROJECT_NOTES.md for context before starting. [bug/request]

That's it — no need to paste history into the chat itself; it lives in the
repo now.
