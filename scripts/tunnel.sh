#!/usr/bin/env bash
#
# tunnel.sh — bring up the SSH port-forward used by the live gateway tests.
#
# The Hermes dev gateway on the home server binds to 127.0.0.1:9119, so the only
# way to reach it from a laptop is an SSH local forward. This script starts that
# forward in the background, waits until the gateway actually answers, and prints
# the WebSocket URL the tests want (see app/src/test/java/com/materialagent/live).
#
# Usage:
#   scripts/tunnel.sh            # start (idempotent) and print the WS URL
#   scripts/tunnel.sh status     # report whether the tunnel is up
#   scripts/tunnel.sh stop       # tear down a tunnel this script started
#
# Environment overrides (all optional):
#   HERMES_TUNNEL_HOST        ssh host alias            (default: home-server)
#   HERMES_TUNNEL_REMOTE_PORT gateway port on the server (default: 9119)
#   HERMES_TUNNEL_LOCAL_PORT  local port to bind        (default: 19119)
#   HERMES_TEST_TOKEN         loopback dev token        (no default — see below)
#   HERMES_TEST_TOKEN_FILE    file holding that token     (default: .hermes-test-token)
#
set -euo pipefail

SSH_HOST="${HERMES_TUNNEL_HOST:-home-server}"
REMOTE_PORT="${HERMES_TUNNEL_REMOTE_PORT:-9119}"
LOCAL_PORT="${HERMES_TUNNEL_LOCAL_PORT:-19119}"
TOKEN_FILE="${HERMES_TEST_TOKEN_FILE:-.hermes-test-token}"

# The token is deliberately not defaulted. This repository is public, and a
# working credential committed to a public repository is not private
# even when it only guards a loopback port — the port is one `ssh -L` away from
# being reachable. It is read from the environment, or from a gitignored file so
# a local checkout keeps working without writing it into a tracked file.
#
# A missing token is not fatal: the forward itself does not need one. It only
# means this script cannot print the `export HERMES_TEST_WS=...` line for you.
resolve_token() {
    if [[ -n "${HERMES_TEST_TOKEN:-}" ]]; then
        printf '%s' "$HERMES_TEST_TOKEN"
    elif [[ -f "$TOKEN_FILE" ]]; then
        tr -d '[:space:]' <"$TOKEN_FILE"
    fi
}

TOKEN="$(resolve_token)"

HEALTH_URL="http://127.0.0.1:${LOCAL_PORT}/api/health"
WS_URL="ws://127.0.0.1:${LOCAL_PORT}/api/ws?token=${TOKEN}"   # printed only when TOKEN is set

# State lives outside the repo so repeated runs never dirty the worktree.
STATE_DIR="${TMPDIR:-/tmp}"
STATE_DIR="${STATE_DIR%/}"
PIDFILE="${STATE_DIR}/materialagent-hermes-tunnel-${LOCAL_PORT}.pid"
LOGFILE="${STATE_DIR}/materialagent-hermes-tunnel-${LOCAL_PORT}.log"
WAIT_SECONDS="${HERMES_TUNNEL_WAIT_SECONDS:-20}"

# A single GET against /api/health. The gateway answers {"ok":true,...} once the
# forward is genuinely carrying traffic — a bound local port alone is not proof.
health_ok() {
    curl -fsS -m 3 "$HEALTH_URL" >/dev/null 2>&1
}

pid_alive() {
    [[ -n "${1:-}" ]] && kill -0 "$1" 2>/dev/null
}

read_pid() {
    [[ -f "$PIDFILE" ]] && tr -d '[:space:]' <"$PIDFILE" || true
}

wait_for_health() {
    local waited=0
    while (( waited < WAIT_SECONDS )); do
        health_ok && return 0
        sleep 0.5
        waited=$((waited + 1))
    done
    return 1
}

print_ready() {
    echo "tunnel ready  -> $HEALTH_URL"
    if [[ -n "$TOKEN" ]]; then
        echo "export HERMES_TEST_WS='$WS_URL'"
    else
        cat >&2 <<'USAGE'
The forward is up, but no gateway token is configured, so no HERMES_TEST_WS line
is printed. Supply one — never by committing it:

    printf '%s' '<your-token>' > .hermes-test-token   # gitignored
    # or: export HERMES_TEST_TOKEN='<your-token>'

The dev server reads its token from HERMES_DASHBOARD_SESSION_TOKEN.
USAGE
    fi
}

cmd_start() {
    # Idempotent path 1: something (this script or a hand-run ssh) already serves
    # the gateway locally — never start a second forward on the same port.
    if health_ok; then
        echo "already up"
        print_ready
        return 0
    fi

    # Idempotent path 2: we have a live pid but it has not finished connecting.
    local existing
    existing="$(read_pid)"
    if pid_alive "$existing"; then
        echo "ssh pid $existing is running, waiting for the gateway…"
        if wait_for_health; then
            print_ready
            return 0
        fi
        echo "pid $existing never became reachable; restarting it" >&2
        kill "$existing" 2>/dev/null || true
        rm -f "$PIDFILE"
    fi

    # -N: no remote command. ExitOnForwardFailure makes ssh die instead of
    # silently succeeding when the local port is taken.
    nohup ssh -N \
        -o ExitOnForwardFailure=yes \
        -o ServerAliveInterval=30 \
        -o ServerAliveCountMax=3 \
        -L "${LOCAL_PORT}:127.0.0.1:${REMOTE_PORT}" \
        "$SSH_HOST" >"$LOGFILE" 2>&1 &
    local pid=$!
    echo "$pid" >"$PIDFILE"

    if wait_for_health; then
        print_ready
        return 0
    fi

    echo "tunnel failed to come up within ${WAIT_SECONDS}s" >&2
    echo "--- ssh log ---" >&2
    tail -n 20 "$LOGFILE" >&2 || true
    echo "--- hint ---" >&2
    echo "check the host alias ('$SSH_HOST') in ~/.ssh/config and that 'hermes serve'" >&2
    echo "is listening on 127.0.0.1:${REMOTE_PORT} on that machine" >&2
    kill "$pid" 2>/dev/null || true
    rm -f "$PIDFILE"
    return 1
}

cmd_stop() {
    local pid
    pid="$(read_pid)"
    if ! pid_alive "$pid"; then
        if health_ok; then
            echo "gateway is reachable but no pidfile at $PIDFILE — not touching it"
            echo "stop it yourself if it is the tunnel you started"
            return 0
        fi
        echo "no tunnel running"
        rm -f "$PIDFILE" "$LOGFILE"
        return 0
    fi
    # Kill the ssh we started; its forward dies with it.
    kill "$pid" 2>/dev/null || true
    for _ in {1..20}; do
        pid_alive "$pid" || break
        sleep 0.25
    done
    pid_alive "$pid" && kill -9 "$pid" 2>/dev/null || true
    rm -f "$PIDFILE" "$LOGFILE"
    echo "tunnel stopped (pid $pid)"
}

cmd_status() {
    if health_ok; then
        echo "up      $(curl -fsS -m 3 "$HEALTH_URL" || echo '{}')"
        print_ready
    else
        echo "down    (no response from $HEALTH_URL)"
        return 1
    fi
}

case "${1:-start}" in
    start)  cmd_start ;;
    stop)   cmd_stop ;;
    status) cmd_status ;;
    *)      echo "usage: $0 [start|stop|status]" >&2; exit 2 ;;
esac
