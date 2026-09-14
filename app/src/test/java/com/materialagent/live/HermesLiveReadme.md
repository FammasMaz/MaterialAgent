# Hermes live test

`HermesLiveTest.kt` drives the real `HermesClient` against a real Hermes gateway
over a real WebSocket. It is the only test here that needs a server.

The dev gateway binds to `127.0.0.1:9119` on the home server, so the laptop
reaches it through an SSH tunnel:

    ./scripts/tunnel.sh        # start the forward (idempotent), print the WS URL
    ./scripts/tunnel.sh status # up or down?
    ./scripts/tunnel.sh stop   # tear down the forward this script started

It polls `http://127.0.0.1:19119/api/health` until the gateway answers, then
prints the WS URL. Override via `HERMES_TUNNEL_HOST`, `HERMES_TUNNEL_LOCAL_PORT`,
`HERMES_TUNNEL_REMOTE_PORT`, `HERMES_TEST_TOKEN`.

Run it:

    export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
    ./gradlew :app:testDebugUnitTest --tests '*HermesLiveTest*'

Point it elsewhere with `HERMES_TEST_WS`. With nothing listening the test is
**skipped** (`assumeTrue`), never failed, so offline builds stay green. A real
turn takes 5-90 s, so the budget is 120 s and a rate-limited provider will time
out here even though the transport is fine.

What it asserts:

1. The socket reaches `ConnectionState.OPEN` (the UI's `Connected`), and
   `gateway.ready` delivers a skin.
2. `session.list` parses through `SessionSummary.from`; an empty catalogue is
   valid, so only the shape is asserted.
3. `session.create` returns non-blank `session_id` and `stored_session_id`.
4. `prompt.submit` ("Reply with exactly the word: pong") yields a
   `message.delta`-family event, a `message.complete` and non-empty assistant
   text; a `turn.error` fails at once with the gateway's own payload.
5. The stored session later shows up in `session.list` with `source=mobile`;
   title text is never asserted, since titles are generated asynchronously.
6. `session.close` then `session.delete`, so runs leave no sessions behind.

The transcript goes to stdout; Gradle files it under `app/build/reports/tests/`.

Known limits: the server needs a model configured, else the turn fails and the
gateway's own words are reported rather than retried. `session.create` params are
not validated server-side (unknown keys are ignored), so a renamed param fails
silently — `source=mobile` is the only assertion that catches that. A session is
not persisted until its first turn completes, so it is absent from
`session.list`, and `session.delete` rejects it until then. JVM only.
