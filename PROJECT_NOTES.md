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

21. **Snooze no-ops if the ringing alarm's row was deleted mid-ring
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
    never silently do nothing. Implemented as described: `ringingSnapshot`
    field set at the top of startRinging, resurrect branch at the top of
    snooze(), with a Log.w naming old and new ids.

22. **Fresh install still stacks the notification dialog on top of
    the first settings screen (review finding 20c, #15 incomplete).** The
    POST_NOTIFICATIONS runtime dialog fires unconditionally in onCreate,
    outside #15's one-screen-per-launch chain. Intended fix: fold it into
    the chain as the first link. Caveat handled: after two denials Android
    stops showing the dialog (launch() no-ops straight to a denied
    callback), which would stall the chain at notifications forever — so
    cap the ask at 2 attempts via a SharedPreferences counter, after which
    the chain proceeds to the settings screens.

23. **Feature: Timers tab with saved timer presets.** A "Timers" tab now sits
    next to "Alarms" (selected tab bold/full-strength, other dimmed, animated
    color + horizontal slide via AnimatedContent; the tab row lives in a new
    HomeScreen that owns the single Scaffold and the shared + FAB, with
    AlarmListScreen refactored into a Scaffold-less AlarmListContent).
    Presets are a new `timers` Room table (DB v5->6 with a real CREATE TABLE
    Migration -- the alarms tables are untouched, so existing alarms survive);
    a running countdown persists its exact end epoch in `runningUntilMillis`,
    armed by TimerScheduler/TimerReceiver mirroring the alarm path (guarded
    setAlarmClock -> inexact set() fallback; no PendingIntent request-code
    collision with alarms despite shared ids, since the receiver component
    differs). The ring reuses AlarmRingtoneService via EXTRA_TIMER_ID and a
    transient synthetic Alarm -- full-screen UI, overlay, marker-based
    interrupted-ring recovery (marker gained an is-timer flag) all carry over.
    Deliberate choices: timers are dismiss-only (snooze has no countdown
    meaning and the snooze path is alarm-shaped; a stray ACTION_SNOOZE during
    a timer ring is treated as dismiss); a timer that fires with its row gone
    still rings with defaults; BootReceiver re-arms running timers still in
    the future but quietly resets expired ones to idle (a kitchen timer
    ringing hours late is noise, per #13's philosophy); saving a preset does
    NOT auto-start it, diverging from the alarms' save-enables rule #18,
    since the toggle is what starts a countdown and auto-running on save
    would surprise anyone setting up several presets; editing a running
    preset stops it (a countdown against the pre-edit duration is
    meaningless). Known pre-existing limitation unchanged: one notification
    id means simultaneous rings (alarm + timer) were never really supported.

24. **Removed the per-row trash icon from the list cards (alarms, series,
    timers).** Requested UX change: deletion is rare, and the always-visible
    icon cost row width and invited accidental taps next to the toggle.
    Deleting now lives solely in each edit screen's top-bar action (all three
    editors already had it, with the same confirmation dialog), so the
    list-level confirm dialogs and onDelete plumbing went too. If quick
    deletion is ever missed, the natural re-add is long-press or swipe on the
    card rather than bringing the icon back.

25. **Feature: ongoing notification for running timers with +30 s / -30 s /
    Stop actions.** One silent (IMPORTANCE_LOW, own channel), ongoing
    notification per running timer (id 3000 + timer id, clear of the ringing
    1001 and upcoming 2001 ids, so several countdowns coexist), posted by a
    new TimerNotificationManager. The visible countdown is the notification
    chronometer (setWhen(runningUntilMillis) + setUsesChronometer +
    setChronometerCountDown): the OS renders and ticks it, so a running
    timer still costs zero process time -- which is also why the three
    buttons are broadcasts to TimerReceiver (nothing else is alive to
    receive them). They share the fire PendingIntent's component and request
    code but carry distinct actions, so filterEquals keeps all four apart.
    +30/-30 re-derive everything from the DB row (a tap racing a natural
    fire or an in-app stop finds an idle row and just tidies the
    notification); -30 past zero disarms the stale AlarmManager entry and
    reuses the exact fire path so a timer only ever ends in a ring one way.
    Stop is byte-for-byte the list toggle's off behavior. Lifecycle: posted
    on start and on boot re-arm (notifications don't survive reboots),
    updated on adjust, cancelled on stop/fire/delete/edit-of-running.
    Untested caveat flagged for on-device review: whether the collapsed
    notification shows the chronometer prominently varies by OEM skin --
    if it's unreadable on the real device, the fallback is re-posting with
    the remaining time in the title from a lightweight ticker, which was
    deliberately avoided first-pass for battery reasons.

26. **Countdown notification was buried in the "Silent" section.** #25 used
    IMPORTANCE_LOW, which on modern Android means no status bar icon and a
    slot in the collapsed Silent section (two swipes to see) -- the opposite
    of what a live countdown is for. Fix: IMPORTANCE_DEFAULT channel with
    sound null + vibration off (soundless but prominent), PRIORITY_DEFAULT
    on the builder, and setSilent(true) removed -- that flag alone re-demotes
    a notification to the Silent section on Android 10+ regardless of
    channel importance, which is easy to miss. Channels are immutable once
    created on a device, so this needed a new channel id
    (running_timer_channel_v2); createChannel() deletes the legacy id so it
    doesn't linger in the app's notification settings. Lesson: pick channel
    importance deliberately at feature time -- fixing it later always costs a
    channel-id migration.

27. **Feature: "rings in" label on alarm cards.** Enabled standalone alarms
    now append "in 7 h 32 min" (or "snoozed, in 4 min") to the card
    subtitle, driven by AlarmScheduler.peekNextTriggerTime so it honors
    snooze, skip-next, and weekday repeats -- the label always matches what
    will actually ring, and doubles as an at-a-glance catch for the classic
    "set 7 PM instead of 7 AM" mistake. A single minute-granularity ticker
    in AlarmListContent, aligned to wall clock minute boundaries, drives all
    cards; the value rounds UP so a pending alarm never reads "in 0 min".
    Disabled alarms show nothing. Series cards intentionally left alone for
    now (their subtitle is already the densest line in the app); if wanted
    later, the natural form is "next 07:05 in 6 h" from the earliest
    still-pending child.

28. **Release text formatting: no tag/commit footer.** Per the maintainer: release
    texts must NOT end with the "Tag: Vx.y.z · Commit: abc1234" line.
    Provide the release notes body only; the target tag/commit can be
    mentioned conversationally in chat when useful, but never inside the
    release text block itself. Also on versioning: the maintainer increments the
    patch number for most releases regardless of feature size (timers tab =
    V1.8, its follow-ups = V1.8.1/.2, the rings-in feature = V1.8.3) --
    don't suggest semver-style minor bumps; when a suggestion is needed at
    all, assume next patch number on the current line and let the maintainer decide
    when a line bump (1.8 -> 1.9) happens.

29. **Feature: pause a series until a date ("disable until Monday").** For
    vacation weeks: SeriesEditScreen gained a Pause section with a Material3
    date picker (selectable from tomorrow; the picker returns UTC-midnight
    millis, converted to LOCAL midnight of the chosen date -- the classic
    pitfall -- meaning "that date's alarms ring again"). Model:
    `pausedUntilMillis` on AlarmSeries (DB v6->7 ALTER TABLE; alarms
    untouched); a paused series keeps enabled=true but its children are
    saved disabled and unscheduled, so effective state = enabled && not
    paused, which is what the card shows (switch off, dimmed, "Paused until
    Mon 13 Jul" subtitle). Resume is triple-redundant because a pause that
    fails to end is a missed wake-up: (1) SeriesUnpauseScheduler arms
    setAndAllowWhileIdle at the resume time (exempt from the exact-alarm
    permission, no status-bar icon; worst-case Doze delay of minutes is
    harmless at midnight) firing SeriesUnpauseReceiver; (2) BootReceiver
    unpauses overdue pauses and re-arms future ones after reboots; (3)
    AlarmViewModel init reconciles on every app open (covers force-stop,
    which silently wipes AlarmManager entries). All three funnel through one
    SeriesUnpauseOps.unpause so a series can only resume one way, and it
    tolerates racing (already-resumed rows are a no-op). The list switch
    always clears a pause: ON while paused = resume now; OFF = plainly
    disabled -- a disabled series must never spring back to life on its own.
    Saving with a pause keeps the #18 save-enables rule intact
    (enabled-but-silenced); a pause date already in the past is stored as
    null rather than a stale timestamp every reader must re-interpret.

30. **"Rings in" on series cards, replacing the alarm count.** Follow-up to
    #27 per the maintainer: an active series now shows "07:00 - 07:45, every 5 min ·
    in 6 h 12 min · Weekdays" -- the "(10 alarms)" count only appears when
    there's no live countdown (series disabled, or a one-shot series whose
    alarms have all fired; paused series keep their "Paused until" line).
    The value is the earliest peekNextTriggerTime across the series'
    ENABLED children, fed by a new observeSeriesChildAlarms flow in the
    uiState combine -- computing from the series definition instead would
    have ignored child-level snoozes and skip-next, and the label must
    match what will actually ring. Rounds/ticks identically to #27 (shared
    minute ticker and formatter).

31. **Build broken by #29: missing import in SeriesUnpauseReceiver.kt.** The
    new file called AlarmWidgetUpdater.updateAll() without importing it from
    the widget package -- unresolved reference, so the V1.8.4 release build
    (and everything after) failed. Root cause: the file was written from
    memory of BootReceiver's body without copying its import list, and the
    development sandbox cannot compile Android projects, so the error only
    surfaced in CI. Fix: the one-line import; a scan of every file changed
    since V1.8.3 found no siblings. Lesson for future sessions: when
    creating a NEW file that mirrors an existing one, diff its imports
    against the file it mirrors before pushing -- cross-package symbols
    (widget.AlarmWidgetUpdater, MainActivity) are the ones that bite, since
    same-package references resolve silently. The Actions log download API
    is blocked from the sandbox (results-receiver host not allowlisted);
    diagnosing build failures means review, so prevention is cheap and cure
    is slow.

32. **Don't wait on CI.** Per the maintainer: after pushing (or creating a release),
    do NOT poll the Actions run for completion -- he watches the Actions tab
    himself and will report failures. Push, state what was pushed, move on.
    (Checking the API for the failure *reason* when the maintainer reports a broken
    build is still fine -- it's the sleep-and-poll loop that's unwanted.)

33. **Paused series presented as "off", and the switch destroyed the pause.**
    Reported by the maintainer: enabling a series then pausing it from the editor
    "just turns the series off". Root cause: #29 deliberately styled the
    paused card identically to disabled (switch unchecked, 0.5 alpha, low
    container) -- so a successful pause was indistinguishable from the
    feature failing, and the obvious reaction (tap the "off" switch back
    on) was defined as resume-now, silently destroying the pause and
    completing the illusion. Fix: paused is now visually enabled-but-
    silenced -- switch stays ON (it reflects `enabled`), enabled container,
    mid 0.75 alpha, and a tertiary-colored "Paused — rings again Mon 13
    Jul" line above the normal subtitle. Switch OFF still disables and
    clears the pause (a master off must never spring back to life);
    resume-now is the editor's Clear + Save. All "until <date>" wording
    became "rings again <date>" -- "until Monday" didn't answer whether
    Monday rings (it does: resume is 00:00 of the picked day) -- and the
    DatePicker got a custom title, "Pick the first day alarms ring again",
    so the semantics are answered inside the picker itself. UX lesson
    recorded: never style two states identically when one is "working as
    asked" and the other is "not doing anything"; the user's only feedback
    channel is that pixel difference.

34. **Feature: Settings screen (default sounds, apply-to-all, backup/
    restore).** Cog icon in the home top bar (actions slot, right of the
    tabs) -> new "settings" route. Contents: (a) separate default sounds
    for alarms and timers (SettingsStore / SharedPreferences), applied at
    CREATION time only -- new-item editors prefill from them, edits keep
    the item's own choice, and ring-time null still falls back to the
    system sound; (b) "Apply to all alarms & series" / "Apply to all
    timers" behind confirm dialogs -- bulk UPDATE queries, no re-arming
    since sound doesn't affect scheduling; (c) backup/restore as versioned
    JSON (org.json, no new dependency) through SAF Create/OpenDocument.
    Backup contains series (with pause), standalone alarms, timer presets,
    and the two defaults; transient state (snooze, skip-next, running
    countdowns) is deliberately excluded -- moments, not configuration --
    and series children aren't serialized at all (they regenerate through
    saveSeries on restore, which also re-arms or nulls pauses via the
    normal path). Restore REPLACES everything after a confirm dialog, and
    the file is parse-validated BEFORE the confirm is even offered, so a
    corrupt file can never destroy data first. Restore honors each
    alarm's backed-up enabled flag (deliberately NOT via
    saveStandaloneAlarm, whose editor semantics always schedule). Caveat
    noted in-app: content:// sound URIs may not resolve on another device;
    the ring path degrades to the system sound.

