# MaterialAgent

An Android client for a Hermes agent server, built with Jetpack Compose and Material 3
Expressive.

Point it at your own `hermes serve` instance and you get the whole agent loop on a phone:
live streaming transcripts, reasoning and tool cards, approvals, model and reasoning-effort
switching, steering mid-turn, and the server's model, toolset, skill and MCP catalogue.

| Connect | Sessions | Conversation |
| --- | --- | --- |
| ![Connect](image.png) | ![Sessions](image.png) | ![Chat](image.png) |

| Capabilities | Model picker | Dark |
| --- | --- | --- |
| ![Tools](image.png) | ![Models](image.png) | ![Dark](image.png) |

## What it does

**Transport.** One WebSocket to `/api/ws`, newline-delimited JSON-RPC 2.0 in both directions,
server pushes arriving as `event` notifications. `HermesClient` owns the socket: 15-second
heartbeats, a 45-second silence deadline, per-session sequence watermarks, `replay_epoch`
tracking, and `session.events.since` replay after a dropped connection. Credentials are either
a loopback token or a single-use WebSocket ticket minted from the dashboard password, and
`HermesConnection` owns that policy so no screen touches the socket directly.

**The agent loop.** `ChatReducer` folds gateway events into a transcript: streamed text with a
caret, reasoning blocks that stay collapsed until you ask, tool cards that show the command
they ran and expand to arguments and result, todo lists, approvals and clarifying questions,
compaction notes, interruptions, and elapsed-work feedback. Sending while a turn runs becomes
steering instead of queueing, and the composer's send button becomes a stop button.

**Everything the server advertises.** Models (1485 on the reference server, so the picker is
search-first), toolsets with per-toolset toggles, skills, MCP servers, and live context-window
and throughput usage.

## Material 3 Expressive

The design system is centralised rather than sprinkled: `Color.kt`, `Type.kt`, `Shape.kt`,
`Motion.kt`, `Theme.kt`, plus a shared component kit (`Expressive.kt`, `Surfaces.kt`,
`NavBar.kt`). Notable choices:

- **Motion** is split by purpose — overshooting springs for spatial changes, non-overshooting
  effect springs for colour and alpha, and settled specs when the user asks for reduced motion.
- **Haptics** are semantic (`HapticCue`) rather than raw amplitudes, so the same cue maps to
  the right API level on any device, and the level is a user setting.
- **Branding** is a vector Hermes mark that carries a gradient as a hero and falls back to a
  single gold accent at small sizes, because blending indigo into gold across a 40dp avatar
  passes through khaki.
- **Navigation** is a floating expressive toolbar plus a large action button rather than a
  bottom bar.

