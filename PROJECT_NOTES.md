# PROJECT_NOTES.md

Working notes for whoever (human or Claude) picks up this codebase next.
Purpose: skip re-discovering context that isn't obvious from reading the
current code/diffs alone. File structure, class names, etc. are NOT
duplicated here deliberately — read the actual repo for that, it's always
accurate and cheap to do via a clone. This file is for the *why*, the
history, and standing working agreements that a fresh read of the code won't
surface.

**Maintenance rule — two-phase bug logging.** When a bug is identified and
about to be worked on: **first** add an entry to the "Bug/change history"
section below, titled with an `[OPEN]` prefix and describing the symptom,
suspected cause, and intended approach — and push that alone, *before*
touching any code. Then do the fix, and in the same commit as the fix,
remove the `[OPEN]` prefix and rewrite the entry in the established format
(what broke, why, what the fix actually was, any rejected approaches worth
remembering). Rationale: a chat session can die mid-fix (it has — see entry
#13's origin), and a pushed `[OPEN]` entry means any fresh session knows
exactly what was in flight instead of reverse-engineering intent from a
half-edited working tree. Cost is one extra push per bug; accepted. Trivial
typo-level changes and pure doc edits are exempt. Batch reviews may push
several `[OPEN]` entries in one go. Keep entries terse — a paragraph, not an
essay. This file is what replaces re-explaining context in chat every
session, so it needs to stay current or that purpose is defeated.

## Who's doing what

Division of labor: **Claude makes all code changes and pushes them; the
maintainer creates the GitHub release (which triggers the CI build) and
live-tests the resulting APK on the phone.** There is no local development
environment anymore — no Termux, no local git, no Android SDK, no emulator,
on either side. Claude (via its tool environment) cannot compile or run the
app — changes are written from reading the code + Android API docs/source
comments via web search, and are only actually verified once the maintainer
installs a real build on the phone. Treat any "this should work" as
unverified until the maintainer confirms. Since pushed code only reaches a
device through a release, assume everything on `main` past the latest tag is
untested.

## Standing working agreements

- **Don't wait on CI after pushing.** The maintainer verifies the build; no
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
  Always tell the maintainer to revoke it (Settings → Developer settings → Personal
  access tokens) after the session, regardless of whether anything looks
  wrong. Tokens only need `repo` scope for this repository.
- **Release-note bullets are single unwrapped lines.** GitHub's release
  renderer joins manually-wrapped lines and keeps the continuation indent,
  producing stray multi-space gaps mid-sentence. One line per bullet, however
  long; let the renderer wrap.
- **Deliver release text as a raw markdown code block in chat.** The text
  itself has always been markdown (`### Fixed` etc.), but pasting it as
  normal chat prose means the chat UI renders it and the maintainer's
  copy loses the `###` marks — GitHub then shows flat text instead of the
  bold section headings. Fence it so it copies verbatim.
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
   alarms can't be silenced by an app). Made the ramp inaudible on the test
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

13. **An interrupted ring vanished forever -- fatal for one-shots.** One-shot
    alarms flip to `enabled = false` in the DB the instant they fire (0.2),
    so a process kill, crash, or reboot mid-ring left them silent and off
    with nothing to bring them back. Fix: a SharedPreferences marker
    (`alarm_ringing_state`) set when ringing starts and cleared only on an
    explicit dismiss/snooze (deliberately NOT in onDestroy, which also runs
    on system kills). Recovery on two paths: the service is START_STICKY, so
    a null-intent restart resumes the marked alarm; and BootReceiver re-fires
    it after a reboot/app-update. Both paths honor a 30-minute grace window --
    resurrecting an alarm hours later (phone was off all night) would be
    worse than staying quiet. BootReceiver clears the marker *before*
    restarting the service so a crash-looping service can't re-trigger from
    boot forever. The notification also gained a Snooze action; previously
    Dismiss was the only option there, which was the sole control surface
    when Android downgrades the full-screen intent to a heads-up (screen on,
    no overlay permission). Snooze intents now carry the alarm id so snoozing
    works even from a freshly restarted process where the in-memory id is gone.
    Marker writes use commit(), not apply(): the async apply() can lose the
    write in an abrupt kill -- the exact event the marker must survive -- and a
    lost *clear* would re-ring a dismissed alarm at next boot. The timestamp is
    stamped fresh on every real firing and preserved only on resumes, so an old
    interrupted ring can't age a new ring out of its recovery window.

14. **Series times wrapping past midnight fired on the wrong day.** A
    repeating series like Monday 23:50, interval 15, duration 30 expanded to
    members at 00:05 and 00:20 that kept "Monday" as their repeat day -- so
    they rang Monday morning, almost a full day before the 23:50 member,
    instead of in the night to Tuesday. expandTimes() now reports each
    member's day shift and saveSeries rotates the ISO weekdays accordingly.
    One-shot series were unaffected: "next future occurrence" already lands
    wrapped times on the following day by construction.

15. **First run stacked up to four settings screens on top of each other.**
    The permission checks in MainActivity each fired their own startActivity
    back-to-back (exact alarms, full-screen intent, DND access, overlay), so
    a fresh install buried the user under a pile of settings screens. Now an
    else-if chain requests only the first missing one per launch, ordered by
    how alarm-critical the permission is; the next comes up on the next
    launch. Still no per-permission "asked before" persistence -- an
    ungranted permission reprompts every launch, which is deliberate for now
    since all four materially affect whether/how alarms ring.

16. **UI modernization pass (icon, app screens, ringing UI).** Not a bug — a
    requested visual refresh. What changed: template purple palette replaced
    with a warm amber/deep indigo brand scheme (fallback only — dynamic
    color remains the default on Android 12+, so screen colors follow the
    wallpaper there); expanded Material 3 type scale plus a shared
    `ClockTextStyle` with tabular figures so clock digits align; list screen
    got a collapsing `LargeTopAppBar`, 24dp-radius tonal cards using the
    surfaceContainer roles, dimmed disabled alarms, and Weekdays/Weekends
    shorthand in day labels; edit screens regrouped into rounded tonal
    sections with circular per-day toggles (`DayOfWeekSelector`, replacing
    the FilterChip rows — same Mon=1..Sun=7 semantics) and suffix-labeled
    number fields; ringing screen redesigned with a hue-tinted dark
    gradient, 96sp tabular time, pulsing icon, and a white pill Dismiss
    with a quiet text Snooze. The gradient is deliberately forced dark with
    white content instead of using the primary/onPrimary pair — in dark
    dynamic schemes primary is *light* and onPrimary *dark*, so the naive
    pairing over a darkened background is unreadable; this was caught
    pre-push, keep it in mind if restyling that screen. OverlayAlarmWindow
    got the matching look (dark amber gradient, pill buttons) via plain
    GradientDrawables — pure view construction, no new OS calls, honoring
    the never-crash rule. New adaptive launcher icon (gradient indigo
    night background, warm gradient clock face at 7:00) including a
    `<monochrome>` layer so Android 13+ themed icons work; and a
    values-night activity theme (`android:Theme.Material.NoActionBar`) so
    startup no longer flashes white in dark mode. No DB, scheduling, or
    service-logic changes; everything is unverified-until-installed as
    usual, and the icon especially needs an eyeball on a real launcher.

17. **Scroll jank (~10fps reported) in the alarm list after the #16 UI
    pass.** Two compounding causes. (a) The big one: every APK ever shipped
    was a *debuggable* build — the CI workflow ran `assembleDebug` and
    attached that to releases. Compose performs drastically worse with
    `debuggable=true` (no ART optimization, live inspection hooks); the
    pre-#16 UI was simply light enough to hide it. Fix: CI now runs
    `assembleRelease`, with the release build type signed by the *same*
    committed keystore — identical signature, so release APKs install over
    the existing debug-signed installs with no uninstall. Minify stays OFF
    deliberately: R8 in alarm-critical code is a separate risk to take on
    its own, not bundled into a perf fix. (b) #16's
    `exitUntilCollapsedScrollBehavior` resized the LargeTopAppBar every
    scroll frame, remeasuring the whole Scaffold + LazyColumn per frame —
    tolerable in release builds, heavy in debug. The app bar is now a
    static LargeTopAppBar; if a collapsing bar is ever wanted again, test
    it on-device in a release build first. Lesson: any future "app feels
    slow" report should start with "which variant is installed?" — and
    local `assembleDebug` artifacts are for inspection only, not perf
    judgment.

18. **Saving an edited alarm now enables it.** Requested UX change, reversing
    an earlier deliberate choice (the edit screens used to preserve the
    existing on/off state on save). In practice, editing an alarm almost
    always means intending to use it — the common flow is grabbing a
    disabled alarm, changing its time, saving, and expecting it to ring.
    Pressing Save in either edit screen (single alarm and series alike, for
    consistency) now sets `enabled = true`; the list-screen toggle remains
    the way to disable. One-line change per screen at the save call site;
    save still goes through the existing repository path, so scheduling and
    the #12 snooze-clearing behavior are untouched, and a series save
    regenerates its children as always — now enabled. Note the asymmetry
    left on purpose: opening an alarm and saving *without* changing
    anything also enables it; deemed acceptable since Save is an explicit
    action, but if that ever annoys, the fix is comparing against `existing`
    before forcing the flag.

19. **Dead space above the "Alarms" title.** Side effect of #17: a
    LargeTopAppBar without a scroll behavior keeps its full expanded
    height (~152dp) permanently, wasting a large band above the title on
    the list screen — half a design choice (#16's large-title look) and
    half an accident (#17 removed the collapse that justified the height).
    Fix: plain pinned TopAppBar (64dp); the space goes to the alarm list.
    If the big-title aesthetic is ever wanted back, a collapsing
    LargeTopAppBar is viable again now that shipped builds are
    non-debuggable — but test on-device first, per #17.

20. **Review finding: silent catch in AlarmRingtoneService.** Full codebase
    review (same spirit as #11) found the codebase healthy overall; the one
    rule violation was the catch wrapping the DB read + startRinging() in
    onStartCommand, which swallowed the exception with only a comment — the
    sole spot in the file violating the log-never-swallow rule. Fixed with
    a Log.e naming the alarm id.
    Review observations logged for the record, no code change planned
    without a decision: (a) editing a series while one of its child alarms
    is ringing deletes the child row, so Snooze on that ring silently
    no-ops (Dismiss unaffected); (b) a series with tiny interval + huge
    duration can generate hundreds of AlarmManager entries with no cap or
    warning; (c) on a fresh install the POST_NOTIFICATIONS runtime dialog
    and the first settings screen from the permission chain still appear
    together (#15 only serialized the settings screens); (d) restoring the
    pre-ramp stream volume on dismiss overwrites any manual volume-button
    change made mid-ring; (e) serviceScope deliberately has no cancel in
    onDestroy — cancelling would abort the async snooze DB write triggered
    just before stopSelf, so its absence is load-bearing; (f) compose BOM
    2024.06 / targetSdk 34 are aging — upgrades work but each deserves its
    own on-device-tested release, not a drive-by.

21. **[OPEN] Snooze no-ops if the ringing alarm's row was deleted mid-ring
    (review finding 20a).** saveSeries regenerates children by deleting all
    rows; if one is ringing, snooze() does `getAlarm(id) ?: return` — sound
    stops, nothing is rescheduled, user thinks they snoozed. Oversleep
    risk. Intended fix: the service keeps an in-memory snapshot of the
    Alarm it started ringing; if the row is gone at snooze time, insert a
    fresh one-shot alarm at now+snoozeMinutes built from the snapshot
    (label/sound/ramp/vibrate preserved) and schedule it. Deliberately
    also applies when the user *deleted* the alarm mid-ring: hitting
    Snooze means "ring again" — losing a wake-up is worse than
    resurrecting a deleted alarm as a one-shot. Snapshot gone too (process
    death + row gone): fall back to a bare 10-minute one-shot; snooze must
    never silently do nothing.

22. **[OPEN] Fresh install still stacks the notification dialog on top of
    the first settings screen (review finding 20c, #15 incomplete).** The
    POST_NOTIFICATIONS runtime dialog fires unconditionally in onCreate,
    outside #15's one-screen-per-launch chain. Intended fix: fold it into
    the chain as the first link. Caveat handled: after two denials Android
    stops showing the dialog (launch() no-ops straight to a denied
    callback), which would stall the chain at notifications forever — so
    cap the ask at 2 attempts via a SharedPreferences counter, after which
    the chain proceeds to the settings screens.

## Restarting this project in a new chat

Generate a brand-new GitHub PAT first (repo scope, `matinoneil/AlarmClock`
only) — treat any previously-pasted token as compromised. Then:

> Continuing work on matinoneil/AlarmClock. Fresh token: `<token>`. Clone the
> repo and read PROJECT_NOTES.md for context before starting. [bug/request]

That's it — no need to paste history into the chat itself; it lives in the
repo now.
