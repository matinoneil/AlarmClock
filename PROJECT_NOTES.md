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

## Read this first — restarting in a new chat

Generate a brand-new GitHub PAT first (repo scope, `matinoneil/AlarmClock`
only) — treat any previously-pasted token as compromised. Then:

> Continuing work on matinoneil/AlarmClock. Fresh token: `<token>`. Clone the
> repo and read PROJECT_NOTES.md for context before starting. [bug/request]

That's it — no need to paste history into the chat itself; it lives in the
repo now.

## Who's doing what

Division of labor: **Claude makes all code changes and pushes them; the
maintainer creates the GitHub release (which triggers the CI build) and
live-tests the resulting APK on the phone.** Amended in practice: Claude may
cut the release itself (POST to the releases API with the PAT) when the
maintainer explicitly asks -- V2.3.4 was created that way. This does NOT change
who verifies: Claude still cannot build or run anything, so a release it cut is
exactly as unverified as one it only wrote notes for. Default remains that the
maintainer releases; do not start cutting releases unasked. The tag must be
capital-V (`V2.3.4`) even when the request says "2.3.4" -- the workflow strips
the leading v/V for versionName and versionCode is the Actions run number, so no
manual version bump is ever needed before tagging. There is no local development
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

## Dead ends - do not reopen

Conclusions only; the evidence is in the numbered entry. Everything here was
proposed, investigated and ruled out. Re-proposing any of it costs a session.

- **Pager consuming vertical drag delta** (HorizontalPager on experimental foundation 1.6.8) - dead, #76: a slow drag tracks the finger 1:1, and the symptom is identical on screens outside the pager.
- **Per-card Modifier.alpha layers / card elevation shadows** - dead, #75: Profile HWUI showed frame spikes app-wide including Settings, which has neither.
- **fillMaxSize viewport/clipping bug in HomeScreen** - never existed, #75. Compose's Column passes the REMAINING space as the max to a non-weighted child, so fillMaxSize() there was already equivalent to weight(1f). V2.1.9 shipped a behavioral no-op and its release notes are inaccurate.
- **OEM per-app refresh-rate cap (60 Hz slow lane)** - dead, #75: the refresh-rate overlay reads 120 across the whole app.
- **#71j, the permission chain moving from LaunchedEffect into onCreate** - not a regression and not the cause, #75. It looks guilty (only UI-adjacent change in V2.1.8) but V2.1.6/V2.1.7 lack it and felt identical; a LaunchedEffect body already ran on the main thread, so the change only moved ~5-30 ms of one-time work from just after the first frame to just before it.
- **Adding androidx.profileinstaller explicitly** - no-op, #75/#76: it is already in the APK transitively via Compose.
- **V2.1.8 as a cause, dependency drift, build variant, classic nested-scroll conflicts, the minute ticker, the rings-in computation** - all ruled out, #73/#75.

