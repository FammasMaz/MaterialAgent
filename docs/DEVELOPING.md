# Developing MaterialAgent

Build, test, internals and the gateway behaviours that shaped the client. If you just want to run
the app, the [README](../README.md) is the shorter read.

## Build

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/sdk

./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM suite; live tests skip without a gateway

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build carries the `.debug` application-id suffix, so it installs alongside a release
build instead of replacing it: `com.materialagent.debug` and `com.materialagent` can both exist on
one device, and each needs its own sign-in.

For an emulator talking to a gateway forwarded to the host:

```bash
adb reverse tcp:9119 tcp:19119
```

### Toolchain

| Component | Version |
| --- | --- |
| Kotlin / Android Gradle Plugin / Gradle | 2.3.0 / 8.13.2 / wrapper on JDK 17 |
| Compose BOM / Material 3 Expressive | 2024.12.01 / 1.5.0-alpha15 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| OkHttp / kotlinx-serialization / coroutines / Coil | 4.12.0 / 1.7.3 / 1.9.0 / 2.5.0 |
| App version | 1.0.0-beta.13 (versionCode 15) |

Release builds minify and shrink resources, so a bug that only reproduces in release is worth
re-checking under the same R8 rules. Updates require the same signing certificate as the installed
build, which is why betas and releases share one persistent keystore.

## Architecture

```
app/src/main/java/com/materialagent/
  core/     # Android-free: transport, wire models, reducers, media parsing
  data/     # connection policy, chat controller, update pipeline
  ui/       # Compose: theme, components, screens, haptics
  update/   # release version comparison
```

- `core/` has no Android dependencies, so protocol behaviour is unit-testable without Compose or a
  device — it is why the protocol suite needs no emulator.
- `HermesConnection` owns credential resolution, socket lifecycle, retry and reconnect policy, so
  no screen touches the socket; blocking sign-in work is confined to `Dispatchers.IO`.
- `ChatController` lives in the container rather than the screen, so a live turn survives screen
  recreation and navigation; `ChatViewModel` only owns view-local state such as the draft.
- Feature ViewModels are resolved from the shared `AppContainer`, keeping dependency construction
  at the application boundary.
- One design system: colour, type, shape, motion, haptics and the reusable surfaces live in
  `ui/theme` and `ui/components`, and screens compose them instead of restating values.

Two conventions the gateway forces:

| Convention | Why |
| --- | --- |
| `prompt.submit`, `session.steer`, `session.interrupt`, `session.history` and `session.branch` take the **runtime** id; `session.title` and `session.delete` take the **stored** id | the API keeps the two namespaces separate instead of accepting an ambiguous nullable id; `ChatController.withLiveSession` retries once through `session.resume` when a call answers `4001`, or `4007` after a reconnect |
| Every interaction method reads a **different** parameter, each defaulting when its key is missing | `approval.respond` reads `choice`, `clarify.respond` reads `answer`, `sudo.respond` reads `password` — the shapes cannot be shared, so the builders are kept together in `InteractionParams`, one per method |

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
what a passing run looks like. Real per-test durations in
`app/build/test-results/testDebugUnitTest/*.xml` are the evidence.

### The live gateway suite

`HermesLiveTest` holds the round trips. Each calls `assumeTrue` on a configured gateway, so a
clean checkout and CI stay green and the live tests **skip** rather than fail.

```bash
scripts/tunnel.sh start          # idempotent SSH forward, waits for /api/health
# or: ssh -N -L 19119:127.0.0.1:9119 <your-server>

export HERMES_TEST_HTTP='http://127.0.0.1:19119'
export HERMES_TEST_USER='<username>'
export HERMES_TEST_PASSWORD='<dashboard password>'

./gradlew :app:testDebugUnitTest --tests '*HermesLiveTest*' --rerun-tasks --no-build-cache
```

Credentials stay outside the repository; `.hermes-test-token` is git-ignored, and the tunnel
script deliberately has no default token.

