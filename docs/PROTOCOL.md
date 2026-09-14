# Hermes Gateway Protocol

Reverse-engineered and **verified live** against `home-server`
(`hermes-agent` v0.21.0, code sha `21b2095d00`) on 2026-09-14.

Server command:

```bash
hermes serve [--host HOST] [--port PORT] [--skip-build]
# "Run the Hermes backend server — the JSON-RPC/WebSocket gateway the desktop
#  app and remote clients connect to. Headless: it never opens a browser UI."
```

The desktop client that ships with Hermes talks this exact protocol, so the
implementation here mirrors `apps/shared/src/json-rpc-gateway.ts` upstream.

---

## 1. Transport

* WebSocket at **`/api/ws`** (optionally behind a reverse-proxy sub-path).
* Frames are **newline-delimited JSON-RPC 2.0**, both directions. No length
  framing, no batching.
* Right after the socket opens the server emits `gateway.ready`.

## 2. Authentication

| Deployment | Credential | How |
|---|---|---|
| loopback / SSH tunnel | session token | `ws://127.0.0.1:9119/api/ws?token=<token>` |
| public bind, password or OAuth gate | single-use ticket | `POST /api/auth/ws-ticket` with the session cookie → `?ticket=<ticket>` (30 s TTL, single use) |
| server-spawned children only | internal credential | `?internal=…` — not usable by third-party clients |

The loopback session token is `_SESSION_TOKEN` in `hermes_cli/web_server.py`.
It is random per process **unless** the environment variable
`HERMES_DASHBOARD_SESSION_TOKEN` is set, in which case that value is used —
which is what makes a persistent mobile connection practical:

```bash
HERMES_DASHBOARD_SESSION_TOKEN=<long-random> hermes serve --host 127.0.0.1
```

`--insecure` is a **no-op** since the June 2026 hardening: a public bind always
requires an auth provider (password or OAuth), so the supported remote patterns
are *tunnel + token* or *public bind + password/OAuth login*.

Rejections close the socket with **4401** (bad credential) or **4403**
(origin/request not allowed).

## 3. Requests

```json
{"jsonrpc":"2.0","id":"r1","method":"session.create","params":{}}
```

Response:

```json
{"jsonrpc":"2.0","id":"r1","result":{ … }}
{"jsonrpc":"2.0","id":"r1","error":{"code":4001,"message":"session not found"}}
```

Observed error codes: `4001` session-not-found, `4002` unknown-config-key,
`4023` cannot-delete-active-session.

Default request timeout in the desktop client is **120 s**.

## 4. Events

```json
{"jsonrpc":"2.0","method":"event","params":{
   "type":"message.delta","session_id":"9d5dcaff","seq":7,
   "payload":{"text":"PONG"}}}
```

`seq` is a per-session in-process counter. `gateway.ready` carries a
`replay_epoch`; if the epoch changes the backend restarted and seq watermarks
must be dropped.

### Event catalogue (observed live unless marked)

| Event | Payload |
|---|---|
| `gateway.ready` | `{skin:{name,colors,light_colors,branding,…}, change_events, heartbeat:true, replay_epoch}` |
| `session.info` | `{model, provider, reasoning_effort, service_tier, fast, yolo, approval_mode, tools{set:[names]}, skills{cat:[names]}, cwd, branch, project, terminal_backend, personality, running, turn_started_at, title, stored_session_id, desktop_contract, version, usage, profile_name, mcp_servers, system_prompt}` |
| `session.title` | `{session_id, title}` (live auto-title, fires several times per turn) |
| `session.usage` | `{usage:{…}}` |
| `session.reclaimed` | `{session_id, stored_session_id, reason}` |
| `sessions.changed` | `{}` — global; re-fetch `session.list` |
| `session.resume_progress` * | progress while a big session rehydrates |
| `message.start` | `null` |
| `message.delta` | `{text}` — streamed assistant text |
| `message.interim` | `{text, already_streamed}` — sealed mid-turn commentary |
| `message.complete` | `{text, usage, status:"complete"|"error", reasoning, partial?, recoverable?, error_surface?}` |
| `reasoning.delta` | `{text}` |
| `reasoning.available` | `{text}` |
| `reasoning.encrypted_content` * | provider-encrypted reasoning blob |
| `thinking.delta` | `{text}` — spinner/status line, frequently empty; **do not** append to the answer |
| `tool.generating` | `{name}` — model is writing args |
| `tool.start` | `{tool_id, name, context, args?, args_text?}` |
| `tool.complete` | `{tool_id, name, args, duration_s, result}` |
| `tool.progress` * | long-running tool progress |
| `todo.updated` * | `{todos}` |
| `status.update` | `{kind, text}` |
| `approval.request` | `{request_id, command/description, allow_permanent, smart_denied?}` |
| `clarify.request` | `{request_id, questions[]}` for a batch (`qid`,`question`,`choices`,`multi_select` per entry); the older single-question `{question, choices?, multi_select?}` shape has no `questions[]` |
| `sudo.request` | `{request_id, …}` |
| `secret.request` | `{request_id, env_var, prompt}` |
| `turn.error` | error text |
| `background.complete` | `{task_id, summary?}` |
| `skin.changed` * | new skin |
| `error` * | generic |