35. **Timer notification button handlers raced each other.** the maintainer reported
    that playing with a 10-min timer's +-30 s buttons seemed to activate an
    11-min alarm/timer once, unreproducible. Investigation found no path
    that touches a DIFFERENT preset (request codes and receiver components
    are all per-id and distinct), but DID find that TimerReceiver's
    handlers were completely unserialized: every broadcast launched an
    independent IO coroutine that read-modify-wrote the same DB row and
    re-armed AlarmManager. Interleavings included lost +-30 updates and,
    nastier, adjust-vs-fire: handler A crosses zero and rings (row ->
    idle) while handler B, holding pre-ring state, writes
    runningUntilMillis back and re-schedules -- the timer springs back to
    life and rings again shortly after, i.e. a phantom activation. Fix: a
    file-level Mutex serializing fire/adjust/stop across all receiver
    instances; every handler already re-reads state from the DB, so the
    race loser now sees the truth and no-ops. (fire() is called from
    adjust() inside the lock -- the Mutex is non-reentrant, so fire must
    never itself lock.) The "wrong preset" observation stays UNVERIFIED:
    if it recurs after this fix it's a different bug -- re-report with the
    exact preset list and tap sequence.

36. **Timer notification layout: countdown and "rings at" swapped.** Per
    The maintainer: the live countdown was in the small header timestamp slot and
    "Rings at HH:MM" was the body text -- backwards for the notification's
    whole point. Now a DecoratedCustomViewStyle custom layout
    (notification_timer.xml) puts a 28sp Chronometer (countDown mode,
    elapsedRealtime base) in the body with the label under it, and "Rings
    at HH:MM" moved to setSubText in the small header slot. Still zero
    process time -- the OS ticks the Chronometer in RemoteViews exactly as
    it did in the when-slot. Text appearances use androidx.core's
    TextAppearance.Compat.Notification styles so colors adapt across
    light/dark and OEM skins; if some skin renders the custom view badly,
    that's the first place to look.