| Live test | What it pins |
| --- | --- |
| `realGatewayTurnRoundTrip` | the whole turn lifecycle: `message.delta` through the authoritative `message.complete`, usage parsing, session persistence, history rows, close and delete |
| `attachmentUploadRoundTrip` | the two attach RPCs and the `@file:` reference the client has to put back into the prompt |
| `branchNeedsTheRuntimeSessionId` | `session.branch` rejects the stored id with `4001`, so a row must resume first |
| `approvalResponseUsesTheServersParameterNames` | a real approval: the card's payload, the parameter names, and that a granted `once` actually ran the command |
| `clarifyAnswersAreMatchedToTheirQuestion` | a two-question batch where each answer moves `remaining` from `[q1]` to `[]` and the agent's finished text carries both options |
| `runtimeIdSurvivesASocketDrop` | a dropped socket does not invalidate the runtime id, so session-scoped calls stay valid after a reconnect |
| `passwordLoginMintsAWorkingTicket` | password sign-in, cookie retention and ticket exchange against a real server |

Timeouts are generous (six to eight minutes) because these drive a real agent. Testing leaves
conversations behind, so `scripts/session-cleanup.py` lists them and, with `--delete`, removes the
ones this project's testing made — it matches an explicit allow-list rather than a pattern,
because the same server holds real conversations.

### A fake gateway for UI work

`scripts/demo-gateway.py` speaks enough of the protocol to run the app with no server at all: it
synthesises sessions, a streaming turn, an approval, a clarify batch, an audio message, the model
catalogue and usage blocks, and it holds no real data. Run it with `--port` and point the app at
the host the emulator can reach (`http://10.0.2.2:<port>`). It is what the README screenshots were
captured against.

## Updates and releases

- The app ships from **GitHub Releases and nowhere else**; `BuildConfig.EXTERNAL_UPDATES_ENABLED`
  turns self-updating off in one place for builds that must not update themselves.
- Checks are throttled to once per 24 hours; manual checks force past the throttle, and a user can
  skip a version.
- Release metadata comes from the GitHub API, filtered on the asset URL being a pinned release
  host; the APK is downloaded, its **SHA-256 verified against the published digest**, and only then
  handed to the installer. The workflow publishes a `.sha256` asset next to the APK and the client
  also accepts the body digest as a fallback.
- Prerelease-aware: `/releases/latest` excludes prereleases, so a beta channel cannot use it.
- Installing needs the "install unknown apps" grant; the app detects a missing grant, sends you to
  the settings screen, and resumes the install on return. `REQUEST_INSTALL_PACKAGES` is declared
  in the manifest — without it `canRequestPackageInstalls()` throws.
- `main` is the development branch; every push and pull request runs
  `.github/workflows/build.yml` (JDK 17, Android SDK, `assembleDebug`, `testDebugUnitTest` — the
  live tests skip there, since the repository carries no credential). Releases are cut by
  dispatching `.github/workflows/release.yml`.

## Design-system notes

- **Motion is split by purpose**: overshooting springs for spatial change, non-overshooting effect
  springs for colour and alpha, and settled specs under reduced motion. The helpers in `Motion.kt`
  read the preference, so reduced motion holds by construction rather than by a flag check at each
  call site.
- **Buttons use the M3E morphing overloads** — `ButtonDefaults.shapes()`,
  `IconButtonDefaults.shapes()`. Passing a `RoundedCornerShape` silently selects the non-morphing
  overload and pins a static outline, which kills the press shape morph.
- **Haptics are semantic, not amplitudes.** A `HapticCue` maps to the right API level for the
  device, intensity is a user setting, and the vocabulary distinguishes a tap, a detent, a refresh
  gesture and a destructive confirm. Incoming text and scroll feedback have their own cues and
  switches, vibration effects are memoised and quantised so high-frequency streaming ticks do not
  rebuild them on the main thread, and scroll haptics come from nested-scroll input so
  programmatic auto-scroll never machine-guns the motor.
- **Session filters, enumerated preferences and capability tabs** share one connected toggle tray
  (`ButtonGroupDefaults` connected shapes, `ConnectedSpaceBetween`).