\* implemented by the server but not reproduced in the probe dump; handled
defensively by the client.

**Tool events are gated by server config.** `_load_tool_progress_mode()` reads
`display.tool_progress` from `~/.hermes/config.yaml` (or
`HERMES_TUI_TOOL_PROGRESS` at process start). When it is `false` the server
emits **no** `tool.start`/`tool.complete`; the client must then reconstruct
tool activity from `session.history` rows with `role:"tool"`. MaterialAgent
handles both, and exposes a setting that turns `display.tool_progress` on via
`config.set` when the user wants live tool cards.

## 5. Method catalogue (subset used by MaterialAgent)

### Which id a method wants

Two ids exist and they are not interchangeable, which is the single most error-prone thing about
this API. The **stored** id (`20260914_042811_ec6c25`) is what `session.list` shows and what
survives restarts; the **runtime** id is a short hex handle minted by `session.create` and
returned again by `session.resume`.

| Wants the runtime id | Wants the stored id |
|---|---|
| `session.branch`, `session.history`, `session.undo` | `session.delete`, `session.title` |
| `prompt.submit`, `session.steer`, `session.interrupt` | |

The runtime id is only valid **while the session is open**: delete it while open and the gateway
answers `4023 cannot delete an active session` (so it was found), but ask again after
`session.close` and the same id answers `4007 session not found` — the handle is forgotten on
close, while the stored id still works. A call with the wrong id is not silently ignored; it
fails with `4001` (or `4023`), which is why the distinction is written down here rather than
inferred.

**Sessions** `session.create{source?,title?,cwd?,model?,provider?,reasoning_effort?,profile?,messages?,parent_session_id?}`
→ `{session_id, stored_session_id, message_count, messages, info}` ·
`session.list` → `{sessions:[{id,title,preview,started_at,message_count,source}]}` ·
`session.most_recent` → `{session_id,title,started_at,source}` ·
`session.resume{stored_id}` → `{session_id, session_key, messages, message_count, messages_omitted, running, turn_started_at, started_at, status, resumed, info}` ·
`session.history{session_id}` → `{count, messages}` — **runtime** id, like `branch`; the stored id
answers `4001 session not found` ·
`session.activate` · `session.close` → `{closed:true}` ·
`session.delete` (active session must be closed first) ·
`session.branch{session_id}` — `session_id` here is the **runtime** id from `session.create`/
`session.resume`; the stored id that `session.list` shows (and every other method accepts) is
rejected with `4001 session not found`, so a list-only session must be resumed first. The fork
happens at the last user message — that reply is *not* carried over, the title becomes
`"<source title> #2"`, and the new session records `parent` = source stored id.
Returns the new session incl. copied `messages` ·
`session.title{session_id,title}` → `{pending,title}` ·
`session.interrupt` → `{status:"interrupted"}` ·
`session.steer{session_id,text}` · `session.set_hidden` ·
`session.undo{session_id}` → `{removed:2}` — drops the **last turn** (the user message and the
reply), leaving everything before it untouched; measured on a two-turn session, which went from
four messages back to the first exchange. Runtime id. ·
`session.cwd.set` · `session.compress` · `session.context_breakdown` ·
`session.usage` → usage object · `session.status` → `{output}` (human text) ·
`session.events.since{session_id,last_seen}` → `{events:[…], epoch}`.