Still live: **hypothesis 4, ART optimisation state** (#75/#76) - under test
on-device as of V2.2. Do not ship a release the maintainer would install
during that window; a new APK restarts the clock.

**Rule earned the hard way (#75/#76):** do not diagnose rendering, layout or
gesture behaviour by reading code in the dev sandbox. It cannot build, run or
profile this app; five attempts produced four wrong answers and one wasted
release. What actually worked was on-device measurement and asking the
maintainer for a precise symptom shape.
## THE REPO IS PUBLIC. The signing key WAS in it (resolved at V2.3.1)

Verified from the GitHub API (`private: false`, `visibility: public`) and by
fetching `keystore/debug.keystore` over raw.githubusercontent.com with NO
credentials at all: 200, 2762 bytes. Entry 0.3 documents that keystore as a
deliberate commit so CI can sign reproducibly -- sound for a PRIVATE repo, which
this is not.

`keytool` opens it with the standard password `android`, alias
`androiddebugkey`, CN=AlarmClock Debug, SHA256
7C:93:AE:E4:CF:31:3B:E2:1F:F3:E9:EA:02:17:9E:F4:FF:DF:50:75:64:51:FD:AB:D8:E3:F2:F3:C9:D8:6C:59.
So the private key is fully usable by anyone who downloads it; the file IS the
credential and it has no protection.

WHAT THAT ACTUALLY ENABLES, stated without inflation: anyone can build an APK,
sign it with this key, and Android will accept it as a genuine UPDATE to
no.hanss.alarmclock -- installing over the real app and inheriting its data
directory and granted permissions. It does not touch the GitHub account, and it
cannot reach the device by itself; someone must still get the malicious APK
sideloaded. With 1 star and 0 forks the practical blast radius is close to nil.
A real weakness, narrow exposure, not an emergency.

THE REAL FIX AND ITS COST: generate a new keystore, hold it in GitHub Secrets
(base64) and decode it in the workflow, drop it from the repo. The old key then
cannot update the app, because a signature mismatch blocks install. That
mismatch is also the cost -- the maintainer's phone must UNINSTALL and reinstall,
and per #74 uninstall wipes the database, so BACK UP FIRST. Purging git history
is optional theatre once the key is rotated; the exposed key becomes worthless.

PROCESS FAILURE WORTH KEEPING: an earlier session in this same conversation told
the maintainer the repo was private and stated it as checked. It had not been
checked -- the script printed a hardcoded `True` next to a comment about needing a
PAT, and a PAT works fine on public repos. The wrong belief then propagated into
security reasoning ("private repo, contained problem"). Print what the API
returns, or do not print it.

NO LICENSE FILE either (`license: null`), which for a public repo means all
rights reserved: viewable and forkable on GitHub, no right to use or modify.
Fine if deliberate. Separately, every dependency is Apache 2.0, which obliges
anyone DISTRIBUTING binaries to include the licence text -- currently unmet, and
more visible now that the repo is public. Normal fix is an open-source-licences
screen or a NOTICE file.

## Verified facts about the shipped artifact

Established by DOWNLOADING the released APK and parsing its manifest, not by
reading build files -- worth redoing the same way rather than re-arguing from
config. Done on V2.2.8 (versionCode 249) with pyaxmlparser.

- **NOT debuggable.** The string `debuggable` does not appear in the shipped
  manifest at all, so it defaults to false. CI runs `gradle assembleRelease` and
  the release buildType never sets isDebuggable. This closes a reasonable
  hypothesis for the #76 scroll clunkiness -- ART will not AOT-compile a
  debuggable app, so a debug build would have explained everything. It is not one.
  #17 already moved CI to assembleRelease for precisely this reason.
- **The hand-written baseline profile IS reaching devices.** `assets/dexopt/baseline.prof`
  and `baseline.profm` are both present in the APK, so AGP is compiling
  src/main/baseline-prof.txt in and profileinstaller has something to install.
  Previously assumed; now confirmed.
- **minSdk 26 / targetSdk 34** in the shipped manifest, confirming #79's revert
  landed.
- **R8 IS OFF** (`isMinifyEnabled = false`). This is the one production-compile
  step not being performed, and the last optimisation lever reachable without a
  local Android dev environment. Deliberate per #17's caution about minification
  in alarm-critical code; not a bug, but the honest answer to "are we doing a
  production compile" is "yes, minus minification".


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

66. **Full-screen-intent re-asks after every update.** Reported: the FSI
    permission must be re-accepted after each APK update. Root cause is
    the OS, not the app: Android 14 made USE_FULL_SCREEN_INTENT
    user-revocable and (for sideloaded apps on some builds) resets it on
    package update; MainActivity's launch chain then detected the
    revocation and auto-opened the system toggle -- hence "asked every
    update". Cannot be prevented app-side (Play-store alarm apps keep the
    auto-grant; sideloaded don't). Shipped mitigation: FSI removed from
    the auto-open chain; instead an errorContainer banner sits above the
    tabs while revoked -- "Full-screen alarms are turned off / Android
    turns this off after some updates. Tap to re-enable..." -- tapping
    deep-links to ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT. The state is
    an Activity-level mutableStateOf refreshed in onResume, so the banner
    vanishes the moment the user returns from the toggle. Alarms still
    ring (heads-up + full volume) without the permission, so a banner is
    proportionate; the rest of the launch chain (notifications, exact
    alarms, DND access, overlay) is unchanged.

67. **Denser same-day time suggestions.** Per the maintainer (at 14:00
    the only today option left was 18:00): today's preset slots widened
    from 09/12/18 to 09/12/15/18/21 in BOTH menus -- the editor's When
    dropdown ("Today at 15:00") and the snooze dialog ("This morning /
    Midday / This afternoon / This evening / Tonight") -- still hidden
    within ~10 min of passing, so a slot is always within ~3 h and the
    list thins naturally through the day. Tomorrow stays 09/12/18
    (morning/afternoon/evening) in both, keeping the menus short.

68. **Bed icon for the bedtime notification.** Per the maintainer: the
    bedtime reminder shared the generic alarm status-bar icon. Now it has
    the Material king-bed glyph as a monochrome vector
    (res/drawable/ic_notification_bed.xml, stock path data, white fill --
    status-bar icons render as alpha silhouettes) and
    BedtimeNotificationManager points its setSmallIcon at it. First
    app-local drawable used as a notification icon; alarms, timers, and
    reminders keep their existing system icons. BROKE THE BUILD on first
    push: the vector carried android:tint="?attr/colorControlNormal" --
    an APPCOMPAT attr, and this is a pure-Compose app with NO appcompat
    dependency, so AAPT2 failed resource linking on both the push and
    V2.1.4 release builds (no APK attached). Fix: drop the tint entirely
    (the system tints notification icons anyway). Lesson for all future
    drawables: no ?attr/ references from appcompat/material XML
    namespaces here -- framework ?android:attr/ or nothing. V2.1.4 was
    deleted and recreated on the fixed commit.

69. **Per-category notification groups.** Per the maintainer: each
    notification type should stay its own entry in the shade. They already
    were separate (ids/channels/icons), but Android force-bundles 4+
    ungrouped notifications from one app into a single stack, which can
    visually merge types. Now every builder sets a category group --
    no.hanss.alarmclock.REMINDERS / BEDTIME / ALARMS (upcoming + ringing
    share ALARMS) / TIMERS -- so the system only ever stacks like with
    like: three reminders may stack with each other, but the bed and the
    alarm never join them. minSdk 26, so single-child groups render as
    plain notifications without needing summaries. FIELD-TESTED OUTCOME
    (maintainer, on-device): Android 12+ still draws all of one app's
    notifications as a single collapsible shade section when there's more
    than one -- collapsed row borrows the newest member's icon, expanded
    children render without individual small icons. That's OS shade
    design, NOT actual merging (expanding shows the separate entries),
    and it sits above group keys. DO NOT retry per-category summary
    notifications: on per-app sectioning they change nothing and add
    permanent chrome. The group keys keep their real job (only like
    fuses with like). Accepted state: single-notification case (the
    maintainer's usual) shows each icon cleanly; heads-up popups are
    unaffected either way.

70. **Tapping the Quick Settings alarm chip FIRED the alarm instead of opening
    the app.** Reported: pressing the alarm shortcut in the shade (swipe down
    twice) made the alarm go off on the spot -- and because a one-shot flips to
    `enabled = false` the instant it fires (0.2), the tap also CONSUMED the
    next morning's wake-up. Cause: `AlarmClockInfo`'s second constructor
    argument is the **showIntent**, the intent the SYSTEM launches when the
    user taps that chip -- and both AlarmScheduler.setAlarmManagerEntry and
    TimerScheduler.schedule passed the very same broadcast PendingIntent they
    hand to setAlarmClock as the fire operation. The chip therefore broadcast
    to AlarmReceiver/TimerReceiver: byte-identical to a real firing and
    indistinguishable from one downstream, so every guard and recovery path
    behaved perfectly on an alarm that was never due. Present since
    setAlarmClock was first used; only reachable by tapping the chip, hence
    surviving 69 entries. Fix: a dedicated activity PendingIntent to
    MainActivity as the showIntent (`createShowIntent`, one per scheduler),
    NEW_TASK/CLEAR_TOP/SINGLE_TOP per #8, with its own request-code namespace
    (800000+id for alarms, 810000+id for timers) and a distinct action, so
    filterEquals can never fuse it with the fire intent or with the widget's
    request-code-0 launch intent. No DB, scheduling, or ring-path change --
    the operation PendingIntent handed to setAlarmClock is untouched, so when
    and how alarms actually ring is bit-for-bit as before. Deliberately NOT
    deep-linked to the specific alarm/timer or its tab: the chip means "show
    me my alarms", MainActivity has no tab deep-link plumbing, and adding it
    is its own change. Lesson: any AlarmManager API taking two PendingIntents
    is worth a second look at which one is which -- the wrong assignment here
    type-checked, compiled, and armed alarms correctly.

71. **Full-codebase review batch (#11/#20 spirit): ten findings fixed, no
    reported symptom for any of them.** Reviewed on request after #70; the
    codebase held its guard discipline nearly everywhere, and these were the
    gaps. (a) **Every** BroadcastReceiver used try/finally with NO catch inside
    a bare CoroutineScope(Dispatchers.IO), which has no exception handler -- so
    any throw reached the default handler and killed the PROCESS. Worst case is
    AlarmReceiver, where the reschedule, upcoming-notification refresh and
    widget update all run AFTER startForegroundService: a throw there killed
    the ring it had just started (recoverable via #13's marker, but that is the
    safety net catching a preventable crash) and, for a repeating alarm, could
    leave the next occurrence unarmed. Every receiver now catches, logs, and
    finishes cleanly. Scope grew from the three fire-path receivers to all
    seven on the same reasoning. (b) Vibration passed no attributes, so it
    defaulted to USAGE_UNKNOWN, which DND and silent mode SUPPRESS -- an alarm
    whose sound failed to load (#6) degraded to nothing at all. Now
    VibrationAttributes.USAGE_ALARM on 33+, the deprecated AudioAttributes
    overload below that. Also: the whole vibrator block was unguarded and sits
    BEFORE the overlay show, so a throw (no vibrator hardware makes the
    VibratorManager cast fail) silently cost the ringing UI; wrapped, since
    vibration is the least important of the three ring channels and must never
    take the other two with it. (c) BootReceiver cleared the ringing marker
    with apply() where #13 mandates commit() -- the one place that invariant was
    broken, and the dangerous direction (a lost clear re-rings a dismissed
    alarm at next boot, and the following service start can kill the process
    before an async write lands). (d) exportSchema false -> true, with the
    required KSP room.schemaLocation arg, so a version bump that forgets its
    Migration shows up as a schema diff in review. Destructive fallback STAYS
    per the standing wipe-beats-crash preference. BUILD OUTCOME: V2.1.8 compiled and packaged fine, so both of this batch's
    compile risks are settled -- the KSP room.schemaLocation arg is picked up
    (exportSchema=true fails the build outright without it) and the API 33
    VibrationAttributes reference is fine under its TIRAMISU guard. NOTE:
    schemas/ is emitted at BUILD time, so it only populates once someone builds locally and commits
    it; CI builds won't. (e) allowBackup was true with both rule files empty,
    i.e. the default: the whole Room DB (reminder text, alarm labels) plus
    prefs to Google cloud backup -- in tension with #46's no-INTERNET privacy
    property, and redundant given the app's own JSON backup/restore.
    allowBackup=false; both rule files now carry explicit excludes so
    re-enabling backup later can't silently start uploading the DB. Direct
    device-to-device transfer is left enabled on purpose (stays between the two
    phones, and it's what carries alarms to a new handset). (f) BootReceiver did
    every alarm, series, timer and reminder in one goAsync against a ~10s
    broadcast budget, with the interrupted-ring resume LAST -- the most
    time-critical item behind the longest loop. Reordered strictly
    most-critical-first: ring resume, alarm re-arm, series unpause, timers,
    reminders, then the purely presentational refreshes, so whatever a timeout
    kills is the cheapest thing to lose. This is a mitigation, not a cure; the
    real fix if it ever bites is a short foreground service or WorkManager
    (no new dependency exists for the latter yet). (g) The specialUse service
    lacked its documented PROPERTY_SPECIAL_USE_FGS_SUBTYPE property; added
    (no breakage before, since holding USE_EXACT_ALARM is itself a qualifying
    criterion for the type). (h) WAKE_LOCK was declared and never used
    anywhere; removed. Audio playback holds its own wakelock, so nothing
    depended on it. (i) SCHEDULE_EXACT_ALARM gained maxSdkVersion=32 beside
    USE_EXACT_ALARM. Related note kept for the record: USE_EXACT_ALARM
    auto-grants, so canScheduleExactAlarms() is always true on 33+ and that
    branch of the #15 chain is live only on Android 12/12L. (j) Four unguarded
    startActivity calls in the permission chain (an OEM missing one of those
    settings screens throws ActivityNotFoundException = a crash on launch over
    a permission that only degrades a feature) now go through one
    safeStartActivity helper; and the chain moved out of a
    LaunchedEffect(Unit) inside setContent -- which re-runs on every activity
    recreation, so a rotation re-fired it -- into onCreate under a
    savedInstanceState == null guard, which is what #15 always claimed it did.
    Deliberately NOT fixed per the maintainer: the uncapped series expansion
    reachable from restore (finding 20b). Nothing here changes the DB schema,
    scheduling math, or the ring path.

72. **With no alarm set, the Quick Settings alarm chip opened the stock Clock
    app.** Reported alongside #70 and a DIFFERENT mechanism, which is why #70's
    fix didn't cover it. The chip has two behaviors: with an alarm armed it
    launches the showIntent of whichever app set the SOONEST AlarmClockInfo
    (#70's territory); with nothing armed there is no showIntent, so the system
    fires ACTION_SHOW_ALARMS and resolves it against installed apps. This app
    declared no filter for that action, so it was never a candidate and the
    preinstalled Clock app won by default. Fix: an ACTION_SHOW_ALARMS +
    category.DEFAULT intent-filter on MainActivity, which is what Android's
    alarm-clock guidance asks any alarm app to declare. MainActivity already
    opens on the Alarms tab, so no code change was needed, and there is no new
    attack surface (the activity is already exported for the launcher). Caveats
    outside the app's control: with two handlers installed Android shows a
    disambiguation dialog until the user picks "always", and some OEM Quick
    Settings panels launch their clock package directly rather than resolving
    the intent, in which case no manifest change can redirect it. Deliberately
    NOT also declaring ACTION_SET_ALARM / ACTION_SET_TIMER (the "Assistant, set
    an alarm for 7" handlers): those need an activity gated on
    com.android.alarm.permission.SET_ALARM plus a UI path for
    externally-supplied alarm parameters, i.e. a feature rather than a manifest
    line.
    Related observation logged while answering a charging question, no change
    made pending a decision: **TimerScheduler also uses setAlarmClock**, so a
    running kitchen timer claims the SYSTEM-WIDE next-alarm slot
    (AlarmManager.getNextAlarmClock) -- it draws the status-bar alarm icon and,
    post-#70, owns the chip. Reminders and series-unpause deliberately avoid
    setAlarmClock already; timers arguably should too, since anything keyed on
    "next alarm" (OEM adaptive-charging features, third-party weather/sleep
    widgets) will read a 10-minute timer as the user's wake-up time. The
    trade-off is that setAlarmClock is the most Doze-proof option and carries a
    background-FGS-start exemption the timer ring depends on;
    setExactAndAllowWhileIdle is subject to quotas, so this needs on-device
    testing rather than a drive-by swap. FOLLOW-UP from the maintainer, which
    largely closes the charging question: the alarm in question was set for
    10:30, and Pixel Adaptive Charging only engages for a wake-up alarm between
    03:00 and 10:00 with the phone plugged in between 21:00 and 04:00 -- so the
    OS declined on its own terms and the app was publishing the alarm correctly
    all along. CONFIRMED on-device afterwards: an alarm set for 09:00 did
    trigger Adaptive Charging. So the answer to "does an OEM charging optimiser
    read a third-party alarm app's wake time" is YES on this device, via
    AlarmManager.getNextAlarmClock, which setAlarmClock populates -- worth
    knowing, since it is undocumented and widely assumed to be Clock-app-only.
    That RAISES the priority of the timer observation above rather than lowering
    it (an earlier draft of this entry had it backwards): because the signal is
    genuinely consumed, a kitchen timer still running when the phone is plugged
    in for the night makes getNextAlarmClock return the TIMER instead of the
    morning alarm, at exactly the moment Adaptive Charging evaluates -- a
    concrete way to lose the overnight charge shaping, not a theoretical one.
    DECIDED by the maintainer: leave timers on setAlarmClock. His usage is
    timers under 10 minutes, only while actively using the phone (cooking), so
    one is never running at overnight plug-in time -- the only moment the
    collision would matter. Weighed against the real downside of the
    alternative (setExactAndAllowWhileIdle is quota-limited and would put a
    timer's ring at risk, a worse bug than a confused charging optimiser), the
    swap isn't worth it. Don't re-propose this without a new symptom; if one
    ever appears it would look like overnight charge shaping failing on a night
    when a timer happened to be running, and the fix shape is recorded above.

73. **Reported scroll clunkiness in the list tabs: NO REGRESSION FOUND, closed
    without a code change.** Reported after V2.1.8: dragging the alarm or reminder list
    moves it only a few lines regardless of how far the finger travels, and
    feels low-framerate. Under 10 items in each list, so not list size, and it
    persists across a reboot.
    RULED OUT so far, all verified against the repo rather than assumed:
    (a) V2.1.8 itself -- the V2.1.7..V2.1.8 diff touches NO file under ui/ and
    no layout/theme resource; it is receivers, manifest, Gradle and the ring
    service only. (b) Dependency drift -- AGP 8.5.0, Kotlin 1.9.24, KSP
    1.9.24-1.0.20, Compose BOM 2024.06.00 and compiler extension 1.5.14 are all
    pinned, nothing floats, so the V2.1.8 build resolves the same tree as
    V2.1.7. (c) Build variant (#17's first question) -- the workflow runs
    assembleRelease for pushes AND releases, so there is no debuggable APK to
    have installed by mistake. (d) A nested-scroll conflict of the classic kind
    -- every list tab is a plain LazyColumn, and no verticalScroll wraps a lazy
    list anywhere in the tree. (e) The #27/#30 minute ticker resetting scroll
    position -- delay(60_000 - now % 60_000) always lands in 1..60000ms so it
    cannot hot-loop, and the LazyColumn's own saveable state survives
    recomposition.
    LOCALIZED by the maintainer on-device: the Settings screen is markedly
    snappier and feels higher-framerate than the list tabs. Settings scrolls
    via verticalScroll and sits OUTSIDE the pager; all three clunky tabs are
    LazyColumns INSIDE #40's HorizontalPager. Same device, same build, same
    session -- so this is the pager path, not the device.
    LEADING HYPOTHESIS: the Pager APIs are still @ExperimentalFoundationApi on
    foundation 1.6.8 (BOM 2024.06.00) per #41, stabilizing only in 1.7. A
    LazyColumn nested in an experimental-era Pager disambiguates every drag
    through the pager's nested-scroll connection before the list sees it, which
    matches "the list moves less than my finger" precisely.
    A/B RESULT, and the actual conclusion: the maintainer had run V2.1.6 for weeks
    without ever noticing this, felt something off on V2.1.8, then reinstalled
    V2.1.6 and V2.1.7 and found all three felt THE SAME as V2.1.8. That is
    negative evidence, and strong: had V2.1.8 changed scrolling, the older
    builds would have felt better on re-test. Scroll behavior has been constant
    across all three versions; what changed was attention, not code -- an
    ordinary thing with subtle UI feel, and hard to un-notice once it happens.
    No regression exists. Closed with no code change.
    PROCESS NOTE, worth more than the finding: the session built a confident
    two-cause theory (pager delta consumption + alpha compositing) off a SINGLE
    data point -- "Settings feels snappier than the tabs" -- and wrote it into
    this file as though diagnosed. It wasn't. A plain Column of Settings rows
    will always feel somewhat different from a lazy list of tonal cards with
    nothing wrong at all, so that comparison never carried the weight put on it.
    The A/B the maintainer ran is what produced real information. Next time a
    subjective "feels worse" report arrives: get the version A/B FIRST, before
    theorising, and remember that this sandbox can neither run nor profile the
    app, so any perf claim from here is inference.
    SECOND CANDIDATE, found after the A/B and probably the fps half of the
    report: every card in all three list tabs applies Modifier.alpha (0.5 for
    disabled/done/idle, 0.75 for paused, from #16/#33/#50). Compose implements
    Modifier.alpha via graphicsLayer, so each card is its own compositing layer
    and every sub-1f card blends offscreen each frame. SettingsScreen has no
    alpha modifier anywhere, which is precisely the screen that feels smoother.
    Also checked and CLEARED in the same pass: the rings-in computation is
    correctly remember()-keyed on (item, nowMillis) in both the series and
    standalone branches, so peekNextTriggerTime runs once a minute, not per
    frame, and items() carry stable keys.
    The two reported symptoms plausibly have two different causes: "the list
    moves less than my finger" is drag-delta consumption (pager), while "clunky,
    fewer fps" is compositing cost (alpha layers). Fixing one need not fix the
    other.
    THE TWO CANDIDATES BELOW ARE UNMEASURED HYPOTHESES, NOT DIAGNOSED CAUSES.
    They are real properties of the code and MAY be worth acting on as
    deliberate polish someday, but nothing has ever demonstrated they are
    perceptible here. Do not "fix" them on the strength of this entry; measure
    first (Android Studio Layout Inspector with recomposition counts, on a
    release build, is the tool). If polish is ever wanted, the cheaper one is:
    apply alpha to container/content COLORS instead of the whole card --
    apply alpha to container/content COLORS instead of the whole card, removing
    the layer per card. Small, surgical, costs no feature. Known visual
    trade-off to accept or reject: color alpha does not fade a card's
    shadow/elevation the way a full-card layer does, so dimmed cards read
    slightly differently. Only if the drag-delta symptom survives that is the
    pager worth touching, via: (1) bump the Compose BOM to
    a 1.7+ foundation where Pager is stable and drop the OptIn from #41 -- the
    principled fix, and #20(f) already wants this upgrade as its own
    on-device-tested release, not a drive-by; (2) revert #40's swipe-between-tabs
    to the previous AnimatedContent, removing the pager from the scroll path
    entirely at the cost of the swipe gesture. Option 1 risks a broad UI
    regression surface for one symptom; option 2 is small and surgical but gives
    up a requested feature.

74. **Field-verified: #34's JSON backup/restore works end to end.** Not a bug.
    While A/B testing #73 the maintainer had to install V2.1.6 over V2.1.8, which
    Android refuses as a downgrade (lower versionCode), so it needed an
    uninstall -- wiping the database. He backed up via Settings, went back and
    forth across three versions, and restored successfully onto V2.1.8. First
    time the feature has been exercised for real rather than assumed to work.
    STANDING PRACTICE this establishes: any future version A/B on-device costs
    an uninstall, so back up FIRST, every time. Worth saying out loud when
    suggesting a downgrade test, because the data loss is silent and total
    otherwise. Untested direction, do not assume: restoring a NEWER backup onto
    an OLDER app version (the serializer's reads are tolerant and ignore
    unknown keys, so it SHOULD degrade gracefully, but nobody has tried it).
    Residual caveat carried over from #34/#6, flagged to the maintainer: an
    uninstall drops content:// URI grants, so custom alarm sounds restored from
    a backup may no longer resolve -- the ring path falls back to the system
    alarm sound, which is safe but silently different from what was configured.

75. **RETRACTED: the claimed fillMaxSize viewport bug never existed, and
    V2.1.9 shipped a no-op.** Investigating #73's scroll complaint produced a
    theory that `fillMaxSize()` on HomeScreen's pager pinned it to the Column's
    FULL height rather than the height remaining after the permission banner,
    clipping the lists and shortening their scroll range. That analysis is
    WRONG. Compose's Row/Column pass the REMAINING space as the maximum to a
    subsequent non-weighted child, so `fillMaxSize()` there was already
    equivalent to `weight(1f)`. Nothing was ever mis-sized. `weight(1f)` is kept
    only because it states intent explicitly. **V2.1.9's release notes describe a
    fix for a bug that did not exist and should be treated as inaccurate.** The
    reasoning error worth remembering: asserting that `fillMaxSize()` ignores
    siblings. It does not.
    Separately fatal to the theory even before the retraction: the maintainer
    confirmed the banner is NOT showing on his device, so the alleged bug was
    latent exactly when the symptom occurred.
    MEASUREMENT RESULT (Profile HWUI, on-device): bars spike over the green line
    in BOTH the list tabs AND Settings, worst when swiping between tabs. That
    killed the per-card-alpha theory -- the jank is APP-WIDE, not specific to the
    cards or the lazy lists.
    HYPOTHESIS 4, ART OPTIMISATION STATE -- the one still live; see #76 for its
    current test. This app shipped no baseline profile and has R8 off, so a
    freshly installed APK runs largely interpreted/JIT until background dexopt
    AOT-compiles the hot paths during idle charging. Compose is unusually
    sensitive to this. Fits the timeline without strain: V2.1.6 had been
    installed for WEEKS (fully optimised, never noticed); V2.1.8 invalidated
    that; then three reinstalls in one afternoon left every version equally
    unoptimised, which is exactly why they all felt the same. **Those reinstalls
    are the confound that made version A/B useless here.** Corroborating: during
    that round an install STALLED, the installer was force-closed, and the app
    ended up installed anyway -- precisely the never-properly-optimised state
    this hypothesis describes. (If a sideload install ever hangs again, suspect
    Play Protect scanning.) Sideloads get no Cloud Profiles, so local dexopt is
    the only path.
    #71j IS NOT THE CAUSE AND WAS NEVER A REGRESSION, recorded because it looks
    guilty and will be asked again: it was the only UI-adjacent change in V2.1.8,
    but V2.1.6/V2.1.7 lack it and felt identical on re-test. A `LaunchedEffect`
    body runs on the composition dispatcher, i.e. the MAIN THREAD, so the old
    arrangement did the same disk read on the same thread; the change only moved
    ~5-30 ms of one-time work (one getSystemService, one checkSelfPermission, one
    small SharedPreferences load, three binder calls, then fall-through) from
    just after the first frame to just before it. Net lifetime cost: about zero.
    Moving it back is optional tidiness, not a fix, and not worth a release.
    PROCESS LESSONS, the durable part of this entry:
    - **A/Bs only isolate variables that actually differ across the arms.** The
      version A/B was worthless for hypothesis 4 (ART state was equal across
      three fresh installs) yet decisive against #71j (the code genuinely
      differs). Same experiment, opposite value.
    - **A clean negative A/B is not proof the report is imaginary.** Device state
      can ride in on a version change and persist across downgrades. When an A/B
      comes back negative and the user still reports the symptom, ask what
      changed on the DEVICE, not just in the code -- and believe the user. This
      entry exists because the maintainer pushed back after being written off.
    - **Own real mistakes; do not manufacture them.** Flagging #71j as "mine to
      own" in conversation inflated a non-issue into a prime suspect that then
      had to be knocked down twice.
    - The five dead hypotheses and the do-not-code-read rule are summarised in
      the "Dead ends" section at the top of this file.
    SUPERSEDED BY #76: the symptom was finally described precisely and turned out
    to be overscroll relaxation, a documented by-design Compose behaviour. #75's
    hypotheses were all chasing the wrong shape.

76. **Reaching a list edge blocked the next swipe until the stretch "cushion"
    paid back.** The real symptom behind the #73/#75 saga, once the maintainer
    described it precisely: swipe to the bottom, then start swiping up again --
    the up-swipe does not take until the bounce-back has finished.
    CAUSE, from Compose's own docs rather than code reading: `OverscrollEffect`
    decorates scroll events and consumes delta BEFORE the scrolling container
    sees it, and `LazyColumn`/`verticalScroll` both configure one automatically.
    Its documented relaxation rule subtracts the outstanding overscroll FIRST
    when the drag reverses, so the stretch charged up at the edge has to be paid
    back before the list moves. By design, present in every Compose app -- which
    is why it was identical on every version (all pinned to foundation 1.6.8),
    identical on every screen, unaffected by reinstalls, and invisible to five
    sessions of code reading.
    FIX SHIPPED (V2.2, commit 926d089):
    `CompositionLocalProvider(LocalOverscrollConfiguration provides null)`
    wrapping `AlarmClockTheme` in MainActivity's `setContent`, with
    `@OptIn(ExperimentalFoundationApi::class)` on `onCreate`. Covers all three
    tabs, Settings and every editor. NOT applied to RingingActivity or
    ReminderSnoozeActivity, which have their own `setContent` and no long lists
    -- add it there if either ever grows one. Trade-off knowingly accepted: no
    bounce at any edge; hitting the end stops dead.
    API VERSION TRAP, per #41: on foundation 1.6.8 the API is
    `LocalOverscrollConfiguration`, typed `OverscrollConfiguration?`, null
    meaning no overscroll, and it is `@ExperimentalFoundationApi` -- the OptIn is
    mandatory or the build breaks. `LocalOverscrollFactory` /
    `rememberPlatformOverscrollFactory` are a LATER foundation release; do not
    use them on this BOM. `OverscrollConfiguration` on 1.6.8 exposes only
    glowColor and drawPadding, so this is on/off, not tunable.
    FIELD RESULT: "better, still some lag but a bit more manageable now." First
    thing in the whole saga to be VERIFIED rather than theorised.

    SECOND HALF -- residual input latency, and the misconception that kept this
    open for three sessions. With edges excluded the feel is still off,
    described as a delay between the swipe and it being rendered, uniform across
    screens. The maintainer's reasoning was "it's a Pixel 9 Pro so performance
    isn't the issue." **ART compilation state is not a hardware question.**
    Interpreted/JIT Compose is several times slower than AOT-compiled Compose and
    no amount of silicon closes that gap; a fast chip only means the gap presents
    as input latency rather than visible slideshow framerate. The control that
    settles it: a comparable Compose app on the SAME phone has the same cushion
    but much less of this -- because it came from Play with a baseline profile,
    cloud profiles and weeks of dexopt. This app had none of the three.
    BASELINE PROFILE SHIPPED (V2.2), correcting #75's "impossible from the dev
    sandbox" verdict: rules can be HAND-WRITTEN. `app/src/main/baseline-prof.txt`,
    one line, `HSPLno/hanss/alarmclock/**->**(**)**` -- exactly the wildcard shape
    Google documents for manual rules. AGP consumes that path with no plugin and
    no Macrobenchmark module. Compose's libraries already ship profiles inside
    their AARs; nothing profiled THIS app's code, which runs every frame on every
    screen. **Deliberately no comment lines in that file:** `#` is not verified as
    legal in HRF and an unparseable rule would fail `compileReleaseArtProfile` --
    same class of mistake as #38's XML comment. Don't "tidy it up".
    WHAT THE PROFILE DOES AND DOES NOT DO, since #75 overclaimed four times: it
    does NOT AOT-compile at sideload install time. It hands ART the correct
    hot-path list immediately so the FIRST background dexopt (idle + charging)
    compiles the right code, instead of the runtime spending days collecting a
    profile. Judge it after an overnight idle charge, not on first launch. Zero
    behavioral risk -- a compilation hint cannot change semantics, which is what
    separates it from R8 and #17's objection.
    EVALUATING THE TWO CHANGES SEPARATELY, since they shipped together: the
    overscroll fix is an immediate binary check (hit the bottom, swipe straight
    back up). The profile needs a charging cycle. Independently revertable.
    WAIT TEST NOW RUNNING CLEAN, unlike #75's attempt: V2.2 installed once and
    left alone, and the profile means ART is handed the hot-path list instead of
    spending days collecting one, so the expected timescale is one idle charging
    cycle. **Do NOT push a release the maintainer would install during this
    window** -- a new APK re-invalidates the ART profile and restarts the clock,
    the exact confound that wasted #75. Sit on non-urgent changes until he
    reports back.
    OUTCOMES: lag fades -> hypothesis 4 was right, the profile works, there was
    never a bug in ui/. Unchanged after an idle charge -> the profile isn't
    enough, and the remaining honest step is a Macrobenchmark-generated profile on
    a real device (a local Android Studio project), NOT another round of code
    reading. There is no adb here and no user-facing way to inspect dexopt state,
    so this judgment is unavoidably subjective; treat a vague "feels the same" as
    real information.
    SHIPPED AS V2.2 -- a LINE bump, chosen by the maintainer. Process note: the
    session first suggested "V2.2.0", wrong twice over against #28, which says
    suggest the next PATCH number on the current line (V2.1.10 here) and never a
    semver-style minor bump -- and this project's line bumps are two-component
    (V2.1, V2.0), never three.
    OBSERVED IN PASSING, no change made: TimerListScreen ticks every 250 ms
    (`delay(250)` in a while loop) to drive the countdown, i.e. 4 recompositions
    a second on that tab -- oversampling for a seconds display, and a
    second-boundary-aligned tick like AlarmListScreen's would be cheaper and
    exact. Not touched: it cannot explain a symptom that also occurs on Settings,
    and bundling unrelated changes into a perf release is what #17 warns against.



77. **Added: a permission status box in Settings.** Requested feature, not a
    bug. Settings -> Permissions -> "Check permissions..." opens a dialog listing
    the five user-revocable permissions this app depends on, each with a green
    dot when granted and the theme error colour when not, a one-line note on what
    degrades without it, and a tap that opens that permission's own Android
    settings screen.
    WHY IT BELONGS HERE: the app degrades quietly by design (0.1, #66, and the
    ramp note in requestNextMissingPermission) -- a missing permission means
    heads-up instead of full-screen, or a non-ramped alarm, never an error. With
    #66's revocation on nearly every sideloaded update, and a request chain that
    deliberately prompts for only the FIRST missing permission per launch (#15,
    #22), the user had no way to tell a working install from a silently degraded
    one.
    NEW FILE ui/PermissionStatusDialog.kt; SettingsScreen gained one state flag,
    one dialog call and one EditSection. Nothing else touched.
    CHECKS ARE REUSED, NOT REIMPLEMENTED, so the dots can never disagree with
    MainActivity's request chain: checkSelfPermission(POST_NOTIFICATIONS),
    AlarmManager.canScheduleExactAlarms, NotificationManager.canUseFullScreenIntent,
    Settings.canDrawOverlays, NotificationManager.isNotificationPolicyAccessGranted.
    Four of the five settings intents are copied verbatim from
    requestNextMissingPermission, so no API surface is guessed at. The ONE
    exception, and the first place to look if the build fails:
    Settings.ACTION_APP_NOTIFICATION_SETTINGS + Settings.EXTRA_APP_PACKAGE for the
    notifications row -- both API 26, which equals minSdk, but neither appears
    elsewhere in this codebase.
    VERSION GATING CUTS BOTH WAYS, the easy bug to introduce here: below
    TIRAMISU / S / UPSIDE_DOWN_CAKE the respective permission is not
    user-revocable, so it must read as GRANTED. Reporting it missing would paint a
    red dot on every older device for something the user cannot act on. minSdk is
    26.
    The full-screen check deliberately mirrors MainActivity.onResume's exact
    `SDK_INT >= UDC && !canUseFullScreenIntent()` shape rather than inverting it,
    because that form already compiles and lints clean against an API 34 method
    at minSdk 26.
    NO UNGUARDED startActivity: every launch is wrapped in try/catch, and a
    failure marks that row "This phone has no settings screen for it." rather than
    offering a dead tap. safeStartActivity's docstring exists because an unguarded
    one crashed the app on launch -- OEM builds routinely lack the exact-alarm and
    full-screen-intent screens, and a permission INSPECTOR that crashes is the
    worst possible version of this feature.
    RE-CHECKS ON RESUME via a LifecycleEventObserver on ON_RESUME, so returning
    from a settings screen updates the dots without reopening the dialog. Uses
    androidx.compose.ui.platform.LocalLifecycleOwner -- correct for compose-ui
    1.6.8. Do NOT switch to androidx.lifecycle.compose.LocalLifecycleOwner:
    lifecycle-runtime-compose is NOT a declared dependency, so that import does
    not resolve. Same class of trap as #41.
    UNVERIFIED until the maintainer builds it: nothing here has been compiled or
    run.
    DEFERRED, deliberately: the button does not show a status summary before you
    open it, which would mean computing the rows in SettingsScreen too.

78. **AlarmScheduler.canScheduleExactAlarms() called an API 31 method with no
    version guard.** Found while writing #77. `AlarmScheduler` had
    `alarmManager.canScheduleExactAlarms()` bare, and MainActivity called it as
    `!viewModel.canScheduleExactAlarms() && Build.VERSION.SDK_INT >= S`. Kotlin
    evaluates `&&` left to right, so the call happened BEFORE the check that was
    meant to protect it -- on API 26-30 that method does not exist, and minSdk is
    26. In alarm-critical code, reached from a permission check on launch.
    FIX: guard inside AlarmScheduler (`SDK_INT < S || alarmManager...`), returning
    true below API 31 because exact alarms need no user grant there -- the correct
    answer, not a fudge -- and reorder MainActivity's condition to test SDK_INT
    first so the guard works by short-circuit rather than by luck. `android.os.Build`
    was NOT imported in AlarmScheduler; that import ships with the fix, because a
    missing import is how #31 broke the build.
    JUSTIFICATION WORTH KEEPING, because the crash was never reproduced: the
    NoSuchMethodError reasoning is sound but UNVERIFIED, and #75 is this file's
    monument to confidently declaring latent bugs that turned out not to exist.
    The fix was made anyway on the grounds that it is correct either way -- an
    unguarded call to an API above minSdk is simply wrong on its own terms -- so
    being right about the crash was never load-bearing. Prefer that framing to
    "found a crash" next time.
    Severity in practice was near zero: minSdk is 26 but the app runs on one
    Pixel 9 Pro, so the old path had never executed.
    SCAN RESULT, so nobody re-runs it: this was the ONLY unguarded above-minSdk
    call in the codebase. canUseFullScreenIntent is guarded on the line above it;
    the VibratorManager cast has an SDK_INT check, a legacy else branch AND a
    try/catch (#71b); createNotificationChannel is API 26 == minSdk;
    canDrawOverlays and isNotificationPolicyAccessGranted are API 23. Not a
    pattern, a one-off.
    STILL OPEN AS A DECISION, not a bug: raising minSdk from 26 to 31 would make
    this whole class of problem impossible and let a chunk of the ~19 SDK_INT
    guards go away. Cost is real but nil in practice for a one-device personal
    app; it would also need the README's "Requires Android 8.0+" line updated.
    Deliberately NOT bundled here.

79. **minSdk raised 26 -> 31, then REVERTED to 26 the same day. Settled: stays
    at 26.** Both directions recorded so this is not re-argued in six months.
    THE CASE FOR RAISING that was acted on first: the project claimed support for
    API 26-30 it had never been built for or tested on, and #78 was an unguarded
    API 31 call sitting in the launch path. Raising the floor makes that class of
    bug impossible by construction rather than by vigilance.
    WHY IT WAS REVERTED, and the reasoning that actually holds: the bump was
    argued partly on being able to simplify the ~15 now-dead SDK_INT guards --
    then counting them showed that cleanup is purely COSMETIC and not worth
    touching eight files, six alarm-critical, from an environment that cannot
    compile. With that gone, the bump bought exactly one thing (an honest minSdk
    number) at the cost of the maintainer's actual preference, which is that the
    more devices can run it the better. Bad trade.
    WHAT MAKES 26 DEFENSIBLE rather than reckless: the #78 scan found exactly ONE
    unguarded above-minSdk call in the whole codebase, and it is fixed. Every
    other API-gated call has a proper SDK_INT check; the alarm-critical
    VibratorManager cast also has a legacy else branch AND a try/catch (#71b).
    The `>= O` guards throughout the notification managers show the code was
    deliberately written for 26.
    HONEST LIMIT, now stated in the README rather than implied: supported on API
    26+, verified only on current Android. The app has never run on 8-11. What
    remains unknown there is BEHAVIOURAL, not crash-shaped -- notification channel
    quirks, how full-screen intents get downgraded, overlay behaviour on old OEM
    builds -- and no user would ever report it. Do not upgrade that caveat into a
    claim of tested support.
    NEVER SHIPPED: the bump (6225879) and this revert both sit between V2.2.2 and
    the next tag, so no device ever received a build with minSdk 31. Nothing
    user-facing to undo.
    NOT REOPENED WITHOUT NEW INFORMATION. A future session proposing a minSdk
    raise needs an actual reason -- a dependency that requires it, or a real bug
    found on an old device -- not tidiness. The dead-guard cleanup is not a
    reason; see above.

80. **Settings trimmed: Backup moved to the bottom, three explanatory blurbs
    removed.** Requested, cosmetic. Backup and Permissions swapped so Backup is
    the last section, and the body text was cut from three places, leaving header
    + buttons only: Backup (the "everything in one file / restoring replaces /
    sounds may fall back" paragraph), Permissions (the "degrades quietly"
    paragraph), and the reminder history blurb ("Completed and removed reminders
    collect as faded history..."). Buttons, dialogs and behaviour untouched --
    only Text() nodes and section order changed.
    NOTE FOR ANYONE TEMPTED TO ADD THEM BACK: the maintainer's position is that
    the permission dialog's own per-row explanations are sufficient, and the
    remaining Settings text (the per-type defaults intros, the "0 = permanent"
    supporting text, etc.) is the part worth keeping. Do not reintroduce
    section-level prose as a "helpful" touch.
    ONE REAL LOSS, recorded rather than argued: the Backup blurb was the only
    place warning that restoring REPLACES everything and that sounds can fall
    back to the system default on another device. The replace warning still
    appears in the restore confirmation dialog, so nothing dangerous is silent;
    the cross-device sound caveat is now undocumented in-app. It remains in the
    README.

81. **A picked song silently stopped being the alarm sound after every update or
    force close, and its name degraded to a bare number.** Reported as a cosmetic
    label bug; it was not. FIELD-CONFIRMED by the maintainer: set a custom song,
    force close, let the alarm fire -- the STOCK alarm sound plays. So every
    release silently reverted his chosen alarm sound.
    CAUSE, one root for both halves: this app declared NO media permission. The
    only read access it ever had to a user's own audio file was the temporary URI
    grant that ACTION_RINGTONE_PICKER hands back, and that grant DIES WITH THE
    PROCESS. An app update, a force close, or any cold start that lost the grant
    left the URI unreadable.
    - The label half: all four lookups call
      RingtoneManager.getRingtone(...).getTitle(context). Per AOSP Ringtone.java,
      getTitle queries MediaStore, CATCHES SecurityException internally (the source
      comments the swallow as "missing cursor is handled below"), and then returns
      `uri.getLastPathSegment()`. For content://media/external/audio/media/1234
      that is "1234". The number was the MediaStore row id. runCatching in the app
      could never help: no exception reaches the caller, the platform returns a
      valid-but-degraded String.
    - The sound half: playback needs the same read. createPlayer returned null and
      AlarmRingtoneService's existing fallback rang the device default, logging
      "Configured alarm sound failed to load". That fallback's comment blamed
      stale/deleted URIs -- a revoked grant fails identically, which is why this hid
      for so long. The degrade-rather-than-fail design worked exactly as intended
      and thereby masked a real bug.
    System sounds under content://media/internal/... are world-readable and were
    never affected, which is why this only ever hit a user-picked SONG.
    FIX: declare the permission and request it. READ_MEDIA_AUDIO plus
    READ_EXTERNAL_STORAGE with android:maxSdkVersion="32" in the manifest;
    MainActivity gains `mediaAudioPermission` (granular from TIRAMISU, broad below),
    `hasMediaAudioPermission()`, a second RequestPermission launcher, and a fifth
    link in requestNextMissingPermission. #77's checker gains a "Music and audio
    access" row pointing at ACTION_APPLICATION_DETAILS_SETTINGS, since runtime
    permissions have no dedicated settings screen.
    PLACED LAST IN THE CHAIN deliberately: without it the alarm still RINGS, just
    with the wrong sound, so it is less critical than the four above it. Same
    two-ask cap as POST_NOTIFICATIONS (Android stops showing the dialog after two
    denials, so an uncapped link would spin forever) using its own prefs key,
    media_audio_permission_asks.
    REJECTED: takePersistableUriPermission cannot be bolted onto this picker --
    ACTION_RINGTONE_PICKER does not return a persistable grant. Switching to
    ACTION_OPEN_DOCUMENT would give durable grants but replaces the ringtone list
    with a file browser and needs migrating URIs already saved. Not worth it now
    that the permission route works.
    A NUMBER AFTER A RESTORE IS NOT THIS BUG -- read this before reopening #81.
    restoreBackupJson writes every soundUri verbatim with NO validation (checked).
    Restoring a backup onto a device that lacks the song leaves a live URI
    pointing at a MediaStore row that does not exist, so getTitle finds zero rows
    and returns getLastPathSegment: the same number, an entirely different cause
    (missing FILE, not missing PERMISSION). The alarm still rings via
    AlarmRingtoneService's default fallback, and #82's reset button is visible
    because the URI is non-null, so the user can normalise it. Do NOT diagnose
    this as a permission regression.
    AND DO NOT MAKE RESTORE VALIDATE URIs: a restore is exactly when media is most
    likely to be TEMPORARILY unresolvable -- permission not yet granted, MediaStore
    still indexing after a device migration -- so nulling unreachable sounds would
    silently wipe selections that were about to become valid. Non-destructive and
    recoverable beats clever. The contained improvement instead is display-only:
    render "Sound unavailable -- using default" when a URI will not resolve, rather
    than a bare number. Not implemented.
    THIS ALSO WEAKENS THE CACHE IDEA BELOW: a cached title would show the
    last-known song name for a file that is gone, cheerfully claiming a sound that
    cannot play. The ugly number is at least an honest failure signal. If the cache
    is ever built, it must not mask an unresolvable URI.
    DEFERRED, worth doing if the label ever misbehaves again: cache the resolved
    title at pick time (a SharedPreferences uri->title map, no schema change) and
    display the stored name. That would also survive the user DENYING the
    permission, and the separate case the service comment describes -- a song
    deleted or its MediaStore row reassigned by a library rescan -- where
    getLastPathSegment will still surface a number.
    FIELD-VERIFIED on V2.2.4/V2.2.5: permission granted, custom song survives a
    force close and plays at ring time. #81 is closed for real.
    Note: the permission is requested
    on the NEXT launch, not retroactively, and existing alarms keep working the
    moment it is granted -- the URI in the DB was always correct, only the access
    was missing.

82. **Added: a way back to the stock alarm sound after picking a custom one.**
    Reported as the sound being "stuck on that song" with no route back.
    THE STATE MODEL WAS ALREADY RIGHT, which is why this needed no schema change:
    soundUri is `String?` on Alarm/AlarmSeries/Timer and on the three Settings
    defaults, and NULL ALREADY MEANS "system default" -- soundName(null) renders
    "System default", soundLabel falls back to "Default alarm sound", and
    AlarmRingtoneService resolves null via getActualDefaultRingtoneUri. The value
    simply had no way back to null from the UI.
    WHY THE SYSTEM PICKER DID NOT COVER IT: every picker call already sets
    EXTRA_RINGTONE_SHOW_DEFAULT=true, so a "Default alarm sound" row IS requested,
    and picking it would store content://settings/system/alarm_alert, which
    behaves as the default. That the maintainer could not get back suggests the
    Pixel's Sound Picker does not surface that row or buries it. NOT investigated
    further and nobody should: an in-app control is OEM-independent and therefore
    better regardless of the answer.
    IMPLEMENTATION: six sites, all previously an identical full-width
    OutlinedButton with a MusicNote icon -- AlarmEditScreen, SeriesEditScreen,
    TimerEditScreen and the three defaults in SettingsScreen. Each is now a Row
    with that button at weight(1f) plus a trailing IconButton (Icons.Outlined.Clear,
    contentDescription "Use the default alarm sound") that appears ONLY when the
    value is non-null, so the default state gains no clutter. In the editors it
    sets `soundUri = null`, saved with the screen. In Settings it ALSO writes
    viewModel.settings.defaultXSoundUri = null, mirroring the picker result
    handler -- forgetting that half would have looked like it worked until the
    screen was reopened.
    CHOSEN OVER A DROPDOWN, which is what the maintainer suggested: a menu costs
    an extra tap on the common path (picking a sound) and would add menu state to
    six places, whereas a trailing reset appears only when actionable and leaves
    the one-tap pick intact. Swap it if he prefers the menu.
    KNOWN COSMETIC EDGE: a non-null value that happens to BE the default URI still
    shows the reset button. Harmless -- tapping normalises it to null with the same
    audible result.
    FIELD-VERIFIED on V2.2.4/V2.2.5: the reset button returns the sound to the
    default as intended.

83. **Backing out of the ringtone picker wiped the current sound back to
    default.** With a custom song set, tapping the sound button and then exiting
    the picker without choosing anything reverted the sound to the stock default.
    NOT CAUSED BY #82 -- verified against V2.1.9, where the same line already
    exists. Pre-existing since the pickers were written; #82 only added the reset
    button beside them. Recorded explicitly because it surfaced immediately after
    #82 shipped and looks like a regression from it.
    CAUSE, one line repeated at four sites: the result handler ended with
    `soundUri = uri?.toString()` (and the `when (pickerTarget)` equivalent in
    SettingsScreen), assigning UNCONDITIONALLY. On cancel there is no result.data,
    so uri is null, so the selection was overwritten with null -- and null is
    exactly this app's representation of "use the system default" (#82). A cancel
    was therefore indistinguishable from a deliberate reset.
    WHY IT WENT UNNOTICED FOR SO LONG, worth noting as a pattern: before #81 the
    label already degraded to a number after any process death, so the sound field
    was not trusted or watched closely. Fixing #81 made the field trustworthy,
    which is what made this one visible. Expect more of this -- fixing a masking
    bug surfaces the bugs it was masking.
    FIX: `if (uri != null) soundUri = uri.toString()`, and the Settings handler
    wraps its whole `when` in the same guard. Chosen over checking
    resultCode == RESULT_OK because it covers BOTH cancel shapes -- a plain cancel
    with no data, and the OEM pickers that return RESULT_OK with the extra absent,
    which a resultCode check would miss. No new import needed.
    TRADE-OFF, documented so nobody "fixes" it back: a null URI is now
    unselectable, i.e. "Silent" could not be chosen through the picker. Fine today
    because every call sets EXTRA_RINGTONE_SHOW_SILENT=false, so Silent is never
    offered; and reverting to default is now a deliberate action via #82's reset
    button. If Silent is ever enabled, this guard must be changed to distinguish a
    cancel from a real silent pick.
    FIELD-VERIFIED on V2.2.5: cancelling the picker keeps the current selection.

84. **An unresolvable sound now says so instead of showing a number.** Follow-up
    to #81/#83. Where a stored soundUri cannot be resolved -- the song was deleted,
    a backup was restored onto a device without it, or the MediaStore row was
    reassigned by a library rescan -- the label showed a bare number. It now reads
    "Sound unavailable - using default", which is what AlarmRingtoneService will
    actually do.
    HOW UNRESOLVABLE IS DETECTED, and this is the part not to "simplify": per
    AOSP, Ringtone.getTitle() does NOT fail when it cannot read the media row. It
    swallows the SecurityException and returns `uri.getLastPathSegment()`, which
    for content://media/external/audio/media/1234 is the string "1234". So the
    detector is `title == uri.lastPathSegment` -- an exact match means resolution
    failed. Ugly but precise, and the only signal available without a separate
    MediaStore query. A real title colliding with its own numeric row id is not a
    realistic risk, and content://settings/system/alarm_alert resolves through a
    different branch returning the underlying sound's real name, so it is
    unaffected.
    CONSOLIDATION: four near-identical copies of the resolution logic
    (AlarmEditScreen, SeriesEditScreen, TimerEditScreen, SettingsScreen.soundName)
    are replaced by one helper, ui/SoundLabel.kt::soundDisplayName(context, uri,
    defaultLabel). defaultLabel is a parameter because the screens legitimately
    differ -- editors say "Default alarm sound", Settings says "System default".
    SettingsScreen keeps its local soundName() wrapper so its many call sites,
    including dialog text, needed no edits.
    Also dropped the now-misleading `?: "Default alarm sound"` at the three editor
    Text sites: it was a redundant elvis on an already non-null value even before
    this change, and leaving it beside a helper that handles the default would read
    as though the default were handled there instead.
    DELIBERATELY UNCHANGED: restore still writes sound URIs verbatim without
    validation. See the reasoning in #81 -- a restore is exactly when media is most
    likely to be temporarily unresolvable, so nulling unreachable sounds would wipe
    selections that were about to become valid. This entry makes the state HONEST
    rather than trying to repair it, and #82's reset button is how the user clears
    it deliberately.
    FIELD-VERIFIED on V2.2.7: the maintainer confirmed the message appears. So the
    `title == uri.lastPathSegment` detector does catch the real case.

85. **Removed the redundant "3:00 timer" line under a resting timer.** Requested,
    cosmetic. A resting timer card showed the duration as the big number and then
    repeated it in the subtitle as "3:00 timer", which restated the number
    immediately above it. The subtitle now carries only the label, plus "Rings at
    HH:MM" while running -- the maintainer explicitly wanted that one KEPT, since it
    says something the big countdown does not.
    THE CARE THIS NEEDED, because the subtitle was doing two jobs: it also renders
    the timer's label, joined with " · ". Simply deleting the else branch would have
    left a dangling separator on a labelled resting timer ("Pasta · ") and an empty
    Text plus its 2.dp spacer on an unlabelled one, still eating vertical space.
    Rebuilt as listOfNotNull(label, ringsAt).joinToString(" · ") so the separator
    cannot dangle, and the whole row is skipped when the result is empty.
    Four cases now: resting+unlabelled renders no subtitle row at all;
    resting+labelled shows just the label; running+unlabelled shows just the ring
    time; running+labelled shows "label · Rings at HH:MM".
    FIELD-VERIFIED on V2.2.7.

86. **Auto-capitalisation on every free-text field, not just reminders.** #63 gave
    the reminder text KeyboardCapitalization.Sentences and stopped there, so every
    other text field started lowercase. Now added to the four that were missing it:
    "Label (optional)" in AlarmEditScreen and TimerEditScreen, "Series name" in
    SeriesEditScreen, and the bedtime "Message (empty = default)" in
    SettingsScreen. Each needed the
    androidx.compose.ui.text.input.KeyboardCapitalization import as well;
    KeyboardOptions was already imported everywhere.
    Sentences, not Words, matching #63 -- capitalises the first letter only, which
    is what was asked for and keeps the five fields consistent.
    NUMERIC FIELDS DELIBERATELY UNTOUCHED, which is most of them: 16 across the
    package carry KeyboardType.Number (ramp seconds, snooze minutes, timer
    hours/minutes/seconds, hours of sleep, reshow minutes). Capitalisation on a
    number pad is meaningless and would only muddy the diff. After this change
    every OutlinedTextField in ui/ is either numeric or capitalised, with none
    unclassified -- verified by counting per file, so a future addition that is
    neither is easy to spot.
    NOT OBSERVED. Shipped in V2.2.8 and assumed working at the maintainer's
    direction rather than tested, which is recorded plainly instead of as
    verified -- the change is four identical one-line additions of an API already
    proven in ReminderEditScreen, so the risk is genuinely low, but low risk is
    not the same as observed. If capitalisation ever misbehaves on one screen,
    start by checking whether that field got the line at all.

87. **Added: self-deleting one-shot alarms.** A "Delete after it rings" toggle in
    the alarm editor, under the day selector, so a one-off alarm removes itself once
    dismissed instead of lingering as a disabled row.
    SCHEMA CHANGE, DB 12 -> 13. New field `deleteAfterRinging: Boolean = false` on
    Alarm, with MIGRATION_12_13 shipping alongside it:
    `ALTER TABLE `alarms` ADD COLUMN `deleteAfterRinging` INTEGER NOT NULL DEFAULT 0`
    -- and, the step that actually matters, ADDED TO THE .addMigrations() LIST.
    Defining a Migration without registering it is indistinguishable from not
    writing one: fallbackToDestructiveMigration would silently wipe every saved
    alarm. Both were verified present after the edit. No committed schemas dir, so
    exportSchema=true only warns, exactly as at v12.
    THE DELETE RUNS ON DISMISS, NOT AT FIRE TIME, and this is the non-obvious part.
    AlarmReceiver looked like the natural home (it already flips one-shots to
    `enabled = false` there), but the service reads the alarm row ASYNCHRONOUSLY in
    its own coroutine to get the sound, ramp and vibrate settings. Deleting at fire
    time races that read, and losing it means ringing with the DEFAULT sound instead
    of the chosen one -- precisely the failure shape #81 just fixed. By dismissal the
    sound is long since loaded. AlarmRingtoneService.handleDismiss() ->
    deleteIfSelfDeleting().
    SNOOZE DOES NOT DELETE: handleSnooze never calls it, because the alarm is still
    in use and the row is what snooze re-points. Do not "tidy" the two paths
    together.
    GUARDS: reads the flag off `ringingSnapshot` (the in-memory copy taken at ring
    start, which exists for #21's vanishing-row case), skips timers via isTimerRing,
    skips any alarm with seriesId != null so a series child can never be deleted out
    from under its series, and skips id <= 0. Wrapped in runCatching that only logs
    -- cleanup bookkeeping must never escape and kill the process mid-ring (#71a); a
    surviving row is cosmetic, a crash is not.
    LIFECYCLE, checked rather than assumed: launched on serviceScope, which
    onDestroy does NOT cancel -- it only calls stopRinging(). That is the same
    property snooze() already relies on to launch and then stopSelf() immediately.
    UI: hidden rather than disabled when repeat days are selected, since "delete
    after use" is meaningless on a repeating alarm; and the save forces
    `deleteAfterRinging && selectedDays.isEmpty()`, so no stored row can hold both a
    schedule and the flag.
    BACKUP: BackupSerializer writes alarm fields explicitly, so both a `put` and a
    tolerant `optBoolean("deleteAfterRinging", false)` read were needed -- a
    pre-V2.2.9 backup restores with the flag off rather than failing.
    ACCEPTED LIMITATION: an alarm that is never explicitly dismissed (service killed,
    user ignores it) is not deleted. AlarmReceiver's existing `enabled = false` still
    applies, so it degrades to exactly today's behaviour.
    UNVERIFIED: not compiled or run. This is the largest change of the session and
    the only one touching the schema -- if anything is wrong, check migration
    registration FIRST, because that is the failure that costs data.

88. **Added: delete-after-use for timers too.** Same treatment as #87, in
    TimerEditScreen's Details section: "Delete after it rings" removes a one-off
    timer preset once it has rung and been dismissed.
    SCHEMA CHANGE, DB 13 -> 14. `deleteAfterRinging: Boolean = false` on
    TimerPreset, MIGRATION_13_14
    (`ALTER TABLE `timers` ADD COLUMN `deleteAfterRinging` INTEGER NOT NULL DEFAULT 0`),
    and -- the step that actually protects the data -- REGISTERED in
    .addMigrations(). Verified present after the edit. Second migration of the day
    after #87's 12 -> 13.
    THE ONE REAL DIFFERENCE FROM #87: timers keep NO ringingSnapshot, because that
    snapshot exists solely so alarm snooze can resurrect a vanished row and timers
    have no snooze. So #87's trick of reading the flag off the snapshot does not
    work, and the timer is read from the DB at dismiss time instead (currentAlarmId
    holds the timer id while isTimerRing is true). Safe precisely because the delete
    runs on DISMISS: the sound is long since loaded, so there is no race with the
    service's own read the way deleting at fire time would have.
    deleteIfSelfDeleting() now branches on isTimerRing -- the old
    `if (isTimerRing) return` guard was exactly what had to change. TimerDao has no
    deleteById, but the row must be fetched anyway to check the flag, so it fetches
    then @Delete's it.
    No snooze concern at all: timers are dismiss-only by design, so unlike #87 there
    is no second path to keep clear of.
    BACKUP: `put` plus a tolerant optBoolean read, so pre-V2.3 backups restore with
    the flag off.
    NOT OBSERVED. Shipped in V2.3 and assumed working at the maintainer's direction
    rather than tested, recorded plainly instead of as verified. It mirrors #87
    closely, but it is the second schema migration of the day and an unregistered or
    malformed migration is the one failure here that costs data -- so if anything
    looks wrong after installing, check the timer LIST is intact first.

89. **Release signing moved to CI secrets (phase 1 of the key rotation).** Response
    to the public-repo finding above. The maintainer generated a fresh PKCS12
    keystore with openssl (no JDK or Android Studio needed -- verified that a
    keystore made by `openssl pkcs12 -export` is accepted by keytool/apksigner) and
    set four repository secrets: KEYSTORE_B64, KEYSTORE_PASSWORD, KEY_ALIAS,
    KEY_PASSWORD. PKCS12 cannot hold separate store and key passwords, so the two
    password secrets are intentionally the same value.
    WIRING: the workflow decodes KEYSTORE_B64 to $RUNNER_TEMP/release.keystore and
    exports KEYSTORE_PATH via $GITHUB_ENV; build.gradle.kts creates a `release`
    signingConfig only when that path exists and is non-blank, and the release
    buildType uses `signingConfigs.findByName("release") ?: getByName("debug")`.
    DESIGNED TO DEGRADE, NOT FAIL, which is why this could land before the rotation
    was proven: with no secret set, the decode step logs and does nothing, no
    release config is created, and the build signs with the committed debug key
    exactly as before. That also keeps fork builds and any local build working. The
    step is gated by a shell test rather than an `if:` expression on the secrets
    context, deliberately -- the shell check is unambiguous and also handles a
    base64 value pasted with line wrapping (whitespace is stripped before decode).
    The secret value is never echoed; only the decoded byte count is logged.
    .gitignore now covers *.keystore, *.jks, *.p12, tmp.key, tmp.crt. NOTE this does
    NOT untrack keystore/debug.keystore -- gitignore never untracks an already
    tracked file, which is intentional here: the debug key must keep working until
    the new signature is proven on the device.
    ROTATION VERIFIED LIVE ON V2.3.1, from the built artifact rather than the log:
    the release APK was downloaded and its signing certificate compared, which is
    the only check that cannot be fooled by a mis-set secret (both branches of the
    decode step exit 0, so a green log proves nothing).
      NEW key, all builds from V2.3.1 onward, v2 signature scheme:
        BC:75:6F:24:3E:51:93:62:D8:2C:34:B3:56:D9:16:2D:69:88:6E:9F:65:A3:99:A4:4A:63:61:51:14:E1:89:91
      OLD leaked debug key, everything up to and including V2.3 -- RETIRED:
        7C:93:AE:E4:CF:31:3B:E2:1F:F3:E9:EA:02:17:9E:F4:FF:DF:50:75:64:51:FD:AB:D8:E3:F2:F3:C9:D8:6C:59
    V2.3.1 IS THE BOUNDARY TAG. Anything at or before V2.3 is signed with a key
    that is public; treat those builds as unauthenticated. Anything from V2.3.1 on
    is signed with a key only the maintainer holds.
    Because the certificate comparison is definitive, the "try to install and watch
    it fail" proof step is redundant and was skipped. It would still fail, which is
    correct behaviour, not a bug.
    STILL TO DO (phase 2, remaining): cut a release; confirm the APK REFUSES to
    install over the existing app (signature mismatch IS the success signal); back
    up app data per #74; uninstall, reinstall, restore, re-grant all six
    permissions; only THEN delete keystore/debug.keystore. Doing it in any other
    order either breaks builds or leaves the exposed key still able to update the
    app.
    STEP 11 IS NOT JUST `git rm` -- the trap that would break the build. Deleting
    keystore/debug.keystore while app/build.gradle.kts:34 still does
    `storeFile = file("../keystore/debug.keystore")` leaves the debug signingConfig
    pointing at a missing file, and the release buildType FALLS BACK to that config
    when the secrets are absent. So step 11 must delete the file AND remove the
    custom `getByName("debug")` block, letting AGP use its own per-machine debug
    key. Entry 0.3's reason for committing that keystore -- consistent debug signing
    across CI so builds install over each other -- no longer applies once CI signs
    release builds with the secret-backed key. Losing per-machine consistency for
    debug builds is harmless here: nobody builds locally, and a secret-less build
    producing an APK that will not install over the real one is arguably a feature.
    The `storePassword = "android"` literals disappear with that block; they were
    the standard debug password and never leaked anything the keystore did not.
    HISTORY PURGE REJECTED, with reasons, so nobody relitigates it: (a) after
    rotation the old key cannot update the app at all -- a signature mismatch blocks
    it -- so it is worthless for the only attack that mattered; (b) the cost is a
    force-push over a PUBLIC repo, rewriting every commit SHA and all 58 tags, whose
    GitHub Releases would then point at commits that no longer exist; (c) it cannot
    work anyway -- the file has been publicly fetchable since 6 July, so caches,
    scrapers and any clone may hold it, and none of that can be recalled. Point (c)
    is exactly why the fix is ROTATION rather than deletion.
    THE OLD KEY'S FINGERPRINT RECORDED ABOVE IS NOT A SECRET. Certificate
    fingerprints are public by design and appear in every APK ever shipped. Keep
    it: it is how a future session identifies an old-key-signed build.
    RESIDUAL RISK THAT ROTATION DOES NOT FIX: anyone still running an
    old-debug-key-signed install stays vulnerable to a malicious "update" signed
    with the leaked key. That is the maintainer until step 10 completes, and
    possibly whoever starred the repo. Other people's installs cannot be fixed from
    here.
    WHAT ROTATION MEANS FOR EVERY APK ALREADY PUBLISHED (58 tags' worth): they stay
    downloadable and they still install on a clean device, but NONE of them can
    install OVER the rotated app -- signature mismatch blocks it in both directions.
    THE OPERATIONAL COST NOBODY WOULD NOTICE UNTIL THEY NEEDED IT: rollback dies.
    Right now a bad release can be undone by installing the previous APK straight
    over it, and #73/#75 leaned on exactly that to A/B V2.1.6 through V2.1.9 in one
    afternoon. After rotation, going back to any pre-rotation build means uninstall
    -> install old -> restore from a backup file, losing anything since the backup.
    Post-rotation versions roll back among themselves normally. If a version A/B is
    ever needed again for a #76-style question, take a backup FIRST.
    PROVENANCE IS GONE FOR PRE-ROTATION RELEASES, and that is not fixable: since the
    old key is public, a third party can sign an APK indistinguishable from any of
    those builds. Not a reason to delete the old assets -- deletion recalls nothing
    that has already been downloaded or cached -- but they should not be treated as
    proof of authorship. Record the rotation tag as the boundary.
    THE ROTATION RELEASE MUST SAY SO IN ITS NOTES. Anyone other than the maintainer
    running an older build will have their update FAIL with an opaque "app not
    installed" / signature error, and the only fix is uninstall + reinstall. Shipping
    a signing-key change silently would look exactly like a broken build. The repo
    is public with a star on it, so assume at least one other install exists.
    PHASE 2 COMPLETE at V2.3.2: the maintainer reinstalled onto the new key, and
    keystore/debug.keystore is now deleted along with the custom debug
    signingConfig that pointed at it. AGP's own per-machine debug key is the
    fallback. Entry 0.3 is therefore HISTORICAL -- do not "restore" the committed
    keystore because 0.3 argues for it; that argument died when CI moved to signing
    the release variant from secrets.
    A secret-less build now produces an APK that cannot install over a real one.
    That is the safe outcome, not a regression: a mis-configured build fails
    visibly at install time instead of shipping something that looks genuine.
    PROCESS FAILURE: step 11 was dropped. The maintainer confirmed the reinstall,
    the conversation moved to an unrelated bug, and the deletion did not happen for
    several turns -- leaving the retired key committed after it no longer needed to
    be. Nothing was harmed (the key was already worthless once V2.3.1 was
    installed), but a multi-step plan with a handoff in the middle needs the
    remaining steps restated at the handoff, not held in the session's head.
    README UPDATED: the install section claimed "builds are signed, so updates
    install over the previous version" with no caveat, which is now false for
    anyone on V2.3 or earlier. It carries the uninstall/reinstall instructions and
    names V2.3.1 as the boundary.
    NOT DONE: no LICENSE file yet. The repo is public with `license: null`, which
    means all rights reserved. Held back only because an MIT copyright line needs
    the maintainer's actual name and guessing a legal name into a public licence
    file is not something to improvise.

90. **Restore displayed pre-restore settings and could silently overwrite the
    restored ones.** Reported after the V2.3.1 uninstall/reinstall as "bedtime
    reminder was off and the text was gone after restore".
    MY FIRST DIAGNOSIS WAS WRONG and the correction is the useful part. I checked
    that the bedtime VALUES are backed up (they are -- all 15 SettingsStore values
    round-trip, and the keys have been in the format since V1.9.2, so an old backup
    file is not the explanation), concluded the values therefore survived, and
    blamed only a missing re-arm. The maintainer then said the values themselves
    were gone, which killed that. Checking the UI instead of the data found it.
    ACTUAL CAUSE: after `restoreBackupJson`, SettingsScreen re-read exactly TWO of
    its fifteen settings-backed `remember` state variables -- defaultAlarmSound and
    defaultTimerSound. Every other one kept its PRE-restore value, so the screen
    displayed fresh-install defaults over correctly-restored data. Bedtime showed
    off with an empty message because that is what the fresh install had.
    AND IT WAS A DATA-LOSS TRAP, not merely cosmetic: each control writes its local
    state to the store on interaction, so touching any stale field wrote the stale
    value back over what had just been restored. The longer the user poked at
    Settings after restoring, the more of the backup they destroyed.
    FIX, two parts:
    (a) SettingsScreen re-reads ALL FIFTEEN values after a restore. Note
        reminderReshowEnabled/Minutes were declared INSIDE the Reminders
        EditSection and thus unreachable from the restore handler's scope -- they are
        hoisted to the top-level state block. **If a setting is ever added to
        SettingsStore, it must be added to that refresh block too**; there is no
        mechanism forcing it, which is exactly how this rotted.
    (b) restoreBackupJson now calls refreshBedtime(). Restore re-armed alarms
        (`scheduler.schedule`) and reminders (`ReminderOps.refresh`) but skipped
        bedtime entirely, so even with correct values no notification would fire
        until the toggle was touched. refresh() schedules or cancels according to
        the flag, so it is right either way.
    PROCESS LESSON, and it is the same one as #75: I reasoned about the DATA path,
    proved it correct, and stopped -- when the symptom was in the VIEW. "The value is
    stored correctly" and "the user can see the value" are different claims. When a
    report contradicts a verified data path, suspect the display before re-verifying
    the data.

91. **The full-screen-alarm banner can be dismissed.** Requested: an X so someone
    who does not want full-screen alarms is not warned about it forever.
    NOTE WHICH PERMISSION THIS IS. The banner is USE_FULL_SCREEN_INTENT
    ("full-screen alarms"), NOT SYSTEM_ALERT_WINDOW ("display over other apps").
    The request came phrased as the latter; only the former has a banner. Do not
    "fix" the banner text to match the other permission.
    WHY DISMISSING IS DEFENSIBLE: alarms still RING without it -- they arrive as
    heads-up notifications instead of taking the screen -- so this is a degraded
    experience, not a broken alarm, and #66's banner reappears after nearly every
    sideloaded update. Hiding it is only acceptable BECAUSE #77's permission
    checker reports the true state on demand, so the information is available
    rather than lost. If that checker is ever removed, revisit this.
    SELF-CLEARING, and this is the part not to simplify away: the flag is reset
    automatically whenever the permission is found granted. So dismissal means
    "stop nagging me while it is off", and if Android revokes it again later the
    banner returns for someone who has demonstrably chosen to use the feature.
    Without that reset it would be a permanent setting with no UI to undo it --
    there is deliberately no toggle for this anywhere.
    IMPLEMENTATION: SettingsStore.fullScreenBannerDismissed (SharedPreferences, so
    NO schema change and no migration). The X is its own IconButton inside the
    banner Row, which matters: the banner Surface has its own onClick that opens
    system settings, and a child clickable consumes the tap rather than falling
    through. HomeScreen reads and writes viewModel.settings directly, so
    MainActivity needed no changes.
    BACKED UP, per #90's lesson that partial settings coverage is how these rot:
    added to BackupData, the JSON write, the tolerant read, and both the export
    and restore assignments in AlarmRepository. NOT added to SettingsScreen's
    post-restore refresh block -- correct, because it is not displayed there; that
    block only covers state the Settings screen holds.
    UNVERIFIED: not compiled or run.

92. **Remind-again and swipe-away protection split into two independent
    toggles.** Requested: two separate switches, settable independently, on new
    AND existing reminders.
    THE TWO MECHANISMS WERE ALREADY ORTHOGONAL IN THE DATA -- #62 gave both
    dropdowns an "Off" option for exactly this. What was coupled was the UI:
    #61's single "Keep reminding until done" switch gated both dropdowns and
    hid them when off, so "off" existed in two places at once (the master
    switch and each dropdown) and the pair read as one setting. This entry is
    therefore mostly a UI restructure. Don't go hunting for a missing
    mechanism.
    WHAT SHIPPED: two EditSections, "Remind me again" and "Swipe-away
    protection", each with its own switch that owns its own off and reveals its
    dropdown when on. The "Off" entries came OUT of both dropdowns, so off is
    expressed in exactly one place per mechanism. `persistent` is no longer
    user-facing -- buildCandidate DERIVES it as `nagEnabled || swipeEnabled`,
    and each switch writes its mechanism's off sentinel (renotify 0 /
    reshow RESHOW_OFF). All four of #62's combinations survive; verified by
    simulating the truth table that save -> load -> save is stable for each.
    NO DB MIGRATION AND NO BACKUP FORMAT CHANGE, deliberately: no new column,
    same three fields. Existing rows are read through two new computed
    properties, `Reminder.nagging` (persistent && renotify > 0) and
    `swipeProtected` (persistent && reshow != RESHOW_OFF). The `persistent &&`
    is the load-bearing part -- a legacy one-and-done row still holds whatever
    renotify/reshow the pre-#92 editor left when it merely HID the dropdowns,
    so without it those stale values would read as toggles that are on. That's
    what makes "old ones" work with no schema touch.
    THE EDITOR NEVER HOLDS AN OFF SENTINEL in its two interval states (they
    normalize to 1440 / FOLLOW_GLOBAL on load), so flicking a switch off and
    on again doesn't lose the chosen interval, and the dropdowns' out-of-preset
    fallback can't render "Every 0 minutes". Cost: turning a mechanism off and
    reopening later shows its dropdown back at the default -- the stored value
    is the sentinel, and keeping a shadow copy would need a column.
    ONE DEAD STATE CLOSED. persistent=true with renotify 0 AND reshow OFF left
    a fired reminder ACTIVE forever with nothing able to re-post it. Both
    toggles off now means persistent=false, i.e. #61's one-and-done, so the
    editor cannot produce it; and onSwipedAway's one-and-done test widened from
    `!persistent` to `!nagging && !swipeProtected` so a row RESTORED from an
    older backup in that state gets swipe-means-Done instead of sitting there.
    NOT folded in: reshow=FOLLOW_GLOBAL while the global switch is off (#62).
    swipeProtected reads the row's OWN intent, so that stays a swipe-sticks
    case -- a global toggle must not silently complete reminders, and it can be
    flipped back without editing every reminder.
    setOngoing NOW FOLLOWS SWIPE PROTECTION instead of `persistent`. With the
    nag on and protection off, `persistent` is still true, and setOngoing does
    block the swipe on pre-14, so keying it off `persistent` would have made
    the notification unswipeable in the one configuration that promises the
    opposite. Only visible below Android 14. autoCancel deliberately stays on
    `persistent`: it governs the TAP, not the swipe, and only a one-and-done
    notification should vanish when tapped -- keying it to protection too would
    have been an unrequested behaviour change.
    REPORTED WORKING on device at V2.3.5 -- the maintainer's words were that the
    toggles "seem to work", i.e. the two switches behave independently in normal
    use. Recorded as reported rather than verified, and deliberately NOT extended
    to #93's parts: the Settings defaults reaching new reminders, the legacy-row
    resolution (editorSwipeEnabled on a follow-global row), or a backup restore.

93. **App-wide defaults for the two reminder toggles, and a copy cleanup on
    both screens.** Requested: a Settings entry setting both #92 toggles as the
    standard for new reminders, plus clearer wording ("a bit confusing now").
    THE SEMANTIC TRAP, and the reason the old copy confused: Settings' two
    existing reminder values were a LIVE FOLLOW, not a default.
    RESHOW_FOLLOW_GLOBAL (-1) means "whatever Settings says right now", and per
    #60 that sentinel is the migration default, so most older reminders are
    still on it. The request was for creation-time defaults. Those are different
    things and the old UI called both "default".
    WHAT SHIPPED. Settings is now a TEMPLATE, copied at creation: prose says
    "What a new reminder starts with", and the section holds the same two
    switches and the same two dropdowns as the editor, same order, same wording.
    RenotifyDropdown/ReshowDropdown went `internal` and are now shared by both
    screens, so they cannot drift. New prefs reminderDefaultNagEnabled (true) /
    reminderDefaultRenotifyMinutes (1440, floor 1 since "off" is the boolean);
    the existing reshow pair is REUSED for the swipe half instead of adding
    parallel prefs, so it now does double duty (new-reminder default AND legacy
    resolution). Every default reproduces the values #92 hardcoded, so nothing
    changes until a default is changed.
    NO MIGRATION AND NO SCHEMA CHANGE. FOLLOW_GLOBAL survives as a legacy read
    path only: ReminderOps still resolves it exactly as before, so untouched old
    rows behave identically, while the editor shows CONCRETE values only (both
    sentinels are negative and every real value >= 0, so one `takeIf { it >= 0 }`
    covers both). Saving a legacy row pins it. Accepted consequence: a -1 row
    keeps following Settings until it is next saved.
    VERIFIED BY SIMULATION, not by running the app: for all four default
    combinations and six existing row shapes, the editor's toggle positions
    match what the row actually does, save->load->save is idempotent, and the
    only row whose behaviour changes is the dead state below.
    THE EDITOR/ENTITY DIVERGENCE IS DELIBERATE -- do not "fix" it. For a -1 row
    while the global switch is off, editorSwipeEnabled() reads FALSE (that is
    what the reminder actually does, and it is what the user is looking at)
    while Reminder.swipeProtected still reads TRUE (it reports the row's OWN
    stored intent, because the swipe path must not let a Settings toggle
    silently mark reminders Done, #92). Both are correct for their caller.
    That row is also the one dead state #92 could not reach: nag off + follow
    global + global off = ACTIVE forever. Opening and saving it now resolves it
    to explicit one-and-done.
    A CONTROL CHANGED, not just text: Settings' free-form "After (minutes)"
    field became the shared dropdown. The asymmetry (text field one side,
    dropdown the other, different vocabulary) was itself part of the confusion.
    Cost: arbitrary minute values can no longer be typed, only kept -- an
    out-of-preset stored value is still offered as-is. Reverting to a text field
    is easy if disliked.
    "Instantly (permanent)" KEPT VERBATIM: #58 records the maintainer choosing
    that wording himself ("call it permanent but I know it isn't technically"),
    so it was left alone during a cleanup pass aimed at everything around it.
    SETTINGS COUNT NOW SEVENTEEN in #90's post-restore refresh block, and both
    new prefs went through all six places (#91's checklist): SettingsStore,
    BackupData, JSON write, tolerant read, both AlarmRepository assignments, and
    that refresh block.
    PARKED AT THE MAINTAINER'S REQUEST -- both offered, both explicitly held
    with "keep a pin in them", so they are wanted eventually, NOT rejected:
    (a) an "Apply these to all reminders" button, which the Timers section
        already has as a precedent -- it would settle the legacy follow-global
        rows in one press instead of one edit at a time;
    (b) reverting Settings' shared reshow dropdown to a free-form minutes text
        field, if losing arbitrary typed values turns out to matter.
    UNVERIFIED: not compiled or run.

    ASKED AND ANSWERED in the same session, recorded because it will be asked
    again: the UPCOMING-ALARM notification (next alarm + "Dismiss next alarm")
    is NOT protected from swipes the way reminders are. It sets setOngoing(true)
    and nothing else -- no deleteIntent, so no code path even observes a swipe,
    and none of #57-#62's comeback machinery applies to it. On Android 14+ a
    swipe therefore sticks until something calls UpcomingAlarmManager.refresh()
    (an alarm added/edited/deleted/toggled, an alarm firing or being
    snoozed/dismissed, series unpause, or boot); the scheduled CHECK_UPCOMING
    fires at one hour out, which has already passed by the time the
    notification is visible, so in the ordinary case nothing re-posts it before
    the alarm rings. Below 14, setOngoing still blocks the swipe outright.
    WHY THAT ASYMMETRY IS DEFENSIBLE and was left alone: for a reminder the
    notification IS the entire delivery mechanism, which is the whole reason
    #57-#62 exist; for an alarm it is a convenience view over something that
    rings regardless, so a swipe costs the skip shortcut, not the alarm. The
    ringing notification is a separate foreground-service one and unaffected.
    Bedtime is deliberately setAutoCancel(true) per #47.

94. **Swipe protection for the upcoming-alarm notification.** Requested: a
    Settings toggle like the reminder one, for alarms AND series, so the
    upcoming-alarm notification can only be cleared with its own action button
    -- while still going away once the alarm actually rings.
    ONE NOTIFICATION COVERS BOTH KINDS, so this is ONE toggle. Series alarms are
    `alarms` rows with a non-null seriesId (standalone rows have null),
    refresh() reads getAllEnabledAlarms() which doesn't filter on it, and the
    result is a single notification (id 2001) for whichever enabled alarm is
    soonest. There is no separate series notification. Do NOT add a second
    toggle to the Alarm series section expecting one, and note the setting lives
    in its own "Upcoming alarm notification" section for exactly this reason --
    a copy in each of the two alarm sections would imply two behaviours.
    NOTHING NEEDED FOR THE RINGING NOTIFICATION, the other thing the request
    could have meant: that one is AlarmRingtoneService's foreground-service
    notification, the service rings regardless of what happens to it, and
    Snooze/Dismiss already end it.
    SHIPPED SMALL, deliberately not a second copy of #57-#62's machinery: the
    only change to the notification is a setDeleteIntent, attached ONLY when the
    setting is on, routed to a new ACTION_UPCOMING_SWIPED whose entire handler
    is refresh(). No scheduler slot, no delay dropdown, no re-post of stale
    content. refresh() recomputing from the alarm table is what makes "goes away
    once it rings" free -- if the alarm has fired or been dismissed it cancels or
    re-points to the next alarm instead of restoring what was swiped. The
    comeback is instant and SILENT (IMPORTANCE_LOW channel + setOnlyAlertOnce),
    so unlike #58's reminder equivalent there is no ding-per-swipe.
    setOngoing(true) LEFT UNCONDITIONAL. Making it follow the toggle would newly
    ALLOW swipes below Android 14 (where setOngoing does block them) whenever the
    toggle is off -- an unrequested change on the platforms that already behave
    the way the maintainer wants. Toggle off == exactly the pre-#94 behaviour.
    Corollary to remember: the toggle only does anything on 14+.
    THE LOOP QUESTION, since refresh() calls cancelNotification() routinely on
    every alarm add/edit/delete/toggle, not just at end-of-life like the reminder
    cancels. deleteIntent fires on USER dismissal only, never on
    NotificationManager.cancel() -- #57 established that here and reminders have
    depended on it since. And even if that were wrong it cannot loop: the chain
    would be cancel -> deleteIntent -> refresh -> cancel, and the second cancel
    has nothing to cancel, so no third delete intent fires. notify() replacing
    the same id doesn't fire it either. No debounce needed.
    A #90-CLASS GAP CLOSED ON THE WAY: the setting is baked into the posted
    notification, so a live one has to be re-posted for a change to take effect.
    New repository/viewModel refreshUpcoming() (mirroring refreshBedtime()),
    called from the toggle AND from restoreBackupJson -- restore re-armed alarms
    and reminders and, since #90, bedtime, but never touched the upcoming
    notification, so a restored flag would have sat inert until the next alarm
    edit. Same bug shape as #90, found by looking for it this time.
    DEFAULT ON, unlike #93's behaviour-preserving defaults: this IS the requested
    behaviour and a toggle defaulting off looks broken on first install. Called
    out in the release notes as a behaviour change rather than shipped quietly.
    SETTINGS COUNT NOW EIGHTEEN in #90's post-restore refresh block, and the new
    pref went through all six places.
    BUILD-SAFETY NOTE: setDeleteIntent is called conditionally on a non-null
    local rather than handed a nullable, because NotificationCompat's nullability
    annotation on that setter was not worth betting a build on from a sandbox
    that cannot compile.
    VERIFIED ON DEVICE after V2.3.5 was installed: swiping the upcoming-alarm
    notification inside the hour before an alarm brings it straight back, and
    the alarm still rings. So the whole chain is confirmed working in the real
    world -- deleteIntent firing on a user swipe, the receiver branch, and
    refresh() re-posting rather than resurrecting something stale. This is the
    FIRST on-device confirmation of anything in #92-#95.
    RELEASED AS V2.3.4 (commit 19b524f), the first release carrying entries #92,
    #93 and #94 -- all three shipped together and NONE had been on a device when
    the tag was cut. If any of them misbehaves that is the release to bisect
    from, and V2.3.3 is the last build without them.

95. **Swipe protection for the running-timer notification.** Requested: the same
    thing #94 did for the upcoming alarm, with a Settings toggle.
    THE ONE REAL HAZARD, and the reason to read this before touching
    TimerReceiver: onReceive's `when (action)` ends in `else -> fire(...)`,
    because the AlarmManager fire intent carries no action. So ANY action it
    does not recognise RINGS THE TIMER -- a new action without its own branch is
    not an inert no-op, it is a spurious ring on every single swipe. The branch
    went in above the else and the else now carries a comment saying so.
    SHIPPED, same shape as #94: a setDeleteIntent on the countdown notification,
    attached ONLY when the setting is on, carrying the timer id (one
    notification per running timer, id 3000 + id, so unlike the single
    upcoming-alarm notification it has to say WHICH timer). The handler re-reads
    the row and re-posts only while it is still running -- which is what makes
    "goes away when it rings" free: fire() clears runningUntilMillis before
    cancelling, and post() already returns early on a null one, so a
    fired/stopped/deleted timer cannot be resurrected by a swipe.
    THE HANDLER RUNS INSIDE timerOpsMutex like every other branch, settling the
    swipe-races-the-ring case for free: if fire() wins the lock the row is
    already idle and the swipe no-ops; if the swipe wins it re-posts and fire()
    cancels it a moment later. No nested locking -- restoreAfterSwipe does not
    take the mutex itself, it is called from inside the existing withLock, which
    is the trap #61 hit with markDone.
    Re-post is SILENT (channel sets no sound and no vibration, plus
    setOnlyAlertOnce), and post() recomputes the chronometer base from the
    current time, so a restored notification shows the correct remaining time
    rather than a stale countdown.
    setOngoing(true) LEFT UNCONDITIONAL, same reasoning as #94; toggle off ==
    exactly the pre-#95 behaviour, and the toggle only bites on Android 14+.
    SETTINGS PLACEMENT: its own "Running timer notification" section rather than
    a row inside the Timers section, which is a block of per-timer DEFAULTS
    ending in "Apply these to all timers" -- a global notification behaviour in
    there would look like another default needing that button pressed.
    Mirroring #94's section also makes both swipe toggles read identically.
    LIVE NOTIFICATIONS re-posted on toggle via new repository/viewModel
    refreshRunningTimers() over getAllRunningTimers(), and from
    restoreBackupJson, exactly as #94 did with refreshUpcoming(). BootReceiver
    needed nothing: it already calls post(), which reads the setting itself, so
    every post path is automatically consistent.
    DEFAULT ON as #94, called out in the release notes as a behaviour change.
    SETTINGS COUNT NOW NINETEEN in #90's post-restore refresh block; the new
    pref went through all six places.
    VERIFIED ON DEVICE at V2.3.5: swiping a running countdown brings it back
    with the correct remaining time and the timer still rings on schedule. So
    the explicit branch really does sit above TimerReceiver's ring-by-default
    else -- no spurious ring on swipe -- and post()'s recomputed chronometer
    base is right.
    RELEASED AS V2.3.5 (commit f5c4002), cut by Claude on request like V2.3.4.
    STACKING: V2.3.5 shipped carrying four entries that had never been on a
    device (#92-#95, since V2.3.4 went untested before V2.3.5 was tagged).
    CONFIRMED SINCE, on device: #94 (upcoming-alarm swipe), #95 (timer swipe),
    and #92 reported working (the two toggles behaving independently).
    STILL UNVERIFIED, all of it #93: the Settings defaults reaching new
    reminders, the legacy-row resolution for a reminder still on
    RESHOW_FOLLOW_GLOBAL, and a backup restore exercising #90's refresh block at
    nineteen values. The restore is the one that matters most -- #90 was a
    silent data-loss trap, not merely a display bug.
    Shipping four untested entries at once is what the unadopted
    pre-release-tag agreement (see Standing working agreements) exists to
    prevent; two of them landing fine does not validate the habit.
    PROCESS SLIP WORTH KEEPING, and it is why the git history shows #92/#93
    marked verified and then un-marked: a confirmation that quoted two bullets
    at once was read as covering both, and "verified on device" was written into
    both entries with invented specifics ("a backup restore shows restored
    values...") that nobody had actually observed. Corrected as soon as the
    maintainer said so. Same failure family as #75 and #88 -- record what was
    actually observed, not what a reply seemed to imply. When one message
    acknowledges several items, confirm which.
    WHAT #94's CONFIRMATION DOES AND DOESN'T BUY #95: it proves the shared
    mechanism -- a deleteIntent fires on a user swipe, does not fire on the
    app's own cancel(), and a re-post from current state is the right handler.
    That was the main unknown. It does NOT cover what is specific to #95: the
    per-timer notification id, the explicit branch sitting above
    TimerReceiver's ring-by-default else, or post() recomputing the chronometer
    base so a restored countdown shows the correct remaining time. Test the
    timer swipe on its own merits.
    If something misbehaves, V2.3.3 is the last release with none of these and
    V2.3.4 splits #95 off from the other three.

96. **Restore never re-arms timers, so a restored running timer never rings --
    KNOWN AND ACCEPTED, do not "fix" this unasked.** Found while working out what
    a restore test was actually worth, NOT from a report.
    restoreBackupJson re-arms alarms (`scheduler.schedule`) and reminders
    (`ReminderOps.refresh`), and since #90 bedtime, but for timers it only does
    `timerDao.insert(it)`. A backup taken while a timer was counting down
    therefore restores a row with runningUntilMillis set and NO AlarmManager
    entry behind it: the app believes it is running and it will never fire.
    #95 MADE IT VISIBLE rather than causing it: refreshRunningTimers() now posts
    the countdown notification for exactly those rows, so instead of failing
    silently it ticks down to zero and then does nothing.
    THE MAINTAINER DECLINED THE FIX -- "such an edge case and I can live with
    it", since it needs a backup made mid-countdown to hit. Recorded as an
    accepted limitation, NOT as a pending task: do not raise it again as a new
    discovery, and do not fix it as drive-by tidying. If it is ever revisited he
    should be asked first.
    THE SYMPTOM TO RECOGNISE, which is the real reason this entry exists: a
    countdown notification that reaches 00:00 and then nothing happens, shortly
    after a restore. That is this, not a broken scheduler or a ringing-service
    regression -- do not go debugging AlarmRingtoneService for it.
    IF IT IS EVER FIXED, the code is already written elsewhere: BootReceiver's
    timer block re-arms anything still in the future and resets anything already
    expired to idle rather than ringing it late. Restore should do the same, and
    the reset must happen BEFORE refreshRunningTimers() or that will post for a
    row about to be cleared.

97. **[OPEN] No way to finish a repeating reminder off -- the list checkmark
    always rolls it to the next occurrence.** Requested: checking off a
    repeating reminder in the list should COMPLETE it (cross it off, into the
    faded history under the active list, deletable from there), and the
    per-occurrence "done, roll forward" action moves to a button inside the
    editor.
    SYMPTOM AS REPORTED: pressing the checkmark on a daily reminder "just skips
    the one reminder for today". Accurate -- markDone on a repeating row rolls
    dueAtMillis to the next on-pattern occurrence, stays PENDING and stays in
    the list (#54, by design). Retiring one has only ever been possible through
    Delete, which is not what crossing something off should be called.
    WHAT THIS IS NOT: no new state. Retiring a live reminder is already exactly
    ReminderOps.delete's live branch (#55) -- cancel notification + scheduler,
    STATE_DONE, repeat fields kept so the faded card still describes itself,
    editable back into a live reminder. So Complete and Delete land in the same
    place on purpose, and the maintainer explicitly wants that: completed rows
    sit as a shadow under the active list with every other expired reminder.
    NO SCHEMA CHANGE, decided rather than defaulted -- no completedAt column,
    history does not distinguish Completed from Deleted. DB stays at 14.
    INTENDED APPROACH:
    (a) ReminderOps.complete() -- mutex, cancel notification, cancel scheduler,
        STATE_DONE. Same transition as delete()'s live branch, separate name so
        the two intents read differently at the call sites.
    (b) List checkmark -> complete, behind a CONFIRM DIALOG (the maintainer's
        choice over a snackbar Undo). One tap where "done for today" used to
        live now retires the reminder, and #52 set the precedent that a
        one-tap irreversible-looking action gets a confirm. Wording must not
        collide with Delete's "Move to history?".
    (c) Editor gains the old per-occurrence action as a labelled button
        (repeating, non-history rows only -- meaningless on a one-shot).
    (d) THE TRAP IN (c), and the reason it is not just a button: the editor
        holds dueAtMillis in local state and Save rebuilds the row from it via
        buildCandidate. Rolling the DB row forward while the screen is open
        leaves that local copy stale, and a subsequent Save would write the OLD
        dueAt back and re-arm it -- silently undoing the roll. The button
        therefore performs the op and LEAVES the screen (onDone()), reusing
        #54's snackbar for the "next ..." feedback. Do not "improve" this into
        an in-place update without solving the write-back.
    ONE-SHOTS UNAFFECTED: checkmark already meant STATE_DONE for them, so the
    new semantics are identical there.
    NOTIFICATION "Done" UNCHANGED -- still per-occurrence. Changing it would
    interact with the nag/re-alert loop (#59/#61/#92) for no requested gain.
    OUT OF SCOPE, flagged not built: the faded history card has no delete
    affordance of its own (tap -> editor -> trash, or Settings' Clear history
    per #56). "I can delete them from there" is true via that path; if a
    per-card delete is wanted it is a separate change.
