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
./gradlew :app:testDebugUnitTest      # 57 JVM tests, no device needed
```

Minimum SDK 26, compile and target SDK 36, Kotlin 2.3.0, AGP 8.7.3, Compose BOM 2024.12.01,
OkHttp 4.12.0, kotlinx-serialization 1.7.3.

## Testing against a real server

The JVM tests include a live round trip that skips itself unless a gateway is listening. Run a
server on `home-server`, forward it, and point the test at it:

```bash
ssh -N -L 19119:127.0.0.1:9119 home-server &     # or scripts/tunnel.sh start
HERMES_TEST_WS='ws://127.0.0.1:19119/api/ws?token=<devtoken>' \
  ./gradlew :app:testDebugUnitTest --tests '*HermesLiveTest*'
```

It connects, lists sessions, creates one, sends a turn, waits for `message.delta` and the
authoritative `message.complete`, parses usage, confirms the session persisted, reads history
rows, then closes and deletes it.

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

## Known gaps

- Approval and clarifying-question cards are implemented and unit-tested, but the reference
  server's default tool set never asked for approval during manual testing, so they have not
  been seen on screen.
- Haptics are implemented per cue and configurable, but were verified by code path only — an
  emulator has no vibration motor.
- Branching a conversation is wired to `session.branch` and reachable from the row menu, but
  only the menu itself has been verified by hand.
- Screenshots above are from a 1080×1920 arm64 emulator running the debug build.