- **Navigation is a floating expressive toolbar** with a large action button rather than a bottom
  bar. It is an intentional overlay: lists pass beneath it while scrolling, and bottom padding
  keeps terminal content clear.

## Protocol traps

All verified against a live server; `docs/PROTOCOL.md` has the full list.

| Trap | Consequence |
| --- | --- |
| A session is not persisted until its first turn completes | `session.list` omits it, and `session.delete` answers `4023 cannot delete an active session` while it is open |
| `session.create` silently ignores unknown parameters | a renamed parameter fails with no error at all |
| Every interaction method reads a different field, defaulting when it is missing | a shared `response` field matched nothing and re-read as `deny`; the buttons only looked like they worked |
| The gateway fails an unanswered approval closed after about a minute | the card must retire the request when its turn ends, not keep offering an action that no longer exists |
| `clarify.respond` needs `question_id` | without it the gateway answers `{"status":"ok"}` while the tool receives nothing |
| `clarify.respond` returns `remaining`, and can return `status: "expired"` | the tool is released only when `remaining` is empty; an expired request must be closed, not claimed as answered |
| `session.branch` rejects a stored session id with `4001` | branching from a row has to resume first |
| `session.close` invalidates the runtime handle | reusing it afterwards can answer `4007 session not found` |
| Sequence numbers are per-session and valid only inside the active `replay_epoch` | stale watermarks must be discarded when the epoch changes |
| A resumed session replays `pending_approval` | an approval raised while the socket was down still arrives |
| `gateway.ready` carries no version field | the gateway's version comes from `GET /api/health` instead, which answers without a session |
| `tool.*` events exist only in `session.history` | a live turn streams `thinking.delta`/`message.delta` and nothing about the command it is running; the `terminal` rows appear when the conversation is replayed |
| `session.list` can lag a branch-snapshot flush | a just-created branch may not be visible yet |

## Verified, and what is not

- The JVM suite passes with no failures, and the live tests run against a real gateway over an SSH
  tunnel: a granted approval is proven to have run its command, and a clarify batch's answers came
  back from the agent.
- Reconnect recovery is proven by cutting the network for a minute and watching the foreground
  liveness probe redial, with the next turn landing in `session.history`. The retry watcher was also
  watched on a device: killing the gateway mid-session showed `Reconnecting (attempt 4)…`, and
  restarting it reconnected on its own, with no manual Retry.
- `liveTurnReportsToolActivityTheSameWayHistoryDoes` runs a command that genuinely executes (its
  marker comes back in the reply) and records the live vocabulary: `approval.request` arrives live,
  `tool.start`/`tool.complete` do not. They are replay-only, so a transcript that grows tool rows
  only after a reopen is behaving correctly.
- **Haptics are verified at the platform, not at the hand.** `adb shell dumpsys vibrator_manager`
  keeps an aggregated history of the effects an app actually played, and the debug package's
  entries match the cue table primitive for primitive — but how they feel needs a motor.
- **Motion is verified from frames**, not from reading the code: a recorded navigation was
  decomposed into frames and consecutive frames compared, showing blended screens and decaying
  spring deltas rather than a cut.
- **Not driven live**: `sudo.request` and `secret.request`, which are implemented and unit-tested
  against the gateway's payload shapes only. There is no instrumented UI test suite; UI behaviour
  is checked by hand, and the parts that can be tested headlessly are JVM tests.

## Docs

- [`PROTOCOL.md`](PROTOCOL.md) — the gateway protocol as verified against a live server
- [`PLAN.md`](PLAN.md) — product surfaces, design-system plan, execution order
- [`audit-m3e.md`](audit-m3e.md) — the Material 3 Expressive conformance audit
- [`../scripts/demo-gateway.py`](../scripts/demo-gateway.py) — a fake gateway for local UI work
- [`../scripts/tunnel.sh`](../scripts/tunnel.sh) — the SSH forward the live tests want
- [`../scripts/session-cleanup.py`](../scripts/session-cleanup.py) — removes the sessions testing left behind