37. **Collapsed timer notification clipped the label line.** The two-line
    custom layout (#36) didn't fit the collapsed content height on the maintainer's
    skin -- the "Timer" line under the chronometer rendered cut off. Fix per
    The maintainer: the label TextView is gone; the layout is chronometer-only. A
    non-blank custom label now rides in the header subText ("Rings at 21:35
    · Tea"); unlabeled timers show no word at all. Reinforces #36's
    warning: the collapsed custom-view area is barely one comfortable line
    tall on some skins -- don't put a second line there.

38. **Build broken by #37: "--" inside an XML comment.** The rewritten
    layout comment in notification_timer.xml used the double-hyphen prose
    style these notes are written in ("countDown mode -- zero process
    time"). A double hyphen is ILLEGAL inside XML comments per the XML
    spec, and aapt2 fails the whole build on it. The Kotlin side of the
    commit was fine; the diff looked so trivially safe that the XML
    comment was the last suspect. Fix: rephrased the comment (colon
    instead). Lesson: these notes' "--" writing style must NEVER leak
    into XML comments -- Kotlin comments tolerate it, XML does not. When a
    build breaks on a "can't possibly fail" diff, check the resource
    files' comments before the code. (V1.8.8's first release was created
    on the broken commit; deleted and re-created on the fix.)

39. **Timer displays sat on 00:00 before ringing.** Reported by the maintainer: the
    countdown reaches 00:00 visibly before the ring. Root cause: both the
    card and the notification chronometer FLOOR the remaining time, so
    00:00 renders for the entire final second while the deadline hasn't
    arrived, and ring startup latency (receiver -> service -> sound, a few
    hundred ms) stacks on top. Fix: ceiling everywhere -- the card computes
    (remainingMs + 999) / 1000, and the notification shifts the Chronometer
    base by +999 ms (the OS widget floors, so a shifted base renders the
    ceiling). Displays now read 00:01 until the timer actually fires;
    00:00 only shows during genuine ring startup. Deliberately NOT fixed
    by firing early: a 10-minute timer must ring after 600 s, not 599.
    Same principle as #27's "never in 0 min" rounding for alarms.

40. **Swipe between the Alarms and Timers tabs.** Per the maintainer: the tab
    content is now a HorizontalPager instead of AnimatedContent -- swiping
    the list area drags between tabs with the finger, tab taps
    animateScrollToPage the same state, and the tab highlight follows
    pagerState.targetPage so it flips mid-swipe at the halfway point
    rather than after the settle. PagerState is saveable, preserving the
    rotation/return-from-editor behavior the old rememberSaveable int
    provided. The FAB reads currentPage (not targetPage): its action must
    match the page actually under the finger, not the one being previewed.
    Vertical list scrolling inside pages coexists via the pager's
    orientation locking.

41. **Build broken by #40: Pager needs @ExperimentalFoundationApi on this
    BOM.** compileReleaseKotlin failed with "This foundation API is
    experimental" on every Pager reference. Root cause: HorizontalPager /
    rememberPagerState / PagerState members are still
    @ExperimentalFoundationApi in foundation 1.6.8 (BOM 2024.06.00); they
    only stabilize in foundation 1.7. The session confidently assumed
    "stable since 1.6" -- wrong, and unverifiable in the sandbox since it
    can't compile. Fix: @OptIn(ExperimentalFoundationApi::class) on
    HomeScreen. Lessons: (a) when introducing ANY new Compose API family,
    check its experimental status against the PINNED BOM version, and when
    in doubt add the OptIn -- a redundant OptIn is a warning, a missing one
    is a broken build; (b) the Actions log-download endpoints
    (results-receiver.actions.githubusercontent.com, *.blob.core.windows.net)
    are blocked by the sandbox allowlist -- the maintainer pastes the red lines on
    request, or those hosts can be added to the egress settings for direct
    access.

42. **Release delete-and-recreate can leave a draft that isn't "Latest".**
    The recreated V1.8.10 ended up in draft state with no release-event
    workflow run and no APK; drafts are invisible to /releases/tags/{tag},
    excluded from the Latest badge (V1.8.9 kept it), and display apart on
    the releases page -- which read as "V1.8.10 shows up after V1.8.9".
    Cause not fully proven (created via API with draft:false; something in
    the delete/recreate + workflow interaction flipped it). Fixed by
    PATCHing draft:false + make_latest:"true". Standing practice from now
    on: after ANY release recreation, verify three things via the API --
    the release is not a draft, /releases/latest points at it, and a
    release-event run fired. If the APK is missing after a few minutes,
    publish-PATCH the release to re-fire the workflow.

43. **Root cause of the V1.8.10 APK saga: workflow triggered on release
    [created] only.** Publishing an existing draft emits 'published', not
    'created', so the draft-publish PATCH from #42 never triggered a
    build -- the release sat published, marked Latest, with no APK and no
    run. Resolution for V1.8.10: delete the release (tag kept) and create
    a fresh published release on the same tag -- a direct create emits
    'created', which the workflow version AT THE TAG'S COMMIT still
    listens for (release-event workflows run the file from the tagged
    commit, so trigger fixes only help tags that contain them). Permanent
    fix: trigger changed to types [published], which fires exactly once
    for both direct creates and draft publishes; deliberately NOT
    [created, published], since a direct create emits both and would
    double-build. Also answered: the releases page sorts by release
    created_at, so a PATCHed old release keeps its timestamp and can sit
    below newer-created ones despite being marked Latest -- recreation
    resets it to the top.

44. **Feature: pause-until-date for standalone alarms.** The series pause
    (#29/#33), now on single alarms -- but DELIBERATELY simpler: no unpause
    receiver, no reconcile, no redundancy. `pausedUntilMillis` on Alarm
    (DB v7->8 ALTER TABLE); nextTriggerTime floors its reference point at
    pausedUntilMillis while the pause is active (snoozes included, so a
    stale snooze can't ring inside a pause), so AlarmManager is armed
    directly at the first post-pause occurrence and reboots re-arm it like
    any alarm -- the pause ends passively with nothing to fail. Works for
    repeating alarms (skips occurrences before the resume day) and
    one-shots (rings at the first HH:MM on/after it). Widget, upcoming
    notification, and rings-in labels are pause-aware for free since they
    all read peekNextTriggerTime. UI mirrors #33: shared PauseEditSection
    extracted from SeriesEditScreen into its own file (ONE copy of the
    UTC->local-midnight conversion) and used by both editors; AlarmCard
    shows the tertiary "Paused - rings again <date>" line, 0.75 alpha,
    switch stays ON, rings-in suppressed while paused; the switch clears a
    pause either way (same rule as series). saveStandaloneAlarm nulls a
    past pause date; backup gained the field with a tolerant optional read
    (pre-#44 backups restore fine, format version unchanged). Process
    near-miss worth remembering: the Alarm-entity patch in the batch
    SILENTLY no-op'd (python str.replace with a mismatched anchor), which
    would have broken the build -- caught only by grep-verifying that every
    replacement actually landed. That verification (grep each expected
    marker after a patch batch) is now standing practice alongside #31's
    import mirroring.

45. **Feature: full alarm defaults in Settings (ramp, snooze, vibrate)
    with one apply-to-all.** The Settings "Alarm sound" section became
    "Alarm defaults": default sound, volume ramp seconds, snooze minutes,
    and vibrate, persisted to SettingsStore as they're edited. Same
    creation-time-only rule as #34: new alarms/series prefill from them
    (id == -1 only), edits keep the item's own values, and one "Apply
    these to all alarms & series" button pushes all four onto every
    existing row behind a confirm dialog that spells out exactly what
    will be set. Bulk UPDATEs on alarms + series; none of the four fields
    affect scheduling, so nothing is re-armed. Timers stay sound-only
    (no ramp/snooze on timers). Backups carry the three new settings with
    tolerant optional reads -- pre-#45 files restore fine. No DB change.

46. **Decision: no in-app updater; the app has NO INTERNET permission and
    that's now a deliberate property.** the maintainer considered an in-app
    update-check (feasible: repo is public, so unauthenticated
    releases/latest + FileProvider install would work) and chose to skip
    it. Key reason to keep it skipped: the app currently declares no
    INTERNET permission, meaning it provably cannot phone home -- a rare
    and valuable privacy property. Do NOT add INTERNET for any future
    feature without flagging this trade-off to the maintainer explicitly.
    External updaters (e.g. Obtainium pointed at the GitHub repo) cover
    the update-check use case with zero code and zero permission change.

47. **Feature: bedtime reminder notification.** A quiet, dismissible
    notification N hours (setting, default 8, off by default) before the
    next enabled alarm rings: "Alarm at 07:00 - bed now for 8 h of sleep".
    BedtimeNotificationManager mirrors UpcomingAlarmManager one-to-one:
    soonest peekNextTriggerTime over ALL enabled alarms (standalone +
    series children -- automatically pause/snooze/skip-aware), an
    AlarmManager check (exact with guarded inexact fallback; only a
    notification, late-is-fine) waking BedtimeReceiver at the bedtime
    moment, refreshed from notifyChanged, the check itself, and
    BootReceiver. The 30-minute grace rule keeps the message honest: a
    bedtime moment more than 30 min in the past at refresh time (alarm
    created only 3 h out with an 8 h window) posts nothing, because "bed
    now for 8 h" would be a lie; the next occurrence re-arms after the
    alarm fires. Settings section with enable switch + hours field
    (refreshes live on change); both settings carried in backups with
    tolerant reads. Own silent channel (soundless, no vibration,
    IMPORTANCE_DEFAULT per #26's lesson -- visible in the status bar, not
    buried in the Silent section). Ids: notification 2002, request code
    999002. No DB change.

48. **Bedtime reminder made audible + custom message.** Per the maintainer: the
    bedtime channel now uses the default notification sound and vibration
    (the app's one intentionally non-silent status notification). Editing
    the channel IN PLACE was safe only because #47 never shipped in any
    release, so the channel existed on no device -- had it shipped, this
    would have required a _v2 channel id per #26. Also added: a settings
    text field for a custom notification message; blank = default text,
    and with a custom message the factual "Alarm at HH:MM" moves to the
    header subText so it stays visible. Message carried in backups
    (tolerant read).

49. **Settings defaults split into three categories: series, alarms,
    timers.** Series and single alarms now have separate default sets
    (sound, ramp, snooze, vibrate each) with separate apply-to-all
    actions; timers gained a vibrate default beside the sound (the only
    other per-timer setting). SettingsStore's series getters FALL BACK to
    the alarm keys until a series key is explicitly written, so values
    The maintainer configured under the old unified settings seed both categories
    rather than resetting. Bulk-apply DAO queries are now properly scoped:
    standalone = WHERE seriesId IS NULL; series = alarm_series rows PLUS
    child alarms WHERE seriesId IS NOT NULL (the children are what ring --
    updating only the definition rows would have been a silent no-op on
    actual ringing behavior); timer = sound + vibrate. Old unscoped
    sound-only bulk methods removed after a caller grep confirmed none
    remained. Editors prefill from their own category. Backup carries the
    five new settings with tolerant reads that fall back to alarm values,
    matching SettingsStore. Three settings sections, three confirm
    dialogs each naming exactly what they touch. No DB change.

50. **Feature: Reminders tab (notification reminders with repeat, snooze
    presets, persistent + daily re-remind).** Third tab beside Alarms/Timers
    (pager 2->3). A reminder is text + a date/time that fires a
    HIGH-importance NOTIFICATION (not a ring) with Done and Snooze actions.
    Shipped as designed: new `reminders` Room table (DB v8->9 CREATE TABLE,
    alarms untouched) with a three-state lifecycle -- pending (scheduled),
    active (fired, notification showing until Done), done (faded history at
    the bottom of the list, Clear-history action). Repeats: daily/weekly
    (+weekday set)/monthly-by-date/monthly-by-weekday(Nth or last)/yearly,
    each with an every-N interval; next-occurrence rolls from the SCHEDULED
    time via Calendar arithmetic (DST keeps wall-clock time), with a
    runaway-guarded catch-up loop (a daily reminder completed three days
    late lands on today's slot, on-pattern). A separate snoozedUntilMillis
    overrides scheduling without moving the pattern reference (#12's split).
    Done on a repeating reminder rolls it back to pending at the next
    occurrence -- completing this week's never kills the series; one-shots
    go to history. The editor derives pattern params FROM the picked first
    date (monthly-by-date takes its day; monthly-by-weekday offers "Nth
    <day>"/"last <day>" computed from it; weekly aligns dueAt onto the
    selected days at save) so the pattern can never contradict the first
    occurrence. Persistence: setOngoing (best-effort on 14+), BootReceiver
    re-posts active notifications and re-arms pending ones (a reminder that
    came due while the phone was off fires late -- unlike timers, a late
    reminder is wanted), and while active a 24 h re-remind alarm re-posts in
    case of an accidental swipe. Snooze opens a small dialog-themed
    translucent activity over whatever app is in front (Tasks-style;
    Theme.AlarmClock.SnoozeDialog) with time-adaptive presets (in 1 h,
    today/tomorrow 09/12/18 as applicable, in 24 h, pick date & time --
    today's slots hide within 10 min of passing). Ops serialized behind a
    Mutex in ReminderOps, the single state-change path (#35's lesson);
    every handler re-reads the row inside the lock. Notification ids
    4000+id (clear of 1001/2001/2002/3000+); one AlarmManager slot per
    reminder serves fire and re-remind alike, the receiver deciding from
    row state. Backup gains a reminders array (tolerant reads; ACTIVE
    restores as PENDING-overdue so the restore path re-fires it). Session
    note: the original build chat died mid-implementation with the working
    tree ~70% done -- the pushed [OPEN] entry plus a preserved container let
    the next session resume instead of reverse-engineering; the two-phase
    rule paid for itself a second time.

51. **Reminder notification/list polish.** Per the maintainer: (a) the
    notification's "Due today HH:MM" content line is gone -- the reminder
    text is the title AND the BigTextStyle body (long texts expand fully
    instead of truncating), because a due-today stamp is redundant at the
    moment it fires: the notification arriving IS the due signal. The due
    stamp survives only as header subText and only once the due DAY has
    passed (the daily-re-remind case, where "Due Mon 20 Jul" genuinely
    informs); on the day itself the header shows nothing. The list card's
    "Today 09:00 · in 2 h" subtitle is deliberately untouched -- there the
    schedule is what you're browsing for. (b) The "Done" heading over the
    history section is gone; the fade already says what those rows are, and
    Clear history stays alone, right-aligned. (c) README lost the Building
    section.

52. **Clear history got a confirm.** Per the maintainer: the reminders tab's
    Clear history button wiped all done rows on a single tap with no undo
    path. Now the same confirmation dialog every destructive action in the
    app already has, and it states the count ("This removes all N completed
    reminders"), so a fat-finger next to the top done card costs nothing.

53. **Reminder text shown twice in the expanded notification.** #51's
    BigTextStyle keeps the content title above the big text in the expanded
    view, and both were the reminder text -- so expanding showed it twice.
    Fixed with setBigContentTitle("") blanking the big-form title: collapsed
    shows the text once (as the title), expanded shows it once (as the body).

54. **Recurring reminder "doesn't go away" when checked off in the app.**
    Diagnosis landed on (b): the path was correct all along (checkbox ->
    ReminderOps.markDone -> notification cancelled, occurrence rolled) and
    the reminder staying in the list is BY DESIGN -- completing this week's
    must not kill the series, per the maintainer's original spec -- but the
    tap gave zero feedback: the card sits in place and only the subtitle's
    date quietly changes, so it reads as a no-op. Fix: checking off a
    repeating reminder shows a snackbar ("Done -- next Tue 21 Jul, 09:00"),
    computed from the same nextOccurrenceAfter the ops layer uses.
    SnackbarHost now lives in HomeScreen's Scaffold, available to all tabs.
    One-shots already had visible feedback (fade + sink to history). If the
    maintainer ever wants recurring done-occurrences to visit the faded
    history between occurrences instead: recommended against (a card that
    fades out and later pops back), but this is where to revisit.

55. **Delete a reminder -> history, not erasure.** Per the maintainer:
    deleting a reminder (recurring included) now lands it in the faded
    history instead of removing it from the database. Delete on a
    PENDING/ACTIVE reminder cancels its notification/scheduling and sets
    STATE_DONE (repeat fields kept, so the faded card still describes
    itself); delete on an already-DONE reminder, and Clear history, remain
    the real erase. Editing a history card and saving re-arms it, so
    history doubles as an undo path. The logic moved from the repository
    into ReminderOps.delete behind the mutex (it had been the one reminder
    state change outside the lock -- #35's discipline now covers all of
    them). Editor dialog wording matches the semantics: "Move to history?"
    for live reminders, "Delete permanently? This can't be undone." for
    history rows.

56. **Clear history moved to Settings.** Per the maintainer: the Clear
    history button left the reminders list -- history now reads as pure
    content, faded cards only. Settings gained a Reminders section whose
    Clear history button shows the count inline ("Clear history (7)…"),
    disables at zero, and keeps #52's count-stating confirm. The Backup
    blurb also finally mentions reminders (they'd been IN the backup since
    #50; the copy lagged).

57. **Swiped-away reminder notifications come back sooner (configurable).**
    Per the maintainer: a swiped notification used to wait for the 24 h
    re-remind. Now the notification carries a deleteIntent (fires exactly on
    user dismissal -- and NOT on the app's own cancel() calls, so
    Done/snooze/delete can't self-trigger it) routed through the receiver to
    ReminderOps.onSwipedAway, which rearms the reminder's single scheduler
    slot at now + reminderReshowMinutes (SettingsStore, default 30, floor 1;
    Settings field in the Reminders section). When it fires, the normal
    ACTIVE re-post path runs and re-arms the daily re-alert. A Done racing
    the swipe resolves behind the mutex: non-ACTIVE rows make the swipe a
    no-op, and markDone's cancel/reschedule overwrites the slot anyway.
    Still-visible notifications keep the 24 h re-alert; same notification id
    everywhere, so never a duplicate. The setting rides in backups
    (tolerant read, default 30 for old files).

58. **Re-show delay of 0 = "permanent" (instant, full-alert).** Per the
    maintainer (revised in follow-up: NOT silent -- "I want full
    notification, call it permanent but I know it isn't technically"): the
    #57 re-show setting accepts 0, meaning a swiped notification re-posts
    IMMEDIATELY at full alert (sound/vibration/heads-up) -- undismissable in
    practice, restoring true persistence on Android 14+ where setOngoing
    alone no longer blocks the swipe, and the ding-on-every-swipe is the
    point: the swipe should visibly and audibly not work. It also rearms
    the daily re-alert slot. Non-zero values still go through the scheduler
    slot as before. Floor moved 1 -> 0 in SettingsStore and the backup
    read; the Settings copy calls the 0 case "permanent". A silent variant
    was briefly implemented and reverted same-session -- post() takes no
    alert flag; if silence is ever wanted, that's the shape it had.

59. **Per-reminder re-alert interval.** Per the maintainer: the 24 h
    re-remind (the nag while a notification sits unhandled) is now
    per-reminder. New renotifyMinutes column, DB v9->10 ALTER TABLE with
    DEFAULT 1440 so every existing reminder keeps the daily behavior;
    fire() and the instant-swipe re-arm both use it (boot funnels through
    fire(), so reboots follow automatically). Editor gained a "Remind
    again" section with a preset dropdown (every 15/30 min, 1/3/6/12 h,
    once a day); an out-of-preset stored value is offered as-is so it
    never silently changes on save. Backup rides with tolerant read
    (default 1440). The Settings swipe-delay copy now says re-alerts
    follow each reminder's own schedule. The global swipe re-show delay
    itself stays a single Settings value.

60. **Per-reminder swipe re-show delay.** Per the maintainer (clarifying
    #59: THIS is what he'd meant, though the nag interval stays as a
    welcome extra): how quickly a swiped-away notification returns is now
    settable per reminder in the editor. Sentinel design: reshowMinutes
    column, RESHOW_FOLLOW_GLOBAL (-1) = follow the global Settings value
    (the migration default, so every existing reminder keeps its current
    behavior), 0 = permanent, N = minutes -- same fallback pattern as the
    series defaults (#49). DB v10->11 ALTER TABLE, deliberately NOT folded
    into 9->10 since push-built v10 APKs may be on-device. The editor's
    "Remind again" section now holds both dropdowns (#59's nag interval
    and this); backup rides with tolerant read (default -1); the Settings
    field is reframed as the default each reminder can override.

61. **One-and-done toggle.** Per the maintainer: a per-reminder switch to
    disable BOTH persistence mechanisms -- "Keep reminding until done",
    default ON. Off: the notification posts once as a normal dismissable
    notification (setOngoing false, autoCancel true), fire() arms no
    re-alert, and swiping it away counts as DONE (one-shot -> history,
    repeating -> rolls to the next occurrence) so nothing sits ACTIVE
    forever. Done/Snooze buttons remain for explicitness. The editor's
    "Remind again" section leads with the switch and hides the #59/#60
    dropdowns when off, with the subtitle spelling out the swipe-means-done
    behavior. New persistent column (DEFAULT 1 -- existing reminders
    unchanged), DB v11->12; backup with tolerant read. Mutex note honored:
    markDone's body moved to a private markDoneLocked, called by both
    markDone (under withLock) and onSwipedAway's one-and-done branch --
    a nested markDone call would have deadlocked the non-reentrant Mutex.

62. **Off options for both persistence mechanisms.** Per the maintainer
    (his case: swipe-protection ON, nag OFF): both editor dropdowns gained
    "Off" -- renotifyMinutes 0 = "Off, never re-alerts"; reshowMinutes
    RESHOW_OFF (-2) = "Off, a swipe dismisses it". The two are fully
    orthogonal: nag-off + reshow-on sits silent but returns after a swipe;
    nag-on + reshow-off keeps re-alerting on schedule but a swipe sticks
    until the next re-alert re-posts it. The global Settings reshow gained
    an enable switch (reminderReshowEnabled pref, default true; delay
    field disabled when off) so App-default can itself mean off --
    per-reminder overrides ignore the switch. No DB migration; the
    sentinels fit the INTEGER columns. fire() and the permanent-repost
    re-arm skip scheduling at renotify 0. Known boundary, deemed correct:
    boot re-posts ACTIVE notifications regardless of reshow-off (it can't
    tell a swipe from a reboot loss). Backup: widened tolerant reads
    (renotify floor 1 -> 0, reshow floor -1 -> -2), new settings boolean
    with optBoolean(true).

63. **Editor/Settings UX batch.** Per the maintainer: (a) the reminder
    text field now auto-capitalizes (KeyboardCapitalization.Sentences).
    (b) The When section's two date/time buttons became ONE dropdown
    button (same UI as the repeat dropdown) with the snooze dialog's
    adaptive standard times -- in 1 h, today 09/12/18 while more than
    10 min ahead, tomorrow 09/12/18, in 24 h -- plus "Pick date & time"
    opening the picker flow, which now CHAINS date -> time as one gesture
    (the old separate time button is gone, so the chain is the only path
    to the time picker). (c) The Bedtime reminder section moved above
    Timers in Settings (brace-matched block swap; order now Alarm series,
    Single alarms, Bedtime, Timers, Reminders, Backup).

64. **Repeat overhaul (all five proposals shipped).** Per the maintainer:
    (1) monthly-on-weekday has full ordinal x day pickers -- First..Fourth/
    Last x Mon..Sun plus Outlook's pseudo-days "Day (any)", "Weekday
    (Mon-Fri)", "Weekend day" (sentinels WEEKDAY_ANY/WORKDAY/WEEKEND =
    8/9/10 in repeatWeekday) -- 40 combos where there were 2; (2)
    monthly-on-date has a free 1..31 picker plus "On the last day of the
    month" (LAST_DAY_OF_MONTH = -1 in repeatDayOfMonth, the true last day
    rather than a clamped 31); (3) new REPEAT_YEARLY_WEEKDAY = 6 ("last
    Sunday of March"), month anchored from dueAt, reusing the weekday/
    weekOfMonth columns; (4) weekly quick-pick buttons Weekdays/Weekends/
    Every day; (5) a "Next: ..." preview of the next 3 occurrences,
    computed by the same math the scheduler uses. Design inversion landed:
    the pattern is the source of truth; buildCandidate() is the single
    place editor state becomes a Reminder (Save and the preview share it)
    and it aligns dueAt forward onto the pattern via the entity's new
    alignDueAtToPattern (time of day kept, one period stepped if the
    resolved day already passed). setToNthWeekdayOfMonth generalized to
    setToNthDaySpecOfMonth (counting loop; the 4th of any spec always
    exists in a month, so it can't overflow). No DB migration; backup
    reads widened (repeatType ..6, repeatWeekday ..10, repeatDayOfMonth
    from -1). describeRepeat covers the new forms; the old derive-from-
    date WeekOfMonthDropdown and the editor's local alignToWeekdays are
    gone.

65. **Yearly-on-a-date got a day picker.** Per the maintainer: the type
    exposed no day control (the date above was the whole pattern), reading
    as lacking next to its siblings. Now: an explicit day dropdown (1..the
    picked month's actual length -- Feb shows 29 in a leap year -- plus
    "On the last day of <month>"), month still anchored from the picked
    date, mirroring monthly-on-a-date. Storage reuses repeatDayOfMonth for
    REPEAT_YEARLY; 0 = legacy rows keep deriving the day from the date
    (saving a legacy reminder converts it to the explicit form, same day),
    so no migration and old backups behave identically. "Last day of
    February" correctly means the 28th or 29th via resolveDayOfMonth.
    Changing the picked month under a day it can't hold (31 -> Feb) snaps
    the day to the month's last via LaunchedEffect. describeRepeat:
    "Yearly on Jul 15" / "Yearly on the last day of Feb".

## Restarting this project in a new chat

Generate a brand-new GitHub PAT first (repo scope, `matinoneil/AlarmClock`
only) — treat any previously-pasted token as compromised. Then:

> Continuing work on matinoneil/AlarmClock. Fresh token: `<token>`. Clone the
> repo and read PROJECT_NOTES.md for context before starting. [bug/request]

That's it — no need to paste history into the chat itself; it lives in the
repo now.