## Build

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 61 JVM tests (the live ones skip themselves
                                      # unless a gateway is listening)
```

Minimum SDK 26, compile and target SDK 36, Kotlin 2.3.0, AGP 8.7.3, Compose BOM 2024.12.01,
OkHttp 4.12.0, kotlinx-serialization 1.7.3.

## Testing against a real server

The JVM tests include a live round trip that skips itself unless a gateway is listening. Run a
server on `home-server`, forward it, and point the test at it:

```bash
ssh -N -L 19119:127.0.0.1:9119 home-server &     # or scripts/tunnel.sh start

# The token is never stored in this repository. Keep it in the environment, or
# in .hermes-test-token (gitignored) and scripts/tunnel.sh will pick it up.
printf '%s' "$YOUR_TOKEN" > .hermes-test-token

HERMES_TEST_TOKEN="$(cat .hermes-test-token)" \
  ./gradlew :app:testDebugUnitTest --tests '*HermesLiveTest*'
```

Without a token the live tests skip rather than fail, so a clean checkout and CI
both stay green. The dev server takes its token from `HERMES_DASHBOARD_SESSION_TOKEN`.

It connects, lists sessions, creates one, sends a turn, waits for `message.delta` and the
authoritative `message.complete`, parses usage, confirms the session persisted, reads history
rows, then closes and deletes it. Four live tests now run against a real server, each pinning a
shape that is easy to get wrong:

| Test | What it pins |
|---|---|
| `realGatewayTurnRoundTrip` | the whole turn lifecycle, plus the persisted-session and event-shape contracts |
| `branchNeedsTheRuntimeSessionId` | `session.branch` rejects the stored id with `4001`, so a row has to resume first |
| `approvalResponseUsesTheServersParameterNames` | a real Tier-2 approval: the card's payload, the `approval.respond` parameter names, and that a granted `once` actually runs the command |
| `runtimeIdSurvivesASocketDrop` | a dropped socket does not invalidate the runtime id, so session-scoped calls stay valid after a reconnect |

For the emulator, forward the same port and rebuild:

```bash
adb reverse tcp:9119 tcp:19119
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Docs

- `docs/PROTOCOL.md` — the gateway protocol as verified against a live server, including the
  behaviours that are not obvious from reading it (a session is not persisted until its first
  turn completes; `gateway.ready` carries no version; unknown `session.create` params are
  ignored silently).
- `docs/PLAN.md` — product surfaces, the design-system plan, and the execution order.

## Approvals

![An approval card waiting on a destructive command](image.png)

A destructive shell command is the one place the agent stops and asks, and the card that asks has
to be exactly right — the tool is blocked until it is answered. It was wrong in three ways at once:

- The gateway names its allowed answers in the `approval.request` payload (`once`, `session`,
  `always`, `deny`, with `always` simply absent when policy forbids it). The reducer dropped them,
  so the card rendered as a bare text field with no way to approve.
- Every interaction method reads a **different** parameter, and falls back to a default when the
  key is missing — `approval.respond` reads `choice` and would otherwise resolve as `"deny"`,
  `clarify.respond` reads `answer`, `sudo.respond` reads `password`, `secret.respond` reads
  `value`. The app sent one shared `"response"` field, so approvals reached the server, matched
  nothing, and quietly denied themselves while the buttons looked like they worked.
- An unanswered approval is failed closed by the gateway (it timed out after 60s here and the
  tool returned "blocked"). The card kept showing "Waiting" with live buttons afterwards, offering
  an action that no longer existed.

All three are fixed: the choices drive the buttons (`Allow once` / `Allow for this session` /
`Always allow` / a quieter `Deny`, with the server's own token sent back), each method sends the
parameters its server counterpart reads (`InteractionParams`), and a turn ending retires whatever
is still unanswered. The whole path is verified rather than assumed — pressing **Allow once** in
the emulator made `rm -rf /tmp/probe-dir` actually run (the directory was gone when checked on the
server), and the denial path left it in place.

## Verification

What has been checked, and how — because "it builds" is not the same claim as "it works".

**Transport and agent loop.** 62 JVM tests, no failures, four of which run against a live gateway
through the tunnel to `home-server`: a full turn round trip (`message.delta` … authoritative
`message.complete`), the branching contract including the rejected stored-id call, an approval
round trip with the granted command proven to have run, and runtime-id stability across a socket
drop.

**Haptics, at the platform rather than the source.** The emulator's vibrator service keeps an
aggregated history of the effects an app actually played, and `com.materialagent.debug` has
entries that match the cue table primitive for primitive and amplitude for amplitude:

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

**Motion, from the frames rather than the code.** Screen-recorded a navigation, extracted the
frames, and compared consecutive ones — a transition that is really animated shows a run of
frames each differing slightly; a cut shows one spike among identical frames. The recorded
frames show both screens blended at partial opacity for about ten frames (~400 ms) while the
navigation pill's label grows between states, with the deltas decaying (0.25 → 0.04) as the
spring settles. Frames outside the transition measure 0.00, so the measurement is picking up
real change rather than encoder noise.

## Known gaps

- Clarifying questions, sudo prompts and credential prompts are implemented and unit-tested
  against the gateway's payload shapes, but only the approval card has been driven end to end
  against a real server — reaching `clarify.request` needs an agent that chooses to ask, and
  `sudo.request`/`secret.request` need a host prompt.
- Haptics fire for real — the emulator exposes a vibrating device that supports `COMPOSE_EFFECTS`
  and the `TICK`/`LOW_TICK` primitives, so the platform-level record is checkable. See
  *Verification* below; what is *not* covered is how they actually feel, which needs a motor.
- Screenshots above are from a 1080×1920 arm64 emulator running the debug build.

Branching is worth calling out because it looked fine and was not. `session.branch` identifies
its source by the **runtime** id, and answers `4001 session not found` for the stored id that
`session.list` shows and every other session method accepts — so branching from a row (which
only knows the stored id) has to resume the session first, and a conversation that is already
open has to branch by its runtime id. The screen also had to be told to re-open the new
argument, because branching navigates to a sibling conversation at the same destination and the
navigation is single-top, so the ViewModel is reused. Both paths are now exercised by hand
*and* pinned by `HermesLiveTest.branchNeedsTheRuntimeSessionId`, which asserts the 4001 for the
stored id so the extra round trip can't be "simplified" away.

Session-scoped calls are guarded separately: `ChatController.withLiveSession` retries once through
`session.resume` when a call answers `4001`, so a runtime id that has gone stale (one was observed
after a reconnect plus relaunch) recovers instead of failing the turn. It is deliberately narrow —
only `4001`, only one retry, and the original failure is returned if the resume does not help. A
socket drop on its own does *not* invalidate the runtime id; `HermesLiveTest.runtimeIdSurvivesASocketDrop`
pins that so the guard is not mistaken for the explanation.