**Turns** `prompt.submit{session_id,text}` → `{status:"streaming"}` — the runtime id is **stable
across a socket drop**: a turn submitted on the pre-reconnect id is accepted, and `session.resume`
hands back the same id (pinned by `HermesLiveTest.runtimeIdSurvivesASocketDrop`) ·
`prompt.background` · `prompt.btw`.

**Interactions** `approval.pending` · `approval.respond{session_id,request_id,choice}` ·
`clarify.respond{request_id,question_id,answer}` · `sudo.respond{request_id,password}` ·
`secret.respond{request_id,value}`.

Each method reads a **different** key and *falls back to a default when it is missing* — a call
with the wrong key succeeds and means the wrong thing. `approval.respond` defaults to `deny`,
`clarify.respond` to an empty answer. Only `approval.respond` resolves its session explicitly;
the others find it through the gateway's pending-request registry. Send exactly these shapes
(verified live, see `HermesLiveTest.approvalResponseUsesTheServersParameterNames`).

A `clarify.request` is a **batch**: it carries `questions[]` (one entry for a single question),
each question has a `qid`, and `clarify.respond` resolves them one at a time by echoing that `qid`
as `question_id`. Every reply reports `remaining` — the qids still unanswered — and the tool is
only released when it is empty. Two failure modes matter:

- **An answer without `question_id` is accepted and ignored.** It returns `{"status":"ok"}` with no
  `remaining`, the question stays unanswered, and the tool eventually sees nothing at all. The
  card then claims an answer the agent never received.
- **`{"status":"expired"}`** means the request is no longer outstanding (answered elsewhere, or
  past the server-side deadline), so the card has to close rather than stay live.

A question that offers `choices` has to be answered with one of them: the tool strips a
`(Recommended)` decoration before the model sees the value, so the wire carries the decorated
label and the agent receives the bare one. Verified live end to end by
`HermesLiveTest.clarifyAnswersAreMatchedToTheirQuestion`, which watches `remaining` go `[q1]` →
`[]` and then checks the agent's finished text contains both options.

`approval.request` carries the allowed answers in `choices`, and `always` is **absent** when the
policy forbids a permanent allow:

```json
{"type":"approval.request",
 "payload":{"request_id":"…","description":"delete in root path",
            "command":"rm -rf /tmp/probe-dir","session_key":"…",
            "choices":["once","session","always","deny"]}}
```

An unanswered approval is **failed closed**: it timed out after ~60 s here, the tool returned
"blocked", and the turn ended normally — `responded` came back as `{"resolved":1}` (an integer,
not `true`) when a choice did arrive, and the response succeeds even for a request whose `choice`
the gateway ignored. The only proof that a grant was honoured is the tool actually running.

**Capabilities** `model.options` → `{providers:[{slug,name,is_current,models[],capabilities,featured_models}]}` ·
`model.set` · `tools.list` → `{toolsets:[{name,description,tool_count,enabled,tools[]}]}` ·
`tools.show` · `skills.manage` → `{skills:{category:[names]}}` ·
`mcp.servers.list` · `mcp.catalog` · `cron.manage` ·
`agents.list` → `{processes:[]}` · `insights.get` · `commands.catalog`.

**Config** `config.get{key}` (errors 4002 on unknown key) · `config.set{key,value}` ·
`config.show` · `model.options` · `reload.env`.

**Transport** `gateway.ping` (heartbeat, every 15 s) · `gateway.capabilities`.

## 6. Wire message shapes for history

`session.resume` / `session.history` / `session.branch` return a `messages`
array of durable rows:

```json
{"role":"user","text":"…","timestamp":1789342795.96,"row_id":11325}
{"role":"tool","name":"terminal","context":"echo X","args":{"command":"echo X"}}
{"role":"assistant","text":"DONE","timestamp":1789342801.58,"row_id":11328}
```

Note there is no tool *result* on rehydrated history rows — only the call.
Live `tool.complete` events carry `result`. The client renders history tool
rows as compact call cards and live ones with their result.
