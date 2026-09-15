#!/usr/bin/env python3
"""
A stand-in for the Hermes gateway, for screenshots and local UI work.

Speaks just enough of the protocol in `docs/PROTOCOL.md` for the app to connect,
list sessions, reopen one, run a streaming turn, raise an approval and a
clarify, and show the model / toolset / MCP catalogues. Everything it serves is
invented: the account is "demo", the address is the emulator's host alias, and
every session title and message body is made up here for screenshots. It never
talks to a real gateway and holds no real data.

Only the standard library is used, so it runs anywhere Python 3.9+ does:

    python3 scripts/demo-gateway.py --port 9119

then point the app at `http://10.0.2.2:9119` from an emulator (or
`http://127.0.0.1:9119` from the host) and sign in with any non-empty access
token, or with the password path, which accepts anything.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
import re
import secrets
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# ---------------------------------------------------------------------------
# Invented content.
# ---------------------------------------------------------------------------

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
REPLAY_EPOCH = "demo-epoch-1"

ACCOUNT = "demo"
HOST_LABEL = "10.0.2.2"
PROJECT = "demo/web"
CWD = "~/demo/web"
BRANCH = "main"
SERVER_VERSION = "0.0.0-demo"
MODEL = "demo-cloud/model-b"
PROVIDER = "Demo Cloud"

AUDIO_PATH = "/tmp/demo-gateway/voice-summary.wav"

SKIN = {
    "name": "demo",
    "colors": {
        "ui_accent": "#d8a657",
        "banner_accent": "#d8a657",
        "text": "#e8e3d3",
        "background": "#14161a",
    },
    "branding": {
        "agent_name": "Hermes Agent",
        "welcome": "Demo gateway",
        "response_label": "Agent",
        "prompt_symbol": "❯",
    },
    "light_colors": {"ui_accent": "#a3741f"},
}


def usage_block(context_used=38000, calls=3):
    """A token ledger that has real numbers in it."""
    return {
        "model": MODEL,
        "input": 12480,
        "output": 940,
        "reasoning": 260,
        "total": 13680,
        "calls": calls,
        "context_used": context_used,
        "context_max": 260000,
        "context_percent": int(round(context_used / 260000 * 100)),
        "cache_hit_pct": 62,
        "avg_tps": 41.6,
        "avg_latency_s": 1.9,
        "compressions": 0,
        "active_subagents": 0,
    }


def session_info(session, running=False, with_usage=True):
    return {
        "model": MODEL,
        "provider": PROVIDER,
        "reasoning_effort": "medium",
        "service_tier": "standard",
        "fast": False,
        "yolo": False,
        "approval_mode": "ask",
        "tools": {
            "set": [
                "terminal",
                "read_file",
                "write_file",
                "search_files",
                "fetch_url",
                "todo_write",
            ]
        },
        "skills": {
            "engineering": ["code-review", "release-checklist"],
            "writing": ["changelog-entry"],
        },
        "cwd": CWD,
        "branch": BRANCH,
        "project": PROJECT,
        "terminal_backend": "local",
        "personality": "concise",
        "running": running,
        "turn_started_at": time.time() if running else None,
        "title": session.title,
        "stored_session_id": session.stored_id,
        "desktop_contract": 3,
        "version": SERVER_VERSION,
        "usage": usage_block() if with_usage else {
            "calls": 0,
            "input": 0,
            "output": 0,
            "total": 0,
        },
        "profile_name": ACCOUNT,
        "mcp_servers": [
            {"name": "demo-docs", "transport": "stdio", "status": "running", "tools": ["docs.search", "docs.read"]},
            {"name": "demo-notes", "transport": "http", "status": "running", "tools": ["notes.list", "notes.create"]},
        ],
        "system_prompt": "You are Hermes, a demo agent used for screenshots.",
    }


# A transcript that reads like a real refactor session: a question, an answer,
# two tool calls, and a closing summary. Tool rows in history carry the call but
# never a result, exactly as the protocol describes.
REFACTOR_MESSAGES = [
    {
        "role": "user",
        "text": "The chat screen file has grown past 900 lines. Pull the transcript state out of it.",
        "timestamp": 0,
        "row_id": 3101,
    },
    {
        "role": "assistant",
        "text": "Found the reducer and the entry model still living beside the composables. "
        "They are pure state, so they belong in their own file.",
        "timestamp": 0,
        "row_id": 3102,
    },
    {
        "role": "tool",
        "name": "search_files",
        "context": "rg -n 'fun reduce|data class TranscriptEntry' app/src/main",
        "args": {"pattern": "fun reduce|data class TranscriptEntry", "path": "app/src/main"},
        "timestamp": 0,
        "row_id": 3103,
    },
    {
        "role": "tool",
        "name": "read_file",
        "context": "app/src/main/java/com/materialagent/ui/screens/chat/ChatScreen.kt lines 1-120",
        "args": {"path": "app/src/main/java/com/materialagent/ui/screens/chat/ChatScreen.kt", "offset": 1, "limit": 120},
        "timestamp": 0,
        "row_id": 3104,
    },
    {
        "role": "assistant",
        "text": "Moved `ChatReducer` and the transcript models into "
        "`data/chat/ChatReducer.kt`, leaving the screen with rendering only. "
        "The screen is down to 604 lines and the reducer has its own tests.",
        "timestamp": 0,
        "row_id": 3105,
    },
    {
        "role": "user",
        "text": "Good. Note what changed so the changelog can link it.",
        "timestamp": 0,
        "row_id": 3106,
    },
    {
        "role": "assistant",
        "text": "Changelog entry drafted under **Changed**: the transcript reducer is now a "
        "separate module, so it can be replayed from a recorded event log in tests.",
        "timestamp": 0,
        "row_id": 3107,
    },
]

RELEASE_MESSAGES = [
    {
        "role": "user",
        "text": "Walk the release checklist and tell me what is left.",
        "timestamp": 0,
        "row_id": 4001,
    },
    {
        "role": "assistant",
        "text": "Checked the four items that need a human: the changelog is current, the "
        "version bump is staged, the tag name matches the workflow's expectation, and "
        "the release notes link the protocol changes.",
        "timestamp": 0,
        "row_id": 4002,
    },
    {
        "role": "tool",
        "name": "terminal",
        "context": "git log --oneline -5",
        "args": {"command": "git log --oneline -5"},
        "timestamp": 0,
        "row_id": 4003,
    },
    {
        "role": "assistant",
        "text": "One thing left: the protocol notes still describe the old framing example.",
        "timestamp": 0,
        "row_id": 4004,
    },
]

DIGEST_MESSAGES = [
    {
        "role": "user",
        "text": "Summarise this week's merged work by area.",
        "timestamp": 0,
        "row_id": 5001,
    },
    {
        "role": "assistant",
        "text": "Three areas moved. Chat gained the split reducer, the theme gained the "
        "contrast pass, and the update path gained a checksum check before install.",
        "timestamp": 0,
        "row_id": 5002,
    },
]

PALETTE_MESSAGES = [
    {
        "role": "user",
        "text": "Give me three tints for the accent that still pass contrast on the dark surface.",
        "timestamp": 0,
        "row_id": 6001,
    },
    {
        "role": "assistant",
        "text": "Suggested #d8a657 at 4.9:1, #c98f4a at 4.6:1 and #b8813f at 5.2:1 against the "
        "dark surface. All three clear 4.5:1 for body text and 3:1 for large text.",
        "timestamp": 0,
        "row_id": 6002,
    },
]

DOCS_MESSAGES = [
    {
        "role": "user",
        "text": "Tidy the protocol notes: move the traps into their own section.",
        "timestamp": 0,
        "row_id": 7001,
    },
    {
        "role": "assistant",
        "text": "Done. The traps are a list of their own now, each one with the symptom to "
        "look for and the parameter that actually gets read.",
        "timestamp": 0,
        "row_id": 7002,
    },
]

AUDIT_MESSAGES = [
    {
        "role": "user",
        "text": "Run the nightly audit.",
        "timestamp": 0,
        "row_id": 8001,
    },
    {
        "role": "assistant",
        "text": "Audit complete: 0 failing checks, 2 advisories, no new dependencies.",
        "timestamp": 0,
        "row_id": 8002,
    },
]

PROVIDERS = [
    {
        "slug": "demo-cloud",
        "name": "Demo Cloud",
        "is_current": True,
        "authenticated": True,
        "auth_type": "api key",
        "models": [
            "model-a",
            "model-b",
            "model-c",
            "model-a-mini",
            "model-a-thinking",
            "model-b-mini",
            "model-c-pro",
            "model-d",
        ],
    },
    {
        "slug": "demo-local",
        "name": "Demo Local",
        "is_current": False,
        "authenticated": True,
        "auth_type": "local runtime",
        "models": [
            "model-a",
            "model-b",
            "model-mini",
            "model-tiny",
            "model-plus",
            "model-max",
        ],
    },
    {
        "slug": "demo-archive",
        "name": "Demo Archive",
        "is_current": False,
        "authenticated": False,
        "auth_type": "api key",
        "warning": "This provider is not signed in.",
        "models": ["model-legacy-1", "model-legacy-2"],
    },
]

TOOLSETS = [
    {
        "name": "files",
        "description": "Read, write and search files in the workspace.",
        "tool_count": 4,
        "enabled": True,
        "tools": ["read_file", "write_file", "search_files", "list_dir"],
    },
    {
        "name": "terminal",
        "description": "Run commands in the session workspace.",
        "tool_count": 2,
        "enabled": True,
        "tools": ["terminal", "terminal_session"],
    },
    {
        "name": "web",
        "description": "Fetch and read pages from the network.",
        "tool_count": 3,
        "enabled": True,
        "tools": ["fetch_url", "search_web", "read_page"],
    },
    {
        "name": "notes",
        "description": "Keep a running todo list for long tasks.",
        "tool_count": 2,
        "enabled": False,
        "tools": ["todo_write", "todo_read"],
    },
]

SKILLS = {
    "engineering": ["code-review", "release-checklist", "protocol-triage"],
    "writing": ["changelog-entry", "commit-message"],
}

MCP_SERVERS = [
    {"name": "demo-docs", "transport": "stdio", "status": "running", "tools": ["docs.search", "docs.read"]},
    {"name": "demo-notes", "transport": "http", "status": "running", "tools": ["notes.list", "notes.create"]},
    {"name": "demo-calendar", "transport": "stdio", "status": "stopped", "tools": ["calendar.upcoming"]},
]


# ---------------------------------------------------------------------------
# In-memory state. Nothing here outlives the process.
# ---------------------------------------------------------------------------


class Session:
    def __init__(self, runtime_id, stored_id, title, source, messages, started_at):
        self.runtime_id = runtime_id
        self.stored_id = stored_id
        self.title = title
        self.source = source
        self.messages = list(messages)
        self.started_at = started_at
        self.seq = 0
        self.running = False
        self.stop = threading.Event()
        self.pending = {}

    @property
    def preview(self):
        for row in reversed(self.messages):
            if row.get("role") == "user" and row.get("text"):
                return row["text"]
        return ""

    @property
    def message_count(self):
        return len(self.messages)


def _stamp(offset_seconds):
    return time.time() - offset_seconds


def build_sessions():
    seeds = [
        ("20260106_093012_demo01", "Release checklist", "mobile", RELEASE_MESSAGES, 8 * 3600),
        ("20260106_081545_demo02", "Weekly digest", "cli", DIGEST_MESSAGES, 11 * 3600),
        ("20260105_171230_demo03", "Refactor notes", "mobile", REFACTOR_MESSAGES, 26 * 3600),
        ("20260105_140210_demo04", "Colour palette ideas", "mobile", PALETTE_MESSAGES, 29 * 3600),
        ("20260104_190005_demo05", "Docs tidy-up", "telegram", DOCS_MESSAGES, 2 * 86400),
    ]
    sessions = []
    for index, (stored_id, title, source, rows, ago) in enumerate(seeds):
        started = _stamp(ago)
        messages = [
            dict(row, timestamp=started + position * 45)
            for position, row in enumerate(rows)
        ]
        sessions.append(
            Session(f"demo{index:02d}a1b2", stored_id, title, source, messages, started)
        )
    # Three runs of one scheduled job, so the inbox has a group to fold.
    for day in range(4, 7):
        stored_id = f"cron_nightly-audit_202601{day:02d}_020000_demo"
        started = _stamp((7 - day) * 86400 + 6 * 3600)
        sessions.append(
            Session(
                f"cron{day}9f3c",
                stored_id,
                f"Nightly audit · {day:02d} Jan 02:00",
                "cron",
                [dict(row, timestamp=started + i * 30) for i, row in enumerate(AUDIT_MESSAGES)],
                started,
            )
        )
    return sessions


SESSIONS = build_sessions()
SESSIONS_BY_RUNTIME = {s.runtime_id: s for s in SESSIONS}
SESSIONS_BY_STORED = {s.stored_id: s for s in SESSIONS}
STATE_LOCK = threading.Lock()


def summary(session):
    return {
        "id": session.stored_id,
        "title": session.title,
        "preview": session.preview,
        "started_at": session.started_at,
        "message_count": session.message_count,
        "source": session.source,
    }


def history_rows(session):
    return [
        {
            "role": row["role"],
            "text": row.get("text"),
            "name": row.get("name"),
            "context": row.get("context"),
            "args": row.get("args"),
            "timestamp": row["timestamp"],
            "row_id": row.get("row_id"),
        }
        for row in session.messages
    ]


def last_turn_started(session):
    for row in reversed(session.messages):
        if row.get("role") == "user" and row.get("timestamp"):
            return row["timestamp"]
    return session.started_at


# ---------------------------------------------------------------------------
# WebSocket plumbing (RFC 6455, server side, standard library only).
# ---------------------------------------------------------------------------

CONNS = []
CONNS_LOCK = threading.Lock()
TICKETS = set()


def frame(payload: bytes, opcode=0x1) -> bytes:
    size = len(payload)
    head = bytes([0x80 | opcode])
    if size < 126:
        head += bytes([size])
    elif size < 65536:
        head += bytes([126]) + struct.pack(">H", size)
    else:
        head += bytes([127]) + struct.pack(">Q", size)
    return head + payload


def read_frame(rfile):
    head = rfile.read(2)
    if not head or len(head) < 2:
        return None
    b1, b2 = head[0], head[1]
    opcode = b1 & 0x0F
    masked = bool(b2 & 0x80)
    size = b2 & 0x7F
    if size == 126:
        size = struct.unpack(">H", rfile.read(2))[0]
    elif size == 127:
        size = struct.unpack(">Q", rfile.read(8))[0]
    mask = rfile.read(4) if masked else None
    payload = rfile.read(size) if size else b""
    if mask:
        payload = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))
    return opcode, payload or b""


class Connection:
    def __init__(self, wfile):
        self.wfile = wfile
        self.lock = threading.Lock()
        self.alive = True

    def send(self, obj):
        self.raw(json.dumps(obj, separators=(",", ":")).encode())

    def raw(self, payload, opcode=0x1):
        with self.lock:
            if not self.alive:
                return
            try:
                self.wfile.write(frame(payload, opcode))
                self.wfile.flush()
            except Exception:
                self.alive = False

    def event(self, event_type, session=None, payload=None, seq=None):
        params = {"type": event_type}
        if session is not None:
            params["session_id"] = session.runtime_id
            params["seq"] = seq if seq is not None else session.seq
        if payload is not None:
            params["payload"] = payload
        self.send({"jsonrpc": "2.0", "method": "event", "params": params})

    def reply(self, request_id, result):
        self.send({"jsonrpc": "2.0", "id": request_id, "result": result})

    def fail(self, request_id, code, message):
        self.send({"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}})


def broadcast(connection, event_type, session=None, payload=None):
    """One session-scoped event, to every socket. The app ignores other ids."""
    if session is None:
        connection.event(event_type, payload=payload)
        return
    with STATE_LOCK:
        session.seq += 1
        seq = session.seq
    with CONNS_LOCK:
        targets = list(CONNS)
    for conn in targets:
        if conn.alive:
            conn.event(event_type, session=session, payload=payload, seq=seq)


def broadcast_global(connection, event_type, payload=None):
    with CONNS_LOCK:
        targets = list(CONNS)
    for conn in targets:
        if conn.alive:
            conn.event(event_type, payload=payload)


# ---------------------------------------------------------------------------
# Turn scripts. The prompt decides which one runs, so a screenshot session can
# be driven from the composer with a single word.
# ---------------------------------------------------------------------------


def text_chunks(text, size=14):
    words = re.findall(r"\S+\s*", text)
    chunk = ""
    for word in words:
        chunk += word
        if len(chunk) >= size:
            yield chunk
            chunk = ""
    if chunk:
        yield chunk


def stream_text(connection, session, text, per_chunk=0.16, stop_check=True):
    for chunk in text_chunks(text):
        if stop_check and session.stop.is_set():
            return
        broadcast(connection, "message.delta", session, {"text": chunk})
        time.sleep(per_chunk)


def wait_for_request(session, request_id, timeout=600):
    with STATE_LOCK:
        entry = session.pending.setdefault(request_id, {"event": threading.Event(), "answers": {}})
    return entry, entry["event"].wait(timeout)


def complete_turn(connection, session, body, running_info=True, with_title=None):
    session.running = False
    broadcast(connection, "message.complete", session, {
        "text": body,
        "usage": usage_block(),
        "status": "complete",
        "reasoning": "",
    })
    if with_title:
        session.title = with_title
        broadcast(connection, "session.title", session, {"title": with_title})
    broadcast(connection, "session.info", session, session_info(session, running=running_info))
    broadcast_global(connection, "sessions.changed", {})


def default_turn(connection, session, prompt):
    body = (
        "Read the request as a change to the demo workspace. "
        "Three files matter here: the screen that renders the list, the reducer "
        "that owns its state, and the changelog that records the change.\n\n"
    )
    stream_text(connection, session, body)
    broadcast(connection, "tool.start", session, {
        "tool_id": "call-1",
        "name": "search_files",
        "context": "rg -n 'TodoItem|pendingInteractions' app/src/main",
        "args": {"pattern": "TodoItem|pendingInteractions", "path": "app/src/main"},
    })
    time.sleep(1.1)
    broadcast(connection, "tool.complete", session, {
        "tool_id": "call-1",
        "name": "search_files",
        "args": {"pattern": "TodoItem|pendingInteractions", "path": "app/src/main"},
        "duration_s": 1.1,
        "result": "app/src/main/java/com/materialagent/data/chat/ChatReducer.kt:8\n"
                  "app/src/main/java/com/materialagent/data/chat/ChatModels.kt:4\n"
                  "app/src/main/java/com/materialagent/ui/screens/chat/ChatEntries.kt:22",
    })
    tail = (
        "\nBoth live in the reducer already, so nothing there needs to move. "
        "The screen only draws them.\n\n"
        "Landed on one change: the row keeps a stable key across a re-render, which "
        "stops the list from re-animating when a single tool result arrives."
    )
    stream_text(connection, session, tail)
    complete_turn(connection, session, body + tail, with_title="Refactor notes")


def approval_turn(connection, session, prompt):
    body = (
        "I can install the demo project's dependencies to check that the lockfile "
        "resolves. That runs a command in your workspace, so it needs your OK "
        "first.\n\n"
    )
    stream_text(connection, session, body)
    request_id = "demo-approval-1"
    entry, _ = wait_for_request(session, request_id, timeout=0.01)
    broadcast(connection, "approval.request", session, {
        "request_id": request_id,
        "description": "run a command in the demo workspace",
        "command": f"npm install --prefix {CWD}",
        "session_key": session.runtime_id,
        "choices": ["once", "session", "always", "deny"],
        "allow_permanent": True,
    })
    entry["event"].wait(600)
    if session.stop.is_set():
        return
    broadcast(connection, "tool.start", session, {
        "tool_id": "call-install",
        "name": "terminal",
        "context": f"npm install --prefix {CWD}",
        "args": {"command": f"npm install --prefix {CWD}", "cwd": CWD},
    })
    time.sleep(1.4)
    broadcast(connection, "tool.complete", session, {
        "tool_id": "call-install",
        "name": "terminal",
        "args": {"command": f"npm install --prefix {CWD}", "cwd": CWD},
        "duration_s": 6.2,
        "result": "added 214 packages, and audited 215 packages in 6s\n\n"
                  "34 packages are looking for funding\nfound 0 vulnerabilities",
    })
    tail = "\nInstalled cleanly: 214 packages, and no vulnerabilities reported."
    stream_text(connection, session, tail)
    complete_turn(connection, session, body + tail, with_title="Install the demo deps")


def clarify_turn(connection, session, prompt):
    body = "Two things before I write it:\n\n"
    stream_text(connection, session, body)
    request_id = "demo-clarify-1"
    entry, _ = wait_for_request(session, request_id, timeout=0.01)
    with STATE_LOCK:
        entry["qids"] = ["q1", "q2"]
    broadcast(connection, "clarify.request", session, {
        "request_id": request_id,
        "questions": [
            {
                "qid": "q1",
                "question": "Which name should the new module use?",
                "choices": ["demo-agent-core", "demo-agent-client"],
                "multi_select": False,
            },
            {
                "qid": "q2",
                "question": "Should its changelog entry go under Added or Changed?",
                "choices": ["Added", "Changed"],
                "multi_select": False,
            },
        ],
    })
    deadline = time.time() + 600
    while time.time() < deadline:
        with STATE_LOCK:
            answers = dict(entry["answers"])
        if "q1" in answers and "q2" in answers:
            break
        time.sleep(0.2)
    if session.stop.is_set():
        return
    tail = (
        "\nUsing `demo-agent-core`, with the changelog entry under Added. "
        "Both answers are in the draft now."
    )
    stream_text(connection, session, tail)
    complete_turn(connection, session, body + tail, with_title="Name the new module")


def audio_turn(connection, session, prompt):
    body = (
        "There is no text to read here — the summary is short enough to listen to, "
        "so I rendered it instead of typing it out.\n\n"
    )
    stream_text(connection, session, body)
    tail = (
        "Three items: the reducer moved, the contrast pass landed, and the update "
        "path now verifies a checksum before it installs anything.\n\n"
        f"MEDIA:{AUDIO_PATH} Voice summary (12s)\n"
    )
    for chunk in text_chunks(tail):
        broadcast(connection, "message.delta", session, {"text": chunk})
        time.sleep(0.05)
    complete_turn(connection, session, body + tail, with_title="Voice summary")


LONG_BODY = (
    "Working through the migration in order, one concern at a time, so the diff stays "
    "reviewable. First the model layer: the entry type gains a stable id and the reducer "
    "stops keying rows by their index, which is what made a tool result re-animate the "
    "whole list. Then the screen: it keeps rendering, and stops owning any state that "
    "outlives a recomposition. Then the tests: the reducer is pure, so a recorded event "
    "log replays through it and the expected transcript is asserted row by row. Then the "
    "changelog, the version bump, and the release notes, in that order, because each one "
    "needs the previous to be true. What is left after that is the part that always takes "
    "longest: reading the whole diff once, slowly, looking for the change that was not "
    "asked for. And while that runs, the honest summary is that nothing is decided yet."
)


def long_turn(connection, session, prompt):
    session.stop.clear()
    for chunk in text_chunks(LONG_BODY, size=18):
        if session.stop.is_set():
            return
        broadcast(connection, "message.delta", session, {"text": chunk})
        time.sleep(0.45)
    complete_turn(connection, session, LONG_BODY, with_title="Migration walkthrough")


def pick_turn(prompt):
    lowered = prompt.lower()
    if "install" in lowered or "approval" in lowered or "run a command" in lowered:
        return approval_turn
    if "clarify" in lowered or "ask me" in lowered or "question" in lowered:
        return clarify_turn
    if "voice" in lowered or "audio" in lowered or "out loud" in lowered:
        return audio_turn
    if "slowly" in lowered or "long" in lowered or "stream" in lowered or "walk through" in lowered:
        return long_turn
    return default_turn


def run_turn(connection, session, prompt):
    session.stop.clear()
    session.running = True
    broadcast(connection, "message.start", session, None)
    try:
        pick_turn(prompt)(connection, session, prompt)
    except Exception as error:  # keep the socket usable after a script bug
        print(f"[demo-gateway] turn failed: {error!r}", flush=True)
        broadcast(connection, "turn.error", session, {"text": "The demo turn failed."})
        session.running = False


# ---------------------------------------------------------------------------
# RPC methods.
# ---------------------------------------------------------------------------


def m_session_list(params):
    with STATE_LOCK:
        rows = [summary(session) for session in SESSIONS]
    rows.sort(key=lambda row: row["started_at"], reverse=True)
    return {"sessions": rows}


def m_session_most_recent(params):
    with STATE_LOCK:
        if not SESSIONS:
            return {}
        newest = max(SESSIONS, key=lambda item: item.started_at)
    return {
        "session_id": newest.stored_id,
        "title": newest.title,
        "started_at": newest.started_at,
        "source": newest.source,
    }


def m_session_create(params):
    with STATE_LOCK:
        index = len(SESSIONS) + 1
        runtime_id = f"demo{secrets.token_hex(4)}"
        stored_id = time.strftime("%Y%m%d_%H%M%S") + f"_demo{index:02d}"
        session = Session(
            runtime_id,
            stored_id,
            params.get("title") or "",
            params.get("source") or "mobile",
            [],
            time.time(),
        )
        SESSIONS.append(session)
        SESSIONS_BY_RUNTIME[runtime_id] = session
        SESSIONS_BY_STORED[stored_id] = session
    return {
        "session_id": runtime_id,
        "stored_session_id": stored_id,
        "message_count": 0,
        "messages": [],
        "info": sparse_info(session),
    }


def sparse_info(session):
    return {
        "model": MODEL,
        "cwd": CWD,
        "branch": BRANCH,
        "project": PROJECT,
        "profile_name": ACCOUNT,
        "tools": {},
        "skills": {},
        "lazy": True,
        "desktop_contract": 3,
    }


def m_session_resume(params):
    stored_id = params.get("session_id") or ""
    with STATE_LOCK:
        session = SESSIONS_BY_STORED.get(stored_id)
    if session is None:
        raise LookupError("session not found")
    return {
        "session_id": session.runtime_id,
        "session_key": session.runtime_id,
        "messages": history_rows(session),
        "message_count": session.message_count,
        "messages_omitted": 0,
        "running": False,
        "turn_started_at": last_turn_started(session),
        "started_at": session.started_at,
        "status": "resumed",
        "resumed": True,
        "info": session_info(session, running=False),
        "title": session.title,
    }


def m_session_history(params):
    session = SESSIONS_BY_RUNTIME.get(params.get("session_id") or "")
    if session is None:
        raise LookupError("session not found")
    return {"count": session.message_count, "messages": history_rows(session)}


def m_session_title(params):
    session = SESSIONS_BY_STORED.get(params.get("session_id") or "")
    if session is None:
        session = SESSIONS_BY_RUNTIME.get(params.get("session_id") or "")
    if session is None:
        raise LookupError("session not found")
    session.title = params.get("title") or session.title
    broadcast_global(None, "sessions.changed", {})
    return {"pending": False, "title": session.title}


def m_session_close(params):
    session = SESSIONS_BY_RUNTIME.get(params.get("session_id") or "")
    if session is None:
        return {"closed": False}
    return {"closed": True}


def m_session_delete(params):
    stored_id = params.get("session_id") or ""
    with STATE_LOCK:
        session = SESSIONS_BY_STORED.pop(stored_id, None)
        if session is not None:
            SESSIONS.remove(session)
            SESSIONS_BY_RUNTIME.pop(session.runtime_id, None)
    return {"deleted": session is not None}


def m_session_branch(params):
    source = SESSIONS_BY_RUNTIME.get(params.get("session_id") or "")
    if source is None:
        raise LookupError("session not found")
    created = m_session_create({"source": source.source})
    branch = SESSIONS_BY_RUNTIME[created["session_id"]]
    branch.title = f"{source.title} #2"
    branch.messages = [dict(row) for row in source.messages]
    created["messages"] = history_rows(branch)
    created["message_count"] = branch.message_count
    created["info"] = sparse_info(branch)
    return created


def m_session_interrupt(params):
    session = SESSIONS_BY_RUNTIME.get(params.get("session_id") or "")
    if session is not None:
        session.stop.set()
        session.running = False
    return {"status": "interrupted"}


def m_session_steer(params):
    return {"steered": True}


def m_session_usage(params):
    return usage_block()


def m_session_status(params):
    session = SESSIONS_BY_RUNTIME.get(params.get("session_id") or "")
    if session is None:
        return {"output": "No such session."}
    return {
        "output": (
            f"Session {session.stored_id}\n"
            f"Model {MODEL} ({PROVIDER})\n"
            f"Project {PROJECT} on branch {BRANCH}\n"
            f"Messages {session.message_count}"
        )
    }


def m_session_events_since(params):
    return {"events": [], "epoch": REPLAY_EPOCH}


def m_model_options(params):
    return {"providers": PROVIDERS}


def m_model_set(params):
    return {"ok": True, "model": params.get("model")}


def m_config_set(params):
    return {"key": params.get("key"), "value": params.get("value"), "ok": True}


def m_tools_list(params):
    return {"toolsets": TOOLSETS}


def m_tools_configure(params):
    name = params.get("name")
    enabled = bool(params.get("enabled"))
    for toolset in TOOLSETS:
        if toolset["name"] == name:
            toolset["enabled"] = enabled
    return {"ok": True, "name": name, "enabled": enabled}


def m_skills_manage(params):
    return {"skills": SKILLS}


def m_mcp_servers_list(params):
    return {"servers": MCP_SERVERS}


def m_agents_list(params):
    return {"processes": []}


def m_approval_pending(params):
    return {"pending": []}


def m_approval_respond(params):
    session_id = params.get("session_id")
    request_id = params.get("request_id") or ""
    session = SESSIONS_BY_RUNTIME.get(session_id) if session_id else None
    if session is None:
        session = next((item for item in SESSIONS if request_id in item.pending), None)
    if session is not None:
        with STATE_LOCK:
            entry = session.pending.get(request_id)
        if entry is not None:
            entry["answers"]["choice"] = params.get("choice")
            entry["event"].set()
    return {"resolved": 1}


def m_clarify_respond(params):
    request_id = params.get("request_id") or ""
    question_id = params.get("question_id") or ""
    answer = params.get("answer") or ""
    session = next((item for item in SESSIONS if request_id in item.pending), None)
    if session is None:
        return {"status": "expired"}
    with STATE_LOCK:
        entry = session.pending.get(request_id)
        if entry is None:
            return {"status": "expired"}
        if question_id:
            entry["answers"][question_id] = answer
        remaining = [
            qid for qid in entry.get("qids", [])
            if qid not in entry["answers"]
        ]
        entry["event"].set()
    return {"status": "ok", "remaining": remaining}


def m_sudo_respond(params):
    return {"status": "ok"}


def m_secret_respond(params):
    return {"status": "ok"}


def m_gateway_ping(params):
    return {"ok": True, "ts": time.time()}


def m_gateway_capabilities(params):
    return {"methods": ["session.list", "session.resume", "prompt.submit"], "version": SERVER_VERSION}


DISPATCH = {
    "gateway.ping": m_gateway_ping,
    "gateway.capabilities": m_gateway_capabilities,
    "session.list": m_session_list,
    "session.most_recent": m_session_most_recent,
    "session.create": m_session_create,
    "session.resume": m_session_resume,
    "session.history": m_session_history,
    "session.title": m_session_title,
    "session.close": m_session_close,
    "session.delete": m_session_delete,
    "session.branch": m_session_branch,
    "session.interrupt": m_session_interrupt,
    "session.steer": m_session_steer,
    "session.usage": m_session_usage,
    "session.status": m_session_status,
    "session.events.since": m_session_events_since,
    "model.options": m_model_options,
    "model.set": m_model_set,
    "config.set": m_config_set,
    "tools.list": m_tools_list,
    "tools.configure": m_tools_configure,
    "skills.manage": m_skills_manage,
    "mcp.servers.list": m_mcp_servers_list,
    "agents.list": m_agents_list,
    "approval.pending": m_approval_pending,
    "approval.respond": m_approval_respond,
    "clarify.respond": m_clarify_respond,
    "sudo.respond": m_sudo_respond,
    "secret.respond": m_secret_respond,
}


def handle_rpc(connection, raw):
    try:
        message = json.loads(raw)
    except ValueError:
        return
    request_id = message.get("id")
    method = message.get("method")
    params = message.get("params") or {}
    if not method:
        return

    if method == "prompt.submit":
        session = SESSIONS_BY_RUNTIME.get(params.get("session_id") or "")
        if session is None:
            connection.fail(request_id, 4001, "session not found")
            return
        connection.reply(request_id, {"status": "streaming"})
        threading.Thread(
            target=run_turn,
            args=(connection, session, params.get("text") or ""),
            daemon=True,
        ).start()
        return

    handler = DISPATCH.get(method)
    if handler is None:
        connection.fail(request_id, -32601, f"unknown method: {method}")
        return
    try:
        connection.reply(request_id, handler(params))
    except LookupError as error:
        connection.fail(request_id, 4001, str(error) or "session not found")
    except Exception as error:  # pragma: no cover - surfaced to the client
        connection.fail(request_id, 4000, f"{error!r}")


# ---------------------------------------------------------------------------
# The audio file the audio turn points at. Generated, never read from disk.
# ---------------------------------------------------------------------------


def build_wav(seconds=12, rate=22050):
    samples = bytearray()
    total = int(seconds * rate)
    for index in range(total):
        t = index / rate
        envelope = min(1.0, t / 0.25) * math.exp(-0.9 * (t % 1.5))
        value = 0.35 * envelope * math.sin(2 * math.pi * 392 * t)
        value += 0.18 * envelope * math.sin(2 * math.pi * 588 * t)
        samples += struct.pack("<h", int(max(-1.0, min(1.0, value)) * 32767))
    data = bytes(samples)
    return (
        b"RIFF"
        + struct.pack("<I", 36 + len(data))
        + b"WAVEfmt "
        + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
        + b"data"
        + struct.pack("<I", len(data))
        + data
    )


WAV_BYTES = build_wav()


# ---------------------------------------------------------------------------
# HTTP + WebSocket endpoint.
# ---------------------------------------------------------------------------


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "demo-gateway/1.0"

    def log_message(self, fmt, *args):
        print("[demo-gateway] " + (fmt % args), flush=True)

    # -- helpers ----------------------------------------------------------

    def send_json(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_empty(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    # -- routes -----------------------------------------------------------

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/api/ws":
            return self.handle_ws(url)
        if url.path == "/api/files/stream":
            return self.handle_audio(url)
        if url.path == "/api/files/download":
            return self.handle_download(url)
        if url.path == "/api/auth/providers":
            return self.send_json(200, {
                "providers": [
                    {"name": "password", "label": "Dashboard password", "supports_password": True}
                ]
            })
        if url.path == "/api/auth/providers/":
            return self.send_json(200, {"providers": []})
        return self.send_json(200, {"name": "demo-gateway", "version": SERVER_VERSION})

    def do_POST(self):
        url = urlparse(self.path)
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if url.path == "/auth/password-login":
            body = json.dumps({"ok": True, "username": ACCOUNT}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Set-Cookie", "hermes_session=demo-session; Path=/; HttpOnly")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if url.path == "/api/auth/ws-ticket":
            ticket = secrets.token_hex(16)
            TICKETS.add(ticket)
            return self.send_json(200, {"ticket": ticket, "expires_in": 30})
        return self.send_empty(404)

    # -- files ------------------------------------------------------------

    def handle_audio(self, url):
        """The player's source: the generated WAV, with byte ranges."""
        total = len(WAV_BYTES)
        start, end = 0, total - 1
        range_header = self.headers.get("Range")
        status = 200
        if range_header:
            match = re.match(r"bytes=(\d*)-(\d*)", range_header)
            if match:
                if match.group(1):
                    start = int(match.group(1))
                if match.group(2):
                    end = min(int(match.group(2)), total - 1)
                status = 206
        body = WAV_BYTES[start:end + 1]
        self.send_response(status)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(len(body)))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        self.end_headers()
        self.wfile.write(body)

    def handle_download(self, url):
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Disposition", 'attachment; filename="voice-summary.wav"')
        self.send_header("Content-Length", str(len(WAV_BYTES)))
        self.end_headers()
        self.wfile.write(WAV_BYTES)

    # -- websocket --------------------------------------------------------

    def handle_ws(self, url):
        query = parse_qs(url.query)
        token = (query.get("token") or [""])[0]
        ticket = (query.get("ticket") or [""])[0]
        # The demo gateway accepts any non-empty token, and a ticket it minted.
        # There is no real credential here to be wrong.
        if not token and ticket not in TICKETS:
            self.send_empty(403)
            self.close_connection = True
            return
        TICKETS.discard(ticket)

        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            self.send_empty(400)
            self.close_connection = True
            return
        accept = base64.b64encode(
            hashlib.sha1((key + WS_GUID).encode()).digest()
        ).decode()
        self.wfile.write(
            (
                "HTTP/1.1 101 Switching Protocols\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Accept: {accept}\r\n"
                "\r\n"
            ).encode()
        )
        self.wfile.flush()
        self.close_connection = True

        connection = Connection(self.wfile)
        with CONNS_LOCK:
            CONNS.append(connection)

        connection.event("gateway.ready", payload={
            "skin": SKIN,
            "change_events": ["sessions.changed", "skin.changed"],
            "heartbeat": True,
            "replay_epoch": REPLAY_EPOCH,
        })

        try:
            while connection.alive:
                incoming = read_frame(self.rfile)
                if incoming is None:
                    break
                opcode, payload = incoming
                if opcode == 0x8:
                    break
                if opcode == 0x9:
                    connection.raw(payload, opcode=0xA)
                    continue
                if opcode != 0x1:
                    continue
                for line in payload.decode("utf-8", "replace").splitlines():
                    if line.strip():
                        handle_rpc(connection, line.strip())
        except Exception as error:
            print(f"[demo-gateway] socket ended: {error!r}", flush=True)
        finally:
            connection.alive = False
            with CONNS_LOCK:
                if connection in CONNS:
                    CONNS.remove(connection)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    parser = argparse.ArgumentParser(description="Hermes-compatible demo gateway")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9119)
    args = parser.parse_args()

    server = Server((args.host, args.port), Handler)
    print(
        f"[demo-gateway] listening on http://{args.host}:{args.port} "
        f"(emulator: http://{HOST_LABEL}:{args.port})",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
