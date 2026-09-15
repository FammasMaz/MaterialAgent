# MaterialAgent

A Jetpack Compose, Material 3 Expressive Android client for a **Hermes** agent gateway. Point it
at your own `hermes serve` and the whole agent loop is on your phone: streaming transcripts,
reasoning and tool rows, approvals, mid-turn steering, media, and the server's model, toolset,
skill and MCP catalogue.

[![Build](https://github.com/FammasMaz/MaterialAgent/actions/workflows/build.yml/badge.svg)](https://github.com/FammasMaz/MaterialAgent/actions/workflows/build.yml)

## What it does

- **The agent loop.** `ChatReducer` folds gateway events into a transcript: streamed text with a
  caret, collapsible reasoning, tool cards that expand to arguments and result, todo lists,
  approvals and clarifying questions, compaction notes, interruptions and elapsed-work feedback.
  Sending while a turn runs steers it instead of queueing, and send becomes stop.
- **One WebSocket** to `/api/ws`, newline-delimited JSON-RPC 2.0 both ways, server pushes arriving
  as `event` notifications — 15-second heartbeats, a 45-second silence deadline, per-session
  sequence watermarks, `replay_epoch` tracking and `session.events.since` replay after a drop.
- **Everything the server advertises**: models (the picker is search-first, because a real server
  advertises four figures' worth), toolsets with per-connection toggles, skills, MCP servers, and
  live context-window and throughput usage.
- **Cron runs stay readable.** Each firing stores its own session, so a handful of automations
  turns the inbox into near-identical rows; every run of one job folds under a single collapsible
  header instead — newest first, on by default, switchable in Settings.
- **Interaction cards use the server's own vocabulary.** Approval buttons are rendered from
  `approval.request.choices` rather than hardcoded, and clarify batches track `remaining` per
  `qid`.
- **Media both ways.** Incoming: the agent writes `MEDIA:<path>` markers into its prose, the app
  strips them and fetches through `/api/media` (images), `/api/files/stream` (audio, with Range so
  seeking works) and `/api/files/download`. Outgoing: images as `image.attach_bytes`, everything
  else as `file.attach` with an `@file:` reference, because the gateway has no upload endpoint a
  chat turn can use.
- **Branching and search** across the session list, including by capability.

## Install

Signed APKs live in [Releases](https://github.com/FammasMaz/MaterialAgent/releases). Or build it:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/sdk

./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM suite; live tests skip without a gateway

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build carries the `.debug` application-id suffix, so it installs alongside a release
build rather than replacing it — `com.materialagent.debug` and `com.materialagent` can both exist,
and each needs its own sign-in.

## Connect

The app talks to a `hermes serve` instance you run. Nothing is bundled or hosted.

1. **Address** — `host:port` of the gateway, e.g. `192.168.1.27:9119`. A path is allowed; the
   client appends `/api/ws` to derive the socket URL.
2. **Auth** — `Access token` (loopback/dev tokens) or `Password`.
3. **Credentials** — with `Password` the app signs in with a username and the dashboard password,
   keeps the session cookie, and exchanges it for a single-use WebSocket ticket. The password
   never touches the socket.
4. **Nickname** — optional label for the saved connection.

Auth is not optional on a reachable server: setting `dashboard.public_url` activates
authentication even on loopback, and Hermes refuses to start without a registered provider. There
is no unauthenticated public-dashboard mode. For a remote gateway, expose it over Tailscale
`serve` (tailnet-only, not Funnel) and connect to the hostname in `dashboard.public_url` — as a
DNS-rebinding defence the dashboard accepts loopback or that one hostname, not a raw tailnet IP.
For local work, an SSH forward plus `adb reverse` is enough (see Testing).

## Toolchain

| Component | Version |
| --- | --- |
| Kotlin / Android Gradle Plugin / Gradle | 2.3.0 / 8.13.2 / wrapper on JDK 17 |
| Compose BOM / Material 3 Expressive | 2024.12.01 / 1.5.0-alpha15 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| OkHttp / kotlinx-serialization / coroutines / Coil | 4.12.0 / 1.7.3 / 1.9.0 / 2.5.0 |
| App version | 1.0.0-beta.13 (versionCode 15) |

Release builds minify and shrink resources, so a bug that only reproduces in release is worth
re-checking under the same R8 rules.

## Architecture

```
app/src/main/java/com/materialagent/
  core/     # Android-free: transport, wire models, reducers, media parsing
  data/     # connection policy, chat controller, update pipeline
  ui/       # Compose: theme, components, screens, haptics
  update/   # release version comparison
```

- `core/` has no Android dependencies, so protocol behaviour is unit-testable without Compose or
  a device.
- `HermesConnection` owns credential resolution, socket lifecycle, retry and reconnect policy, so
  no screen touches the socket; blocking sign-in work is confined to `Dispatchers.IO`.
- `ChatController` lives in the container rather than the screen, so a live turn survives screen
  recreation and navigation.
- One design system: colour, type, shape, motion, haptics and the reusable surfaces live in
  `ui/theme` and `ui/components`, and screens compose them instead of restating values.

Two conventions the gateway forces:

| Convention | Why |
| --- | --- |
| `prompt.submit`, `session.steer`, `session.interrupt`, `session.history` and `session.branch` take the **runtime** id; `session.title` and `session.delete` take the **stored** id | the API keeps the two namespaces separate instead of accepting an ambiguous nullable id; `ChatController` retries once through `session.resume` when a call answers `4001`, or `4007` after a reconnect |
| Every interaction method reads a **different** parameter, each defaulting when its key is missing | `approval.respond` reads `choice`, `clarify.respond` reads `answer`, `sudo.respond` reads `password` — the shapes cannot be shared, so they live together in `InteractionParams` |

## Testing

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks --no-build-cache
```

288 JVM cases in `app/src/test` cover the wire protocol, the transcript reducer, media-marker
parsing and URL building, the outgoing attachment contract, session grouping, model search,
scroll-tick gating, the pull-reveal ratchet, effective palette, version comparison and the update
pipeline.

`--rerun-tasks --no-build-cache` is **not optional** when you need evidence: the test environment
is not a declared task input, so Gradle treats the task as up to date and then restores the
previous results XML — reporting the last run's skips as if it had just executed, which is exactly
what a passing run looks like.

Live round trips live in `HermesLiveTest` and, because each calls `assumeTrue` on a configured
gateway, a clean checkout and CI **skip** them rather than fail:

```bash
scripts/tunnel.sh start          # idempotent SSH forward; or: ssh -N -L 19119:127.0.0.1:9119 <your-server>

export HERMES_TEST_HTTP='http://127.0.0.1:19119'
export HERMES_TEST_USER='<username>'
export HERMES_TEST_PASSWORD='<dashboard password>'
./gradlew :app:testDebugUnitTest --tests '*HermesLiveTest*' --rerun-tasks --no-build-cache
```

They pin the turn lifecycle, attachment upload, runtime-versus-stored ids, approvals (a granted
`once` really ran the command), a two-question clarify batch, reconnect, and password ticket
login. Credentials stay outside the repository. `scripts/session-cleanup.py` deletes the sessions
testing leaves behind, by explicit allow-list — the same server holds real conversations, so it
never pattern-matches.

## Updates

The app ships from GitHub Releases and nowhere else, and `BuildConfig.EXTERNAL_UPDATES_ENABLED`
turns self-updating off in one place. Checks are throttled to once a day (manual checks bypass the
throttle), release metadata is filtered on the asset URL being a pinned release host, and the
APK's SHA-256 is verified against the published digest before the installer ever sees it.
Prereleases are supported because GitHub's `/releases/latest` excludes them. Installing needs the
"install unknown apps" grant, which the app detects and resumes after. Updates require the same
signing certificate, so betas and releases share one persistent keystore.

## Design notes

- **Motion is split by purpose**: overshooting springs for spatial change, non-overshooting
  effect springs for colour and alpha, and settled specs under reduced motion. `Motion.kt` reads
  the preference, so reduced motion holds by construction rather than by a flag check per call
  site.
- **Buttons use the M3E morphing overloads** — `ButtonDefaults.shapes()`,
  `IconButtonDefaults.shapes()`. Passing a `RoundedCornerShape` silently selects the static
  overload and pins an outline, which kills the press shape morph.
- **Haptics are semantic, not amplitudes.** A `HapticCue` maps to the right API level for the
  device and the intensity is a user setting; the vocabulary distinguishes a tap, a detent, a
  refresh gesture and a destructive confirm. Incoming text and scroll feedback have their own cues
  and switches, effects are memoised and quantised so streaming ticks do not rebuild them on the
  main thread, and scroll haptics come from nested-scroll input so programmatic auto-scroll stays
  silent.
- **Session filters, enumerated preferences and capability tabs** share one connected toggle tray
  (`ButtonGroupDefaults` connected shapes, `ConnectedSpaceBetween`).
- **Navigation is a floating expressive toolbar** with a large action button rather than a bottom
  bar. It is an intentional overlay: lists pass beneath it, and bottom padding keeps terminal
  content clear.

## Protocol traps

Everything here was verified against a live server; `docs/PROTOCOL.md` has the full list.

| Trap | Consequence |
| --- | --- |
| A session is not persisted until its first turn completes | `session.list` omits it, and `session.delete` answers `4023 cannot delete an active session` while it is open |
| `session.create` silently ignores unknown parameters | a renamed parameter fails with no error at all |
| Every interaction method reads a different field, defaulting when it is missing | a shared `response` field matched nothing and re-read as `deny`; the buttons only looked like they worked |
| The gateway fails an unanswered approval closed after about a minute | the card must retire the request when its turn ends, not keep offering an action that no longer exists |
| `clarify.respond` needs `question_id` | without it the gateway answers `{"status":"ok"}` while the tool receives nothing |
| `clarify.respond` returns `remaining` and can return `status: "expired"` | the tool is released only when `remaining` is empty; an expired request must be closed, not claimed as answered |
| `session.branch` rejects a stored session id with `4001` | branching from a row has to resume first |
| `session.close` invalidates the runtime handle | reusing it afterwards can answer `4007 session not found` |
| Sequence numbers are per-session and valid only inside the active `replay_epoch` | stale watermarks must be discarded when the epoch changes |
| A resumed session replays `pending_approval` | an approval raised while the socket was down still arrives |
| `session.list` can lag a branch-snapshot flush | a just-created branch may not be visible yet |

## Verified, and what is not

- The JVM suite passes with no failures, and the live tests run against a real gateway over an SSH
  tunnel: a granted approval is proven to have run its command, and a clarify batch's answers came
  back from the agent.
- Reconnect recovery is proven by cutting the network for a minute and watching the foreground
  liveness probe redial, with the next turn landing in `session.history`.
- **Haptics are verified at the platform, not at the hand.** `adb shell dumpsys vibrator_manager`
  keeps an aggregated history of the effects an app actually played, and the debug package's
  entries match the cue table primitive for primitive — but how they feel needs a motor.
- **Not driven live**: `sudo.request` and `secret.request`, which are implemented and unit-tested
  against the gateway's payload shapes only. There is no instrumented UI test suite; UI behaviour
  is checked by hand, and the parts that can be tested headlessly are JVM tests.
- **This is a beta.** The gateway protocol has moved under the client more than once, and
  `docs/PROTOCOL.md` records what was true when.

## Docs

- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — the gateway protocol as verified against a live server
- [`docs/PLAN.md`](docs/PLAN.md) — product surfaces, design-system plan, execution order
- [`scripts/demo-gateway.py`](scripts/demo-gateway.py) — a fake gateway that speaks enough of the protocol for local UI work
- [`scripts/tunnel.sh`](scripts/tunnel.sh) — the SSH forward the live tests want
- [`scripts/session-cleanup.py`](scripts/session-cleanup.py) — removes the sessions testing left behind
