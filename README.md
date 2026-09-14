# MaterialAgent

A Jetpack Compose, Material 3 Expressive Android client for a **Hermes** agent gateway.
Point it at your own `hermes serve` instance and the whole agent loop is on your phone:
streaming transcripts, reasoning and tool rows, approvals, steering mid-turn, media, and the
server's model, toolset, skill and MCP catalogue.

[![Build](https://github.com/FammasMaz/MaterialAgent/actions/workflows/build.yml/badge.svg)](https://github.com/FammasMaz/MaterialAgent/actions/workflows/build.yml)


## What it does

**The agent loop.** `ChatReducer` folds gateway events into a transcript: streamed text with a
caret, reasoning blocks that stay collapsed until you open them, tool cards that show the command
they ran and expand to arguments and result, todo lists, approvals and clarifying questions,
compaction notes, interruptions, and elapsed-work feedback. Sending while a turn is running
becomes steering instead of queueing, and the send button becomes a stop button.

**Transport.** One WebSocket to `/api/ws`, newline-delimited JSON-RPC 2.0 in both directions,
server pushes arriving as `event` notifications. `HermesClient` owns the socket: 15-second
heartbeats, a 45-second silence deadline, per-session sequence watermarks, `replay_epoch`
tracking, and `session.events.since` replay after a dropped connection.

**Everything the server advertises.** Models (the picker is search-first because a real server
advertises four figures' worth), toolsets with per-connection toggles, skills, MCP servers, and
live context-window and throughput usage.

**Scheduled work stays readable.** Each cron firing stores its own session, so a handful of
automations turns the inbox into a wall of near-identical rows named
`cron_<job>_<date>_<time>`. Every run of one job folds under a single collapsible header instead,
newest first, on by default and switchable from Settings.

**Interaction cards use the server's own vocabulary.** Approval buttons are rendered from
`approval.request.choices` rather than hardcoded, clarify batches track `remaining` per `qid`,
and sudo prompts read the password field `sudo.respond` actually wants.

**Media both ways.** Incoming: the agent writes `MEDIA:<path>` markers into its prose, the app
strips them and fetches the file through three authenticated endpoints — `/api/media` (images),
`/api/files/stream` (audio, HTTP Range, so seeking works), and `/api/files/download`. Outgoing:
images go up as `image.attach_bytes`, everything else as `file.attach`, because the gateway has
no HTTP upload endpoint a chat turn can use. The composer takes images from the system photo
picker plus audio and file pickers, and all of them feed one sender.

**Branching and search.** Any session branches into an independent one; the list is searchable
and filterable, including by capability.

## Connect to a Hermes gateway

The app talks to a `hermes serve` instance you run. Nothing is bundled or hosted.

1. **Address** — `host:port` of the gateway, e.g. `192.168.1.27:9119` or
   `http://example.<tailnet>.ts.net:9119`. A path may be included; the client appends `/api/ws`
   to derive the socket URL.
2. **Auth method** — `Access token` (loopback/dev tokens) or `Password`.
3. **Credentials** — with `Password`, the app signs in with a **username** and the dashboard
   password, keeps the session cookie, and exchanges it for a **single-use WebSocket ticket**.
   It never puts the password on the socket.
4. **Nickname** — optional label for saved connections.

Auth is not optional on a reachable server: setting `dashboard.public_url` activates
authentication even when Hermes binds to loopback, and Hermes refuses to start without a
registered auth provider. There is no unauthenticated public-dashboard mode.

**Remote access.** The reference setup exposes Hermes over Tailscale `serve`
(tailnet-only, not Funnel), so a phone must be **signed in to the same tailnet** to reach it.
Plain tailnet access is still not enough by itself: the dashboard's Host check accepts loopback
or the single hostname in `dashboard.public_url`, so use the configured hostname rather than the
raw tailnet IP. For local development the alternative is an SSH forward plus `adb reverse`
(see below).

While a connect is in flight the whole form is disabled, so taps and toggles look dead and the
pixels do not change. That is a *connecting* app, not a frozen one — check with
`uiautomator dump` rather than tapping, and do not press Back mid-connect (it cancels the job
and leaves a "cancelled before it finished" banner that only Retry clears).

## Build and run

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/Users/user/Library/Android/sdk     # or wherever your SDK lives

./gradlew :app:assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM suite; live tests skip without a gateway
```

Install on a connected device or emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build carries the `.debug` application-id suffix, so it installs alongside a release
build instead of replacing it. Two package names can therefore exist on one device:
`com.materialagent.debug` (what `assembleDebug` installs) and `com.materialagent` (release).
Sign in separately in each.

### Toolchain

| Component | Version |
| --- | --- |
| Kotlin | 2.3.0 |
| Android Gradle Plugin | 8.13.2 |
| Gradle / JDK | wrapper, JDK 17 |
| Compose BOM | 2024.12.01 |
| Material 3 (Expressive) | 1.5.0-alpha15 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| OkHttp | 4.12.0 |
| kotlinx-serialization / coroutines | 1.7.3 / 1.9.0 |
| Coil | 2.5.0 |
| App version | 1.0.0-beta.8 (versionCode 10) |

Release builds minify and shrink resources (`isMinifyEnabled` and `isShrinkResources` are both
on), so a bug that only reproduces in release is worth checking with the same R8 rules.

## Architecture

```
app/src/main/java/com/materialagent/
  core/            # Android-free: transport, wire models, reducers
    model/         #   Media markers, outgoing attachment contract
  data/            # Connection policy, chat controller, update pipeline
  ui/              # Compose: theme, components, screens, haptics
  update/          # Release version comparison
```

- **`core/` has no Android dependencies.** The socket, the wire models and the transcript
  reducer are plain Kotlin, so protocol behaviour is unit-testable without Compose or an
  emulator. This is the single most useful structural decision in the repo.
- **`HermesConnection` owns credential resolution, socket lifecycle, retry and reconnect
  policy**, so no screen touches the socket. Blocking sign-in work is confined to
  `Dispatchers.IO` there.
- **`ChatController` lives in the container, not the screen**, so a live turn survives screen
  recreation and navigation; `ChatViewModel` only owns view-local state such as the draft.
- **Feature ViewModels are resolved from the shared `AppContainer`**, keeping dependency
  construction at the application boundary instead of threading a container through nested
  composables.
- **One design system**: colour, type, shape, motion, haptics and the reusable surfaces live in
  `ui/theme` and `ui/components`, so screens compose them rather than restating values.

Two conventions that exist because the gateway makes them necessary:

- `session.branch`, `session.history`, `prompt.submit`, `session.steer` and `session.interrupt`
  take the *runtime* id; `session.title` and `session.delete` take the *stored* id. The API
  surface separates these two namespaces rather than accepting an ambiguous nullable id, and
  `ChatController.withLiveSession` retries once through `session.resume` when a call comes back
  `4001` (or `4007`, which a reconnect can also cause).
- Every interaction method reads a **different** parameter — `approval.respond` reads `choice`,
  `clarify.respond` reads `answer`, `sudo.respond` reads `password` — and each falls back to a
  default when its key is missing. The builders are kept together in `InteractionParams`, one per
  method, precisely because the parameter shapes cannot be shared.

## Testing

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew :app:testDebugUnitTest
```

264 JVM test cases live in `app/src/test`, covering the wire protocol, the transcript reducer,
media-marker parsing and URL building, the outgoing attachment contract, session grouping, model
search, scroll-tick gating, the pull-reveal ratchet, effective palette, version comparison, and
the update pipeline.

### The cache trap

`--rerun-tasks --no-build-cache` is **not optional** when you need evidence:

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks --no-build-cache
```

The test environment is not a declared task input, so Gradle treats the test task as up to date
after any earlier run — and worse, restores the previous results XML from its build cache. The
suite then reports the last run's `skipped` results as if it had just executed, which is exactly
what a passing run looks like. Only a forced run, with real per-test durations in
`app/build/test-results/testDebugUnitTest/*.xml`, is evidence.

### The live gateway suite

`HermesLiveTest` holds the round trips. Each one calls `assumeTrue` on a configured gateway, so a
clean checkout and CI both stay green and the live tests **skip** rather than fail.

```bash
scripts/tunnel.sh start        # idempotent SSH forward, waits for /api/health
# or: ssh -N -L 19119:127.0.0.1:9119 home-server

export HERMES_TEST_HTTP='http://127.0.0.1:19119'
export HERMES_TEST_USER='<username>'
export HERMES_TEST_PASSWORD="$(cat ~/MaterialAgent-release/dashboard-password.txt)"

./gradlew :app:testDebugUnitTest --tests '*HermesLiveTest*' --rerun-tasks --no-build-cache
```

Passwords and tokens live outside the repository; `.hermes-test-token` is git-ignored, and the
tunnel script deliberately has no default token because a committed credential must be treated as public.

| Live test | What it pins |
|---|---|
| `realGatewayTurnRoundTrip` | the whole turn lifecycle — `message.delta` through the authoritative `message.complete`, usage parsing, session persistence, history rows, close and delete |
| `attachmentUploadRoundTrip` | the two attach RPCs and the `@file:` reference the client has to put back into the prompt |
| `branchNeedsTheRuntimeSessionId` | `session.branch` rejects the stored id with `4001`, so a row must resume first |
| `approvalResponseUsesTheServersParameterNames` | a real approval: the card's payload, the parameter names, and that a granted `once` actually ran the command |
| `clarifyAnswersAreMatchedToTheirQuestion` | a two-question batch where each answer moves `remaining` from `[q1]` to `[]` and the agent's finished text contains both options |
| `runtimeIdSurvivesASocketDrop` | a dropped socket does not invalidate the runtime id, so session-scoped calls stay valid after a reconnect |
| `passwordLoginMintsAWorkingTicket` | password sign-in, cookie retention and ticket exchange against a real server |

Timeouts are generous (6–8 minutes) because these drive a real agent.

Testing leaves conversations behind, so `scripts/session-cleanup.py` lists them and, with
`--delete`, removes the ones this project's testing made. It matches an explicit allow-list of
titles rather than a pattern: the same server holds real conversations and a fuzzy match would
delete them.

For the emulator, forward the gateway port and rebuild:

```bash
adb reverse tcp:9119 tcp:19119
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Releases and the in-app updater

The app ships from **GitHub Releases and nowhere else**. `BuildConfig.EXTERNAL_UPDATES_ENABLED`
turns the updater off in one place for any build that must not self-update.

- Checks are throttled to once per 24 hours, and manual checks can force past the throttle.
- Release metadata is read from the GitHub API, filtered on the asset URL being a pinned release
  host before a byte moves.
- The APK is downloaded, **SHA-256 verified against the published digest**, and only then handed
  to the system installer.
- Prerelease-aware: GitHub's `/releases/latest` endpoint excludes prereleases, so the updater
  cannot use it for a beta channel.
- Update checks ignore `/releases/latest` ordering, compare versions, and let a user skip a
  version.
- Installing needs the "install unknown apps" grant; the app detects a missing grant and sends
  you to the settings screen, resuming the install on return. `REQUEST_INSTALL_PACKAGES` is
  declared in the manifest — without it `canRequestPackageInstalls()` throws.
- **Updates require the same signing certificate.** A release signed with a fresh key cannot
  update an existing installation; the beta and release channels are signed with a persistent
  release keystore for this reason. The tag workflow falls back to debug signing when no keystore
  secret is configured, and an APK signed that way will not install over a release-signed one.

`main` is the development branch, and every push and pull request to it runs
`.github/workflows/build.yml` (JDK 17, Android SDK, `assembleDebug`, `testDebugUnitTest`;
the live tests skip there because the repository carries no credential).

## Design system notes

The design system is centralised rather than sprinkled: `ui/theme/{Color,Type,Shape,Motion,
Theme}.kt` plus a shared component kit (`Expressive.kt`, `Surfaces.kt`, `NavBar.kt`).

- **Motion is split by purpose.** Overshooting springs for spatial change, non-overshooting
  effect springs for colour and alpha, and settled specs when the user asks for reduced motion.
  The helpers in `Motion.kt` are preference-aware, so reduced motion is honoured by construction
  rather than by a flag check at each call site.
- **Buttons use the M3E morphing overloads.** `shapes = ButtonDefaults.shapes()` and
  `IconButtonDefaults.shapes()` — passing a `RoundedCornerShape` selects the non-morphing
  overload and pins a static outline, which silently kills the press shape morph.
- **Haptics are semantic, not amplitudes.** A `HapticCue` maps to the right API level for the
  device, the intensity is a user setting, and the vocabulary distinguishes a tap, a detent, a
  refresh gesture and a destructive confirm. Incoming text and scroll feedback have their own
  cues and their own switches, and vibration effects are memoised and quantised so high-frequency
  streaming ticks do not rebuild effects on the main thread.
- **Scroll haptics come from nested-scroll input**, so programmatic streaming auto-scroll does
  not machine-gun the motor at the user.
- **Grouping uses one connected toggle tray** (`ButtonGroupDefaults` connected shapes,
  `ConnectedSpaceBetween`) for session filters, enumerated preferences and capability tabs.
- **Navigation is a floating expressive toolbar** plus a large action button rather than a bottom
  bar. It is an intentional overlay: lists pass beneath it while scrolling, and bottom padding
  keeps terminal content clear.

## Protocol traps worth knowing

Things that looked fine and were not, all verified against a live server. `docs/PROTOCOL.md` has
the full list.

| Trap | Consequence |
|---|---|
| A session is not persisted until its first turn completes | `session.list` omits it, and `session.delete` answers `4023 cannot delete an active session` while it is open |
| `session.create` silently ignores unknown parameters | a renamed parameter fails with no error at all |
| Every interaction method reads a different field, and defaults when it is missing | a shared `response` field matched nothing and re-read as `deny`; the buttons looked like they worked |
| An unanswered approval is failed closed by the gateway (~60s) | the card kept offering buttons for an action that no longer existed, so a turn ending now retires unanswered requests |
| `clarify.respond` needs `question_id` | without it the gateway answers `{"status":"ok"}` while the tool receives nothing |
| `clarify.respond` returns `remaining` and can return `status: "expired"` | the tool is released only when `remaining` is empty; an expired request must be closed, not claimed as answered |
| `session.branch` rejects the stored session id with `4001` | branching from a row has to resume first |
| `session.close` invalidates the runtime handle | reusing it afterwards can answer `4007 session not found` |
| Event sequence numbers are per-session and valid only inside the active `replay_epoch` | stale watermarks must be discarded when the epoch changes |
| A resumed session replays `pending_approval` | an approval raised while the socket was down still arrives |
| `gateway.ready` carries no version field | the client's `serverVersion` stays null for it |
| `session.list` can lag a branch-snapshot flush | a just-created branch may not be visible yet |

## Verification

What has been checked, and how — because "it builds" is not the same claim as "it works".

**Transport and the agent loop.** The JVM suite passes with no failures; the seven live tests run
against a real gateway through the SSH tunnel, including a granted approval proven to have run
its command and a clarify batch whose answers the agent echoed back.

**Haptics, at the platform rather than the source.** The emulator's vibrator service keeps an
aggregated history of the effects an app actually played, and `com.materialagent.debug` has
entries matching the cue table primitive for primitive and amplitude for amplitude:

```
Primitive=TICK(scale=0.80, delay=0ms)                                  -> SENT
Primitive=TICK(scale=0.36, delay=0ms)                                  -> STREAM_TICK  (0.8 * 0.45)
[TICK(0.56), TICK(0.28, delay=60ms)]                                   -> TURN_COMPLETE
[TICK(0.80), TICK(0.80, delay=90ms), TICK(1.00, delay=90ms)]           -> NEEDS_ATTENTION
[TICK(0.56), TICK(0.56, delay=70ms)]                                   -> TOOL_START
```

Check it yourself while the app is doing something:

```bash
adb shell dumpsys vibrator_manager | sed -n '/Aggregated vibration history/,$p'
```

**Motion, from the frames rather than the code.** A recorded navigation was decomposed into
frames and consecutive frames compared: a real animation shows a run of frames each differing
slightly, a cut shows one spike among identical frames. The frames show both screens blended at
partial opacity for about ten frames (~400 ms) while the navigation pill's label grows between
states, with deltas decaying (0.25 → 0.04) as the spring settles, and 0.00 outside the transition.

**Approvals, end to end.** Pressing **Allow once** made `rm -rf /tmp/probe-dir` actually run
(the directory was gone on the server), and the denial path left it in place.

## Limitations and verification gaps

- **Sudo and credential prompts are not driven live.** They are implemented and unit-tested
  against the gateway's payload shapes, but reaching `sudo.request`/`secret.request` needs a host
  prompt that the test setup does not produce.
- **A clarify question that offers choices can only be answered from those choices.** Free text
  is still sent for a question without any, but the card does not offer a text field for a
  question that has options, since the agent cannot use an unoffered value.
- **Haptics are verified at the platform, not at the hand.** The emulator exposes a vibrating
  device supporting `COMPOSE_EFFECTS` and the `TICK`/`LOW_TICK` primitives, so the recorded
  effects are checkable; how they actually feel needs a motor.
- **Screenshots are from one emulator and one gateway**, at one point in a fast-moving beta. They
  show the debug build against the reference server, not every state the app can reach.
- **No instrumented UI test suite.** There is no `app/src/androidTest` source set; UI behaviour
  is verified by hand on the emulator, and the Compose-level logic that can be tested headlessly
  (search, the pull ratchet, scroll gating, grouping, palette) is covered by JVM tests instead.
- **This is a beta.** Version `1.0.0-beta.N`; the gateway protocol has moved under the client
  more than once, and `docs/PROTOCOL.md` records what was true when.

## Docs

- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — the gateway protocol as verified against a live
  server, including the behaviours that are not obvious from reading it.
- [`docs/PLAN.md`](docs/PLAN.md) — product surfaces, the design-system plan, and the execution
  order.
- [`scripts/tunnel.sh`](scripts/tunnel.sh) — the SSH forward the live tests want.
- [`scripts/session-cleanup.py`](scripts/session-cleanup.py) — removes the sessions testing left
  behind, by allow-list.